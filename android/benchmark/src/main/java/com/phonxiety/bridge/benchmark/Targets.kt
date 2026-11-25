package com.phonxiety.bridge.benchmark

/** The app under test; `targetProjectPath = ":app"` binds this module to it. */
internal const val TARGET_PACKAGE = "com.phonxiety.bridge"

/**
 * Top-level destinations, by the label the navigation suite renders.  The shell
 * only shows them once a top-level destination is current, so their presence is
 * also how the journeys tell a paired install from the setup wizard.
 */
internal const val HOME_LABEL = "Home"
internal const val CALLS_LABEL = "Calls"
internal const val LAB_LABEL = "Lab"

/** How long a journey waits for the shell to draw its destinations. */
internal const val SHELL_TIMEOUT_MS = 5_000L

/** How long a journey waits for a tapped destination to become current. */
internal const val TAB_TIMEOUT_MS = 3_000L

/** Iterations per measured case: enough for a p50 without a ten-minute run. */
internal const val ITERATIONS = 10
