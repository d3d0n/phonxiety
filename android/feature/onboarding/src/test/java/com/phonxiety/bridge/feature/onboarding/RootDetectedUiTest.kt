package com.phonxiety.bridge.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.WizardState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the person is told about root: the exact grant steps of every hiding manager, and the
 * leftovers of a provider the phone migrated away from — reported, never acted on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RootDetectedUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(state: OnboardingUiState) {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    OnboardingScreen(state = state, callbacks = OnboardingCallbacks())
                }
            }
        }
    }

    @Test
    fun aPendingKernelSuGrantNamesItsOwnSuperuserSteps() {
        show(OnboardingUiState(wizard = WizardState.RootDenied(OnboardingSamples.kernelSuDenied)))

        composeRule.onNodeWithText("KernelSU: Open KernelSU → Superuser", substring = true).assertExists()
        composeRule.onNodeWithText("force-close the manager", substring = true).assertExists()
    }

    @Test
    fun aPendingApatchGrantNamesItsOwnSuperuserSteps() {
        show(OnboardingUiState(wizard = WizardState.RootDenied(OnboardingSamples.apatchDenied)))

        composeRule.onNodeWithText("APatch: Open APatch → Superuser", substring = true).assertExists()
    }

    @Test
    fun twoHidingManagersListBothSetsOfSteps() {
        val state = WizardState.RootDenied(
            provider = OnboardingSamples.kernelSu,
            coProviders = listOf(OnboardingSamples.apatch)
        )

        show(OnboardingUiState(wizard = state))

        composeRule.onNodeWithText("KernelSU: Open KernelSU", substring = true).assertExists()
        composeRule.onNodeWithText("APatch: Open APatch", substring = true).assertExists()
    }

    @Test
    fun anInactiveProviderLeftOverFromAMigrationIsReportedWithItsFiles() {
        val leftovers = listOf(
            DetectedProvider(
                id = RootId.MAGISK,
                version = "31.1 (31100)",
                managerPackage = "com.topjohnwu.magisk",
                managerInstalled = true,
                artefacts = listOf("/data/adb/magisk")
            )
        )
        val state = OnboardingUiState(
            wizard = WizardState.ReadyToInstall(OnboardingSamples.kernelSu, ModuleState.Absent),
            leftovers = leftovers
        )

        show(state)

        composeRule
            .onNodeWithText("Magisk leftovers, inactive: /data/adb/magisk, Magisk app")
            .assertExists()
    }
}
