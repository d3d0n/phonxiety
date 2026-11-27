package com.phonxiety.bridge.feature.livecall

import android.content.res.Resources
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.designsystem.LocalPhonxietyType
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.ScreenScaffold
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.designsystem.formatPhoneNumber
import com.phonxiety.bridge.model.FailureKind
import java.text.NumberFormat

/** Where a composer can be opened from: fresh, or prefilled by "call again". */
data class ComposerIntent(
    val destination: String? = null,
    val goal: String? = null,
    val context: String? = null,
    val constraints: String? = null
)

@Composable
fun ComposerRoute(
    onBack: () -> Unit,
    onPlaced: (String) -> Unit,
    onOpenDoctor: () -> Unit,
    modifier: Modifier = Modifier,
    intent: ComposerIntent = ComposerIntent(),
    viewModel: ComposerViewModel = hiltViewModel()
) {
    LaunchedEffect(intent) {
        viewModel.preload(intent.destination, intent.goal, intent.context, intent.constraints)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.placedCallId) {
        state.placedCallId?.let(onPlaced)
    }
    ComposerScreen(
        state = state,
        onBack = onBack,
        onDestinationChange = viewModel::onDestinationChange,
        onGoalChange = viewModel::onGoalChange,
        onContextChange = viewModel::onContextChange,
        onConstraintsChange = viewModel::onConstraintsChange,
        onFieldDraftChange = viewModel::onFieldDraftChange,
        onAddField = viewModel::addField,
        onRemoveField = viewModel::removeField,
        onMaxDurationChange = viewModel::onMaxDurationChange,
        onRingTimeoutChange = viewModel::onRingTimeoutChange,
        onReview = viewModel::review,
        onDismissReview = viewModel::dismissReview,
        onConfirm = viewModel::confirm,
        onOpenDoctor = onOpenDoctor,
        modifier = modifier
    )
}

/**
 * The brief. Validation is the daemon's, shown as it arrives; the review step repeats every field
 * that will be dialed, because the number and the goal are the two things a wrong tap costs.
 */
@Composable
fun ComposerScreen(
    state: ComposerUiState,
    onBack: () -> Unit,
    onDestinationChange: (String) -> Unit,
    onGoalChange: (String) -> Unit,
    onContextChange: (String) -> Unit,
    onConstraintsChange: (String) -> Unit,
    onFieldDraftChange: (String) -> Unit,
    onAddField: () -> Unit,
    onRemoveField: (String) -> Unit,
    onMaxDurationChange: (Int) -> Unit,
    onRingTimeoutChange: (Int) -> Unit,
    onReview: () -> Unit,
    onDismissReview: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDoctor: () -> Unit = {}
) {
    val focusManager = LocalFocusManager.current
    val fields = remember { ComposerFieldFocus() }
    // The number is the first thing a call needs: the brief opens with it focused, once per entry.
    // The claim is saveable so a recreation does not repeat the request, and the sheet is an
    // overlay in this same composition: while it is up the field under it must not grab the focus
    // back from the review the user is reading.
    var focusClaimed by rememberSaveable { mutableStateOf(false) }
    val shown = rememberUpdatedState(state)
    LaunchedEffect(fields) {
        if (focusClaimed) return@LaunchedEffect
        // Wait for the first frame, when the field is attached and can take the focus.
        withFrameNanos { }
        focusClaimed = true
        val visible = shown.value
        if (!visible.confirming && !visible.placing) fields.destination.requestFocus()
    }
    Box(modifier = modifier.fillMaxSize()) {
        ScreenScaffold(title = stringResource(R.string.composer_title), onBack = onBack) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (state.validating) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WhoCard(state, onDestinationChange, fields, focusManager)
                    WhyCard(state, onGoalChange, onContextChange, onConstraintsChange, fields, focusManager)
                    FieldsCard(
                        state = state,
                        onFieldDraftChange = onFieldDraftChange,
                        onAddField = onAddField,
                        onRemoveField = onRemoveField,
                        fields = fields,
                        focusManager = focusManager
                    )
                    LimitsCard(
                        state = state,
                        onMaxDurationChange = onMaxDurationChange,
                        onRingTimeoutChange = onRingTimeoutChange
                    )
                    if (state.warnings.isNotEmpty()) {
                        WarningsCard(state.warnings)
                    }
                    state.requestIssue?.let { issue ->
                        ErrorCard(
                            title = stringResource(R.string.live_call_action_failed_title),
                            message = issue.headline.asString(),
                            kind = FailureKind.REFUSED,
                            output = issue.detail
                        )
                    }
                    state.placeError?.let { message ->
                        ErrorCard(
                            title = stringResource(R.string.composer_error_title),
                            message = message.asString(),
                            kind = state.placeErrorKind,
                            output = state.placeErrorDetail,
                            // The placement failed, so repeating it must go through the brief's
                            // checks and the review again: the edited fields may be different and
                            // confirm() would send fresh ids for whatever is on screen now.
                            onRetry = onReview,
                            onOpenDoctor = onOpenDoctor
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    PrimaryActionButton(
                        label = when {
                            state.placing -> stringResource(R.string.composer_action_placing)
                            state.validating -> stringResource(R.string.composer_action_checking)
                            state.valid == false -> correctionLabel(state)
                            state.valid == true -> stringResource(R.string.composer_action_review)
                            else -> stringResource(R.string.composer_action_finish)
                        },
                        onClick = onReview,
                        enabled = state.ready,
                        busy = state.placing,
                        supportingText = state.firstFieldError?.asString()
                    )
                }
            }
        }
        if (state.confirming) {
            ConfirmSheet(
                state = state,
                onConfirm = onConfirm,
                onDismiss = onDismissReview
            )
        }
    }
}

