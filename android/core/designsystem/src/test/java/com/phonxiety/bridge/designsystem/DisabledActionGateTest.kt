package com.phonxiety.bridge.designsystem

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.testing.assertAccessible
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A disabled action passes the gate in both palettes: its label keeps readable colours, and the
 * framework's misread of a centred label on a disabled Button is the one result the gate
 * suppresses (WCAG 1.4.3 exempts inactive controls).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DisabledActionGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun enableAccessibility() {
        val manager = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
    }

    private fun renderDisabled(dark: Boolean) {
        composeRule.setContent {
            PhonxietyTheme(darkTheme = dark, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        PrimaryActionButton(label = "Finish the brief", onClick = {}, enabled = false)
                    }
                }
            }
        }
    }

    @Test
    fun aDisabledActionPassesTheGateInLight() {
        renderDisabled(dark = false)
        composeRule.onRoot().assertAccessible()
    }

    @Test
    fun aDisabledActionPassesTheGateInDark() {
        renderDisabled(dark = true)
        composeRule.onRoot().assertAccessible()
    }
}
