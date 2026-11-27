package com.phonxiety.bridge.designsystem

import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A status label that changes animates in place: the old word leaves while the new one arrives,
 * and both are on screen only inside the short transition. With reduced motion the swap is
 * immediate, because a user who turned animation off asked for the new word, not for a transition.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class StatusLabelMotionTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The pill itself: the test pins the component a reader sees, not a bare text fixture. */
    private fun setPill(label: () -> String, motionEnabled: Boolean = true) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalMotionEnabled provides motionEnabled
            ) {
                PhonxietyTheme(dynamicColor = false) {
                    Column {
                        StatusPill(label = label(), tone = StatusTone.POSITIVE)
                    }
                }
            }
        }
    }

    @Test
    fun aChangingStatusLabelKeepsTheOldWordOnlyInsideTheTransition() {
        var label by mutableStateOf("Connected")
        composeRule.mainClock.autoAdvance = false
        setPill({ label })
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Connected").assertExists()

        label = "Reconnecting"
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.mainClock.advanceTimeBy(40L)
        // A few frames in: the leaving word and the arriving word are both on screen.
        composeRule.onNodeWithText("Connected").assertExists()
        composeRule.onNodeWithText("Reconnecting").assertExists()

        composeRule.mainClock.advanceTimeBy(Motion.STATUS_DURATION_MS + 100L)
        composeRule.onAllNodesWithText("Connected").assertCountEquals(0)
        composeRule.onNodeWithText("Reconnecting").assertExists()
    }

    @Test
    fun reducedMotionSwapsTheStatusLabelWithNoTransition() {
        var label by mutableStateOf("Connected")
        composeRule.mainClock.autoAdvance = false
        setPill({ label }, motionEnabled = false)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Connected").assertExists()

        label = "Reconnecting"
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.mainClock.advanceTimeBy(40L)
        // No in-between state: the frame that draws the new word has already dropped the old one.
        composeRule.onAllNodesWithText("Connected").assertCountEquals(0)
        composeRule.onNodeWithText("Reconnecting").assertExists()
    }
}
