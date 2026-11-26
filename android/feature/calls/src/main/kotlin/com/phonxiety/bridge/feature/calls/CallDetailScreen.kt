package com.phonxiety.bridge.feature.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.data.Speaker
import com.phonxiety.bridge.data.speakerOf
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
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
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        when {
            state.missing -> Column {
                DetailHeader(state = state, onBack = onBack)
                EmptyState(
                    title = "This call is gone",
                    message = "It was deleted from the daemon, or the id never existed."
                )
            }

            snapshot == null && state.loading -> Column {
                DetailHeader(state = state, onBack = onBack)
                LoadingState(message = "Reading the call")
            }

            snapshot == null -> Column {
                DetailHeader(state = state, onBack = onBack)
                ErrorCard(
                    title = "The call could not be read",
                    message = state.error ?: "The daemon did not answer.",
                    actionLabel = "Retry",
                    onAction = onRefresh,
                    modifier = Modifier.padding(16.dp)
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { DetailHeader(state = state, onBack = onBack) }
                if (state.offline) {
                    item { OfflineNote(onRefresh = onRefresh) }
                }
                if (state.live) {
                    item {
                        SectionCard(title = "In progress", status = { StatusPill("Live", StatusTone.ACCENT, pulse = true) }) {
                            Text(
                                text = "This call is still running. Supervision and the hang-up live on the live call screen.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            FilledTonalButton(onClick = { onOpenLive(state.callId) }) {
                                Text("Open the live call")
                            }
                        }
                    }
                }
                item { ResultCard(state) }
                item { RequestCard(state) }
                item { TimingCard(state) }
                val counters = snapshot.counters
                if (counters != null && counters.isNotEmpty()) {
                    item { CountersCard(counters) }
                }
                item { MarksCard(state = state, onMark = onMark) }
                item { RecordingCard(state = state, onDownload = onDownloadAudio, onToggle = onTogglePlayback, onSeek = onSeek, onShare = onShareAudio) }
                item { TranscriptCard(state = state, onLoadMore = onLoadMoreTranscript) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { state.brief?.let(onCallAgain) },
                            enabled = state.brief != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Call again")
                        }
                        FilledTonalButton(
                            onClick = onShareTranscript,
                            enabled = state.transcript.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share transcript")
                        }
                    }
                }
                item { DeleteCard(state = state, onDelete = onDelete) }
                item { Spacer(Modifier.width(4.dp)) }
            }
        }
    }
}

@Composable
private fun DetailHeader(state: CallDetailUiState, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
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
                text = state.snapshot?.destination?.let(::maskNumber) ?: "Call",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    state.snapshot?.farEnd?.takeIf { it.isNotBlank() },
                    state.snapshot?.callId,
                    state.snapshot?.state
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        StatusPill(
            label = (state.snapshot?.callOutcome ?: state.snapshot?.state ?: "unknown").replace('_', ' '),
            tone = outcomeTone(state.snapshot?.callOutcome)
        )
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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Showing the copy cached on this phone.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
    }
}

