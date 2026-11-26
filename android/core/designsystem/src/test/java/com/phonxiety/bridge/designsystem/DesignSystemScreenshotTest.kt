package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.component.ActionEmphasis
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
import com.phonxiety.bridge.designsystem.component.ToggleRow
import com.phonxiety.bridge.designsystem.testing.assertAccessible
import com.phonxiety.bridge.designsystem.testing.pinAnimationFrame
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every shared component in three shots: 411 dp light, 411 dp dark, and 320 dp at 200 % font scale
 * in light. The accessibility gate runs on each recorded state before the image is accepted.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2600dp-xxhdpi")
class DesignSystemScreenshotTest(private val matrixCase: MatrixCase) {
    @get:Rule
    val composeRule = createComposeRule()

    private val originalLocale: Locale = Locale.getDefault()
    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    @Before
    fun pinLocaleAndTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocaleAndTimeZone() {
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
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .testTag(CAPTURE_TAG)
                                    .width(matrixCase.variant.widthDp.dp)
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                PreviewBody(matrixCase.preview)
                            }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(CAPTURE_TAG).assertAccessible()
        composeRule.pinAnimationFrame()
        composeRule.onNodeWithTag(CAPTURE_TAG)
            .captureRoboImage("src/test/screenshots/designsystem_$matrixCase.png")
    }

    companion object {
        private const val CAPTURE_TAG = "designsystem_matrix_capture"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun matrix(): List<Array<Any>> {
            val variants = listOf(
                Variant(411, dark = false, fontScale = 1f, slug = "411_light"),
                Variant(411, dark = true, fontScale = 1f, slug = "411_dark"),
                Variant(320, dark = false, fontScale = 2f, slug = "320_font200_light")
            )
            return Preview.entries.flatMap { preview ->
                variants.map { variant -> arrayOf<Any>(MatrixCase(preview, variant)) }
            }
        }
    }
}

enum class Preview { PILLS, METERS, CARDS, STACKING, STEPS, ACTIONS, ERROR, STATES, CONTROLS }

data class Variant(
    val widthDp: Int,
    val dark: Boolean,
    val fontScale: Float,
    val slug: String
)

data class MatrixCase(val preview: Preview, val variant: Variant) {
    override fun toString(): String = "${preview.name.lowercase()}_${variant.slug}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewBody(preview: Preview) {
    when (preview) {
        Preview.PILLS -> {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinkPathPill(LinkPath.DIRECT)
                LinkPathPill(LinkPath.RELAY)
                LinkPathPill(LinkPath.OFFLINE)
                LinkPathPill(LinkPath.UNKNOWN)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill("Connected", StatusTone.POSITIVE)
                StatusPill("Reconnecting", StatusTone.WARNING)
                StatusPill("Failed", StatusTone.NEGATIVE)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill("Idle", StatusTone.NEUTRAL)
                StatusPill("Live", StatusTone.ACCENT)
                StatusPill("Connecting", StatusTone.WARNING, pulse = true)
            }
        }

        Preview.METERS -> {
            LevelMeter(label = "Microphone", levelDbfs = -18f, modifier = Modifier.width(300.dp))
            LevelMeter(label = "Far end", levelDbfs = -47f, modifier = Modifier.width(300.dp))
            LevelMeter(label = "Silent", levelDbfs = null, modifier = Modifier.width(300.dp))
        }

