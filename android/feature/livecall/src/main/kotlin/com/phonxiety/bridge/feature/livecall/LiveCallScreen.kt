package com.phonxiety.bridge.feature.livecall

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.data.Speaker
import com.phonxiety.bridge.data.bitrateKbps
import com.phonxiety.bridge.data.speakerOf
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ActionEmphasis
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.LevelMeter
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.STACK_AT_FONT_SCALE
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatTile
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.designsystem.farEndLabel
import com.phonxiety.bridge.designsystem.formatElapsedTime
import com.phonxiety.bridge.designsystem.formatPhoneNumber
import com.phonxiety.bridge.designsystem.resultText
import com.phonxiety.bridge.model.CallMark
import com.phonxiety.bridge.model.PartialTurn
import com.phonxiety.bridge.model.TranscriptTurn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The playback ring is 48 kHz mono, so one millisecond of downlink audio is 48 samples. */
private const val AUDIO_SAMPLES_PER_MILLISECOND = 48L
private const val UNIT_MILLISECONDS = "ms"
private const val UNIT_KILOBITS = "kbit/s"
private const val MISSING_VALUE = "—"
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L

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
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            when {
                state.missing -> EmptyState(
                    title = stringResource(R.string.live_call_not_found_title),
                    message = stringResource(R.string.live_call_not_found_message),
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                !state.loaded && state.loading -> LoadingState(
                    message = stringResource(R.string.live_call_loading),
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
        TranscriptCard(state = state, onReload = onReload, modifier = modifier)
    }
    val controls = @Composable { modifier: Modifier, showError: Boolean ->
        ControlsColumn(
            state = state,
            onInstructionChange = onInstructionChange,
            onSendInstruction = onSendInstruction,
            onTogglePause = onTogglePause,
            onSummarize = onSummarize,
            onDtmf = onDtmf,
            onMark = onMark,
            onReload = onReload,
            showError = showError,
            modifier = modifier
        )
    }
    // A compact screen opens with the audio card, the instruction and the marks folded away: the
    // transcript is what a person is reading, and the fold is one 48 dp row, not a second viewport.
    var controlsOpen by rememberSaveable { mutableStateOf(false) }
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
                            controls(Modifier.fillMaxSize(), true)
                        }
                    }
                } else {
                    // Only a running call folds its controls away: once it has ended the result is
                    // the reason the screen is open, so it stays on screen without a tap. A call
                    // whose end was never confirmed has no result either: its card and transcript
                    // are the content, and the controls fold like a running call's.
                    val folded = !state.ended || state.phase == "unknown"
                    Column(modifier = Modifier.fillMaxSize()) {
                        // A failure (a refused hang-up, a dropped instruction) is never behind the
                        // fold: it explains why the pinned action did not do what it promised.
                        state.error?.let { message ->
                            LiveCallError(
                                message = message,
                                code = state.errorCode,
                                onReload = onReload,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { transcript(Modifier.fillMaxSize()) }
                        if (controlsOpen || !folded) {
                            // A running call's expanded controls share the remaining height with
                            // the transcript: at 200 % text a fixed cut clips its jump control.
                            // Once the call has ended the result takes its usual bounded box.
                            val controlsHeight = if (folded) {
                                Modifier.weight(1f)
                            } else {
                                Modifier.heightIn(max = controlsMaxHeight)
                            }
                            Box(
                                modifier = controlsHeight
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                controls(Modifier.fillMaxSize(), false)
                            }
                        }
                        if (folded) {
                            ControlsDisclosure(
                                open = controlsOpen,
                                onToggle = { controlsOpen = !controlsOpen }
                            )
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
 * The compact layout's second half, behind one row. Collapsed, the transcript keeps the viewport it
 * needs at 200 % text; expanded, the controls scroll inside a bounded box of their own.
 */
@Composable
private fun ControlsDisclosure(open: Boolean, onToggle: () -> Unit) {
    val action = stringResource(
        if (open) R.string.live_call_controls_hide else R.string.live_call_controls_show
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = action }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.live_call_controls_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
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
            PrimaryActionButton(
                label = stringResource(R.string.live_call_open_record),
                onClick = { onOpenDetail(state.callId) },
                modifier = Modifier.weight(1f),
                emphasis = ActionEmphasis.TONAL
            )
        } else if (state.controls.hangup) {
            HangUpButton(
                state = state,
                onHangUp = onHangUp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * The number as the owner reads it, then the running time and who is on the line. The timer is the
 * platform's elapsed format; its spoken name spells the duration out, because "1:09" is read as
 * digits.
 */
@Composable
private fun LiveCallHeader(state: LiveCallUiState, onBack: () -> Unit, nowEpochMs: Long) {
    val seconds = elapsedSeconds(state, nowEpochMs)
    val spoken = seconds?.let { spokenDuration(it) }
    val stacked = LocalDensity.current.fontScale >= STACK_AT_FONT_SCALE
    val pill: @Composable (Modifier) -> Unit = { modifier ->
        StatusPill(
            label = statusLabel(state),
            tone = if (state.reconnecting) StatusTone.WARNING else phaseTone(state),
            pulse = state.reconnecting || (!state.ended && state.phase == "dialing"),
            modifier = modifier
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(com.phonxiety.bridge.designsystem.R.string.designsystem_action_back),
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.destination?.let(::formatPhoneNumber)
                        ?: stringResource(R.string.live_call_fallback_title),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (seconds != null) {
                    Text(
                        text = formatTimer(state, nowEpochMs),
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.semantics { contentDescription = spoken.orEmpty() }
                    )
                }
                val detail = listOfNotNull(
                    state.farEnd?.takeIf { it.isNotBlank() }?.let { farEndLabel(it) },
                    state.audioCodec?.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.live_call_audio_codec, humanizeToken(it)) }
                ).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // At 200 % text the pill would squeeze the number to an ellipsis, so it drops under
            // the header at the app's stacking scale.
            if (!stacked) {
                Spacer(Modifier.width(8.dp))
                pill(Modifier)
            }
        }
        if (stacked) {
            pill(Modifier.padding(start = 48.dp, top = 4.dp))
        }
    }
}

/**
 * The call whose end the daemon recorded as unconfirmed. The reason it gives is evidence, so it
 * sits behind Details in monospace; the card says what is and is not known and offers the one
 * useful action, reading the record again. Only the daemon's own link-loss reason names a lost
 * connection: an unacknowledged hang-up leaves the phone connected, so its copy stays neutral.
 */
@Composable
private fun UnknownStatusCard(
    reason: String?,
    onReload: () -> Unit,
    modifier: Modifier = Modifier
) {
    ErrorCard(
        title = stringResource(R.string.live_call_status_unknown_title),
        message = stringResource(
            if (isLinkLoss(reason)) {
                R.string.live_call_status_unknown_link_lost
            } else {
                R.string.live_call_status_unknown_message
            }
        ),
        output = reason,
        actionLabel = stringResource(R.string.live_call_status_refresh),
        onAction = onReload,
        modifier = modifier
    )
}

/** The daemon's own name for a call whose phone link died; see `controller._fail`. */
internal const val LINK_LOST_ERROR = "device_disconnected"

/** The transcript's scroll container, so tests reach a card that is not in the first viewport. */
internal const val TRANSCRIPT_LIST_TAG = "live_call_transcript"

/**
 * Whether the record's error is the daemon's link loss. Any other reason (an unacknowledged
 * hang-up, a voice failure) says nothing about the connection and must not claim it.
 */
internal fun isLinkLoss(reason: String?): Boolean = reason?.trim() == LINK_LOST_ERROR

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
                text = stringResource(R.string.live_call_reconnect_banner),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onReload) {
                Text(stringResource(R.string.live_call_reconnect_action))
            }
        }
    }
}

/**
 * The transcript is the screen: its heading is the first row of the same scroll, so at 200 % text it
 * moves out of the way instead of eating the only readable viewport.
 */
@Composable
private fun TranscriptCard(
    state: LiveCallUiState,
    onReload: () -> Unit,
    modifier: Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        TranscriptList(state = state, onReload = onReload, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun TranscriptHeading(state: LiveCallUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = stringResource(R.string.live_call_transcript_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = transcriptSubtitle(state),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Listening is a claim about an established call: while the link is down, or before the
        // phone has connected, the screen says nothing about it.
        if (state.phase == "active" && !state.reconnecting) {
            StatusPill(
                label = stringResource(
                    if (state.muted) {
                        R.string.live_call_agent_paused
                    } else {
                        R.string.live_call_agent_listening
                    }
                ),
                tone = if (state.muted) StatusTone.WARNING else StatusTone.POSITIVE
            )
        }
    }
}

@Composable
private fun transcriptSubtitle(state: LiveCallUiState): String = transcriptSubtitle(state, LocalResources.current)

/**
 * What the transcript heading says about the call: an unconfirmed end is named as unconfirmed, never
 * as an ended call, and a dropped link never claims the agent is live.
 */
internal fun transcriptSubtitle(state: LiveCallUiState, resources: Resources): String = when {
    state.phase == "unknown" -> resources.getString(
        R.string.live_call_transcript_unconfirmed,
        resources.getQuantityString(R.plurals.live_call_turns, state.turns.size, state.turns.size)
    )

    state.ended -> resources.getString(
        R.string.live_call_transcript_ended,
        resources.getQuantityString(R.plurals.live_call_turns, state.turns.size, state.turns.size)
    )

    state.reconnecting -> when {
        state.turns.isEmpty() -> resources.getString(R.string.live_call_transcript_waiting)
        else -> resources.getQuantityString(R.plurals.live_call_turns, state.turns.size, state.turns.size)
    }

    state.activity == "working" -> resources.getString(R.string.live_call_transcript_working)

    state.turns.isEmpty() -> resources.getString(R.string.live_call_transcript_waiting)

    else -> resources.getQuantityString(R.plurals.live_call_turns, state.turns.size, state.turns.size)
}

/**
 * Newest turn at the bottom. Following is a property of the scroll position: as soon as the user
 * scrolls up the list stops moving, and the jump pill brings it back to live. The heading is item
 * zero of this same list, so the transcript text owns every pixel the screen can spare.
 *
 * The unconfirmed-end message is the second row, at the top. When the list enters that state the
 * row is brought into view once, and the list stops following the newest turn: a reader who was at
 * the bottom, or who opens an already unconfirmed call, sees why the end is unconfirmed instead of
 * the newest line. Manual scrolling and the jump pill keep working, and leaving the state restores
 * ordinary following.
 */
@Composable
private fun TranscriptList(state: LiveCallUiState, onReload: () -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val following by remember {
        derivedStateOf { !listState.canScrollForward || listState.layoutInfo.visibleItemsInfo.isEmpty() }
    }
    val itemCount = state.turns.size + state.partials.size
    // An unconfirmed end is part of the content, not a card above it: the same scroll keeps it,
    // the transcript and the pinned action reachable at 200 % text.
    val unknownCard = state.phase == "unknown"
    val headerCount = if (unknownCard) 2 else 1
    // The last transcript item is the last item of the list.
    val lastIndex = headerCount + itemCount - 1
    // The unconfirmed end sits under the heading, so this is the row that must be seen.
    val unknownCardIndex = 1
    // One reveal per unconfirmed end: after it the reader owns the position, so a turn arriving
    // cannot drag the list back to the bottom and hide the message again.
    var unknownRevealed by remember { mutableStateOf(false) }
    LaunchedEffect(unknownCard, itemCount, following) {
        if (unknownCard) {
            if (!unknownRevealed) {
                listState.scrollToItem(unknownCardIndex)
                unknownRevealed = true
            }
        } else {
            unknownRevealed = false
            if (itemCount > 0 && following) listState.scrollToItem(lastIndex)
        }
    }
    // The jump control sits under the list, never over a line the reader is trying to read.
    Column(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(TRANSCRIPT_LIST_TAG),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { TranscriptHeading(state) }
            if (unknownCard) {
                item { UnknownStatusCard(reason = state.statusUnknownError, onReload = onReload) }
            }
            items(state.turns.size) { index -> TranscriptRow(state.turns[index]) }
            items(state.partials.size) { index -> PartialRow(state.partials[index]) }
            if (itemCount == 0) {
                item { LoadingState(message = stringResource(R.string.live_call_transcript_starts)) }
            } else if (!state.ended) {
                item {
                    Text(
                        text = stringResource(R.string.live_call_waiting_next_turn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        }
        if (!following) {
            TextButton(
                onClick = { scope.launch { listState.animateScrollToItem(lastIndex) } },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.live_call_jump_to_live))
            }
        }
    }
}

@Composable
private fun TranscriptRow(turn: TranscriptTurn) {
    val speaker = speakerOf(turn.role)
    val agent = speaker == Speaker.AGENT
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
            Text(
                text = speakerLabel(turn.role),
                style = MaterialTheme.typography.labelMedium,
                color = content
            )
            Text(
                text = turn.text ?: MISSING_VALUE,
                style = MaterialTheme.typography.bodyLarge,
                color = content
            )
        }
    }
}

/** A turn the daemon has not settled yet: the same shape as a finished turn, with an ellipsis. */
@Composable
private fun PartialRow(partial: PartialTurn) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = partial.role?.let { speakerLabel(it) }
                ?: stringResource(R.string.live_call_role_someone),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = partial.text + "…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The call's own failure, with the daemon's code behind Details and the one retry beside it. */
@Composable
private fun LiveCallError(
    message: com.phonxiety.bridge.designsystem.UiText,
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
            if (state.controls.summarize) {
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
            if (state.controls.marks) {
                MarksCard(state = state, onMark = onMark)
            }
            if (state.controls.dtmf) {
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
            // value keeps tabular monospace.
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
            Text(stringResource(R.string.live_call_hanging_up))
        } else {
            Text(stringResource(R.string.live_call_hang_up))
        }
    }
    if (confirming) {
        val seconds = state.callSecondsElapsed
        AlertDialog(
            onDismissRequest = { confirming = false },
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
                TextButton(
                    onClick = {
                        confirming = false
                        onHangUp()
                    }
                ) {
                    Text(stringResource(R.string.live_call_hang_up))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.live_call_hang_up_keep))
                }
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

/** The seconds the call has been connected, or the duration it ended at. */
internal fun elapsedSeconds(state: LiveCallUiState, nowEpochMs: Long): Long? {
    val start = state.connectedAtMs ?: state.createdAtMs ?: return null
    val end = (if (state.ended) state.endedAtMs else null) ?: nowEpochMs
    return ((end - start).coerceAtLeast(0)) / 1000
}

/** `MM:SS`, or `H:MM:SS` past an hour, from the platform formatter. */
internal fun formatTimer(state: LiveCallUiState, nowEpochMs: Long): String = elapsedSeconds(state, nowEpochMs)?.let(::formatElapsedTime) ?: MISSING_VALUE

/** "Call duration 1 minute 9 seconds": what TalkBack says instead of reading "1:09" as digits. */
internal fun spokenDuration(
    seconds: Long,
    resources: Resources
): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val rest = total % SECONDS_PER_MINUTE
    val words = buildList {
        if (hours > 0) {
            add(resources.getQuantityString(R.plurals.live_call_duration_hours, hours.toInt(), hours.toInt()))
        }
        if (minutes > 0) {
            add(
                resources.getQuantityString(
                    R.plurals.live_call_duration_minutes,
                    minutes.toInt(),
                    minutes.toInt()
                )
            )
        }
        if (rest > 0 || isEmpty()) {
            add(resources.getQuantityString(R.plurals.live_call_duration_seconds, rest.toInt(), rest.toInt()))
        }
    }
    return resources.getString(R.string.live_call_duration_description, words.joinToString(" "))
}

@Composable
private fun spokenDuration(seconds: Long): String = spokenDuration(seconds, LocalResources.current)

/** `assistant`/`user` are the daemon's words for the two speakers; older records say `agent`/`far_end`. */
@Composable
internal fun speakerLabel(role: String?): String = speakerLabel(role, LocalResources.current)

internal fun speakerLabel(role: String?, resources: Resources): String = when (speakerOf(role)) {
    Speaker.AGENT -> resources.getString(R.string.live_call_role_agent)

    Speaker.FAR_END -> resources.getString(R.string.live_call_role_far_end)

    Speaker.OTHER -> role?.trim()?.takeIf { it.isNotEmpty() }
        ?.replace('_', ' ')
        ?.replaceFirstChar { it.uppercase() }
        ?: resources.getString(R.string.live_call_role_someone)
}

/** The pill never says a call is live while the link it reports through is down. */
internal fun statusLabel(state: LiveCallUiState, resources: Resources): String = if (state.reconnecting) {
    resources.getString(R.string.live_call_phase_reconnecting)
} else {
    phaseLabel(state, resources)
}

internal fun phaseLabel(state: LiveCallUiState, resources: Resources): String = when (state.phase) {
    "preparing" -> resources.getString(R.string.live_call_phase_preparing)

    "dialing" -> resources.getString(R.string.live_call_phase_dialing)

    "active" -> resources.getString(R.string.live_call_phase_live)

    "ending" -> resources.getString(R.string.live_call_phase_ending)

    "ended" -> resources.getString(R.string.live_call_phase_ended)

    "cancelled" -> resources.getString(R.string.live_call_phase_canceled)

    "failed" -> resources.getString(R.string.live_call_phase_failed)

    "unknown" -> resources.getString(R.string.live_call_phase_unknown)

    else -> resources.getString(
        if (state.loaded) R.string.live_call_phase_idle else R.string.live_call_phase_loading
    )
}

@Composable
internal fun statusLabel(state: LiveCallUiState): String = statusLabel(state, LocalResources.current)

internal fun phaseTone(state: LiveCallUiState): StatusTone = when (state.phase) {
    "active" -> StatusTone.POSITIVE
    "dialing", "preparing", "ending" -> StatusTone.WARNING
    "failed", "cancelled" -> StatusTone.NEGATIVE
    else -> StatusTone.NEUTRAL
}

/** Playback overflows are counted in samples; the tile reports the audio time they cost. */
internal fun playbackDropMillis(samples: Long): Long = samples.coerceAtLeast(0) / AUDIO_SAMPLES_PER_MILLISECOND

/** A daemon token the screen shows as words: `reservation_time` → `Reservation time`. */
internal fun humanizeToken(token: String): String = token.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }
