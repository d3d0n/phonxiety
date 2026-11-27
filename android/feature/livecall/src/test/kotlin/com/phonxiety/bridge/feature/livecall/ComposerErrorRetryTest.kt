package com.phonxiety.bridge.feature.livecall

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.model.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A failed placement's Retry must repeat the placement the same way the button does: validation
 * and the review sheet first. Calling confirm() directly would send whatever the form holds now,
 * including an edit made after the failure, with fresh ids and no review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ComposerErrorRetryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(state: ComposerUiState, onReview: () -> Unit, onConfirm: () -> Unit) {
        composeRule.setContent {
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
                    onReview = onReview,
                    onDismissReview = {},
                    onConfirm = onConfirm
                )
            }
        }
    }

    @Test
    fun aFailedPlacementsRetryOpensTheReviewAndDoesNotPlace() {
        var reviews = 0
        var confirms = 0
        show(
            ComposerUiState(
                destination = "+998901234567",
                goal = "Book a table for two on Thursday",
                valid = true,
                placeError = UiText.Literal("the daemon is unreachable"),
                placeErrorKind = FailureKind.TRANSPORT
            ),
            onReview = { reviews++ },
            onConfirm = { confirms++ }
        )

        composeRule.onNodeWithText("Retry").performScrollTo().assertIsDisplayed().performClick()

        assertEquals("Retry goes through the review step", 1, reviews)
        assertEquals("Retry never places directly", 0, confirms)
    }
}
