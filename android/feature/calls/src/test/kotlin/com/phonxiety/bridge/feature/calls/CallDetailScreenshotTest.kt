package com.phonxiety.bridge.feature.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.model.CallMark
import com.phonxiety.bridge.model.TranscriptTurn
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A finished call and a running one: the detail screen has to make both obvious at a glance. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CallDetailScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, state: CallDetailUiState) {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
                        onDelete = {}
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/call_detail_$name.png")
    }

    @Test
    fun finished() = capture(
        "finished",
        CallDetailUiState(
            callId = "call-1",
            snapshot = detailSnapshot(
                transcript = TRANSCRIPT,
                marks = listOf(CallMark("agreed", 1_764_000_030.0), CallMark("follow-up", 1_764_000_040.0, "confirm the window seat"))
            ),
            transcript = TRANSCRIPT,
            marks = listOf(CallMark("agreed", 1_764_000_030.0), CallMark("follow-up", 1_764_000_040.0, "confirm the window seat")),
            loading = false,
            hasMoreTranscript = true,
            audio = CallAudioUiState(downloading = true)
        )
    )

    @Test
    fun live() = capture(
        "live",
        CallDetailUiState(
            callId = "call-2",
            snapshot = detailSnapshot(callId = "call-2", live = true),
            transcript = listOf(TranscriptTurn(1, "agent", "Good evening…", 1_764_000_006.0, true)),
            loading = false
        )
    )

    @Test
    fun offlineCopy() = capture(
        "offline",
        CallDetailUiState(
            callId = "call-3",
            snapshot = detailSnapshot(callId = "call-3", transcript = TRANSCRIPT.take(1)),
            transcript = TRANSCRIPT.take(1),
            loading = false,
            offline = true,
            error = "the bridge service is not running (link_down)"
        )
    )

    private companion object {
        val TRANSCRIPT = listOf(
            TranscriptTurn(1, "agent", "Good evening, I would like to book a table for two on Thursday at eight.", 1_764_000_006.0, true),
            TranscriptTurn(2, "far_end", "Thursday at eight is full. We have seven or nine.", 1_764_000_014.0, true),
            TranscriptTurn(3, "agent", "Nine would work. Two people, under the name Rasulov.", 1_764_000_021.0, true)
        )
    }
}
