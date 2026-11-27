package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The mark glows only on dark, never below its minimum size, and the lockup keeps the word legible. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class BrandMarkTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theMarkGlowsOnDarkOnly() {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = true, dynamicColor = false) {
                BrandMark(modifier = Modifier.testTag("dark"))
            }
        }
        assertTrue(composeRule.onNodeWithTag("dark").brandGlow())
    }

    @Test
    fun aGlowRequestOnLightIsRefused() {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = false, dynamicColor = false) {
                BrandMark(modifier = Modifier.testTag("light"), glow = true)
            }
        }
        assertFalse(composeRule.onNodeWithTag("light").brandGlow())
    }

    @Test
    fun theMarkNeverDrawsBelowSixteenDp() {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = true, dynamicColor = false) {
                Box {
                    BrandMark(modifier = Modifier.size(8.dp).testTag("tiny"))
                }
            }
        }
        val bounds = composeRule.onNodeWithTag("tiny").getUnclippedBoundsInRoot()
        assertTrue((bounds.right - bounds.left).value >= 16f)
        assertTrue((bounds.bottom - bounds.top).value >= 16f)
    }

    @Test
    fun theLockupKeepsTheWordAtLeastEighteenDp() {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = true, dynamicColor = false) {
                BrandLockup(modifier = Modifier.testTag("lockup"), height = 10.dp)
            }
        }
        val bounds = composeRule.onNodeWithTag("lockup").getUnclippedBoundsInRoot()
        assertTrue((bounds.bottom - bounds.top).value >= 18f)
    }

    @Test
    fun anUndersizedCallerModifierCannotShrinkTheWordmark() {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = true, dynamicColor = false) {
                BrandLockup(
                    modifier = Modifier.height(8.dp).testTag("lockup"),
                    height = 24.dp
                )
            }
        }
        val word = composeRule
            .onNodeWithTag(BRAND_WORDMARK_TEST_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue((word.bottom - word.top).value >= 18f)
    }

    @Test
    fun theMarkTakesItsColourFromTheBrandNotTheScheme() {
        assertEquals(Color(0xFFFF4FD8), BrandTokens.Dark.mark)
        assertEquals(Color(0xFFB3128F), BrandTokens.Light.mark)
        assertTrue(BrandTokens.Dark.glowAllowed)
        assertFalse(BrandTokens.Light.glowAllowed)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.brandGlow(): Boolean = fetchSemanticsNode().config[BrandGlowKey]
}
