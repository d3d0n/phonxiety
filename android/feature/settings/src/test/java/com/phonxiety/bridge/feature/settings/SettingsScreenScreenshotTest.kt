package com.phonxiety.bridge.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The two settings surfaces that must not look wrong: loaded values and the auto-tune change log. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, state: SettingsUiState, dark: Boolean = false) {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = dark, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(state = state)
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/settings_$name.png")
    }

    @Test
    fun loadedWithAutoTune() = capture("loaded", previewSettingsState())

    @Test
    fun autoTuneOff() = capture(
        "auto_tune_off",
        previewSettingsState().copy(autoTune = AutoTuneUi(enabled = false))
    )

    @Test
    fun darkTheme() = capture("dark", previewSettingsState(), dark = true)
}
