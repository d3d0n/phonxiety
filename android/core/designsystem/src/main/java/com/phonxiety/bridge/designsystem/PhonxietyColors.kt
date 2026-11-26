package com.phonxiety.bridge.designsystem

import androidx.compose.ui.graphics.Color

/** Raw brand colours; only the theme maps them to Material roles. */
internal object PhonxietyColors {
    val Indigo = Color(0xFF3F4A7E)
    val LightIndigo = Color(0xFFBAC3FF)
    val IndigoContainer = Color(0xFFDEE0FF)
    val IndigoOnContainer = Color(0xFF121C4C)
    val DarkIndigoContainer = Color(0xFF2A3568)
    val DarkOnPrimary = Color(0xFF0A1546)
    val LightInversePrimary = Color(0xFFBAC3FF)

    val Slate = Color(0xFF5A5D72)
    val LightSlate = Color(0xFFC2C5DD)
    val SlateContainer = Color(0xFFDEE1F9)
    val SlateOnContainer = Color(0xFF171A2C)
    val DarkSlateContainer = Color(0xFF414659)

    val Teal = Color(0xFF146C5E)
    val LightTeal = Color(0xFF8BD5C6)
    val TealContainer = Color(0xFFA7F2E0)
    val TealOnContainer = Color(0xFF00201A)
    val DarkTealContainer = Color(0xFF005045)
    val DarkOnTeal = Color(0xFF00382F)

    val Red = Color(0xFFBA1A1A)
    val LightRed = Color(0xFFFFB4AB)
    val RedContainer = Color(0xFFFFDAD6)
    val DarkRedContainer = Color(0xFF93000A)

    val LightBackground = Color(0xFFFBF8FF)
    val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
    val LightSurfaceContainerLow = Color(0xFFF5F2FB)
    val LightSurfaceContainer = Color(0xFFEFEDF5)
    val LightSurfaceContainerHigh = Color(0xFFE9E7F0)
    val LightSurfaceContainerHighest = Color(0xFFE3E1EA)
    val LightOnSurface = Color(0xFF1B1B21)
    val LightSurfaceVariant = Color(0xFFE3E1EC)
    val LightOnSurfaceVariant = Color(0xFF46464F)
    val LightOutline = Color(0xFF767680)
    val LightOutlineVariant = Color(0xFFC7C5D0)

    val DarkBackground = Color(0xFF131318)
    val DarkSurfaceContainerLowest = Color(0xFF0E0E13)
    val DarkSurfaceContainerLow = Color(0xFF1B1B21)
    val DarkSurfaceContainer = Color(0xFF1F1F25)
    val DarkSurfaceContainerHigh = Color(0xFF292930)
    val DarkSurfaceContainerHighest = Color(0xFF34343B)
    val DarkOnSurface = Color(0xFFE4E1E9)
    val DarkSurfaceVariant = Color(0xFF46464F)
    val DarkOnSurfaceVariant = Color(0xFFC7C5D0)
    val DarkOutline = Color(0xFF90909A)
    val DarkOutlineVariant = Color(0xFF46464F)

    val White = Color(0xFFFFFFFF)

    /**
     * Signal colours for pills and meters; tones are semantic, not raw component colours.
     * Success and warning never come from the wallpaper palette, so an "OK" pill cannot turn
     * salmon under a warm system accent.
     */
    val Warn = Color(0xFF8A5300)
    val WarnContainer = Color(0xFFFFE2BE)
    val WarnDark = Color(0xFFFFB870)
    val WarnContainerDark = Color(0xFF4A2F00)
}
