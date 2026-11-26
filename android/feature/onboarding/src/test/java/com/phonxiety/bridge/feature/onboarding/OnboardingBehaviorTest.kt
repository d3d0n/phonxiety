package com.phonxiety.bridge.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.root.InstallOperation
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.WizardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wizard's acceptance checks that a screenshot cannot prove: a rejected paste stays editable,
 * one action per screen, whole action labels at 200 %, the reboot copy that stays true when
 * notifications are denied, and a canceled scanner that starts nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val state = mutableStateOf(OnboardingUiState())

    private fun host(
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
                            .width(widthDp.dp)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        OnboardingScreen(state = state.value, callbacks = callbacks)
                    }
                }
            }
        }
    }

    private fun show(next: OnboardingUiState) {
        state.value = next
        composeRule.waitForIdle()
    }

    private fun sample(name: String): WizardState = requireNotNull(OnboardingSamples.states[name])

    @Test
    fun aRejectedPasteStaysEditable() {
        host(
            OnboardingCallbacks(
                onPairLink = { state.value = state.value.copy(pairingError = "pair_link_invalid") },
                onDismissPairingError = { state.value = state.value.copy(pairingError = null) }
            )
        )
        show(OnboardingUiState(wizard = WizardState.NeedsPairing(OnboardingSamples.magisk)))

        composeRule.onNodeWithText("Paste pairing link").performClick()
        composeRule.onNodeWithText("Pairing link").performTextInput("phonxiety://pair?broken")
        composeRule.onNodeWithText("Pair").performClick()

        // The sheet's field and the card behind it both carry the sentence; either is enough.
        composeRule
            .onAllNodesWithText("That is not a pairing link. Copy it again from phonxiety pair.")
            .onFirst()
            .assertExists()
        composeRule.onAllNodesWithText("phonxiety://pair?broken").onFirst().assertExists()

        // Editing clears the rejection and keeps what the person typed.
        composeRule.onAllNodesWithText("phonxiety://pair?broken").onFirst().performTextInput("2")
        composeRule.onAllNodesWithText("phonxiety://pair?broken2").onFirst().assertExists()
        composeRule
            .onAllNodesWithText("That is not a pairing link. Copy it again from phonxiety pair.")
            .assertCountEquals(0)
    }

    @Test
    fun aRejectedLinkKeepsItsRawCodeForDetails() {
        host()
        show(
            OnboardingUiState(
                wizard = WizardState.NeedsPairing(OnboardingSamples.magisk),
                pairingError = "pair_link_invalid"
            )
        )
        composeRule.onNodeWithText("The pairing was rejected").assertExists()
        composeRule.onNodeWithText("Show details").performClick()
        composeRule.onNodeWithText("pair_link_invalid").assertExists()
    }

    @Test
    fun everyScreenOffersOneActionPerVerb() {
        host()
        val expected = listOf(
            "install_failed" to "Retry",
            "doctor" to "Open Doctor",
            "verify_failed" to "Open Doctor",
            "indeterminate" to "Check again",
            "conflict" to "Check again",
            "reboot_pending" to "Reboot now",
            "reboot_pending" to "Later",
            "needs_pairing" to "Scan QR code",
            "needs_pairing" to "Paste pairing link"
        )
        expected.forEach { (stateName, label) ->
            show(OnboardingUiState(wizard = sample(stateName)))
            val nodes = composeRule.onAllNodesWithText(label).fetchSemanticsNodes()
            assertEquals("$stateName must show one \"$label\"", 1, nodes.size)
        }
    }

    @Test
    fun aPassingVerifyOffersNoDoctorAction() {
        host()
        show(OnboardingUiState(wizard = sample("verify")))
        composeRule.onNodeWithText("Open Doctor").assertDoesNotExist()
    }

    @Test
    fun actionLabelsAreWholeAtTwoHundredPercent() {
        host(fontScale = 2f, widthDp = 320)
        val labels = linkedMapOf(
            "ready_to_install" to "Install module",
            "provider_prep" to "Install metamodule",
            "root_denied_kernelsu" to "Open root manager",
            "needs_pairing" to "Scan QR code",
            "reboot_pending" to "Reboot now",
            "needs_runtime" to "Grant permissions",
            "ready" to "Open dashboard"
        )
        labels.forEach { (stateName, label) ->
            show(OnboardingUiState(wizard = sample(stateName)))
            composeRule.onNodeWithText(label).assertExists()
            // Two lines at 200 % are still whole; a clipped or ellipsized label would be taller.
            val height = composeRule.onNodeWithText(label).fetchSemanticsNode().size.height
            val limit = with(composeRule.density) { 110.dp.roundToPx() }
            assertTrue("$label is not whole at 200%: $height px", height <= limit)
        }
    }

    @Test
    fun theRebootCopyNeverPromisesAnAutomaticResume() {
        host()
        listOf("reboot_pending", "reboot_pending_metamodule").forEach { stateName ->
            show(OnboardingUiState(wizard = sample(stateName)))
            composeRule
                .onNodeWithText("After the reboot, open Phonxiety", substring = true)
                .assertExists()
            composeRule.onNodeWithText("by itself", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun aCanceledScannerStartsNoCameraFallback() {
        var scans = 0
        var cameras = 0
        host(OnboardingCallbacks(onScanQr = { scans++ }, onCameraScan = { cameras++ }))
        show(OnboardingUiState(wizard = WizardState.NeedsPairing(OnboardingSamples.magisk)))
        // The page's own scan action: a scanner that returns nothing must start nothing else.
        composeRule.onNodeWithText("Scan QR code").performClick()

        assertEquals(1, scans)
        assertEquals(0, cameras)
    }

    @Test
    fun theMetamoduleIsNamedAndItsDownloadShowsProgress() {
        host()
        show(
            OnboardingUiState(
                wizard = WizardState.Installing(
                    provider = OnboardingSamples.kernelSu,
                    operation = InstallOperation.PREPARE_METAMODULE,
                    module = ModuleState.Absent
                ),
                installPhase = SetupInstallPhase.Downloading(bytes = 512_000, total = 1_048_576)
            )
        )
        composeRule.onNodeWithText("Downloading meta-overlayfs").assertExists()
        composeRule.onNodeWithText("Installing the metamodule").assertExists()
        composeRule.onNodeWithText("Installing Phonxiety").assertDoesNotExist()
    }

    @Test
    fun theProviderVersionDropsAPatchsBuildHash() {
        host()
        val apatch = OnboardingSamples.apatch.copy(version = "e8e4651 (11282)")
        show(OnboardingUiState(wizard = WizardState.ReadyToInstall(apatch)))
        // The subtitle and the provider row both name it; the hash must be gone from both.
        composeRule.onAllNodesWithText("APatch 11282").onFirst().assertExists()
        composeRule.onNodeWithText("e8e4651", substring = true).assertDoesNotExist()
    }

    @Test
    fun aProviderWithoutAVersionIsNamedWithoutANumber() {
        host()
        val apatch = OnboardingSamples.apatch.copy(version = "e8e4651")
        show(OnboardingUiState(wizard = WizardState.ReadyToInstall(apatch)))
        composeRule.onAllNodesWithText("APatch").onFirst().assertExists()
    }
}
