package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.LevelMeter
import com.phonxiety.bridge.designsystem.component.LinkPath
import com.phonxiety.bridge.designsystem.component.LinkPathPill
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.SetupStep
import com.phonxiety.bridge.designsystem.component.SetupStepList
import com.phonxiety.bridge.designsystem.component.SetupStepState
import com.phonxiety.bridge.designsystem.component.StatTile
import com.phonxiety.bridge.designsystem.component.StatTone
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * One screenshot per design-system surface, in the branded fallback palette so the images do not
 * depend on the machine's wallpaper. `recordRoborazziDebug` writes them; `verifyRoborazziDebug`
 * compares them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DesignSystemScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(
        name: String,
        dark: Boolean = false,
        content: @Composable () -> Unit
    ) {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = dark, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        content()
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/designsystem_$name.png")
    }

    @Test
    fun statusPills() = capture("status_pills") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkPathPill(LinkPath.DIRECT)
            LinkPathPill(LinkPath.RELAY)
            LinkPathPill(LinkPath.OFFLINE)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("Paired", StatusTone.POSITIVE)
            StatusPill("Connecting", StatusTone.WARNING)
            StatusPill("Denied", StatusTone.NEGATIVE)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("Idle", StatusTone.NEUTRAL)
            StatusPill("Ready", StatusTone.ACCENT)
        }
    }

    @Test
    fun meters() = capture("meters") {
        LevelMeter(label = "Microphone", levelDbfs = -18f, modifier = Modifier.fillMaxWidth())
        LevelMeter(label = "Speaker", levelDbfs = -47f, modifier = Modifier.fillMaxWidth())
        LevelMeter(label = "Silent", levelDbfs = null, modifier = Modifier.fillMaxWidth())
    }

    @Test
    fun cards() = capture("cards") {
        SectionCard(title = "Link", subtitle = "iroh via direct path") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatTile(label = "RTT p50", value = "41 ms", mono = true, tone = StatTone.POSITIVE)
                StatTile(label = "Uptime", value = "12 m", mono = true)
                StatTile(label = "Reconnects", value = "0", mono = true)
            }
            KeyValueRow(label = "Endpoint", value = "6c3a1f9b", mono = true)
        }
        SectionCard(title = "Audio", status = { StatusPill("Opus", StatusTone.ACCENT) }) {
            KeyValueRow(label = "Bitrate", value = "32 kbit/s")
        }
    }

    @Test
    fun steps() = capture("steps") {
        SetupStepList(
            steps = listOf(
                SetupStep("Detect root", SetupStepState.DONE, "Magisk 31.1"),
                SetupStep("Install module", SetupStepState.ACTIVE, "phonxiety_bridge 0.3.0"),
                SetupStep("Reboot", SetupStepState.PENDING),
                SetupStep("Pair with your computer", SetupStepState.PENDING)
            )
        )
        SetupStepList(
            steps = listOf(
                SetupStep("Install module", SetupStepState.FAILED, "ksud exited with 1")
            )
        )
    }

    @Test
    fun actions() = capture("actions") {
        PrimaryActionButton(label = "Install and reboot", onClick = {})
        PrimaryActionButton(
            label = "Installing…",
            onClick = {},
            busy = true,
            progress = 0.4f,
            supportingText = "The provider is mounting the module"
        )
        PrimaryActionButton(label = "Recheck", onClick = {}, enabled = false)
    }

    @Test
    fun errorCard() = capture("error_card") {
        ErrorCard(
            title = "The module install failed",
            message = "ksud refused the zip. Retry is safe; nothing was mounted.",
            output = "ksud module install /data/local/tmp/phonxiety_bridge.zip\n! module.prop: unsupported versionCode",
            initiallyExpanded = true,
            actionLabel = "Retry",
            onAction = {}
        )
    }

    @Test
    fun stateViews() = capture("state_views") {
        LoadingState(message = "Reading /data/adb through su…")
        EmptyState(
            title = "No calls yet",
            message = "Calls placed by the agent appear here."
        )
        Text(text = "Empty state with an action belongs here", style = MaterialTheme.typography.bodySmall)
    }

    @Test
    fun darkCards() = capture("dark_cards", dark = true) {
        SectionCard(title = "Device") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatTile(label = "Battery", value = "78 %", mono = true)
                StatTile(label = "Thermal", value = "Light", tone = StatTone.WARNING)
                StatTile(label = "Doze", value = "Exempt", mono = true, tone = StatTone.POSITIVE)
            }
        }
        LevelMeter(label = "Speaker", levelDbfs = -12f, modifier = Modifier.fillMaxWidth())
    }
}
