package com.phonxiety.bridge.feature.livecall

import android.os.Handler
import android.os.SystemClock
import com.phonxiety.bridge.data.LiveCallFront
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState

/**
 * The policy that brings Phonxiety's live screen back over Telecom's in-call UI after a call placed
 * from the composer: once over the initial cover after the placement, and once over the cover
 * Telecom raises when the call is answered.
 *
 * Telecom's in-call screen is an activity drawn above every app, so placing a call from this phone
 * covers the live call, and answering it covers the live screen again when the call goes ACTIVE.
 * The system binds its InCallService with `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS`, which is how
 * in-call UIs raise their own windows, and this activity issues the return from its own lifecycle
 * right after the cover, while its task is still recent. [onUserLeaveHint] tells a system cover from
 * a deliberate leave: it fires for Home and Recents, never when another app or the system takes the
 * foreground.
 *
 * The two one-shot permits in [LiveCallFront] share the placed call id and nothing else:
 *
 * - the initial permit is spent by the first cover after placement. The delay covers the placement
 *   reply: the daemon answers the composer only after Telecom has already drawn its screen, so the
 *   call is marked as this phone's a moment after the stop. A resumed screen, a deliberate leave, an
 *   exhausted wait for the reply and a hangup all close that opportunity, so a placement reply that
 *   lands late cannot pull the live screen over somebody else's call. A return that lands on a call
 *   that is already ACTIVE retires the answer permit as well: the answered screen was behind it.
 * - the answer permit opens at the stop that follows the answer, and only for the call this phone
 *   placed, only if the live screen was seen since then — by the initial return or by the activity
 *   resuming over the placed call — and only while the screen at that stop belongs to no other
 *   call. The wait then looks for ACTIVE (the daemon is a link round-trip behind Telecom's own
 *   transition) for 400 ms plus twelve retries. A call that appears during the wait is never
 *   replaced and does not spend the permit: Telecom does not stop this activity again when that
 *   call ends, so the wait keeps watching until the deadline it opened with. The deadline, a
 *   hangup, a resume and a deliberate leave close the wait without spending it, leaving the permit
 *   for this call's own next cover; a return spends it once per call. A resume over an already
 *   ACTIVE call retires it, and a spent permit never opens again, so a later cover (a hold, a
 *   reopened dialer) stays with Telecom. A wait that is open when the activity is recreated for a
 *   configuration change is adopted by the next instance with the deadline it opened with.
 *
 * Every return is issued only while the placed call is [CallPhase.DIALING] or [CallPhase.ACTIVE],
 * with the screen on.
 */
