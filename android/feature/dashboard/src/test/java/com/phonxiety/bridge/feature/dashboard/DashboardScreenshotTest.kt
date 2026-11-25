package com.phonxiety.bridge.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.LinkError
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshots of the dashboard states that must never look alike. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DashboardScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, bridge: BridgeState, paired: Boolean = true) {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    DashboardScreen(
                        state = DashboardUiState(bridge = bridge, paired = paired),
                        onOpenSetup = {},
                        onOpenDoctor = {},
                        onNewCall = {},
                        onOpenLiveCall = {},
                        onRunLinkTest = {},
                        onOpenUpdates = {},
                        onUnpair = {},
                        nowEpochMs = DashboardSamples.now
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/dashboard_$name.png")
    }

    @Test
    fun direct() = capture("direct", DashboardSamples.direct)

    @Test
    fun relayInCall() = capture("relay_in_call", DashboardSamples.relayInCall)

    @Test
    fun offline() = capture(
        "offline",
        DashboardSamples.offline.copy(
            lastError = LinkError(
                code = "heartbeat_missed",
                message = "The daemon stopped answering five heartbeats in a row",
                atEpochMs = DashboardSamples.now - 40_000
            )
        )
    )

    @Test
    fun unpaired() = capture("unpaired", BridgeState(), paired = false)
}
