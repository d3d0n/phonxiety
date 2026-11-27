package com.phonxiety.bridge.feature.lab.cards

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ActionEmphasis
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.designsystem.component.ToggleRow
import com.phonxiety.bridge.designsystem.formatElapsedTime
import com.phonxiety.bridge.feature.lab.LabCallbacks
import com.phonxiety.bridge.feature.lab.LabUiState
import com.phonxiety.bridge.feature.lab.R
import com.phonxiety.bridge.feature.lab.TestCallResultUi
import com.phonxiety.bridge.feature.lab.codecLabel
import com.phonxiety.bridge.feature.lab.humanize
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The dialled number is digits, never prose: the keyboard opens numeric, offers no autocorrect and
 * no capitalisation, and Done puts it away instead of inserting a newline.
 */
internal val TEST_NUMBER_KEYBOARD = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Phone,
    imeAction = ImeAction.Done
)

internal const val TONE_SECONDS_MIN = 1
internal const val TONE_SECONDS_MAX = 10

@Composable
internal fun TestCallCard(state: LabUiState, callbacks: LabCallbacks) {
    val call = state.testCall
    val focus = LocalFocusManager.current
    SectionCard(
        title = stringResource(R.string.lab_test_title),
        subtitle = stringResource(R.string.lab_test_subtitle)
    ) {
        OutlinedTextField(
            value = call.number,
            onValueChange = callbacks.onTestNumber,
            label = { Text(stringResource(R.string.lab_test_number)) },
            singleLine = true,
            keyboardOptions = TEST_NUMBER_KEYBOARD,
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.lab_test_number_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Each toggle needs its own phone capability as well as the daemon's orchestration:
        // asking for one the phone does not have rejects the whole placement with 409.
        val loopbackAvailable = state.testCallLoopbackAvailable
        val toneAvailable = state.testCallToneAvailable
        if (loopbackAvailable || toneAvailable) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.lab_test_probes_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(R.string.lab_test_probes_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (loopbackAvailable) {
                ToggleRow(
                    title = stringResource(R.string.lab_test_loopback),
                    checked = call.loopback,
                    onCheckedChange = callbacks.onTestLoopback,
                    detail = stringResource(R.string.lab_test_loopback_detail),
                    enabled = !call.placing
                )
            }
            if (toneAvailable) {
                ToggleRow(
                    title = stringResource(R.string.lab_test_tone),
                    checked = call.tone,
                    onCheckedChange = callbacks.onTestTone,
                    detail = stringResource(R.string.lab_test_tone_detail),
                    enabled = !call.placing
                )
                if (call.tone) {
                    TestToneSecondsSlider(
                        seconds = call.toneSeconds,
                        enabled = !call.placing,
                        onSeconds = callbacks.onTestToneSeconds
                    )
                }
            }
        }
        PrimaryActionButton(
            label = stringResource(if (call.placing) R.string.lab_test_placing else R.string.lab_test_place),
            onClick = callbacks.onRequestTestCall,
            enabled = call.numberReady && !call.placing,
            busy = call.placing,
            supportingText = if (call.numberReady) null else stringResource(R.string.lab_test_need_number)
        )
        call.callId?.let { callId ->
            // What happened reads as a status; the internal id is evidence, selectable and labelled.
            Text(
                text = stringResource(R.string.lab_test_placed),
                style = MaterialTheme.typography.bodyMedium
            )
            SelectionContainer {
                KeyValueRow(
                    label = stringResource(R.string.lab_test_call_id),
                    value = callId,
                    mono = true
                )
            }
        }
        call.error?.let { failure ->
            ErrorCard(
                title = stringResource(R.string.lab_test_failed),
                message = failure.message.asString(),
                kind = failure.kind,
                output = failure.code,
                onRetry = callbacks.onRequestTestCall,
                onOpenDoctor = callbacks.onOpenDoctor
            )
        }
    }
}

/** Ten positions, each a whole second; a drag cannot land between them. */
@Composable
private fun TestToneSecondsSlider(seconds: Int, enabled: Boolean, onSeconds: (Int) -> Unit) {
    val name = stringResource(R.string.lab_test_tone_seconds)
    val valueText = stringResource(R.string.lab_test_tone_seconds_state, seconds)
    Text(
        text = "$name · $valueText",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = seconds.toFloat(),
        onValueChange = { raw -> onSeconds(raw.roundToInt().coerceIn(TONE_SECONDS_MIN, TONE_SECONDS_MAX)) },
        valueRange = TONE_SECONDS_MIN.toFloat()..TONE_SECONDS_MAX.toFloat(),
        steps = TONE_SECONDS_MAX - TONE_SECONDS_MIN - 1,
        enabled = enabled,
        modifier = Modifier.semantics {
            contentDescription = name
            stateDescription = valueText
        }
    )
}

/**
 * The call the lab placed, as the bridge recorded it. The summary is a phase of the record, not an
 * instant fact: the card says it is still being written rather than showing an empty result.
 */
@Composable
internal fun TestCallResultCard(result: TestCallResultUi, onOpenCall: (String) -> Unit) {
    // Resolved before the slot: a null slot adds no row and no gap, an empty one still would.
    val outcomeLabel = testOutcomeLabel(result.outcome)
    SectionCard(
        title = stringResource(R.string.lab_test_result_title),
        subtitle = stringResource(R.string.lab_test_result_subtitle),
        status = if (outcomeLabel != null) {
            {
                StatusPill(
                    label = outcomeLabel,
                    tone = if (result.outcome?.lowercase(Locale.US) == "answered") {
                        StatusTone.POSITIVE
                    } else {
                        StatusTone.WARNING
                    }
                )
            }
        } else {
            null
        }
    ) {
        result.durationSeconds?.let {
            KeyValueRow(
                label = stringResource(R.string.lab_test_result_duration),
                value = formatElapsedTime(it),
                mono = true
            )
        }
        result.codec?.takeIf { it.isNotBlank() }?.let {
            KeyValueRow(label = stringResource(R.string.lab_test_result_codec), value = codecLabel(it))
        }
        when {
            result.summaryReady -> Text(text = result.summary.orEmpty(), style = MaterialTheme.typography.bodyLarge)

            result.waiting -> Text(
                text = stringResource(R.string.lab_test_result_waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            result.error != null -> ErrorCard(
                title = stringResource(R.string.lab_test_result_failed),
                message = result.error.message.asString(),
                kind = result.error.kind,
                output = result.error.code
            )

            result.unfinished -> Text(
                text = stringResource(R.string.lab_test_result_unfinished),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            result.summaryAbsent -> Text(
                text = stringResource(R.string.lab_test_result_summary_absent),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            else -> Text(
                text = stringResource(R.string.lab_test_result_summary_pending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        PrimaryActionButton(
            label = stringResource(R.string.lab_test_result_open),
            onClick = { onOpenCall(result.callId) },
            emphasis = ActionEmphasis.TONAL
        )
    }
}

/** The daemon's outcome in words; an outcome this build does not know still reads cleanly. */
@Composable
private fun testOutcomeLabel(outcome: String?): String? = when (outcome?.lowercase(Locale.US)) {
    null, "" -> null
    "answered" -> stringResource(R.string.lab_test_outcome_answered)
    "no_answer" -> stringResource(R.string.lab_test_outcome_no_answer)
    "busy" -> stringResource(R.string.lab_test_outcome_busy)
    "declined" -> stringResource(R.string.lab_test_outcome_declined)
    "disconnected" -> stringResource(R.string.lab_test_outcome_disconnected)
    "cancelled", "canceled" -> stringResource(R.string.lab_test_outcome_canceled)
    "failed" -> stringResource(R.string.lab_test_outcome_failed)
    else -> humanize(outcome)
}
