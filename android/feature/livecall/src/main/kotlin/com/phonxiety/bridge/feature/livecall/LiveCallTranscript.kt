package com.phonxiety.bridge.feature.livecall

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.data.Speaker
import com.phonxiety.bridge.data.speakerOf
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.model.PartialTurn
import com.phonxiety.bridge.model.TranscriptTurn
import kotlinx.coroutines.launch

/** The daemon's own name for a call whose phone link died; see `controller._fail`. */
internal const val LINK_LOST_ERROR = "device_disconnected"

/** The transcript's scroll container, so tests reach a card that is not in the first viewport. */
internal const val TRANSCRIPT_LIST_TAG = "live_call_transcript"

/** The heading is item zero of the transcript's own list. */
internal const val TRANSCRIPT_HEADING_KEY = "transcript:heading"

/** The unconfirmed-end card sits directly under the heading while it is on screen. */
internal const val UNKNOWN_STATUS_KEY = "transcript:unknown"

/**
 * Whether the record's error is the daemon's link loss. Any other reason (an unacknowledged
 * hang-up, a voice failure) says nothing about the connection and must not claim it.
 */
internal fun isLinkLoss(reason: String?): Boolean = reason?.trim() == LINK_LOST_ERROR

/**
 * The transcript is the screen: its heading is the first row of the same scroll, so at 200 % text it
 * moves out of the way instead of eating the only readable viewport.
 */
@Composable
internal fun TranscriptCard(
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
 * as an ended call, and a dropped link never claims the agent is live. While the link is down the
 * heading comes before present-tense activity or progress: it shows the turns already recorded, or
 * waits neutrally, and a retained partial stays on screen without being called in progress. A
 * partial turn is progress only while connected, when it is on screen as it is spoken.
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

    state.reconnecting && state.turns.isNotEmpty() ->
        resources.getQuantityString(R.plurals.live_call_turns, state.turns.size, state.turns.size)

    state.reconnecting -> resources.getString(R.string.live_call_transcript_waiting)

    state.activity == "working" -> resources.getString(R.string.live_call_transcript_working)

    state.turns.isNotEmpty() ->
        resources.getQuantityString(R.plurals.live_call_turns, state.turns.size, state.turns.size)

    state.partials.isNotEmpty() -> resources.getString(R.string.live_call_transcript_in_progress)

    else -> resources.getString(R.string.live_call_transcript_waiting)
}

/**
 * Newest turn at the bottom. Following is a property of the scroll position: as soon as the user
 * scrolls up the list stops moving, and the jump pill brings it back to live. The heading is item
 * zero of this same list, so the transcript text owns every pixel the screen can spare.
 *
 * Every row is keyed by what it is — a final turn by its sequence, a partial by its role — and
 * carries its own content type. A partial that becomes a final turn replaces its own row in place:
 * the sequence it settles under is the same row, so a reader's first visible line does not move.
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
            item(key = TRANSCRIPT_HEADING_KEY, contentType = "heading") { TranscriptHeading(state) }
            if (unknownCard) {
                item(key = UNKNOWN_STATUS_KEY, contentType = "notice") {
                    UnknownStatusCard(reason = state.statusUnknownError, onReload = onReload)
                }
            }
            items(
                count = state.turns.size,
                key = { index -> turnKey(state.turns[index]) },
                contentType = { "turn" }
            ) { index -> TranscriptRow(state.turns[index]) }
            items(
                count = state.partials.size,
                key = { index -> partialKey(index, state.partials[index]) },
                contentType = { "partial" }
            ) { index -> PartialRow(state.partials[index]) }
            if (itemCount == 0) {
                item(key = "transcript:empty", contentType = "notice") {
                    LoadingState(message = stringResource(R.string.live_call_transcript_starts))
                }
            } else if (!state.ended) {
                item(key = "transcript:waiting", contentType = "notice") {
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
            // Nothing is live once the end is recorded: the control goes to the last line, not to
            // a conversation that is over. An unconfirmed end is neither live nor recorded: the
            // control goes to the newest stored turn and claims neither state.
            val label = when {
                !state.ended -> R.string.live_call_jump_to_live
                state.phase == "unknown" -> R.string.live_call_jump_to_latest
                else -> R.string.live_call_jump_to_end
            }
            TextButton(
                onClick = { scope.launch { listState.animateScrollToItem(lastIndex) } },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .heightIn(min = 48.dp)
            ) {
                Text(stringResource(label))
            }
        }
    }
}

/** A final turn is one row per sequence: the daemon never renumbers a recorded line. */
internal fun turnKey(turn: TranscriptTurn): String = "transcript:turn:${turn.sequence}"

/** A partial belongs to a speaker, so a role keeps the row through each update of its words. */
internal fun partialKey(index: Int, partial: PartialTurn): String {
    val role = partial.role?.trim()?.takeIf { it.isNotEmpty() }
    return if (role != null) "transcript:partial:$role" else "transcript:partial:$index"
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

/**
 * The call whose end the daemon recorded as unconfirmed. The reason it gives is evidence, so it
 * sits behind Details in the code face; the card says what is and is not known and offers the one
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
