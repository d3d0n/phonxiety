package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.component.STACK_AT_FONT_SCALE
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The card's stacking contract at the font-scale breakpoint: where the status sits, that it is
 * rendered once, that the heading keeps its semantics, and that the subtitle never shares the
 * status's width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SectionCardBehaviourTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setCard(
        fontScale: Float,
        width: Dp = 411.dp,
        title: String = TITLE,
        subtitle: String? = SUBTITLE,
        status: (@Composable RowScope.() -> Unit)? = { STATUS() }
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PhonxietyTheme(dynamicColor = false) {
                    Box(modifier = Modifier.testTag(ROOT).width(width)) {
                        SectionCard(title = title, subtitle = subtitle, status = status) {
                            Text(text = CONTENT, modifier = Modifier.testTag(CONTENT_TAG))
                        }
                    }
                }
            }
        }
    }

    private fun topOf(text: String) = composeRule.onNodeWithText(text).getUnclippedBoundsInRoot().top

    private fun boundsOf(text: String) = composeRule.onNodeWithText(text).getUnclippedBoundsInRoot()

    @Test
    fun belowTheBreakpointTheStatusSitsBesideTheTitle() {
        setCard(fontScale = STACK_AT_FONT_SCALE - 0.1f)
        val title = boundsOf(TITLE)
        val status = boundsOf(STATUS_TEXT)
        assertTrue("the status is in the title's row", status.top < title.bottom)
        assertTrue("the status starts right of the title", status.left > title.left)
        assertTrue("the subtitle stays under the row", boundsOf(SUBTITLE).top >= title.bottom)
    }

    @Test
    fun atTheBreakpointTheStatusDropsUnderTheFullWidthHeading() {
        setCard(fontScale = STACK_AT_FONT_SCALE)
        val title = boundsOf(TITLE)
        val subtitle = boundsOf(SUBTITLE)
        val status = boundsOf(STATUS_TEXT)
        assertEquals("the title starts at the content edge", title.left, subtitle.left)
        assertEquals("the status starts at the content edge", title.left, status.left)
        assertTrue("the status is under the subtitle", status.top >= subtitle.bottom)
        assertTrue("the content follows the status", topOf(CONTENT) > status.top)
    }

    @Test
    fun theTitleKeepsHeadingSemanticsWhenStacked() {
        setCard(fontScale = 2f)
        composeRule.onNodeWithText(TITLE)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    }

    @Test
    fun theStatusSlotIsRenderedOnceWhenSideBySide() {
        setCard(fontScale = 1f)
        composeRule.onAllNodesWithText(STATUS_TEXT).assertCountEquals(1)
    }

    @Test
    fun theStatusSlotIsRenderedOnceWhenStacked() {
        setCard(fontScale = 2f)
        composeRule.onAllNodesWithText(STATUS_TEXT).assertCountEquals(1)
    }

    @Test
    fun aNullStatusAddsNoVerticalGap() {
        setCard(fontScale = 2f, status = null)
        val gap = boundsOf(CONTENT).top - boundsOf(SUBTITLE).bottom
        assertEquals("the content follows the subtitle at the card's own spacing", 10f, gap.value, 1f)
    }

    @Test
    fun theSubtitleTakesTheFullContentWidthWhenStacked() {
        // One unbreakable token: the text node reports its whole available width, so any squeeze
        // from the status would show up here.
        setCard(fontScale = 2f, width = 320.dp, subtitle = "x".repeat(120))
        val innerWidthDp = 320f - 2 * 18f
        val subtitle = boundsOf("x".repeat(120))
        assertEquals(innerWidthDp, (subtitle.right - subtitle.left).value, 1f)
    }

    @Test
    fun aLongTitlePlusStatusAtThreeTwentyAndDoubleTextStayApart() {
        // A token wider than the card reports the whole available width; sharing the row with the
        // status would show up as a narrower node.
        setCard(fontScale = 2f, width = 320.dp, title = "y".repeat(120))
        val title = boundsOf("y".repeat(120))
        val subtitle = boundsOf(SUBTITLE)
        val status = boundsOf(STATUS_TEXT)
        assertEquals(320f - 2 * 18f, (title.right - title.left).value, 1f)
        assertTrue("the status is under the heading", status.top >= subtitle.bottom)
        assertTrue("the content follows", boundsOf(CONTENT).top > status.bottom)
    }

    private companion object {
        const val ROOT = "section_card_root"
        const val CONTENT_TAG = "section_card_content"
        const val TITLE = "Link quality"
        const val SUBTITLE = "The grants the bridge needs to keep the link alive without a prompt."
        const val CONTENT = "Uptime 4 min"
        const val STATUS_TEXT = "Direct"

        @Composable
        fun STATUS() {
            StatusPill(label = STATUS_TEXT, tone = StatusTone.POSITIVE)
        }
    }
}
