package com.phonxiety.bridge.telecom

/**
 * The durable call record the session, the reporter, the observer and the
 * commands share.
 *
 * The production implementation is [CallLedger]; tests substitute a fake so
 * the admission and tombstone decisions are provable without a file.
 */
internal interface CallStore {
    fun current(): CallLedger.Snapshot

    fun reserve(
        callId: String,
        destination: String,
        phoneAccount: String?,
        createdWall: Long,
        createdElapsed: Long
    )

    fun markState(callId: String, callState: String)

    fun cancel(callId: String)

    fun finish(callId: String, destination: String, recentState: String)

    fun orphan(
        callId: String,
        destination: String,
        dialStartedWall: Long,
        ttlMs: Long
    )

    fun consumeOrphan(callId: String, dialStartedWall: Long)

    fun pruneOrphans()
}
