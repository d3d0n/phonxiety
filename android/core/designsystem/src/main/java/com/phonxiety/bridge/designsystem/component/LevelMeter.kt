package com.phonxiety.bridge.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.Caption
import com.phonxiety.bridge.designsystem.LocalPhonxietyType
import com.phonxiety.bridge.designsystem.LocalStatusColors
import kotlin.math.max
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
    val status = LocalStatusColors.current
    val offColor = MaterialTheme.colorScheme.surfaceVariant
    val lowColor = status.positiveIndicator
    val hotColor = status.negativeIndicator
    Column(modifier = modifier) {
        MeterHeader(
            label = label,
            // A missing measurement is "—": a unit would claim a number that is not there.
            reading = levelDbfs?.let { formatDbfs(it) } ?: "—",
            modifier = Modifier.fillMaxWidth()
        )
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

/**
 * The label and the reading. The reading is measured at its natural width so it never breaks; when
 * the two no longer share a row the label sits above it and the reading keeps a line of its own.
 * Intrinsic widths decide that, so each child is measured exactly once, which Compose requires.
 * The reading is never ellipsized: when it alone is wider than the meter it wraps, so every digit
 * stays on screen.
 */
@Composable
private fun MeterHeader(label: String, reading: String, modifier: Modifier = Modifier) {
    val readingStyle = LocalPhonxietyType.current.code(MaterialTheme.typography.labelMedium)
    Layout(
        modifier = modifier,
        content = {
            Caption(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = reading, style = readingStyle)
        }
    ) { measurables, constraints ->
        val gap = meterHeaderGap.roundToPx()
        val maxWidth = constraints.maxWidth
        val labelMeasurable = measurables[0]
        val readingMeasurable = measurables[1]
        val labelWidth = labelMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val readingWidth = readingMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        if (labelWidth + gap + readingWidth <= maxWidth) {
            val readingPlaceable = readingMeasurable.measure(Constraints())
            val labelPlaceable = labelMeasurable.measure(
                Constraints(maxWidth = (maxWidth - gap - readingPlaceable.width).coerceAtLeast(0))
            )
            val width = if (maxWidth == Constraints.Infinity) {
                labelPlaceable.width + gap + readingPlaceable.width
            } else {
                maxWidth
            }
            val height = max(labelPlaceable.height, readingPlaceable.height)
            layout(width, height) {
                labelPlaceable.placeRelative(0, (height - labelPlaceable.height) / 2)
                readingPlaceable.placeRelative(
                    width - readingPlaceable.width,
                    (height - readingPlaceable.height) / 2
                )
            }
        } else {
            val width = maxWidth.coerceAtLeast(0)
            val labelPlaceable = labelMeasurable.measure(Constraints(maxWidth = width))
            val readingPlaceable = readingMeasurable.measure(Constraints(maxWidth = width))
            layout(width, labelPlaceable.height + gap + readingPlaceable.height) {
                labelPlaceable.placeRelative(0, 0)
                readingPlaceable.placeRelative(width - readingPlaceable.width, labelPlaceable.height + gap)
            }
        }
    }
}

private val meterHeaderGap = 8.dp

/** `−12 dBFS`, with a Unicode minus and a no-break space so the value lines up in monospace. */
fun formatDbfs(value: Float): String {
    val rounded = value.roundToInt()
    val magnitude = if (rounded == 0) "0" else Math.abs(rounded).toString()
    return "−$magnitude\u00A0dBFS".takeIf { rounded < 0 } ?: "$magnitude\u00A0dBFS"
}
