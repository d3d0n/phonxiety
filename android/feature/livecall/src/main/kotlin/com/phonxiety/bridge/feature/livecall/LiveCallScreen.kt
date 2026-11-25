package com.phonxiety.bridge.feature.livecall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.LevelMeter
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatTile
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.model.CallMark
import com.phonxiety.bridge.model.PartialTurn
import com.phonxiety.bridge.model.TranscriptTurn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The live call entry point; the host only supplies navigation. */
@Composable
fun LiveCallRoute(
    callId: String,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LiveCallViewModel = hiltViewModel()
) {
    LaunchedEffect(callId) { viewModel.bind(callId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by tickingNow()
    KeepScreenOn()
    LiveCallScreen(
        state = state,
        onBack = onBack,
        onInstructionChange = viewModel::onInstructionChange,
        onSendInstruction = viewModel::tellAgent,
        onTogglePause = viewModel::togglePause,
        onSummarize = viewModel::summarizeNow,
        onDtmf = viewModel::sendDtmf,
        onMark = viewModel::mark,
        onHangUp = viewModel::hangUp,
        onReload = viewModel::reload,
        onOpenDetail = onOpenDetail,
        nowEpochMs = now,
        modifier = modifier
    )
}

/**
 * The supervisor: state and timer, the streamed transcript, the agent's activity, the level
 * meters and every control the voice backend honours. Narrow screens stack the transcript above
 * the controls; wide ones put them side by side so the transcript stays visible while typing.
 */
@Composable
fun LiveCallScreen(
    state: LiveCallUiState,
    onBack: () -> Unit,
    onInstructionChange: (String) -> Unit,
    onSendInstruction: () -> Unit,
    onTogglePause: () -> Unit,
    onSummarize: () -> Unit,
    onDtmf: (String) -> Unit,
    onMark: (String) -> Unit,
    onHangUp: () -> Unit,
    onReload: () -> Unit,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
    nowEpochMs: Long = System.currentTimeMillis()
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        when {
            state.missing -> EmptyState(
                title = "This call is not on the daemon",
                message = "It may have been deleted from the daemon. Back to the list for the ones that remain.",
                modifier = Modifier.align(Alignment.TopCenter)
            )

            !state.loaded && state.loading -> LoadingState(
                message = "Reading the call",
                modifier = Modifier.align(Alignment.Center)
            )

            else -> LiveCallBody(
                state = state,
                onBack = onBack,
                onInstructionChange = onInstructionChange,
                onSendInstruction = onSendInstruction,
                onTogglePause = onTogglePause,
                onSummarize = onSummarize,
                onDtmf = onDtmf,
                onMark = onMark,
                onHangUp = onHangUp,
                onReload = onReload,
                onOpenDetail = onOpenDetail,
                nowEpochMs = nowEpochMs
            )
        }
    }
}

@Composable
private fun LiveCallBody(
    state: LiveCallUiState,
    onBack: () -> Unit,
    onInstructionChange: (String) -> Unit,
    onSendInstruction: () -> Unit,
    onTogglePause: () -> Unit,
    onSummarize: () -> Unit,
    onDtmf: (String) -> Unit,
    onMark: (String) -> Unit,
    onHangUp: () -> Unit,
    onReload: () -> Unit,
    onOpenDetail: (String) -> Unit,
    nowEpochMs: Long
) {
    val transcript = @Composable { modifier: Modifier ->
        TranscriptCard(state = state, modifier = modifier)
    }
    val controls = @Composable { modifier: Modifier ->
        ControlsColumn(
            state = state,
            onInstructionChange = onInstructionChange,
            onSendInstruction = onSendInstruction,
            onTogglePause = onTogglePause,
            onSummarize = onSummarize,
            onDtmf = onDtmf,
            onMark = onMark,
            onReload = onReload,
            modifier = modifier
        )
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val controlsMaxHeight = maxHeight * 0.46f
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
        ) {
            LiveCallHeader(state = state, onBack = onBack, nowEpochMs = nowEpochMs)
            if (state.reconnecting) {
                ReconnectingBanner(onReload = onReload)
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { transcript(Modifier.fillMaxSize()) }
                        Box(
                            modifier = Modifier
                                .width(380.dp)
                                .fillMaxHeight()
                        ) {
                            controls(Modifier.fillMaxSize())
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { transcript(Modifier.fillMaxSize()) }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = controlsMaxHeight)
                                .padding(bottom = 8.dp)
                        ) {
                            controls(Modifier.fillMaxSize())
                        }
                    }
                }
            }
            // Pinned in both orientations: ending the call must never need a scroll.
            PinnedActionRow(state = state, onHangUp = onHangUp, onOpenDetail = onOpenDetail)
        }
    }
}

