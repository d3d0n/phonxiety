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
import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.testing.assertAccessible
import com.phonxiety.bridge.designsystem.testing.pinAnimationFrame
import com.phonxiety.bridge.designsystem.testing.resumeAnimationFrames
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Doctor in three shots: every state at 411 dp light, one overview in 411 dp dark, and the states
 * whose rows, records or pinned actions reflow at 320 dp with 200 % text. Each variant sets the
 * window width and the resource font scale itself, so the confirmation dialog is laid out at the
 * configuration it is named after rather than at 411 dp with a scaled child. The accessibility gate
 * runs on each recorded state before its image is accepted.
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
            PhonxietyTheme(darkTheme = variant.dark, dynamicColor = false) {
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
            composeRule.pinAnimationFrame()
            composeRule.onNode(isDialog())
                .captureRoboImage("src/test/screenshots/doctor_confirm_${variant.slug}.png")
        } else {
            val disclosure = matrixCase.state.openedDetails
            if (disclosure != null) {
                // This state records the expanded evidence itself: open Details with a semantics
                // click (no ripple to settle), then bring the raw record into the viewport, so a
                // 200 % capture shows the expanded content rather than the top of the screen.
                composeRule.onNodeWithContentDescription(disclosure, substring = true)
                    .performScrollTo()
                    .performSemanticsAction(SemanticsActions.OnClick)
                composeRule.waitForIdle()
                composeRule.onAllNodesWithText(matrixCase.state.anchorText!!, substring = true)
                    .onFirst()
                    .performScrollTo()
                    .assertIsDisplayed()
            }
            composeRule.onNodeWithTag(CAPTURE_TAG).assertAccessible()
            composeRule.pinAnimationFrame()
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
        // The dark overview above carries the theme; the lower sections are judged at light and
        // at 200 % text, where the long entries reflow.
        assumeFalse(matrixCase.variant.dark)
        val variant = matrixCase.variant
        configureWindow(variant)
        composeRule.setContent {
            AssertWindowMatches(variant)
            PhonxietyTheme(darkTheme = variant.dark, dynamicColor = false) {
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
        composeRule.pinAnimationFrame()
        composeRule.onNodeWithTag(CAPTURE_TAG)
            .captureRoboImage("src/test/screenshots/doctor_lower_recovery_${variant.slug}.png")

        // A real persisted entry, not an empty state: its text is on screen before the capture.
        composeRule.resumeAnimationFrames()
        composeRule.onNodeWithText(SAVED_ENTRY_TEXT, substring = true).performScrollTo()
        composeRule.onNodeWithText(SAVED_ENTRY_TEXT, substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(CAPTURE_TAG).assertAccessible()
        composeRule.pinAnimationFrame()
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
        // Changing the device configuration can reset the process default: the instant this screen
        // formats is reproducible only with both pinned at the moment of composition.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
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

        private val LIGHT = Variant(411, dark = false, fontScale = 1f, slug = "411_light")
        private val DARK = Variant(411, dark = true, fontScale = 1f, slug = "411_dark")
        private val LARGE_TEXT = Variant(320, dark = false, fontScale = 2f, slug = "320_font200_light")

        /** The screen's overview is the one state that also carries the dark theme. */
        private val OVERVIEW = DoctorState.ALL_GREEN

        /** States whose rows, raw records or pinned actions reflow at 200 % text. */
        private val LARGE_TEXT_STATES = setOf(
            DoctorState.ALL_GREEN,
            DoctorState.FAILURES,
            DoctorState.PENDING,
            DoctorState.RECOVERY,
            DoctorState.DURING_CALL,
            DoctorState.DETAILS_RELAY,
            DoctorState.DETAILS_EXIT
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun matrix(): List<Array<Any>> = DoctorState.entries.flatMap { state ->
            val variants = buildList {
                add(LIGHT)
                if (state == OVERVIEW) add(DARK)
                if (state in LARGE_TEXT_STATES) add(LARGE_TEXT)
            }
            variants.map { variant -> arrayOf<Any>(MatrixCase(state, variant)) }
        }
    }
}

data class Variant(
    val widthDp: Int,
    val dark: Boolean,
    val fontScale: Float,
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
    },
    DETAILS_RELAY("details_relay") {
        override fun build(): DoctorUiState = DoctorSamples.severalFailures().let { state ->
            state.copy(rows = state.rows.filter { it.kind == DoctorItemKind.RELAY }, selected = null)
        }

        override val openedDetails: String get() = "Details: Relay configuration"
        override val anchorText: String get() = "2 custom relay(s)"
    },
    DETAILS_EXIT("details_exit") {
        override fun build(): DoctorUiState = DoctorSamples.severalFailures().let { state ->
            state.copy(rows = state.rows.filter { it.kind == DoctorItemKind.LAST_CRASH }, selected = null)
        }

        override val openedDetails: String get() = "Details: Last process exit"
        override val anchorText: String get() = "reason_16"
    };

    abstract fun build(): DoctorUiState

    /** The Details disclosure this state opens, or null when the screen stays closed. */
    open val openedDetails: String? get() = null

    /** The raw record the opened Details must show before the capture. */
    open val anchorText: String? get() = null
}
