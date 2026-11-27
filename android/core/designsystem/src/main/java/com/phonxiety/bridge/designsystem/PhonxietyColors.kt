package com.phonxiety.bridge.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Raw brand colours, straight from the brand kit. Only the theme maps them to Material roles.
 *
 * Signal is the arrow mark and nothing else: no text role and no background role may use it, so a
 * screen keeps its reading contrast whether or not the mark is on it.
 */
internal object PhonxietyColors {
    /** Main dark background. */
    val Void = Color(0xFF0A0710)

    /** The arrow mark on dark backgrounds; never text, never a background. */
    val Signal = Color(0xFFFF4FD8)

    /** Names and headings on dark backgrounds. */
    val Trace = Color(0xFFF6D8FF)

    /** Plates: the icon background and container fills. */
    val Plate = Color(0xFF5B2A86)

    /** Captions and secondary text on dark backgrounds. */
    val Mute = Color(0xFFB99CCC)

    /** Main light background. */
    val Paper = Color(0xFFF4F1F6)

    /** The arrow mark on light backgrounds: Signal, darkened until it clears AA on Paper. */
    val SignalOnLight = Color(0xFFB3128F)

    /** Ink of the wordmark on light backgrounds, from the kit. */
    val Ink = Color(0xFF140A1E)

    /** Void lightened toward Plate, 10 %: the first step above the background in dark mode. */
    val DarkSurfaceLow = Color(0xFF120A1C)

    /** Void toward Plate, 16 %. */
    val DarkSurfaceContainer = Color(0xFF170D23)

    /** Void toward Plate, 22 %. */
    val DarkSurfaceHigh = Color(0xFF1C0F2A)

    /** Void toward Plate, 30 %. */
    val DarkSurfaceHighest = Color(0xFF221233)

    /** Void toward Plate, 38 %: the surface-variant role. */
    val DarkSurfaceVariant = Color(0xFF29143D)

    /** One step below Void, for sheets that sit under the background. */
    val DarkSurfaceLowest = Color(0xFF070410)

    /** Mute pulled 22 % toward Void: a 5.2:1 boundary on the darkest backgrounds. */
    val DarkOutline = Color(0xFF8E7AA0)

    /** A 3.9:1 boundary on Void and 3.4:1 on the highest container. */
    val DarkOutlineVariant = Color(0xFF7A6688)

    /** Secondary lilac for the dark scheme, between Trace and Plate. */
    val DarkSecondary = Color(0xFFD9B8F5)

    /** A deeper plate for secondary containers. */
    val DarkSecondaryContainer = Color(0xFF3B2152)

    /** A cool lilac that stays clear of Signal. */
    val DarkTertiary = Color(0xFFCBB4F0)

    /** Tertiary container on dark. */
    val DarkTertiaryContainer = Color(0xFF3A1C55)

    /** On a tertiary container. */
    val DarkOnTertiaryContainer = Color(0xFFEDD9FF)

    val DarkOnTertiary = Color(0xFF1C0A2B)

    /** Error on dark: a warm coral, deliberately not magenta. */
    val DarkError = Color(0xFFFFB4A8)

    val DarkOnError = Color(0xFF690005)

    val DarkErrorContainer = Color(0xFF6E1B12)

    val DarkOnErrorContainer = Color(0xFFFFDAD3)

    /** Light container steps: Paper toward Plate, in single-digit percentages. */
    val LightSurfaceLowest = Color(0xFFFFFFFF)

    val LightSurfaceLow = Color(0xFFFAF7FC)

    val LightSurfaceContainer = Color(0xFFF0EAF5)

    val LightSurfaceHigh = Color(0xFFEBE3F2)

    val LightSurfaceHighest = Color(0xFFE5DBEE)

    val LightSurfaceVariant = Color(0xFFEBE3F2)

    /** Captions on Paper: 7.4:1, and still 6.2:1 on the highest container. */
    val LightOnSurfaceVariant = Color(0xFF5C4570)

    /** A 4.3:1 boundary on Paper, used where a control outline must be seen. */
    val LightOutline = Color(0xFF7D6B8B)

    val LightOutlineVariant = Color(0xFF857092)

    val LightPrimaryContainer = Color(0xFFEBDCF7)

    val LightOnPrimaryContainer = Color(0xFF2E1146)

    val LightSecondary = Color(0xFF62427C)

    val LightSecondaryContainer = Color(0xFFEEE4F5)

    val LightOnSecondaryContainer = Color(0xFF2E1146)

    val LightTertiary = Color(0xFF7E3D9F)

    val LightTertiaryContainer = Color(0xFFF3DDFB)

    val LightOnTertiaryContainer = Color(0xFF3A0F4C)

    val LightError = Color(0xFFB3261E)

    val LightOnError = Color(0xFFFFFFFF)

    val LightErrorContainer = Color(0xFFFFDAD6)

    val LightOnErrorContainer = Color(0xFF410002)

    val White = Color(0xFFFFFFFF)

    /** Success, warning and error pairs for both backgrounds; none is magenta. */
    val PositiveContainer = Color(0xFFC6F0DF)

    val OnPositiveContainer = Color(0xFF07402F)

    val PositiveIndicator = Color(0xFF0B6B4F)

    val PositiveContainerDark = Color(0xFF0E3B2E)

    val OnPositiveContainerDark = Color(0xFF86E7C3)

    val PositiveIndicatorDark = Color(0xFF6FE0B8)

    val WarningContainer = Color(0xFFFFE3BF)

    val OnWarningContainer = Color(0xFF6B3D00)

    val WarningContainerDark = Color(0xFF4A2E00)

    val OnWarningContainerDark = Color(0xFFFFC98A)

    val NegativeContainer = Color(0xFFFFDAD6)

    val OnNegativeContainer = Color(0xFF6B1B12)

    val NegativeIndicator = Color(0xFFB3261E)

    val NegativeContainerDark = Color(0xFF4A120C)

    val OnNegativeContainerDark = Color(0xFFFFB4A8)

    val NegativeIndicatorDark = Color(0xFFFF8F80)
}