@Composable
private fun ResultCard(state: CallDetailUiState) {
    val summary = state.snapshot?.summary
    val fields = state.summaryFields
    SectionCard(
        title = "Result",
        subtitle = state.snapshot?.taskOutcome?.replace('_', ' '),
        status = {
            state.snapshot?.resultStatus?.let { StatusPill(it, if (it == "ready") StatusTone.POSITIVE else StatusTone.NEUTRAL) }
        }
    ) {
        if (summary.isNullOrBlank() && fields.isEmpty()) {
            Text(
                text = "No summary was produced.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!summary.isNullOrBlank()) {
            Text(text = summary, style = MaterialTheme.typography.bodyLarge)
        }
        fields.entries.forEach { (key, value) ->
            KeyValueRow(label = key.replace('_', ' '), value = value.render(), mono = true)
        }
        state.snapshot?.error?.let { error ->
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
    SectionCard(title = "The brief") {
        request.text("destination")?.let { KeyValueRow(label = "Number", value = it, mono = true) }
        request.text("goal")?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge) }
        request.text("context")?.let { value ->
            KeyValueRow(label = "Context", value = value)
        }
        request.text("constraints")?.let { value ->
            KeyValueRow(label = "Constraints", value = value)
        }
        val fields = state.resultFields
        if (fields.isNotEmpty()) {
            KeyValueRow(label = "Fields", value = fields.joinToString(", "))
        }
        val limits = request["limits"] as? JsonObject
        limits?.text("max_duration_s")?.let { KeyValueRow(label = "Max duration", value = formatDuration(it.toDoubleOrNull() ?: 0.0), mono = true) }
        limits?.text("ring_timeout_s")?.let { KeyValueRow(label = "Ring timeout", value = "$it s", mono = true) }
    }
}

@Composable
private fun TimingCard(state: CallDetailUiState) {
    val snapshot = state.snapshot ?: return
    SectionCard(title = "Timing") {
        snapshot.createdAt?.let { KeyValueRow(label = "Requested", value = formatDate(it)) }
        snapshot.connectedAt?.let { KeyValueRow(label = "Connected", value = formatDate(it)) }
        snapshot.endedAt?.let { KeyValueRow(label = "Ended", value = formatDate(it)) }
        val duration = snapshot.computedDurationSeconds()
        if (duration != null) {
            KeyValueRow(label = "Duration", value = formatDuration(duration), mono = true)
        }
        snapshot.disconnectCause?.let { cause ->
            KeyValueRow(label = "Disconnected by", value = cause.text("reason") ?: cause.toString())
        }
    }
}

@Composable
private fun CountersCard(counters: JsonObject) {
    SectionCard(title = "Counters", subtitle = "Reported by the phone during the call") {
        counters.entries.forEach { (key, value) ->
            KeyValueRow(label = key.replace('_', ' '), value = value.render(), mono = true)
        }
    }
}

@Composable
private fun MarksCard(state: CallDetailUiState, onMark: (String) -> Unit) {
    SectionCard(
        title = "Marks",
        subtitle = if (state.marks.isEmpty()) "Nothing marked" else "${state.marks.size} marked"
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DETAIL_MARKS.forEach { kind ->
                FilterChip(
                    selected = state.marks.any { it.mark == kind },
                    onClick = { onMark(kind) },
                    label = { Text(kind.replace('-', ' ').replaceFirstChar { it.uppercase() }) },
                    enabled = !state.marking
                )
            }
        }
        state.marks.forEach { mark: CallMark ->
            KeyValueRow(
                label = mark.note ?: mark.mark.replace('-', ' '),
                value = mark.at?.let(::formatDate) ?: mark.mark
            )
        }
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
    SectionCard(
        title = "Recording",
        subtitle = "The .m4a share copy, downloaded to this phone",
        status = {
            if (state.audio.error != null) StatusPill("Failed", StatusTone.NEGATIVE)
        }
    ) {
        if (state.audio.downloading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = "Downloading from the daemon…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (state.audio.filePath == null) {
            FilledTonalButton(onClick = onDownload) { Text("Download the recording") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggle, enabled = state.audio.prepared) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = if (state.audio.playing) "Pause" else "Play",
                        modifier = Modifier.size(22.dp)
                    )
                }
                val duration = (state.audio.durationMs ?: 0L).coerceAtLeast(1L)
                Slider(
                    value = (state.audio.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { fraction -> onSeek((fraction * duration).toLong()) },
                    modifier = Modifier.weight(1f),
                    enabled = state.audio.prepared
                )
                Text(
                    text = "${formatMillis(state.audio.positionMs)} / ${formatMillis(state.audio.durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share the audio")
                }
            }
        }
        state.audio.error?.let { message ->
            Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TranscriptCard(state: CallDetailUiState, onLoadMore: () -> Unit) {
    SectionCard(
        title = "Transcript",
        subtitle = when {
            state.transcript.isEmpty() -> "No turns were recorded"
            state.hasMoreTranscript -> "The first ${state.transcript.size} turns"
            else -> "${state.transcript.size} turns"
        }
    ) {
        state.transcript.forEach { turn ->
            DetailTurn(turn)
        }
        if (state.loadingTranscript) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (state.hasMoreTranscript) {
            TextButton(onClick = onLoadMore, enabled = !state.loadingTranscript) { Text("Load more turns") }
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
                Speaker.AGENT -> "Agent"
                Speaker.FAR_END -> "Far end"
                Speaker.OTHER -> turn.role ?: "—"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (agent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = turn.text ?: "—", style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    }
}

@Composable
private fun DeleteCard(state: CallDetailUiState, onDelete: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    SectionCard(title = "Delete", subtitle = "Removes the record, the transcript and the recording from the daemon") {
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
            Text(if (state.deleting) "Deleting…" else "Delete this call")
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete this call?") },
            text = { Text("The daemon removes its archive. This cannot be undone, and the phone's cache copy goes with it.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onDelete()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Keep it") }
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

internal fun formatMillis(ms: Long?): String {
    if (ms == null || ms <= 0) return "0:00"
    val total = ms / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

internal val DETAIL_MARKS: List<String> = listOf("agreed", "refused", "follow-up")
