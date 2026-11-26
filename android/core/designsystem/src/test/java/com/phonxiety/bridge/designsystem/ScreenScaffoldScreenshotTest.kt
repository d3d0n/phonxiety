package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.ScreenScaffold
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.testing.assertAccessible
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shared frame: one app bar, 16 dp margins, and a lower section reached by scrolling. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenScaffoldScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lightPhone() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                ScreenScaffold(title = "Settings", onBack = {}) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionCard(title = "Bridge", subtitle = "The service that keeps the link open.") {
                            KeyValueRow(label = "State", value = "Connected")
                            KeyValueRow(label = "Path", value = "Direct")
                        }
                        SectionCard(title = "Audio") {
                            KeyValueRow(label = "Codec", value = "Opus")
                        }
                    }
                }
            }
        }
        composeRule.onRoot().assertAccessible()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/designsystem_scaffold_411_light.png")
    }

    @Test
    fun widePhoneInDark() {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = true, dynamicColor = false) {
                ScreenScaffold(title = "Settings", onBack = {}) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionCard(title = "Bridge", subtitle = "The service that keeps the link open.") {
                            KeyValueRow(label = "State", value = "Connected")
                            KeyValueRow(label = "Path", value = "Direct")
                        }
                        SectionCard(title = "Audio") {
                            KeyValueRow(label = "Codec", value = "Opus")
                        }
                    }
                }
            }
        }
        composeRule.onRoot().assertAccessible()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/designsystem_scaffold_411_dark.png")
    }

    @Test
    fun smallScreenAtTwoHundredPercentInLight() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f)
            ) {
                PhonxietyTheme(darkTheme = false, dynamicColor = false) {
                    Box(
                        modifier = Modifier
                            .testTag(SCAFFOLD_TAG)
                            .width(320.dp)
                            .fillMaxSize()
                    ) {
                        ScreenScaffold(title = "Settings", onBack = {}) {
                            SectionCard(title = "Bridge", subtitle = "The service that keeps the link open.") {
                                KeyValueRow(label = "State", value = "Connected")
                            }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(SCAFFOLD_TAG).assertAccessible()
        composeRule.onNodeWithTag(SCAFFOLD_TAG)
            .captureRoboImage("src/test/screenshots/designsystem_scaffold_320_font200_light.png")
    }

    @Test
    fun lowerSectionAfterScrolling() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                ScreenScaffold(title = "Doctor", onBack = {}) {
                    LazyColumn(
                        modifier = Modifier
                            .testTag(LIST_TAG)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items((1..20).map { "Check $it" }) { label ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(LIST_TAG).performScrollToIndex(19)
        composeRule.onRoot().assertAccessible()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/designsystem_scaffold_lower.png")
    }

    private companion object {
        const val SCAFFOLD_TAG = "scaffold_capture"
        const val LIST_TAG = "scaffold_list"
    }
}
