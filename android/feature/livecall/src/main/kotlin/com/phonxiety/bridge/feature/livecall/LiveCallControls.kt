package com.phonxiety.bridge.feature.livecall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.data.bitrateKbps
import com.phonxiety.bridge.designsystem.LocalPhonxietyType
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.LevelMeter
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatTile
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.designsystem.resultText
import com.phonxiety.bridge.model.CallMark
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The marks the daemon accepts, in the order the chips show them. */
internal val MARK_KINDS: List<String> = listOf("agreed", "refused", "follow-up")

/**
 * One control's place in the column. The real control is drawn only once `/info` confirmed the
 * voice backend honours it; while that answer is still travelling the screen shows a disabled
 * look-alike with the reason, never an enabled control the daemon would refuse; a confirmed
 * refusal removes the control instead of lying about it.
 */
@Composable
private fun ControlSlot(
    availability: ControlAvailability,
    label: String,
    content: @Composable () -> Unit
) {
    when (availability) {
        ControlAvailability.ENABLED -> content()
        ControlAvailability.UNKNOWN -> UnavailableControl(label)
        ControlAvailability.DISABLED -> Unit
    }
}

/** A control whose availability is unknown: visibly disabled, with the one sentence saying why. */
@Composable
private fun UnavailableControl(label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        FilledTonalButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(label)
        }
        Text(
            text = stringResource(R.string.live_call_control_checking),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun ControlsColumn(
    state: LiveCallUiState,
    onInstructionChange: (String) -> Unit,
    onSendInstruction: () -> Unit,
    onTogglePause: () -> Unit,
    onSummarize: () -> Unit,
    onDtmf: (String) -> Unit,
    onMark: (String) -> Unit,
    onReload: () -> Unit,
    modifier: Modifier,
    showError: Boolean = true
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (showError) {
            state.error?.let { message ->
                LiveCallError(message = message, code = state.errorCode, onReload = onReload)
            }
        }
        LevelCard(state)
        if (!state.ended) {
            ControlSlot(
                availability = state.controls.instruction,
                label = stringResource(R.string.live_call_instruction_title)
            ) {
                InstructionCard(
                    state = state,
                    onInstructionChange = onInstructionChange,
                    onSendInstruction = onSendInstruction
                )
            }
            ControlSlot(
                availability = state.controls.pause,
                label = stringResource(R.string.live_call_pause_agent)
            ) {
                FilledTonalButton(
                    onClick = onTogglePause,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy && !state.hangingUp
                ) {
                    Text(
                        stringResource(
                            if (state.muted) {
                                R.string.live_call_resume_agent
                            } else {
                                R.string.live_call_pause_agent
                            }
                        )
                    )
                }
            }
            ControlSlot(
                availability = state.controls.summarize,
                label = stringResource(R.string.live_call_summarise)
            ) {
                FilledTonalButton(
                    onClick = onSummarize,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.summarizing && !state.hangingUp
                ) {
                    Text(
                        stringResource(
                            if (state.summarizing) {
                                R.string.live_call_summarising
                            } else {
                                R.string.live_call_summarise
                            }
                        )
                    )
                }
                // Progress sits under the button that started it, and announces itself politely.
                if (state.summarizing) {
                    val progressLabel = stringResource(R.string.live_call_summarising_progress)
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = progressLabel
                                liveRegion = LiveRegionMode.Polite
                            }
                    )
                }
            }
            ResultBlock(state = state)
            ControlSlot(
                availability = state.controls.marks,
                label = stringResource(R.string.live_call_marks_title)
            ) {
                MarksCard(state = state, onMark = onMark)
            }
            ControlSlot(
                availability = state.controls.dtmf,
                label = stringResource(R.string.live_call_keypad_title)
            ) {
                DtmfCard(onDtmf = onDtmf, enabled = !state.busy && !state.hangingUp)
            }
        } else {
            ResultBlock(state = state)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LevelCard(state: LiveCallUiState) {
    // The negotiated codec comes from the call record; the numbers exist only while this phone's
    // own audio stream runs.
    val codec = state.audio.codec ?: state.audioCodec
    val streaming = state.audio.codec != null
    SectionCard(title = stringResource(R.string.live_call_audio_title)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatTile(
                label = stringResource(R.string.live_call_codec),
                value = codec ?: MISSING_VALUE,
                mono = true
            )
            StatTile(
                label = stringResource(R.string.live_call_bitrate),
                value = bitrateKbps(state.audio.bitrate)?.toString() ?: MISSING_VALUE,
                mono = true,
                unit = if (state.audio.bitrate != null) UNIT_KILOBITS else null
            )
            StatTile(
                label = stringResource(R.string.live_call_playback_drops),
                value = if (streaming) {
                    playbackDropMillis(state.audio.drops).toString()
                } else {
                    MISSING_VALUE
                },
                mono = true,
                unit = if (streaming) UNIT_MILLISECONDS else null
            )
        }
        if (streaming) {
            Text(
                text = stringResource(R.string.live_call_playback_drops_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LevelMeter(
            label = stringResource(R.string.live_call_microphone),
            levelDbfs = state.audio.txLevelDbfs,
            modifier = Modifier.fillMaxWidth()
        )
        LevelMeter(
            label = stringResource(R.string.live_call_role_far_end),
            levelDbfs = state.audio.rxLevelDbfs,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun InstructionCard(
    state: LiveCallUiState,
    onInstructionChange: (String) -> Unit,
    onSendInstruction: () -> Unit
) {
    SectionCard(
        title = stringResource(R.string.live_call_instruction_title),
        subtitle = stringResource(
            if (state.instruction.isBlank()) {
                R.string.live_call_instruction_reason
            } else {
                R.string.live_call_instruction_subtitle
            }
        )
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = state.instruction,
                onValueChange = onInstructionChange,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.live_call_instruction_label)) },
                placeholder = { Text(stringResource(R.string.live_call_instruction_placeholder)) },
                maxLines = 3,
                colors = fieldColors(),
                shape = MaterialTheme.shapes.medium
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = onSendInstruction,
                enabled = state.instruction.isNotBlank() && !state.busy && !state.hangingUp
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.live_call_send),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun ResultBlock(state: LiveCallUiState) {
    val fields = state.summaryFields.orEmpty()
    if (state.summary.isNullOrBlank() && fields.isEmpty()) return
    SectionCard(
        title = stringResource(R.string.live_call_result_title),
        subtitle = state.resultStatus?.takeIf { it.isNotBlank() }?.let(::humanizeToken)
    ) {
        if (state.summary.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.live_call_result_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(text = state.summary, style = MaterialTheme.typography.bodyLarge)
        }
        fields.entries.forEach { (key, value) ->
            // A boolean answers a question; a sentence is read as a sentence. Only a technical
            // value keeps tabular mono.
            val display = resultText(
                raw = (value as? JsonPrimitive)?.contentOrNull ?: value.toString(),
                boolean = (value as? JsonPrimitive)?.booleanOrNull,
                missing = value is JsonNull
            )
            KeyValueRow(label = humanizeToken(key), value = display.text, mono = display.mono)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarksCard(state: LiveCallUiState, onMark: (String) -> Unit) {
    SectionCard(
        title = stringResource(R.string.live_call_marks_title),
        subtitle = if (state.marks.isEmpty()) {
            stringResource(R.string.live_call_marks_empty)
        } else {
            pluralStringResource(R.plurals.live_call_marks_count, state.marks.size, state.marks.size)
        }
    ) {
        // Three chips stop fitting one row long before 200 % text; they wrap instead of clipping,
        // and each keeps the 48 dp touch target the accessibility gate asks for.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MARK_KINDS.forEach { kind ->
                FilterChip(
                    selected = state.marks.any { it.mark == kind },
                    onClick = { onMark(kind) },
                    label = { Text(humanizeToken(kind)) },
                    enabled = !state.busy && !state.hangingUp,
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
        if (state.marks.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                state.marks.forEach { mark: CallMark ->
                    StatusPill(
                        label = humanizeToken(mark.mark),
                        tone = if (mark.mark == "refused") StatusTone.NEGATIVE else StatusTone.NEUTRAL
                    )
                }
            }
        }
    }
}

@Composable
private fun DtmfCard(onDtmf: (String) -> Unit, enabled: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    val keyStyle = LocalPhonxietyType.current.code(LocalTextStyle.current)
    SectionCard(
        title = stringResource(R.string.live_call_keypad_title),
        subtitle = stringResource(R.string.live_call_keypad_subtitle)
    ) {
        TextButton(onClick = { open = !open }) {
            Text(
                stringResource(
                    if (open) R.string.live_call_keypad_hide else R.string.live_call_keypad_show
                )
            )
        }
        if (open) {
            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("*", "0", "#")
            )
            keys.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { key ->
                        FilledTonalButton(
                            onClick = { onDtmf(key) },
                            modifier = Modifier.weight(1f),
                            enabled = enabled
                        ) {
                            Text(key, style = keyStyle)
                        }
                    }
                }
            }
        }
    }
}

/** The call's own failure, with the daemon's code behind Details and the one retry beside it. */
@Composable
internal fun LiveCallError(
    message: UiText,
    code: String?,
    onReload: () -> Unit,
    modifier: Modifier = Modifier
) {
    ErrorCard(
        title = stringResource(R.string.live_call_action_failed_title),
        message = message.asString(),
        output = code,
        actionLabel = stringResource(R.string.live_call_retry),
        onAction = onReload,
        modifier = modifier
    )
}

/**
 * The hang-up, with its confirmation owned by the screen's state: the dialog is open exactly while
 * [LiveCallUiState.pendingHangUp] holds, so a configuration change keeps it and nothing is sent
 * before the user confirms.
 */
@Composable
internal fun HangUpButton(
    state: LiveCallUiState,
    onRequestHangUp: () -> Unit,
    onConfirmHangUp: () -> Unit,
    onDismissHangUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onRequestHangUp,
        modifier = modifier.fillMaxWidth(),
        enabled = !state.busy && !state.hangingUp,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        )
    ) {
        if (state.hangingUp) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onError
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.live_call_hanging_up))
        } else {
            Text(stringResource(R.string.live_call_hang_up))
        }
    }
    if (state.pendingHangUp) {
        val seconds = state.callSecondsElapsed
        AlertDialog(
            onDismissRequest = onDismissHangUp,
            title = { Text(stringResource(R.string.live_call_hang_up_title)) },
            text = {
                Text(
                    stringResource(
                        if (seconds != null && seconds < 3) {
                            R.string.live_call_hang_up_young
                        } else {
                            R.string.live_call_hang_up_body
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmHangUp) {
                    Text(stringResource(R.string.live_call_hang_up))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissHangUp) {
                    Text(stringResource(R.string.live_call_hang_up_keep))
                }
            }
        )
    }
}
