package com.phonxiety.bridge.feature.dashboard.cards

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.phonxiety.bridge.data.bitrateKbps
import com.phonxiety.bridge.designsystem.component.LevelMeter
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatTile
import com.phonxiety.bridge.designsystem.component.StatTone
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.feature.dashboard.R
import com.phonxiety.bridge.model.AudioState

/** The playback ring is 48 kHz mono, so one millisecond of downlink audio is 48 samples. */
internal const val AUDIO_SAMPLES_PER_MILLISECOND = 48L

/** With no stream there are no measurements: the card says when one starts instead of showing zeros. */
@Composable
internal fun AudioCard(audio: AudioState) {
    val codec = audio.codec
    SectionCard(
        title = stringResource(R.string.dashboard_audio_title),
        status = {
            if (codec == null) {
                StatusPill(
                    label = stringResource(R.string.dashboard_no_stream),
                    tone = StatusTone.NEUTRAL
                )
            } else {
                StatusPill(label = humanizeToken(codec), tone = StatusTone.ACCENT)
            }
        }
    ) {
        if (codec == null) {
            Text(
                text = stringResource(R.string.dashboard_no_audio_stream),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        TileRow {
            StatTile(
                label = stringResource(R.string.dashboard_bitrate),
                value = bitrateKbps(audio.bitrate)?.toString() ?: MISSING_VALUE,
                mono = true,
                unit = if (audio.bitrate != null) UNIT_KILOBITS else null
            )
            StatTile(
                label = stringResource(R.string.dashboard_frames_out),
                value = audio.framesOut.toString(),
                mono = true
            )
            StatTile(
                label = stringResource(R.string.dashboard_playback_drops),
                value = playbackDropMillis(audio.drops).toString(),
                mono = true,
                tone = if (audio.drops > 0) StatTone.NEGATIVE else StatTone.NEUTRAL,
                unit = UNIT_MILLISECONDS
            )
        }
        Text(
            text = stringResource(R.string.dashboard_playback_drops_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LevelMeter(
            label = stringResource(R.string.dashboard_microphone),
            levelDbfs = audio.txLevelDbfs,
            modifier = Modifier.fillMaxWidth()
        )
        LevelMeter(
            label = stringResource(R.string.dashboard_far_end),
            levelDbfs = audio.rxLevelDbfs,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Playback overflows are counted in samples; the tile reports the audio time they cost. */
internal fun playbackDropMillis(samples: Long): Long = samples / AUDIO_SAMPLES_PER_MILLISECOND

/** A daemon token the screen shows as words: `reservation_time` → `Reservation time`. */
internal fun humanizeToken(token: String): String = token.replace('_', ' ').replaceFirstChar { it.uppercase() }
