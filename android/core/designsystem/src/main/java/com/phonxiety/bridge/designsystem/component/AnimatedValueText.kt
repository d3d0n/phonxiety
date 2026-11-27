package com.phonxiety.bridge.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.phonxiety.bridge.designsystem.Motion

/**
 * A label or value that changes in place: a state name, a measurement, a count. The new text is
 * the only thing that moves, using the shared status motion, so two rows of the same screen never
 * animate differently. At system animator scale zero the swap is immediate.
 *
 * [contentAlignment] is where the text sits when the old and the new text differ in size; the
 * default keeps a value's left edge fixed, which is the edge a reader's eye returns to.
 */
@Composable
fun AnimatedValueText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    contentAlignment: Alignment = Alignment.CenterStart
) {
    val reduceMotion = !Motion.animationsEnabled()
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = { Motion.statusChange(reduceMotion) },
        contentAlignment = contentAlignment,
        label = "AnimatedValueText"
    ) { value ->
        Text(
            text = value,
            style = style,
            color = color,
            textAlign = textAlign,
            maxLines = maxLines
        )
    }
}
