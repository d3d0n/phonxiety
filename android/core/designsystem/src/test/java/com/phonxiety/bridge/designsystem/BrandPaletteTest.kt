package com.phonxiety.bridge.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette is the brand's, and Signal is reserved: no text role and no background role may use
 * the mark's colour, so a screen never reads magenta by accident.
 */
class BrandPaletteTest {
    private val void = Color(0xFF0A0710)
    private val signal = Color(0xFFFF4FD8)
    private val trace = Color(0xFFF6D8FF)
    private val plate = Color(0xFF5B2A86)
    private val mute = Color(0xFFB99CCC)
    private val paper = Color(0xFFF4F1F6)

    @Test
    fun darkSchemeUsesTheBrandRoles() {
        val scheme = PhonxietyDarkColors
        assertEquals(void, scheme.background)
        assertEquals(void, scheme.surface)
        assertEquals(trace, scheme.onSurface)
        assertEquals(trace, scheme.onBackground)
        assertEquals(mute, scheme.onSurfaceVariant)
        assertEquals(trace, scheme.primary)
        assertEquals(void, scheme.onPrimary)
        assertEquals(plate, scheme.primaryContainer)
        assertEquals(trace, scheme.onPrimaryContainer)
    }

    @Test
    fun lightSchemeUsesTheBrandRoles() {
        val scheme = PhonxietyLightColors
        assertEquals(paper, scheme.background)
        assertEquals(paper, scheme.surface)
        assertEquals(void, scheme.onSurface)
        assertEquals(void, scheme.onBackground)
        assertEquals(plate, scheme.primary)
        assertEquals(paper, scheme.onPrimary)
    }

    @Test
    fun signalIsNotATextOrBackgroundRole() {
        listOf(PhonxietyDarkColors to "dark", PhonxietyLightColors to "light").forEach { (scheme, name) ->
            textAndBackgroundRoles(scheme).forEach { (role, color) ->
                assertTrue("$name $role used Signal", color != signal)
            }
            val status = statusColorsFor(scheme, dark = name == "dark")
            listOf(
                "positive" to status.positive,
                "warning" to status.warning,
                "negative" to status.negative,
                "neutral" to status.neutral,
                "accent" to status.accent
            ).forEach { (role, pair) ->
                assertTrue("$name $role container used Signal", pair.container != signal)
                assertTrue("$name $role content used Signal", pair.content != signal)
            }
            assertTrue("$name positive indicator used Signal", status.positiveIndicator != signal)
            assertTrue("$name negative indicator used Signal", status.negativeIndicator != signal)
        }
    }

    @Test
    fun everyTextPairClearsAaAndOutlinesStayVisible() {
        listOf(PhonxietyDarkColors to "dark", PhonxietyLightColors to "light").forEach { (scheme, name) ->
            assertTrue("$name onSurface", contrast(scheme.onSurface, scheme.surface) >= 4.5)
            assertTrue(
                "$name onSurface on the highest container",
                contrast(scheme.onSurface, scheme.surfaceContainerHighest) >= 4.5
            )
            assertTrue(
                "$name onSurfaceVariant on the background",
                contrast(scheme.onSurfaceVariant, scheme.background) >= 4.5
            )
            assertTrue(
                "$name onSurfaceVariant on the highest container",
                contrast(scheme.onSurfaceVariant, scheme.surfaceContainerHighest) >= 4.5
            )
            assertTrue(
                "$name onPrimary on primary",
                contrast(scheme.onPrimary, scheme.primary) >= 4.5
            )
            assertTrue(
                "$name onPrimaryContainer on primaryContainer",
                contrast(scheme.onPrimaryContainer, scheme.primaryContainer) >= 4.5
            )
            assertTrue(
                "$name outline on the background",
                contrast(scheme.outline, scheme.background) >= 3.0
            )
            assertTrue(
                "$name outlineVariant on the highest container",
                contrast(scheme.outlineVariant, scheme.surfaceContainerHighest) >= 3.0
            )
        }
    }

    private fun textAndBackgroundRoles(scheme: ColorScheme): List<Pair<String, Color>> = listOf(
        "primary" to scheme.primary,
        "onPrimary" to scheme.onPrimary,
        "primaryContainer" to scheme.primaryContainer,
        "onPrimaryContainer" to scheme.onPrimaryContainer,
        "secondary" to scheme.secondary,
        "onSecondary" to scheme.onSecondary,
        "secondaryContainer" to scheme.secondaryContainer,
        "onSecondaryContainer" to scheme.onSecondaryContainer,
        "tertiary" to scheme.tertiary,
        "onTertiary" to scheme.onTertiary,
        "tertiaryContainer" to scheme.tertiaryContainer,
        "onTertiaryContainer" to scheme.onTertiaryContainer,
        "error" to scheme.error,
        "onError" to scheme.onError,
        "errorContainer" to scheme.errorContainer,
        "onErrorContainer" to scheme.onErrorContainer,
        "background" to scheme.background,
        "onBackground" to scheme.onBackground,
        "surface" to scheme.surface,
        "onSurface" to scheme.onSurface,
        "surfaceVariant" to scheme.surfaceVariant,
        "onSurfaceVariant" to scheme.onSurfaceVariant,
        "surfaceContainerLowest" to scheme.surfaceContainerLowest,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
        "surfaceDim" to scheme.surfaceDim,
        "surfaceBright" to scheme.surfaceBright,
        "outline" to scheme.outline,
        "outlineVariant" to scheme.outlineVariant,
        "inverseSurface" to scheme.inverseSurface,
        "inverseOnSurface" to scheme.inverseOnSurface,
        "inversePrimary" to scheme.inversePrimary
    )

    private fun contrast(first: Color, second: Color): Double {
        val light = max(first.luminance(), second.luminance()) + 0.05
        val dark = min(first.luminance(), second.luminance()) + 0.05
        return light / dark
    }
}
