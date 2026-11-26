package com.phonxiety.bridge.feature.livecall

import androidx.compose.ui.test.SemanticsNodeInteraction
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import com.github.takahirom.roborazzi.checkRoboAccessibility
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.TextContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher

/**
 * WCAG 1.4.3 exempts the text of an inactive control from the contrast minimum, and the
 * Accessibility Test Framework misreads the label of a disabled Button whose text is centred: it
 * samples the container as the text colour. This is the same suppression the designsystem's shared
 * gate applies, kept in step with it.
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

/** The flagged node is the label inside the control, so the disabled state sits on an ancestor. */
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
