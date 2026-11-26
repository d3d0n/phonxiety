package com.phonxiety.bridge.feature.livecall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.model.TranscriptTurn
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An unconfirmed end explains itself on arrival. The message is the second row of the transcript,
 * so a reader who was following the newest turn must be shown it; while it is on screen the list
 * does not follow the newest turn, and manual scrolling keeps working afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LiveCallUnknownRevealTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun anAlreadyUnconfirmedCallShowsTheMessageAndItsAction() {
        setScreen(
            getState = {
                longCall().copy(
                    state = "unknown",
                    ended = true,
                    statusUnknownError = UNCONFIRMED_REASON
                )
            }
        )
        // No scroll before the assertion: the reveal is the screen's own.
        composeRule.onNodeWithText("Call status unknown").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh status").assertIsDisplayed()
    }

    @Test
    fun aFollowingListTurnsToTheMessageAndStaysThere() {
        val turns = longTurns()
        val live = longCall().copy(turns = turns)
        val state = mutableStateOf(live)
        setScreen(getState = { state.value })

        // Follow the newest turn: the last row of the list is at the bottom of the viewport.
        composeRule.onNodeWithTag(TRANSCRIPT_LIST_TAG).performScrollToIndex(turns.size + 1)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(lastTurnText(turns)).assertIsDisplayed()

        state.value = live.copy(state = "unknown", ended = true, statusUnknownError = UNCONFIRMED_REASON)
        composeRule.waitForIdle()

        // The message is brought into view instead of the list staying on the newest line.
        composeRule.onNodeWithText("Call status unknown").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh status").assertIsDisplayed()
        composeRule.onNodeWithText(lastTurnText(turns)).assertDoesNotExist()

        // The reader can still scroll, and the reveal never runs again to pull the list back.
        composeRule.onNodeWithTag(TRANSCRIPT_LIST_TAG).performScrollToIndex(turns.size + 1)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(lastTurnText(turns)).assertIsDisplayed()
        composeRule.onNodeWithText("Call status unknown").assertDoesNotExist()
    }

    /** 30 turns: several viewports of transcript on a 320 dp wide phone. */
    private fun longTurns(): List<TranscriptTurn> = List(30) { index ->
        TranscriptTurn(
            sequence = index + 1L,
            role = if (index % 2 == 0) "assistant" else "user",
            text = "Turn ${index + 1}: ${turnPhrases[index % turnPhrases.size]}",
            at = 1_764_000_006.0 + index,
            finalTurn = true
        )
    }

    private fun lastTurnText(turns: List<TranscriptTurn>): String = turns.last().text.orEmpty()

    private fun longCall(): LiveCallUiState = LiveCallSamples.live.copy(
        turns = longTurns(),
        partials = emptyList()
    )

    private fun setScreen(getState: () -> LiveCallUiState, width: Dp = 320.dp) {
        composeRule.setContent {
            PhonxietyTheme {
                Box(
                    modifier = Modifier
                        .testTag(SCREEN_TAG)
                        .width(width)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    LiveCallScreen(
                        state = getState(),
                        onBack = {},
                        onInstructionChange = {},
                        onSendInstruction = {},
                        onTogglePause = {},
                        onSummarize = {},
                        onDtmf = {},
                        onMark = {},
                        onHangUp = {},
                        onReload = {},
                        onOpenDetail = {},
                        nowEpochMs = NOW
                    )
                }
            }
        }
    }

    private companion object {
        const val SCREEN_TAG = "live_call_unknown_reveal"
        const val NOW = 1_764_000_074_000L
    }
}

private const val UNCONFIRMED_REASON = "hangup_unconfirmed: the phone did not answer"

/** Enough words per turn that no two rows read alike. */
private val turnPhrases = listOf(
    "the table is confirmed for Thursday at eight",
    "we have a window seat requested as well",
    "could you repeat the name on the reservation",
    "the name is Rasulov, spelled as you heard it",
    "one more question about parking at the door",
    "the side street has spaces after seven",
    "do you take the card on arrival or in advance",
    "either way works, nothing is charged today"
)
