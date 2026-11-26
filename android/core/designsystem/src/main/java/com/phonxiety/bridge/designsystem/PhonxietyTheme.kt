package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * The app theme: Material 3 Expressive motion and shapes, a branded indigo/teal palette, and
 * wallpaper-derived colour when the device supports dynamic colour.
 *
 * The palette is deliberately one source of truth with [PhonxietyColors] so screenshots, previews
 * and the app render the same colours whether or not dynamic colour is on.
 */
@Composable
fun PhonxietyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> PhonxietyDarkColors
        else -> PhonxietyLightColors
    }
    val statusColors = statusColorsFor(colorScheme, darkTheme)
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = PhonxietyShapes,
        typography = PhonxietyTypography,
        content = {
            CompositionLocalProvider(LocalStatusColors provides statusColors, content = content)
        }
    )
}

/** The branded fallback palette in light mode. */
internal val PhonxietyLightColors: ColorScheme = lightColorScheme(
    primary = PhonxietyColors.Indigo,
    onPrimary = PhonxietyColors.White,
    primaryContainer = PhonxietyColors.IndigoContainer,
    onPrimaryContainer = PhonxietyColors.IndigoOnContainer,
    secondary = PhonxietyColors.Slate,
    onSecondary = PhonxietyColors.White,
    secondaryContainer = PhonxietyColors.SlateContainer,
    onSecondaryContainer = PhonxietyColors.SlateOnContainer,
    tertiary = PhonxietyColors.Teal,
    onTertiary = PhonxietyColors.White,
    tertiaryContainer = PhonxietyColors.TealContainer,
    onTertiaryContainer = PhonxietyColors.TealOnContainer,
    error = PhonxietyColors.Red,
    errorContainer = PhonxietyColors.RedContainer,
    background = PhonxietyColors.LightBackground,
    onBackground = PhonxietyColors.LightOnSurface,
    surface = PhonxietyColors.LightBackground,
    onSurface = PhonxietyColors.LightOnSurface,
    surfaceVariant = PhonxietyColors.LightSurfaceVariant,
    surfaceContainerLowest = PhonxietyColors.LightSurfaceContainerLowest,
    surfaceContainerLow = PhonxietyColors.LightSurfaceContainerLow,
    surfaceContainer = PhonxietyColors.LightSurfaceContainer,
    surfaceContainerHigh = PhonxietyColors.LightSurfaceContainerHigh,
    surfaceContainerHighest = PhonxietyColors.LightSurfaceContainerHighest,
    onSurfaceVariant = PhonxietyColors.LightOnSurfaceVariant,
    outline = PhonxietyColors.LightOutline,
    outlineVariant = PhonxietyColors.LightOutlineVariant,
    inverseSurface = PhonxietyColors.LightOnSurface,
    inverseOnSurface = PhonxietyColors.LightBackground,
    inversePrimary = PhonxietyColors.LightInversePrimary
)

/** The branded fallback palette in dark mode. */
internal val PhonxietyDarkColors: ColorScheme = darkColorScheme(
    primary = PhonxietyColors.LightIndigo,
    onPrimary = PhonxietyColors.DarkOnPrimary,
    primaryContainer = PhonxietyColors.DarkIndigoContainer,
    onPrimaryContainer = PhonxietyColors.IndigoContainer,
    secondary = PhonxietyColors.LightSlate,
    onSecondary = PhonxietyColors.DarkOnSurface,
    secondaryContainer = PhonxietyColors.DarkSlateContainer,
    onSecondaryContainer = PhonxietyColors.SlateContainer,
    tertiary = PhonxietyColors.LightTeal,
    onTertiary = PhonxietyColors.DarkOnTeal,
    tertiaryContainer = PhonxietyColors.DarkTealContainer,
    onTertiaryContainer = PhonxietyColors.TealContainer,
    error = PhonxietyColors.LightRed,
    errorContainer = PhonxietyColors.DarkRedContainer,
    background = PhonxietyColors.DarkBackground,
    onBackground = PhonxietyColors.DarkOnSurface,
    surface = PhonxietyColors.DarkBackground,
    onSurface = PhonxietyColors.DarkOnSurface,
    surfaceVariant = PhonxietyColors.DarkSurfaceVariant,
    surfaceContainerLowest = PhonxietyColors.DarkSurfaceContainerLowest,
    surfaceContainerLow = PhonxietyColors.DarkSurfaceContainerLow,
    surfaceContainer = PhonxietyColors.DarkSurfaceContainer,
    surfaceContainerHigh = PhonxietyColors.DarkSurfaceContainerHigh,
    surfaceContainerHighest = PhonxietyColors.DarkSurfaceContainerHighest,
    onSurfaceVariant = PhonxietyColors.DarkOnSurfaceVariant,
    outline = PhonxietyColors.DarkOutline,
    outlineVariant = PhonxietyColors.DarkOutlineVariant,
    inverseSurface = PhonxietyColors.DarkOnSurface,
    inverseOnSurface = PhonxietyColors.DarkBackground,
    inversePrimary = PhonxietyColors.Indigo
)
