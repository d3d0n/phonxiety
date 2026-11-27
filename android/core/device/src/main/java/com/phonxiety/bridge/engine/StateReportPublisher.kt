package com.phonxiety.bridge.engine

import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.EngineStateStore
import com.phonxiety.bridge.StateReportDispatch
import com.phonxiety.bridge.callPhaseOf
import com.phonxiety.bridge.model.DeviceOutbound
import com.phonxiety.bridge.model.DisconnectCause
import com.phonxiety.bridge.telecom.CallLedgerException
import com.phonxiety.bridge.telecom.CallStore

/**
 * Writes one call-state report to the wire and the ledger.
 *
 * A report is recorded as reported only when the transport accepted it.  The
 * terminal `ended` report is the exception the daemon depends on: a link that
 * failed before it could take the frame keeps `(callId, "ended", cause)` here,
 * and the next accepted hello sends it once more, right after the handshake.
 *
 * Command replies are deliberately not retained.  The daemon's device-link
 * read loop fails every pending command future with `device_disconnected` and
 * clears its pending table when the link closes, and `command()` also bounds
 * its wait with its own timeout and removes the id in `finally`, so a reply
 * that missed its link is never awaited again.  Only the call's terminal state
 * would otherwise be lost.
 */
internal class StateReportPublisher(
    private val session: CallSession,
    private val dials: CallStore,
    private val store: EngineStateStore,
    private val wire: (DeviceOutbound) -> Boolean
) {
    companion object {
        private const val TAG = "Bridge:engine"

        /** Only a handful of calls can finish while one link is down. */
        private const val MAX_PENDING_TERMINAL = 4
    }

    private data class Terminal(val callId: String, val cause: DisconnectCause?)

    /** Guarded by [CallSession.lock]. */
    private val terminals = ArrayDeque<Terminal>()

    /**
     * One call-state report.  Called with or without the session lock held, as
     * its two callers do today; the lock is re-entrant because it is the same
     * monitor.
     */
    fun report(call: CallSession.OwnedCall, state: String, cause: DisconnectCause?) {
        val causeKey = cause?.let { "${it.code}|${it.label}|${it.reason}" }
        // Telecom's connect time is the base the system's call chip uses; the store records it
        // once, so the live screen can count the conversation whenever it opens.
        val connectTimeMillis = call.call?.let { CallCorrelation.detailsOf(it) }?.connectTimeMillis?.takeIf { it > 0 }
        var decided: StateReportDispatch.Plan? = null
        synchronized(session.lock) {
            if (call.finished && state != "ended") return
            call.state = state
            decided = StateReportDispatch.plan(
                prevState = call.reportedState,
                prevCauseKey = call.reportedCauseKey,
                state = state,
                causeKey = causeKey
            ) ?: return
        }
        val plan = decided ?: return
        if (plan.send) {
            val sent = wire(DeviceOutbound.CallState(call.callId, state, cause))
            synchronized(session.lock) {
                if (sent) {
                    if (plan.recordState) {
                        call.reportedState = state
                        try {
                            dials.markState(call.callId, state)
                        } catch (e: CallLedgerException) {
                            BridgeLog.e(TAG) { "call state not durable: ${e.message}" }
                        }
                    }
                    if (plan.recordCauseKey) call.reportedCauseKey = causeKey
                    if (state == "ended") clearTerminalLocked(call.callId)
                } else if (state == "ended") {
                    rememberTerminalLocked(call.callId, cause)
                }
            }
        }
        // The wire already has a repeated phase, but the store may still lack the answer: a
        // call restored from the ledger is adopted here, and this is the first moment Telecom's
        // own connect time exists. A replay keeps the stamp the store has and never mines one
        // from the engine's clock at adoption time.
        if (plan.replay) {
            store.callPhaseReplayed(callPhaseOf(state), connectTimeMillis = connectTimeMillis)
        } else {
            store.callPhase(callPhaseOf(state), connectTimeMillis = connectTimeMillis)
        }
    }

    /**
     * Sends every retained terminal report once, immediately after an accepted
     * hello.  A frame the link refuses stays for the next accepted hello; a
     * frame it takes is cleared so a finished call is never reported twice.
     */
    fun reportPendingAfterHello() {
        while (true) {
            val next = synchronized(session.lock) { terminals.firstOrNull() } ?: return
            if (!wire(DeviceOutbound.CallState(next.callId, "ended", next.cause))) return
            synchronized(session.lock) { terminals.remove(next) }
        }
    }

    private fun rememberTerminalLocked(callId: String, cause: DisconnectCause?) {
        terminals.removeAll { it.callId == callId }
        terminals.addLast(Terminal(callId, cause))
        while (terminals.size > MAX_PENDING_TERMINAL) terminals.removeFirst()
    }

    private fun clearTerminalLocked(callId: String) {
        terminals.removeAll { it.callId == callId }
    }
}
