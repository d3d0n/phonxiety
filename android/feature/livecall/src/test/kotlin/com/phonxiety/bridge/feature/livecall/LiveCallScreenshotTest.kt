package com.phonxiety.bridge.feature.livecall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.model.AudioState
import com.phonxiety.bridge.model.CallMark
import com.phonxiety.bridge.model.PartialTurn
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
 * The live call on the decision-10 matrix: 411 dp at 100 %, 320 dp at 200 %, light, dark and one
 * dynamic-colour case, plus the controls after a scroll and the hang-up confirmation. Every capture
 * runs the accessibility gate first.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LiveCallScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setScreen(
        state: LiveCallUiState = LiveCallSamples.live,
        dark: Boolean = false,
        dynamicColor: Boolean = false,
        fontScale: Float = 1f,
        width: Dp = 411.dp,
        onHangUp: () -> Unit = {},
        onSendInstruction: () -> Unit = {}
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
                        LiveCallScreen(
                            state = state,
                            onBack = {},
                            onInstructionChange = {},
                            onSendInstruction = onSendInstruction,
                            onTogglePause = {},
                            onSummarize = {},
                            onDtmf = {},
                            onMark = {},
                            onHangUp = onHangUp,
                            onReload = {},
                            onOpenDetail = {},
                            nowEpochMs = NOW
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
        composeRule.onNodeWithTag(SCREEN_TAG).captureRoboImage("src/test/screenshots/live_call_$name.png")
        composeRule.onNodeWithTag(SCREEN_TAG).assertAccessible()
    }

    @Test
    fun widePhoneInLight() = render("411_light") { setScreen() }

    @Test
    fun widePhoneInDark() = render("411_dark") { setScreen(dark = true) }

    @Test
    fun widePhoneInDynamicColour() = render("411_dynamic") { setScreen(dynamicColor = true) }

    @Test
    fun smallScreenAtTwoHundredPercentInLight() = render("320_font200_light") { setScreen(fontScale = 2f, width = 320.dp) }

    @Test
    fun smallScreenAtTwoHundredPercentInDark() = render("320_font200_dark") { setScreen(dark = true, fontScale = 2f, width = 320.dp) }

    @Test
    fun theControlsAfterScrolling() = render(
        "controls_lower_411_light",
        beforeCapture = { scrollControls() }
    ) { setScreen() }

    @Test
    fun theControlsAtTwoHundredPercent() = render(
        "controls_lower_320_font200_dark",
        beforeCapture = { scrollControls() }
    ) { setScreen(dark = true, fontScale = 2f, width = 320.dp) }

    @Test
    fun aDroppedLink() = render("reconnecting_411_light") {
        setScreen(state = LiveCallSamples.live.copy(reconnecting = true, partials = emptyList()))
    }

    @Test
    fun theEndedCallWithItsResult() = render("ended_411_light") { setScreen(state = LiveCallSamples.ended) }

    @Test
    fun aPausedAgentWithADraft() = render("paused_411_light") {
        setScreen(
            state = LiveCallSamples.live.copy(
                muted = true,
                instruction = "ask for Thursday",
                activity = "waiting"
            )
        )
    }

    @Test
    fun noAudioStreamShowsNoFabricatedZeros() = render("no_stream_411_dark") {
        setScreen(state = LiveCallSamples.live.copy(audio = AudioState()), dark = true)
    }

    @Test
    fun anAbsentStreamHasNoDropsFootnote() {
        setScreen(state = LiveCallSamples.live.copy(audio = AudioState()))
        composeRule.onNodeWithText("Playback audio this phone discarded on this call").assertDoesNotExist()
        composeRule.onNodeWithText("Playback drops").assertExists()
    }

    @Test
    fun theHangUpConfirmation() {
        setScreen()
        // A semantics click starts no ripple, so the capture does not depend on animation timing.
        composeRule.onNodeWithText("Hang up").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SCREEN_TAG).assertAccessible()
        composeRule.onAllNodes(isDialog()).onLast().assertAccessible()
        composeRule.onAllNodes(isDialog()).onLast()
            .captureRoboImage("src/test/screenshots/live_call_hangup_dialog_411_light.png")
    }

    @Test
    fun aDroppedLinkNeverReadsAsALiveCall() {
        setScreen(state = LiveCallSamples.live.copy(reconnecting = true))
        composeRule.onNodeWithText("Reconnecting").assertExists()
        composeRule.onNodeWithText("Live").assertDoesNotExist()
        composeRule.onNodeWithText("Agent listening").assertDoesNotExist()
    }

    @Test
    fun theInstructionFieldExplainsWhySendIsOff() {
        setScreen(state = LiveCallSamples.live.copy(instruction = ""))
        composeRule.onNodeWithText("Type an instruction, then send.").assertExists()
    }

    @Test
    fun hangUpAsksFirstAndDismissingChangesNothing() {
        var hangUps = 0
        setScreen(onHangUp = { hangUps++ })
        composeRule.onNodeWithText("Hang up").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Keep talking").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertEquals(0, hangUps)

        composeRule.onNodeWithText("Hang up").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onAllNodesWithText("Hang up").onLast().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertEquals(1, hangUps)
    }

    @Test
    fun sendingTheInstructionCallsTheAgent() {
        var sends = 0
        setScreen(state = LiveCallSamples.live.copy(instruction = "ask for Thursday"), onSendInstruction = { sends++ })
        composeRule.onNodeWithContentDescription("Send instruction").performClick()
        assertEquals(1, sends)
    }

    private fun scrollControls() {
        // The bottom half holds the scrolling control column on a phone.
        composeRule.onNodeWithTag(SCREEN_TAG).performTouchInput {
            swipeUp(startY = height * 0.9f, endY = height * 0.55f)
        }
        composeRule.onNodeWithTag(SCREEN_TAG).performTouchInput {
            swipeUp(startY = height * 0.9f, endY = height * 0.55f)
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val SCREEN_TAG = "live_call_capture"
        const val NOW = 1_764_000_074_000L
    }
}

