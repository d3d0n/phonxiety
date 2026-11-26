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
import com.phonxiety.bridge.designsystem.testing.Screenshot
import com.phonxiety.bridge.designsystem.testing.assertAccessible
import com.phonxiety.bridge.designsystem.testing.pinAnimationFrame
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings in three shots: every state at 411 dp light, the loaded screen in 411 dp dark, and the
 * states whose rows reflow at 320 dp with 200 % text. The accessibility gate runs on each state
 * before its image is accepted.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2600dp-xxhdpi")
@Category(Screenshot::class)
class SettingsScreenScreenshotTest(private val matrixCase: SettingsMatrixCase) {
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
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, matrixCase.variant.fontScale)
            ) {
                PhonxietyTheme(darkTheme = matrixCase.variant.dark, dynamicColor = false) {
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
        composeRule.pinAnimationFrame()
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
        composeRule.onNodeWithText("Revert everything").performScrollTo()
        composeRule.onNodeWithTag(CAPTURE_TAG).assertAccessible()
        composeRule.pinAnimationFrame()
        composeRule.onNodeWithTag(CAPTURE_TAG)
            .captureRoboImage("src/test/screenshots/settings_lower_sections.png")
    }

    companion object {
        private const val CAPTURE_TAG = "settings_matrix_capture"

        private val LIGHT = SettingsVariant(411, dark = false, fontScale = 1f, slug = "411_light")
        private val DARK = SettingsVariant(411, dark = true, fontScale = 1f, slug = "411_dark")
        private val LARGE_TEXT = SettingsVariant(320, dark = false, fontScale = 2f, slug = "320_font200_light")

        /** The states whose label/value rows are long enough to reflow at 200 % text. */
        private val LARGE_TEXT_STATES = setOf(SettingsState.LOADED, SettingsState.INVALID_NUMBER)

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun matrix(): List<Array<Any>> = SettingsState.entries.flatMap { state ->
            val variants = buildList {
                add(LIGHT)
                if (state == SettingsState.LOADED) add(DARK)
                if (state in LARGE_TEXT_STATES) add(LARGE_TEXT)
            }
            variants.map { variant -> arrayOf<Any>(SettingsMatrixCase(state, variant)) }
        }
    }
}

data class SettingsVariant(
    val widthDp: Int,
    val dark: Boolean,
    val fontScale: Float,
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
