package com.phonxiety.bridge.feature.lab

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
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The test-call question on a real activity that is destroyed and rebuilt. The pending question is
 * a value in the view model's state, so it outlives the composition exactly as it outlives a
 * rotation; a screen that kept it in a composable-local `remember` would come back without it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LabRecreationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val viewModel = LabViewModel(
        api = FakeDaemonApi(),
        bridgeState = FakeBridgeStateRepository(),
        phonePreferences = FakePhonePreferences(),
        lanPathCheck = FakeLanPathCheck(),
        context = context
    )

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun theTestCallQuestionSurvivesActivityRecreation() {
        viewModel.setTestNumber("+1 (202) 555-0123")
        val content: @Composable () -> Unit = {
            PhonxietyTheme(dynamicColor = false) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                LabScreen(
                    state = state,
                    callbacks = LabCallbacks(
                        onRequestTestCall = viewModel::requestTestCall,
                        onDismissTestCall = viewModel::dismissTestCall,
                        onConfirmTestCall = viewModel::confirmTestCall
                    )
                )
            }
        }
        composeRule.activity.setContent { content() }

        composeRule.waitUntil(ASKS_FOR_A_CALL) {
            composeRule.onAllNodesWithText("Place test call").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Place test call").performScrollTo().performClick()
        composeRule.waitUntil(HAS_QUESTION) {
            composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.activityRule.scenario.recreate()
        composeRule.activityRule.scenario.onActivity { activity -> activity.setContent { content() } }

        composeRule.waitUntil(HAS_QUESTION) {
            composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Place the test call?").assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    private companion object {
        const val ASKS_FOR_A_CALL = 5_000L
        const val HAS_QUESTION = 5_000L
    }
}