class CallFrontReturn(
    private val handler: Handler,
    private val call: () -> CallState?,
    private val screenInteractive: () -> Boolean,
    private val anotherCall: () -> Boolean,
    private val openFront: () -> Unit,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    /** Set between [onUserLeaveHint] and the next resume: the user walked away on purpose. */
    private var userLeft = false

    /** How many more times the current wait may look at the call. */
    private var attemptsLeft = 0

    /** Which opportunity the posted attempt belongs to. */
    private var stage = Stage.NONE

    /**
     * The placed call's live screen was on the phone's display since the placement: either the
     * initial return put it there, or the activity resumed over the placed call. The answer cover
     * is only pulled back when this holds; a deliberate leave clears it until a resume restores it.
     */
    private var screenWasShown = false

    private val tick = Runnable { attempt() }

    fun onResume() {
        userLeft = false
        abandonWait()
        // The screen is seen again without a return: the cover either opportunity waited for is over.
        LiveCallFront.closeOpportunity()
        LiveCallFront.closeAnswerCover()
        val placed = LiveCallFront.placedCallId()
        screenWasShown = placed != null
        if (placed != null && isAnswered(placed)) {
            // The live screen came forward over an answered call, so Telecom's answered screen is
            // already behind it: the next cover is a hold or a reopened dialer, not the answer.
            LiveCallFront.retireAnswerCover(placed)
        }
    }

    /** Home and Recents; the system in-call screen appearing is not a user leave. */
    fun onUserLeaveHint() {
        userLeft = true
        screenWasShown = false
        abandonWait()
        LiveCallFront.closeOpportunity()
        LiveCallFront.closeAnswerCover()
    }

    /**
     * The activity is going away. [recreating] is true for a configuration change: a wait already
     * open for the placed call is handed to the next instance, which adopts it in [onStart] with the
     * deadline it opened with. A finishing activity has no successor, so it closes that wait.
     */
    fun onDestroy(recreating: Boolean) {
        abandonWait()
        // A destroyed activity cannot return, and the initial cover is over either way.
        LiveCallFront.closeOpportunity()
        if (!recreating) LiveCallFront.closeAnswerCover()
    }

    /** The record of a call that already ended cannot return, so it is dropped here. */
    fun onStart() {
        val placed = LiveCallFront.placedCallId() ?: return
        val current = call()
        if (current != null && current.callId == placed && !recoverable(current)) {
            LiveCallFront.forget(placed)
            return
        }
        adoptAnswerWait()
    }

    /**
     * Called from the activity's stop. [canReturn] is the activity still able to start itself:
     * false while it is finishing or only being recreated for a configuration change.
     */
    fun onStop(canReturn: Boolean) {
        abandonWait()
        if (!canReturn) {
            // This stop may not start the activity, but a wait opened earlier for this call is
            // still this call's to return over; it keeps its own deadline.
            adoptAnswerWait()
            return
        }
        val now = clock()
        if (LiveCallFront.awaitsCover(now)) {
            // Only a stop inside the initial cover opportunity arms the placement wait. Covers by a
            // call this phone did not place, and covers after the reply timed out, fall through.
            if (anotherCall()) return
            stage = Stage.INITIAL
            attemptsLeft = RETURN_ATTEMPTS
            handler.postDelayed(tick, RETURN_DELAY_MS)
            return
        }
        // The answer raises Telecom's screen again over the live screen. The opportunity opens at
        // this stop so the return stays inside the window where this activity may start itself; the
        // wait then covers the daemon reporting the answer a link round-trip later.
        if (anotherCall()) {
            // Telecom is showing another call above this one: that screen is not the answer's, so
            // it is left in front and this call's permit is not spent on it. A wait this call
            // already had open keeps its own deadline and keeps watching for that call to end.
            if (LiveCallFront.answerCoverPending(now)) {
                adoptAnswerWait()
            } else {
                LiveCallFront.closeAnswerCover()
            }
            return
        }
        val armed = armAnswerCover(now)
        if (!armed && !LiveCallFront.answerCoverPending(now)) return
        stage = Stage.ANSWER
        attemptsLeft = ANSWER_ATTEMPTS
        handler.postDelayed(tick, RETURN_DELAY_MS)
    }

    /**
     * Takes over a wait that is already open for the placed call — one opened before a configuration
     * change, or one this instance adopted and stopped for. The deadline is the one the wait opened
     * with: [LiveCallFront] refuses a wait that ran out, so adopting never mints a fresh permit.
     */
    private fun adoptAnswerWait() {
        if (stage == Stage.ANSWER) return
        if (!LiveCallFront.answerCoverPending(clock())) return
        stage = Stage.ANSWER
        attemptsLeft = ANSWER_ATTEMPTS
        handler.postDelayed(tick, RETURN_DELAY_MS)
    }

    private fun armAnswerCover(now: Long): Boolean {
        // Without a live screen seen since the placement, the answer screen is not ours to replace:
        // the user left on purpose, or the initial return was never issued.
        if (!screenWasShown) return false
        val placed = LiveCallFront.placedCallId() ?: return false
        val current = call() ?: return false
        if (current.callId != placed || !recoverable(current)) return false
        return LiveCallFront.armAnswerCover(now)
    }

    private fun attempt() {
        when (stage) {
            Stage.INITIAL -> initialAttempt()
            Stage.ANSWER -> answerAttempt()
            Stage.NONE -> Unit
        }
    }

    private fun initialAttempt() {
        if (userLeft) {
            LiveCallFront.closeOpportunity()
            return
        }
        val now = clock()
        val pending = LiveCallFront.pendingCallId(now)
        val current = call()
        if (pending == null) {
            // The placement reply is still travelling; give it a few ticks to land. A call that is
            // already hanging up is not worth waiting for.
            if (LiveCallFront.awaitsCover(now) && (current == null || recoverable(current))) {
                retry()
            } else {
                LiveCallFront.closeOpportunity()
            }
            return
        }
        if (current == null || current.callId != pending) {
            retry()
            return
        }
        if (!recoverable(current)) {
            // Hanging up: nobody may be pulled over the end-of-call screen.
            LiveCallFront.forget(pending)
            return
        }
        if (!screenInteractive()) {
            // Nobody would see the return, and the one shot must not survive into a later cover.
            LiveCallFront.forget(pending)
            return
        }
        if (!LiveCallFront.claimReturn(pending, now)) return
        screenWasShown = true
        openFront()
        if (current.phase == CallPhase.ACTIVE) {
            // The answered call was already behind this cover, so the answer permit has nothing
            // left to wait for: a later cover is a hold or a reopened dialer.
            LiveCallFront.retireAnswerCover(pending)
        }
    }

    private fun answerAttempt() {
        if (userLeft) {
            LiveCallFront.closeAnswerCover()
            return
        }
        val now = clock()
        val placed = LiveCallFront.placedCallId()
        if (placed == null || !LiveCallFront.answerCoverPending(now)) {
            // The call ended or was forgotten, or the answer wait ran out.
            LiveCallFront.closeAnswerCover()
            return
        }
        if (anotherCall()) {
            // Another call came in over this one: its screen is not ours to replace and the permit
            // is not spent on it. Telecom does not stop this activity again when that call ends, so
            // keep watching until the deadline this wait opened with.
            handler.postDelayed(tick, RETURN_RETRY_MS)
            return
        }
        val current = call()
        if (current == null || current.callId != placed) {
            // The daemon has not published this call's next state yet.
            retryAnswer()
            return
        }
        if (current.phase == CallPhase.DIALING) {
            // Still ringing: the cover is already up, the answer is a link round-trip away.
            retryAnswer()
            return
        }
        if (current.phase != CallPhase.ACTIVE) {
            // Hanging up, or already over: nobody may be pulled over the end-of-call screen.
            LiveCallFront.closeAnswerCover()
            return
        }
        if (!screenInteractive()) {
            LiveCallFront.closeAnswerCover()
            return
        }
        if (!LiveCallFront.claimAnswerReturn(placed, now)) return
        openFront()
    }

    private fun retry() {
        if (attemptsLeft <= 0) {
            // The placement reply never came: the cover it belongs to is over.
            LiveCallFront.closeOpportunity()
            return
        }
        attemptsLeft--
        handler.postDelayed(tick, RETURN_RETRY_MS)
    }

    private fun retryAnswer() {
        if (attemptsLeft <= 0) {
            // The call never reported ACTIVE inside the wait: the answer screen stays.
            LiveCallFront.closeAnswerCover()
            return
        }
        attemptsLeft--
        handler.postDelayed(tick, RETURN_RETRY_MS)
    }

    private fun abandonWait() {
        attemptsLeft = 0
        stage = Stage.NONE
        handler.removeCallbacks(tick)
    }

    private fun isAnswered(callId: String): Boolean {
        val current = call() ?: return false
        return current.callId == callId && current.phase == CallPhase.ACTIVE
    }

    private fun recoverable(state: CallState): Boolean = state.phase == CallPhase.DIALING ||
        state.phase == CallPhase.ACTIVE

    private enum class Stage { NONE, INITIAL, ANSWER }

    private companion object {
        /** Long enough for the in-call screen to settle, short enough to still count as a cover. */
        const val RETURN_DELAY_MS = 400L

        /** The placement reply, and the answer, can be a link round-trip behind the system screen. */
        const val RETURN_RETRY_MS = 300L
        const val RETURN_ATTEMPTS = 10

        /** 400 ms plus twelve retries: 4 s, inside [LiveCallFront.ANSWER_TTL_MS]. */
        const val ANSWER_ATTEMPTS = 12
    }
}