/**
 * The one action that must always be reachable: hang up while the call runs, open the record once
 * it has ended. It lives outside the transcript and the control column, so no scroll hides it.
 */
@Composable
private fun PinnedActionRow(
    state: LiveCallUiState,
    onHangUp: () -> Unit,
    onOpenDetail: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state.ended) {
            FilledTonalButton(
                onClick = { onOpenDetail(state.callId) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Open the call record")
            }
        } else if (state.controls.hangup) {
            HangUpButton(
                state = state,
                onHangUp = onHangUp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LiveCallHeader(state: LiveCallUiState, onBack: () -> Unit, nowEpochMs: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                modifier = Modifier.size(22.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.destination?.let(::maskDestination) ?: "Call",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    formatTimer(state, nowEpochMs),
                    state.farEnd?.takeIf { it.isNotBlank() },
                    state.audioCodec
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(
            label = state.activity ?: phaseLabel(state),
            tone = phaseTone(state),
            pulse = !state.ended && (state.state == "dialing" || state.state == "active")
        )
    }
}

@Composable
private fun ReconnectingBanner(onReload: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "The link dropped; the transcript resumes from the daemon's record.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onReload) { Text("Resync") }
        }
    }
}

@Composable
private fun TranscriptCard(
    state: LiveCallUiState,
    modifier: Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Transcript", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = when {
                            state.ended -> "Call ended · ${state.state ?: "unknown"}"
                            state.partials.isNotEmpty() -> "Live"
                            state.turns.isEmpty() -> "Waiting for the first turn"
                            else -> "${state.turns.size} turns"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!state.ended) {
                    StatusPill(
                        label = if (state.muted) "Agent paused" else "Listening",
                        tone = if (state.muted) StatusTone.WARNING else StatusTone.POSITIVE
                    )
                }
            }
            if (state.turns.isEmpty() && state.partials.isEmpty()) {
                LoadingState(message = "The transcript starts when someone speaks")
            } else {
                TranscriptList(state = state, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Newest turn at the bottom. Following is a property of the scroll position: as soon as the user
 * scrolls up the list stops moving, and the jump pill brings it back to live.
 */
@Composable
private fun TranscriptList(state: LiveCallUiState, modifier: Modifier) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val following by remember {
        derivedStateOf { !listState.canScrollForward || listState.layoutInfo.visibleItemsInfo.isEmpty() }
    }
    val itemCount = state.turns.size + state.partials.size
    LaunchedEffect(itemCount, following) {
        if (itemCount > 0 && following) listState.scrollToItem(itemCount - 1)
    }
    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.turns.size) { index -> TranscriptRow(state.turns[index]) }
            items(state.partials.size) { index -> PartialRow(state.partials[index]) }
            if (!state.ended) {
                item {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (!following) {
            FilledTonalButton(
                onClick = { scope.launch { listState.animateScrollToItem((itemCount - 1).coerceAtLeast(0)) } },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
            ) {
                Text("Jump to live")
            }
        }
    }
}

@Composable
private fun TranscriptRow(turn: TranscriptTurn) {
    val agent = turn.role == "agent"
    val container = if (agent) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (agent) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (agent) "Agent" else "Far end",
                    style = MaterialTheme.typography.labelSmall,
                    color = content
                )
                if (turn.sequence > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "#${turn.sequence}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            Text(
                text = turn.text ?: "—",
                style = MaterialTheme.typography.bodyLarge,
                color = content
            )
        }
    }
}

