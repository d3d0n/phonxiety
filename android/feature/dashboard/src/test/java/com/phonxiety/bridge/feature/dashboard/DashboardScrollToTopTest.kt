package com.phonxiety.bridge.feature.dashboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.phonxiety.bridge.designsystem.LocalScrollToTop
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.ScrollToTopRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The dashboard's own half of the re-tap contract: it registers its scroll action under the key
 * the host dispatches, and dispatching with a provided registry brings the first item back into
 * view. Without the registration the dispatch would be a no-op and the header would stay scrolled
 * away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DashboardScrollToTopTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dispatchingTheSectionKeyBringsTheHeaderBackIntoView() {
        val registry = ScrollToTopRegistry()
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalScrollToTop provides registry) {
                    DashboardScreen(
                        state = DashboardUiState(bridge = DashboardSamples.direct, paired = true),
                        onOpenSetup = {},
                        onOpenDoctor = {},
                        onNewCall = {},
                        onOpenLiveCall = {},
                        onRunLinkTest = {},
                        onOpenUpdates = {},
                        onRequestUnpair = {},
                        onDismissUnpair = {},
                        onConfirmUnpair = {},
                        nowEpochMs = DashboardSamples.now
                    )
                }
            }
        }

        composeRule.onNode(hasScrollAction())
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 4_000f) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Bridge control").assertIsNotDisplayed()

        composeRule.runOnIdle { registry.dispatch(DASHBOARD_SCROLL_TO_TOP_KEY) }

        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithText("Bridge control").assertIsDisplayed() }.isSuccess
        }
        composeRule.onNodeWithText("Bridge control").assertIsDisplayed()
    }
}
