package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.component.LevelMeter
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * At 320 dp and 200 % text the caption and the reading no longer share a row. The reading takes a
 * line of its own instead of breaking inside "dBFS", and the caption moves above it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LevelMeterLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aReadingThatCannotShareTheLabelRowTakesItsOwnLine() {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = false, dynamicColor = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    LevelMeter(
                        label = "Microphone",
                        levelDbfs = -18f,
                        // A 320 dp screen has 288 dp of content beside its 16 dp padding.
                        modifier = Modifier.width(288.dp)
                    )
                }
            }
        }

        val label = composeRule.onNodeWithText("Microphone").bounds()
        val reading = composeRule.onNodeWithText("−18\u00A0dBFS").bounds()
        assertTrue(
            "the reading sits below the caption",
            reading.top.value >= label.bottom.value
        )
        assertTrue(
            "the reading keeps one line",
            (reading.bottom - reading.top).value < (label.bottom - label.top).value * 1.8f
        )
    }

    /**
     * Narrower than the reading itself. The header must lay out once and let the reading wrap, so
     * nothing is measured twice, ellipsized or dropped.
     */
    @Test
    fun aReadingWiderThanTheMeterWrapsWithoutLosingDigits() {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = false, dynamicColor = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    LevelMeter(
                        label = "Microphone",
                        levelDbfs = -18f,
                        modifier = Modifier.width(60.dp)
                    )
                }
            }
        }

        val reading = composeRule.onNodeWithText("−18\u00A0dBFS").bounds()
        assertTrue(
            "the whole reading fits the meter's width",
            (reading.right - reading.left).value <= 60f + 0.5f
        )
        assertTrue(
            "the reading wraps instead of being cut after one line",
            (reading.bottom - reading.top).value > 40f
        )
    }

    private fun SemanticsNodeInteraction.bounds() = getUnclippedBoundsInRoot()
}