        Preview.CARDS -> {
            SectionCard(
                title = "Link",
                subtitle = "A long subtitle stays at full width under a status pill that would otherwise squeeze it.",
                status = { StatusPill("Direct", StatusTone.POSITIVE) }
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatTile(
                        label = "Latency (median)",
                        value = "41",
                        unit = "ms",
                        mono = true,
                        tone = StatTone.POSITIVE,
                        modifier = Modifier.widthIn(min = 140.dp)
                    )
                    StatTile(
                        label = "Battery",
                        value = "78",
                        unit = "%",
                        mono = true,
                        modifier = Modifier.widthIn(min = 140.dp)
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatTile(
                        label = "Uptime",
                        value = formatDuration(7_500),
                        mono = true,
                        modifier = Modifier.widthIn(min = 140.dp)
                    )
                    StatTile(
                        label = "Reconnects",
                        value = "0",
                        mono = true,
                        modifier = Modifier.widthIn(min = 140.dp)
                    )
                }
                KeyValueRow(label = "Endpoint", value = "6c3a1f9b", mono = true)
                KeyValueRow(label = "Missing", value = "—", mono = true)
                // A long answer and its label stack: the label keeps its width at any text size.
                KeyValueRow(
                    label = "Menu options heard",
                    value = "Press one for reservations, two for the kitchen, and hold for the manager after six."
                )
            }
            SectionCard(title = "Audio", status = { StatusPill("Opus", StatusTone.ACCENT) }) {
                KeyValueRow(label = "Bitrate", value = "64 kbit/s", mono = true)
                StatTile(
                    label = "Level",
                    value = "−21",
                    unit = "dBFS",
                    mono = true,
                    tone = StatTone.WARNING,
                    modifier = Modifier.widthIn(min = 140.dp)
                )
            }
        }

        Preview.STACKING -> {
            SectionCard(
                title = "The phone's privileged permissions",
                subtitle = "The grants the bridge needs to keep the link alive without a prompt.",
                status = { StatusPill("Two missing", StatusTone.WARNING) }
            ) {
                KeyValueRow(label = "Capture audio output", value = "Granted")
                KeyValueRow(label = "Control in-call experience", value = "Missing")
            }
        }

        Preview.STEPS -> {
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
                    SetupStep("Install module", SetupStepState.FAILED, "The installer refused the package")
                )
            )
        }

        Preview.ACTIONS -> {
            PrimaryActionButton(label = "Start call", onClick = {})
            PrimaryActionButton(label = "Pair another computer", onClick = {}, emphasis = ActionEmphasis.TONAL)
            PrimaryActionButton(label = "Cancel", onClick = {}, emphasis = ActionEmphasis.OUTLINED)
            PrimaryActionButton(label = "Details", onClick = {}, emphasis = ActionEmphasis.TEXT)
            PrimaryActionButton(
                label = "Installing…",
                onClick = {},
                busy = true,
                progress = 0.4f,
                supportingText = "The provider is mounting the module"
            )
            PrimaryActionButton(label = "Recheck", onClick = {}, enabled = false)
        }

        Preview.ERROR -> {
            ErrorCard(
                title = "The module install failed",
                message = "The installer refused the package. Retry is safe; nothing was mounted.",
                output = "ksud module install /data/local/tmp/phonxiety_bridge.zip\n" +
                    "! module.prop: unsupported versionCode",
                initiallyExpanded = true,
                actionLabel = "Try again",
                onAction = {},
                dismissLabel = "Dismiss",
                onDismiss = {}
            )
            ErrorCard(
                title = "The update failed",
                message = "The installer refused the package.",
                actionLabel = "Check again",
                onAction = {}
            )
        }

        Preview.STATES -> {
            EmptyState(
                title = "No calls yet",
                message = "Calls placed by the agent appear here.",
                icon = Icons.Filled.Search,
                action = { TextButton(onClick = {}) { Text("Start call") } }
            )
            EmptyState(
                title = "No recordings",
                message = "A recording appears when a call saves one."
            )
            LoadingState(message = "Reading the daemon's report…")
        }

        Preview.CONTROLS -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ToggleRow(
                    title = "Allow background running",
                    checked = true,
                    onCheckedChange = {},
                    detail = "Keeps the bridge alive when the screen is off."
                )
                ToggleRow(
                    title = "Watch thermal status",
                    checked = false,
                    onCheckedChange = {}
                )
                ToggleRow(
                    title = "Blocked while a call runs",
                    checked = true,
                    onCheckedChange = {},
                    enabled = false
                )
            }
            SectionCard(title = "Numbers") {
                KeyValueRow(label = "Call timer", value = formatElapsedTime(3_725), mono = true)
                KeyValueRow(label = "Uptime", value = formatDuration(7_500), mono = true)
                KeyValueRow(label = "Missing value", value = "—", mono = true)
            }
        }
    }
}
