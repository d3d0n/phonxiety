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
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The lab on the decision-10 matrix: 411 dp at 100 %, 320 dp at 200 %, light, dark and one
 * dynamic-color case, plus the audio controls, the test-call dialog and the lower sections. Every
 * capture runs the accessibility gate first.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LabScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setScreen(
        state: LabUiState,
        dark: Boolean = false,
        dynamicColor: Boolean = false,
        fontScale: Float = 1f,
        width: Dp = 411.dp,
        callbacks: LabCallbacks = LabCallbacks()
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
        composeRule.onNodeWithTag(SCREEN_TAG).captureRoboImage("src/test/screenshots/lab_$name.png")
        composeRule.onNodeWithTag(SCREEN_TAG).assertAccessible()
    }

    @Test
    fun widePhoneInLight() = render("411_light") { setScreen(previewLabState()) }

    @Test
    fun widePhoneInDark() = render("411_dark") { setScreen(previewLabState(), dark = true) }

    @Test
    fun widePhoneInDynamicColor() = render("411_dynamic") { setScreen(previewLabState(), dynamicColor = true) }

    @Test
    fun smallScreenAtTwoHundredPercentInLight() = render("320_font200_light") {
        setScreen(previewLabState(), fontScale = 2f, width = 320.dp)
    }

    @Test
    fun smallScreenAtTwoHundredPercentInDark() = render("320_font200_dark") {
        setScreen(previewLabState(), dark = true, fontScale = 2f, width = 320.dp)
    }

    @Test
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
    fun theToneControlsInsideACall() = render(
        "audio_live_411_light",
        beforeCapture = {
            composeRule.onNodeWithContentDescription("Tone level").performScrollTo()
            composeRule.waitForIdle()
        }
    ) { setScreen(previewLabState()) }

    @Test
    fun theLowerSections() = render(
        "lower_411_light",
        beforeCapture = {
            composeRule.onNodeWithText("App log").performScrollTo()
            composeRule.waitForIdle()
        }
    ) { setScreen(previewLabState()) }

    @Test
    fun theLowerSectionsAtTwoHundredPercent() = render(
        "lower_320_font200_dark",
        beforeCapture = {
            composeRule.onNodeWithText("App log").performScrollTo()
            composeRule.waitForIdle()
        }
    ) { setScreen(previewLabState(), dark = true, fontScale = 2f, width = 320.dp) }

    @Test
    fun theTestCallConfirmation() {
        setScreen(previewLabState().copy(testCall = previewLabState().testCall.copy(confirming = true)))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SCREEN_TAG).assertAccessible()
        // The dialog is captured on its own node: the scrim behind it animates, the dialog does not.
        composeRule.onAllNodes(isDialog()).onLast().assertAccessible()
        composeRule.onAllNodes(isDialog()).onLast()
            .captureRoboImage("src/test/screenshots/lab_dialog_411_light.png")
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
    }
}