/** The composer's text fields, in the order the IME walks them; the focus test drives the chain. */
internal const val COMPOSER_NUMBER_TAG = "composer_field_number"
internal const val COMPOSER_GOAL_TAG = "composer_field_goal"
internal const val COMPOSER_CONTEXT_TAG = "composer_field_context"
internal const val COMPOSER_CONSTRAINTS_TAG = "composer_field_constraints"
internal const val COMPOSER_FIELD_DRAFT_TAG = "composer_field_draft"

/** Above this scale two labels no longer fit side by side in a sheet. */
private const val STACK_ACTIONS_AT_FONT_SCALE = 1.5f

/**
 * The action names the field that is actually wrong: editing the number cannot fix an oversized
 * goal, and a request that never reached the daemon is nobody's field.
 */
@Composable
private fun correctionLabel(state: ComposerUiState): String = stringResource(
    when {
        state.destinationIssue != null -> R.string.composer_action_fix_number
        state.goalIssue != null -> R.string.composer_action_fix_goal
        else -> R.string.composer_status_check_brief
    }
)

/** The border carries the field's edge; onSurfaceVariant keeps it far above the 3:1 floor. */
@Composable
internal fun fieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedBorderColor = MaterialTheme.colorScheme.primary
)

/**
 * A field's failure in the product's words, with the daemon's own sentence behind Details: the
 * local line says what to change; the raw diagnostic is what makes a bug report actionable.
 */
@Composable
private fun FieldError(issue: ComposerIssue) {
    val shown = issue.headline.asString()
    val raw = issue.detail?.takeIf { it.isNotBlank() && it != shown }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(shown)
        if (raw != null) {
            var open by rememberSaveable(raw) { mutableStateOf(false) }
            TextButton(
                onClick = { open = !open },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    stringResource(
                        if (open) {
                            com.phonxiety.bridge.designsystem.R.string.designsystem_hide_details
                        } else {
                            com.phonxiety.bridge.designsystem.R.string.designsystem_show_details
                        }
                    )
                )
            }
            if (open) {
                Text(
                    text = raw,
                    style = LocalPhonxietyType.current.code(MaterialTheme.typography.bodySmall)
                )
            }
        }
    }
}

