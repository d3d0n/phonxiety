package com.phonxiety.bridge.feature.onboarding

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.testing.assertAccessible
import com.phonxiety.bridge.designsystem.testing.pinAnimationFrame
import kotlin.math.ceil

/**
 * Records and checks a screen whose content is taller than the recorded phone-height viewport.
 * The page is regrown to the height its own content needs before the gate runs, so no line is ever
 * cut by a viewport edge: the framework would sample the wrong background there, and suppressing
 * that result would also hide a real contrast failure on the same line. The resulting image covers
 * every section in one unsuppressed capture.
 *
 * @param pageHeightDp sets the height the screen is composed with, in dp.
 */
@OptIn(ExperimentalRoborazziApi::class)
internal fun ComposeContentTestRule.captureWholeScreen(
    screenTag: String,
    footerText: String,
    pageHeightDp: (Int) -> Unit,
    name: String
) {
    // Tall enough for the longest state, so the footer's own position is never constrained.
    pageHeightDp(MEASUREMENT_DP)
    waitForIdle()
    val screen = onNodeWithTag(screenTag).fetchSemanticsNode()
    val footer = onNodeWithText(footerText, substring = true).fetchSemanticsNode()
    val scroll = onNode(hasScrollAction()).fetchSemanticsNode()
    val density = footer.layoutInfo.density.density
    // The screen's bottom padding, plus the sticky action bar that sits below the scroller.
    val bottomPaddingPx = 20f * density
    val actionBarPx = screen.size.height - scroll.size.height
    val contentHeightDp = ceil((footer.boundsInRoot.bottom + bottomPaddingPx + actionBarPx) / density).toInt()
    pageHeightDp(contentHeightDp)
    waitForIdle()
    onNodeWithTag(screenTag).assertAccessible()
    pinAnimationFrame()
    onNodeWithTag(screenTag).captureRoboImage(name)
}

private const val MEASUREMENT_DP = 4000
