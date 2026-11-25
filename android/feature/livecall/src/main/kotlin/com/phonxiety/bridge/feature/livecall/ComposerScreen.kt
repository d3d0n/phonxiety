package com.phonxiety.bridge.feature.livecall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone

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
        modifier = modifier
    )
}

/**
 * The brief. Validation is the daemon's, shown as it arrives; the review step repeats every field
 * that will be dialled, because the number and the goal are the two things a wrong tap costs.
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
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(text = "New call", style = MaterialTheme.typography.titleLarge)
            }
            if (state.validating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionCard(
                    title = "Who",
                    subtitle = "An E.164 number or a short service number",
                    status = {
                        when {
                            state.valid == true -> StatusPill("Valid", StatusTone.POSITIVE)
                            state.valid == false -> StatusPill("Check the brief", StatusTone.NEGATIVE)
                            else -> StatusPill("Draft", StatusTone.NEUTRAL)
                        }
                    }
                ) {
                    OutlinedTextField(
                        value = state.destination,
                        onValueChange = onDestinationChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Destination number" },
                        label = { Text("Number") },
                        placeholder = { Text("+998 90 123 45 67") },
                        singleLine = true,
                        isError = state.destinationError != null,
                        supportingText = state.destinationError?.let { message -> { Text(message) } },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Phone,
                            imeAction = ImeAction.Next
                        ),
                        shape = MaterialTheme.shapes.medium
                    )
                }
                SectionCard(title = "Why", subtitle = "What the agent must accomplish") {
                    OutlinedTextField(
                        value = state.goal,
                        onValueChange = onGoalChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Goal" },
                        label = { Text("Goal and brief") },
                        placeholder = { Text("Book a table for two on Thursday at 20:00") },
                        minLines = 3,
                        maxLines = 6,
                        isError = state.goalError != null,
                        supportingText = state.goalError?.let { message -> { Text(message) } },
                        shape = MaterialTheme.shapes.medium
                    )
                    OutlinedTextField(
                        value = state.context,
                        onValueChange = onContextChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Context") },
                        placeholder = { Text("Name, order number, anything the agent should know") },
                        minLines = 2,
                        maxLines = 4,
                        shape = MaterialTheme.shapes.medium
                    )
                    OutlinedTextField(
                        value = state.constraints,
                        onValueChange = onConstraintsChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Constraints") },
                        placeholder = { Text("Do not pay a deposit; ask for a window seat if possible") },
                        minLines = 2,
                        maxLines = 4,
                        shape = MaterialTheme.shapes.medium
                    )
                }
                SectionCard(
                    title = "What to bring back",
                    subtitle = "Each field becomes a labelled answer in the result"
                ) {
                    if (state.resultFields.isEmpty()) {
                        Text(
                            text = "No fields yet: the summary will be free text.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.resultFields.forEach { field ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AssistChip(
                                onClick = { onRemoveField(field) },
                                label = { Text(field) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Remove $field",
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
                            modifier = Modifier.weight(1f),
                            label = { Text("Field") },
                            placeholder = { Text("reservation_time") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(
                            onClick = onAddField,
                            enabled = state.fieldDraft.isNotBlank() && state.resultFields.size < ComposerViewModel.MAX_FIELDS
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Add the field",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                SectionCard(title = "Limits", subtitle = "The daemon enforces these; the phone cannot extend them") {
                    LimitSlider(
                        label = "Longest call",
                        value = state.maxDurationSeconds,
                        range = ComposerViewModel.MIN_DURATION..(state.limits.maxDurationSeconds ?: ComposerViewModel.MAX_DURATION_LIMIT),
                        step = 30,
                        format = ::formatMinutes
                    ) { onMaxDurationChange(it) }
                    LimitSlider(
                        label = "Ring timeout",
                        value = state.ringTimeoutSeconds,
                        range = ComposerViewModel.MIN_RING_TIMEOUT..(state.limits.ringTimeoutSeconds ?: ComposerViewModel.RING_TIMEOUT_LIMIT),
                        step = 5,
                        format = { "$it s" }
                    ) { onRingTimeoutChange(it) }
                    if (state.limits.callsPerHour != null) {
                        KeyValueRow(label = "Calls per hour", value = state.limits.callsPerHour.toString(), mono = true)
                    }
                }
                if (state.warnings.isNotEmpty()) {
                    SectionCard(title = "Worth knowing", status = { StatusPill("Warnings", StatusTone.WARNING) }) {
                        state.warnings.forEach { warning ->
                            Text(
                                text = "· $warning",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                state.error?.let { message ->
                    ErrorCard(title = "The call was not placed", message = message)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                PrimaryActionButton(
                    label = when {
                        state.placing -> "Dialling…"
                        state.valid == true -> "Review and place"
                        else -> "Finish the brief"
                    },
                    onClick = onReview,
                    enabled = state.ready,
                    busy = state.placing
                )
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

@Composable
private fun LimitSlider(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    format: (Int) -> String,
    onChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = format(value),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw -> onChange((raw / step).toInt() * step) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
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
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxSize(),
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f),
            onClick = onDismiss
        ) {}
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .heightIn(max = 520.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "Dial this now?", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "The daemon receives exactly this brief.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                KeyValueRow(label = "Number", value = request.destination, mono = true)
                ReviewText(label = "Goal", value = request.goal)
                if (request.context.isNotEmpty()) ReviewText(label = "Context", value = request.context)
                if (request.constraints.isNotEmpty()) ReviewText(label = "Constraints", value = request.constraints)
                KeyValueRow(
                    label = "Result fields",
                    value = request.resultFields.joinToString(", ").ifEmpty { "none" }
                )
                KeyValueRow(label = "Longest call", value = formatMinutes(request.maxDurationSeconds ?: 0))
                KeyValueRow(label = "Ring timeout", value = "${request.ringTimeoutSeconds ?: 0} s")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        enabled = !state.placing
                    ) {
                        Text("Edit")
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        enabled = !state.placing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (state.placing) "Dialling…" else "Place call")
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

internal fun formatMinutes(seconds: Int): String = when {
    seconds < 60 -> "$seconds s"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60} min ${seconds % 60} s"
}
