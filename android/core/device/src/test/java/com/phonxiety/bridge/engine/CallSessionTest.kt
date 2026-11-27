package com.phonxiety.bridge.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The admission and tombstone decisions through the production call session:
 * a duplicate or conflicting dial is refused by the same monitor that admits
 * it, and a failed durable write never drops the owned intent.
 */
class CallSessionTest {
    private fun session(store: FakeCallStore = FakeCallStore()): CallSession {
        val owner = "com.phonxiety.bridge"
        return CallSession(store, owner, clock = { 1_000L })
    }

    private fun CallSession.dial(
        callId: String,
        destination: String,
        linkUp: Boolean = true,
        running: Boolean = true,
        unrelated: Boolean = false
    ): CallSession.DialOutcome = beginDial(
        callId = callId,
        destination = destination,
        phoneAccount = null,
        unrelatedCall = { unrelated },
        linkUp = { linkUp },
        running = { running }
    )

    @Test
    fun theSameDialTwiceIsAReplayAndReservesOnce() {
        val store = FakeCallStore()
        val session = session(store)

        session.dial("call-1", "+998901234567")
        val repeated = session.dial("call-1", "+998901234567")

        assertTrue(repeated is CallSession.DialOutcome.Duplicate)
        assertEquals("dialing", (repeated as CallSession.DialOutcome.Duplicate).state)
        assertEquals(listOf("call-1"), store.reserveCalls)
    }

    @Test
    fun aConflictingDestinationForTheOwnedIdIsRefused() {
        val store = FakeCallStore()
        val session = session(store)

        session.dial("call-1", "+998901234567")
        try {
            session.dial("call-1", "+998901234568")
            fail("a conflicting destination must be refused")
        } catch (e: CallSession.DialRefused) {
            assertEquals("duplicate_conflict", e.code)
        }

        assertEquals(listOf("call-1"), store.reserveCalls)
        assertEquals("+998901234567", session.owned?.destination)
    }

    @Test
    fun aDifferentCallWhileOwnedIsBusy() {
        val session = session()
        session.dial("call-1", "+998901234567")

        try {
            session.dial("call-2", "+998901234568")
            fail("a second call must be refused")
        } catch (e: CallSession.DialRefused) {
            assertEquals("busy", e.code)
        }
    }

    @Test
    fun aPersistenceFailureLeavesNothingOwned() {
        val store = FakeCallStore().apply { failReserve = true }
        val session = session(store)

        try {
            session.dial("call-1", "+998901234567")
            fail("a failed reservation must refuse the dial")
        } catch (e: CallSession.DialRefused) {
            assertEquals("state_persist_failed", e.code)
        }

        assertNull(session.owned)
        assertTrue(session.recent.isEmpty())
    }

    @Test
    fun aFailedTombstoneKeepsThePendingCall() {
        val store = FakeCallStore().apply { failOrphan = true }
        val session = session(store)
        session.dial("call-1", "+998901234567")

        try {
            synchronized(session.lock) { session.planHangupLocked("call-1") }
            fail("a failed tombstone must refuse the hang-up")
        } catch (e: CallSession.DialRefused) {
            assertEquals("state_persist_failed", e.code)
        }

        // The intent is still owned: the caller can retry, and a restart will
        // still find the reservation in the ledger.
        assertNotNull(session.owned)
        assertEquals("+998901234567", session.owned?.destination)
        assertNull(session.recent["call-1"])
        assertEquals(1, store.orphanAttempts)
    }

    @Test
    fun aHangUpOfThePendingDialWritesTheTombstoneBeforeDroppingTheCall() {
        val store = FakeCallStore()
        val session = session(store)
        session.dial("call-1", "+998901234567")

        val plan = synchronized(session.lock) { session.planHangupLocked("call-1") }

        assertEquals(listOf("call-1"), store.orphanCalls)
        assertNull(session.owned)
        assertEquals("ended", session.recent["call-1"]?.state)
        assertEquals(1, session.orphanCancels.size)
        assertEquals(Long.MAX_VALUE, plan.toneOwner?.generation?.get())
    }

    @Test
    fun aFinishedCallIdStillRefusesAConflictingDial() {
        val session = session()
        session.dial("call-1", "+998901234567")
        synchronized(session.lock) { session.planHangupLocked("call-1") }

        val repeated = session.dial("call-1", "+998901234567")
        assertTrue(repeated is CallSession.DialOutcome.Duplicate)
        assertEquals("ended", (repeated as CallSession.DialOutcome.Duplicate).state)

        try {
            session.dial("call-1", "+998901234568")
            fail("a finished id may only be replayed with its own destination")
        } catch (e: CallSession.DialRefused) {
            assertEquals("duplicate_conflict", e.code)
        }
    }

    @Test
    fun aCancelActionStopsTheDtmfGenerationBeforeTheHangUpOutranksIt() {
        val session = session()
        session.dial("call-1", "+998901234567")
        val owned = session.owned!!

        // `cancel_actions` moves the generation under the same lock the DTMF
        // runner reads, so a waiting tone sees the cancellation on its next check.
        val future = synchronized(session.lock) { session.bumpGenerationLocked("call-1", 5) }
        assertTrue(future === owned)
        assertEquals(5L, owned.generation.get())

        // A stale request never moves it back.
        assertNull(synchronized(session.lock) { session.bumpGenerationLocked("call-1", 4) })
        assertEquals(5L, owned.generation.get())

        // A hang-up ends the whole action generation, so even the newest plan is stale.
        synchronized(session.lock) { session.planHangupLocked("call-1") }
        assertEquals(Long.MAX_VALUE, owned.generation.get())
        assertNull(synchronized(session.lock) { session.bumpGenerationLocked("call-1", 6) })
        assertNull(session.owned)
    }

    @Test
    fun aDialUnderAnIdleLinkActionWaitsForTheDoor() {
        val session = session()
        val action = session.admission.tryBeginIdleLinkAction { false }
        val dialStarted = CountDownLatch(1)

        val dial = Thread {
            runCatching {
                session.beginDial(
                    callId = "call-1",
                    destination = "+998901234567",
                    phoneAccount = null,
                    // The dial reads this before it waits for the door: it says the
                    // attempt is under way without polling or sleeping.
                    unrelatedCall = {
                        dialStarted.countDown()
                        false
                    },
                    linkUp = { true },
                    running = { true }
                )
            }
        }
        dial.start()
        assertTrue(dialStarted.await(5, TimeUnit.SECONDS))

        // The action is still in flight; the dial must not have reserved.
        assertNull(session.owned)

        action?.release()
        dial.join(5_000)
        assertNotNull(session.owned)
    }
}
