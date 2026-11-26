package com.phonxiety.bridge.designsystem

import androidx.compose.ui.test.SemanticsNodeInteraction
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import com.github.takahirom.roborazzi.checkRoboAccessibility

/**
 * The shared accessibility gate. Warning is the failure level because insufficients contrast is
 * reported as a warning by the Accessibility Test Framework, and hidden contrast failures are
 * exactly what this pass exists to catch.
 */
@OptIn(ExperimentalRoborazziApi::class)
fun accessibilityGateOptions(): RoborazziATFAccessibilityCheckOptions = RoborazziATFAccessibilityCheckOptions(
    checker = RoborazziATFAccessibilityChecker(),
    failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.Warning
)

/** Runs the gate on one captured state. */
@OptIn(ExperimentalRoborazziApi::class)
fun SemanticsNodeInteraction.assertAccessible() {
    checkRoboAccessibility(accessibilityGateOptions())
}
