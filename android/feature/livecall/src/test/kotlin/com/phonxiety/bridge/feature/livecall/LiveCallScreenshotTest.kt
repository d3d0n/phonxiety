package com.phonxiety.bridge.feature.livecall

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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.designsystem.testing.assertAccessible
import com.phonxiety.bridge.designsystem.testing.pinAnimationFrame
import com.phonxiety.bridge.model.AudioState
import com.phonxiety.bridge.model.CallMark
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.PartialTurn
import com.phonxiety.bridge.model.TranscriptTurn
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The live call on the decision-10 matrix: 411 dp at 100 %, 320 dp at 200 %, light, dark and one
 * dynamic-color case, plus the controls after a scroll and the hang-up confirmation. Every capture
 * runs the accessibility gate first.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LiveCallScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val originalLocale: Locale = Locale.getDefault()
    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    @Before
    fun pinLocaleAndTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocaleAndTimeZone() {
        TimeZone.setDefault(originalTimeZone)
        Locale.setDefault(originalLocale)
    }

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
        composeRule.pinAnimationFrame()
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
        beforeCapture = {
            openControls()
            scrollControls()
        }
    ) { setScreen() }

    @Test
    fun theControlsAtTwoHundredPercent() = render(
        "controls_lower_320_font200_dark",
        beforeCapture = {
            openControls()
            // The marks sit under the transcript at 200 %: one lower control, read at that scale.
            composeRule.onNodeWithText("Follow up").performScrollTo()
            composeRule.waitForIdle()
        }
    ) { setScreen(dark = true, fontScale = 2f, width = 320.dp) }

    @Test
    fun aDroppedLink() = render("reconnecting_411_light") {
        setScreen(state = LiveCallSamples.live.copy(reconnecting = true, partials = emptyList()))
    }

    @Test
    fun theEndedCallWithItsResult() = render("ended_411_light") { setScreen(state = LiveCallSamples.ended) }

    @Test
    fun anEndedCallNeverFoldsItsResultAway() {
        setScreen(state = LiveCallSamples.ended)
        // The result is why the screen is open once the call is over: no disclosure stands over it.
        composeRule.onNodeWithText("Audio and controls").assertDoesNotExist()
        composeRule
            .onNodeWithText("Table for two confirmed for Thursday at 21:00 under Rasulov.", substring = true)
            .assertExists()
    }

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
        openControls()
        composeRule
            .onNodeWithText("Playback audio this phone discarded on the current audio stream")
            .assertDoesNotExist()
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
        composeRule.pinAnimationFrame()
        composeRule.onAllNodes(isDialog()).onLast()
            .captureRoboImage("src/test/screenshots/live_call_hangup_dialog_411_light.png")
    }

    @Test
    fun thePhoneIsDialingWhileTheRecordStillSaysPreparing() = render("preparing_dialing_411_light") {
        // Telecom dials before the daemon's snapshot answers: the header follows the phone.
        setScreen(state = LiveCallSamples.live.copy(state = "preparing", localPhase = CallPhase.DIALING))
    }

    @Test
    fun aDialingPhoneIsNotStillPreparing() {
        setScreen(state = LiveCallSamples.live.copy(state = "preparing", localPhase = CallPhase.ACTIVE))
        composeRule.onNodeWithText("Live").assertExists()
        composeRule.onNodeWithText("Preparing").assertDoesNotExist()
    }

    /**
     * The phone's own hang-up is not a recorded end: the record still says preparing, so the screen
     * keeps its running timer and offers Hang up rather than announcing an end it cannot show.
     */
    @Test
    fun aPhonesHangUpDoesNotEndAStalePreparingRecord() {
        setScreen(
            state = LiveCallSamples.live.copy(
                state = "preparing",
                localPhase = CallPhase.ENDED,
                partials = emptyList()
            )
        )
        composeRule.onNodeWithText("Preparing").assertExists()
        composeRule.onNodeWithText("Ended").assertDoesNotExist()
        composeRule.onNodeWithText("Hang up").assertExists()
        composeRule.onNodeWithText("Open record").assertDoesNotExist()
    }

    @Test
    fun aPreparingCallDoesNotClaimTheAgentIsListening() {
        setScreen(
            state = LiveCallSamples.live.copy(
                state = "preparing",
                localPhase = null,
                activity = null,
                turns = emptyList(),
                partials = emptyList(),
                summary = null
            )
        )
        composeRule.onNodeWithText("Preparing").assertExists()
        composeRule.onNodeWithText("Waiting for the first turn").assertExists()
        composeRule.onNodeWithText("Agent listening").assertDoesNotExist()
    }

    @Test
    fun theUnconfirmedEndExplainsItself() = render(
        "status_unknown_411_light",
        beforeCapture = { scrollTranscriptTo("Call status unknown") }
    ) { setScreen(state = unknownEndedCapture(UNCONFIRMED_REASON)) }

    @Test
    fun theUnconfirmedEndInDark() = render(
        "status_unknown_411_dark",
        beforeCapture = { scrollTranscriptTo("Call status unknown") }
    ) { setScreen(state = unknownEndedCapture(UNCONFIRMED_REASON), dark = true) }

    @Test
    fun theUnconfirmedEndAtTwoHundredPercent() = render(
        "status_unknown_320_font200_light",
        beforeCapture = { scrollTranscriptTo("Call status unknown") }
    ) { setScreen(state = unknownEndedCapture(UNCONFIRMED_REASON), fontScale = 2f, width = 320.dp) }

    @Test
    fun theUnconfirmedEndExpandedAtTwoHundredPercent() = render(
        "status_unknown_expanded_320_font200_light",
        beforeCapture = {
            scrollTranscriptTo("Call status unknown")
            composeRule.onNodeWithText("Show details").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(UNCONFIRMED_REASON).performScrollTo()
            composeRule.waitForIdle()
        }
    ) { setScreen(state = unknownEndedCapture(UNCONFIRMED_REASON), fontScale = 2f, width = 320.dp) }

    @Test
    fun anUnconfirmedEndSaysWhyAndKeepsItsEvidence() {
        setScreen(state = unknownEnded(UNCONFIRMED_REASON))
        composeRule.onNodeWithText("Call status unknown").assertExists()
        composeRule.onNodeWithText("The final call status could not be confirmed.").assertExists()
        // A refused acknowledgement leaves the phone connected: the card never claims a lost link.
        composeRule
            .onNodeWithText("The connection to the computer was lost.", substring = true)
            .assertDoesNotExist()
        // The call is never called over: no screen word says the end was recorded.
        composeRule.onNodeWithText("Ended", substring = false).assertDoesNotExist()
        composeRule.onNodeWithText("Refresh status").assertExists()
        // The daemon's own reason stays behind Details, verbatim.
        composeRule.onNodeWithText(UNCONFIRMED_REASON).assertDoesNotExist()
        composeRule.onNodeWithText("Show details").performClick()
        composeRule.onNodeWithText(UNCONFIRMED_REASON).assertExists()
    }

    @Test
    fun onlyTheDaemonsLinkLossNamesALostConnection() {
        setScreen(state = unknownEnded(LINK_LOST_ERROR))
        composeRule
            .onNodeWithText("The connection to the computer was lost. The final call status could not be confirmed.")
            .assertExists()
        // The raw code stays evidence, behind Details.
        composeRule.onNodeWithText(LINK_LOST_ERROR).assertDoesNotExist()
        composeRule.onNodeWithText("Show details").performClick()
        composeRule.onNodeWithText(LINK_LOST_ERROR).assertExists()
    }

    /**
     * The card is content, not a slab above it: at 320 dp and 200 % the transcript still scrolls,
     * Details still expands, and the pinned action never needs the scroll.
     */
    @Test
    fun theUnknownCardKeepsTheTranscriptAndThePinnedActionReachable() {
        setScreen(state = unknownEnded(UNCONFIRMED_REASON), fontScale = 2f, width = 320.dp)
        // The pinned action sits outside the transcript's scroll, so no scroll hides it.
        val screen = composeRule.onNodeWithTag(SCREEN_TAG).getUnclippedBoundsInRoot()
        val action = composeRule.onNodeWithText("Open the call record").getUnclippedBoundsInRoot()
        assertTrue("the pinned action starts inside the screen", action.top < screen.bottom)
        scrollTranscriptTo("Call status unknown")
        composeRule.onNodeWithText("Call status unknown").assertIsDisplayed()
        scrollTranscriptTo("Transcript")
        composeRule.onNodeWithText("Transcript").assertIsDisplayed()
        scrollTranscriptTo("Call status unknown")
        composeRule.onNodeWithText("Show details").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(UNCONFIRMED_REASON).assertExists()
        composeRule.onNodeWithText("Open the call record").assertExists()
    }

    /** The captures keep the real transcript, so the scroll that reaches the card is proven. */
    private fun unknownEndedCapture(reason: String): LiveCallUiState = LiveCallSamples.ended.copy(state = "unknown", statusUnknownError = reason)

    /**
     * The unconfirmed card without turns: the list opens on the card instead of the newest turn,
     * so a wording assertion does not need a scroll.
     */
    private fun unknownEnded(reason: String): LiveCallUiState = LiveCallSamples.ended.copy(state = "unknown", statusUnknownError = reason, turns = emptyList())

    /** The transcript composes by viewport: reach an item that is not on screen. */
    private fun scrollTranscriptTo(text: String) {
        composeRule.onNodeWithTag(TRANSCRIPT_LIST_TAG).performScrollToNode(hasText(text))
        composeRule.waitForIdle()
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
        openControls()
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
        openControls()
        composeRule.onNodeWithContentDescription("Send instruction").performScrollTo().performClick()
        assertEquals(1, sends)
    }

    /**
     * The compact layout's own promise: with the secondary controls folded away, a real turn stays on screen at
     * 320 dp and 200 % text, and the controls it hides are one row away.
     */
    @Test
    fun theTranscriptKeepsAReadableViewportAtTwoHundredPercent() {
        setScreen(fontScale = 2f, width = 320.dp)
        composeRule.onNodeWithText("Let me check the…", substring = true).assertIsDisplayed()
        // Folded, the audio card is not composed at all.
        composeRule.onNodeWithText("Playback drops").assertDoesNotExist()
        composeRule.onNodeWithText("Audio and controls").assertIsDisplayed()
        openControls()
        composeRule.onNodeWithText("Playback drops").assertExists()
        composeRule.onNodeWithText("Audio and controls").assertIsDisplayed()
    }

    @Test
    fun theControlsDisclosureSaysWhichWayItGoes() {
        setScreen()
        composeRule.onNodeWithText("Audio and controls").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Show controls")
        )
        openControls()
        composeRule.onNodeWithText("Audio and controls").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Hide controls")
        )
    }

    private fun openControls() {
        composeRule.onNodeWithText("Audio and controls").performClick()
        composeRule.waitForIdle()
    }

    /** The controls' own scroll container: reach its last card, not a screen-height swipe. */
    private fun scrollControls() {
        composeRule.onNodeWithText("Show keypad").performScrollTo()
        composeRule.waitForIdle()
    }

    /**
     * A hang-up the daemon refuses leaves the call running: its failure may never be folded away
     * behind the same disclosure that holds the retry.
     */
    @Test
    fun aFailedHangUpStaysVisibleWhileControlsAreFolded() {
        val state = mutableStateOf(LiveCallSamples.live)
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(
                    modifier = Modifier
                        .testTag(SCREEN_TAG)
                        .width(411.dp)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    LiveCallScreen(
                        state = state.value,
                        onBack = {},
                        onInstructionChange = {},
                        onSendInstruction = {},
                        onTogglePause = {},
                        onSummarize = {},
                        onDtmf = {},
                        onMark = {},
                        onHangUp = {
                            state.value = state.value.copy(
                                hangingUp = false,
                                error = UiText.Literal("the bridge service is not running"),
                                errorCode = "link_down"
                            )
                        },
                        onReload = {},
                        onOpenDetail = {},
                        nowEpochMs = NOW
                    )
                }
            }
        }
        composeRule.onNodeWithText("Hang up").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onAllNodesWithText("Hang up").onLast().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        // Folded controls by default, and the failure is still on screen.
        composeRule.onNodeWithText("the bridge service is not running").assertIsDisplayed()
        composeRule.onNodeWithText("Audio and controls").assertIsDisplayed()
    }

    private companion object {
        const val SCREEN_TAG = "live_call_capture"
        const val NOW = 1_764_000_074_000L
    }
}

/** A call that has been going for a minute, with all three surfaces of the screen occupied. */
private const val UNCONFIRMED_REASON = "hangup_unconfirmed: the phone did not answer"

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
