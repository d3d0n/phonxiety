package com.phonxiety.bridge

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.feature.onboarding.SetupPreferences
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The host on the real activity: four sections with their own depths, the suite on nested
 * destinations and off full-screen flows, and a re-tap that resets the active section instead of
 * stacking anything. Every assertion is a tap on the real navigation suite and the tag the host
 * draws at that moment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostNavigationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun finishSetup() {
        SetupPreferences(context).ready = true
    }

    @Test
    fun aDetailStaysUnderItsSectionAcrossATabSwitch() {
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            scenario.settle()
            assertScreen(DashboardKey)

            scenario.deliver(routeIntent(Notifications.ROUTE_DOCTOR))
            scenario.settle()
            assertScreen(DoctorKey)
            // The suite is still there: the Doctor is nested inside the Dashboard section.
            suiteItem("Lab").assertExists()

            suiteItem("Lab").performClick()
            scenario.settle()
            assertScreen(LabKey)

            suiteItem("Home").performClick()
            scenario.settle()
            // The Dashboard section kept the doctor it had open.
            assertScreen(DoctorKey)

            suiteItem("Home").performClick()
            scenario.settle()
            assertScreen(DashboardKey)
        }
    }

    @Test
    fun reTappingTheActiveSectionAtItsRootStaysThere() {
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            scenario.settle()

            suiteItem("Home").performClick()
            suiteItem("Home").performClick()
            scenario.settle()

            assertScreen(DashboardKey)
        }
    }

    @Test
    fun aLiveCallHidesTheSuiteAndBackReturnsToTheSection() {
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            scenario.settle()

            scenario.deliver(routeIntent(Notifications.ROUTE_CALL, callId = "call-7"))
            scenario.settle()
            assertScreen(LiveCallKey("call-7"))
            suiteItem("Calls").assertDoesNotExist()

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            scenario.settle()
            assertScreen(DashboardKey)
            suiteItem("Calls").assertExists()
        }
    }

    @Test
    fun backAtANonDashboardRootShowsTheDashboard() {
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            scenario.settle()
            suiteItem("Settings").performClick()
            scenario.settle()
            assertScreen(SettingsKey)

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            scenario.settle()

            assertScreen(DashboardKey)
        }
    }

    @Test
    fun theWizardHidesTheSuite() {
        SetupPreferences(context).ready = false
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            scenario.settle()

            assertScreen(OnboardingKey())
            suiteItem("Calls").assertDoesNotExist()
        }
    }

    @Test
    fun theStripReportsAnOfflineLinkAboveTheSuite() {
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            scenario.settle()

            compose.onNodeWithTag(LINK_STRIP_TAG).assertExists()
            compose.onNodeWithText(context.getString(R.string.link_strip_offline)).assertExists()
        }
    }

    private fun launchIntent(): Intent = Intent(context, MainActivity::class.java)

    private fun routeIntent(route: String, callId: String? = null): Intent = Intent()
        .setClassName(context.packageName, Notifications.HOST_ACTIVITY)
        .putExtra(Notifications.EXTRA_ROUTE, route)
        .apply { if (callId != null) putExtra(Notifications.EXTRA_CALL_ID, callId) }

    /** A navigation-suite item by its label; the label inside the icon is not clickable itself. */
    private fun suiteItem(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun assertScreen(key: androidx.navigation3.runtime.NavKey) = compose.onNodeWithTag(destinationTag(key)).assertExists()

    /** The start destination resolves on the main dispatcher, one turn after the activity starts. */
    private fun ActivityScenario<MainActivity>.settle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    /** Delivers an intent the way the system would to an activity that is already on a screen. */
    private fun ActivityScenario<MainActivity>.deliver(intent: Intent) {
        onActivity { activity -> activity.receiveNewIntent(intent) }
    }

    /** `Activity.onNewIntent` is protected; the test still runs the real override, not a copy. */
    private fun MainActivity.receiveNewIntent(intent: Intent) {
        val method = MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
        method.isAccessible = true
        method.invoke(this, intent)
    }
}
