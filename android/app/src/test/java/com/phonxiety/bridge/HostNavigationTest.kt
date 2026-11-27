package com.phonxiety.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.feature.onboarding.SetupPreferences
import com.phonxiety.bridge.os.Notifications
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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

    @Test
    fun backAtRootsThatLeaveTheAppIsLeftToTheSystem() {
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            scenario.settle()
            assertScreen(DashboardKey)
            scenario.onActivity { activity ->
                assertFalse(
                    "the host swallowed the predictive exit at the Dashboard root",
                    activity.onBackPressedDispatcher.hasEnabledCallbacks()
                )
            }

            suiteItem("Settings").performClick()
            scenario.settle()
            assertScreen(SettingsKey)
            scenario.onActivity { activity ->
                assertTrue(
                    "the host does not answer back at a non-Dashboard root",
                    activity.onBackPressedDispatcher.hasEnabledCallbacks()
                )
            }

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            scenario.settle()
            assertScreen(DashboardKey)
        }
    }

    @Test
    fun theFreshInstallWizardRootLeavesTheExitToTheSystem() {
        SetupPreferences(context).ready = false
        ActivityScenario.launch<MainActivity>(launchIntent()).use { scenario ->
            scenario.settle()
            assertScreen(OnboardingKey())
            scenario.onActivity { activity ->
                assertFalse(
                    "the host swallowed the predictive exit at the wizard root",
                    activity.onBackPressedDispatcher.hasEnabledCallbacks()
                )
            }
        }
    }

    @Test
    fun thePairingLinkLandsOnTheWizardFromAColdStart() {
        ActivityScenario.launch<MainActivity>(pairIntent(LINK)).use { scenario ->
            scenario.settle()

            assertScreen(OnboardingKey(LINK))
        }
    }

    @Test
    fun aPairingLinkBringsTheDashboardForwardAndKeepsTheOtherSectionsDepth() {
        // A controller, not ActivityScenario: closing a scenario after a warm pairing intent
        // waits forever in this Robolectric environment, which is a test-harness limitation and
        // not an app state. The real activity, its real onNewIntent and its real host are all
        // still driven here.
        val controller = Robolectric.buildActivity(MainActivity::class.java, launchIntent()).setup()
        shadowOf(Looper.getMainLooper()).idle()
        compose.settleHost()
        assertScreen(DashboardKey)

        suiteItem("Calls").performClick()
        compose.settleHost()
        assertScreen(CallsKey)

        // A detail is open in Calls when the link arrives.
        controller.newIntent(routeIntent(Notifications.ROUTE_DOCTOR))
        compose.settleHost()
        assertScreen(DoctorKey)

        controller.newIntent(pairIntent(LINK))
        compose.settleHost()
        assertScreen(OnboardingKey(LINK))

        // The wizard is a full-screen flow: back returns to the Dashboard, and only then does the
        // suite come back.
        controller.get().onBackPressedDispatcher.onBackPressed()
        compose.settleHost()
        assertScreen(DashboardKey)

        suiteItem("Calls").performClick()
        compose.settleHost()
        assertScreen(DoctorKey)

        suiteItem("Home").performClick()
        compose.settleHost()
        assertScreen(DashboardKey)

        controller.pause().stop().destroy()
    }

    @Test
    fun theWizardsDisclosureIsRestoredAcrossRecreation() {
        ActivityScenario.launch<MainActivity>(pairIntent(LINK)).use { scenario ->
            scenario.settle()
            assertScreen(OnboardingKey(LINK))

            compose.onNodeWithText("Show steps").performScrollTo().performClick()
            scenario.settle()
            compose.onNodeWithText("Hide steps").assertExists()

            scenario.recreate()
            scenario.settle()

            // Not only the destination tag: the entry's own saveable state came back.
            assertScreen(OnboardingKey(LINK))
            compose.onNodeWithText("Hide steps").assertExists()
        }
    }

    private fun pairIntent(link: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))

    private fun launchIntent(): Intent = Intent(context, MainActivity::class.java)

    private fun routeIntent(route: String, callId: String? = null): Intent = Intent()
        .setClassName(context.packageName, Notifications.HOST_ACTIVITY)
        .putExtra(Notifications.EXTRA_ROUTE, route)
        .apply { if (callId != null) putExtra(Notifications.EXTRA_CALL_ID, callId) }

    /** A navigation-suite item by its label; the label inside the icon is not clickable itself. */
    private fun suiteItem(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun assertScreen(key: androidx.navigation3.runtime.NavKey) = compose.onNodeWithTag(destinationTag(key)).assertExists()

    /** The start destination resolves on a worker, so the rendered screen is the evidence. */
    private fun ActivityScenario<MainActivity>.settle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.settleHost()
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

    private companion object {
        const val LINK = "phonxiety://pair?one"
    }
}
