package com.phonxiety.bridge.feature.livecall

import androidx.annotation.VisibleForTesting

/**
 * The call-scoped, one-shot permission to bring Phonxiety's live screen back over the system's
 * in-call UI. A call the computer places also runs through Telecom, but nobody on the phone asked
 * to watch that one, so the token exists only for a call placed from the composer here.
 *
 * It lives outside the activity on purpose: it is armed when the composer's placement is accepted,
 * spent the first time the screen is pulled forward, and cleared when that call ends. Recreation
 * therefore cannot re-arm it, and a second cover of the same call finds it spent.
 */
object LiveCallFront {
    private val lock = Any()

    /** The call id placed from the composer, kept until that call ends. */
    private var placedCallId: String? = null

    /** A placement request is travelling; the in-call cover it causes has not been drawn yet. */
    private var placementInFlight = false

    /** The one-shot return for [placedCallId] is still unspent. */
    private var returnAvailable = false

    /** The composer is about to ask the daemon to place a call from this phone. */
    fun expectPlacement() {
        synchronized(lock) { placementInFlight = true }
    }

    /** The placement request did not produce a call; nothing will be covered. */
    fun cancelPlacement() {
        synchronized(lock) { placementInFlight = false }
    }

    /** The daemon accepted a call this phone placed. Arms the one shot for it. */
    fun remember(callId: String) {
        if (callId.isBlank()) return
        synchronized(lock) {
            placedCallId = callId
            placementInFlight = false
            returnAvailable = true
        }
    }

    /** True while the in-call screen that followed a composer placement may still be pulled back. */
    fun awaitsCover(): Boolean = synchronized(lock) { placementInFlight || returnAvailable }

    /** The call this phone placed, whether or not its one-shot return is spent. */
    fun placedCallId(): String? = synchronized(lock) { placedCallId }

    /** The placed call id while its one-shot return is unspent, otherwise null. */
    fun pendingCallId(): String? = synchronized(lock) { placedCallId?.takeIf { returnAvailable } }

    /**
     * Spends the one-shot return for [callId]. False for a second cover of the same call, for any
     * other call (an incoming one, or one the computer placed), and once the call has ended.
     */
    fun claimReturn(callId: String): Boolean = synchronized(lock) {
        val claimed = returnAvailable && placedCallId == callId
        if (claimed) returnAvailable = false
        claimed
    }

    /** [callId] ended or is gone: the token dies with it. */
    fun forget(callId: String) {
        synchronized(lock) {
            if (placedCallId == callId) {
                placedCallId = null
                returnAvailable = false
            }
        }
    }

    @VisibleForTesting
    fun resetForTest() {
        synchronized(lock) {
            placedCallId = null
            placementInFlight = false
            returnAvailable = false
        }
    }
}
