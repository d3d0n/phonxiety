package com.phonxiety.bridge.feature.calls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.designsystem.farEndLabel
import com.phonxiety.bridge.designsystem.formatElapsedTime
import com.phonxiety.bridge.designsystem.formatPhoneNumber
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

/** The list: every call the bridge kept, filterable, searchable, and readable offline. */
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
    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.calls_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    .semantics { heading() }
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
            val failure = state.error
            if (failure != null && !state.offline) {
                ErrorCard(
                    title = stringResource(R.string.calls_refresh_failed),
                    message = failure.message.asString(),
                    output = failure.code,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    actionLabel = stringResource(R.string.calls_retry),
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
                    !state.loaded -> LoadingState(message = stringResource(R.string.calls_loading))

                    state.calls.isEmpty() -> EmptyState(
                        title = stringResource(
                            if (state.filtered) R.string.calls_empty_filtered_title else R.string.calls_empty_title
                        ),
                        message = stringResource(
                            if (state.filtered) R.string.calls_empty_filtered_body else R.string.calls_empty_body
                        ),
                        action = {
                            if (state.filtered) {
                                TextButton(onClick = onClearFilters) { Text(stringResource(R.string.calls_filter_clear)) }
                            } else {
                                PrimaryActionButton(
                                    label = stringResource(R.string.calls_place_call),
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
        placeholder = { Text(stringResource(R.string.calls_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.calls_clear_search),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

/**
 * Two rows of filters, each named: the call's outcome, and what answered at the far end. The chip
 * label is the word a person reads; the stored value stays the daemon's key.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRows(
    state: CallsUiState,
    onOutcomeChange: (String?) -> Unit,
    onFarEndChange: (String?) -> Unit,
    onClearFilters: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Chips wrap instead of scrolling: a label cut at the edge reads as a broken control.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = state.filter.outcome == null,
                onClick = { onOutcomeChange(null) },
                label = { Text(stringResource(R.string.calls_filter_all)) },
                leadingIcon = { SelectedCheck(state.filter.outcome == null) }
            )
            state.outcomes.forEach { outcome ->
                val selected = state.filter.outcome == outcome
                FilterChip(
                    selected = selected,
                    onClick = { onOutcomeChange(if (selected) null else outcome) },
                    label = { Text(outcomeLabel(outcome) ?: humanize(outcome)) },
                    leadingIcon = { SelectedCheck(selected) }
                )
            }
            // A loaded list with nothing matching shows the same action in its empty state: one button,
            // one word. Before the list has loaded (or after a failed refresh) only this row offers it.
            if (state.filtered && (!state.loaded || state.calls.isNotEmpty())) {
                TextButton(onClick = onClearFilters) { Text(stringResource(R.string.calls_filter_clear)) }
            }
        }
        if (state.farEnds.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.calls_filter_reached),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.farEnds.forEach { farEnd ->
                    val selected = state.filter.farEnd == farEnd
                    FilterChip(
                        selected = selected,
                        onClick = { onFarEndChange(if (selected) null else farEnd) },
                        label = { Text(farEndLabel(farEnd)) },
                        leadingIcon = { SelectedCheck(selected) }
                    )
                }
            }
        }
    }
}

/** Selection is a shape as well as a tint, so a chip never relies on colour alone. */
@Composable
internal fun SelectedCheck(selected: Boolean) {
    if (selected) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            modifier = Modifier.size(FilterChipDefaults.IconSize)
        )
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
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.calls_offline),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onRefresh,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
            ) {
                Text(stringResource(R.string.calls_retry))
            }
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
                    text = stringResource(R.string.calls_end_of_history),
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
    // At 200 % text a pill beside the number leaves it three groups a line; the pill moves under
    // the text instead of squeezing it.
    val stacked = LocalDensity.current.fontScale >= STACKED_AT_FONT_SCALE
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        if (stacked) {
            CallText(call)
            Spacer(Modifier.height(6.dp))
            CallStatusPill(call)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallText(call, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                CallStatusPill(call)
            }
        }
    }
}

@Composable
private fun CallText(call: CallSummary, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = call.destination?.let(::formatPhoneNumber) ?: stringResource(R.string.calls_unknown_number),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace
        )
        call.farEnd?.takeIf { it.isNotBlank() }?.let { farEnd ->
            Text(
                text = farEndLabel(farEnd),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = call.summary ?: call.goal ?: stringResource(R.string.calls_no_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            // A preview that stops mid-word looks broken; the ellipsis says the rest is one tap
            // away on the call's own screen.
            overflow = TextOverflow.Ellipsis
        )
        val marks = call.marks
        Text(
            text = listOfNotNull(
                call.createdAt?.let(::formatDate),
                call.durationSeconds?.let { formatElapsedTime(it.toLong()) },
                marks.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.calls_marks, it, it) },
                stringResource(R.string.calls_recording_available).takeIf { call.hasRecording }
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One pill per row: the live phase, or what the call's record says it became. */
@Composable
private fun CallStatusPill(call: CallSummary) {
    if (call.live) {
        StatusPill(
            label = stringResource(R.string.calls_live),
            tone = StatusTone.ACCENT,
            pulse = true
        )
        return
    }
    val label = outcomeLabel(call.callOutcome)
        ?: stateLabel(call.state)
        ?: stringResource(R.string.calls_status_unknown)
    StatusPill(label = label, tone = outcomeTone(call.callOutcome))
}

internal fun outcomeTone(outcome: String?): StatusTone = when (outcome) {
    "answered" -> StatusTone.POSITIVE
    "no_answer", "busy", "declined" -> StatusTone.WARNING
    "cancelled", "canceled", "disconnected", "failed" -> StatusTone.NEGATIVE
    else -> StatusTone.NEUTRAL
}

internal fun formatDate(epochSeconds: Double): String {
    val millis = (epochSeconds * 1000).toLong()
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}

/** Above this font scale a pill beside a row's text would squeeze it, so the pill moves under. */
private const val STACKED_AT_FONT_SCALE = 1.5f
