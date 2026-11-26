package com.phonxiety.bridge.data

import android.os.SystemClock
import androidx.annotation.VisibleForTesting

/**
 * The call-scoped, one-shot permission to bring Phonxiety's own screen back over the system's
 * in-call UI. A call the computer places also runs through Telecom, but nobody on the phone asked
 * to watch that one, so the token exists only for a call placed from this phone: the composer, or
 * the test lab's test call.
 *
 * It lives in the data layer because both of those screens arm it and neither may depend on the
 * other. It lives outside the activity on purpose: it is armed when a placement is accepted, spent
 * the first time the screen is pulled forward, and cleared when that call ends. Recreation
 * therefore cannot re-arm it, and a second cover of the same call finds it spent.
 *
 * The permission is bound to the *initial* cover only. [expectPlacement] opens one opportunity,
 * [remember] arms the return inside it, and [closeOpportunity] ends it for good — a resumed screen,
 * a deliberate leave, an expired wait for the placement reply, or a hangup all close it. A
 * placement reply that lands after the opportunity closed therefore cannot reopen it, and a later
 * cover of any other screen (an incoming call above an owned one included) finds nothing to spend.
 * The deadline keeps a placement that never covered anything from staying armed indefinitely.
 */
object LiveCallFront {
    private val lock = Any()

    /** The call id placed from the composer, kept until that call ends. */
    private var placedCallId: String? = null

    /** A placement request is travelling; the in-call cover it causes has not been drawn yet. */
    private var placementInFlight = false

    /** The one-shot return for [placedCallId] is still unspent. */
    private var returnAvailable = false

    /** The initial cover that follows this phone's placement may still be pulled back. */
    private var opportunityOpen = false

    /** Monotonic time the opportunity opened; read only while [opportunityOpen]. */
    private var opportunityOpenedAt = 0L

    /** The composer is about to ask the daemon to place a call from this phone. */
    fun expectPlacement(nowElapsed: Long = SystemClock.elapsedRealtime()) {
        synchronized(lock) {
            placementInFlight = true
            returnAvailable = false
            opportunityOpen = true
            opportunityOpenedAt = nowElapsed
        }
    }

    /** The placement request did not produce a call; nothing will be covered. */
    fun cancelPlacement() {
        synchronized(lock) {
            placementInFlight = false
            returnAvailable = false
            opportunityOpen = false
        }
    }

    /** The daemon accepted a call this phone placed. Arms the one shot for it. */
    fun remember(callId: String, nowElapsed: Long = SystemClock.elapsedRealtime()) {
        if (callId.isBlank()) return
        synchronized(lock) {
            placedCallId = callId
            placementInFlight = false
            // A reply that arrives after the cover opportunity closed must not open it again.
            returnAvailable = stillOpen(nowElapsed)
        }
    }

    /** True while the in-call screen that followed a composer placement may still be pulled back. */
    fun awaitsCover(nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean = synchronized(lock) {
        stillOpen(nowElapsed) && (placementInFlight || returnAvailable)
    }

    /** The call this phone placed, whether or not its one-shot return is spent. */
    fun placedCallId(): String? = synchronized(lock) { placedCallId }

    /** The placed call id while its one-shot return is unspent, otherwise null. */
    fun pendingCallId(nowElapsed: Long = SystemClock.elapsedRealtime()): String? = synchronized(lock) {
        placedCallId?.takeIf { returnAvailable && stillOpen(nowElapsed) }
    }

    /**
     * Spends the one-shot return for [callId]. False for a second cover of the same call, for any
     * other call (an incoming one, or one the computer placed), once the call has ended, and after
     * the opportunity closed.
     */
    fun claimReturn(callId: String, nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean = synchronized(lock) {
        val claimed = returnAvailable && placedCallId == callId && stillOpen(nowElapsed)
        if (claimed) {
            returnAvailable = false
            opportunityOpen = false
        }
        claimed
    }

    /**
     * The initial cover opportunity is over: the screen came back, the user walked away, the wait
     * for the placement reply timed out, or the call is hanging up. No later cover can return.
     */
    fun closeOpportunity() {
        synchronized(lock) {
            opportunityOpen = false
            placementInFlight = false
            returnAvailable = false
        }
    }

    /** [callId] ended or is gone: the token dies with it. */
    fun forget(callId: String) {
        synchronized(lock) {
            if (placedCallId == callId) {
                placedCallId = null
                returnAvailable = false
                opportunityOpen = false
                placementInFlight = false
            }
        }
    }

    private fun stillOpen(nowElapsed: Long): Boolean = opportunityOpen &&
        nowElapsed - opportunityOpenedAt <= OPPORTUNITY_TTL_MS

    @VisibleForTesting
    fun resetForTest() {
        synchronized(lock) {
            placedCallId = null
            placementInFlight = false
            returnAvailable = false
            opportunityOpen = false
            opportunityOpenedAt = 0L
        }
    }

    /**
     * Long enough for a link round-trip plus the activity's retry budget, short enough that a
     * placement which never covered anything cannot be spent on a later, unrelated cover.
     */
    @VisibleForTesting
    const val OPPORTUNITY_TTL_MS = 10_000L
}
