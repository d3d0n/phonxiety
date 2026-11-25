package com.phonxiety.bridge.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * The user journeys the profile covers, shared by the generator and the
 * benchmarks so the two cannot drift apart.
 *
 * A device with no stored pairing opens the setup wizard instead of the
 * dashboard; the shell then has no top-level destinations, so a journey stops
 * after the cold start and profiles only the wizard.  Pair the device first
 * (`phonxiety pair` code or a `phonxiety://pair` link) to profile the dashboard,
 * Calls and the lab.
 */
internal fun MacrobenchmarkScope.awaitShell(): Boolean = device.wait(Until.hasObject(By.text(CALLS_LABEL)), SHELL_TIMEOUT_MS) != null

/** Opens a top-level destination from the navigation bar or rail. */
internal fun MacrobenchmarkScope.openSection(label: String): Boolean {
    val target: UiObject2 = device.run {
        wait(Until.findObject(By.text(label).clickable(true)), TAB_TIMEOUT_MS)
            ?: wait(Until.findObject(By.text(label)), TAB_TIMEOUT_MS)
    } ?: return false
    target.click()
    device.waitForIdle()
    return true
}

/** Scrolls the current screen's list once, as a call history or a transcript does. */
internal fun MacrobenchmarkScope.scrollCurrentList() {
    val list = device.findObject(By.scrollable(true)) ?: return
    list.scroll(Direction.DOWN, 1.0f)
    device.waitForIdle()
}
