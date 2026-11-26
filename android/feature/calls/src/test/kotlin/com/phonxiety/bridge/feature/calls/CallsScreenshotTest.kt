package com.phonxiety.bridge.feature.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.data.calls.CallFilter
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.designsystem.formatPhoneNumber
import com.phonxiety.bridge.designsystem.testing.assertAccessible
import com.phonxiety.bridge.designsystem.testing.pinAnimationFrame
import com.phonxiety.bridge.model.CallSummary
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The history list on the decision-10 matrix: 411 dp at 100 %, 320 dp at 200 %, light, dark and one
 * dynamic-color case, plus the empty, filtered and offline states and the end of the list. Every
 * capture runs the accessibility gate first.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CallsScreenshotTest {
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
        state: CallsUiState,
        dark: Boolean = false,
        dynamicColor: Boolean = false,
        fontScale: Float = 1f,
        width: Dp = 411.dp,
        onOutcomeChange: (String?) -> Unit = {},
        onFarEndChange: (String?) -> Unit = {},
        onClearFilters: () -> Unit = {}
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale)
            ) {
                PhonxietyTheme(darkTheme = dark, dynamicColor = dynamicColor) {
                    Box(
                        modifier = Modifier
                            .testTag(SCREEN_TAG)
                            .width(width)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        CallsScreen(
                            state = state,
                            onRefresh = {},
                            onLoadMore = {},
                            onQueryChange = {},
                            onOutcomeChange = onOutcomeChange,
                            onFarEndChange = onFarEndChange,
                            onClearFilters = onClearFilters,
                            onOpenCall = {},
                            onNewCall = {}
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
        composeRule.onNodeWithTag(SCREEN_TAG).captureRoboImage("src/test/screenshots/calls_$name.png")
        composeRule.onNodeWithTag(SCREEN_TAG).assertAccessible()
    }

    @Test
    fun widePhoneInLight() = render("411_light") { setScreen(filled()) }

    @Test
    fun widePhoneInDark() = render("411_dark") { setScreen(filled(), dark = true) }

    @Test
    fun widePhoneInDynamicColor() = render("411_dynamic") { setScreen(filled(), dynamicColor = true) }

    @Test
    fun smallScreenAtTwoHundredPercentInLight() = render("320_font200_light") {
        setScreen(filled(), fontScale = 2f, width = 320.dp)
    }

    @Test
    fun smallScreenAtTwoHundredPercentInDark() = render("320_font200_dark") {
        setScreen(filled(), dark = true, fontScale = 2f, width = 320.dp)
    }

    @Test
    fun noCallsYet() = render("empty_411_light") { setScreen(CallsUiState(loaded = true)) }

    @Test
    fun nothingMatchesTheFilter() = render("empty_filtered_411_light") {
        setScreen(
            CallsUiState(loaded = true, filter = CallFilter(outcome = "no_answer", query = "pizza"))
        )
    }

    @Test
    fun theOfflineCopy() = render("offline_411_light") {
        setScreen(filled().copy(offline = true, error = CallsFailure(UiText.Literal("The bridge service isn't running."), "link_down")))
    }

    @Test
    fun theEndOfTheHistory() = render(
        "lower_411_light",
        beforeCapture = {
            composeRule.onNodeWithText("You've reached the end of the history.").performScrollTo()
            composeRule.waitForIdle()
        }
    ) { setScreen(filled()) }

    @Test
    fun outcomeChipsReadAsWords() {
        setScreen(filled())
        // "Declined" and "Disconnected" exist only as filter chips in this sample.
        composeRule.onNodeWithText("Declined").assertExists()
        composeRule.onNodeWithText("Disconnected").assertExists()
        // The daemon's own token is never the label.
        composeRule.onNodeWithText("no answer").assertDoesNotExist()
        composeRule.onNodeWithText("disconnected").assertDoesNotExist()
    }

    @Test
    fun aChipReportsTheDaemonsOwnKey() {
        var requested: String? = "unset"
        setScreen(filled(), onOutcomeChange = { requested = it })
        composeRule.onNodeWithText("Declined").performClick()
        assertEquals("declined", requested)
    }

    @Test
    fun theFilterChipsWrapInsteadOfRunningOffTheEdge() {
        setScreen(filled(), fontScale = 2f, width = 320.dp)
        // A label cut at the edge reads as a broken control; the chips are the only nodes these
        // words exist on in this sample.
        for (label in listOf("Declined", "Disconnected")) {
            val bounds = composeRule.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue("$label stays inside the screen", bounds.right.value <= 320f)
        }
    }

    @Test
    fun clearFiltersStaysReachableBeforeTheListLoads() {
        // A filtered list that never loaded (here the refresh failed offline) has no empty state to
        // carry the action, so the filter row keeps it.
        setScreen(CallsUiState(filter = CallFilter(outcome = "declined"), loaded = false, offline = true))
        composeRule.onAllNodesWithText("Clear filters").assertCountEquals(1)
    }

    @Test
    fun aLoadedFilteredEmptyListOffersClearFiltersOnce() {
        setScreen(CallsUiState(filter = CallFilter(outcome = "declined"), loaded = true))
        composeRule.onAllNodesWithText("Clear filters").assertCountEquals(1)
    }

    @Test
    fun aLoadedFilteredListKeepsClearFiltersInTheRow() {
        setScreen(filled().copy(filter = CallFilter(outcome = "no_answer")))
        composeRule.onAllNodesWithText("Clear filters").assertCountEquals(1)
    }

    @Test
    fun theFarEndRowSaysWhatItFilters() {
        setScreen(filled())
        composeRule.onNodeWithText("Reached").assertExists()
        composeRule.onNodeWithText("Person").assertExists()
    }

    @Test
    fun aRunningCallShowsOneStateAndNoRawPhase() {
        setScreen(filled())
        // One pill for the live row — the phase, once — and never the raw "active" token.
        composeRule.onAllNodesWithText("Live").assertCountEquals(1)
        composeRule.onNodeWithText("active").assertDoesNotExist()
        composeRule.onNodeWithText("In progress").assertDoesNotExist()
    }

    @Test
    fun everyInternationalNumberKeepsItsCountry() {
        setScreen(filled())
        for (e164 in listOf("+998901234567", "+14155550123", "+442071234567")) {
            composeRule.onAllNodesWithText(formatPhoneNumber(e164), substring = true)
                .onFirst()
                .assertExists()
        }
        // The list is the owner's own history: nothing is masked here.
        composeRule.onNodeWithText("··", substring = true).assertDoesNotExist()
    }

    @Test
    fun aSummaryPreviewStopsAtTwoLinesWithAnEllipsis() {
        val long = "The agent held the table for two on Thursday at eight and confirmed the window " +
            "seat, then read the booking reference back twice"
        setScreen(CallsUiState(loaded = true, calls = listOf(summary("call-long", summary = long))))
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(long, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it.invoke(layouts) }
        // Two lines, and the reader is told the rest is elsewhere: the preview truncates with an
        // ellipsis, never a silent cut.
        assertEquals(2, layouts.single().lineCount)
        assertEquals(TextOverflow.Ellipsis, layouts.single().layoutInput.overflow)
        assertTrue(layouts.single().hasVisualOverflow)
    }

    @Test
    fun aFailedRefreshKeepsTheCodeBehindDetails() {
        setScreen(
            CallsUiState(
                loaded = true,
                calls = filled().calls,
                error = CallsFailure(UiText.Literal("The bridge didn't answer."), "link_down")
            )
        )
        composeRule.onNodeWithText("The bridge didn't answer.").assertExists()
        // The code is not concatenated into the sentence.
        composeRule.onNodeWithText("link_down", substring = true).assertDoesNotExist()
    }

    private companion object {
        const val SCREEN_TAG = "calls_capture"

        fun filled(): CallsUiState = CallsUiState(
            loaded = true,
            calls = SAMPLES,
            farEnds = listOf("person", "ivr")
        )

        val SAMPLES: List<CallSummary> = listOf(
            summary(
                "call-live",
                state = "active",
                callOutcome = null,
                summary = null,
                createdAt = 1_764_000_000.0,
                live = true,
                marks = 1,
                hasRecording = false
            ),
            summary("call-1", createdAt = 1_763_900_000.0, marks = 1),
            summary(
                "call-2",
                destination = "+14155550123",
                state = "ringing",
                callOutcome = "no_answer",
                summary = "The line rang out",
                farEnd = "ivr",
                createdAt = 1_763_800_000.0,
                hasRecording = false
            ),
            summary(
                "call-3",
                destination = "+442071234567",
                state = "ended",
                callOutcome = "cancelled",
                summary = "Canceled before the far end answered",
                farEnd = null,
                createdAt = 1_763_700_000.0,
                hasRecording = false
            )
        )
    }
}
