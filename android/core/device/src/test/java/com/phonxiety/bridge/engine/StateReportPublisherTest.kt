package com.phonxiety.bridge.engine

import com.phonxiety.bridge.EngineStateStore
import com.phonxiety.bridge.model.DeviceOutbound
import com.phonxiety.bridge.model.DisconnectCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal `ended` report through the production publisher.
 *
 * A frame the transport refused is retained and re-sent once right after the
 * next accepted hello; an accepted one is never repeated.  Non-terminal states
 * are recorded as reported only when the transport took them.
 */
class StateReportPublisherTest {
    private class Wire {
        var accept = true
        val attempts = mutableListOf<DeviceOutbound.CallState>()
        val accepted = mutableListOf<DeviceOutbound.CallState>()

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
        session.planHangupLocked("call-1")

        // The hello reads the owned call: a finished one is gone from the session.
        assertEquals(null, session.owned)
        assertEquals(null, session.activeCallState())
    }
}
