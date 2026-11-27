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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.model.CallEvent
import com.phonxiety.bridge.model.PartialTurn
import com.phonxiety.bridge.model.TranscriptTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The transcript's rows are keyed by what they are: a final turn by its sequence and a partial by
 * its role. The daemon settles a partial into a final turn in one update — the turn is added and
 * its role's partial is removed together — and the list keeps the row the reader is on where it
 * was; a reader's first visible line does not jump under their eyes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LiveCallTranscriptStabilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aSettledPartialDoesNotMoveTheFirstVisibleRow() {
        val turns = longTurns().take(10)
        val anchor = PartialTurn(role = "assistant", text = ANCHOR_WORDS, at = null)
        val settling = PartialTurn(role = "user", text = SETTLING_WORDS, at = null)
        // Enough rows under the anchor that the list can park it at the top of the viewport
        // instead of clamping at the end, where a change of content height moves everything.
        val extras = List(18) { index -> "speaker$index" }
            .mapIndexed { index, role ->
                PartialTurn(role = role, text = "Line ${index + 1} is still being spoken while the far end finishes", at = null)
            }
        val partials = listOf(anchor, settling) + extras
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
                        onOpenCallScreen = {},
                        onRetryHangUp = {},
                        onOpenDetail = {},
                        nowEpochMs = NOW
                    )
                }
            }
        }

        // Park the list on the first partial: heading 0, turns 1..10, the anchor at 11. The other
        // speaker's partial sits under it, and the turns below give the list enough content to
        // scroll the anchor to the top of the viewport.
        val anchorIndex = 1 + turns.size
        composeRule.onNodeWithTag(TRANSCRIPT_LIST_TAG).performScrollToIndex(anchorIndex)
        composeRule.waitForIdle()
        val listTop = composeRule.onNodeWithTag(TRANSCRIPT_LIST_TAG).getUnclippedBoundsInRoot().top
        val before = composeRule
            .onNode(hasText(ANCHOR_WORDS, substring = true))
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the anchor is the first visible row keyed into the list: ${before.top} vs $listTop",
            before.top - listTop < 40.dp
        )

        // The daemon's own transition: the final turn is added and its role's partial is removed
        // in the same reduced state, which puts the new turn above every partial.
        val settled = reduceCallEvents(
            live,
            listOf(
                CallEvent(
                    type = "transcript",
                    revision = 1,
                    role = "user",
                    text = SETTLED_WORDS,
                    sequence = 11,
                    at = "2026-11-26T10:00:40+00:00"
                )
            ),
            mutableSetOf()
        )
        state.value = settled
        composeRule.waitForIdle()

        assertEquals("the final turn was added", 11, settled.turns.size)
        assertEquals("its role's partial went with it", listOf(anchor) + extras, settled.partials)

        val after = composeRule
            .onNode(hasText(ANCHOR_WORDS, substring = true))
            .getUnclippedBoundsInRoot()
        assertEquals(
            "the row at the top of the viewport kept its offset",
            before.top.value,
            after.top.value,
            0.5f
        )
        assertTrue(
            "the same keyed row is still the first visible row: ${after.top} vs $listTop",
            after.top - listTop < 40.dp
        )
        // Nothing corrected the scroll: the control for a reader parked mid-history is still there.
        composeRule.onNodeWithText("Jump to live").assertExists()
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

        const val ANCHOR_WORDS = "let me check that with the kitchen"
        const val SETTLING_WORDS = "Thursday at eight would be ideal"
        const val SETTLED_WORDS = "Thursday at eight it is, under Rasulov"

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
