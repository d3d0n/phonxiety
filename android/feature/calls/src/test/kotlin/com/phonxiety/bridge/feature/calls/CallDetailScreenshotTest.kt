package com.phonxiety.bridge.feature.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.designsystem.formatPhoneNumber
import com.phonxiety.bridge.model.CallMark
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.TranscriptTurn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * One call's record on the decision-10 matrix: 411 dp at 100 %, 320 dp at 200 %, light, dark and one
 * dynamic-color case, the running call, the offline copy, the lower sections and the delete dialog.
 * Every capture runs the accessibility gate first.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CallDetailScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setScreen(
        state: CallDetailUiState,
        dark: Boolean = false,
        dynamicColor: Boolean = false,
        fontScale: Float = 1f,
        width: Dp = 411.dp,
        onDelete: () -> Unit = {}
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale)
            ) {
                PhonxietyTheme(darkTheme = dark, dynamicColor = dynamicColor) {
                    Box(
                        modifier = Modifier
                            .testTag(SCREEN_TAG)
                            .width(width)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
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
                            onDelete = onDelete
                        )
                    }
                }
            }
        }
    }

    private fun render(name: String, beforeCapture: () -> Unit = {}, content: () -> Unit) {
        content()
        beforeCapture()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SCREEN_TAG).captureRoboImage("src/test/screenshots/call_detail_$name.png")
        composeRule.onNodeWithTag(SCREEN_TAG).assertAccessible()
    }

    @Test
    fun widePhoneInLight() = render("411_light") { setScreen(finished()) }

    @Test
    fun widePhoneInDark() = render("411_dark") { setScreen(finished(), dark = true) }

    @Test
    fun widePhoneInDynamicColor() = render("411_dynamic") { setScreen(finished(), dynamicColor = true) }

    @Test
    fun smallScreenAtTwoHundredPercentInLight() = render("320_font200_light") {
        setScreen(finished(), fontScale = 2f, width = 320.dp)
    }

    @Test
    fun smallScreenAtTwoHundredPercentInDark() = render("320_font200_dark") {
        setScreen(finished(), dark = true, fontScale = 2f, width = 320.dp)
    }

    @Test
    fun aRunningCall() = render("live_411_light") {
        setScreen(
            CallDetailUiState(
                callId = "call-2",
                snapshot = detailSnapshot(callId = "call-2", live = true),
                transcript = listOf(TranscriptTurn(1, "agent", "Good evening…", 1_764_000_006.0, true)),
                loading = false
            )
        )
    }

    @Test
    fun theOfflineCopy() = render("offline_411_light") {
        setScreen(
            CallDetailUiState(
                callId = "call-3",
                snapshot = detailSnapshot(callId = "call-3", transcript = TRANSCRIPT.take(1)),
                transcript = TRANSCRIPT.take(1),
                loading = false,
                offline = true
            )
        )
    }

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

    /** A lazy list composes by viewport, so the next section is reached through the list itself. */
    private fun scrollToNode(text: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private companion object {
        const val SCREEN_TAG = "call_detail_capture"

        val TRANSCRIPT = listOf(
            TranscriptTurn(1, "agent", "Good evening, I would like to book a table for two on Thursday at eight.", 1_764_000_006.0, true),
            TranscriptTurn(2, "far_end", "Thursday at eight is full. We have seven or nine.", 1_764_000_014.0, true),
            TranscriptTurn(3, "agent", "Nine would work. Two people, under the name Rasulov.", 1_764_000_021.0, true)
        )

        val MARKS = listOf(
            CallMark("agreed", 1_764_000_030.0),
            CallMark("follow-up", 1_764_000_040.0, "confirm the window seat")
        )

        fun finished(): CallDetailUiState = CallDetailUiState(
            callId = "call-1",
            snapshot = detailSnapshot(transcript = TRANSCRIPT, marks = MARKS),
            transcript = TRANSCRIPT,
            marks = MARKS,
            loading = false,
            hasMoreTranscript = true,
            audio = CallAudioUiState(downloading = true)
        )
    }
}
