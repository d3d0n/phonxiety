package com.phonxiety.bridge.feature.livecall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.model.PartialTurn
import com.phonxiety.bridge.model.TranscriptTurn
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The transcript's rows are keyed by what they are: a final turn by its sequence and a partial by
 * its role. When the daemon settles a partial into a final turn, the list keeps every row where it
 * was; a reader's first visible line does not jump under their eyes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LiveCallTranscriptStabilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aPartialBecomingAFinalTurnDoesNotMoveTheFirstVisibleRow() {
        val turns = longTurns().take(10)
        val partials = List(25) { index ->
            PartialTurn(role = "speaker$index", text = "Partial line ${index + 1} is still being spoken", at = null)
        }
        val live = LiveCallSamples.live.copy(turns = turns, partials = partials)
        val state = mutableStateOf(live)
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(
                    modifier = Modifier
                        .testTag(SCREEN_TAG)
                        .width(320.dp)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    LiveCallScreen(
                        state = state.value,
                        onBack = {},
                        onInstructionChange = {},
                        onSendInstruction = {},
                        onTogglePause = {},
                        onSummarize = {},
                        onDtmf = {},
                        onMark = {},
                        onHangUp = {},
                        onConfirmHangUp = {},
                        onDismissHangUp = {},
                        onReload = {},
                        onOpenDetail = {},
                        nowEpochMs = NOW
                    )
                }
            }
        }

        // Park the list on the first partial: heading 0, turns 1..10, the partial is index 11 and
        // the partials below it give the list enough content to scroll it to the top.
        composeRule.onNodeWithTag(TRANSCRIPT_LIST_TAG).performScrollToIndex(turns.size + 1)
        composeRule.waitForIdle()
        val firstPartial = partials.first()
        val before = composeRule
            .onNode(hasText(firstPartial.text.orEmpty(), substring = true))
            .getUnclippedBoundsInRoot()

        // A record snapshot can carry the settled turn while the partial feed still shows the
        // words: the turn is inserted above the row being read.
        val settled = TranscriptTurn(
            sequence = 11,
            role = firstPartial.role,
            text = "The line settles into a numbered turn",
            at = 1_764_000_040.0,
            finalTurn = true
        )
        state.value = live.copy(turns = turns + settled)
        composeRule.waitForIdle()

        val during = composeRule
            .onNode(hasText(firstPartial.text.orEmpty(), substring = true))
            .getUnclippedBoundsInRoot()
        assertEquals("the row the reader was on did not move", before.top.value, during.top.value, 0.5f)

        // The partial's own clear completes the conversion; the numbered turn is what remains.
        state.value = live.copy(turns = turns + settled, partials = partials.drop(1))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TRANSCRIPT_LIST_TAG).performScrollToIndex(turns.size + 1)
        composeRule.waitForIdle()
        composeRule.onNode(hasText(settled.text.orEmpty(), substring = true)).assertExists()
    }

    private fun longTurns(): List<TranscriptTurn> = List(30) { index ->
        TranscriptTurn(
            sequence = index + 1L,
            role = if (index % 2 == 0) "assistant" else "user",
            text = "Turn ${index + 1}: ${phrases[index % phrases.size]}",
            at = 1_764_000_006.0 + index,
            finalTurn = true
        )
    }

    private companion object {
        const val SCREEN_TAG = "live_call_transcript_stability"
        const val NOW = 1_764_000_074_000L

        const val PARTIAL_WORDS = "let me check that with the kitchen"

        val PARTIAL = PartialTurn(role = "user", text = PARTIAL_WORDS, at = null)

        val phrases = listOf(
            "the table is confirmed for Thursday at eight",
            "we have a window seat requested as well",
            "could you repeat the name on the reservation",
            "the name is Rasulov, spelled as you heard it",
            "one more question about parking at the door",
            "the side street has spaces after seven",
            "do you take the card on arrival or in advance",
            "either way works, nothing is charged today"
        )
    }
}
