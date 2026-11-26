package com.phonxiety.bridge.feature.doctor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Doctor across the decision-10 matrix: 411 dp at 100 %, a deterministic dynamic-colour case, and
 * 320 dp at 200 % font scale in light and dark. Each variant sets the window width and the resource
 * font scale itself, so the confirmation dialog is laid out at the configuration it is named after
 * rather than at 411 dp with a scaled child. The accessibility gate runs on each recorded state
 * before its image is accepted.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DoctorScreenshotTest(private val matrixCase: MatrixCase) {
    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()

    @get:Rule
    val composeRule = createComposeRule()

    /** The screens print clock times; a screenshot must not depend on the machine's zone. */
    @Before
    fun pinClock() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreClock() {
        TimeZone.setDefault(originalTimeZone)
        Locale.setDefault(originalLocale)
    }

    @Test
    fun rendersOneMatrixState() {
        val variant = matrixCase.variant
        configureWindow(variant)
        composeRule.setContent {
            AssertWindowMatches(variant)
            PhonxietyTheme(darkTheme = variant.dark, dynamicColor = variant.dynamic) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.fillMaxSize().testTag(CAPTURE_TAG)) {
                        DoctorScreen(state = matrixCase.state.build(), callbacks = DoctorCallbacks())
                    }
                }
            }
        }
        if (matrixCase.state == DoctorState.PENDING) {
            // The dialog lives in its own window: capture and gate it, not the screen behind it.
            composeRule.onNode(isDialog()).assertAccessible()
            composeRule.onNode(isDialog())
                .captureRoboImage("src/test/screenshots/doctor_confirm_${variant.slug}.png")
        } else {
            composeRule.onNodeWithTag(CAPTURE_TAG).assertAccessible()
            composeRule.onNodeWithTag(CAPTURE_TAG)
                .captureRoboImage("src/test/screenshots/doctor_${matrixCase.state.slug}_${variant.slug}.png")
        }
    }

    /**
     * The lower half of the screen is a state of its own: the recovery guides and the change log
     * are captured in separate viewports, each at the variant's real window width and font scale.
     */
    @Test
    fun rendersTheLowerSections() {
        val variant = matrixCase.variant
        configureWindow(variant)
        composeRule.setContent {
            AssertWindowMatches(variant)
            PhonxietyTheme(darkTheme = variant.dark, dynamicColor = variant.dynamic) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.fillMaxSize().testTag(CAPTURE_TAG)) {
                        DoctorScreen(state = DoctorSamples.withChangeLog(), callbacks = DoctorCallbacks())
                    }
                }
            }
        }
        composeRule.onNodeWithText(RECOVERY_ENTRY_TEXT, substring = true).performScrollTo()
        composeRule.onNodeWithText(RECOVERY_ENTRY_TEXT, substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(CAPTURE_TAG).assertAccessible()
        composeRule.onNodeWithTag(CAPTURE_TAG)
            .captureRoboImage("src/test/screenshots/doctor_lower_recovery_${variant.slug}.png")

        // A real persisted entry, not an empty state: its text is on screen before the capture.
        composeRule.onNodeWithText(SAVED_ENTRY_TEXT, substring = true).performScrollTo()
        composeRule.onNodeWithText(SAVED_ENTRY_TEXT, substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(CAPTURE_TAG).assertAccessible()
        composeRule.onNodeWithTag(CAPTURE_TAG)
            .captureRoboImage("src/test/screenshots/doctor_lower_changes_${variant.slug}.png")
    }

    /** A capture must not depend on a ripple or an unfinished scroll: let both settle first. */
    private fun settle() {
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
    }

    /** Robolectric's window must be the variant's phone, with its resource font scale. */
    private fun configureWindow(variant: Variant) {
        RuntimeEnvironment.setQualifiers("+w${variant.widthDp}dp-h891dp")
        RuntimeEnvironment.setFontScale(variant.fontScale)
    }

    @Composable
    private fun AssertWindowMatches(variant: Variant) {
        val density = LocalDensity.current
        val widthDp = with(density) {
            LocalWindowInfo.current.containerSize.width.toDp().value
        }
        assertEquals(variant.widthDp.toFloat(), widthDp, 0.5f)
        assertEquals(variant.fontScale, density.fontScale, 0.01f)
    }

    companion object {
        private const val CAPTURE_TAG = "doctor_matrix_capture"
        const val SAVED_ENTRY_TEXT = "Restarted the bridge to reopen the link"
        const val RECOVERY_ENTRY_TEXT = "The call dropped at the phone"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun matrix(): List<Array<Any>> {
            val variants = listOf(
                Variant(411, dark = false, fontScale = 1f, dynamic = false, slug = "411_light"),
                Variant(411, dark = true, fontScale = 1f, dynamic = false, slug = "411_dark"),
                Variant(411, dark = false, fontScale = 1f, dynamic = true, slug = "411_dynamic"),
                Variant(320, dark = false, fontScale = 2f, dynamic = false, slug = "320_font200_light"),
                Variant(320, dark = true, fontScale = 2f, dynamic = false, slug = "320_font200_dark")
            )
            return DoctorState.entries.flatMap { state ->
                variants.map { variant -> arrayOf<Any>(MatrixCase(state, variant)) }
            }
        }
    }
}

data class Variant(
    val widthDp: Int,
    val dark: Boolean,
    val fontScale: Float,
    val dynamic: Boolean,
    val slug: String
)

data class MatrixCase(val state: DoctorState, val variant: Variant) {
    override fun toString(): String = "${state.slug}_${variant.slug}"
}

enum class DoctorState(val slug: String) {
    ALL_GREEN("all_green") {
        override fun build(): DoctorUiState = DoctorSamples.allGreen()
    },
    FAILURES("failures") {
        override fun build(): DoctorUiState = DoctorSamples.severalFailures()
    },
    CHECKING("checking") {
        override fun build(): DoctorUiState = DoctorSamples.allGreen().copy(checking = true, rows = emptyList(), daemonFixes = emptyList())
    },
    PENDING("confirm") {
        override fun build(): DoctorUiState = DoctorSamples.pendingFix()
    },
    RECOVERY("recovery") {
        override fun build(): DoctorUiState = DoctorSamples.recovery()
    },
    DURING_CALL("during_call") {
        override fun build(): DoctorUiState = DoctorSamples.duringCall()
    };

    abstract fun build(): DoctorUiState
}
