package com.phonxiety.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.ScrollToTopRegistry
import com.phonxiety.bridge.feature.onboarding.SetupPreferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The re-tap contract through the real host: the action registered under the section's key is the
 * one that runs. The host is composed with the registry the test holds, and the fake replaces the
 * section root's own action for a moment, so a navigator that stopped using the host's registry —
 * or stopped dispatching at a root — leaves the counter at zero.
 *
 * The app is debuggable for the test, which is what makes a host that forgot the provision fail at
 * the registration instead of going quiet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostScrollDispatchTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun reTappingTheActiveSectionAtItsRootRunsTheActionRegisteredUnderItsKey() {
        SetupPreferences(context).ready = true
        val flags = context.applicationInfo.flags
        context.applicationInfo.flags = flags or ApplicationInfo.FLAG_DEBUGGABLE
        try {
            val registry = ScrollToTopRegistry()
            var dashboardScrolls = 0
            var otherScrolls = 0

            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                shadowOf(Looper.getMainLooper()).idle()
                composeRule.settleHost()

                scenario.onActivity { activity ->
                    activity.setContent {
                        PhonxietyTheme(dynamicColor = false) {
                            PhonxietyNavigationHost(
                                signal = null,
                                start = DashboardKey,
                                strip = LinkStripState.Hidden,
                                onSignalConsumed = {},
                                scrollToTop = registry
                            )
                        }
                    }
                }
                composeRule.settleHost()

                composeRule.runOnIdle {
                    registry.register(Section.DASHBOARD.scrollToTopKey) { dashboardScrolls++ }
                    registry.register(Section.CALLS.scrollToTopKey) { otherScrolls++ }
                }

                composeRule.onNode(hasText("Home") and hasClickAction()).performClick()

                composeRule.runOnIdle {
                    assertEquals("the active section's action did not run", 1, dashboardScrolls)
                    assertEquals("another section's action ran", 0, otherScrolls)
                }
            }
        } finally {
            context.applicationInfo.flags = flags
        }
    }
}