@Composable
private fun PartialRow(partial: PartialTurn) {
    Text(
        text = "${partial.role?.let(::speakerLabel) ?: "Someone"}: ${partial.text}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    )
}

@Composable
private fun ControlsColumn(
    state: LiveCallUiState,
    onInstructionChange: (String) -> Unit,
    onSendInstruction: () -> Unit,
    onTogglePause: () -> Unit,
    onSummarize: () -> Unit,
    onDtmf: (String) -> Unit,
    onMark: (String) -> Unit,
    onReload: () -> Unit,
    modifier: Modifier
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.summarizing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.error?.let { message ->
            ErrorCard(
                title = "The daemon refused that",
                message = message,
                actionLabel = "Retry",
                onAction = onReload
            )
        }
        LevelCard(state)
        if (!state.ended) {
            if (state.controls.instruction) {
                InstructionCard(
                    state = state,
                    onInstructionChange = onInstructionChange,
                    onSendInstruction = onSendInstruction
                )
            }
            if (state.controls.pause) {
                FilledTonalButton(
                    onClick = onTogglePause,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy && !state.hangingUp
                ) {
                    Text(if (state.muted) "Resume the agent's speech" else "Pause the agent's speech")
                }
            }
            if (state.controls.summarize) {
                FilledTonalButton(
                    onClick = onSummarize,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.summarizing && !state.hangingUp
                ) {
                    Text(if (state.summarizing) "Asking for the summary…" else "Summarise now")
                }
            }
            ResultBlock(state = state, onSummarize = null)
            if (state.controls.marks) {
                MarksCard(state = state, onMark = onMark)
            }
            if (state.controls.dtmf) {
                DtmfCard(onDtmf = onDtmf, enabled = !state.busy && !state.hangingUp)
            }
        } else {
            ResultBlock(state = state, onSummarize = null)
        }
    }
}

