package com.phonxiety.bridge.designsystem

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import java.util.Locale

/**
 * A caption in the brand's caption face: JetBrains Mono at 12 sp with wide tracking, uppercased at
 * the point of rendering. The stored string keeps its own casing, so a translation can decide, and
 * accessibility reads that original casing instead of shouted letters.
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
        text = renderCaption(text, locale),
        modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(text) },
        style = LocalPhonxietyType.current.caption,
        color = color,
        maxLines = maxLines,
        overflow = overflow
    )
}

/** The caption's visual casing; the one place the render-site uppercase rule lives. */
internal fun renderCaption(text: String, locale: Locale): String = text.uppercase(locale)
