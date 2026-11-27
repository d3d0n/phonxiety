package com.phonxiety.bridge.designsystem

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow

/**
 * A caption in the brand's caption face: JetBrains Mono at 12 sp with wide tracking, uppercased at
 * the point of rendering. The stored string keeps its own casing, so a translation can decide.
 */
@Composable
fun Caption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val locale = LocalConfiguration.current.locales[0]
    Text(
        text = text.uppercase(locale),
        modifier = modifier,
        style = LocalPhonxietyType.current.caption,
        color = color,
        maxLines = maxLines,
        overflow = overflow
    )
}
