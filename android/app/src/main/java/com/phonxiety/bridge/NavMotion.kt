package com.phonxiety.bridge

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import com.phonxiety.bridge.designsystem.Motion

/**
 * The screen transitions, built from the design system's motion tokens: one duration and one curve
 * for every push and pop, so a navigation confirms the same thing a status change does.
 *
 * The transforms are plain values so they can be asserted; the specs hand them to `NavDisplay`.
 * Reduced motion turns every transition into an instant swap. The wizard is the one exception and
 * it is deliberate: see [wizardPredictivePop].
 */
internal class NavTransitions(
    val enter: ContentTransform,
    val pop: ContentTransform,
    val predictivePop: ContentTransform
) {
    val enterSpec: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = { enter }

    val popSpec: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = { pop }

    val predictivePopSpec: AnimatedContentTransitionScope<Scene<NavKey>>.(Int) -> ContentTransform =
        { predictivePop }
}

/** The transitions for one process: [reduceMotion] is the platform's animator verdict. */
internal fun navTransitions(reduceMotion: Boolean): NavTransitions {
    if (reduceMotion) {
        val swap = ContentTransform(EnterTransition.None, ExitTransition.None)
        return NavTransitions(enter = swap, pop = swap, predictivePop = swap)
    }
    val short = tween<Float>(durationMillis = Motion.STATUS_FADE_MS, easing = Motion.statusCurve)
    val slide = tween<IntOffset>(durationMillis = Motion.STATUS_DURATION_MS, easing = Motion.statusCurve)
    val inFromTrailing = slideInHorizontally(slide) { width -> width / ENTER_SHIFT }
    val outToLeading = slideOutHorizontally(slide) { width -> -width / EXIT_SHIFT }
    val inFromLeading = slideInHorizontally(slide) { width -> -width / ENTER_SHIFT }
    val outToTrailing = slideOutHorizontally(slide) { width -> width / EXIT_SHIFT }
    return NavTransitions(
        enter = ContentTransform(
            targetContentEnter = fadeIn(short) + inFromTrailing,
            initialContentExit = fadeOut(short) + outToLeading
        ),
        pop = ContentTransform(
            targetContentEnter = fadeIn(short) + inFromLeading,
            initialContentExit = fadeOut(short) + outToTrailing
        ),
        // The gesture already moves the content; the scene below only has to settle under it.
        predictivePop = ContentTransform(
            targetContentEnter = fadeIn(short),
            initialContentExit = scaleOut(
                targetScale = PREDICTIVE_SCALE,
                animationSpec = tween(durationMillis = Motion.STATUS_DURATION_MS, easing = Motion.statusCurve)
            )
        )
    )
}

/**
 * The wizard entry's predictive-back preview.
 *
 * It ignores the platform's reduced-motion setting on purpose: the gesture is the owner's own
 * drag, not an animation the app starts, and the wizard is the first screen of a fresh install, so
 * a preview that showed nothing would look like a frozen app right up to the moment it closed.
 */
internal fun wizardPredictivePop(): ContentTransform = ContentTransform(
    targetContentEnter = fadeIn(
        tween(durationMillis = Motion.STATUS_FADE_MS, easing = Motion.statusCurve)
    ),
    initialContentExit = scaleOut(
        targetScale = PREDICTIVE_SCALE,
        animationSpec = tween(durationMillis = Motion.STATUS_DURATION_MS, easing = Motion.statusCurve)
    )
)

/** An eighth of the width is enough to read as a push without parking the old screen off-screen. */
private const val ENTER_SHIFT = 8

/** The leaving screen moves a little less, so the push reads as one surface sliding over another. */
private const val EXIT_SHIFT = 12

private const val PREDICTIVE_SCALE = 0.94f
