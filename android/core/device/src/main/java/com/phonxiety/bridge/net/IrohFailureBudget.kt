package com.phonxiety.bridge.net

/**
 * The iroh endpoint's recovery account: consecutive attempts that failed before
 * opening, and the pending request to rebind the endpoint.
 *
 * Trips at [TRIP_AFTER] consecutive failures; an accepted open resets the
 * streak.  The first trip needs no history and is allowed immediately, even
 * with the clock at zero.  Further trips wait [COOLDOWN_MS] after the *actual*
 * recycle — the bind that consumed the request — not after the request.  A trip
 * only sets a pending request, which stays pending until a live bind consumes
 * it, so an attempt that never binds cannot lose it.
 */
internal class IrohFailureBudget(private val clock: () -> Long) {
    companion object {
        /** Consecutive iroh failures before the endpoint is rebound. */
        const val TRIP_AFTER = 3

        /** At most one recycle per window, measured from the actual recycle. */
        const val COOLDOWN_MS = 60_000L
    }

    private val lock = Any()
    private var consecutive = 0
    private var pending = false
    private var lastRecycleAt: Long? = null

    /** One iroh attempt opened: the failure streak is over. */
    fun opened() {
        synchronized(lock) { consecutive = 0 }
    }

    /** One iroh attempt failed before opening; true when it trips a fresh-bind request. */
    fun failed(): Boolean = synchronized(lock) {
        consecutive++
        if (consecutive < TRIP_AFTER || pending) return false
        val last = lastRecycleAt
        if (last != null && clock() - last < COOLDOWN_MS) return false
        pending = true
        true
    }

    /** Feeds one attempt outcome into the account. */
    fun note(outcome: AttemptOutcome) {
        when (outcome) {
            AttemptOutcome.OPENED -> opened()
            AttemptOutcome.FAILED_BEFORE_OPEN -> failed()
        }
    }

    /** True while a trip's fresh-bind request has not been consumed. */
    fun pending(): Boolean = synchronized(lock) { pending }

    /** A bind consumed the pending request: the cooldown starts at the actual recycle. */
    fun consume(): Boolean = synchronized(lock) {
        if (!pending) return false
        pending = false
        lastRecycleAt = clock()
        true
    }
}
