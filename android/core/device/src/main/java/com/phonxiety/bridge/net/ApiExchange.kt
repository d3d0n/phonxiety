package com.phonxiety.bridge.net

import com.phonxiety.bridge.model.DeviceApiResult
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one way an app-API request reaches the daemon.
 *
 * A request takes its place in [table] and the caller's pairing generation
 * first, then waits for [readiness] within the caller's single deadline, checks
 * the pairing again and only then sends.  The deadline therefore covers the
 * readiness wait and the answer together, and no request ever crosses to a
 * pairing that replaced its own: the old gateway stays the only one it can use.
 *
 * Entries leave the table on the answer, the timeout and cancellation alike.
 */
internal class ApiExchange(
    private val table: ApiRequestTable,
    private val readiness: LinkReadiness,
    private val pairingEpoch: () -> Long,
    private val send: (wire: String) -> Boolean
) {
    /**
     * One request, bounded by [deadlineMs].  Suspension of the caller cancels
     * the wait and drops the entry; a timeout is a typed result, not an
     * exception.
     */
    suspend fun exchange(
        id: String,
        wire: String,
        deadlineMs: Long,
        onEnqueued: () -> Unit = {}
    ): DeviceApiResult {
        val entry = table.post(id, pairingEpoch()) ?: return RequestFailure.TooMany.result(id)
        try {
            return withTimeoutOrNull(deadlineMs) { deliver(entry, wire, onEnqueued) }
                ?: RequestFailure.Timeout.result(id)
        } finally {
            table.remove(id)
        }
    }

    private suspend fun deliver(
        entry: ApiRequestTable.Entry,
        wire: String,
        onEnqueued: () -> Unit
    ): DeviceApiResult {
        readiness.await()?.let {
            onEnqueued()
            return it.result(entry.id)
        }
        // The gateway was taken at post time; a pairing that moved while waiting
        // must not carry this request to the daemon that replaced its own.
        if (pairingEpoch() != entry.pairingEpoch) {
            onEnqueued()
            return RequestFailure.PairingChanged.result(entry.id)
        }
        // Counted as sent before the hand-off: once the transport may have the
        // frame, a link loss has to answer it rather than leave it waiting.
        entry.markSent()
        val sent = send(wire)
        // The caller's door is held up to this point and never across the wait.
        onEnqueued()
        if (!sent) return RequestFailure.Refused.result(entry.id)
        val result = entry.answer.await()
        // An answer that outlived its pairing is refused, exactly like a request
        // that arrived after it: the caller never sees the old daemon's data as
        // the new one's.
        if (pairingEpoch() != entry.pairingEpoch) return RequestFailure.PairingChanged.result(entry.id)
        return result
    }

    /** A transport opened; its `hello` is still pending. */
    fun transportOpened() = readiness.opened()

    /** The live link queued its `hello`: waiting requests may go out. */
    fun helloQueued() = readiness.helloQueued()

    /** The link went down: sent requests fail, waiting ones follow the next link. */
    fun linkDown() {
        readiness.linkDown()
        table.failAll(RequestFailure.LinkDown, includeWaiting = false)
    }

    /** Terminal: every pending request, waiting or sent, completes with [failure]. */
    fun stop(failure: RequestFailure) {
        readiness.stop(failure)
        table.failAll(failure, includeWaiting = true)
    }

    /** Requests still pending; the table's observable size. */
    fun pending(): Int = table.size()
}
