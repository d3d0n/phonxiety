package com.phonxiety.bridge.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.PhonxietyColors
import kotlin.math.roundToInt

/** The level a 16-bit VOICE_* frame reports when nothing is audible. */
const val METER_FLOOR_DBFS: Float = -60f

/**
 * A segmented dBFS meter for one audio direction, the shape a mixing desk uses: silence is empty,
 * full scale fills it. Values above 0 dBFS are clamped, because the engine never reports them.
 */
@Composable
fun LevelMeter(
    label: String,
    levelDbfs: Float?,
    modifier: Modifier = Modifier,
    segments: Int = 24
) {
    val fraction = levelDbfs?.let { ((it - METER_FLOOR_DBFS) / -METER_FLOOR_DBFS).coerceIn(0f, 1f) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val offColor = MaterialTheme.colorScheme.surfaceVariant
    val lowColor = if (dark) PhonxietyColors.LightTeal else PhonxietyColors.Teal
    val hotColor = MaterialTheme.colorScheme.error
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = levelDbfs?.let { formatDbfs(it) } ?: "— dBFS",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .padding(top = 4.dp)
        ) {
            val gap = 3.dp.toPx()
            val segmentWidth = ((size.width - gap * (segments - 1)) / segments).coerceAtLeast(1f)
            val lit = fraction?.let { (it * segments).roundToInt() } ?: 0
            for (index in 0 until segments) {
                val x = index * (segmentWidth + gap)
                val isLit = index < lit
                val color: Color = when {
                    !isLit -> offColor
                    index >= segments - 3 -> hotColor
                    else -> lowColor
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, 0f),
                    size = Size(segmentWidth, size.height),
                    cornerRadius = CornerRadius(size.height / 2f)
                )
            }
        }
    }
}

/** `-12 dBFS`, with a Unicode minus so the number lines up in monospace. */
fun formatDbfs(value: Float): String {
    val rounded = value.roundToInt()
    val magnitude = if (rounded == 0) "0" else Math.abs(rounded).toString()
    return "−$magnitude dBFS".takeIf { rounded < 0 } ?: "$magnitude dBFS"
}
