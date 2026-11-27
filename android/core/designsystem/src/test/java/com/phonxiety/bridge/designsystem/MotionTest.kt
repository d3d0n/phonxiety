package com.phonxiety.bridge.designsystem

import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.TweenSpec
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The motion tokens' two defects: the size transform under reduced motion, and a live setting. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MotionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reducedMotionHasNoSizeTransform() {
        assertNull(Motion.statusChange(reduceMotion = true).sizeTransform)
    }

    @Test
    fun theEnabledSizeTransformUsesTheStatusDurationAndCurve() {
        val transform = Motion.statusChange(reduceMotion = false).sizeTransform
        requireNotNull(transform)
        val spec = transform.createAnimationSpec(IntSize(10, 10), IntSize(40, 20))
        val tween = spec as TweenSpec<IntSize>
        assertEquals(Motion.STATUS_DURATION_MS, tween.durationMillis)
        assertEquals(Motion.statusCurve, tween.easing)
    }

    @Test
    fun turningAnimationsBackOnIsSeenWhileComposed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        composeRule.setContent {
            Text(if (Motion.animationsEnabled()) "on" else "off")
        }
        composeRule.onNodeWithText("off").assertExists()

        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        resolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("on").assertExists()
    }

    @Test
    fun anExplicitOverrideWinsOverTheSystemSetting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        composeRule.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides false) {
                Text(if (Motion.animationsEnabled()) "on" else "off")
            }
        }
        composeRule.onNodeWithText("off").assertExists()
    }
}