/** A call that has been going for a minute, with all three surfaces of the screen occupied. */
object LiveCallSamples {
    val live = LiveCallUiState(
        callId = "call-2026-11-26",
        loaded = true,
        destination = "+998901234567",
        farEnd = "Bella Napoli",
        state = "active",
        activity = "idle",
        turns = listOf(
            TranscriptTurn(1, "assistant", "Good evening, I would like to book a table for two on Thursday at eight.", 1_764_000_006.0, true),
            TranscriptTurn(2, "user", "Thursday at eight is full. We have seven or nine.", 1_764_000_014.0, true),
            TranscriptTurn(3, "assistant", "Nine would work. Two people, under the name Rasulov.", 1_764_000_021.0, true),
            TranscriptTurn(4, "user", "Nine it is. Anything else?", 1_764_000_031.0, true),
            TranscriptTurn(5, "assistant", "One question: is there a window seat?", 1_764_000_040.0, true)
        ),
        partials = listOf(PartialTurn("user", "Let me check the", null)),
        marks = listOf(CallMark("agreed", 1_764_000_041.0)),
        summary = "Table for two confirmed for Thursday at 21:00 under Rasulov.",
        audioCodec = "opus",
        connectedAtMs = 1_764_000_005_000L,
        createdAtMs = 1_764_000_000_000L,
        audio = AudioState(
            codec = "opus",
            bitrate = 32_000,
            rxLevelDbfs = -18f,
            txLevelDbfs = -26f,
            framesOut = 2_400,
            framesIn = 2_398,
            drops = 0
        )
    )

    val ended = live.copy(
        state = "ended",
        callOutcome = "answered",
        resultStatus = "ready",
        ended = true,
        endedAtMs = 1_764_000_052_000L,
        activity = "idle",
        partials = emptyList(),
        muted = false,
        summaryFields = JsonObject(
            mapOf(
                "reservation_time" to JsonPrimitive("Thursday 21:00"),
                "party_size" to JsonPrimitive("2"),
                "window_seat" to JsonPrimitive("requested")
            )
        ),
        marks = listOf(CallMark("agreed", 1_764_000_041.0), CallMark("follow-up", 1_764_000_050.0, "confirm the window seat"))
    )
}
