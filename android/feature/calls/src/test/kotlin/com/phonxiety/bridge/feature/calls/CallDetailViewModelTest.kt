package com.phonxiety.bridge.feature.calls

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The delete question belongs to the screen state, and the confirmation consumes it before the
 * daemon is asked: a second tap has nothing left to confirm, so the record is deleted once.
 *
 * The snapshot has one writer, the cache's flow. A refresh asks the daemon, which writes through;
 * a refresh that fails leaves the cached record where it was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: RecordingCallsRepository

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = RecordingCallsRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): CallDetailViewModel = CallDetailViewModel(context, repository, SilentPlayer).also {
        it.bind(CALL_ID)
    }

    @Test
    fun theQuestionOpensAndClosesWithoutDeleting() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.requestDelete()
        assertTrue(viewModel.state.value.confirmingDelete)

        viewModel.dismissDelete()
        assertFalse(viewModel.state.value.confirmingDelete)

        advanceUntilIdle()
        assertEquals(emptyList<String>(), repository.deleted)
    }

    @Test
    fun confirmingDeletesExactlyOnce() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.requestDelete()

        var finished = 0
        viewModel.confirmDelete { finished++ }
        // The confirmation was consumed before the launch, so a double tap finds nothing to run.
        viewModel.confirmDelete { finished++ }
        advanceUntilIdle()

        assertEquals(listOf(CALL_ID), repository.deleted)
        assertEquals(1, finished)
        assertFalse(viewModel.state.value.confirmingDelete)
        assertTrue(viewModel.state.value.deleted)
    }

    @Test
    fun confirmingWithoutAQuestionDeletesNothing() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.confirmDelete {}
        advanceUntilIdle()
        assertEquals(emptyList<String>(), repository.deleted)
    }

    @Test
    fun onlyTheDatabaseChangesTheDetailSnapshot() = runTest(dispatcher) {
        repository.detailFlow.value = CACHED
        repository.networkSnapshot = CALL.copy(summary = "Answered by the daemon")
        val viewModel = viewModel()

        advanceUntilIdle()

        // The daemon's answer is not drawn directly: it only lands through the cache's flow.
        assertEquals(CACHED, viewModel.state.value.snapshot)
    }

    @Test
    fun aDatabaseUpdateReachesTheScreenWhileTheRefreshIsStillRunning() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        val viewModel = viewModel()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.refreshing)

        repository.detailFlow.value = UPDATED
        advanceUntilIdle()

        assertEquals(UPDATED, viewModel.state.value.snapshot)
        assertTrue("the refresh has not finished", viewModel.state.value.refreshing)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.refreshing)
    }

    @Test
    fun concurrentRefreshesMakeOneNetworkRequest() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(1, repository.detailRequests)

        // A pull while the first request is still open joins it instead of asking again.
        viewModel.refresh()
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals("one request while one is in flight", 1, repository.detailRequests)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, repository.detailRequests)

        // Once it has finished, a new pull is a new request.
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(2, repository.detailRequests)
    }

    @Test
    fun aFailedRefreshLeavesTheCachedRecordOnScreen() = runTest(dispatcher) {
        repository.detailFlow.value = CACHED
        repository.detailFailure = ApiOutcome.Failure(503, "link_down", "the device link is down")
        val viewModel = viewModel()

        advanceUntilIdle()

        assertEquals(CACHED, viewModel.state.value.snapshot)
        assertTrue(viewModel.state.value.offline)
        assertFalse(viewModel.state.value.refreshing)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun aRefusedRefreshKeepsTheCachedRecordAlreadyOnScreen() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        repository.detailFlow.value = CACHED
        // A refused refresh hands back the copy the store had, which is the one on screen here.
        repository.networkSnapshot = CACHED
        repository.detailFailure = NOT_FOUND
        repository.gate = gate
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(CACHED, viewModel.state.value.snapshot)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("the stored record is not replaced", CACHED, viewModel.state.value.snapshot)
        assertFalse("a stored record is not a missing call", viewModel.state.value.missing)
        assertNotNull("the refusal is still reported", viewModel.state.value.error)
    }

    @Test
    fun aRefusedRefreshDoesNotHideACachedRecordTheStoreEmitsAfterwards() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        repository.detailFlow.value = null
        // The refusal carries the record the store holds, so the screen learns of the copy before
        // the store's own emission of it arrives.
        repository.networkSnapshot = CACHED
        repository.detailFailure = NOT_FOUND
        repository.gate = gate
        val viewModel = viewModel()
        advanceUntilIdle()
        assertNull("the store's emission has not arrived yet", viewModel.state.value.snapshot)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse("the store's copy is not a missing call", viewModel.state.value.missing)

        repository.detailFlow.value = CACHED
        advanceUntilIdle()

        assertEquals(CACHED, viewModel.state.value.snapshot)
        assertFalse(viewModel.state.value.missing)
        assertNotNull(viewModel.state.value.error)
    }

    @Test
    fun aRefusedRefreshWithNothingStoredSaysTheCallIsMissing() = runTest(dispatcher) {
        repository.detailFlow.value = null
        repository.networkSnapshot = null
        repository.detailFailure = NOT_FOUND
        val viewModel = viewModel()

        advanceUntilIdle()

        assertNull(viewModel.state.value.snapshot)
        assertTrue("no copy to keep: the call is missing", viewModel.state.value.missing)
        assertFalse(viewModel.state.value.offline)
        assertNotNull(viewModel.state.value.error)
    }

    private companion object {
        const val CALL_ID = "call-1"

        val CALL = CallSnapshot(callId = CALL_ID, destination = "+998901234567", state = "ended", callOutcome = "answered")
        val NOT_FOUND = ApiOutcome.Failure(404, "call_not_found", "the call is not on the device")
        val CACHED = CALL.copy(summary = "Table for two on Thursday")
        val UPDATED = CALL.copy(summary = "Table moved to Friday")
    }
}

