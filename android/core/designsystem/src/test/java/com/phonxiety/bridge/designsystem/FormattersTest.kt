package com.phonxiety.bridge.designsystem

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.designsystem.component.formatDbfs
import com.phonxiety.bridge.designsystem.component.formatMeasure
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Timers and prose durations share one platform-backed implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FormattersTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun elapsedTimeUsesThePlatformClock() {
        assertEquals("00:00", formatElapsedTime(0))
        assertEquals("00:45", formatElapsedTime(45))
        assertEquals("01:09", formatElapsedTime(69))
        assertEquals("1:00:00", formatElapsedTime(3_600))
        assertEquals("1:02:05", formatElapsedTime(3_725))
    }

    @Test
    fun proseDurationsUseNoBreakSpaces() {
        assertEquals("45\u00A0s", formatDuration(resources, 45))
        assertEquals("1\u00A0min", formatDuration(resources, 60))
        assertEquals("4\u00A0min", formatDuration(resources, 240))
        assertEquals("2\u00A0h\u00A005\u00A0min", formatDuration(resources, 7_500))
    }

    @Test
    fun unitsJoinTheWayTheWritingRulesAsk() {
        assertEquals("78%", formatMeasure("78", "%", Locale.ENGLISH))
        assertEquals("41\u00A0ms", formatMeasure("41", "ms", Locale.ENGLISH))
        assertEquals("64\u00A0kbit/s", formatMeasure("64", "kbit/s", Locale.ENGLISH))
        assertEquals("—", formatMeasure("—", null, Locale.ENGLISH))
        assertEquals("−21\u00A0dBFS", formatDbfs(-21f))
        assertEquals("0\u00A0dBFS", formatDbfs(0f))
    }

    @Test
    fun percentUsesTheLocalesOwnSpacing() {
        assertEquals("78%", formatMeasure("78", "%", Locale.ENGLISH))
        assertEquals("78\u00A0%", formatMeasure("78", "%", Locale.FRENCH))
        assertEquals("78\u00A0%", formatMeasure("78", "%", Locale.GERMAN))
        assertEquals("—", formatMeasure("—", "%", Locale.FRENCH))
        assertEquals("41\u00A0ms", formatMeasure("41", "ms", Locale.FRENCH))
    }

    @Test
    fun composableDurationMatchesTheResourcesResolver() {
        composeRule.setContent { Text(formatDuration(7_500)) }
        composeRule.onNodeWithText("2\u00A0h\u00A005\u00A0min").assertTextEquals("2\u00A0h\u00A005\u00A0min")
    }
}
