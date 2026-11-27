package com.phonxiety.bridge

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.robolectric.Shadows.shadowOf

/**
 * Waits until the host has drawn a destination.
 *
 * The start destination is read on a worker now, so the first frame can be one main-loop turn —
 * and one worker turn — behind the activity's launch. Idling the main looper once is not enough
 * evidence that the read answered; the screen tag is.
 */
internal fun ComposeTestRule.settleHost() {
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        waitForIdle()
        val drawn = onAllNodes(hasTestTagPrefix(DESTINATION_TAG_PREFIX)).fetchSemanticsNodes().isNotEmpty()
        if (drawn) return
        Thread.sleep(5)
    }
    throw AssertionError("the host did not draw a destination")
}

/** Matches the tag [destinationTag] puts on the host's current screen. */
private fun hasTestTagPrefix(prefix: String) = androidx.compose.ui.test.SemanticsMatcher(
    "has a test tag starting with $prefix"
) { node ->
    node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
}

private const val DESTINATION_TAG_PREFIX = "phonxiety:screen:"
