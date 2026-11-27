package com.phonxiety.bridge.engine

import com.phonxiety.bridge.EngineStateStore
import com.phonxiety.bridge.model.DeviceOutbound
import com.phonxiety.bridge.model.DisconnectCause
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal `ended` report through the production publisher.
 *
 * A frame the transport refused is retained and re-sent once right after the
 * next accepted hello; an accepted one is never repeated.  A plan records its
 * state and cause under the session lock when it is planned, before the send,
 * so a slower earlier frame never overwrites a newer recorded one.
 */
class StateReportPublisherTest {
    private class Wire {
        @Volatile var accept = true
        val attempts: MutableList<DeviceOutbound.CallState> = Collections.synchronizedList(mutableListOf())
        val accepted: MutableList<DeviceOutbound.CallState> = Collections.synchronizedList(mutableListOf())

        fun send(message: DeviceOutbound): Boolean {
            if (message !is DeviceOutbound.CallState) return accept
            attempts += message
            if (!accept) return false
            accepted += message
            return true
        }
    }

    private fun session(store: FakeCallStore = FakeCallStore()): CallSession = CallSession(store, "com.phonxiety.bridge", clock = { 1_000L })

    private fun call(id: String = "call-1") = CallSession.OwnedCall(
        callId = id,
        destination = "+998901234567",
        dialStartedWall = 1_000,
        dialStartedElapsed = 1_000
    )

    private val cause = DisconnectCause(code = 2, label = "NORMAL", reason = "normal")
    private val otherCause = DisconnectCause(code = 3, label = "BUSY", reason = "busy")

    @Test
    fun aRefusedEndedReportIsResentOnceAfterTheNextAcceptedHello() {
        val wire = Wire().apply { accept = false }
        val publisher = StateReportPublisher(session(), FakeCallStore(), EngineStateStore(), wire::send)
        val finished = call()

        publisher.report(finished, "ended", cause)
        assertTrue(wire.accepted.isEmpty())

        wire.accept = true
        publisher.replayRetained()

        assertEquals(1, wire.accepted.size)
        val resent = wire.accepted.single()
        assertEquals("ended", resent.state)
        assertEquals(cause, resent.disconnectCause)
        assertEquals("call-1", resent.callId)

        // The slot is cleared only by the accepted send: nothing is repeated.
        publisher.replayRetained()
        assertEquals(1, wire.accepted.size)
    }

    @Test
    fun anAcceptedEndedReportIsNeverReplayed() {
        val wire = Wire()
        val publisher = StateReportPublisher(session(), FakeCallStore(), EngineStateStore(), wire::send)

        publisher.report(call(), "ended", cause)
        assertEquals(1, wire.accepted.size)

        publisher.replayRetained()

        assertEquals(1, wire.accepted.size)
    }

    @Test
    fun aNullCauseIsRetainedAndReplayed() {
        val wire = Wire().apply { accept = false }
        val publisher = StateReportPublisher(session(), FakeCallStore(), EngineStateStore(), wire::send)

        publisher.report(call(), "ended", null)
        wire.accept = true
        publisher.replayRetained()

        val resent = wire.accepted.single()
        assertEquals("ended", resent.state)
        assertNull(resent.disconnectCause)
        publisher.replayRetained()
        assertEquals(1, wire.accepted.size)
    }

    /**
     * The pre-carve dedupe: the state-change `ended` sends once, and the later
     * `finishOwned` of the same call with the same cause stays quiet.
     */
    @Test
    fun aStateChangeEndedAndFinishOwnedWithTheSameCauseSendOnce() {
        val wire = Wire()
        val publisher = StateReportPublisher(session(), FakeCallStore(), EngineStateStore(), wire::send)
        val active = call()

        publisher.report(active, "active", null)
        publisher.report(active, "ended", cause)
        publisher.report(active, "ended", cause)

        assertEquals(1, wire.accepted.count { it.state == "ended" })
        assertEquals(1, wire.attempts.count { it.state == "ended" })
        assertEquals("ended", active.reportedState)
    }

    /**
     * A terminal report that gains a different disconnect cause is sent again,
     * and the retained slot carries that newest cause to the next hello.
     */
    @Test
    fun aChangedCauseIsSentAgainAndTheNewestCauseIsReplayed() {
        val wire = Wire()
        val publisher = StateReportPublisher(session(), FakeCallStore(), EngineStateStore(), wire::send)
        val active = call()

        publisher.report(active, "active", null)
        publisher.report(active, "ended", cause)
        assertEquals(listOf(cause), wire.accepted.filter { it.state == "ended" }.map { it.disconnectCause })

        // The late cause arrives on a link that refuses it: retained for the next hello.
        wire.accept = false
        publisher.report(active, "ended", otherCause)
        assertTrue(wire.accepted.count { it.state == "ended" } == 1)

        wire.accept = true
        publisher.replayRetained()

        assertEquals(
            listOf(cause, otherCause),
            wire.accepted.filter { it.state == "ended" }.map { it.disconnectCause }
        )
        // The newest cause is recorded, so a third identical report stays quiet.
        publisher.report(active, "ended", otherCause)
        assertEquals(2, wire.accepted.count { it.state == "ended" })
    }

    /**
     * The plan records its state before the frame leaves, so the recording of
     * an earlier `dialing` is already visible while its send is still blocked
     * and a slower send cannot undo the newer `active`.
     */
    @Test
    fun aSlowerDialingSendCannotOverwriteTheRecordedActive() {
        val store = FakeCallStore()
        val session = session(store)
        session.beginDial(
            callId = "call-1",
            destination = "+998901234567",
            phoneAccount = null,
            unrelatedCall = { false },
            linkUp = { true },
            running = { true }
        )
        val owned = session.owned!!
        val dialingInFlight = CountDownLatch(1)
        val releaseDialing = CountDownLatch(1)
        val dialingReturned = CountDownLatch(1)
        val publisher = StateReportPublisher(session, store, EngineStateStore()) { message ->
            if (message is DeviceOutbound.CallState && message.state == "dialing") {
                // Hold the earlier frame until the newer state has been recorded.
                dialingInFlight.countDown()
                releaseDialing.await(5, TimeUnit.SECONDS)
            }
            true
        }

        val dialing = Thread {
            publisher.report(owned, "dialing", null)
            dialingReturned.countDown()
        }
        dialing.start()
        assertTrue(dialingInFlight.await(5, TimeUnit.SECONDS))

        // The plan was recorded under the lock, before the blocked send.
        assertEquals("dialing", owned.reportedState)
        assertEquals("dialing", store.snapshot.pending?.state)

        publisher.report(owned, "active", null)
        assertEquals("active", owned.reportedState)
        assertEquals("active", store.snapshot.pending?.state)

        releaseDialing.countDown()
        assertTrue(dialingReturned.await(5, TimeUnit.SECONDS))
        dialing.join(5_000)

        assertEquals("active", owned.reportedState)
        assertEquals("active", store.snapshot.pending?.state)
    }

    @Test
    fun aFinishedCallIsNotAdvertisedByTheSession() {
        val session = session()
        session.beginDial(
            callId = "call-1",
            destination = "+998901234567",
            phoneAccount = null,
            unrelatedCall = { false },
            linkUp = { true },
            running = { true }
        )
        synchronized(session.lock) { session.planHangupLocked("call-1") }

        // The hello reads the owned call: a finished one is gone from the session.
        assertEquals(null, session.owned)
        assertEquals(null, session.activeCallState())
    }
}
