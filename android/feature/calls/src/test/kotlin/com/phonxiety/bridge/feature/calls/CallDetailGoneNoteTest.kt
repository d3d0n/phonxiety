package com.phonxiety.bridge.feature.calls

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.model.CallSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A call the computer no longer has, still shown from the copy saved on the phone: the screen says
 * so beside the record instead of keeping the refusal to itself. Any other refresh failure keeps
 * the offline note it always had.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallDetailGoneNoteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val cached = CallSnapshot(callId = "call-1", destination = "+998901234567", state = "ended", callOutcome = "answered")

    @Test
    fun aStoredRecordTheComputerNoLongerHasSaysSo() {
        show(CallDetailUiState(callId = "call-1", snapshot = cached, loading = false, error = CallsFailure.of("gone", "call_not_found")))

        composeRule.onNodeWithText(context.getString(R.string.call_detail_gone_on_computer)).assertIsDisplayed()
    }

    @Test
    fun anotherRefreshFailureDoesNotSayTheCallIsGone() {
        show(CallDetailUiState(callId = "call-1", snapshot = cached, loading = false, offline = true, error = CallsFailure.of("busy", "daemon_busy")))

        composeRule.onNodeWithText(context.getString(R.string.call_detail_offline)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.call_detail_gone_on_computer)).assertDoesNotExist()
    }

    @Test
    fun aCallGoneAfterAFailedRefreshShowsOnlyTheGoneNote() {
        // An earlier refresh failed and left the offline note; a later answer says the call is gone.
        show(CallDetailUiState(callId = "call-1", snapshot = cached, loading = false, offline = true, error = CallsFailure.of("gone", "call_not_found")))

        composeRule.onNodeWithText(context.getString(R.string.call_detail_gone_on_computer)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.call_detail_offline)).assertDoesNotExist()
    }

    private fun show(state: CallDetailUiState) {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
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
                    onRequestDelete = {},
                    onDismissDelete = {},
                    onConfirmDelete = {}
                )
            }
        }
    }
}
