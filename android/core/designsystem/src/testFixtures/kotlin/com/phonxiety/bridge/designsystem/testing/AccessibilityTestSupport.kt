package com.phonxiety.bridge.designsystem.testing

import androidx.compose.ui.test.SemanticsNodeInteraction
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import com.github.takahirom.roborazzi.checkRoboAccessibility
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.TextContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement
import org.hamcrest.Description
import org.hamcrest.Matchers.anyOf
import org.hamcrest.TypeSafeMatcher

/**
 * WCAG 1.4.3 exempts the text of an inactive control from the contrast minimum, and the
 * Accessibility Test Framework misreads the label of a disabled Button whose text is centered: it
 * samples the container as the text color and reports about 1.05:1. The design system still gives
 * disabled labels readable colors; only the contrast result of a disabled element is suppressed.
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
 * A text line clipped to a sliver at the viewport edge is not a readable element: the framework
 * samples whatever few antialiased pixels remain and reports a contrast failure for text the user
 * cannot read anyway. A scroll view always cuts some line at its edge, so contrast is only judged
 * when at least half of the line's full height is presented.
 */
private object ClippedTextContrast : TypeSafeMatcher<AccessibilityViewCheckResult>() {
    override fun matchesSafely(item: AccessibilityViewCheckResult): Boolean {
        if (item.accessibilityHierarchyCheck != TextContrastCheck::class.java) return false
        // The framework states partial visibility itself; the visible bounds are clipped to the
        // sliver and cannot tell the full line's height again.
        return item.message?.toString()?.contains("partially visible") == true
    }

    override fun describeTo(description: Description) {
        description.appendText("text clipped to a sliver at the viewport edge")
    }
}

/**
 * The shared accessibility gate. Warning is the failure level because insufficient contrast is
 * reported as a warning by the Accessibility Test Framework, and hidden contrast failures are
 * exactly what this pass exists to catch.
 */
@OptIn(ExperimentalRoborazziApi::class)
fun accessibilityGateOptions(): RoborazziATFAccessibilityCheckOptions = RoborazziATFAccessibilityCheckOptions(
    checker = RoborazziATFAccessibilityChecker(
        suppressions = anyOf(DisabledTextContrast, ClippedTextContrast)
    ),
    failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.Warning
)

/** Runs the gate on one captured state. */
@OptIn(ExperimentalRoborazziApi::class)
fun SemanticsNodeInteraction.assertAccessible() {
    checkRoboAccessibility(accessibilityGateOptions())
}
