package com.phonxiety.bridge.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Background and foreground of one semantic status tone. */
@Immutable
data class StatusColorPair(val container: Color, val content: Color)

/**
 * Status tones as theme tokens. Success and warning are fixed pairs, so they keep their meaning
 * under any wallpaper palette; error and neutral follow the Material roles the platform defines.
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
            container = PhonxietyColors.DarkTealContainer,
            content = PhonxietyColors.TealContainer
        )
    } else {
        StatusColorPair(
            container = PhonxietyColors.TealContainer,
            content = PhonxietyColors.TealOnContainer
        )
    },
    warning = if (dark) {
        StatusColorPair(
            container = PhonxietyColors.WarnContainerDark,
            content = PhonxietyColors.WarnDark
        )
    } else {
        StatusColorPair(
            container = PhonxietyColors.WarnContainer,
            content = PhonxietyColors.Warn
        )
    },
    negative = StatusColorPair(container = scheme.errorContainer, content = scheme.onErrorContainer),
    neutral = StatusColorPair(container = scheme.surfaceVariant, content = scheme.onSurfaceVariant),
    accent = StatusColorPair(container = scheme.primaryContainer, content = scheme.onPrimaryContainer),
    positiveIndicator = if (dark) PhonxietyColors.LightTeal else PhonxietyColors.Teal,
    negativeIndicator = if (dark) PhonxietyColors.LightRed else PhonxietyColors.Red,
    neutralIndicator = scheme.outline
)
