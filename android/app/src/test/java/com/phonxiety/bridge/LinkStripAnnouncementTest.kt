package com.phonxiety.bridge

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.model.PathKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The strip's announcement contract: the status indicator is the polite live region, so a change
 * of connection status is announced, while the round trip sampled beside it is only redrawn and
 * never triggers an announcement of its own.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LinkStripAnnouncementTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun theLiveRegionAnnouncesStatusChangesButNotRoundTripSamples() {
        val state = mutableStateOf(
            LinkStripState.Visible(LinkSummary(LinkLevel.UP, PathKind.RELAY, rttMs = 120))
        )
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContentView(
            ComposeView(activity).apply {
                setContent {
                    PhonxietyTheme(dynamicColor = false) {
                        LinkStatusStrip(state = state.value, topInset = 0.dp)
                    }
                }
            }
        )
        composeRule.waitForIdle()

        val live = composeRule.onNode(isPoliteLiveRegion())
        val relayTexts = live.fetchSemanticsNode().config[SemanticsProperties.Text]
        assertEquals(1, relayTexts.size)

        // The round trip is on screen but outside the live region, so it is not what is announced.
        composeRule.onAllNodesWithText("120", substring = true).assertCountEquals(1)

        composeRule.runOnIdle {
            state.value = LinkStripState.Visible(LinkSummary(LinkLevel.UP, PathKind.RELAY, rttMs = 999))
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("999", substring = true).assertCountEquals(1)
        assertEquals(
            "a round-trip sample changed what the live region announces",
            relayTexts,
            live.fetchSemanticsNode().config[SemanticsProperties.Text]
        )

        composeRule.runOnIdle {
            state.value = LinkStripState.Visible(LinkSummary(LinkLevel.CONNECTING))
        }
        composeRule.waitForIdle()
        assertNotEquals(
            "a connection-status change was not announced",
            relayTexts,
            live.fetchSemanticsNode().config[SemanticsProperties.Text]
        )
    }

    private fun isPoliteLiveRegion() = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
}
