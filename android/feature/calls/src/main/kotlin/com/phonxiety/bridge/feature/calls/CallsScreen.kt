package com.phonxiety.bridge.feature.calls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.model.CallSummary
import java.text.DateFormat
import java.util.Date

/** The history entry point; the host supplies navigation only. */
@Composable
fun CallsRoute(
    onOpenCall: (String) -> Unit,
    onNewCall: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CallsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CallsScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onQueryChange = viewModel::setQuery,
        onOutcomeChange = viewModel::setOutcome,
        onFarEndChange = viewModel::setFarEnd,
        onClearFilters = viewModel::clearFilters,
        onOpenCall = onOpenCall,
        onNewCall = onNewCall,
        modifier = modifier
    )
}

/** The list: every call the daemon kept, filterable, searchable, and readable offline. */
@Composable
fun CallsScreen(
    state: CallsUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onQueryChange: (String) -> Unit,
    onOutcomeChange: (String?) -> Unit,
    onFarEndChange: (String?) -> Unit,
    onClearFilters: () -> Unit,
    onOpenCall: (String) -> Unit,
    onNewCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Calls",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
            )
            SearchField(query = state.filter.query, onQueryChange = onQueryChange)
            FilterRows(
                state = state,
                onOutcomeChange = onOutcomeChange,
                onFarEndChange = onFarEndChange,
                onClearFilters = onClearFilters
            )
            if (state.offline) {
                OfflineBanner(onRefresh = onRefresh)
            }
            if (state.error != null && !state.offline) {
                ErrorCard(
                    title = "The history could not be refreshed",
                    message = state.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    actionLabel = "Retry",
                    onAction = onRefresh
                )
            }
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    !state.loaded -> LoadingState(message = "Reading the history")

                    state.calls.isEmpty() -> EmptyState(
                        title = if (state.filtered) "Nothing matches" else "No calls yet",
                        message = if (state.filtered) {
                            "Relax the filter or clear the search."
                        } else {
                            "Calls placed from this phone or from the computer appear here with their transcript."
                        },
                        action = {
                            if (state.filtered) {
                                TextButton(onClick = onClearFilters) { Text("Clear the filters") }
                            } else {
                                PrimaryActionButton(
                                    label = "Place a call",
                                    onClick = onNewCall,
                                    modifier = Modifier.widthIn(max = 260.dp)
                                )
                            }
                        }
                    )

                    else -> CallsList(
                        state = state,
                        onOpenCall = onOpenCall,
                        onLoadMore = onLoadMore
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Search a summary, a number or a name") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear the search", modifier = Modifier.size(18.dp))
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium
    )
}

@Composable
private fun FilterRows(
    state: CallsUiState,
    onOutcomeChange: (String?) -> Unit,
    onFarEndChange: (String?) -> Unit,
    onClearFilters: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = state.filter.outcome == null,
                onClick = { onOutcomeChange(null) },
                label = { Text("All") }
            )
            state.outcomes.forEach { outcome ->
                FilterChip(
                    selected = state.filter.outcome == outcome,
                    onClick = {
                        onOutcomeChange(if (state.filter.outcome == outcome) null else outcome)
                    },
                    label = { Text(outcome.replace('_', ' ')) }
                )
            }
        }
        if (state.farEnds.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                state.farEnds.take(MAX_FAR_END_CHIPS).forEach { farEnd ->
                    FilterChip(
                        selected = state.filter.farEnd == farEnd,
                        onClick = {
                            onFarEndChange(if (state.filter.farEnd == farEnd) null else farEnd)
                        },
                        label = { Text(farEnd, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
                if (state.filtered) {
                    TextButton(onClick = onClearFilters) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
private fun OfflineBanner(onRefresh: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "No link to the computer: showing the calls cached on this phone.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRefresh) { Text("Retry") }
        }
    }
}

@Composable
private fun CallsList(
    state: CallsUiState,
    onOpenCall: (String) -> Unit,
    onLoadMore: () -> Unit
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        items(state.calls, key = { it.callId }) { call ->
            CallRow(call = call, onClick = { onOpenCall(call.callId) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
        if (state.loadingMore) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        } else if (state.hasMore) {
            // The sentinel is composed only when the reader reaches it, so the next page is
            // requested from the list itself instead of reading scroll geometry in composition.
            item {
                LaunchedEffect(state.calls.size) { onLoadMore() }
                Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            item {
                Text(
                    text = "That is every call on the daemon.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun CallRow(call: CallSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = call.destination?.let(::maskNumber) ?: "Unknown number",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
                if (call.live) {
                    Spacer(Modifier.width(8.dp))
                    StatusPill(label = "Live", tone = StatusTone.ACCENT, pulse = true)
                }
            }
            call.farEnd?.takeIf { it.isNotBlank() }?.let { farEnd ->
                Text(
                    text = farEnd,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = call.summary ?: call.goal ?: "No summary yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    call.createdAt?.let(::formatDate),
                    call.durationSeconds?.let(::formatDuration),
                    call.marks.takeIf { it > 0 }?.let { marks -> if (marks == 1) "1 mark" else "$marks marks" },
                    if (call.hasRecording) "recording" else null
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(
            label = (call.callOutcome ?: call.state ?: "unknown").replace('_', ' '),
            tone = outcomeTone(call.callOutcome)
        )
    }
}

internal fun maskNumber(destination: String): String {
    val digits = destination.filter { it.isDigit() }
    if (digits.length < 5) return destination
    val country = if (destination.trimStart().startsWith("+")) "+${digits.take(3)}" else ""
    val tail = digits.takeLast(2)
    return if (country.isEmpty()) "·· $tail" else "$country ·· $tail"
}

internal fun outcomeTone(outcome: String?): StatusTone = when (outcome) {
    "answered" -> StatusTone.POSITIVE
    "no_answer", "busy", "declined" -> StatusTone.WARNING
    "cancelled", "disconnected", "failed" -> StatusTone.NEGATIVE
    else -> StatusTone.NEUTRAL
}

internal fun formatDate(epochSeconds: Double): String {
    val millis = (epochSeconds * 1000).toLong()
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}

internal fun formatDuration(seconds: Double): String {
    val total = seconds.toLong()
    val minutes = total / 60
    val rest = total % 60
    return if (minutes == 0L) "${rest}s" else "${minutes}m ${rest.toString().padStart(2, '0')}s"
}

private const val MAX_FAR_END_CHIPS = 4
