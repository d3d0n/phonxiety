package com.phonxiety.bridge.designsystem

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi harness sample: records a bitmap with `recordRoborazziDebug` and
 * compares it with `verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DesignsystemPlaceholderScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun placeholder() {
        composeRule.setContent {
            PhonxietyTheme {
                DesignsystemPlaceholder()
            }
        }
        composeRule.onRoot().captureRoboImage(
            "src/test/screenshots/designsystem_placeholder.png"
        )
    }
}
