package com.phonxiety.bridge.engine

import com.phonxiety.bridge.telecom.CallLedger
import com.phonxiety.bridge.telecom.CallLedgerException
import com.phonxiety.bridge.telecom.CallStore

/**
 * A call store with the production API and injectable write failures, so the
 * admission and tombstone decisions are provable without a ledger file.
 */
internal class FakeCallStore : CallStore {
    var snapshot = CallLedger.Snapshot(pending = null, recent = emptyList(), orphans = emptyList())
        private set

    var failReserve = false
    var failOrphan = false

    val reserveCalls = mutableListOf<String>()
    val orphanCalls = mutableListOf<String>()
    var orphanAttempts = 0
        private set
    val finishCalls = mutableListOf<Pair<String, String>>()

    override fun current(): CallLedger.Snapshot = snapshot

    override fun reserve(
        callId: String,
        destination: String,
        phoneAccount: String?,
        createdWall: Long,
        createdElapsed: Long
    ) {
        if (failReserve) throw CallLedgerException("ledger_write_failed")
        reserveCalls += callId
        snapshot = snapshot.copy(
            pending = CallLedger.Pending(
                callId = callId,
                destination = destination,
                phoneAccount = phoneAccount,
                createdWall = createdWall,
                createdElapsed = createdElapsed,
                cancelled = false,
                state = "dialing"
            ),
            recent = snapshot.recent.filterNot { it.callId == callId } +
                CallLedger.Recent(callId, destination, "dialing")
        )
    }

    override fun markState(callId: String, callState: String) {
        val pending = snapshot.pending ?: return
        if (pending.callId != callId) return
        snapshot = snapshot.copy(pending = pending.copy(state = callState))
    }

    override fun cancel(callId: String) {
        val pending = snapshot.pending ?: return
        if (pending.callId != callId) return
        snapshot = snapshot.copy(pending = pending.copy(cancelled = true))
    }

    override fun finish(callId: String, destination: String, recentState: String) {
        finishCalls += callId to recentState
        snapshot = snapshot.copy(
            pending = snapshot.pending?.takeUnless { it.callId == callId },
            recent = snapshot.recent.filterNot { it.callId == callId } +
                CallLedger.Recent(callId, destination, recentState)
        )
    }

    override fun orphan(
        callId: String,
        destination: String,
        dialStartedWall: Long,
        ttlMs: Long
    ) {
        orphanAttempts++
        if (failOrphan) throw CallLedgerException("ledger_write_failed")
        orphanCalls += callId
        snapshot = snapshot.copy(
            pending = snapshot.pending?.takeUnless { it.callId == callId },
            recent = snapshot.recent.filterNot { it.callId == callId } +
                CallLedger.Recent(callId, destination, "ended"),
            orphans = snapshot.orphans.filterNot { it.callId == callId } +
                CallLedger.Orphan(callId, destination, dialStartedWall, dialStartedWall + ttlMs)
        )
    }

    override fun consumeOrphan(callId: String, dialStartedWall: Long) {
        snapshot = snapshot.copy(orphans = snapshot.orphans.filterNot { it.callId == callId })
    }

    override fun pruneOrphans() = Unit
}
