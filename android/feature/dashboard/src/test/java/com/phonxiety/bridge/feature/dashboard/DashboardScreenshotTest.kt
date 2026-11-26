package com.phonxiety.bridge.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.testing.assertAccessible
import com.phonxiety.bridge.designsystem.testing.pinAnimationFrame
import com.phonxiety.bridge.model.AudioState
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.LinkError
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import java.util.Locale
import java.util.TimeZone
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
 * The dashboard across the states that must never look alike: the overview at 411 dp light and dark
 * and at 320 dp with 200 % text, the in-call, offline, unpaired and no-stream states at 411 dp
 * light, the in-call, unpaired, lower and full-endpoint states at 320 dp with 200 % text, and the
 * unpair dialog. Every capture runs the accessibility gate first.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DashboardScreenshotTest {
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
        bridge: BridgeState = DashboardSamples.direct,
        paired: Boolean = true,
        dark: Boolean = false,
        fontScale: Float = 1f,
        width: Dp = 411.dp,
        nowEpochMs: Long = DashboardSamples.now,
        onOpenLiveCall: (String) -> Unit = {},
        onNewCall: () -> Unit = {},
        onRunLinkTest: () -> Unit = {},
        onUnpair: () -> Unit = {}
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale)
            ) {
                PhonxietyTheme(darkTheme = dark, dynamicColor = false) {
                    Box(
                        modifier = Modifier
                            .testTag(SCREEN_TAG)
                            .width(width)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        DashboardScreen(
                            state = DashboardUiState(bridge = bridge, paired = paired),
                            onOpenSetup = {},
                            onOpenDoctor = {},
                            onNewCall = onNewCall,
                            onOpenLiveCall = onOpenLiveCall,
                            onRunLinkTest = onRunLinkTest,
                            onOpenUpdates = {},
                            onUnpair = onUnpair,
                            nowEpochMs = nowEpochMs
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
        composeRule.onNodeWithTag(SCREEN_TAG).captureRoboImage("src/test/screenshots/dashboard_$name.png")
        composeRule.onNodeWithTag(SCREEN_TAG).assertAccessible()
    }

    @Test
    fun widePhoneInLight() = render("411_light") { setScreen() }

    @Test
    fun widePhoneInDark() = render("411_dark") { setScreen(dark = true) }

    @Test
    fun smallScreenAtTwoHundredPercentInLight() = render("320_font200_light") { setScreen(fontScale = 2f, width = 320.dp) }

    @Test
    fun aCallInProgressAtWideWidth() = render("relay_in_call_411_light") { setScreen(bridge = DashboardSamples.relayInCall) }

    @Test
    fun aCallInProgressAtTwoHundredPercent() = render("relay_in_call_320_font200_light") {
        setScreen(bridge = DashboardSamples.relayInCall, fontScale = 2f, width = 320.dp)
    }

    @Test
    fun theLinkDropped() = render("offline_411_light") {
        setScreen(
            bridge = DashboardSamples.offline.copy(
                lastError = LinkError(
                    code = "heartbeat_missed",
                    message = "The daemon stopped answering five heartbeats in a row",
                    atEpochMs = DashboardSamples.now - 40_000
                )
            )
        )
    }

    @Test
    fun anUnpairedPhone() = render("unpaired_411_light") { setScreen(bridge = BridgeState(), paired = false) }

    @Test
    fun anUnpairedPhoneAtTwoHundredPercent() = render("unpaired_320_font200_light") {
        setScreen(bridge = BridgeState(), paired = false, fontScale = 2f, width = 320.dp)
    }

    @Test
    fun noAudioStreamYet() = render("no_stream_411_light") {
        setScreen(bridge = DashboardSamples.direct.copy(audio = AudioState()))
    }

    @Test
    fun theLowerCardsAfterScrolling() = render(
        "lower_411_light",
        beforeCapture = { composeRule.onNodeWithTag(SCREEN_TAG).performTouchInput { swipeUp() } }
    ) { setScreen() }

    @Test
    fun theLowerCardsAtTwoHundredPercent() = render(
        "lower_320_font200_light",
        beforeCapture = {
            composeRule.onNodeWithTag(SCREEN_TAG).performTouchInput { swipeUp() }
            composeRule.onNodeWithTag(SCREEN_TAG).performTouchInput { swipeUp() }
        }
    ) { setScreen(fontScale = 2f, width = 320.dp) }

    @Test
    fun theEndpointShowsAShortFormAndTheWholeKeyOnRequest() {
        setScreen()
        // The row is readable: eight characters, an ellipsis, the last four.
        composeRule.onNodeWithText("864b90b7…cc62").assertExists()
        composeRule.onNodeWithText(DashboardSamples.ENDPOINT_ID).assertDoesNotExist()

        composeRule.onNodeWithText("Show full ID").performScrollTo().performClick()
        composeRule.waitForIdle()
        // The whole key is text the reader can select and copy.
        composeRule.onNodeWithText(DashboardSamples.ENDPOINT_ID).assertExists()
        composeRule.onNodeWithText("Hide full ID").assertExists()
        composeRule.onNodeWithText("Show full ID").assertDoesNotExist()
    }

    /**
     * The whole endpoint after its disclosure is opened, across the decision-10 matrix. The key is
     * scrolled into the viewport, so a 200 % image shows the expanded id rather than the top card.
     */
    private fun captureWholeEndpoint(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        width: Dp = 411.dp
    ) {
        setScreen(dark = dark, fontScale = fontScale, width = width)
        // A semantics click starts no ripple, so the capture does not depend on animation timing.
        composeRule.onNodeWithText("Show full ID").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        // A key that fills several lines must be on screen, and still be one legible block.
        composeRule.onNodeWithText(DashboardSamples.ENDPOINT_ID).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SCREEN_TAG).assertAccessible()
        composeRule.pinAnimationFrame()
        composeRule.onNodeWithTag(SCREEN_TAG)
            .captureRoboImage("src/test/screenshots/dashboard_$name.png")
    }

    @Test
    fun theWholeEndpointAfterTheDisclosureIsOpened() = captureWholeEndpoint("endpoint_full_411_light")

    @Test
    fun theWholeEndpointAtTwoHundredPercentInLight() = captureWholeEndpoint("endpoint_full_320_font200_light", fontScale = 2f, width = 320.dp)

    @Test
    fun theTransportIsSpelledTheSameWayEverywhere() {
        setScreen()
        // The project and the library style it lowercase, like a stylized proper noun.
        composeRule.onNodeWithText("iroh").assertExists()
        composeRule.onNodeWithText("Iroh").assertDoesNotExist()
    }

    @Test
    fun theUnpairConfirmation() {
        setScreen()
        openUnpairDialog()
        composeRule.onNodeWithTag(SCREEN_TAG).assertAccessible()
        // The dialog lives in its own window: it is gated and captured on its own root.
        composeRule.onAllNodes(isDialog()).onLast().assertAccessible()
        composeRule.pinAnimationFrame()
        composeRule.onAllNodes(isDialog()).onLast()
            .captureRoboImage("src/test/screenshots/dashboard_unpair_dialog_411_light.png")
    }

    @Test
    fun anUnmeasuredPathReadsUnknown() {
        setScreen(bridge = DashboardSamples.direct.copy(link = UNKNOWN_PATH))
        composeRule.onNodeWithText("Unknown").assertExists()
        composeRule.onNodeWithText("Relay").assertDoesNotExist()
        composeRule.onNodeWithText("Direct").assertDoesNotExist()
    }

    @Test
    fun playbackDropsAreMillisecondsOfTheCurrentStream() {
        setScreen(bridge = DashboardSamples.relayInCall)
        // 43 200 samples at 48 kHz is 900 ms of discarded playback audio.
        composeRule.onNode(hasText("900", substring = true)).assertExists()
        composeRule
            .onNodeWithText("Playback audio this phone discarded on the current audio stream")
            .assertExists()
    }

    @Test
    fun bitrateIsConvertedToKilobits() {
        // The engine publishes bits per second; the tile reads 32 kbit/s, never 32000 kHz.
        setScreen(bridge = DashboardSamples.direct)
        composeRule.onNodeWithText("32\u00A0kbit/s").assertExists()
        composeRule.onNodeWithText("32000", substring = true).assertDoesNotExist()
    }

    @Test
    fun theLinkCardKeepsTheMeasuredPathWhileTheHeaderShowsThePhase() {
        setScreen(bridge = DashboardSamples.relayInCall)
        // The header wears the phase, so the path is this card's, once.
        composeRule.onNodeWithText("Live").assertExists()
        composeRule.onAllNodesWithText("Relay").assertCountEquals(1)
    }

    @Test
    fun anIdleDashboardNamesThePathOnceInTheHeader() {
        setScreen()
        composeRule.onAllNodesWithText("Direct").assertCountEquals(1)
    }

    @Test
    fun noAudioStreamShowsNoFabricatedZeros() {
        setScreen(bridge = DashboardSamples.direct.copy(audio = AudioState()))
        composeRule.onNodeWithText("No audio stream. It starts with a call.").assertExists()
        composeRule.onNodeWithText("Playback drops").assertDoesNotExist()
    }

    @Test
    fun theCallCardCountsFromTheConnectNotTheRing() {
        setScreen(bridge = DashboardSamples.relayInCall)
        // 74 s of ring is not the call: the tile is the 40 s since the answer, like the system chip.
        composeRule.onNodeWithText("Duration").assertExists()
        composeRule.onNodeWithText("00:40").assertExists()
        // 74 s would read as 01:14, so the ring is never shown as this tile's duration.
        composeRule.onNodeWithText("01:14").assertDoesNotExist()
    }

    @Test
    fun theRingIsDialingAndNotADuration() {
        setScreen(bridge = DashboardSamples.dialing)
        // The header pill and the tile both name the phase; neither calls the ring a duration.
        composeRule.onAllNodesWithText("Dialing").assertCountEquals(2)
        composeRule.onNodeWithText("00:09").assertExists()
        composeRule.onNodeWithText("Duration").assertDoesNotExist()
        composeRule.onNodeWithText("Connected at").assertExists()
    }

    @Test
    fun anEndedCallKeepsTheDurationItEndedWith() {
        // The sample answered 40 s before the fixed clock and the call ended 10 s later.
        val ended = DashboardSamples.relayInCall.call!!.copy(
            phase = CallPhase.ENDED,
            endedAtEpochMs = DashboardSamples.now - 10_000
        )
        // Five minutes later the tile still reads the 30 s the conversation lasted.
        setScreen(
            bridge = DashboardSamples.relayInCall.copy(call = ended),
            nowEpochMs = DashboardSamples.now + 5 * 60_000
        )
        composeRule.onNodeWithText("Ended").assertExists()
        composeRule.onNodeWithText("Duration").assertExists()
        composeRule.onNodeWithText("00:30").assertExists()
        composeRule.onNodeWithText("05:30").assertDoesNotExist()
    }

    @Test
    fun aCallInProgressOffersOneWayInAndNoSecondCall() {
        setScreen(bridge = DashboardSamples.relayInCall)
        composeRule.onNodeWithText("Open live call").assertExists()
        composeRule.onNodeWithText("New call").assertDoesNotExist()
        composeRule.onNodeWithText("A call is running. Open it above to end it before starting another.")
            .assertExists()
    }

    @Test
    fun openingTheLiveCallReportsTheCallsOwnId() {
        var opened: String? = null
        setScreen(bridge = DashboardSamples.relayInCall, onOpenLiveCall = { opened = it })
        composeRule.onNodeWithText("Open live call").performClick()
        assertEquals("preview", opened)
    }

    @Test
    fun aNewCallIsThePrimaryActionWhenNothingRuns() {
        var newCalls = 0
        setScreen(onNewCall = { newCalls++ })
        composeRule.onNodeWithText("New call").performClick()
        assertEquals(1, newCalls)
    }

    @Test
    fun unpairingAsksFirstAndCancelingChangesNothing() {
        var unpairs = 0
        setScreen(onUnpair = { unpairs++ })
        openUnpairDialog()
        composeRule.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertEquals(0, unpairs)

        openUnpairDialog()
        composeRule.onAllNodesWithText("Unpair").onLast().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertEquals(1, unpairs)
    }

    @Test
    fun theClockIsReadAgainEverySecond() {
        var fake = 1_000L
        var seen = 0L
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val now by tickingNow(clock = { fake })
            seen = now
        }
        composeRule.mainClock.advanceTimeBy(1_500)
        assertEquals(1_000L, seen)

        fake = 2_500L
        composeRule.mainClock.advanceTimeBy(1_100)
        assertEquals(2_500L, seen)
    }

    private fun openUnpairDialog() {
        // A semantics click starts no ripple, so the capture does not depend on animation timing.
        composeRule.onNodeWithText("Unpair").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    private companion object {
        const val SCREEN_TAG = "dashboard_capture"

        val UNKNOWN_PATH = (DashboardSamples.direct.link as LinkState.Up).copy(
            path = PathKind.UNKNOWN,
            rttMs = null
        )
    }
}
