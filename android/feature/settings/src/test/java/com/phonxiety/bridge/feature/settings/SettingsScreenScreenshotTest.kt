package com.phonxiety.bridge.feature.settings

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
 * Settings across the decision-10 matrix: 411 dp at 100 %, a deterministic dynamic-colour case, and
 * 320 dp at 200 % font scale in light and dark. The accessibility gate runs on each state before
 * its image is accepted.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2600dp-xxhdpi")
class SettingsScreenScreenshotTest(private val matrixCase: SettingsMatrixCase) {
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
                            SettingsScreen(state = matrixCase.state.build())
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(CAPTURE_TAG).assertAccessible()
        composeRule.onNodeWithTag(CAPTURE_TAG)
            .captureRoboImage("src/test/screenshots/settings_${matrixCase.state.slug}_${matrixCase.variant.slug}.png")
    }

    /** The daemon card is three screens tall; the save row at its end must be recorded too. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
    fun rendersTheLowerSections() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.testTag(CAPTURE_TAG)) {
                        SettingsScreen(state = previewSettingsState())
                    }
                }
            }
        }
        composeRule.onNodeWithText("Evaluate now").performScrollTo()
        composeRule.onNodeWithTag(CAPTURE_TAG).assertAccessible()
        composeRule.onNodeWithTag(CAPTURE_TAG)
            .captureRoboImage("src/test/screenshots/settings_lower_sections.png")
    }

    companion object {
        private const val CAPTURE_TAG = "settings_matrix_capture"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun matrix(): List<Array<Any>> {
            val variants = listOf(
                SettingsVariant(411, dark = false, fontScale = 1f, dynamic = false, slug = "411_light"),
                SettingsVariant(411, dark = true, fontScale = 1f, dynamic = false, slug = "411_dark"),
                SettingsVariant(411, dark = false, fontScale = 1f, dynamic = true, slug = "411_dynamic"),
                SettingsVariant(320, dark = false, fontScale = 2f, dynamic = false, slug = "320_font200_light"),
                SettingsVariant(320, dark = true, fontScale = 2f, dynamic = false, slug = "320_font200_dark")
            )
            return SettingsState.entries.flatMap { state ->
                variants.map { variant -> arrayOf<Any>(SettingsMatrixCase(state, variant)) }
            }
        }
    }
}

data class SettingsVariant(
    val widthDp: Int,
    val dark: Boolean,
    val fontScale: Float,
    val dynamic: Boolean,
    val slug: String
)

data class SettingsMatrixCase(val state: SettingsState, val variant: SettingsVariant) {
    override fun toString(): String = "${state.slug}_${variant.slug}"
}

enum class SettingsState(val slug: String) {
    LOADED("loaded") {
        override fun build(): SettingsUiState = previewSettingsState()
    },
    AUTO_TUNE_OFF("auto_tune_off") {
        override fun build(): SettingsUiState = previewSettingsState().copy(autoTune = AutoTuneUi(enabled = false))
    },
    INVALID_NUMBER("invalid_number") {
        override fun build(): SettingsUiState = previewSettingsState().let { state ->
            state.copy(daemon = state.daemon.copy(form = state.daemon.form.copy(ringTimeout = "soon")))
        }
    };

    abstract fun build(): SettingsUiState
}
