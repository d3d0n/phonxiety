package com.phonxiety.bridge.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.component.SheetScaffold
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sheet frame's promises: the title is the pane's name and a heading, the subtitle is the
 * sheet's own words under it, and the content keeps clear of the navigation bar by exactly the
 * inset the frame is handed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SheetScaffoldTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theSheetAnnouncesItsPaneAndPutsTheTitleOnTheHeading() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                SheetScaffold(
                    title = TITLE,
                    subtitle = SUBTITLE,
                    modifier = Modifier.testTag(SHEET),
                    navigationBarInsets = WindowInsets(0)
                ) {
                    Text(text = BODY)
                }
            }
        }
        composeRule.onNodeWithTag(SHEET)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, TITLE))
        composeRule.onNodeWithText(TITLE)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeRule.onNodeWithText(SUBTITLE).assertExists()
        composeRule.onNodeWithText(BODY).assertExists()
    }

    @Test
    fun theContentKeepsClearOfTheNavigationBarByTheGivenInset() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Row {
                    SheetWithInset(tag = "without", bottomInset = 0.dp)
                    SheetWithInset(tag = "with", bottomInset = 48.dp)
                }
            }
        }
        val without = composeRule.onNodeWithTag("without").getUnclippedBoundsInRoot()
        val with = composeRule.onNodeWithTag("with").getUnclippedBoundsInRoot()
        // The only difference between the two frames is the inset, so the sheet grows by it.
        assertEquals(48f, (with.bottom - with.top - (without.bottom - without.top)).value, 0.5f)
        val bodyWith = composeRule.onNodeWithTag("body_with").getUnclippedBoundsInRoot()
        assertEquals(48f, (with.bottom - bodyWith.bottom).value, 0.5f)
    }

    @Test
    fun theContentSitsInsideTheFramePadding() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                SheetScaffold(
                    title = TITLE,
                    modifier = Modifier.testTag(SHEET),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    navigationBarInsets = WindowInsets(0)
                ) {
                    Box(modifier = Modifier.testTag(BODY_TAG).height(24.dp))
                }
            }
        }
        val sheet = composeRule.onNodeWithTag(SHEET).getUnclippedBoundsInRoot()
        val body = composeRule.onNodeWithTag(BODY_TAG).getUnclippedBoundsInRoot()
        assertEquals(12f, (body.left - sheet.left).value, 0.5f)
        assertEquals(4f, (sheet.bottom - body.bottom).value, 0.5f)
    }

    @Test
    fun aSheetWithoutASubtitleHasNoEmptyRow() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                SheetScaffold(
                    title = TITLE,
                    modifier = Modifier.testTag(SHEET),
                    navigationBarInsets = WindowInsets(0)
                ) {
                    Text(text = BODY)
                }
            }
        }
        composeRule.onNodeWithText(SUBTITLE).assertDoesNotExist()
        composeRule.onNodeWithText(BODY).assertExists()
    }

    @Composable
    private fun SheetWithInset(tag: String, bottomInset: Dp) {
        Box(modifier = Modifier.width(180.dp)) {
            SheetScaffold(
                title = TITLE,
                subtitle = SUBTITLE,
                modifier = Modifier.testTag(tag),
                contentPadding = PaddingValues(0.dp),
                navigationBarInsets = WindowInsets(bottom = bottomInset)
            ) {
                Box(modifier = Modifier.testTag("body_$tag").height(24.dp))
            }
        }
    }

    private companion object {
        const val TITLE = "Pair with your computer"
        const val SUBTITLE = "Paste the link from the dashboard, or scan its code."
        const val BODY = "Sheet body"
        const val SHEET = "sheet"
        const val BODY_TAG = "sheet_body"
    }
}
