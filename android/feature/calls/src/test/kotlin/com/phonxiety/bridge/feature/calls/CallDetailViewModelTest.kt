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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The delete question belongs to the screen state, and the confirmation consumes it before the
 * daemon is asked: a second tap has nothing left to confirm, so the record is deleted once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: RecordingCallsRepository
    private lateinit var viewModel: CallDetailViewModel

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = RecordingCallsRepository()
        viewModel = CallDetailViewModel(context, repository, SilentPlayer)
        viewModel.bind(CALL_ID)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theQuestionOpensAndClosesWithoutDeleting() = runTest(dispatcher) {
        viewModel.requestDelete()
        assertTrue(viewModel.state.value.confirmingDelete)

        viewModel.dismissDelete()
        assertFalse(viewModel.state.value.confirmingDelete)

        advanceUntilIdle()
        assertEquals(emptyList<String>(), repository.deleted)
    }

    @Test
    fun confirmingDeletesExactlyOnce() = runTest(dispatcher) {
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
        viewModel.confirmDelete {}
        advanceUntilIdle()
        assertEquals(emptyList<String>(), repository.deleted)
    }

    private companion object {
        const val CALL_ID = "call-1"
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

/** A repository that answers one finished call and records every deletion it is asked for. */
private class RecordingCallsRepository : CallsRepository {
    val deleted = mutableListOf<String>()

    override fun observeCalls(filter: CallFilter): Flow<List<CallSummary>> = flowOf(emptyList())

    override fun observeCall(callId: String): Flow<CallSummary?> = flowOf(null)

    override fun observeDetail(callId: String): Flow<CallSnapshot?> = flowOf(CALL)

    override fun observeTranscript(callId: String): Flow<List<TranscriptTurn>> = flowOf(emptyList())

    override fun observeFarEnds(): Flow<List<String>> = flowOf(emptyList())

    override suspend fun page(filter: CallFilter, before: Double?, limit: Int): CallPageResult = CallPageResult(emptyList(), hasMore = false, nextBefore = null, failure = null)

    override suspend fun detail(callId: String, refresh: Boolean): CallDetailResult = CallDetailResult(CALL, failure = null, cached = false)

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
