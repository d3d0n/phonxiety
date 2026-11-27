package com.phonxiety.bridge.feature.calls

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.input.ImeAction
import com.phonxiety.bridge.designsystem.LocalScrollToTop
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.ScrollToTopRegistry
import com.phonxiety.bridge.designsystem.formatPhoneNumber
import com.phonxiety.bridge.model.CallSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the history screen promises beyond its pixels: the search field finishes its own search,
 * every row is one named button, and the section's re-tap returns the list to its first entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CallsScreenBehaviourTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setScreen(
        state: CallsUiState,
        registry: ScrollToTopRegistry? = null
    ) {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                val screen: @Composable () -> Unit = {
                    Surface(color = MaterialTheme.colorScheme.background) {
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
                if (registry == null) {
                    screen()
                } else {
                    CompositionLocalProvider(LocalScrollToTop provides registry) { screen() }
                }
            }
        }
    }

    @Test
    fun theSearchFieldAsksTheKeyboardToSearch() {
        setScreen(CallsUiState(loaded = true))
        val field = composeRule.onNode(hasSetTextAction() and hasText("Search calls", substring = true))
        // The keyboard's own action is Search, not the default Enter of an unconfigured field.
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Search))
        field.performClick()
        field.assertIsFocused()

        // The field handles the action instead of falling through to a newline.
        val handled = composeRule.runOnIdle {
            field.fetchSemanticsNode().config[SemanticsActions.OnImeAction].action?.invoke()
        }
        assertEquals(true, handled)
    }

    @Test
    fun theSearchActionLetsGoOfTheFieldAndHidesTheKeyboard() {
        val focus = RecordingFocusManager()
        val keyboard = RecordingKeyboard()

        dismissSearch(focus, keyboard)

        assertEquals(1, focus.cleared)
        assertEquals(1, keyboard.hidden)
    }

    @Test
    fun aCallRowIsOneButtonWithItsOwnActionName() {
        setScreen(CallsUiState(loaded = true, calls = listOf(summary("call-1"))))
        val number = formatPhoneNumber("+998901234567")
        val row = composeRule.onNode(hasText(number, substring = true) and hasClickAction())
            .fetchSemanticsNode()

        assertEquals(Role.Button, row.config[SemanticsProperties.Role])
        val action = row.config.getOrNull(SemanticsActions.OnClick)
        // The spoken name of the tap is "Open the call", never a bare "click" over a phone number.
        assertEquals("Open the call", action?.label)
    }

    @Test
    fun theSectionsReTapBringsTheFirstCallBack() {
        val registry = ScrollToTopRegistry()
        setScreen(CallsUiState(loaded = true, calls = manyCalls()), registry)

        composeRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("Booking 39"))
        composeRule.waitForIdle()
        // The first row is far enough back that the lazy list no longer composes it.
        composeRule.onNodeWithText("Booking 0").assertDoesNotExist()

        composeRule.runOnIdle { registry.dispatch("calls") }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Booking 0").assertIsDisplayed()
    }

    private fun manyCalls(): List<CallSummary> = (0 until 40).map { index ->
        summary(
            callId = "call-$index",
            destination = "+9989000%05d".format(index),
            summary = "Booking $index"
        )
    }
}

/** Records what the search action asked of the focus system. */
private class RecordingFocusManager : FocusManager {
    var cleared = 0

    override fun clearFocus(force: Boolean) {
        cleared++
    }

    override fun moveFocus(focusDirection: FocusDirection): Boolean = false
}

/** Records what the search action asked of the keyboard. */
private class RecordingKeyboard : SoftwareKeyboardController {
    var hidden = 0

    override fun show() = Unit

    override fun hide() {
        hidden++
    }
}
