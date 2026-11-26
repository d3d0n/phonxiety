package com.phonxiety.bridge.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every text pair the status tokens can put on screen clears WCAG AA, and wallpaper cannot move it. */
class StatusColorsTest {
    private val textPairs = 4.5
    private val nonTextPairs = 3.0

    @Test
    fun lightSchemePairsClearAa() {
        assertPairs(PhonxietyLightColors, dark = false)
    }

    @Test
    fun darkSchemePairsClearAa() {
        assertPairs(PhonxietyDarkColors, dark = true)
    }

    @Test
    fun successAndWarningDoNotFollowTheWallpaper() {
        val branded = statusColorsFor(PhonxietyLightColors, dark = false)
        val loudWallpaper = PhonxietyLightColors.copy(
            tertiary = Color.Magenta,
            tertiaryContainer = Color.Yellow,
            onTertiaryContainer = Color.Cyan
        )
        val dynamicLike = statusColorsFor(loudWallpaper, dark = false)
        assertEquals(branded.positive, dynamicLike.positive)
        assertEquals(branded.warning, dynamicLike.warning)
    }

    private fun assertPairs(scheme: androidx.compose.material3.ColorScheme, dark: Boolean) {
        val status = statusColorsFor(scheme, dark)
        assertAtLeast(textPairs, status.positive, "positive")
        assertAtLeast(textPairs, status.warning, "warning")
        assertAtLeast(textPairs, status.negative, "negative")
        assertAtLeast(textPairs, status.neutral, "neutral")
        assertAtLeast(textPairs, status.accent, "accent")
        assertTrue(
            "positive meter segment on surface",
            contrast(status.positiveIndicator, scheme.surface) >= nonTextPairs
        )
        assertTrue(
            "negative meter segment on surface",
            contrast(status.negativeIndicator, scheme.surface) >= nonTextPairs
        )
    }

    private fun assertAtLeast(
        minimum: Double,
        pair: StatusColorPair,
        name: String
    ) {
        val ratio = contrast(pair.container, pair.content)
        assertTrue("$name contrast was $ratio", ratio >= minimum)
    }

    private fun contrast(first: Color, second: Color): Double {
        val light = max(first.luminance(), second.luminance()) + 0.05
        val dark = min(first.luminance(), second.luminance()) + 0.05
        return light / dark
    }
}