/** The daemon's field limit stays on screen; going over it turns the count into the error color. */
@Composable
private fun GoalLength(used: Int) {
    val numbers = NumberFormat.getIntegerInstance()
    Text(
        text = stringResource(
            R.string.composer_goal_counter,
            numbers.format(used),
            numbers.format(COMPOSER_GOAL_LIMIT)
        ),
        color = if (used > COMPOSER_GOAL_LIMIT) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

@Composable
private fun WhoCard(
    state: ComposerUiState,
    onDestinationChange: (String) -> Unit,
    fields: ComposerFieldFocus,
    focusManager: FocusManager
) {
    SectionCard(
        title = stringResource(R.string.composer_who_title),
        subtitle = stringResource(R.string.composer_who_subtitle),
        status = {
            when {
                state.valid == true -> StatusPill(
                    label = stringResource(R.string.composer_status_valid),
                    tone = StatusTone.POSITIVE
                )

                state.destinationError != null -> StatusPill(
                    label = stringResource(R.string.composer_status_check_number),
                    tone = StatusTone.NEGATIVE
                )

                state.goalError != null -> StatusPill(
                    label = stringResource(R.string.composer_status_check_goal),
                    tone = StatusTone.NEGATIVE
                )

                state.valid == false -> StatusPill(
                    label = stringResource(R.string.composer_status_check_brief),
                    tone = StatusTone.NEGATIVE
                )

                else -> StatusPill(
                    label = stringResource(R.string.composer_status_draft),
                    tone = StatusTone.NEUTRAL
                )
            }
        }
    ) {
        OutlinedTextField(
            value = state.destination,
            onValueChange = onDestinationChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(COMPOSER_NUMBER_TAG)
                .focusRequester(fields.destination)
                .focusProperties { next = fields.goal },
            label = { Text(stringResource(R.string.composer_number_label)) },
            placeholder = { Text(stringResource(R.string.composer_number_placeholder)) },
            minLines = 1,
            maxLines = 2,
            isError = state.destinationIssue != null,
            supportingText = state.destinationIssue?.let { issue -> { FieldError(issue) } },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Next,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false
            ),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
            colors = fieldColors(),
            shape = MaterialTheme.shapes.medium
        )
    }
}

@Composable
private fun WhyCard(
    state: ComposerUiState,
    onGoalChange: (String) -> Unit,
    onContextChange: (String) -> Unit,
    onConstraintsChange: (String) -> Unit,
    fields: ComposerFieldFocus,
    focusManager: FocusManager
) {
    SectionCard(
        title = stringResource(R.string.composer_why_title),
        subtitle = stringResource(R.string.composer_why_subtitle)
    ) {
        OutlinedTextField(
            value = state.goal,
            onValueChange = onGoalChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(COMPOSER_GOAL_TAG)
                .focusRequester(fields.goal)
                .focusProperties { next = fields.context },
            label = { Text(stringResource(R.string.composer_goal_label)) },
            placeholder = { Text(stringResource(R.string.composer_goal_placeholder)) },
            minLines = 3,
            maxLines = 6,
            isError = state.goalIssue != null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
            supportingText = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    state.goalIssue?.let { issue -> FieldError(issue) }
                    GoalLength(state.goal.length)
                }
            },
            colors = fieldColors(),
            shape = MaterialTheme.shapes.medium
        )
        OutlinedTextField(
            value = state.context,
            onValueChange = onContextChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(COMPOSER_CONTEXT_TAG)
                .focusRequester(fields.context)
                .focusProperties { next = fields.constraints },
            label = { Text(stringResource(R.string.composer_context_label)) },
            placeholder = { Text(stringResource(R.string.composer_context_placeholder)) },
            minLines = 2,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
            colors = fieldColors(),
            shape = MaterialTheme.shapes.medium
        )
        OutlinedTextField(
            value = state.constraints,
            onValueChange = onConstraintsChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(COMPOSER_CONSTRAINTS_TAG)
                .focusRequester(fields.constraints)
                .focusProperties { next = fields.fieldDraft },
            label = { Text(stringResource(R.string.composer_constraints_label)) },
            placeholder = { Text(stringResource(R.string.composer_constraints_placeholder)) },
            minLines = 2,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
            colors = fieldColors(),
            shape = MaterialTheme.shapes.medium
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldsCard(
    state: ComposerUiState,
    onFieldDraftChange: (String) -> Unit,
    onAddField: () -> Unit,
    onRemoveField: (String) -> Unit,
    fields: ComposerFieldFocus,
    focusManager: FocusManager
) {
    SectionCard(
        title = stringResource(R.string.composer_fields_title),
        subtitle = stringResource(R.string.composer_fields_subtitle)
    ) {
        if (state.resultFields.isEmpty()) {
            Text(
                text = stringResource(R.string.composer_fields_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            state.resultFields.forEach { field ->
                InputChip(
                    selected = false,
                    onClick = { onRemoveField(field) },
                    label = { Text(humanizeToken(field)) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(
                                R.string.composer_remove_field,
                                humanizeToken(field)
                            ),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.fieldDraft,
                onValueChange = onFieldDraftChange,
                modifier = Modifier
                    .weight(1f)
                    .testTag(COMPOSER_FIELD_DRAFT_TAG)
                    .focusRequester(fields.fieldDraft),
                label = { Text(stringResource(R.string.composer_field_label)) },
                placeholder = { Text(stringResource(R.string.composer_field_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = fieldColors(),
                shape = MaterialTheme.shapes.medium
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = onAddField,
                enabled = state.fieldDraft.isNotBlank() && state.resultFields.size < ComposerViewModel.MAX_FIELDS
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.composer_add_field),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun LimitsCard(
    state: ComposerUiState,
    onMaxDurationChange: (Int) -> Unit,
    onRingTimeoutChange: (Int) -> Unit
) {
    SectionCard(
        title = stringResource(R.string.composer_limits_title),
        subtitle = stringResource(R.string.composer_limits_subtitle)
    ) {
        LimitSlider(
            label = stringResource(R.string.composer_longest_call),
            value = state.maxDurationSeconds,
            range = ComposerViewModel.MIN_DURATION..(state.limits.maxDurationSeconds ?: ComposerViewModel.MAX_DURATION_LIMIT),
            step = 30,
            onChange = onMaxDurationChange
        )
        LimitSlider(
            label = stringResource(R.string.composer_ring_timeout),
            value = state.ringTimeoutSeconds,
            range = ComposerViewModel.MIN_RING_TIMEOUT..(state.limits.ringTimeoutSeconds ?: ComposerViewModel.RING_TIMEOUT_LIMIT),
            step = 5,
            onChange = onRingTimeoutChange
        )
        if (state.limits.callsPerHour != null) {
            KeyValueRow(
                label = stringResource(R.string.composer_calls_per_hour),
                value = state.limits.callsPerHour.toString(),
                mono = true
            )
        }
    }
}

@Composable
private fun WarningsCard(warnings: List<UiText>) {
    SectionCard(
        title = stringResource(R.string.composer_warnings_title),
        status = {
            StatusPill(
                label = stringResource(R.string.composer_warnings_pill),
                tone = StatusTone.WARNING
            )
        }
    ) {
        warnings.forEach { warning ->
            Text(
                text = stringResource(R.string.composer_warning_bullet, warning.asString()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A value the daemon enforces, with a stepper on each side so the number can be set without
 * dragging, the step count the slider honours, and the value in the units it is read in.
 */
@Composable
private fun LimitSlider(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    onChange: (Int) -> Unit
) {
    val shorter = pluralStringResource(R.plurals.composer_shorter, step, step)
    val longer = pluralStringResource(R.plurals.composer_longer, step, step)
    // At 200 % text a label beside three stepper cells wraps mid-word; the label takes its own line.
    val stacked = LocalDensity.current.fontScale >= STACK_ACTIONS_AT_FONT_SCALE
    val stepper: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(
                onClick = { onChange(value - step) },
                enabled = value - step >= range.first,
                modifier = Modifier.semantics { contentDescription = shorter }
            ) {
                Text(text = "−", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = formatMinutes(value),
                style = LocalPhonxietyType.current.code(MaterialTheme.typography.bodyMedium, tabular = true),
                modifier = Modifier.widthIn(min = 64.dp),
                textAlign = TextAlign.Center
            )
            IconButton(
                onClick = { onChange(value + step) },
                enabled = value + step <= range.last,
                modifier = Modifier.semantics { contentDescription = longer }
            ) {
                Text(text = "+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (stacked) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            stepper()
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                stepper()
            }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw -> onChange((raw / step).toInt() * step) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * The confirmation: the exact payload the daemon receives, in the order a person reads it. It is a
 * sheet over the brief rather than a separate screen so nothing can change between review and dial.
 */
@Composable
private fun ConfirmSheet(
    state: ComposerUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val request = state.request
    val destination = formatPhoneNumber(request.destination)
    val title = stringResource(R.string.composer_confirm_title)
    Box(modifier = Modifier.fillMaxSize()) {
        // A scrim is a touch shortcut, not a control: it stays nameless for TalkBack because the
        // sheet's own Edit button is the documented way out.
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)
        ) {}
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .heightIn(max = 560.dp)
                .semantics { paneTitle = title },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                // The review scrolls; the two buttons never move away from the bottom edge.
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = stringResource(R.string.composer_confirm_body, destination),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    KeyValueRow(label = stringResource(R.string.composer_number_label), value = destination)
                    if (destination != request.destination) {
                        KeyValueRow(
                            label = stringResource(R.string.composer_confirm_sent_as),
                            value = request.destination,
                            mono = true
                        )
                    }
                    ReviewText(label = stringResource(R.string.composer_review_goal), value = request.goal)
                    if (request.context.isNotEmpty()) {
                        ReviewText(label = stringResource(R.string.composer_review_context), value = request.context)
                    }
                    if (request.constraints.isNotEmpty()) {
                        ReviewText(
                            label = stringResource(R.string.composer_review_constraints),
                            value = request.constraints
                        )
                    }
                    KeyValueRow(
                        label = stringResource(R.string.composer_confirm_result_fields),
                        value = request.resultFields
                            .joinToString(", ") { humanizeToken(it) }
                            .ifEmpty { stringResource(R.string.composer_confirm_none) }
                    )
                    KeyValueRow(
                        label = stringResource(R.string.composer_longest_call),
                        value = formatMinutes(request.maxDurationSeconds ?: 0)
                    )
                    KeyValueRow(
                        label = stringResource(R.string.composer_ring_timeout),
                        value = formatMinutes(request.ringTimeoutSeconds ?: 0)
                    )
                }
                // Two labels side by side stop fitting long before 200 % text: stack them there
                // rather than wrap a button label.
                val stacked = LocalDensity.current.fontScale >= STACK_ACTIONS_AT_FONT_SCALE
                val dismiss: @Composable (Modifier) -> Unit = { buttonModifier ->
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = buttonModifier,
                        enabled = !state.placing
                    ) {
                        Text(stringResource(R.string.composer_edit))
                    }
                }
                val confirm: @Composable (Modifier) -> Unit = { buttonModifier ->
                    Button(
                        onClick = onConfirm,
                        modifier = buttonModifier,
                        enabled = !state.placing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            if (state.placing) {
                                stringResource(R.string.composer_action_placing)
                            } else {
                                stringResource(R.string.composer_place_call)
                            }
                        )
                    }
                }
                if (stacked) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        dismiss(Modifier.fillMaxWidth())
                        confirm(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        dismiss(Modifier.weight(1f))
                        confirm(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewText(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The composer's text fields in the order the IME walks them: number, goal, context, constraints,
 * then the field draft, which ends the walk. The requesters make the chain explicit, so the order
 * never depends on how the cards are laid out at a given width.
 */
private class ComposerFieldFocus {
    val destination = FocusRequester()
    val goal = FocusRequester()
    val context = FocusRequester()
    val constraints = FocusRequester()
    val fieldDraft = FocusRequester()
}

/** `60` → `1 min`, `90` → `1 min 30 s`: the units a limit is read in, never a raw second count. */
internal fun formatMinutes(seconds: Int, resources: Resources): String = when {
    seconds < 60 -> resources.getQuantityString(R.plurals.composer_duration_seconds, seconds, seconds)

    seconds % 60 == 0 -> resources.getQuantityString(
        R.plurals.composer_duration_minutes,
        seconds / 60,
        seconds / 60
    )

    else -> resources.getString(
        R.string.composer_duration_minutes_seconds,
        seconds / 60,
        seconds % 60
    )
}

@Composable
internal fun formatMinutes(seconds: Int): String = formatMinutes(seconds, LocalResources.current)
