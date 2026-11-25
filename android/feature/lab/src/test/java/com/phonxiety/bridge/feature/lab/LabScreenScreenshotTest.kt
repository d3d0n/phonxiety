package com.phonxiety.bridge.feature.lab

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

/** Every lab surface that must not look wrong: measured numbers, a disabled audio card, logs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LabScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, state: LabUiState, dark: Boolean = false) {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = dark, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LabScreen(state = state)
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/lab_$name.png")
    }

    @Test
    fun measuredRun() = capture("measured", previewLabState())

    @Test
    fun audioOutsideACall() = capture(
        "audio_idle",
        LabUiState(
            link = previewLabState().link,
            testCall = previewLabState().testCall,
            loopbackSupported = false,
            toneSupported = true
        )
    )

    @Test
    fun darkTheme() = capture("dark", previewLabState(), dark = true)
}
