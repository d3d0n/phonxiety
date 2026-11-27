package com.phonxiety.bridge.feature.livecall

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The composer's opening focus is claimed once per entry. The review sheet is an overlay in the
 * same composition, so a recreation while it is open must not hand the focus back to the number
 * field under it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ComposerRecreationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aRecreatedComposerUnderTheReviewSheetDoesNotRefocusTheNumber() {
        val state = ComposerUiState(
            destination = "+998901234567",
            goal = "Book a table for two at eight",
            valid = true,
            confirming = true
        )
        val content: @Composable () -> Unit = {
            PhonxietyTheme(dynamicColor = false) {
                ComposerScreen(
                    state = state,
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
        composeRule.activity.setContent { content() }
        composeRule.onNodeWithText("Place this call?").assertExists()
        composeRule.onNodeWithTag(COMPOSER_NUMBER_TAG).assertIsNotFocused()

        // The activity is destroyed and rebuilt over the same review: the number must stay unfocused.
        composeRule.activityRule.scenario.recreate()
        composeRule.activityRule.scenario.onActivity { activity -> activity.setContent { content() } }
        composeRule.onNodeWithText("Place this call?").assertExists()
        composeRule.onNodeWithTag(COMPOSER_NUMBER_TAG).assertIsNotFocused()
    }

    /** A fresh entry still opens on the number: the once-per-entry claim is not a blanket opt-out. */
    @Test
    fun aFreshEntryStillOpensOnTheNumber() {
        composeRule.activity.setContent {
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
        composeRule.onNodeWithTag(COMPOSER_NUMBER_TAG).assertIsFocused()
    }
}
