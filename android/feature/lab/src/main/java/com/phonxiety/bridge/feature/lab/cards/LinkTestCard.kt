package com.phonxiety.bridge.feature.lab.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.STACK_AT_FONT_SCALE
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatTile
import com.phonxiety.bridge.designsystem.component.StatTone
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.feature.lab.LabCallbacks
import com.phonxiety.bridge.feature.lab.LatencyPercentileChart
import com.phonxiety.bridge.feature.lab.LinkTestUi
import com.phonxiety.bridge.feature.lab.LocalLabClock
import com.phonxiety.bridge.feature.lab.R
import com.phonxiety.bridge.feature.lab.RttHistoryChart
import com.phonxiety.bridge.feature.lab.codecLabel
import com.phonxiety.bridge.feature.lab.localTime
import com.phonxiety.bridge.feature.lab.millisText
import com.phonxiety.bridge.feature.lab.monoMillis
import com.phonxiety.bridge.feature.lab.p95Tone
import com.phonxiety.bridge.feature.lab.percentText
import com.phonxiety.bridge.feature.lab.rateLabel
import com.phonxiety.bridge.feature.lab.tone
import com.phonxiety.bridge.feature.lab.transportLabel
import com.phonxiety.bridge.feature.lab.verdictLabel
import com.phonxiety.bridge.model.LinkTestResult

@Composable
internal fun LinkTestCard(link: LinkTestUi, callbacks: LabCallbacks) {
    val seconds = link.seconds.toInt()
    val durationName = stringResource(R.string.lab_link_duration)
    val durationState = stringResource(R.string.lab_link_duration_state, seconds)
    SectionCard(
        title = stringResource(R.string.lab_link_title),
        subtitle = stringResource(R.string.lab_link_subtitle),
        status = {
            link.last?.let {
                StatusPill(label = verdictLabel(it.verdict, it.healthy), tone = it.tone())
            }
        }
    ) {
        WrapRow {
            CodecChoice(stringResource(R.string.lab_codec_auto), link.codec == null) { callbacks.onLinkCodec(null) }
            CodecChoice(stringResource(R.string.lab_codec_opus), link.codec == "opus") { callbacks.onLinkCodec("opus") }
            CodecChoice(stringResource(R.string.lab_codec_pcm), link.codec == "pcm") { callbacks.onLinkCodec("pcm") }
        }
        WrapRow {
            CodecChoice(stringResource(R.string.lab_transport_stream), link.transport == "stream") {
                callbacks.onLinkTransport("stream")
            }
            CodecChoice(stringResource(R.string.lab_transport_datagram), link.transport == "datagram") {
                callbacks.onLinkTransport("datagram")
            }
        }
        Text(
            text = "$durationName · $durationState",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = link.seconds,
            onValueChange = callbacks.onLinkSeconds,
            valueRange = 1f..30f,
            modifier = Modifier.semantics {
                contentDescription = durationName
                stateDescription = durationState
            }
        )
        PrimaryActionButton(
            label = stringResource(if (link.running) R.string.lab_link_running else R.string.lab_link_run),
            onClick = callbacks.onRunLinkTest,
            enabled = !link.running,
            busy = link.running
        )
        if (link.running) {
            Text(
                text = stringResource(R.string.lab_link_sending, seconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        link.error?.let { failure ->
            ErrorCard(
                title = stringResource(R.string.lab_link_failed),
                message = failure.message.asString(),
                output = failure.code
            )
        }
        link.last?.let { result ->
            HorizontalDivider()
            LatencyStats(result)
            LatencyPercentileChart(result.p50, result.p95, result.max)
            LinkTestDetails(result)
        }
        if (link.history.isNotEmpty()) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.lab_history_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }
            )
            RttHistoryChart(link.history.mapNotNull { it.p95 }.reversed())
            link.history.take(5).forEach { run -> HistoryRow(run) }
        }
    }
}

/**
 * Four measurements: two per row while a half-width tile still holds its caption, one per row from
 * [STACK_AT_FONT_SCALE], where the p95 caption would otherwise wrap a word or two per line.
 */
@Composable
private fun LatencyStats(result: LinkTestResult) {
    val stacked = LocalDensity.current.fontScale >= STACK_AT_FONT_SCALE
    val readings = listOf(
        LatencyReading(
            label = stringResource(R.string.lab_stat_median),
            value = result.p50?.let(::monoMillis) ?: stringResource(R.string.lab_none)
        ),
        LatencyReading(
            label = stringResource(R.string.lab_stat_p95),
            value = result.p95?.let(::monoMillis) ?: stringResource(R.string.lab_none),
            tone = result.p95Tone(),
            caption = stringResource(R.string.lab_stat_p95_caption)
        ),
        LatencyReading(
            label = stringResource(R.string.lab_stat_worst),
            value = result.max?.let(::monoMillis) ?: stringResource(R.string.lab_none)
        ),
        LatencyReading(
            label = stringResource(R.string.lab_stat_loss),
            value = result.loss?.let(::percentText) ?: stringResource(R.string.lab_none)
        )
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (stacked) {
            readings.forEach { reading ->
                StatTile(
                    label = reading.label,
                    value = reading.value,
                    mono = true,
                    tone = reading.tone,
                    caption = reading.caption,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            readings.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { reading ->
                        StatTile(
                            label = reading.label,
                            value = reading.value,
                            mono = true,
                            tone = reading.tone,
                            caption = reading.caption,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** One latency measurement, collected before layout so both arrangements draw the same facts. */
private data class LatencyReading(
    val label: String,
    val value: String,
    val tone: StatTone = StatTone.NEUTRAL,
    val caption: String? = null
)

@Composable
private fun HistoryRow(run: LinkTestResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = localTime(run.at, LocalLabClock.current()) ?: stringResource(R.string.lab_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            StatusPill(label = verdictLabel(run.verdict, run.healthy), tone = run.tone())
        }
        Text(
            text = stringResource(
                R.string.lab_history_row,
                codecLabel(run.codec),
                run.p95?.let(::millisText) ?: stringResource(R.string.lab_none),
                run.loss?.let(::percentText) ?: stringResource(R.string.lab_none)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Each measured fact on its own labelled row, so no value travels without its name. */
@Composable
private fun LinkTestDetails(result: LinkTestResult) {
    result.route?.takeIf { it.isNotBlank() }?.let {
        KeyValueRow(label = stringResource(R.string.lab_link_path), value = it)
    }
    result.transport?.let {
        KeyValueRow(label = stringResource(R.string.lab_link_transport), value = transportLabel(it))
    }
    result.throughputBps?.let {
        KeyValueRow(label = stringResource(R.string.lab_link_throughput), value = rateLabel(it), mono = true)
    }
    val frames = result.frames
    if (frames.sent > 0) {
        KeyValueRow(
            label = stringResource(R.string.lab_link_frames),
            value = stringResource(R.string.lab_link_frames_value, frames.echoed, frames.sent),
            mono = true
        )
    }
}
