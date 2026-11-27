package com.phonxiety.bridge.feature.livecall

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.CallEventsSource
import com.phonxiety.bridge.data.LiveCallFeed
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.model.CallId
import com.phonxiety.bridge.model.DaemonInfo
import com.phonxiety.bridge.model.VoiceControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hang-up confirmation on a real activity. The confirmation is opened through the
 * ViewModel's own callback, the host activity is destroyed and rebuilt with the ViewModel kept by
 * its store, and the dialog is still up afterwards: nothing about the question lives in
 * composition memory. Confirming then ends the call exactly once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LiveCallRecreationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeDaemonApi().apply {
        info = ApiOutcome.Ok(200, DaemonInfo(voiceControls = VoiceControls(hangup = true)))
        callSnapshot = ApiOutcome.Ok(200, snapshot(state = "active"))
        hangup = ApiOutcome.Ok(200, snapshot(state = "ending"))
    }

    /** The feed only exists so the screen has its shared stream; this call ends through hang-up. */
    private val feed = LiveCallFeed(
        CallEventsSource { callId, since, waitSeconds -> api.events(callId, since, waitSeconds) },
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    private val factory = viewModelFactory {
        initializer { LiveCallViewModel(api, FakeBridgeState(), FakeCallScreenOpener(), feed) }
    }

    @Test
    fun theConfirmationOpenedForTheCallOutlivesRecreationAndHangsUpOnce() {
        val viewModel = retainedViewModel(composeRule.activity)
        val content: @Composable () -> Unit = {
            PhonxietyTheme(dynamicColor = false) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    LiveCallScreen(
                        state = state,
                        onBack = {},
                        onInstructionChange = {},
                        onSendInstruction = {},
                        onTogglePause = {},
                        onSummarize = {},
                        onDtmf = {},
                        onMark = {},
                        onHangUp = viewModel::requestHangUp,
                        onConfirmHangUp = viewModel::confirmHangUp,
                        onDismissHangUp = viewModel::dismissHangUp,
                        onReload = viewModel::reload,
                        onOpenCallScreen = viewModel::openCallScreen,
                        onRetryHangUp = viewModel::hangUp,
                        onOpenDetail = {}
                    )
                }
            }
        }
        composeRule.activity.setContent { content() }
        viewModel.bind(CallId(CALL_ID))
        composeRule.waitUntil(TIMEOUT_MS) { viewModel.state.value.loaded }

        viewModel.requestHangUp()
        composeRule.waitUntil(TIMEOUT_MS) { viewModel.state.value.pendingHangUp }
        composeRule.onNodeWithText("Hang up now?").assertExists()

        composeRule.activityRule.scenario.recreate()
        var recreated: LiveCallViewModel? = null
        composeRule.activityRule.scenario.onActivity { activity ->
            recreated = retainedViewModel(activity)
            activity.setContent { content() }
        }

        assertSame("the call state is the activity's retained ViewModel", viewModel, recreated)
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Hang up now?").assertExists()
        composeRule.onNodeWithText("Keep talking").assertExists()

        composeRule.onAllNodesWithText("Hang up").onLast().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(TIMEOUT_MS) { api.hangupCalls == 1 }
        composeRule.waitForIdle()

        assertEquals("confirming ends the call exactly once", 1, api.hangupCalls)
        assertEquals("the pending question is consumed", false, viewModel.state.value.pendingHangUp)
    }

    private fun retainedViewModel(activity: ComponentActivity): LiveCallViewModel = ViewModelProvider(activity, factory)[LiveCallViewModel::class.java]

    private companion object {
        const val CALL_ID = "call-2026-11-26"
        const val TIMEOUT_MS = 5_000L
    }
}
