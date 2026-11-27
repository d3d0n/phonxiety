package com.phonxiety.bridge.feature.livecall

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The composer is a form, and the keyboard walks it: the number opens focused, IME Next moves down
 * one field at a time in layout order, and the field draft ends the walk with Done.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ComposerFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setScreen() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                ComposerScreen(
                    state = ComposerUiState(),
                    onBack = {},
                    onDestinationChange = {},
                    onGoalChange = {},
                    onContextChange = {},
                    onConstraintsChange = {},
                    onFieldDraftChange = {},
                    onAddField = {},
                    onRemoveField = {},
                    onMaxDurationChange = {},
                    onRingTimeoutChange = {},
                    onReview = {},
                    onDismissReview = {},
                    onConfirm = {}
                )
            }
        }
    }

    @Test
    fun theNumberOpensFocused() {
        setScreen()
        composeRule.onNodeWithTag(COMPOSER_NUMBER_TAG).assertIsFocused()
    }

    @Test
    fun imeNextWalksTheFieldsInOrderAndDoneEndsTheWalk() {
        setScreen()
        composeRule.onNodeWithTag(COMPOSER_NUMBER_TAG).assertIsFocused()

        composeRule.onNodeWithTag(COMPOSER_NUMBER_TAG).performImeAction()
        composeRule.onNodeWithTag(COMPOSER_GOAL_TAG).assertIsFocused()

        composeRule.onNodeWithTag(COMPOSER_GOAL_TAG).performImeAction()
        composeRule.onNodeWithTag(COMPOSER_CONTEXT_TAG).assertIsFocused()

        composeRule.onNodeWithTag(COMPOSER_CONTEXT_TAG).performImeAction()
        composeRule.onNodeWithTag(COMPOSER_CONSTRAINTS_TAG).assertIsFocused()

        composeRule.onNodeWithTag(COMPOSER_CONSTRAINTS_TAG).performImeAction()
        composeRule.onNodeWithTag(COMPOSER_FIELD_DRAFT_TAG).assertIsFocused()

        composeRule.onNodeWithTag(COMPOSER_FIELD_DRAFT_TAG).performImeAction()
        composeRule.onNodeWithTag(COMPOSER_FIELD_DRAFT_TAG).assertIsNotFocused()
    }
}
