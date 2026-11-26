package com.phonxiety.bridge.feature.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.data.Speaker
import com.phonxiety.bridge.data.speakerOf
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.designsystem.formatDuration
import com.phonxiety.bridge.designsystem.formatElapsedTime
import com.phonxiety.bridge.designsystem.formatPhoneNumber
import com.phonxiety.bridge.model.CallMark
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.TranscriptTurn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable
fun CallDetailRoute(
    callId: String,
    onBack: () -> Unit,
    onCallAgain: (CallBrief) -> Unit,
    onOpenLive: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CallDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(callId) { viewModel.bind(callId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    CallDetailScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onLoadMoreTranscript = viewModel::loadMoreTranscript,
        onMark = viewModel::mark,
        onCallAgain = onCallAgain,
        onOpenLive = onOpenLive,
        onDownloadAudio = viewModel::downloadAudio,
        onTogglePlayback = viewModel::togglePlayback,
        onSeek = viewModel::seekTo,
        onShareAudio = {
            viewModel.audioFile()?.let { file ->
                CallSharing.shareFile(context, file, "audio/mp4", "phonxiety call ${state.callId}")
            }
        },
        onShareTranscript = {
            val file = CallSharing.transcriptFile(context, state.callId, state.transcript)
            CallSharing.shareFile(context, file, "text/plain", "phonxiety transcript ${state.callId}")
        },
        onDelete = { viewModel.delete(onBack) },
        modifier = modifier
    )
}

@Composable
fun CallDetailScreen(
    state: CallDetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMoreTranscript: () -> Unit,
    onMark: (String) -> Unit,
    onCallAgain: (CallBrief) -> Unit,
    onOpenLive: (String) -> Unit,
    onDownloadAudio: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Long) -> Unit,
    onShareAudio: () -> Unit,
    onShareTranscript: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snapshot = state.snapshot
    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        when {
            state.missing -> Column {
                DetailHeader(state = state, onBack = onBack)
                EmptyState(
                    title = stringResource(R.string.call_detail_missing_title),
                    message = stringResource(R.string.call_detail_missing_body)
                )
            }

            snapshot == null && state.loading -> Column {
                DetailHeader(state = state, onBack = onBack)
                LoadingState(message = stringResource(R.string.call_detail_loading))
            }

            snapshot == null -> Column {
                DetailHeader(state = state, onBack = onBack)
                val failure = state.error
                ErrorCard(
                    title = stringResource(R.string.call_detail_read_failed),
                    message = failure?.message?.asString() ?: stringResource(R.string.call_detail_read_failed_body),
                    output = failure?.code,
                    actionLabel = stringResource(R.string.call_detail_retry),
                    onAction = onRefresh,
                    modifier = Modifier.padding(16.dp)
                )
            }

            else -> CallDetailBody(
                state = state,
                snapshot = snapshot,
                onBack = onBack,
                onRefresh = onRefresh,
                onLoadMoreTranscript = onLoadMoreTranscript,
                onMark = onMark,
                onCallAgain = onCallAgain,
                onOpenLive = onOpenLive,
                onDownloadAudio = onDownloadAudio,
                onTogglePlayback = onTogglePlayback,
                onSeek = onSeek,
                onShareAudio = onShareAudio,
                onShareTranscript = onShareTranscript,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun CallDetailBody(
    state: CallDetailUiState,
    snapshot: CallSnapshot,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMoreTranscript: () -> Unit,
    onMark: (String) -> Unit,
    onCallAgain: (CallBrief) -> Unit,
    onOpenLive: (String) -> Unit,
    onDownloadAudio: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Long) -> Unit,
    onShareAudio: () -> Unit,
    onShareTranscript: () -> Unit,
    onDelete: () -> Unit
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { DetailHeader(state = state, onBack = onBack) }
        if (state.live) {
            item { LiveCard(callId = state.callId, onOpenLive = onOpenLive) }
        }
        if (state.offline) {
            item { OfflineNote(onRefresh = onRefresh) }
        }
        item { ResultCard(state) }
        item { RequestCard(state) }
        item { TimingCard(state) }
        item {
            TextButton(onClick = { showDetails = !showDetails }) {
                Text(
                    stringResource(
                        if (showDetails) R.string.call_detail_details_hide else R.string.call_detail_details_show
                    )
                )
            }
        }
        if (showDetails) {
            item { DetailsCard(snapshot) }
        }
        item { MarksCard(state = state, onMark = onMark) }
        item {
            RecordingCard(
                state = state,
                onDownload = onDownloadAudio,
                onToggle = onTogglePlayback,
                onSeek = onSeek,
                onShare = onShareAudio
            )
        }
        item { TranscriptCard(state = state, onLoadMore = onLoadMoreTranscript) }
        item { DetailActions(state = state, onCallAgain = onCallAgain, onShareTranscript = onShareTranscript) }
        item { DeleteCard(state = state, onDelete = onDelete) }
        item { Spacer(Modifier.width(4.dp)) }
    }
}

@Composable
private fun DetailHeader(state: CallDetailUiState, onBack: () -> Unit) {
    val snapshot = state.snapshot
    val number = snapshot?.destination?.let(::formatPhoneNumber)
        ?: stringResource(R.string.call_detail_fallback_title)
    val subtitle = listOfNotNull(
        snapshot?.farEnd?.takeIf { it.isNotBlank() }?.let { farEndLabel(it) },
        stateLabel(snapshot?.state)
    ).joinToString(" · ")
    val live = state.live
    val pill = if (live) {
        stringResource(R.string.calls_live)
    } else {
        outcomeLabel(snapshot?.callOutcome)
            ?: stateLabel(snapshot?.state)
            ?: stringResource(R.string.calls_status_unknown)
    }
    val tone = if (live) StatusTone.ACCENT else outcomeTone(snapshot?.callOutcome)
    // At 200 % text the number, the subtitle and the pill cannot share a row without one of them
    // shrinking to a character a line, so the header stacks instead.
    val stacked = LocalDensity.current.fontScale >= STACKED_AT_FONT_SCALE
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.call_detail_back),
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = number, style = MaterialTheme.typography.titleLarge, maxLines = 3)
                if (subtitle.isNotBlank() && !stacked) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
            if (!stacked) {
                StatusPill(label = pill, tone = tone, pulse = live)
            }
        }
        if (stacked) {
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            StatusPill(label = pill, tone = tone, pulse = live, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        }
    }
}

