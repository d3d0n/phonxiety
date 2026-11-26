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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.testing.assertAccessible
import com.phonxiety.bridge.designsystem.testing.pinAnimationFrame
import com.phonxiety.bridge.root.InstallOperation
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.WizardState
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every wizard state at 411 dp light, the main screen once in 411 dp dark, and 320 dp at 200 %
 * light for the states whose layout changes the most (longest copy, longest action label, a
 * sheet-free progress block, both error kinds). The accessibility gate runs on each recorded state
 * before the image is accepted.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h4000dp-xxhdpi")
class OnboardingScreenshotTest(private val case: MatrixCase) {
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
    private var pageHeightDp by mutableStateOf(case.variant.heightDp)

    @Test
    fun rendersOneMatrixState() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, case.variant.fontScale)
            ) {
                PhonxietyTheme(darkTheme = case.variant.dark, dynamicColor = false) {
                    Box(
                        modifier = Modifier
                            .testTag(SCREEN_TAG)
                            .width(case.variant.widthDp.dp)
                            .height(pageHeightDp.dp)
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        OnboardingScreen(state = case.state, callbacks = OnboardingCallbacks())
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val name = "src/test/screenshots/wizard_${case.stateName}_${case.variant.slug}"
        if (case.variant.phoneViewport) {
            // The phone-height viewport as a person sees it, then the same screen at its own content
            // height: only there is every line whole, so only there does the gate run.
            composeRule.onNodeWithTag(SCREEN_TAG).captureRoboImage("$name.png")
            composeRule.captureWholeScreen(SCREEN_TAG, FOOTER, { pageHeightDp = it }, "${name}_content.png")
        } else {
            composeRule.onNodeWithTag(SCREEN_TAG).assertAccessible()
            composeRule.pinAnimationFrame()
            composeRule.onNodeWithTag(SCREEN_TAG).captureRoboImage("$name.png")
        }
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
        val heightDp: Int,
        val dark: Boolean,
        val fontScale: Float,
        /** Whether the height is a phone viewport, which also needs a whole-content capture. */
        val phoneViewport: Boolean = false,
        val slug: String
    )

    companion object {
        private const val SCREEN_TAG = "onboarding_screen"

        /**
         * 411 dp is captured as one full page. 320 dp at 200 % uses a phone's own height (891 dp)
         * instead of a long sheet, because that is the viewport the layout has to survive; it also
         * gets a whole-content capture, which is the only one the gate checks because a viewport
         * edge cuts lines.
         */
        private val LIGHT = Variant(411, heightDp = FULL_PAGE_DP, dark = false, fontScale = 1f, slug = "411_light")
        private val DARK = Variant(411, heightDp = FULL_PAGE_DP, dark = true, fontScale = 1f, slug = "411_dark")
        private val LARGE_TEXT = Variant(
            widthDp = 320,
            heightDp = PHONE_DP,
            dark = false,
            fontScale = 2f,
            phoneViewport = true,
            slug = "320_font200_light"
        )

        /** The screen's main state, and the one that carries the dark theme. */
        private const val OVERVIEW_STATE = "ready_to_install"

        /** The page's last line; the whole-content capture measures the page by it. */
        private const val FOOTER = "No cloud, no analytics"

        private const val PHONE_DP = 891
        private const val FULL_PAGE_DP = 2600

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
            val wide = states.map { (name, state) -> MatrixCase(name, state, LIGHT) }
            val large = states
                .filter { (name, _) -> name in representative }
                .map { (name, state) -> MatrixCase(name, state, LARGE_TEXT) }
            val overview = states
                .filter { (name, _) -> name == OVERVIEW_STATE }
                .map { (name, state) -> MatrixCase(name, state, DARK) }
            return (wide + large + overview).map { arrayOf<Any>(it) }
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
