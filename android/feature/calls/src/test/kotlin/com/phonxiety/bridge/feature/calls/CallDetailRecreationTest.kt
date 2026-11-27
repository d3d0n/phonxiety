package com.phonxiety.bridge.feature.calls

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.DeleteOutcome
import com.phonxiety.bridge.data.MarkOutcome
import com.phonxiety.bridge.data.calls.CallDetailResult
import com.phonxiety.bridge.data.calls.CallFilter
import com.phonxiety.bridge.data.calls.CallPageResult
import com.phonxiety.bridge.data.calls.CallsRepository
import com.phonxiety.bridge.data.calls.TranscriptResult
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.model.CallId
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.CallSummary
import com.phonxiety.bridge.model.DeleteCallResult
import com.phonxiety.bridge.model.MarksResponse
import com.phonxiety.bridge.model.TranscriptTurn
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The delete question on a real activity that is destroyed and rebuilt. The ViewModel outlives the
 * composition exactly as a retained ViewModel outlives a rotation, and the dialog is drawn from
 * its state alone: nothing about the question is remembered inside the screen, so a screen that
 * went back to a composable-local `remember` would come back without it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallDetailRecreationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theDeleteQuestionSurvivesActivityRecreation() {
        val user = RecreatedCallRepository()
        val viewModel = CallDetailViewModel(context, user, IdlePlayer)
        viewModel.bind(CallId("call-1"))
        val content: @Composable () -> Unit = {
            PhonxietyTheme(dynamicColor = false) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                CallDetailScreen(
                    state = state,
                    onBack = {},
                    onRefresh = {},
                    onLoadMoreTranscript = {},
                    onMark = {},
                    onCallAgain = {},
                    onOpenLive = {},
                    onDownloadAudio = {},
                    onTogglePlayback = {},
                    onSeek = {},
                    onShareAudio = {},
                    onShareTranscript = {},
                    onRequestDelete = viewModel::requestDelete,
                    onDismissDelete = viewModel::dismissDelete,
                    onConfirmDelete = { viewModel.confirmDelete {} }
                )
            }
        }
        composeRule.activity.setContent { content() }

        composeRule.waitUntil(HAS_LIST) {
            composeRule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Delete this call"))
        composeRule.onNodeWithText("Delete this call").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(HAS_QUESTION) {
            composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.activityRule.scenario.recreate()
        composeRule.activityRule.scenario.onActivity { activity -> activity.setContent { content() } }

        composeRule.waitUntil(HAS_QUESTION) {
            composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Delete this call?").assertExists()
        composeRule.onNodeWithText("Keep it").assertExists()
    }

    private companion object {
        const val HAS_LIST = 5_000L
        const val HAS_QUESTION = 5_000L
    }
}

private object IdlePlayer : CallAudioPlayer {
    override val state = MutableStateFlow(PlaybackState())

    override fun prepare(file: File) = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun release() = Unit
}

/** One finished call; the deletion is allowed but never needed by this test. */
private class RecreatedCallRepository : CallsRepository {
    override fun observeCalls(filter: CallFilter): Flow<List<CallSummary>> = flowOf(emptyList())

    override fun observeCall(callId: CallId): Flow<CallSummary?> = flowOf(null)

    override fun observeDetail(callId: CallId): Flow<CallSnapshot?> = flowOf(CALL)

    override fun observeTranscript(callId: CallId): Flow<List<TranscriptTurn>> = flowOf(emptyList())

    override fun observeFarEnds(): Flow<List<String>> = flowOf(emptyList())

    override suspend fun page(filter: CallFilter, before: Double?, limit: Int): CallPageResult = CallPageResult(emptyList(), hasMore = false, nextBefore = null, failure = null)

    override suspend fun detail(callId: CallId, refresh: Boolean): CallDetailResult = CallDetailResult(CALL, failure = null, cached = false)

    override suspend fun transcript(callId: CallId, cursor: Long?, limit: Int): TranscriptResult = TranscriptResult(emptyList(), nextCursor = null, hasMore = false, complete = true, failure = null)

    override suspend fun addMark(callId: CallId, mark: String, note: String?): MarkOutcome = MarkOutcome.Marked(200, MarksResponse().marks)

    override suspend fun delete(callId: CallId): DeleteOutcome = DeleteOutcome.Deleted(200)

    override suspend fun downloadAudio(callId: CallId, format: String, file: File): ApiResult = ApiResult.Failure(501, "unused", "unused")

    private companion object {
        val CALL = CallSnapshot(callId = "call-1", destination = "+998901234567", state = "ended", callOutcome = "answered")
    }
}