@Composable
private fun LevelCard(state: LiveCallUiState) {
    SectionCard(title = "Audio") {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            StatTile(
                label = "Codec",
                value = state.audio.codec ?: state.audioCodec ?: "—",
                mono = true
            )
            StatTile(
                label = "Bitrate",
                value = state.audio.bitrate?.let { "$it kbit/s" } ?: "—",
                mono = true
            )
            StatTile(label = "Drops", value = state.audio.drops.toString(), mono = true)
        }
        LevelMeter(
            label = "Microphone",
            levelDbfs = state.audio.txLevelDbfs,
            modifier = Modifier.fillMaxWidth()
        )
        LevelMeter(
            label = "Downlink",
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
        title = "Tell the agent",
        subtitle = "A directive for the voice session; it is never spoken into the call."
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = state.instruction,
                onValueChange = onInstructionChange,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Instruction to the agent" },
                placeholder = { Text("Ask for Thursday") },
                maxLines = 3,
                shape = MaterialTheme.shapes.medium
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = onSendInstruction,
                enabled = state.instruction.isNotBlank() && !state.busy && !state.hangingUp
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send the instruction",
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun ResultBlock(state: LiveCallUiState, onSummarize: (() -> Unit)?) {
    val fields = state.summaryFields.orEmpty()
    if (state.summary.isNullOrBlank() && fields.isEmpty() && onSummarize == null) return
    SectionCard(
        title = "Result so far",
        subtitle = state.resultStatus?.let { "status: $it" }
    ) {
        if (state.summary.isNullOrBlank()) {
            Text(
                text = "The agent has not produced a summary yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(text = state.summary, style = MaterialTheme.typography.bodyLarge)
        }
        fields.entries.forEach { (key, value) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = key.replace('_', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = (value as? JsonPrimitive)?.contentOrNull ?: value.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        if (onSummarize != null) {
            TextButton(onClick = onSummarize) { Text("Summarise now") }
        }
    }
}

@Composable
private fun MarksCard(state: LiveCallUiState, onMark: (String) -> Unit) {
    SectionCard(
        title = "Marks",
        subtitle = if (state.marks.isEmpty()) "Nothing marked yet" else "${state.marks.size} marked"
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MARK_KINDS.forEach { kind ->
                FilterChip(
                    selected = state.marks.any { it.mark == kind },
                    onClick = { onMark(kind) },
                    label = { Text(kind.replace('-', ' ').replaceFirstChar { it.uppercase() }) },
                    enabled = !state.busy && !state.hangingUp
                )
            }
        }
        if (state.marks.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.marks.forEach { mark: CallMark ->
                    StatusPill(
                        label = mark.mark.replace('-', ' '),
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
    SectionCard(
        title = "Keypad",
        subtitle = "Digits go to the far end's menu through the daemon.",
        status = {
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
        }
    ) {
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
                            Text(key, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HangUpButton(state: LiveCallUiState, onHangUp: () -> Unit, modifier: Modifier = Modifier) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Button(
        onClick = { confirming = true },
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
            Text("Ending the call…")
        } else {
            Text("Hang up")
        }
    }
    if (confirming) {
        val seconds = state.callSecondsElapsed
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Hang up?") },
            text = {
                Text(
                    if (seconds != null && seconds < 3) {
                        "The call is only a few seconds old: the far end may not have picked up yet."
                    } else {
                        "The daemon ends the call now. The record stays in Calls with its transcript."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onHangUp()
                    }
                ) {
                    Text("Hang up")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Keep talking") }
            }
        )
    }
}

/** Requests the wake lock the plan promises while this screen is visible. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
}

/** One tick per second for the timer; the call record is the only source of the start time. */
@Composable
private fun tickingNow(): androidx.compose.runtime.State<Long> = produceState(initialValue = System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(1_000)
    }
}

internal val MARK_KINDS: List<String> = listOf("agreed", "refused", "follow-up")

internal fun formatTimer(state: LiveCallUiState, nowEpochMs: Long): String {
    val start = state.connectedAtMs ?: state.createdAtMs ?: return "—"
    val seconds = ((nowEpochMs - start).coerceAtLeast(0)) / 1000
    val minutes = seconds / 60
    val rest = seconds % 60
    return "$minutes:${rest.toString().padStart(2, '0')}"
}

/** `far_end` is the daemon's word; the screen says "Far end". */
internal fun speakerLabel(role: String): String = when (role) {
    "far_end" -> "Far end"
    "agent" -> "Agent"
    else -> role.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

internal fun phaseLabel(state: LiveCallUiState): String = when (state.state) {
    "preparing" -> "Preparing"
    "dialing" -> "Dialing"
    "active" -> "Live"
    "ending" -> "Ending"
    "ended" -> "Ended"
    "cancelled" -> "Cancelled"
    "failed" -> "Failed"
    "unknown" -> "Unknown"
    else -> if (state.loaded) "Idle" else "Loading"
}

internal fun phaseTone(state: LiveCallUiState): StatusTone = when (state.state) {
    "active" -> StatusTone.POSITIVE
    "dialing", "preparing", "ending" -> StatusTone.WARNING
    "failed", "cancelled" -> StatusTone.NEGATIVE
    else -> StatusTone.NEUTRAL
}

/** `+998901234567` → `+998 ·· 67`: the country and the last two digits, nothing else. */
internal fun maskDestination(destination: String): String {
    val digits = destination.filter { it.isDigit() }
    if (digits.length < 5) return destination
    val country = if (destination.trimStart().startsWith("+")) "+${digits.take(3)}" else ""
    val tail = digits.takeLast(2)
    return if (country.isEmpty()) "·· $tail" else "$country ·· $tail"
}
