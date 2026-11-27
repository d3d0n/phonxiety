package com.phonxiety.bridge.feature.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.LocalScrollToTop
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.ScrollToTopRegistry
import com.phonxiety.bridge.feature.lab.cards.LOG_FILTER_KEYBOARD
import com.phonxiety.bridge.feature.lab.cards.TEST_NUMBER_KEYBOARD
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The lab as the navigation host sees it: its page registers the section's scroll action, and the
 * two text fields declare what their keyboards do.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LabScreenBehaviourTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setScreen(registry: ScrollToTopRegistry? = null) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalScrollToTop provides registry,
                // The sample run is from one morning: render it as "today" on any test date.
                LocalLabClock provides { FIXED_NOW_MILLIS }
            ) {
                PhonxietyTheme(dynamicColor = false) {
                    Box(
                        modifier = Modifier
                            .width(411.dp)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        LabScreen(state = previewLabState())
                    }
                }
            }
        }
    }

    @Test
    fun reTappingTheSectionBringsTheFirstCardBack() {
        val registry = ScrollToTopRegistry()
        setScreen(registry)

        composeRule.onNodeWithText("App log").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Link test").assertIsNotDisplayed()

        // The frozen key the host dispatches on a re-tap of the Lab section.
        registry.dispatch("lab")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Link test").assertIsDisplayed()
    }

    @Test
    fun theNumberFieldTakesDigitsAndKeepsItsOwnCorrections() {
        setScreen()

        composeRule.onNodeWithText("Test number")
            .performScrollTo()
            .assert(hasImeAction(ImeAction.Done))
        assertEquals(KeyboardType.Phone, TEST_NUMBER_KEYBOARD.keyboardType)
        assertEquals(KeyboardCapitalization.None, TEST_NUMBER_KEYBOARD.capitalization)
        assertEquals(false, TEST_NUMBER_KEYBOARD.autoCorrectEnabled)
    }

    @Test
    fun theLogFilterClosesTheKeyboardWhenDone() {
        setScreen()

        composeRule.onNodeWithText("Filter")
            .performScrollTo()
            .assert(hasImeAction(ImeAction.Done))
        assertEquals(ImeAction.Done, LOG_FILTER_KEYBOARD.imeAction)
    }

    private companion object {
        /** 2026-11-26T10:30:00Z. */
        const val FIXED_NOW_MILLIS = 1_796_000_000_000L
    }
}
