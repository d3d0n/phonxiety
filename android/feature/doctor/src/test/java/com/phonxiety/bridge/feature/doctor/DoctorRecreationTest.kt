package com.phonxiety.bridge.feature.doctor

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.root.ModuleState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The fix question on a real activity that is destroyed and rebuilt. The pending repair is a value
 * in the view model's state, so it outlives the composition exactly as it outlives a rotation; a
 * screen that kept the question in a composable-local `remember` would come back without it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DoctorRecreationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val viewModel = DoctorViewModel(
        source = FakeDoctorSource(TestData.snapshot(module = ModuleState.Absent)),
        bridgeState = FakeBridgeState(TestData.bridge()),
        pairing = FakePairing(),
        daemonApi = FakeDaemonApi().apply { doctorOutcome = ApiOutcome.Ok(200, TestData.report()) },
        changeLog = FakeChangeLog(),
        serviceControl = FakeServiceControl(),
        // The first check pass runs where the construction happens, so the rows are there at once.
        io = UnconfinedTestDispatcher()
    )

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun theFixQuestionSurvivesActivityRecreation() {
        val content: @Composable () -> Unit = {
            PhonxietyTheme(dynamicColor = false) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                DoctorScreen(
                    state = state,
                    callbacks = DoctorCallbacks(
                        onFix = viewModel::requestFix,
                        onConfirm = viewModel::confirmFix,
                        onDismissConfirm = viewModel::dismissConfirm
                    )
                )
            }
        }
        composeRule.activity.setContent { content() }

        composeRule.waitUntil(OFFERS_A_FIX) {
            composeRule.onAllNodesWithContentDescription("Reinstall the module", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Reinstall the module", substring = true)
            .onFirst()
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(HAS_QUESTION) {
            composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.activityRule.scenario.recreate()
        composeRule.activityRule.scenario.onActivity { activity -> activity.setContent { content() } }

        composeRule.waitUntil(HAS_QUESTION) {
            composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Reinstall the module?").assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    private companion object {
        const val OFFERS_A_FIX = 5_000L
        const val HAS_QUESTION = 5_000L
    }
}
