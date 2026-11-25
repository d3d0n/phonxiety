package com.phonxiety.bridge.feature.updates

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

/** Screenshots of the states the Updates screen must never confuse: current, available, blocked. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class UpdatesScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, state: UpdatesUiState) {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    UpdatesScreen(
                        state = state,
                        onBack = {},
                        onRefresh = {},
                        onInstallApp = {},
                        onInstallModule = {},
                        onRollback = {},
                        onDismiss = {}
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/updates_$name.png")
    }

    @Test
    fun upToDate() = capture("up_to_date", UpdatesSamples.upToDate())

    @Test
    fun updateAvailable() = capture("update_available", UpdatesSamples.updateAvailable())

    @Test
    fun incompatible() = capture("incompatible", UpdatesSamples.incompatible())

    @Test
    fun installing() = capture("installing", UpdatesSamples.installing())

    @Test
    fun failed() = capture("failed", UpdatesSamples.failed())
}
