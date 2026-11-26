package com.phonxiety.bridge.feature.onboarding

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.checkRoboAccessibility
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.TextContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement
import kotlin.math.ceil
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher

/**
 * WCAG 1.4.3 exempts the text of an inactive control from the contrast minimum, and the
 * Accessibility Test Framework misreads the label of a disabled Button whose text is centred: it
 * samples the container as the text colour and reports about 1.05:1. The design system still gives
 * disabled labels readable colours; only the contrast result of a disabled element is suppressed.
 */
private object DisabledTextContrast : TypeSafeMatcher<AccessibilityViewCheckResult>() {
    override fun matchesSafely(item: AccessibilityViewCheckResult): Boolean {
        val contrast = item.accessibilityHierarchyCheck == TextContrastCheck::class.java
        return contrast && item.element.insideDisabledControl()
    }

    override fun describeTo(description: Description) {
        description.appendText("text contrast of a disabled control")
    }
}

/** The flagged node is the label inside the Button, so the disabled state sits on an ancestor. */
private fun ViewHierarchyElement?.insideDisabledControl(): Boolean {
    var node = this
    while (node != null) {
        if (!node.isEnabled) return true
        node = node.parentView
    }
    return false
}

/**
 * The shared accessibility gate. Warning is the failure level because insufficient contrast is
 * reported as a warning by the Accessibility Test Framework, and hidden contrast failures are
 * exactly what this pass exists to catch.
 */
@OptIn(ExperimentalRoborazziApi::class)
private fun accessibilityGateOptions(): RoborazziATFAccessibilityCheckOptions = RoborazziATFAccessibilityCheckOptions(
    checker = RoborazziATFAccessibilityChecker(suppressions = DisabledTextContrast),
    failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.Warning
)

/** Runs the gate on one captured state. */
@OptIn(ExperimentalRoborazziApi::class)
internal fun SemanticsNodeInteraction.assertAccessible() {
    checkRoboAccessibility(accessibilityGateOptions())
}

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
    onNodeWithTag(screenTag).captureRoboImage(name)
}

private const val MEASUREMENT_DP = 4000
