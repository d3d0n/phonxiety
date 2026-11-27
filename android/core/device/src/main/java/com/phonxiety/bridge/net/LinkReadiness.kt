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
 * request follows the next link within its own deadline.  A hello that fails
 * answers that attempt's waiters with a typed reason ([fail]) without making the
 * readiness terminal; [stop] is terminal: every waiter, current and future, gets
 * [failure] and is never sent.
 *
 * Each wait belongs to an attempt ([Attempt]), so a new transport opening in the
 * same instant the previous hello failed never steals the failure from the
 * requests that were already waiting.
 */
internal class LinkReadiness {
    /** One transport's wait for its hello: the waiters of that attempt wake together. */
    private class Attempt {
        val signal = CompletableDeferred<Unit>()
        var failure: RequestFailure? = null
    }

    private val lock = Any()
    private var ready = false
    private var stopped: RequestFailure? = null
    private var attempt = Attempt()

    /** A transport opened: `hello` is still pending, so the link is not usable yet. */
    fun opened() {
        synchronized(lock) {
            if (stopped != null) return
            // A new transport is a new attempt, whether the previous one was ready
            // or had failed its hello.
            if (ready || attempt.failure != null) {
                ready = false
                attempt = Attempt()
            }
        }
    }

    /** The live link queued its `hello`: requests may go out on it. */
    fun helloQueued() {
        synchronized(lock) {
            if (stopped != null || ready) return
            ready = true
            attempt.signal.complete(Unit)
        }
    }

    /** The link went down: from now on a request waits for the next link's `hello`. */
    fun linkDown() {
        synchronized(lock) {
            if (stopped != null || !ready) return
            ready = false
            attempt = Attempt()
        }
    }

    /**
     * The link's `hello` failed: every request waiting for readiness now gets [reason], and the
     * next transport waits afresh. Unlike [stop] this is not terminal — connection attempts keep
     * following the supervisor's schedule, exactly as before.
     */
    fun fail(reason: RequestFailure) {
        synchronized(lock) {
            if (stopped != null || ready) return
            attempt.failure = reason
            attempt.signal.complete(Unit)
        }
    }

    /** Ends every wait for good with [failure]. */
    fun stop(failure: RequestFailure) {
        synchronized(lock) {
            if (stopped != null) return
            stopped = failure
            attempt.signal.complete(Unit)
        }
    }

    /**
     * Waits until the link is ready; null means "go ahead".  A stopped readiness
     * returns its failure immediately, before and after any wait; the attempt a
     * waiter belongs to answers it with the hello failure it was waiting on.
     */
    suspend fun await(): RequestFailure? {
        while (true) {
            val current = synchronized(lock) {
                stopped?.let { return it }
                if (ready) return null
                attempt
            }
            current.signal.await()
            current.failure?.let { return it }
        }
    }
}
