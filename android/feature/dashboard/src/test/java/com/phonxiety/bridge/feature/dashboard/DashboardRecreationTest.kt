package com.phonxiety.bridge.feature.dashboard

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.model.BridgeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The unpair question on a real activity that is destroyed and rebuilt. The ViewModel outlives the
 * composition exactly as a retained ViewModel outlives a rotation, and the dialog is drawn from
 * its state alone: nothing about the question is remembered inside the screen, so a screen that
 * went back to a composable-local `remember` would come back without it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DashboardRecreationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theUnpairQuestionSurvivesActivityRecreation() {
        val viewModel = DashboardViewModel(
            context,
            FakeBridgeStateRepository(BridgeState()),
            FakePairingRepository(pairedNow = true)
        )
        val content: @Composable () -> Unit = {
            PhonxietyTheme(dynamicColor = false) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                DashboardScreen(
                    state = state,
                    onOpenSetup = {},
                    onOpenDoctor = {},
                    onNewCall = {},
                    onOpenLiveCall = {},
                    onRunLinkTest = {},
                    onOpenUpdates = {},
                    onRequestUnpair = viewModel::requestUnpair,
                    onDismissUnpair = viewModel::dismissUnpair,
                    onConfirmUnpair = viewModel::unpair
                )
            }
        }
        composeRule.activity.setContent { content() }

        composeRule.waitUntil(ASKS_FOR_UNPAIR) {
            composeRule.onAllNodesWithText("Unpair").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Unpair").performScrollTo().performClick()
        composeRule.waitUntil(HAS_QUESTION) {
            composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.activityRule.scenario.recreate()
        composeRule.activityRule.scenario.onActivity { activity -> activity.setContent { content() } }

        composeRule.waitUntil(HAS_QUESTION) {
            composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Unpair this phone?").assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    private companion object {
        const val ASKS_FOR_UNPAIR = 5_000L
        const val HAS_QUESTION = 5_000L
    }
}
