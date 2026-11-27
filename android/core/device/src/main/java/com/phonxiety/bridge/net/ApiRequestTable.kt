package com.phonxiety.bridge.net

import com.phonxiety.bridge.model.DeviceApiError
import com.phonxiety.bridge.model.DeviceApiResult
import kotlinx.coroutines.CompletableDeferred

/**
 * Why a pending app-API request can no longer be answered.  Every failure the
 * request path can produce is one of these, so the codes and messages live in
 * one place instead of being scattered over the engine.
 */
internal sealed class RequestFailure(
    val code: String,
    val message: String,
    val status: Int
) {
    /** The link went down after the request left: its answer will never arrive. */
    object LinkDown : RequestFailure("link_down", "the device link is down", 503)

    /** The transport refused the frame outright. */
    object Refused : RequestFailure("link_down", "the device link refused the message", 503)

    /** The service is stopping and this request is not being sent anywhere else. */
    object ShuttingDown : RequestFailure("link_down", "the bridge is shutting down", 503)

    /** The pairing changed while the request waited; it must never cross to the new daemon. */
    object PairingChanged : RequestFailure("pairing_changed", "the pairing changed", 503)

    /** Too many requests were already waiting; the table refuses another. */
    object TooMany : RequestFailure("link_down", "too many requests are already pending", 503)

    /** The link never completed its handshake: a request waiting for readiness cannot be sent. */
    object HelloFailed : RequestFailure("hello_failed", "the device link could not complete its handshake", 503)

    /** The deadline (readiness wait included) ran out. */
    object Timeout : RequestFailure("timeout", "the daemon did not answer in time", 504)

    /** Anything that escaped the send or the wait; the message is the exception's class only. */
    class Transport(error: Throwable) : RequestFailure("transport_error", error.javaClass.simpleName, 500)

    fun result(id: String): DeviceApiResult = DeviceApiResult(id = id, status = status, error = DeviceApiError(code, message))
}

/**
 * The app-API requests waiting for an answer, one entry per request id.
 *
 * Posting is atomic with the pairing generation the request was made under, so
 * the router can never complete a request with an answer to another one.  An
 * entry is removed in the same step it completes: no timeout, cancellation,
 * link loss or shutdown leaves one behind.
 *
 * [Entry.sent] separates a request still waiting for the link's readiness from
 * one already on the wire: a link loss can never answer the former (it waits
 * for the next link instead), only the latter.
 */
internal class ApiRequestTable(private val capacity: Int = 64) {
    class Entry internal constructor(
        val id: String,
        val pairingEpoch: Long
    ) {
        internal val answer = CompletableDeferred<DeviceApiResult>()

        /** True once the frame was handed to the transport; a link loss fails only these. */
        var sent: Boolean = false
            private set

        internal fun markSent() {
            sent = true
        }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    /** Posts one request; null when the id is already pending or the table is full. */
    fun post(id: String, pairingEpoch: Long): Entry? = synchronized(lock) {
        if (entries.containsKey(id) || entries.size >= capacity) return null
        val entry = Entry(id, pairingEpoch)
        entries[id] = entry
        entry
    }

    /** Completes the request the answer names; false when its id is not pending. */
    fun complete(result: DeviceApiResult): Boolean = synchronized(lock) {
        val entry = entries.remove(result.id) ?: return false
        entry.answer.complete(result)
        true
    }

    /** Removes [id] without completing it; false when nothing was pending. */
    fun remove(id: String): Boolean = synchronized(lock) { entries.remove(id) != null }

    /**
     * Completes [failure] into every pending request, or only the ones already
     * sent; returns how many were completed.
     */
    fun failAll(failure: RequestFailure, includeWaiting: Boolean): Int = synchronized(lock) {
        val doomed = entries.values.filter { includeWaiting || it.sent }
        doomed.forEach { entries.remove(it.id) }
        doomed.forEach { it.answer.complete(failure.result(it.id)) }
        doomed.size
    }

    /** Completes [failure] into the requests still waiting for the link's readiness. */
    fun failWaiting(failure: RequestFailure): Int = synchronized(lock) {
        val doomed = entries.values.filter { !it.sent }
        doomed.forEach { entries.remove(it.id) }
        doomed.forEach { it.answer.complete(failure.result(it.id)) }
        doomed.size
    }

    fun size(): Int = synchronized(lock) { entries.size }
}
