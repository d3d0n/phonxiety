package com.phonxiety.bridge.designsystem.testing

import androidx.compose.ui.test.junit4.ComposeContentTestRule

/**
 * Pins the frame a capture records: [ComposeContentTestRule.waitForIdle] does not freeze an
 * indeterminate progress indicator or a pulse, so the animation clock is stopped and moved
 * exactly one frame.
 */
fun ComposeContentTestRule.pinAnimationFrame() {
    mainClock.autoAdvance = false
    mainClock.advanceTimeByFrame()
}

/**
 * Lets the animation clock run again after a pinned capture, for a step that scrolls or clicks
 * before the next capture in the same test.
 */
fun ComposeContentTestRule.resumeAnimationFrames() {
    mainClock.autoAdvance = true
}
