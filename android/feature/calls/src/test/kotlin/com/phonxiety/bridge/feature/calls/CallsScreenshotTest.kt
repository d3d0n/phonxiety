package com.phonxiety.bridge.feature.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.data.calls.CallFilter
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.model.CallSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The history list: empty, filled, and the offline copy that must not look like a fresh page. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CallsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, state: CallsUiState) {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    CallsScreen(
                        state = state,
                        onRefresh = {},
                        onLoadMore = {},
                        onQueryChange = {},
                        onOutcomeChange = {},
                        onFarEndChange = {},
                        onClearFilters = {},
                        onOpenCall = {},
                        onNewCall = {}
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/calls_$name.png")
    }

    @Test
    fun empty() = capture(
        "empty",
        CallsUiState(loaded = true)
    )

    @Test
    fun emptyWithFilters() = capture(
        "empty_filtered",
        CallsUiState(loaded = true, filter = CallFilter(outcome = "no_answer", query = "pizza"))
    )

    @Test
    fun filled() = capture("filled", CallsUiState(loaded = true, calls = SAMPLES, farEnds = listOf("Bella Napoli")))

    @Test
    fun offline() = capture(
        "offline",
        CallsUiState(
            loaded = true,
            calls = SAMPLES,
            offline = true,
            error = "the bridge service is not running (link_down)"
        )
    )

    private companion object {
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
                destination = "+998977654321",
                callOutcome = "no_answer",
                summary = "The line rang out",
                farEnd = null,
                createdAt = 1_763_800_000.0,
                hasRecording = false
            ),
            summary(
                "call-3",
                destination = "+998781234567",
                callOutcome = "busy",
                summary = "Busy signal twice",
                farEnd = "Pizza Roma",
                createdAt = 1_763_700_000.0,
                hasRecording = false
            )
        )
    }
}
