package com.phonxiety.bridge.feature.calls

import app.cash.turbine.test
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.calls.CallDetailResult
import com.phonxiety.bridge.data.calls.CallFilter
import com.phonxiety.bridge.data.calls.CallPageResult
import com.phonxiety.bridge.data.calls.CallsRepository
import com.phonxiety.bridge.data.calls.TranscriptResult
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.CallSummary
import com.phonxiety.bridge.model.DeleteCallResult
import com.phonxiety.bridge.model.MarksResponse
import com.phonxiety.bridge.model.TranscriptTurn
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Paging and the two ways it can end: the daemon answers (append, dedup, stop at the end) or it
 * does not (the cache is shown and the screen says it is a copy).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeCallsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeCallsRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theFirstPageComesFromTheDaemon() = runTest(dispatcher) {
        repository.pages += CallPageResult(
            calls = listOf(call("a"), call("b")),
            hasMore = true,
            nextBefore = 100.0,
            failure = null
        )

        val viewModel = CallsViewModel(repository)
        viewModel.state.test {
            awaitItem()
            var state = awaitItem()
            while (!(state.loaded && state.calls.size == 2)) state = awaitItem()
            assertTrue(state.hasMore)
            assertFalse(state.filtered)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun loadMoreAppendsWithoutDuplicates() = runTest(dispatcher) {
        repository.pages += CallPageResult(listOf(call("a"), call("b")), hasMore = true, nextBefore = 50.0, failure = null)
        repository.pages += CallPageResult(listOf(call("b"), call("c")), hasMore = false, nextBefore = null, failure = null)

        val viewModel = CallsViewModel(repository).also { collect(it) }
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf("a", "b", "c"), state.calls.map { it.callId })
        assertFalse(state.hasMore)
        assertEquals(listOf(null, 50.0), repository.beforeRequests)
    }

    @Test
    fun anOfflinePageShowsTheCacheAndSaysSo() = runTest(dispatcher) {
        repository.cached.value = listOf(call("cached"))
        repository.pages += CallPageResult(
            calls = emptyList(),
            hasMore = false,
            nextBefore = null,
            failure = ApiOutcome.Failure(503, "link_down", "the bridge service is not running")
        )

        val viewModel = CallsViewModel(repository).also { collect(it) }
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.offline)
        assertEquals(listOf("cached"), state.calls.map { it.callId })
        assertEquals("the bridge service is not running (link_down)", state.error)
    }

    @Test
    fun aFilterAsksTheDaemonAgainAndSearchesWhatIsLoaded() = runTest(dispatcher) {
        repository.cached.value = listOf(call("a"), call("b", summary = "Pizza order"))
        repository.pages += CallPageResult(listOf(call("a"), call("b", summary = "Pizza order")), hasMore = false, nextBefore = null, failure = null)

        val viewModel = CallsViewModel(repository).also { collect(it) }
        advanceUntilIdle()

        viewModel.setQuery("pizza")
        advanceUntilIdle()
        assertEquals(listOf("b"), viewModel.state.value.calls.map { it.callId })
        assertEquals(1, repository.pageRequests)

        viewModel.setOutcome("answered")
        advanceUntilIdle()
        assertEquals(2, repository.pageRequests)
        assertEquals("answered", repository.filters.last().outcome)
    }

    @Test
    fun clearingTheFilterRestoresTheList() = runTest(dispatcher) {
        repository.cached.value = listOf(call("a"), call("b"))
        repository.pages += CallPageResult(listOf(call("a"), call("b")), hasMore = false, nextBefore = null, failure = null)

        val viewModel = CallsViewModel(repository).also { collect(it) }
        advanceUntilIdle()
        viewModel.setQuery("nothing matches")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.calls.isEmpty())

        repository.cached.value = listOf(call("a"), call("b"))
        repository.pages += CallPageResult(listOf(call("a"), call("b")), hasMore = false, nextBefore = null, failure = null)
        viewModel.clearFilters()
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.calls.size)
        assertFalse(viewModel.state.value.filter.isFiltered)
    }

    /**
     * The state is `WhileSubscribed`, exactly as the screen uses it: without a collector the
     * combine never runs, so every test that reads `state.value` holds one for its lifetime.
     */
    private fun TestScope.collect(viewModel: CallsViewModel) {
        backgroundScope.launch { viewModel.state.collect {} }
    }

    private fun call(callId: String, summary: String = "Table booked"): CallSummary = summary(callId = callId, summary = summary)
}

/** A repository the test scripts page by page; the cache is a flow the test can fill. */
private class FakeCallsRepository : CallsRepository {
    val cached = MutableStateFlow<List<CallSummary>>(emptyList())
    val pages = mutableListOf<CallPageResult>()
    val beforeRequests = mutableListOf<Double?>()
    val filters = mutableListOf<CallFilter>()
    var pageRequests = 0

    override fun observeCalls(filter: CallFilter): Flow<List<CallSummary>> = cached.map { rows ->
        rows.filter { row ->
            (filter.outcome == null || row.callOutcome == filter.outcome) &&
                (filter.farEnd == null || row.farEnd == filter.farEnd)
        }
    }

    override fun observeCall(callId: String): Flow<CallSummary?> = cached.map { rows -> rows.firstOrNull { it.callId == callId } }

    override fun observeDetail(callId: String): Flow<CallSnapshot?> = MutableStateFlow<CallSnapshot?>(null)

    override fun observeTranscript(callId: String): Flow<List<TranscriptTurn>> = MutableStateFlow(emptyList())

    override fun observeFarEnds(): Flow<List<String>> = MutableStateFlow(emptyList())

    override suspend fun page(filter: CallFilter, before: Double?, limit: Int): CallPageResult {
        pageRequests++
        beforeRequests += before
        filters += filter
        return if (pages.isEmpty()) {
            CallPageResult(emptyList(), false, null, null)
        } else {
            pages.removeAt(0)
        }
    }

    override suspend fun detail(callId: String, refresh: Boolean): CallDetailResult = CallDetailResult(null, null, cached = true)

    override suspend fun transcript(callId: String, cursor: Long?, limit: Int): TranscriptResult = TranscriptResult(emptyList(), cursor, false, false, null)

    override suspend fun addMark(callId: String, mark: String, note: String?): ApiOutcome<MarksResponse> = ApiOutcome.Ok(200, MarksResponse())

    override suspend fun delete(callId: String): ApiOutcome<DeleteCallResult> = ApiOutcome.Ok(200, DeleteCallResult())

    override suspend fun downloadAudio(callId: String, format: String, file: File): ApiResult = ApiResult.Failure(501, "unused", "unused")
}
