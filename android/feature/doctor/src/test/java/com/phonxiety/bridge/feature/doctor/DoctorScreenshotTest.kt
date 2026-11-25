package com.phonxiety.bridge.feature.doctor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
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

/** Doctor as it renders: every check green, several failures, a recovery page and the confirm dialog. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1200dp-xxhdpi")
class DoctorScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, state: DoctorUiState) {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    DoctorScreen(state = state, callbacks = DoctorCallbacks())
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/doctor_$name.png")
    }

    @Test
    fun allGreen() = capture("all_green", DoctorSamples.allGreen())

    @Test
    fun severalFailures() = capture("failures", DoctorSamples.severalFailures())

    @Test
    fun recoveryPage() = capture("recovery", DoctorSamples.recovery())

    @Test
    fun confirmDialog() = capture("confirm", DoctorSamples.pendingFix())

    @Test
    fun duringCall() = capture("during_call", DoctorSamples.duringCall())

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h800dp-xxhdpi")
    fun twoPane() = capture("two_pane", DoctorSamples.severalFailures().copy(selected = DoctorEvaluator.ID_LINK))

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h1200dp-xxhdpi")
    fun checking() = capture(
        "checking",
        DoctorSamples.allGreen().copy(checking = true, rows = emptyList(), daemonFixes = emptyList())
    )
}
