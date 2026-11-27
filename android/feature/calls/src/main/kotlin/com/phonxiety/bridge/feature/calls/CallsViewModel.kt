package com.phonxiety.bridge.feature.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.data.calls.CallFilter
import com.phonxiety.bridge.data.calls.CallsRepository
import com.phonxiety.bridge.model.CallSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One screenful of history: the daemon's page, the cache behind it, and what the refresh said. */
data class CallsUiState(
    val calls: List<CallSummary> = emptyList(),
    val filter: CallFilter = CallFilter(),
    val farEnds: List<String> = emptyList(),
    val outcomes: List<String> = CALL_OUTCOMES,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    val offline: Boolean = false,
    val error: CallsFailure? = null
) {
    val filtered: Boolean get() = filter.isFiltered
    val empty: Boolean get() = loaded && calls.isEmpty() && !refreshing
}

private data class Page(
    val items: List<CallSummary> = emptyList(),
    val nextBefore: Double? = null,
    val hasMore: Boolean = false,
    val loaded: Boolean = false
)

private data class Busy(
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val offline: Boolean = false,
    val error: CallsFailure? = null
)

/**
 * History with the daemon as the record and the Room cache as the offline answer. Search runs over
 * what is loaded because the daemon's page has no query parameter; filters are the daemon's own.
 */
@HiltViewModel
class CallsViewModel @Inject constructor(
    private val repository: CallsRepository
) : ViewModel() {
    private val filter = MutableStateFlow(CallFilter())
    private val page = MutableStateFlow(Page())
    private val busy = MutableStateFlow(Busy())

    private val cached = MutableStateFlow<List<CallSummary>>(emptyList())

    val state: StateFlow<CallsUiState> = combine(
        filter,
        page,
        busy,
        cached,
        repository.observeFarEnds()
    ) { currentFilter, currentPage, currentBusy, cacheRows, farEnds ->
        val source = if (currentPage.loaded) currentPage.items else cacheRows
        CallsUiState(
            calls = source.filter { it.matches(currentFilter.query) },
            filter = currentFilter,
            farEnds = farEnds,
            refreshing = currentBusy.refreshing,
            loadingMore = currentBusy.loadingMore,
            loaded = currentPage.loaded || cacheRows.isNotEmpty(),
            hasMore = currentPage.hasMore,
            offline = currentBusy.offline,
            error = currentBusy.error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = CallsUiState()
    )

    init {
        viewModelScope.launch {
            filter.collectLatest { current ->
                repository.observeCalls(current).collect { cacheRows -> cached.value = cacheRows }
            }
        }
        refresh()
    }

    fun refresh() {
        if (busy.value.refreshing) return
        busy.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val result = repository.page(filter.value, before = null)
            val failure = result.failure
            if (failure != null) {
                busy.update {
                    it.copy(
                        refreshing = false,
                        offline = true,
                        error = CallsFailure.of(failure.status, failure.code, failure.message)
                    )
                }
            } else {
                page.value = Page(
                    items = result.calls,
                    nextBefore = result.nextBefore,
                    hasMore = result.hasMore,
                    loaded = true
                )
                busy.update { it.copy(refreshing = false, offline = false, error = null) }
            }
        }
    }

    fun loadMore() {
        val current = page.value
        if (!current.loaded || !current.hasMore || busy.value.loadingMore || busy.value.refreshing) return
        val before = current.nextBefore ?: return
        busy.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val result = repository.page(filter.value, before = before)
            val failure = result.failure
            if (failure != null) {
                busy.update {
                    it.copy(loadingMore = false, offline = true, error = CallsFailure.of(failure.status, failure.code, failure.message))
                }
            } else {
                val known = current.items.mapTo(HashSet()) { it.callId }
                page.value = current.copy(
                    items = current.items + result.calls.filterNot { it.callId in known },
                    nextBefore = result.nextBefore,
                    hasMore = result.hasMore
                )
                busy.update { it.copy(loadingMore = false, offline = false) }
            }
        }
    }

    fun setOutcome(outcome: String?) = applyFilter(filter.value.copy(outcome = outcome))

    fun setFarEnd(farEnd: String?) = applyFilter(filter.value.copy(farEnd = farEnd))

    fun setQuery(query: String) = applyFilter(filter.value.copy(query = query))

    fun clearFilters() = applyFilter(CallFilter())

    private fun applyFilter(next: CallFilter) {
        if (next == filter.value) return
        val needsDaemon = next.outcome != filter.value.outcome || next.farEnd != filter.value.farEnd
        filter.value = next
        if (needsDaemon) {
            page.value = Page()
            refresh()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The outcomes the daemon stores; the filter chips are these words. */
internal val CALL_OUTCOMES: List<String> =
    listOf("answered", "no_answer", "busy", "declined", "disconnected", "cancelled")

/** Search covers what a person remembers: who was called and what it was about. */
internal fun CallSummary.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim()
    return listOfNotNull(destination, summary, goal, farEnd).any { it.contains(needle, ignoreCase = true) }
}
