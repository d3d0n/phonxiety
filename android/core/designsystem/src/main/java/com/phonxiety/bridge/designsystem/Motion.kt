package com.phonxiety.bridge.designsystem

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

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
     * are [EnterTransition.None]/[ExitTransition.None] and there is no size transform either: the
     * new label appears at its own size at once, so a user who asked for less motion never sees
     * the old box clip the new text. The enabled transition sizes with the same duration and curve
     * as the slide, not the default spring.
     */
    fun statusChange(reduceMotion: Boolean): ContentTransform = if (reduceMotion) {
        ContentTransform(EnterTransition.None, ExitTransition.None, 0f, null)
    } else {
        val enter = fadeIn(tween(durationMillis = STATUS_FADE_MS, easing = statusCurve)) +
            slideInVertically(
                animationSpec = tween(durationMillis = STATUS_DURATION_MS, easing = statusCurve),
                initialOffsetY = { height -> (height * STATUS_RISE).toInt() }
            )
        val exit = fadeOut(tween(durationMillis = STATUS_FADE_MS, easing = statusCurve))
        ContentTransform(
            targetContentEnter = enter,
            initialContentExit = exit,
            sizeTransform = SizeTransform(clip = false) { _, _ ->
                tween(durationMillis = STATUS_DURATION_MS, easing = statusCurve)
            }
        )
    }

    /**
     * Whether transitions run. An explicit [LocalMotionEnabled] value wins; otherwise this follows
     * the system animator setting and keeps following it while composed, so turning animations
     * back on in Settings is seen without restarting the app.
     */
    @Composable
    fun animationsEnabled(): Boolean = LocalMotionEnabled.current ?: observeSystemAnimations().value
}

/**
 * The system's reduced-motion verdict for the current composition, overridable so a preview or a
 * test can pin either side. `null` means "follow the platform": the caller observes the animator
 * duration scale rather than reading it once.
 */
val LocalMotionEnabled = compositionLocalOf<Boolean?> { null }

/** Reads the animator duration scale; zero disables animation, any positive value enables it. */
internal fun readAnimatorScale(resolver: ContentResolver): Boolean = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

@Composable
private fun observeSystemAnimations(): State<Boolean> {
    val resolver = LocalContext.current.contentResolver
    val enabled = remember(resolver) { mutableStateOf(readAnimatorScale(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                enabled.value = readAnimatorScale(resolver)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}
