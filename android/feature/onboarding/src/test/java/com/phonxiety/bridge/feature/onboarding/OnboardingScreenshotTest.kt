package com.phonxiety.bridge.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.root.InstallOperation
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.WizardState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every wizard state across the decision-10 matrix: 411 dp at 100 % in light, dark and one
 * deterministic dynamic-color case, plus 320 dp at 200 % in light and dark. The accessibility gate
 * runs on each recorded state before the image is accepted.
 *
 * All states are rendered at 411 dp/light; the other four variants cover the states whose layout
 * changes the most (longest copy, longest action label, a sheet-free progress block, both error
 * kinds).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2600dp-xxhdpi")
class OnboardingScreenshotTest(private val case: MatrixCase) {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersOneMatrixState() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, case.variant.fontScale)
            ) {
                PhonxietyTheme(
                    darkTheme = case.variant.dark,
                    dynamicColor = case.variant.dynamic
                ) {
                    Box(
                        modifier = Modifier
                            .testTag(SCREEN_TAG)
                            .width(case.variant.widthDp.dp)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        OnboardingScreen(state = case.state, callbacks = OnboardingCallbacks())
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SCREEN_TAG).assertAccessible()
        composeRule.onNodeWithTag(SCREEN_TAG)
            .captureRoboImage("src/test/screenshots/wizard_${case.stateName}_${case.variant.slug}.png")
    }

    data class MatrixCase(
        val stateName: String,
        val state: OnboardingUiState,
        val variant: Variant
    ) {
        override fun toString(): String = "${stateName}_${variant.slug}"
    }

    data class Variant(
        val widthDp: Int,
        val dark: Boolean,
        val fontScale: Float,
        val dynamic: Boolean,
        val slug: String
    )

    companion object {
        private const val SCREEN_TAG = "onboarding_screen"

        private val allVariants = listOf(
            Variant(411, dark = false, fontScale = 1f, dynamic = false, slug = "411_light"),
            Variant(411, dark = true, fontScale = 1f, dynamic = false, slug = "411_dark"),
            Variant(411, dark = false, fontScale = 1f, dynamic = true, slug = "411_dynamic"),
            Variant(320, dark = false, fontScale = 2f, dynamic = false, slug = "320_font200_light"),
            Variant(320, dark = true, fontScale = 2f, dynamic = false, slug = "320_font200_dark")
        )

        /** States whose layout is exercised by every variant of the matrix. */
        private val representative = setOf(
            "checking",
            "no_root",
            "root_denied_kernelsu",
            "provider_prep",
            "ready_to_install",
            "installing_metamodule",
            "install_failed",
            "reboot_pending_metamodule",
            "verify_failed",
            "needs_runtime",
            "needs_pairing",
            "pairing_rejected",
            "doctor",
            "indeterminate",
            "conflict",
            "ready"
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun matrix(): List<Array<Any>> {
            val states = states()
            val wide = states.map { (name, state) -> MatrixCase(name, state, allVariants.first()) }
            val rest = states
                .filter { (name, _) -> name in representative }
                .flatMap { (name, state) ->
                    allVariants.drop(1).map { variant -> MatrixCase(name, state, variant) }
                }
            return (wide + rest).map { arrayOf<Any>(it) }
        }

        private fun states(): List<Pair<String, OnboardingUiState>> {
            val samples = OnboardingSamples.states.map { (name, state) ->
                name to OnboardingUiState(wizard = state)
            }
            val extra = listOf(
                "pairing_rejected" to OnboardingUiState(
                    wizard = WizardState.NeedsPairing(OnboardingSamples.magisk),
                    pairingError = "pair_link_invalid"
                ),
                "leftovers" to OnboardingUiState(
                    wizard = WizardState.ReadyToInstall(OnboardingSamples.kernelSu),
                    leftovers = listOf(OnboardingSamples.magisk)
                ),
                "installing_metamodule" to OnboardingUiState(
                    wizard = WizardState.Installing(
                        provider = OnboardingSamples.kernelSu,
                        operation = InstallOperation.PREPARE_METAMODULE,
                        module = ModuleState.Absent
                    ),
                    installPhase = SetupInstallPhase.Downloading(bytes = 712_000, total = 1_310_000)
                ),
                "installing_metamodule_install" to OnboardingUiState(
                    wizard = WizardState.Installing(
                        provider = OnboardingSamples.kernelSu,
                        operation = InstallOperation.PREPARE_METAMODULE,
                        module = ModuleState.Absent
                    ),
                    installPhase = SetupInstallPhase.Installing
                )
            )
            return samples + extra
        }
    }
}
