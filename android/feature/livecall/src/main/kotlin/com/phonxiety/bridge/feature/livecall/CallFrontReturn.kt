package com.phonxiety.bridge.feature.livecall

import android.os.Handler
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState

/**
 * The policy that brings Phonxiety's live screen back over Telecom's in-call UI, at most once per
 * call placed from the composer.
 *
 * Telecom's in-call screen is an activity drawn above every app, so placing a call from this phone
 * covers the live call. The system binds its InCallService with
 * `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS`, which is how in-call UIs raise their own windows, and
 * this activity issues the return from its own lifecycle right after the cover, while its task is
 * still recent. [onUserLeaveHint] tells a system cover from a deliberate leave: it fires for Home
 * and Recents, never when another app or the system takes the foreground.
 *
 * The token in [LiveCallFront] makes the return one-shot per placed call: the first cover after
 * placement spends it, later covers of the same call, a recreated activity, and covers by any other
 * call (an incoming one, one the computer placed) find nothing to spend. The delay covers the
 * placement reply: the daemon answers the composer only after Telecom has already drawn its screen,
 * so the call is marked as this phone's a moment after the stop.
 */
class CallFrontReturn(
    private val handler: Handler,
    private val call: () -> CallState?,
    private val screenInteractive: () -> Boolean,
    private val openFront: () -> Unit
) {
    /** Set between [onUserLeaveHint] and the next resume: the user walked away on purpose. */
    private var userLeft = false

    /** How many more times the return may wait for the placement reply. */
    private var attemptsLeft = 0

    private val tick = Runnable { attempt() }

    fun onResume() {
        userLeft = false
        handler.removeCallbacks(tick)
    }

    /** Home and Recents; the system in-call screen appearing is not a user leave. */
    fun onUserLeaveHint() {
        userLeft = true
    }

    fun onDestroy() {
        handler.removeCallbacks(tick)
    }

    /** The token of a call that already ended cannot return, so it is dropped here. */
    fun onStart() {
        val placed = LiveCallFront.placedCallId()
        val current = call()
        if (placed != null && current?.callId == placed && current.phase == CallPhase.ENDED) {
            LiveCallFront.forget(placed)
        }
    }

    /**
     * Called from the activity's stop. [canReturn] is the activity still able to start itself:
     * false while it is finishing or only being recreated for a configuration change.
     */
    fun onStop(canReturn: Boolean) {
        handler.removeCallbacks(tick)
        if (!canReturn) return
        // Only a stop that may follow a composer placement arms the wait. Every later stop of a
        // call whose token is spent, and every cover by a call this phone did not place, is left to
        // Telecom's own screen.
        if (!LiveCallFront.awaitsCover()) return
        attemptsLeft = RETURN_ATTEMPTS
        handler.postDelayed(tick, RETURN_DELAY_MS)
    }

    private fun attempt() {
        if (userLeft) return
        val pending = LiveCallFront.pendingCallId()
        if (pending == null) {
            // The placement reply is still travelling; give it a few ticks to land.
            if (LiveCallFront.awaitsCover() && call()?.phase != CallPhase.ENDED) retry()
            return
        }
        val current = call()
        if (current == null || current.callId != pending) {
            retry()
            return
        }
        if (current.phase == CallPhase.ENDED) {
            LiveCallFront.forget(pending)
            return
        }
        if (!screenInteractive()) {
            // Nobody would see the return, and the one shot must not survive into a later cover.
            LiveCallFront.forget(pending)
            return
        }
        if (!LiveCallFront.claimReturn(pending)) return
        openFront()
    }

    private fun retry() {
        if (attemptsLeft <= 0) return
        attemptsLeft--
        handler.postDelayed(tick, RETURN_RETRY_MS)
    }

    private companion object {
        /** Long enough for the in-call screen to settle, short enough to still count as a cover. */
        const val RETURN_DELAY_MS = 400L

        /** The placement reply can be a link round-trip behind the system screen. */
        const val RETURN_RETRY_MS = 300L
        const val RETURN_ATTEMPTS = 10
    }
}
