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
 * next accepted hello; an accepted one is never repeated.  Non-terminal states
 * are recorded as reported only when the transport took them, and a slower
 * earlier send never overwrites a newer recorded one.
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

    private fun session() = CallSession(FakeCallStore(), "com.phonxiety.bridge", clock = { 1_000L })

    private fun call(id: String = "call-1") = CallSession.OwnedCall(
        callId = id,
        destination = "+998901234567",
        dialStartedWall = 1_000,
        dialStartedElapsed = 1_000
    )

    private val cause = DisconnectCause(code = 2, label = "NORMAL", reason = "normal")

    @Test
    fun aRefusedEndedReportIsResentOnceAfterTheNextAcceptedHello() {
        val wire = Wire().apply { accept = false }
        val publisher = StateReportPublisher(session(), FakeCallStore(), EngineStateStore(), wire::send)
        val finished = call()

        publisher.report(finished, "ended", cause)
        assertTrue(wire.accepted.isEmpty())

        wire.accept = true
        publisher.reportPendingAfterHello()

        assertEquals(1, wire.accepted.size)
        val resent = wire.accepted.single()
        assertEquals("ended", resent.state)
        assertEquals(cause, resent.disconnectCause)
        assertEquals("call-1", resent.callId)

        // The slot is cleared only by the accepted send: nothing is repeated.
        publisher.reportPendingAfterHello()
        assertEquals(1, wire.accepted.size)
    }

    @Test
    fun anAcceptedEndedReportIsNeverSentAgain() {
        val wire = Wire()
        val publisher = StateReportPublisher(session(), FakeCallStore(), EngineStateStore(), wire::send)

        publisher.report(call(), "ended", cause)
        assertEquals(1, wire.accepted.size)

        publisher.reportPendingAfterHello()

        assertEquals(1, wire.accepted.size)
    }

    @Test
    fun aRefusedNonTerminalStateIsNotRecordedAndIsSentAgainOnceAccepted() {
        val wire = Wire().apply { accept = false }
        val publisher = StateReportPublisher(session(), FakeCallStore(), EngineStateStore(), wire::send)
        val active = call()

        publisher.report(active, "dialing", null)
        assertEquals(1, wire.attempts.size)
        assertNull(active.reportedState)

        wire.accept = true
        publisher.report(active, "dialing", null)

        assertEquals(2, wire.attempts.size)
        assertEquals(1, wire.accepted.size)
        assertEquals("dialing", active.reportedState)
    }

    /**
     * The P2-1 interleaving: the state-change `ended` is refused and retained,
     * the next hello's replay is accepted, and only then `finishOwned` reports
     * the very same `ended`.  The accepted replay marks the report delivered,
     * so the later caller must stay quiet.
     */
    @Test
    fun anAcceptedReplayBeforeFinishOwnedSendsOneEnded() {
        val wire = Wire()
        val store = FakeCallStore()
        val session = CallSession(store, "com.phonxiety.bridge", clock = { 1_000L })
        val publisher = StateReportPublisher(session, store, EngineStateStore(), wire::send)
        session.beginDial(
            callId = "call-1",
            destination = "+998901234567",
            phoneAccount = null,
            unrelatedCall = { false },
            linkUp = { true },
            running = { true }
        )
        val active = session.owned!!
        publisher.report(active, "active", null)
        wire.accept = false

        publisher.report(active, "ended", cause)
        assertTrue(wire.accepted.none { it.state == "ended" })

        // The retained terminal goes out right after the next accepted hello...
        wire.accept = true
        publisher.reportPendingAfterHello()
        // ...and the publisher, not the later caller, owns the accepted report.
        publisher.report(active, "ended", cause)

        assertEquals(1, wire.accepted.count { it.state == "ended" })
        assertEquals("ended", active.reportedState)
        assertEquals("ended", store.snapshot.pending?.state)
    }

    /**
     * The P2-2 interleaving: a slow `dialing` frame returns after `active` was
     * recorded.  It must neither move the call back nor overwrite the ledger.
     */
    @Test
    fun aSlowerDialingSendCannotOverwriteTheRecordedActive() {
        val store = FakeCallStore()
        val session = CallSession(store, "com.phonxiety.bridge", clock = { 1_000L })
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
        val activeRecorded = CountDownLatch(1)
        val publisher = StateReportPublisher(session, store, EngineStateStore()) { message ->
            if (message is DeviceOutbound.CallState && message.state == "dialing") {
                // Hold the earlier frame until the newer state has been recorded.
                dialingInFlight.countDown()
                activeRecorded.await(5, TimeUnit.SECONDS)
            }
            true
        }

        val dialing = Thread { publisher.report(owned, "dialing", null) }
        dialing.start()
        assertTrue(dialingInFlight.await(5, TimeUnit.SECONDS))

        publisher.report(owned, "active", null)
        assertEquals("active", owned.reportedState)
        assertEquals("active", store.snapshot.pending?.state)

        activeRecorded.countDown()
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
