package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captions keep the brand's shouted look on screen while accessibility reads the original casing.
 * The reference line renders the literal uppercase text, so an image match proves the caption's
 * own rendering is uppercase and not merely its string.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CaptionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theCaptionUppercasesForDisplayAndKeepsOriginalCaseForAccessibility() {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = true, dynamicColor = false) {
                Column {
                    Caption(text = "Microphone", modifier = Modifier.testTag("caption"))
                    Text(
                        text = "MICROPHONE",
                        style = PhonxietyType().caption,
                        modifier = Modifier.testTag("reference")
                    )
                }
            }
        }

        composeRule.onNodeWithTag("caption").assertTextEquals("Microphone")
        val rendered = composeRule.onNodeWithTag("caption").captureToImage().asAndroidBitmap()
        val reference = composeRule.onNodeWithTag("reference").captureToImage().asAndroidBitmap()
        assertTrue("the caption draws uppercase letters", rendered.sameAs(reference))
    }
}
