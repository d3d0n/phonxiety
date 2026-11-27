package com.phonxiety.bridge

internal object StateReportDispatch {
    /**
     * [send] repeats the state on the wire, [recordState] makes it durable in the ledger,
     * [recordCauseKey] remembers the report's cause for dedup, and [replay] tells the store this is
     * not a new phase transition.
     */
    internal class Plan(
        val send: Boolean,
        val replay: Boolean,
        val recordState: Boolean,
        val recordCauseKey: Boolean
    )

    /**
     * Null means there is nothing to do: the wire and the ledger already hold this report.  A new
     * state is sent once and recorded; a repeated phase is only replayed into the store, except
     * that an ended call reports again when it gains a different disconnect cause, so the
     * late cause still reaches the daemon.  That cause is remembered, which keeps the next
     * identical report quiet.
     */
    fun plan(prevState: String?, prevCauseKey: String?, state: String, causeKey: String?): Plan? {
        if (prevState != state) {
            return Plan(send = true, replay = false, recordState = true, recordCauseKey = true)
        }
        if (state != "ended") {
            return Plan(send = false, replay = true, recordState = false, recordCauseKey = false)
        }
        if (causeKey == null || causeKey == prevCauseKey) return null
        return Plan(send = true, replay = true, recordState = false, recordCauseKey = true)
    }
}
