package com.phonxiety.bridge.feature.lab

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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.designsystem.testing.Screenshot
import com.phonxiety.bridge.designsystem.testing.assertAccessible
import com.phonxiety.bridge.designsystem.testing.pinAnimationFrame
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The lab: the overview at 411 dp light and dark and at 320 dp with 200 % text, the audio controls,
 * the lower sections and the test-call dialog at 411 dp light, and the test-call result at 200 %
 * text. Every capture runs the accessibility gate first.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LabScreenScreenshotTest {
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
        state: LabUiState,
        dark: Boolean = false,
        fontScale: Float = 1f,
        width: Dp = 411.dp,
        callbacks: LabCallbacks = LabCallbacks()
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale),
                // The samples are from 2026-11-26: render them as "today" on any test date.
                LocalLabClock provides { FIXED_NOW_MILLIS }
            ) {
                PhonxietyTheme(darkTheme = dark, dynamicColor = false) {
                    Box(
                        modifier = Modifier
                            .testTag(SCREEN_TAG)
                            .width(width)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        LabScreen(state = state, callbacks = callbacks)
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
        composeRule.onNodeWithTag(SCREEN_TAG).captureRoboImage("src/test/screenshots/lab_$name.png")
        composeRule.onNodeWithTag(SCREEN_TAG).assertAccessible()
    }

    @Test
    @Category(Screenshot::class)
    fun widePhoneInLight() = render("411_light") { setScreen(previewLabState()) }

    @Test
    @Category(Screenshot::class)
    fun widePhoneInDark() = render("411_dark") { setScreen(previewLabState(), dark = true) }

    @Test
    @Category(Screenshot::class)
    fun smallScreenAtTwoHundredPercentInLight() = render("320_font200_light") {
        setScreen(previewLabState(), fontScale = 2f, width = 320.dp)
    }

    @Test
    @Category(Screenshot::class)
    fun audioOutsideACall() = render(
        "audio_idle_411_light",
        beforeCapture = {
            composeRule
                .onNodeWithText("Start a call first. Loopback and the tone run inside a call you own.")
                .performScrollTo()
            composeRule.waitForIdle()
        }
    ) {
        setScreen(
            LabUiState(
                link = previewLabState().link,
                testCall = previewLabState().testCall,
                loopbackSupported = false,
                toneSupported = true
            )
        )
    }

    @Test
    @Category(Screenshot::class)
    fun theToneControlsInsideACall() = render(
        "audio_live_411_light",
        beforeCapture = {
            composeRule.onNodeWithContentDescription("Tone level").performScrollTo()
            composeRule.waitForIdle()
        }
    ) { setScreen(previewLabState()) }

    @Test
    @Category(Screenshot::class)
    fun theLowerSections() = render(
        "lower_411_light",
        beforeCapture = {
            composeRule.onNodeWithText("App log").performScrollTo()
            composeRule.waitForIdle()
        }
    ) { setScreen(previewLabState()) }

    @Test
    @Category(Screenshot::class)
    fun theLowerSectionsAtTwoHundredPercentInLight() = render(
        "lower_320_font200_light",
        beforeCapture = {
            composeRule.onNodeWithText("App log").performScrollTo()
            composeRule.waitForIdle()
        }
    ) { setScreen(previewLabState(), fontScale = 2f, width = 320.dp) }

    @Test
    fun theLabChipsWrapInsteadOfRunningOffTheEdge() {
        setScreen(previewLabState(), fontScale = 2f, width = 320.dp)
        // A chip cut at the screen edge reads as a broken control; every one of them fits.
        for (label in listOf("PCM", "Datagram")) {
            val bounds = composeRule.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue("$label stays inside the screen", bounds.right.value <= 320f)
        }
    }

    @Test
    fun theLatencyTileStackOnePerRowAtTwoHundredPercent() {
        setScreen(previewLabState(), fontScale = 2f, width = 320.dp)
        val labels = listOf("Median", "p95", "Worst", "Loss")
        val lefts = labels.map { composeRule.onNodeWithText(it).getUnclippedBoundsInRoot().left.value }
        assertEquals("every tile starts at the content edge", 1, lefts.map { Math.round(it) }.distinct().size)
        val tops = labels.map { composeRule.onNodeWithText(it).getUnclippedBoundsInRoot().top.value }
        for (index in 1 until tops.size) {
            assertTrue("${labels[index]} sits under ${labels[index - 1]}", tops[index] > tops[index - 1])
        }
        val caption = composeRule.onNodeWithText("19 of 20 frames arrived faster").getUnclippedBoundsInRoot()
        // A half-width column would leave this caption about 120 dp; stacked it has the card's width.
        assertTrue("the caption keeps a readable column", caption.right.value - caption.left.value >= 240f)
    }

    @Test
    fun theLatencyTilesKeepTwoPerRowAtNormalText() {
        setScreen(previewLabState())
        val median = composeRule.onNodeWithText("Median").getUnclippedBoundsInRoot()
        val p95 = composeRule.onNodeWithText("p95").getUnclippedBoundsInRoot()
        val worst = composeRule.onNodeWithText("Worst").getUnclippedBoundsInRoot()
        val loss = composeRule.onNodeWithText("Loss").getUnclippedBoundsInRoot()
        assertEquals("the first pair shares a row", median.top.value, p95.top.value, 1f)
        assertEquals("the second pair shares a row", worst.top.value, loss.top.value, 1f)
        assertTrue("p95 sits right of the median", p95.left.value > median.left.value)
        assertTrue("the second pair starts under the first", worst.top.value > median.top.value)
    }

    @Test
    @Category(Screenshot::class)
    fun theTestCallConfirmation() {
        setScreen(previewLabState().copy(testCall = previewLabState().testCall.copy(confirming = true)))
        // Let the dialog's enter animation finish: a half-faded scrim is a different image.
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SCREEN_TAG).assertAccessible()
        // The dialog is captured on its own node: the scrim behind it animates, the dialog does not.
        composeRule.onAllNodes(isDialog()).onLast().assertAccessible()
        composeRule.pinAnimationFrame()
        composeRule.onAllNodes(isDialog()).onLast()
            .captureRoboImage("src/test/screenshots/lab_dialog_411_light.png")
    }

    /** A finished test call: the probes were asked for and the bridge has the summary. */
    private fun finishedTestCall(
        result: TestCallResultUi = testCallResult()
    ): LabUiState {
        val base = previewLabState()
        return base.copy(
            testCall = base.testCall.copy(
                probesSupported = true,
                loopback = true,
                tone = true,
                toneSeconds = 5,
                callId = "call-1",
                result = result
            )
        )
    }

    private fun testCallResult(
        resultStatus: String? = "ready",
        summary: String? = "The far end answered, heard the tone and said the line was clear. They asked to be called again tomorrow morning.",
        waiting: Boolean = false
    ): TestCallResultUi = TestCallResultUi(
        callId = "call-1",
        outcome = "answered",
        resultStatus = resultStatus,
        summary = summary,
        durationSeconds = 34,
        codec = "opus",
        waiting = waiting
    )

    @Test
    @Category(Screenshot::class)
    fun theTestCallProbesAndTheirResult() = render(
        "test_call_result_411_light",
        beforeCapture = {
            composeRule.onNodeWithText("Open the call record").performScrollTo()
            composeRule.waitForIdle()
        }
    ) { setScreen(finishedTestCall()) }

    @Test
    fun thePlacementConfirmationSeparatesTheStatusFromTheCallId() {
        setScreen(finishedTestCall())
        composeRule.onNodeWithText("Test call placed").performScrollTo().assertExists()
        composeRule.onNodeWithText("Call ID").assertExists()
        composeRule.onNodeWithText("call-1").assertExists()
        // The status no longer carries the internal id on the same line.
        composeRule.onNodeWithText("Placed", substring = true).assertDoesNotExist()
    }

    @Test
    @Category(Screenshot::class)
    fun theTestCallResultAtTwoHundredPercentInLight() = render(
        "test_call_result_320_font200_light",
        beforeCapture = {
            composeRule.onNodeWithText("Open the call record").performScrollTo()
            composeRule.waitForIdle()
        }
    ) { setScreen(finishedTestCall(), fontScale = 2f, width = 320.dp) }

    @Test
    @Category(Screenshot::class)
    fun theTestCallWhoseSummaryIsStillBeingWritten() = render(
        "test_call_waiting_411_light",
        beforeCapture = {
            composeRule.onNodeWithText("Reading the call record…").performScrollTo()
            composeRule.waitForIdle()
        }
    ) { setScreen(finishedTestCall(testCallResult(resultStatus = "pending", summary = null, waiting = true))) }

    @Test
    fun theProbeTogglesSayWhoHearsThem() {
        setScreen(finishedTestCall())
        composeRule.onNodeWithText("Loop the far end back").performScrollTo()
        composeRule.onNodeWithText("The far end hears what they say, echoed back to them.").assertExists()
        composeRule.onNodeWithText("Play a tone").assertExists()
        composeRule.onNodeWithText("The far end hears the tone, at the frequency and level set above.").assertExists()
    }

    @Test
    fun anOlderDaemonGetsNoProbeToggles() {
        setScreen(previewLabState().copy(testCall = previewLabState().testCall.copy(probesSupported = false)))
        composeRule.onNodeWithText("Loop the far end back").assertDoesNotExist()
        composeRule.onNodeWithText("Play a tone").assertDoesNotExist()
    }

    @Test
    fun eachProbeToggleFollowsItsPhoneCapability() {
        val base = previewLabState()
        setScreen(
            base.copy(
                loopbackSupported = false,
                toneSupported = true,
                testCall = base.testCall.copy(probesSupported = true)
            )
        )
        composeRule.onNodeWithText("Loop the far end back").assertDoesNotExist()
        composeRule.onNodeWithText("Play a tone").performScrollTo().assertExists()
    }

    @Test
    fun aPhoneWithoutAToneIsNotOfferedOne() {
        val base = previewLabState()
        setScreen(
            base.copy(
                loopbackSupported = true,
                toneSupported = false,
                testCall = base.testCall.copy(probesSupported = true)
            )
        )
        composeRule.onNodeWithText("Loop the far end back").performScrollTo().assertExists()
        composeRule.onNodeWithText("Play a tone").assertDoesNotExist()
    }

    /** The automatic tone is configured before dialing, so its settings are usable with no call. */
    @Test
    @Category(Screenshot::class)
    fun theToneSettingsBeforeDialing() = render(
        "audio_tone_setup_411_light",
        beforeCapture = {
            composeRule.onNodeWithContentDescription("Tone level").performScrollTo()
            composeRule.waitForIdle()
        }
    ) {
        val base = previewLabState()
        setScreen(
            LabUiState(
                link = base.link,
                loopbackSupported = true,
                toneSupported = true,
                testCall = base.testCall.copy(probesSupported = true, tone = true)
            )
        )
    }

    @Test
    fun theToneSettingsComeBeforeAnyCallButLivePlaybackDoesNot() {
        val base = previewLabState()
        setScreen(
            LabUiState(
                loopbackSupported = true,
                toneSupported = true,
                testCall = base.testCall.copy(probesSupported = true, tone = true)
            )
        )
        composeRule.onNodeWithContentDescription("Tone frequency").performScrollTo().assertExists()
        composeRule.onNodeWithContentDescription("Tone level").assertExists()
        composeRule.onNodeWithText("Tone for the test call").assertExists()
        // Only the live playback waits for a call.
        composeRule.onNodeWithText("Play tone").assertDoesNotExist()
    }

    @Test
    fun theToneSecondsControlFollowsTheToneToggle() {
        val base = previewLabState()
        val state = mutableStateOf(
            base.copy(testCall = base.testCall.copy(probesSupported = true, tone = false))
        )
        val callbacks = LabCallbacks(
            onTestTone = { on -> state.value = state.value.copy(testCall = state.value.testCall.copy(tone = on)) },
            onTestToneSeconds = { seconds ->
                state.value = state.value.copy(testCall = state.value.testCall.copy(toneSeconds = seconds))
            }
        )
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    LabScreen(state = state.value, callbacks = callbacks)
                }
            }
        }
        composeRule.onNodeWithContentDescription("Tone length").assertDoesNotExist()
        composeRule.onNodeWithText("Play a tone").performScrollTo().performClick()
        composeRule.waitForIdle()
        val length = composeRule.onNodeWithContentDescription("Tone length")
        length.performScrollTo()
        length.assert(hasStateDescription("5\u00A0s"))
        // A screen reader moves the slider through its semantics action, not a drag.
        length.performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        composeRule.waitForIdle()
        assertEquals(3, state.value.testCall.toneSeconds)
        composeRule.onNodeWithText("Tone length · 3\u00A0s").assertExists()
    }

    @Test
    fun theResultCardOpensItsCallRecord() {
        var opened: String? = null
        setScreen(finishedTestCall(), callbacks = LabCallbacks(onOpenCall = { opened = it }))
        composeRule.onNodeWithText("Open the call record").performScrollTo().performClick()
        assertEquals("call-1", opened)
    }

    @Test
    fun aPendingSummaryIsNotAMissingOne() {
        setScreen(finishedTestCall(testCallResult(resultStatus = "pending", summary = null, waiting = false)))
        composeRule.onNodeWithText("The bridge is still writing the summary.").performScrollTo().assertExists()
        composeRule.onNodeWithText("The bridge has no summary for this call.").assertDoesNotExist()
    }

    @Test
    fun aCallWithoutASummarySaysSoAndStillOpens() {
        setScreen(finishedTestCall(testCallResult(resultStatus = null, summary = null, waiting = false)))
        composeRule.onNodeWithText("The bridge has no summary for this call.").performScrollTo().assertExists()
        composeRule.onNodeWithText("Answered").assertExists()
    }

    @Test
    fun aReadyRecordWithoutASummaryIsNotStillPending() {
        setScreen(finishedTestCall(testCallResult(resultStatus = "ready", summary = null, waiting = false)))
        composeRule.onNodeWithText("The bridge has no summary for this call.").performScrollTo().assertExists()
        composeRule.onNodeWithText("The bridge is still writing the summary.").assertDoesNotExist()
    }

    @Test
    fun aCallTheBridgeHasNotClosedSaysSoAndStillOpens() {
        setScreen(
            finishedTestCall(
                testCallResult(resultStatus = null, summary = null).copy(outcome = null, unfinished = true)
            )
        )
        composeRule.onNodeWithText("The bridge hasn't closed this call yet. Open the call record to follow it.")
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithText("The bridge has no summary for this call.").assertDoesNotExist()
        composeRule.onNodeWithText("Open the call record").assertExists()
    }

    @Test
    fun anUnfinishedCallLeavesNoEmptyStatusRowAtTwoHundredPercent() {
        setScreen(
            finishedTestCall(
                testCallResult(resultStatus = null, summary = null).copy(outcome = null, unfinished = true)
            ),
            fontScale = 2f,
            width = 320.dp
        )
        composeRule.onNodeWithText("Open the call record").performScrollTo()
        composeRule.waitForIdle()
        val subtitle = composeRule
            .onNodeWithText("The bridge's own record of the call it placed.")
            .getUnclippedBoundsInRoot()
        val durationLabel = composeRule.onNodeWithText("Duration").getUnclippedBoundsInRoot()
        assertEquals(
            "the content follows the subtitle at the card's own spacing, with no empty status row",
            10f,
            (durationLabel.top - subtitle.bottom).value,
            1f
        )
    }

    @Test
    fun theThreeProbeVerdictsStayDistinct() {
        setScreen(previewLabState())
        composeRule.onAllNodesWithText("Reached").assertCountEquals(1)
        composeRule.onNodeWithText("Not reached").assertExists()
        composeRule.onNodeWithText("Not checked").assertExists()
    }

    @Test
    fun anAbsentLossIsNeverReportedAsZero() {
        val run = previewLabState().link.last!!.copy(loss = null)
        setScreen(
            previewLabState().copy(
                link = previewLabState().link.copy(
                    last = run,
                    history = listOf(run)
                )
            )
        )
        composeRule.onNodeWithText("loss —", substring = true).assertExists()
        composeRule.onNodeWithText("loss 0.0%", substring = true).assertDoesNotExist()
    }

    @Test
    fun theToneSlidersAreNamedAndMoveWithoutDragging() {
        val state = mutableStateOf(
            LabUiState(callPhase = "active", callId = "call-1", loopbackSupported = true, toneSupported = true)
        )
        val callbacks = LabCallbacks(
            onToneHz = { hz -> state.value = state.value.copy(audio = state.value.audio.copy(toneHz = hz)) },
            onToneDbfs = { db -> state.value = state.value.copy(audio = state.value.audio.copy(toneDbfs = db)) }
        )
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    LabScreen(state = state.value, callbacks = callbacks)
                }
            }
        }
        val frequency = composeRule.onNodeWithContentDescription("Tone frequency")
        frequency.performScrollTo()
        frequency.assert(hasStateDescription("${numberText(1_000)}\u00A0Hz"))
        // A screen reader or a keyboard moves the slider through its semantics action, not a drag.
        frequency.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        composeRule.waitForIdle()
        assertEquals(700, state.value.audio.toneHz)
        composeRule.onNodeWithText("Tone frequency · ${numberText(700)}\u00A0Hz").assertExists()

        val level = composeRule.onNodeWithContentDescription("Tone level")
        level.performScrollTo()
        level.assert(hasStateDescription("\u221220\u00A0dBFS"))
        level.performSemanticsAction(SemanticsActions.SetProgress) { it(-40f) }
        composeRule.waitForIdle()
        assertEquals(-40.0, state.value.audio.toneDbfs, 0.001)
    }

    @Test
    fun theLoopbackCopySaysWhoHearsIt() {
        setScreen(previewLabState())
        composeRule.onNodeWithText("Echo the far end back").assertExists()
        composeRule.onNodeWithText("The far end hears their own voice, slightly delayed.").assertExists()
        composeRule.onNodeWithText("Echo the uplink back").assertDoesNotExist()
    }

    @Test
    fun anUnmeasuredPathSaysWhy() {
        setScreen(
            previewLabState().copy(
                probe = ProbeUi(
                    rows = listOf(
                        ProbeRowUi(
                            key = "lan",
                            endpoint = "192.168.1.20:18763",
                            verdict = ProbeVerdict.NOT_CHECKED,
                            note = UiText.Res(R.string.lab_probe_note_unpaired),
                            listenerNote = UiText.Res(R.string.lab_probe_listener_answers)
                        )
                    )
                )
            )
        )
        composeRule.onNodeWithText("Not checked").assertExists()
        composeRule.onNodeWithText("This phone isn't paired, so it can't authenticate a probe.").assertExists()
        composeRule.onNodeWithText("The bridge's own listener answers there.").assertExists()
        composeRule.onNodeWithText("Not reached").assertDoesNotExist()
    }

    @Test
    fun anErrorKeepsTheCodeOutOfItsSentence() {
        setScreen(
            previewLabState().copy(
                link = previewLabState().link.copy(
                    error = LabFailure(UiText.Literal("The bridge didn't answer."), "link_down")
                )
            )
        )
        composeRule.onNodeWithText("The bridge didn't answer.").assertExists()
        composeRule.onNodeWithText("link_down", substring = true).assertDoesNotExist()
    }

    private fun hasStateDescription(value: String) = SemanticsMatcher.expectValue(
        SemanticsProperties.StateDescription,
        value
    )

    private companion object {
        const val SCREEN_TAG = "lab_capture"

        /** Noon on the samples' day, in the zone every capture renders in. */
        val FIXED_NOW_MILLIS: Long = java.time.LocalDateTime.parse("2026-11-26T12:00:00")
            .atOffset(java.time.ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }
}
