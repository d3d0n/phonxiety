package com.phonxiety.bridge.feature.doctor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Doctor across the decision-10 matrix: 411 dp at 100 %, a deterministic dynamic-colour case, and
 * 320 dp at 200 % font scale in light and dark. The accessibility gate runs on each recorded state
 * before its image is accepted.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2600dp-xxhdpi")
class DoctorScreenshotTest(private val matrixCase: MatrixCase) {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersOneMatrixState() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, matrixCase.variant.fontScale)
            ) {
                PhonxietyTheme(
                    darkTheme = matrixCase.variant.dark,
                    dynamicColor = matrixCase.variant.dynamic
                ) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(
                            modifier = Modifier
                                .testTag(CAPTURE_TAG)
                                .width(matrixCase.variant.widthDp.dp)
                        ) {
                            DoctorScreen(state = matrixCase.state.build(), callbacks = DoctorCallbacks())
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(CAPTURE_TAG).assertAccessible()
        composeRule.onNodeWithTag(CAPTURE_TAG)
            .captureRoboImage("src/test/screenshots/doctor_${matrixCase.state.slug}_${matrixCase.variant.slug}.png")
    }

    /** The lower half of the screen is a state of its own: the change log and the guides. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
    fun rendersTheLowerSections() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.testTag(CAPTURE_TAG)) {
                        DoctorScreen(state = DoctorSamples.severalFailures(), callbacks = DoctorCallbacks())
                    }
                }
            }
        }
        composeRule.onNodeWithText("Recovery guides").performScrollTo()
        composeRule.onNodeWithTag(CAPTURE_TAG).assertAccessible()
        composeRule.onNodeWithTag(CAPTURE_TAG)
            .captureRoboImage("src/test/screenshots/doctor_lower_sections.png")
    }

    companion object {
        private const val CAPTURE_TAG = "doctor_matrix_capture"

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
