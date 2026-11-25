package com.phonxiety.bridge.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme()

/**
 * Base theme for the app's Compose surfaces.
 *
 * TODO(designsystem): replace the bare light scheme with the Material 3
 * Expressive palette and typography.
 */
@Composable
fun PhonxietyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content
    )
}
