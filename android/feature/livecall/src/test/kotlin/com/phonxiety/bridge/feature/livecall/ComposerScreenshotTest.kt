package com.phonxiety.bridge.feature.livecall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.model.ApiLimits
import com.phonxiety.bridge.model.ValidationIssue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The composer: an empty brief, a validated one, an invalid one, and the confirmation itself. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ComposerScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, state: ComposerUiState) {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/composer_$name.png")
    }

    @Test
    fun empty() = capture("empty", ComposerUiState(limits = LIMITS))

    @Test
    fun validated() = capture(
        "validated",
        ComposerUiState(
            destination = "+998 90 123 45 67",
            goal = "Book a table for two on Thursday at 20:00 under Rasulov.",
            context = "The restaurant knows us from the 12th; ask for the corner table if it is free.",
            constraints = "Do not pay a deposit. No later than 21:00.",
            resultFields = listOf("reservation_time", "party_size", "confirmation_code"),
            limits = LIMITS,
            valid = true,
            warnings = listOf("the goal is very short; the agent may not know what to accomplish")
        )
    )

    @Test
    fun invalid() = capture(
        "invalid",
        ComposerUiState(
            destination = "112",
            goal = "Call",
            limits = LIMITS,
            valid = false,
            errors = listOf(ValidationIssue("destination", "emergency numbers cannot be called by the agent"))
        )
    )

    @Test
    fun confirmation() = capture(
        "confirmation",
        ComposerUiState(
            destination = "+998 90 123 45 67",
            goal = "Book a table for two on Thursday at 20:00 under Rasulov.",
            constraints = "Do not pay a deposit.",
            resultFields = listOf("reservation_time"),
            limits = LIMITS,
            valid = true,
            confirming = true
        )
    )

    private companion object {
        val LIMITS = ApiLimits(
            maxDurationSeconds = 1800,
            ringTimeoutSeconds = 90,
            callsPerHour = 6
        )
    }
}