@Composable
private fun LiveCard(callId: String, onOpenLive: (String) -> Unit) {
    SectionCard(title = stringResource(R.string.call_detail_live_title)) {
        Text(
            text = stringResource(R.string.call_detail_live_body),
            style = MaterialTheme.typography.bodyMedium
        )
        FilledTonalButton(onClick = { onOpenLive(callId) }) {
            Text(stringResource(R.string.call_detail_open_live))
        }
    }
}

@Composable
private fun OfflineNote(onRefresh: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.call_detail_offline),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onRefresh,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
            ) {
                Text(stringResource(R.string.call_detail_refresh))
            }
        }
    }
}

@Composable
private fun ResultCard(state: CallDetailUiState) {
    val snapshot = state.snapshot
    val summary = snapshot?.summary
    val fields = state.summaryFields
    SectionCard(
        title = stringResource(R.string.call_detail_result_title),
        subtitle = snapshot?.taskOutcome?.takeIf { it.isNotBlank() }?.let(::humanize),
        status = {
            resultLabel(snapshot?.resultStatus)?.let { label ->
                StatusPill(
                    label = label,
                    tone = if (snapshot?.resultStatus == "ready") StatusTone.POSITIVE else StatusTone.NEUTRAL
                )
            }
        }
    ) {
        if (summary.isNullOrBlank() && fields.isEmpty()) {
            Text(
                text = stringResource(
                    if (state.live) R.string.call_detail_summary_pending else R.string.call_detail_summary_absent
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!summary.isNullOrBlank()) {
            Text(text = summary, style = MaterialTheme.typography.bodyLarge)
        }
        fields.entries.forEach { (key, value) ->
            KeyValueRow(label = humanize(key), value = value.render(), mono = true)
        }
        snapshot?.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun RequestCard(state: CallDetailUiState) {
    val request = state.snapshot?.request ?: return
    SectionCard(title = stringResource(R.string.call_detail_brief_title)) {
        request.text("destination")?.let {
            KeyValueRow(label = stringResource(R.string.call_detail_number), value = formatPhoneNumber(it), mono = true)
        }
        request.text("goal")?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge) }
        request.text("context")?.let { value ->
            KeyValueRow(label = stringResource(R.string.call_detail_context), value = value)
        }
        request.text("constraints")?.let { value ->
            KeyValueRow(label = stringResource(R.string.call_detail_constraints), value = value)
        }
        val fields = state.resultFields
        if (fields.isNotEmpty()) {
            KeyValueRow(
                label = stringResource(R.string.call_detail_reported_fields),
                value = fields.joinToString(", ") { humanize(it) }
            )
        }
        val limits = request["limits"] as? JsonObject
        limits?.text("max_duration_s")?.toLongOrNull()?.let {
            KeyValueRow(label = stringResource(R.string.call_detail_longest_call), value = formatDuration(it), mono = true)
        }
        limits?.text("ring_timeout_s")?.toLongOrNull()?.let {
            KeyValueRow(label = stringResource(R.string.call_detail_ring_timeout), value = formatDuration(it), mono = true)
        }
    }
}

@Composable
private fun TimingCard(state: CallDetailUiState) {
    val snapshot = state.snapshot ?: return
    SectionCard(title = stringResource(R.string.call_detail_timing_title)) {
        snapshot.createdAt?.let { KeyValueRow(label = stringResource(R.string.call_detail_requested), value = formatDate(it)) }
        snapshot.connectedAt?.let { KeyValueRow(label = stringResource(R.string.call_detail_connected), value = formatDate(it)) }
        snapshot.endedAt?.let { KeyValueRow(label = stringResource(R.string.call_detail_ended), value = formatDate(it)) }
        val duration = snapshot.computedDurationSeconds()
        if (duration != null) {
            KeyValueRow(
                label = stringResource(R.string.call_detail_duration),
                value = formatElapsedTime(duration.toLong()),
                mono = true
            )
        }
        snapshot.disconnectCause?.let { cause ->
            KeyValueRow(
                label = stringResource(R.string.call_detail_disconnected_by),
                value = causeLabel(cause.text("reason"))
            )
        }
    }
}

/** The evidence behind the words: the id, the raw codes and the per-call counters. */
@Composable
private fun DetailsCard(snapshot: CallSnapshot) {
    SectionCard(title = stringResource(R.string.call_detail_details_title)) {
        KeyValueRow(label = stringResource(R.string.call_detail_call_id), value = snapshot.callId, mono = true)
        snapshot.state?.let { KeyValueRow(label = stringResource(R.string.call_detail_raw_state), value = it, mono = true) }
        snapshot.callOutcome?.let { KeyValueRow(label = stringResource(R.string.call_detail_raw_outcome), value = it, mono = true) }
        snapshot.resultStatus?.let { KeyValueRow(label = stringResource(R.string.call_detail_raw_result), value = it, mono = true) }
        val counters = snapshot.counters
        if (counters != null && counters.isNotEmpty()) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.call_detail_counters_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(R.string.call_detail_counters_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            counters.entries.forEach { (key, value) ->
                KeyValueRow(label = humanize(key), value = value.render(), mono = true)
            }
        }
    }
}

@Composable
private fun MarksCard(state: CallDetailUiState, onMark: (String) -> Unit) {
    val count = state.marks.size
    SectionCard(
        title = stringResource(R.string.call_detail_marks_title),
        subtitle = if (count == 0) {
            stringResource(R.string.call_detail_marks_empty)
        } else {
            pluralStringResource(R.plurals.call_detail_marks_count, count, count)
        }
    ) {
        FlowRowChips {
            DETAIL_MARKS.forEach { kind ->
                val selected = state.marks.any { it.mark == kind }
                FilterChip(
                    selected = selected,
                    onClick = { onMark(kind) },
                    label = { Text(markLabel(kind)) },
                    leadingIcon = { SelectedCheck(selected) },
                    enabled = !state.marking
                )
            }
        }
        state.marks.forEach { mark: CallMark ->
            KeyValueRow(
                label = mark.note ?: markLabel(mark.mark),
                value = mark.at?.let(::formatDate) ?: markLabel(mark.mark)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        content()
    }
}

@Composable
private fun RecordingCard(
    state: CallDetailUiState,
    onDownload: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onShare: () -> Unit
) {
    if (!state.hasRecording) return
    val failure = state.audio.error
    SectionCard(
        title = stringResource(R.string.call_detail_recording_title),
        subtitle = stringResource(recordingSubtitle(state.audio)),
        status = {
            if (failure != null) StatusPill(stringResource(R.string.call_detail_failed_pill), StatusTone.NEGATIVE)
        }
    ) {
        if (state.audio.downloading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (state.audio.filePath == null) {
            FilledTonalButton(onClick = onDownload) { Text(stringResource(R.string.call_detail_recording_download)) }
        } else {
            val position = formatElapsedTime(state.audio.positionMs / 1000)
            val total = formatElapsedTime((state.audio.durationMs ?: 0L) / 1000)
            // The times sit under the track: beside it, large text left the seek bar no width at all.
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggle, enabled = state.audio.prepared) {
                        Icon(
                            imageVector = if (state.audio.playing) PauseIcon else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (state.audio.playing) {
                                    R.string.call_detail_recording_pause
                                } else {
                                    R.string.call_detail_recording_play
                                }
                            ),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    val duration = (state.audio.durationMs ?: 0L).coerceAtLeast(1L)
                    val name = stringResource(R.string.call_detail_recording_position)
                    val positionState = stringResource(R.string.call_detail_recording_position_state, position, total)
                    Slider(
                        value = (state.audio.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                        onValueChange = { fraction -> onSeek((fraction * duration).toLong()) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(RECORDING_SEEK_TAG)
                            .semantics {
                                contentDescription = name
                                stateDescription = positionState
                            },
                        enabled = state.audio.prepared
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = PLAY_BUTTON_WIDTH),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    PlaybackTime(position)
                    PlaybackTime(total)
                }
            }
            TextButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.call_detail_share_audio))
            }
        }
        if (failure != null) {
            Text(
                text = failure.message.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            failure.code?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PlaybackTime(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Material's pause glyph: the core icon set has play but not pause. */
private val PauseIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Pause",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 19f)
            horizontalLineTo(10f)
            verticalLineTo(5f)
            horizontalLineTo(6f)
            close()
            moveTo(14f, 5f)
            verticalLineTo(19f)
            horizontalLineTo(18f)
            verticalLineTo(5f)
            close()
        }
    }.build()
}

/** The recording's seek bar, for tests that measure it. */
internal const val RECORDING_SEEK_TAG = "call_detail_recording_seek"

/** The play button's touch target, so the times line up under the seek bar. */
private val PLAY_BUTTON_WIDTH = 48.dp

@Composable
private fun TranscriptCard(state: CallDetailUiState, onLoadMore: () -> Unit) {
    val count = state.transcript.size
    SectionCard(
        title = stringResource(R.string.call_detail_transcript_title),
        subtitle = when {
            count == 0 -> stringResource(R.string.call_detail_transcript_empty)
            state.hasMoreTranscript -> pluralStringResource(R.plurals.call_detail_turns_first, count, count)
            else -> pluralStringResource(R.plurals.call_detail_turns, count, count)
        }
    ) {
        state.transcript.forEach { turn ->
            DetailTurn(turn)
        }
        if (state.loadingTranscript) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (state.hasMoreTranscript) {
            TextButton(onClick = onLoadMore, enabled = !state.loadingTranscript) {
                Text(stringResource(R.string.call_detail_load_more_turns))
            }
        }
    }
}

@Composable
private fun DetailTurn(turn: TranscriptTurn) {
    val speaker = speakerOf(turn.role)
    val agent = speaker == Speaker.AGENT
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = when (speaker) {
                Speaker.AGENT -> stringResource(R.string.call_detail_role_agent)
                Speaker.FAR_END -> stringResource(R.string.call_detail_role_far_end)
                Speaker.OTHER -> turn.role ?: "—"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (agent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = turn.text ?: "—", style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    }
}

/** One filled action, one tonal: "Call again" is what most people came for. */
@Composable
private fun DetailActions(
    state: CallDetailUiState,
    onCallAgain: (CallBrief) -> Unit,
    onShareTranscript: () -> Unit
) {
    FlowRowChips {
        Button(onClick = { state.brief?.let(onCallAgain) }, enabled = state.brief != null) {
            Text(stringResource(R.string.call_detail_action_call_again))
        }
        FilledTonalButton(onClick = onShareTranscript, enabled = state.transcript.isNotEmpty()) {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.call_detail_action_share))
        }
    }
}

@Composable
private fun DeleteCard(state: CallDetailUiState, onDelete: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        title = stringResource(R.string.call_detail_delete_title),
        subtitle = stringResource(R.string.call_detail_delete_subtitle)
    ) {
        Button(
            onClick = { confirming = true },
            enabled = !state.deleting,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(
                    if (state.deleting) R.string.call_detail_deleting else R.string.call_detail_delete_action
                )
            )
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.call_detail_delete_confirm_title)) },
            text = { Text(stringResource(R.string.call_detail_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onDelete()
                    }
                ) {
                    Text(stringResource(R.string.call_detail_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.call_detail_delete_keep)) }
            }
        )
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonElement.render(): String = when (this) {
    is JsonPrimitive -> contentOrNull ?: toString()
    is JsonArray -> joinToString(", ") { it.render() }
    else -> toString()
}

/** The record carries the two timestamps; the length is their difference, not a stored field. */
internal fun CallSnapshot.computedDurationSeconds(): Double? {
    val connected = connectedAt ?: return null
    val ended = endedAt ?: return null
    return ((ended - connected).coerceAtLeast(0.0) * 10).toLong() / 10.0
}

internal val DETAIL_MARKS: List<String> = listOf("agreed", "refused", "follow-up")

/** Above this font scale a header row would squeeze its own title, so it stacks. */
private const val STACKED_AT_FONT_SCALE = 1.5f
