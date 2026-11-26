package com.phonxiety.bridge.designsystem

import android.content.Context
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.checkRoboAccessibility
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityViewCheckException
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Proves the gate is not a no-op: white text on a white surface must fail it at the Warning level.
 * The same options run on every recorded screenshot state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class InaccessibleFixtureTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun enableAccessibility() {
        val manager = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
    }

    @Test
    fun whiteOnWhiteFailsTheGate() {
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
                AndroidView(
                    factory = { context ->
                        TextView(context).apply {
                            text = "This text is invisible"
                            setTextColor(android.graphics.Color.WHITE)
                            setBackgroundColor(android.graphics.Color.WHITE)
                        }
                    }
                )
            }
        }

        assertThrows(AccessibilityViewCheckException::class.java) {
            composeRule.onRoot().checkRoboAccessibility(accessibilityGateOptions())
        }
    }
}
