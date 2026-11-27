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
 * A report is planned and recorded under [CallSession.lock], before anything
 * is sent: the first report of a state (and of a new terminal cause) moves the
 * call's reported state and the ledger at plan time, so a slower earlier send
 * can never overwrite a newer recorded plan.  Only [StateReportDispatch]
 * decides whether a frame goes out at all, so a state the wire already has is
 * not repeated.
 *
 * The terminal `ended` report is the exception the daemon depends on: a link
 * that refuses the frame keeps `(callId, cause)` here, and the next accepted
 * hello sends it once more, right after the handshake and before the waiting
 * app requests.  Delivery is at-least-once, not exactly once: a duplicate
 * `ended` for the same `call_id` is harmless to the daemon, which dispatches
 * by call id, touches only rows still in `unknown` when it reconciles an
 * ending, and clears its active-call record only for the matching id.  A
 * frame the link accepts clears the retained slot, so an accepted report is
 * never replayed.
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
        private const val MAX_RETAINED_ENDS = 4
    }

    /** A terminal `ended` the wire has not accepted yet. */
    private class RetainedEnd(val callId: String, val cause: DisconnectCause?)

    /** Terminal reports waiting for a link that takes them; guarded by [CallSession.lock]. */
    private val retained = ArrayDeque<RetainedEnd>()

    /**
     * One call-state report.  The Telecom state and details callbacks call it
     * holding the session lock, so their send happens under it, as before the
     * engine was split; call-added, `finishOwned` and pending-dial expiry call
     * it without the lock.  Either way the plan and its record happen under the
     * lock, before the send.
     */
    fun report(call: CallSession.OwnedCall, state: String, cause: DisconnectCause?) {
        val causeKey = cause?.let { "${it.code}|${it.label}|${it.reason}" }
        // Telecom's connect time is the base the system's call chip uses; the store records it
        // once, so the live screen can count the conversation whenever it opens.
        val connectTimeMillis = call.call?.let { CallCorrelation.detailsOf(it) }?.connectTimeMillis?.takeIf { it > 0 }
        var decided: StateReportDispatch.Plan? = null
        var kept: RetainedEnd? = null
        synchronized(session.lock) {
            if (call.finished && state != "ended") return
            call.state = state
            val plan = StateReportDispatch.plan(
                prevState = call.reportedState,
                prevCauseKey = call.reportedCauseKey,
                state = state,
                causeKey = causeKey
            ) ?: return
            decided = plan
            if (plan.recordState) {
                call.reportedState = state
                markState(call.callId, state)
            }
            if (plan.recordCauseKey) call.reportedCauseKey = causeKey
            // Retained before the send, under the same critical section that recorded the
            // plan, so a link that drops the frame cannot lose it.
            if (plan.send && state == "ended") kept = retainLocked(call.callId, cause)
        }
        val plan = decided ?: return
        if (plan.send) {
            val sent = wire(DeviceOutbound.CallState(call.callId, state, cause))
            val ticket = kept
            if (sent && ticket != null) {
                synchronized(session.lock) { retained.removeAll { it === ticket } }
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
     * hello.  Runs under [CallSession.lock], like any other report; a frame the
     * link takes clears its slot, and a frame it refuses stays for the next
     * accepted hello.  Delivery is therefore at-least-once: a stale retained
     * `ended` may repeat, which the daemon tolerates for the same call id.
     */
    fun replayRetained() {
        synchronized(session.lock) {
            for (end in retained.toList()) {
                val sent = wire(DeviceOutbound.CallState(end.callId, "ended", end.cause))
                if (sent) retained.removeAll { it === end }
            }
        }
    }

    private fun markState(callId: String, state: String) {
        try {
            dials.markState(callId, state)
        } catch (e: CallLedgerException) {
            BridgeLog.e(TAG) { "call state not durable: ${e.message}" }
        }
    }

    /**
     * Keeps one terminal report for the next accepted hello.  A newer report
     * for the same call replaces the older one, so the newest cause wins; the
     * oldest call's report is evicted beyond [MAX_RETAINED_ENDS].
     */
    private fun retainLocked(callId: String, cause: DisconnectCause?): RetainedEnd {
        retained.removeAll { it.callId == callId }
        val end = RetainedEnd(callId, cause)
        retained.addLast(end)
        while (retained.size > MAX_RETAINED_ENDS) retained.removeFirst()
        return end
    }
}
