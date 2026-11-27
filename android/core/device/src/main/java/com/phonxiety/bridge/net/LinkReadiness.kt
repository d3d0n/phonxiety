package com.phonxiety.bridge.net

import kotlinx.coroutines.CompletableDeferred

/**
 * Whether the live link can carry a request: readiness means its `hello` was
 * queued, not that a transport opened.  The daemon reads the first
 * post-authentication control frame as the handshake, so a request sent on a
 * transport that has not queued `hello` yet is refused; waiting for readiness
 * instead of failing at once is what keeps a request issued during a reconnect
 * alive until the link is usable again.
 *
 * A link that goes down while a request waits returns it to waiting, so the
 * request follows the next link within its own deadline.  [stop] is terminal:
 * every waiter, current and future, gets [failure] and is never sent.
 */
internal class LinkReadiness {
    private val lock = Any()
    private var ready = false
    private var stopped: RequestFailure? = null
    private var signal = CompletableDeferred<Unit>()

    /** A transport opened: `hello` is still pending, so the link is not usable yet. */
    fun opened() {
        synchronized(lock) {
            if (stopped != null || !ready) return
            ready = false
            signal = CompletableDeferred()
        }
    }

    /** The live link queued its `hello`: requests may go out on it. */
    fun helloQueued() {
        synchronized(lock) {
            if (stopped != null || ready) return
            ready = true
            signal.complete(Unit)
        }
    }

    /** The link went down: from now on a request waits for the next link's `hello`. */
    fun linkDown() {
        synchronized(lock) {
            if (stopped != null || !ready) return
            ready = false
            signal = CompletableDeferred()
        }
    }

    /** Ends every wait for good with [failure]. */
    fun stop(failure: RequestFailure) {
        synchronized(lock) {
            if (stopped != null) return
            stopped = failure
            signal.complete(Unit)
        }
    }

    /**
     * Waits until the link is ready; null means "go ahead".  A stopped readiness
     * returns its failure immediately, before and after any wait.
     */
    suspend fun await(): RequestFailure? {
        while (true) {
            val waiting = synchronized(lock) {
                stopped?.let { return it }
                if (ready) return null
                signal
            }
            waiting.await()
        }
    }
}
