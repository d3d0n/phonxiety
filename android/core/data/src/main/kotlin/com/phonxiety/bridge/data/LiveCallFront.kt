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
 * A call has two separate one-shot permits, both bound to the same placed call id:
 *
 * - the *initial* cover that follows the placement. [expectPlacement] opens one opportunity,
 *   [remember] arms the return inside it, and [closeOpportunity] ends it for good — a resumed
 *   screen, a deliberate leave, an expired wait for the placement reply, or a hangup all close it.
 *   A placement reply that lands after the opportunity closed therefore cannot reopen it, and a
 *   later cover of any other screen (an incoming call above an owned one included) finds nothing
 *   to spend.
 * - the *answer* cover: Telecom's in-call UI raises itself again when the call goes ACTIVE, over the
 *   live screen the initial return had put in front. [armAnswerCover] opens that opportunity once
 *   per call, and [claimAnswerReturn] spends it once. [closeAnswerCover] ends it when the screen
 *   comes back, the user walks away, or the wait expires; [armAnswerCover] then refuses for the rest
 *   of the call, so a later cover (a hold, a reopened dialer) is left to Telecom's own screen.
 *
 * Each opportunity has its own deadline, so a placement or an answer that never covered anything
 * cannot stay armed indefinitely.
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

    /** The answer cover of [placedCallId] has not been spent yet. */
    private var answerAvailable = false

    /** The answer cover may still be pulled back; expired by [ANSWER_TTL_MS]. */
    private var answerOpen = false

    /** Monotonic time the answer cover opened; read only while [answerOpen]. */
    private var answerOpenedAt = 0L

    /** This call's answer cover was already opened or declined; it cannot be opened again. */
    private var answerDecided = false

    /** The composer is about to ask the daemon to place a call from this phone. */
    fun expectPlacement(nowElapsed: Long = SystemClock.elapsedRealtime()) {
        synchronized(lock) {
            placementInFlight = true
            returnAvailable = false
            opportunityOpen = true
            opportunityOpenedAt = nowElapsed
            resetAnswer()
        }
    }

    /** The placement request did not produce a call; nothing will be covered. */
    fun cancelPlacement() {
        synchronized(lock) {
            placementInFlight = false
            returnAvailable = false
            opportunityOpen = false
            resetAnswer()
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
            // The answer permit belongs to the call being remembered here, never to a previous one.
            resetAnswer()
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
     * Opens the answer-cover return for the placed call, at most once per call. False when no call
     * was placed from this phone, when the answer cover was already opened or declined for this
     * call, and when the call's record is gone. A second cover of the same call is left to Telecom.
     */
    fun armAnswerCover(nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean = synchronized(lock) {
        val armed = placedCallId != null && !answerDecided
        if (armed) {
            answerDecided = true
            answerAvailable = true
            answerOpen = true
            answerOpenedAt = nowElapsed
        }
        armed
    }

    /** True while the answer cover of the placed call may still be pulled back, before it is spent. */
    fun answerCoverPending(nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean = synchronized(lock) {
        answerOpen && answerAvailable && answerStillOpen(nowElapsed)
    }

    /**
     * Spends the answer-cover return for [callId]. False for a second cover of the same call (a
     * hold, a reopened dialer), for any other call, once the call ended, and after the answer
     * opportunity closed. The permit never opens again for this call.
     */
    fun claimAnswerReturn(callId: String, nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean = synchronized(lock) {
        val claimed = answerOpen && answerAvailable && placedCallId == callId && answerStillOpen(nowElapsed)
        if (claimed) {
            answerAvailable = false
            answerOpen = false
        }
        claimed
    }

    /**
     * The answer cover is over: the screen came back, the user walked away, the wait for the call
     * to go ACTIVE timed out, or the call is ending. This call gets no second attempt.
     */
    fun closeAnswerCover() {
        synchronized(lock) {
            answerAvailable = false
            answerOpen = false
        }
    }

    /**
     * The initial cover opportunity is over: the screen came back, the user walked away, the wait
     * for the placement reply timed out, or the call is hanging up. No later initial cover can
     * return; the separately scoped answer cover is closed by [closeAnswerCover].
     */
    fun closeOpportunity() {
        synchronized(lock) {
            opportunityOpen = false
            placementInFlight = false
            returnAvailable = false
        }
    }

    /** [callId] ended or is gone: both tokens die with it. */
    fun forget(callId: String) {
        synchronized(lock) {
            if (placedCallId == callId) {
                placedCallId = null
                returnAvailable = false
                opportunityOpen = false
                placementInFlight = false
                resetAnswer()
            }
        }
    }

    private fun stillOpen(nowElapsed: Long): Boolean = opportunityOpen &&
        nowElapsed - opportunityOpenedAt <= OPPORTUNITY_TTL_MS

    private fun answerStillOpen(nowElapsed: Long): Boolean = nowElapsed - answerOpenedAt <= ANSWER_TTL_MS

    private fun resetAnswer() {
        answerAvailable = false
        answerOpen = false
        answerOpenedAt = 0L
        answerDecided = false
    }

    @VisibleForTesting
    fun resetForTest() {
        synchronized(lock) {
            placedCallId = null
            placementInFlight = false
            returnAvailable = false
            opportunityOpen = false
            opportunityOpenedAt = 0L
            resetAnswer()
        }
    }

    /**
     * Long enough for a link round-trip plus the activity's retry budget, short enough that a
     * placement which never covered anything cannot be spent on a later, unrelated cover.
     */
    @VisibleForTesting
    const val OPPORTUNITY_TTL_MS = 10_000L

    /**
     * The answer cover opens at the stop that follows the answer; the wait for the call's ACTIVE
     * state has to fit inside this window, and a cover after it is somebody else's screen.
     */
    @VisibleForTesting
    const val ANSWER_TTL_MS = 6_000L
}
