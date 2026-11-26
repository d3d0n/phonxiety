package com.phonxiety.bridge.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.testing.assertAccessible
import com.phonxiety.bridge.designsystem.testing.pinAnimationFrame
import com.phonxiety.bridge.root.WizardState
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The parts of the wizard the matrix cannot reach by state alone: the opened step plan, the pairing
 * sheet with and without a rejection, a scrolled lower section, and the camera dialog's blocked
 * permission state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingExtrasScreenshotTest {
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

    /** The height the screen is composed with; a content capture regrows it to fit the page. */
    private var pageHeightDp by mutableStateOf(PHONE_DP)

    private fun setScreen(
        state: OnboardingUiState,
        callbacks: OnboardingCallbacks = OnboardingCallbacks(),
        fontScale: Float = 1f,
        widthDp: Int = 411
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale)
            ) {
                PhonxietyTheme(dynamicColor = false) {
                    Box(
                        modifier = Modifier
                            .testTag(SCREEN_TAG)
                            .width(widthDp.dp)
                            .height(pageHeightDp.dp)
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        OnboardingScreen(state = state, callbacks = callbacks)
                    }
                }
            }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h4000dp-xxhdpi")
    fun theOpenStepPlan() {
        setScreen(OnboardingUiState(wizard = requireNotNull(OnboardingSamples.states["verify_failed"])))
        // A semantics click starts no ripple, so the capture does not depend on animation timing.
        composeRule.onNodeWithText("Show steps").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SCREEN_TAG)
            .captureRoboImage("src/test/screenshots/wizard_steps_expanded_411_light.png")
        // The expanded plan runs past a phone's height; the gate needs every line whole.
        composeRule.captureWholeScreen(
            SCREEN_TAG,
            FOOTER,
            { pageHeightDp = it },
            "src/test/screenshots/wizard_steps_expanded_411_light_content.png"
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h4000dp-xxhdpi")
    fun theOpenStepPlanAtTwoHundredPercent() {
        setScreen(
            OnboardingUiState(wizard = requireNotNull(OnboardingSamples.states["verify_failed"])),
            fontScale = 2f,
            widthDp = 320
        )
        composeRule.onNodeWithText("Show steps").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SCREEN_TAG)
            .captureRoboImage("src/test/screenshots/wizard_steps_expanded_320_font200_light.png")
        // A phone-height viewport at 200 % cuts the plan in half; the whole content is what the gate
        // sees and what the second image records.
        composeRule.captureWholeScreen(
            SCREEN_TAG,
            FOOTER,
            { pageHeightDp = it },
            "src/test/screenshots/wizard_steps_expanded_320_font200_light_content.png"
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h4000dp-xxhdpi")
    fun theScrolledLowerSection() {
        setScreen(OnboardingUiState(wizard = requireNotNull(OnboardingSamples.states["ready_to_install"])))
        composeRule.onNodeWithText("Show steps").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SCREEN_TAG)
            .captureRoboImage("src/test/screenshots/wizard_lower_411_light.png")
        composeRule.captureWholeScreen(
            SCREEN_TAG,
            FOOTER,
            { pageHeightDp = it },
            "src/test/screenshots/wizard_lower_411_light_content.png"
        )
    }

    @Test
    fun thePairingSheet() {
        setScreen(OnboardingUiState(wizard = WizardState.NeedsPairing(OnboardingSamples.magisk)))
        composeRule.onNodeWithText("Paste pairing link").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(isDialog()).onLast().assertAccessible()
        composeRule.pinAnimationFrame()
        composeRule.onAllNodes(isDialog()).onLast()
            .captureRoboImage("src/test/screenshots/wizard_pairing_sheet_411_light.png")
    }

    @Test
    fun thePairingSheetWithARejectedLink() {
        setScreen(
            OnboardingUiState(
                wizard = WizardState.NeedsPairing(OnboardingSamples.magisk),
                pairingError = "pair_link_invalid"
            )
        )
        composeRule.onNodeWithText("Paste pairing link").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(isDialog()).onLast().assertAccessible()
        composeRule.pinAnimationFrame()
        composeRule.onAllNodes(isDialog()).onLast()
            .captureRoboImage("src/test/screenshots/wizard_pairing_sheet_rejected_411_light.png")
    }

    @Test
    fun theCameraDialogWhenTheCameraIsBlocked() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                QrScanDialog(onCode = {}, onDismiss = {}, onPasteRequested = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodes(isDialog()).onLast().assertAccessible()
        composeRule.pinAnimationFrame()
        composeRule.onAllNodes(isDialog()).onLast()
            .captureRoboImage("src/test/screenshots/wizard_camera_blocked_411_light.png")
    }

    private companion object {
        const val SCREEN_TAG = "onboarding_screen"
        const val FOOTER = "No cloud, no analytics"
        const val PHONE_DP = 891
    }
}
