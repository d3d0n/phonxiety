package com.phonxiety.bridge.feature.livecall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.designsystem.testing.Screenshot
import com.phonxiety.bridge.designsystem.testing.assertAccessible
import com.phonxiety.bridge.designsystem.testing.pinAnimationFrame
import com.phonxiety.bridge.model.ApiLimits
import com.phonxiety.bridge.model.DaemonInfo
import com.phonxiety.bridge.model.ValidationResult
import java.text.NumberFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The composer on the decision-10 matrix, its confirmation sheet, and the click paths that must
 * keep working: a field goes away when its chip is used, and a valid brief reaches the review step.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ComposerScreenshotTest {
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

    private fun setScreen(
        state: ComposerUiState,
        dark: Boolean = false,
        fontScale: Float = 1f,
        width: Dp = 411.dp,
        onReview: () -> Unit = {}
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale)
            ) {
                PhonxietyTheme(darkTheme = dark, dynamicColor = false) {
                    Box(
                        modifier = Modifier
                            .testTag(SCREEN_TAG)
                            .width(width)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
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
                            onConfirm = {}
                        )
                    }
                }
            }
        }
    }

    private fun render(name: String, beforeCapture: () -> Unit = {}, content: () -> Unit) {
        content()
        beforeCapture()
        composeRule.waitForIdle()
        composeRule.pinAnimationFrame()
        composeRule.onNodeWithTag(SCREEN_TAG).captureRoboImage("src/test/screenshots/composer_$name.png")
        composeRule.onNodeWithTag(SCREEN_TAG).assertAccessible()
    }

    @Test
    @Category(Screenshot::class)
    fun widePhoneInLight() = render("411_light") { setScreen(VALIDATED) }

    @Test
    @Category(Screenshot::class)
    fun widePhoneInDark() = render("411_dark") { setScreen(VALIDATED, dark = true) }

    @Test
    @Category(Screenshot::class)
    fun smallScreenAtTwoHundredPercentInLight() = render("320_font200_light") { setScreen(VALIDATED, fontScale = 2f, width = 320.dp) }

    @Test
    @Category(Screenshot::class)
    fun anEmptyBrief() = render("empty_411_light") { setScreen(ComposerUiState(limits = LIMITS)) }

    @Test
    @Category(Screenshot::class)
    fun anInvalidNumber() = render("invalid_411_light") { setScreen(INVALID) }

    @Test
    @Category(Screenshot::class)
    fun theLowerFormAfterScrolling() = render(
        "lower_411_light",
        beforeCapture = {
            composeRule.onNodeWithTag(SCREEN_TAG).performTouchInput { swipeUp() }
            composeRule.onNodeWithTag(SCREEN_TAG).performTouchInput { swipeUp() }
        }
    ) { setScreen(VALIDATED) }

    @Test
    @Category(Screenshot::class)
    fun theLimitsAfterScrolling() = render(
        "limits_411_light",
        beforeCapture = { composeRule.onNodeWithText("Calls per hour").performScrollTo() }
    ) { setScreen(VALIDATED) }

    @Test
    @Category(Screenshot::class)
    fun theWarningsAfterScrolling() = render(
        "warnings_411_light",
        beforeCapture = { composeRule.onNodeWithText("agent may not know", substring = true).performScrollTo() }
    ) { setScreen(VALIDATED) }

    @Test
    @Category(Screenshot::class)
    fun theConfirmation() = render("confirmation_411_light") {
        setScreen(VALIDATED.copy(confirming = true))
    }

    @Test
    fun aValidationFailureKeepsTheDaemonsWordsAndCode() {
        setScreen(
            VALIDATED.copy(
                valid = false,
                errors = listOf(
                    ComposerIssue(
                        location = "request",
                        headline = UiText.Literal("the bridge service is not running"),
                        detail = "link_down"
                    )
                )
            )
        )
        composeRule.onNodeWithText("the bridge service is not running").assertExists()
        composeRule.onNodeWithText("Show details").assertExists()
    }

    @Test
    fun anInvalidNumberNamesItsFieldAndTheVerdict() {
        setScreen(INVALID)
        composeRule.onNodeWithText("Check the number").assertExists()
        composeRule.onAllNodesWithText("Emergency numbers can't be called by the agent.").onFirst().assertExists()
        composeRule.onNodeWithText("Fix the number").assertExists()
        // The daemon's own diagnostic stays reachable behind Details.
        composeRule.onNodeWithText("Show details").performScrollTo().performClick()
        composeRule
            .onNodeWithText("emergency numbers cannot be called by the agent")
            .assertExists()
    }

    @Test
    fun anOversizedGoalNamesTheGoalAndKeepsItsLimit() {
        setScreen(
            VALIDATED.copy(
                valid = false,
                errors = listOf(localIssue("goal", "String should have at most 8000 characters"))
            )
        )
        // The merged text field repeats its own supporting text, so match the first occurrence.
        composeRule
            .onAllNodesWithText("The goal is longer than the 8,000-character limit.")
            .onFirst()
            .assertExists()
        composeRule.onNodeWithText("Fix the goal").assertExists()
        composeRule.onNodeWithText("Check the goal").assertExists()
        composeRule.onNodeWithText("Fix the number").assertDoesNotExist()
        // The 8,000-character limit stays visible beside the field it constrains.
        val used = NumberFormat.getIntegerInstance().format(VALIDATED.goal.length)
        composeRule.onAllNodesWithText("$used / 8,000 characters").onFirst().assertExists()
    }

    @Test
    fun aRequestFailureAsksForTheBriefNotTheNumber() {
        setScreen(
            VALIDATED.copy(
                valid = false,
                errors = listOf(
                    ComposerIssue(
                        location = "request",
                        headline = UiText.Literal("the bridge service is not running"),
                        detail = "link_down"
                    )
                )
            )
        )
        // The status pill and the action both read the neutral verdict for a brief nobody checked.
        composeRule.onAllNodesWithText("Check the brief").onFirst().assertExists()
        composeRule.onNodeWithText("Fix the number").assertDoesNotExist()
    }

    @Test
    fun removingAFieldChipRemovesTheField() {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo(limits = LIMITS))
        api.validation = ApiOutcome.Ok(200, ValidationResult(valid = true))
        val viewModel = ComposerViewModel(api)
        viewModel.onFieldDraftChange("reservation_time")
        viewModel.addField()
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(modifier = Modifier.testTag(SCREEN_TAG).fillMaxSize()) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    ComposerScreen(
                        state = state,
                        onBack = {},
                        onDestinationChange = viewModel::onDestinationChange,
                        onGoalChange = viewModel::onGoalChange,
                        onContextChange = viewModel::onContextChange,
                        onConstraintsChange = viewModel::onConstraintsChange,
                        onFieldDraftChange = viewModel::onFieldDraftChange,
                        onAddField = viewModel::addField,
                        onRemoveField = viewModel::removeField,
                        onMaxDurationChange = viewModel::onMaxDurationChange,
                        onRingTimeoutChange = viewModel::onRingTimeoutChange,
                        onReview = viewModel::review,
                        onDismissReview = viewModel::dismissReview,
                        onConfirm = viewModel::confirm
                    )
                }
            }
        }
        composeRule.onNodeWithText("Reservation time").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(viewModel.state.value.resultFields.isEmpty())
    }

    @Test
    fun aValidBriefReachesTheReviewStep() {
        var reviews = 0
        setScreen(VALIDATED, onReview = { reviews++ })
        composeRule.onNodeWithText("Review and place").performClick()
        assertEquals(1, reviews)
    }

    companion object {
        const val SCREEN_TAG = "composer_capture"

        val LIMITS = ApiLimits(
            maxDurationSeconds = 1800,
            ringTimeoutSeconds = 90,
            callsPerHour = 6
        )

        internal val VALIDATED = ComposerUiState(
            destination = "+998 90 123 45 67",
            goal = "Book a table for two on Thursday at 20:00 under Rasulov.",
            context = "The restaurant knows us from the 12th; ask for the corner table if it is free.",
            constraints = "Do not pay a deposit. No later than 21:00.",
            resultFields = listOf("reservation_time", "party_size", "confirmation_code"),
            limits = LIMITS,
            valid = true,
            warnings = listOf(UiText.Literal("the goal is very short; the agent may not know what to accomplish"))
        )

        val INVALID = ComposerUiState(
            destination = "112",
            goal = "Call",
            limits = LIMITS,
            valid = false,
            errors = listOf(localIssue("destination", "emergency numbers cannot be called by the agent"))
        )
    }
}
