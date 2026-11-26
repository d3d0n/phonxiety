package com.phonxiety.bridge.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.root.WizardState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** One screenshot per wizard state, plus the pairing rejection the scanner can produce. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, state: OnboardingUiState) {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    OnboardingScreen(state = state, callbacks = OnboardingCallbacks())
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/wizard_$name.png")
    }

    @Test
    fun checking() = capture("checking", OnboardingUiState(WizardState.Checking))

    @Test
    fun noRoot() = capture("no_root", state("no_root"))

    @Test
    fun rootDeniedMagisk() = capture("root_denied_magisk", state("root_denied_magisk"))

    @Test
    fun rootDeniedKernelSu() = capture("root_denied_kernelsu", state("root_denied_kernelsu"))

    @Test
    fun rootDeniedKernelSuNext() = capture("root_denied_kernelsu_next", state("root_denied_kernelsu_next"))

    @Test
    fun rootDeniedApatch() = capture("root_denied_apatch", state("root_denied_apatch"))

    @Test
    fun providerPrep() = capture("provider_prep", state("provider_prep"))

    @Test
    fun readyToInstall() = capture("ready_to_install", state("ready_to_install"))

    @Test
    fun installing() = capture("installing", state("installing"))

    @Test
    fun installFailed() = capture("install_failed", state("install_failed"))

    @Test
    fun rebootPending() = capture("reboot_pending", state("reboot_pending"))

    @Test
    fun rebootPendingMetamodule() = capture("reboot_pending_metamodule", state("reboot_pending_metamodule"))

    @Test
    fun rebootFailed() = capture("reboot_failed", state("reboot_failed"))

    @Test
    fun verify() = capture("verify", state("verify"))

    @Test
    fun verifyFailed() = capture("verify_failed", state("verify_failed"))

    @Test
    fun needsRuntime() = capture("needs_runtime", state("needs_runtime"))

    @Test
    fun needsPairing() = capture("needs_pairing", state("needs_pairing"))

    @Test
    fun pairingRejected() = capture(
        "pairing_rejected",
        OnboardingUiState(
            wizard = WizardState.NeedsPairing(OnboardingSamples.magisk),
            pairingError = "the ticket expired; run the pairing command again"
        )
    )

    @Test
    fun firstLinkTest() = capture("first_link_test", state("first_link_test"))

    @Test
    fun ready() = capture("ready", OnboardingUiState(WizardState.Ready))

    @Test
    fun doctor() = capture("doctor", state("doctor"))

    @Test
    fun indeterminate() = capture("indeterminate", state("indeterminate"))

    @Test
    fun conflict() = capture("conflict", state("conflict"))

    @Test
    fun leftovers() = capture(
        "leftovers",
        OnboardingUiState(
            wizard = WizardState.ReadyToInstall(OnboardingSamples.kernelSu),
            leftovers = listOf(OnboardingSamples.magisk)
        )
    )

    private fun state(name: String): OnboardingUiState = OnboardingUiState(wizard = requireNotNull(OnboardingSamples.states[name]))
}
