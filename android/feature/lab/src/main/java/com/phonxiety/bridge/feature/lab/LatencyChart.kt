package com.phonxiety.bridge.feature.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * RTT percentiles of one run as three bars, with a line at the 400 ms point above which a
 * conversation starts to feel queued. A measurement the daemon did not return stays an empty track
 * with "—" under it, never a zero-height bar that reads as "0 ms".
 *
 * The numbers are also in the accessibility description: the chart is a summary, the values are the
 * evidence.
 */
@Composable
fun LatencyPercentileChart(
    p50Ms: Long?,
    p95Ms: Long?,
    maxMs: Long?,
    modifier: Modifier = Modifier
) {
    if (p50Ms == null && p95Ms == null && maxMs == null) return
    val track = MaterialTheme.colorScheme.surfaceVariant
    val bar = MaterialTheme.colorScheme.primary
    val worn = MaterialTheme.colorScheme.error
    val threshold = MaterialTheme.colorScheme.outline
    val top = maxOf((p50Ms ?: 0L).toFloat(), (p95Ms ?: 0L).toFloat(), (maxMs ?: 0L).toFloat(), THRESHOLD_MS)
    val values = listOf(p50Ms, p95Ms, maxMs)
    val none = stringResource(R.string.lab_none)
    val median = stringResource(R.string.lab_stat_median)
    val p95 = stringResource(R.string.lab_stat_p95)
    val worst = stringResource(R.string.lab_stat_worst)
    val description = stringResource(
        R.string.lab_chart_description,
        p50Ms?.let(::millisText) ?: none,
        p95Ms?.let(::millisText) ?: none,
        maxMs?.let(::millisText) ?: none
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .semantics { contentDescription = description }
        ) {
            val barGap = size.width * 0.06f
            val barWidth = (size.width - barGap * 2) / 3f
            values.forEachIndexed { index, value ->
                val left = index * (barWidth + barGap)
                drawRoundRect(
                    color = track,
                    topLeft = Offset(left, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = CornerRadius(6.dp.toPx())
                )
                if (value == null) return@forEachIndexed
                val fraction = (value.toFloat() / top).coerceIn(0f, 1f)
                val height = size.height * fraction
                drawRoundRect(
                    color = if (value > THRESHOLD_MS) worn else bar,
                    topLeft = Offset(left, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(6.dp.toPx())
                )
            }
            val thresholdY = size.height * (1f - THRESHOLD_MS / top)
            drawLine(
                color = threshold,
                start = Offset(0f, thresholdY),
                end = Offset(size.width, thresholdY),
                strokeWidth = 1.dp.toPx()
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            listOf(median to p50Ms, p95 to p95Ms, worst to maxMs).forEach { (label, value) ->
                Text(
                    text = stringResource(
                        R.string.lab_chart_caption,
                        label,
                        value?.let(::millisText) ?: none
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** The p95 of recent runs, oldest to newest, so a trend is visible without reading a table. */
@Composable
fun RttHistoryChart(p95Values: List<Long>, modifier: Modifier = Modifier) {
    if (p95Values.size < 2) return
    val line = MaterialTheme.colorScheme.tertiary
    val worn = MaterialTheme.colorScheme.error
    val grid = MaterialTheme.colorScheme.outlineVariant
    val top = maxOf(p95Values.max().toFloat(), THRESHOLD_MS)
    val description = stringResource(
        R.string.lab_history_chart_description,
        millisText(p95Values.lastOrNull() ?: 0L)
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics { contentDescription = description }
    ) {
        val usable = size.height - 4.dp.toPx()
        val step = if (p95Values.size > 1) size.width / (p95Values.size - 1) else 0f
        drawLine(grid, Offset(0f, usable), Offset(size.width, usable), strokeWidth = 1.dp.toPx())
        val path = Path()
        p95Values.forEachIndexed { index, value ->
            val x = index * step
            val y = usable * (1f - (value.toFloat() / top).coerceIn(0f, 1f))
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = line, style = Stroke(width = 2.dp.toPx()))
        val lastY = usable * (1f - (p95Values.last().toFloat() / top).coerceIn(0f, 1f))
        drawCircle(
            color = if (p95Values.last() > THRESHOLD_MS) worn else line,
            radius = 3.dp.toPx(),
            center = Offset(size.width, lastY)
        )
    }
}

/** A conversation is comfortable below this p95; above it the chart marks the bar in error colour. */
const val THRESHOLD_MS = 400f
