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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The app theme: Material 3 Expressive motion, brand shapes and type, and the brand palette.
 *
 * Dynamic colour is off by default: the brand is the design, and a wallpaper-derived scheme cannot
 * guarantee Signal stays reserved or that the derived tones keep their contrast. The parameter
 * remains for tests and for a future opt-in.
 */
@Composable
fun PhonxietyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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
    val type = remember { PhonxietyType() }
    val brand = if (darkTheme) BrandTokens.Dark else BrandTokens.Light
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = PhonxietyShapes,
        typography = PhonxietyTypography,
        content = {
            CompositionLocalProvider(
                LocalStatusColors provides statusColors,
                LocalPhonxietyType provides type,
                LocalBrand provides brand,
                content = content
            )
        }
    )
}

/** The branded fallback palette in light mode: Paper background, Void text, Plate primary. */
internal val PhonxietyLightColors: ColorScheme = lightColorScheme(
    primary = PhonxietyColors.Plate,
    onPrimary = PhonxietyColors.Paper,
    primaryContainer = PhonxietyColors.LightPrimaryContainer,
    onPrimaryContainer = PhonxietyColors.LightOnPrimaryContainer,
    secondary = PhonxietyColors.LightSecondary,
    onSecondary = PhonxietyColors.White,
    secondaryContainer = PhonxietyColors.LightSecondaryContainer,
    onSecondaryContainer = PhonxietyColors.LightOnSecondaryContainer,
    tertiary = PhonxietyColors.LightTertiary,
    onTertiary = PhonxietyColors.White,
    tertiaryContainer = PhonxietyColors.LightTertiaryContainer,
    onTertiaryContainer = PhonxietyColors.LightOnTertiaryContainer,
    error = PhonxietyColors.LightError,
    onError = PhonxietyColors.LightOnError,
    errorContainer = PhonxietyColors.LightErrorContainer,
    onErrorContainer = PhonxietyColors.LightOnErrorContainer,
    background = PhonxietyColors.Paper,
    onBackground = PhonxietyColors.Void,
    surface = PhonxietyColors.Paper,
    onSurface = PhonxietyColors.Void,
    surfaceVariant = PhonxietyColors.LightSurfaceVariant,
    surfaceContainerLowest = PhonxietyColors.LightSurfaceLowest,
    surfaceContainerLow = PhonxietyColors.LightSurfaceLow,
    surfaceContainer = PhonxietyColors.LightSurfaceContainer,
    surfaceContainerHigh = PhonxietyColors.LightSurfaceHigh,
    surfaceContainerHighest = PhonxietyColors.LightSurfaceHighest,
    surfaceDim = PhonxietyColors.LightSurfaceHighest,
    surfaceBright = PhonxietyColors.White,
    onSurfaceVariant = PhonxietyColors.LightOnSurfaceVariant,
    outline = PhonxietyColors.LightOutline,
    outlineVariant = PhonxietyColors.LightOutlineVariant,
    inverseSurface = PhonxietyColors.Void,
    inverseOnSurface = PhonxietyColors.Trace,
    inversePrimary = PhonxietyColors.Trace
)

/** The branded palette in dark mode: Void surfaces graded toward Plate, Trace text. */
internal val PhonxietyDarkColors: ColorScheme = darkColorScheme(
    primary = PhonxietyColors.Trace,
    onPrimary = PhonxietyColors.Void,
    primaryContainer = PhonxietyColors.Plate,
    onPrimaryContainer = PhonxietyColors.Trace,
    secondary = PhonxietyColors.DarkSecondary,
    onSecondary = PhonxietyColors.Void,
    secondaryContainer = PhonxietyColors.DarkSecondaryContainer,
    onSecondaryContainer = PhonxietyColors.Trace,
    tertiary = PhonxietyColors.DarkTertiary,
    onTertiary = PhonxietyColors.DarkOnTertiary,
    tertiaryContainer = PhonxietyColors.DarkTertiaryContainer,
    onTertiaryContainer = PhonxietyColors.DarkOnTertiaryContainer,
    error = PhonxietyColors.DarkError,
    onError = PhonxietyColors.DarkOnError,
    errorContainer = PhonxietyColors.DarkErrorContainer,
    onErrorContainer = PhonxietyColors.DarkOnErrorContainer,
    background = PhonxietyColors.Void,
    onBackground = PhonxietyColors.Trace,
    surface = PhonxietyColors.Void,
    onSurface = PhonxietyColors.Trace,
    surfaceVariant = PhonxietyColors.DarkSurfaceVariant,
    surfaceContainerLowest = PhonxietyColors.DarkSurfaceLowest,
    surfaceContainerLow = PhonxietyColors.DarkSurfaceLow,
    surfaceContainer = PhonxietyColors.DarkSurfaceContainer,
    surfaceContainerHigh = PhonxietyColors.DarkSurfaceHigh,
    surfaceContainerHighest = PhonxietyColors.DarkSurfaceHighest,
    surfaceDim = PhonxietyColors.Void,
    surfaceBright = PhonxietyColors.DarkSurfaceHigh,
    onSurfaceVariant = PhonxietyColors.Mute,
    outline = PhonxietyColors.DarkOutline,
    outlineVariant = PhonxietyColors.DarkOutlineVariant,
    inverseSurface = PhonxietyColors.Trace,
    inverseOnSurface = PhonxietyColors.Void,
    inversePrimary = PhonxietyColors.Plate
)