private object SilentPlayer : CallAudioPlayer {
    override val state = MutableStateFlow(PlaybackState())

    override fun prepare(file: File) = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun release() = Unit
}

/** A repository whose cache flow and network answer the test drives separately. */
private class RecordingCallsRepository : CallsRepository {
    val deleted = mutableListOf<String>()
    val detailFlow = MutableStateFlow<CallSnapshot?>(CALL)
    var networkSnapshot: CallSnapshot? = null
    var detailFailure: ApiOutcome.Failure? = null
    var detailRequests = 0

    /** When set, a detail request waits for it: the test decides when the refresh finishes. */
    var gate: CompletableDeferred<Unit>? = null

    override fun observeCalls(filter: CallFilter): Flow<List<CallSummary>> = flowOf(emptyList())

    override fun observeCall(callId: String): Flow<CallSummary?> = flowOf(null)

    override fun observeDetail(callId: String): Flow<CallSnapshot?> = detailFlow

    override fun observeTranscript(callId: String): Flow<List<TranscriptTurn>> = flowOf(emptyList())

    override fun observeFarEnds(): Flow<List<String>> = flowOf(emptyList())

    override suspend fun page(filter: CallFilter, before: Double?, limit: Int): CallPageResult = CallPageResult(emptyList(), hasMore = false, nextBefore = null, failure = null)

    override suspend fun detail(callId: String, refresh: Boolean): CallDetailResult {
        detailRequests++
        gate?.await()
        return CallDetailResult(networkSnapshot, failure = detailFailure, cached = networkSnapshot == null)
    }

    override suspend fun transcript(callId: String, cursor: Long?, limit: Int): TranscriptResult = TranscriptResult(emptyList(), nextCursor = null, hasMore = false, complete = true, failure = null)

    override suspend fun addMark(callId: String, mark: String, note: String?): ApiOutcome<MarksResponse> = ApiOutcome.Ok(200, MarksResponse())

    override suspend fun delete(callId: String): ApiOutcome<DeleteCallResult> {
        deleted += callId
        return ApiOutcome.Ok(200, DeleteCallResult())
    }

    override suspend fun downloadAudio(callId: String, format: String, file: File): ApiResult = ApiResult.Failure(501, "unused", "unused")

    private companion object {
        val CALL = CallSnapshot(callId = "call-1", destination = "+998901234567", state = "ended", callOutcome = "answered")
    }
}
