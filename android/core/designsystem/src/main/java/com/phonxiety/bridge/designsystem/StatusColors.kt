package com.phonxiety.bridge.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Background and foreground of one semantic status tone. */
@Immutable
data class StatusColorPair(val container: Color, val content: Color)

/**
 * Status tones as theme tokens. Every tone is a fixed brand pair, so success, warning and error
 * keep their meaning and their contrast under any dynamic scheme. None of them is magenta: Signal
 * belongs to the arrow alone.
 */
@Immutable
data class StatusColors(
    val positive: StatusColorPair,
    val warning: StatusColorPair,
    val negative: StatusColorPair,
    val neutral: StatusColorPair,
    val accent: StatusColorPair,
    /** Filled meter segments that are healthy; a strong tint rather than a text colour. */
    val positiveIndicator: Color,
    /** Filled meter segments in the hot zone. */
    val negativeIndicator: Color,
    /** The neutral dot on a pill. */
    val neutralIndicator: Color
)

/** The status token set of the current theme. */
val LocalStatusColors = staticCompositionLocalOf { statusColorsFor(PhonxietyLightColors, dark = false) }

internal fun statusColorsFor(scheme: ColorScheme, dark: Boolean): StatusColors = StatusColors(
    positive = if (dark) {
        StatusColorPair(
            container = PhonxietyColors.PositiveContainerDark,
            content = PhonxietyColors.OnPositiveContainerDark
        )
    } else {
        StatusColorPair(
            container = PhonxietyColors.PositiveContainer,
            content = PhonxietyColors.OnPositiveContainer
        )
    },
    warning = if (dark) {
        StatusColorPair(
            container = PhonxietyColors.WarningContainerDark,
            content = PhonxietyColors.OnWarningContainerDark
        )
    } else {
        StatusColorPair(
            container = PhonxietyColors.WarningContainer,
            content = PhonxietyColors.OnWarningContainer
        )
    },
    negative = if (dark) {
        StatusColorPair(
            container = PhonxietyColors.NegativeContainerDark,
            content = PhonxietyColors.OnNegativeContainerDark
        )
    } else {
        StatusColorPair(
            container = PhonxietyColors.NegativeContainer,
            content = PhonxietyColors.OnNegativeContainer
        )
    },
    neutral = StatusColorPair(container = scheme.surfaceVariant, content = scheme.onSurfaceVariant),
    accent = StatusColorPair(container = scheme.primaryContainer, content = scheme.onPrimaryContainer),
    positiveIndicator = if (dark) PhonxietyColors.PositiveIndicatorDark else PhonxietyColors.PositiveIndicator,
    negativeIndicator = if (dark) PhonxietyColors.NegativeIndicatorDark else PhonxietyColors.NegativeIndicator,
    neutralIndicator = scheme.outline
)
