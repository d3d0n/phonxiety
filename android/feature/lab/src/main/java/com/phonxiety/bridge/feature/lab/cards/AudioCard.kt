package com.phonxiety.bridge.feature.lab.cards

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.ToggleRow
import com.phonxiety.bridge.feature.lab.AudioUi
import com.phonxiety.bridge.feature.lab.LabCallbacks
import com.phonxiety.bridge.feature.lab.LabUiState
import com.phonxiety.bridge.feature.lab.R
import com.phonxiety.bridge.feature.lab.numberText
import kotlin.math.roundToInt

/** The tone frequencies the lab offers; a drag cannot land on 1 kHz by accident, a step can. */
internal val TONE_PRESETS_HZ = listOf(300, 440, 700, 1_000, 1_500, 2_000, 3_000, 4_000)

/** The length of the tone a live playback plays; the automatic one has its own slider. */
internal const val TONE_SECONDS = 5

internal const val LEVEL_STEP_DB = 5f

@Composable
internal fun AudioCard(state: LabUiState, callbacks: LabCallbacks) {
    val reason = state.audioDisabledReason
    // The automatic test tone takes its frequency and level from these sliders, so they are
    // usable while that option is on even with no call; only live playback waits for one.
    val live = reason == null
    val toneSetup = state.testCallTone
    SectionCard(
        title = stringResource(R.string.lab_audio_title),
        subtitle = stringResource(R.string.lab_audio_subtitle)
    ) {
        if (reason != null) {
            Text(
                text = reason.asString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (live) {
            ToggleRow(
                title = stringResource(R.string.lab_audio_loopback),
                checked = state.audio.loopbackOn,
                onCheckedChange = callbacks.onSetLoopback,
                detail = stringResource(R.string.lab_audio_loopback_detail),
                enabled = !state.audio.busy && state.loopbackSupported != false
            )
        }
        if (live || toneSetup) {
            // With no call the sliders configure the automatic test tone, not playback: name them.
            if (!live) {
                Text(
                    text = stringResource(R.string.lab_audio_tone_setup),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() }
                )
            }
            ToneFrequencySlider(audio = state.audio, onToneHz = callbacks.onToneHz)
            ToneLevelSlider(audio = state.audio, onToneDbfs = callbacks.onToneDbfs)
        }
        if (live) {
            PrimaryActionButton(
                label = stringResource(if (state.audio.busy) R.string.lab_audio_play_busy else R.string.lab_audio_play),
                onClick = callbacks.onPlayTone,
                enabled = !state.audio.busy && state.toneSupported != false,
                busy = state.audio.busy,
                supportingText = stringResource(R.string.lab_audio_play_supporting, TONE_SECONDS)
            )
        }
        state.audio.note?.let {
            Text(
                text = it.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.audio.error?.let { failure ->
            ErrorCard(
                title = stringResource(R.string.lab_audio_failed),
                message = failure.message.asString(),
                output = failure.code
            )
        }
    }
}

/**
 * The frequency is a list of presets: a 3 900-step drag cannot land on 1 kHz, and a reader who
 * cannot drag moves one step at a time.
 */
@Composable
private fun ToneFrequencySlider(audio: AudioUi, onToneHz: (Int) -> Unit) {
    val name = stringResource(R.string.lab_audio_tone_frequency)
    val valueText = stringResource(R.string.lab_audio_hz, numberText(audio.toneHz))
    val lastIndex = TONE_PRESETS_HZ.lastIndex
    val index = TONE_PRESETS_HZ.indexOfFirst { it >= audio.toneHz }.let { if (it < 0) lastIndex else it }
    NamedSlider(
        name = name,
        valueText = valueText,
        value = index.toFloat(),
        onValueChange = { raw ->
            val snapped = raw.roundToInt().coerceIn(0, lastIndex)
            onToneHz(TONE_PRESETS_HZ[snapped])
        },
        valueRange = 0f..lastIndex.toFloat(),
        steps = (lastIndex - 1).coerceAtLeast(0)
    )
}

/** Level in 2 dB steps; the readout carries the unit the call hears. */
@Composable
private fun ToneLevelSlider(audio: AudioUi, onToneDbfs: (Double) -> Unit) {
    val name = stringResource(R.string.lab_audio_tone_level)
    val valueText = stringResource(R.string.lab_audio_dbfs, numberText(audio.toneDbfs.roundToInt()))
    NamedSlider(
        name = name,
        valueText = valueText,
        value = audio.toneDbfs.toFloat(),
        onValueChange = { raw -> onToneDbfs(((raw / LEVEL_STEP_DB).roundToInt() * LEVEL_STEP_DB).toDouble()) },
        valueRange = -60f..0f,
        steps = 11
    )
}

@Composable
private fun NamedSlider(
    name: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int
) {
    Text(
        text = "$name · $valueText",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        modifier = Modifier.semantics {
            contentDescription = name
            stateDescription = valueText
        }
    )
}
