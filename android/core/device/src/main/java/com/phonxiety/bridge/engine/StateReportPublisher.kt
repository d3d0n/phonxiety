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
 * A report is recorded as reported only when the transport accepted it, and
 * only while it is still the newest plan for its call: a slower earlier send
 * (a `dialing` frame overtaken by `active`) therefore never overwrites the
 * newer state in memory or in the ledger.  Every send takes a revision under
 * [CallSession.lock]; the transport is called outside that lock.
 *
 * The terminal `ended` report is the exception the daemon depends on: a link
 * that failed before it could take the frame keeps `(callId, "ended", cause)`
 * here, and the next accepted hello sends it once more, right after the
 * handshake.  A replay and a normal report for the same call never send the
 * same terminal report twice: the replay only runs while no report for that
 * call is in flight, a normal report of the same cause yields to a replay,
 * and an accepted replay records the report as delivered so the later
 * `finishOwned` report of that `ended` stays quiet.
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

    private class Terminal(
        val callId: String,
        val cause: DisconnectCause?,
        val causeKey: String?,
        /** The call's plan revision when this report was refused. */
        val revision: Long
    )

    private class ReplayTicket(
        val terminal: Terminal,
        /** The call still owning this report, or null once it is gone. */
        val call: CallSession.OwnedCall?,
        val revision: Long
    )

    private class Decision(
        val plan: StateReportDispatch.Plan,
        val revision: Long,
        val send: Boolean
    )

    /** Guarded by [CallSession.lock]. */
    private val terminals = ArrayDeque<Terminal>()

    /** Newest in-flight normal send per call; guarded by [CallSession.lock]. */
    private val sending = HashMap<String, Long>()

    /** Terminal report a replay is sending, as call id to cause key; guarded by [CallSession.lock]. */
    private val replaying = HashMap<String, String?>()

    /** Accepted terminal reports with no live record left to carry them; guarded by [CallSession.lock]. */
    private val delivered = LinkedHashMap<String, String?>()

    /**
     * One call-state report.  Called with or without the session lock held, as
     * its two callers do today; the report's own state lives under that lock,
     * and the transport is always called outside it.
     */
    fun report(call: CallSession.OwnedCall, state: String, cause: DisconnectCause?) {
        val causeKey = cause?.let { "${it.code}|${it.label}|${it.reason}" }
        // Telecom's connect time is the base the system's call chip uses; the store records it
        // once, so the live screen can count the conversation whenever it opens.
        val connectTimeMillis = call.call?.let { CallCorrelation.detailsOf(it) }?.connectTimeMillis?.takeIf { it > 0 }
        val decision = synchronized(session.lock) {
            planLocked(call, state, cause, causeKey)
        } ?: return
        val plan = decision.plan
        if (decision.send) {
            val sent = wire(DeviceOutbound.CallState(call.callId, state, cause))
            synchronized(session.lock) {
                recordLocked(call, state, cause, causeKey, decision.revision, plan, sent)
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
     * frame it takes is cleared so a finished call is never reported twice, and
     * its delivery is recorded even when the call object is already gone.
     */
    fun reportPendingAfterHello() {
        while (true) {
            val ticket = claimReplay() ?: return
            val terminal = ticket.terminal
            val sent = wire(DeviceOutbound.CallState(terminal.callId, "ended", terminal.cause))
            synchronized(session.lock) {
                replaying.remove(terminal.callId)
                if (!sent) return
                val call = ticket.call
                if (call != null && call.reportRevision == ticket.revision) {
                    call.reportedState = "ended"
                    call.reportedCauseKey = terminal.causeKey
                    markState(call.callId, "ended")
                } else {
                    noteDeliveredLocked(terminal.callId, terminal.causeKey)
                    markState(terminal.callId, "ended")
                }
                terminals.removeAll { it === terminal }
            }
        }
    }

    /**
     * Plans one report under [CallSession.lock].  Null means there is nothing
     * to do; otherwise the plan names the revision its accepted send may record
     * under and whether this call should send at all.
     */
    private fun planLocked(
        call: CallSession.OwnedCall,
        state: String,
        cause: DisconnectCause?,
        causeKey: String?
    ): Decision? {
        if (call.finished && state != "ended") return null
        call.state = state
        // A terminal report already accepted for this call id stays delivered: a late
        // duplicate of the same cause must not repeat it, whatever object still holds it.
        if (state == "ended" && delivered.containsKey(call.callId) && delivered[call.callId] == causeKey) return null
        val plan = StateReportDispatch.plan(
            prevState = call.reportedState,
            prevCauseKey = call.reportedCauseKey,
            state = state,
            causeKey = causeKey
        ) ?: return null
        if (!plan.send) return Decision(plan, 0L, send = false)
        val replayCause = replaying[call.callId]
        if (replayCause != null) {
            // The replay owns this call's ended frame right now.  A newer cause is
            // kept for the next accepted hello instead of racing the replay frame.
            if (state == "ended" && replayCause != causeKey) {
                val revision = ++call.reportRevision
                rememberTerminalLocked(call.callId, cause, causeKey, revision)
            }
            return Decision(plan, 0L, send = false)
        }
        val revision = ++call.reportRevision
        sending[call.callId] = revision
        return Decision(plan, revision, send = true)
    }

    /**
     * Records an accepted send, or retains a refused terminal report, under
     * [CallSession.lock].  A send a newer plan overtook records nothing: its
     * outcome must not move the call back to an older state.
     */
    private fun recordLocked(
        call: CallSession.OwnedCall,
        state: String,
        cause: DisconnectCause?,
        causeKey: String?,
        revision: Long,
        plan: StateReportDispatch.Plan,
        sent: Boolean
    ) {
        if (sending[call.callId] == revision) sending.remove(call.callId)
        if (call.reportRevision == revision) {
            if (sent) {
                if (plan.recordState) {
                    call.reportedState = state
                    markState(call.callId, state)
                }
                if (plan.recordCauseKey) call.reportedCauseKey = causeKey
                if (state == "ended") terminals.removeAll { it.callId == call.callId }
            } else if (state == "ended") {
                rememberTerminalLocked(call.callId, cause, causeKey, revision)
            }
        } else if (!sent && state == "ended" && !sending.containsKey(call.callId)) {
            // A newer non-terminal plan superseded this refusal; the newer state may
            // be recorded, but the call's terminal report must not be lost.
            rememberTerminalLocked(call.callId, cause, causeKey, call.reportRevision)
        }
        if (sent && state == "ended" && call.reportRevision != revision) {
            // The frame reached the daemon even though a newer plan took the record;
            // the newer plan's state wins, and this ended stays delivered.
            noteDeliveredLocked(call.callId, causeKey)
        }
    }

    /**
     * Picks the next terminal report a replay may send, or null when nothing is
     * ready.  A call with a report in flight, or a newer plan than the retained
     * one, is left for the send that owns it; [replaying] then keeps a normal
     * report of the same call from racing this frame.
     */
    private fun claimReplay(): ReplayTicket? = synchronized(session.lock) {
        for (terminal in terminals) {
            if (sending.containsKey(terminal.callId) || replaying.containsKey(terminal.callId)) continue
            val live = session.owned?.takeIf { it.callId == terminal.callId && !it.finished }
            // A newer plan already owns the record; its accepted send clears the slot.
            if (live != null && live.reportRevision != terminal.revision) continue
            val revision = if (live == null) 0L else ++live.reportRevision
            replaying[terminal.callId] = terminal.causeKey
            return ReplayTicket(terminal, live, revision)
        }
        null
    }

    private fun markState(callId: String, state: String) {
        try {
            dials.markState(callId, state)
        } catch (e: CallLedgerException) {
            BridgeLog.e(TAG) { "call state not durable: ${e.message}" }
        }
    }

    private fun rememberTerminalLocked(callId: String, cause: DisconnectCause?, causeKey: String?, revision: Long) {
        terminals.removeAll { it.callId == callId }
        terminals.addLast(Terminal(callId, cause, causeKey, revision))
        while (terminals.size > MAX_PENDING_TERMINAL) terminals.removeFirst()
    }

    private fun noteDeliveredLocked(callId: String, causeKey: String?) {
        delivered.remove(callId)
        delivered[callId] = causeKey
        while (delivered.size > MAX_PENDING_TERMINAL) {
            val oldest = delivered.keys.firstOrNull() ?: break
            delivered.remove(oldest)
        }
    }
}
