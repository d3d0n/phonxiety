package com.phonxiety.bridge.feature.livecall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.designsystem.LocalPhonxietyType
import com.phonxiety.bridge.designsystem.component.ActionEmphasis
import com.phonxiety.bridge.designsystem.component.AnimatedValueText
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.STACK_AT_FONT_SCALE
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.designsystem.farEndLabel
import com.phonxiety.bridge.designsystem.formatPhoneNumber
import kotlinx.coroutines.delay

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
        onHangUp = viewModel::requestHangUp,
        onConfirmHangUp = viewModel::confirmHangUp,
        onDismissHangUp = viewModel::dismissHangUp,
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
    onConfirmHangUp: () -> Unit,
    onDismissHangUp: () -> Unit,
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
                    onConfirmHangUp = onConfirmHangUp,
                    onDismissHangUp = onDismissHangUp,
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
    onConfirmHangUp: () -> Unit,
    onDismissHangUp: () -> Unit,
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
            PinnedActionRow(
                state = state,
                onHangUp = onHangUp,
                onConfirmHangUp = onConfirmHangUp,
                onDismissHangUp = onDismissHangUp,
                onOpenDetail = onOpenDetail
            )
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
 *
 * Hang-up is the one control that never waits for `/info`: the end-of-call path works whether or
 * not the voice backend says it honours a hang-up, so a live call is always endable. The other
 * unknown controls keep explaining why they are disabled.
 */
@Composable
private fun PinnedActionRow(
    state: LiveCallUiState,
    onHangUp: () -> Unit,
    onConfirmHangUp: () -> Unit,
    onDismissHangUp: () -> Unit,
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
        } else {
            HangUpButton(
                state = state,
                onRequestHangUp = onHangUp,
                onConfirmHangUp = onConfirmHangUp,
                onDismissHangUp = onDismissHangUp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * The number as the owner reads it, then the running time and who is on the line. The timer is the
 * platform's elapsed format in the code face, animated in place; its spoken name spells the
 * duration out, because "1:09" is read as digits.
 */
@Composable
private fun LiveCallHeader(state: LiveCallUiState, onBack: () -> Unit, nowEpochMs: Long) {
    val seconds = elapsedSeconds(state, nowEpochMs)
    val spoken = seconds?.let { spokenDuration(it) }
    val stacked = LocalDensity.current.fontScale >= STACK_AT_FONT_SCALE
    val timerStyle = LocalPhonxietyType.current.code(MaterialTheme.typography.titleSmall, tabular = true)
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
                    AnimatedValueText(
                        text = formatTimer(state, nowEpochMs),
                        style = timerStyle,
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
