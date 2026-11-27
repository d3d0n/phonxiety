package com.phonxiety.bridge.designsystem

import android.animation.ValueAnimator
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Motion tokens: one duration and one curve for the state changes the app makes. A status swap is
 * short on purpose — it confirms that the same element changed instead of replaying the screen.
 */
object Motion {
    /** The whole status change, from the first frame of the old label to the last of the new. */
    const val STATUS_DURATION_MS = 220

    /** The fade inside a status change; the slide and the size change take the whole duration. */
    const val STATUS_FADE_MS = 120

    /** How far an entering label rises, as a fraction of its own height. */
    const val STATUS_RISE = 0.12f

    /** The standard status animation curve. */
    val statusCurve = FastOutSlowInEasing

    /** The status duration as an animation spec, for callers that animate a value themselves. */
    val status: FiniteAnimationSpec<Float> = tween(durationMillis = STATUS_DURATION_MS, easing = statusCurve)

    /**
     * The enter/exit pair for a label whose text changes in place. With [reduceMotion] both sides
     * are [EnterTransition.None]/[ExitTransition.None]: the new label appears at once, so a user
     * who asked for less motion sees the change, not a transition.
     */
    fun statusChange(reduceMotion: Boolean): ContentTransform = if (reduceMotion) {
        EnterTransition.None togetherWith ExitTransition.None
    } else {
        fadeIn(tween(durationMillis = STATUS_FADE_MS, easing = statusCurve)) +
            slideInVertically(
                animationSpec = tween(durationMillis = STATUS_DURATION_MS, easing = statusCurve),
                initialOffsetY = { height -> (height * STATUS_RISE).toInt() }
            ) togetherWith
            fadeOut(tween(durationMillis = STATUS_FADE_MS, easing = statusCurve))
    }

    /** Whether transitions run; the platform turns animators off at system animator scale zero. */
    @Composable
    fun animationsEnabled(): Boolean = LocalMotionEnabled.current
}

/**
 * The system's reduced-motion verdict for the current composition, overridable so a preview or a
 * test can pin either side. The default follows the platform animator scale, which the user sets to
 * zero to turn animation off.
 */
val LocalMotionEnabled = compositionLocalOf { ValueAnimator.areAnimatorsEnabled() }
