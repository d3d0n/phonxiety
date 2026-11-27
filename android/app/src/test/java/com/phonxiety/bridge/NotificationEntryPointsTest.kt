package com.phonxiety.bridge

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.feature.onboarding.SetupPreferences
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import com.phonxiety.bridge.model.PathKind
import com.phonxiety.bridge.os.BridgeNotice
import com.phonxiety.bridge.os.Notifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Each card's own tap, the real [MainActivity] and the destination the running host draws — from a
 * cold start and through `onNewIntent` of an activity that is already on a screen.
 *
 * Nothing here rebuilds a back stack of its own: every assertion is the tag
 * [destinationTag] puts on the screen the host is drawing at that moment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationEntryPointsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun finishSetup() {
        // Otherwise the wizard is the start destination, as it is on a fresh install.
        SetupPreferences(context).ready = true
    }

    private fun call(phase: CallPhase = CallPhase.ACTIVE) = CallState(
        callId = "call-7",
        destinationMasked = "+******4567",
        phase = phase,
        startedAtEpochMs = NOW
    )

    private fun setupCard() = Notifications.setup(context, "Finish the module install", "Open phonxiety to verify it.")

    /** What the platform starts when the card's own PendingIntent is sent. */
    private fun startedBy(card: Notification): Intent {
        val tap = requireNotNull(card.contentIntent) { "the card has no tap" }
        tap.send()
        val started = requireNotNull(shadowOf(context as Application).nextStartedActivity) {
            "the tap started nothing"
        }
        assertEquals(Notifications.HOST_ACTIVITY, started.component?.className)
        return started
    }

    private fun onScreen(intent: Intent, block: (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch<MainActivity>(intent).use { launched ->
            settle()
            block(launched)
        }
    }

    /** The start destination is resolved on a worker, so the rendered screen is the evidence. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.settleHost()
    }

    private fun assertScreen(key: NavKey) {
        settle()
        compose.onNodeWithTag(destinationTag(key)).assertExists()
    }

    private fun suiteItem(label: String) = compose.onNode(hasText(label) and hasClickAction())

    /** Delivers the card's tap to the activity the owner is already on, as the system would. */
    private fun deliverAsNewIntent(scenario: ActivityScenario<MainActivity>, card: Notification) {
        val started = startedBy(card)
        deliverIntent(scenario, started)
    }

    /** Delivers any host intent to the activity already on screen. */
    private fun deliverIntent(scenario: ActivityScenario<MainActivity>, intent: Intent) {
        scenario.onActivity { activity -> activity.receiveNewIntent(intent) }
        settle()
    }

    /** `Activity.onNewIntent` is protected; the test still runs the real override, not a copy. */
    private fun MainActivity.receiveNewIntent(intent: Intent) {
        val method = MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
        method.isAccessible = true
        method.invoke(this, intent)
    }

    @Test
    fun theDeviceLinkCardsTapLandsOnTheDashboardFromAColdStart() {
        onScreen(startedBy(Notifications.bridge(context, BridgeNotice.Offline))) {
            assertScreen(DashboardKey)
        }
    }

    @Test
    fun theDeviceLinkCardsTapReturnsToTheDashboardFromAnotherScreen() {
        onScreen(startedBy(setupCard())) { scenario ->
            assertScreen(OnboardingKey())

            deliverAsNewIntent(scenario, Notifications.bridge(context, BridgeNotice.Linked(PathKind.DIRECT, 24)))

            assertScreen(DashboardKey)
        }
    }

    @Test
    fun theCallCardsTapLandsOnThatCallFromAColdStart() {
        onScreen(startedBy(Notifications.call(context, call()))) {
            assertScreen(LiveCallKey("call-7"))
        }
    }

    @Test
    fun theCallCardsTapLandsOnThatCallFromAnotherScreen() {
        onScreen(startedBy(Notifications.bridge(context, BridgeNotice.Offline))) { scenario ->
            assertScreen(DashboardKey)

            deliverAsNewIntent(scenario, Notifications.call(context, call(CallPhase.DIALING)))

            assertScreen(LiveCallKey("call-7"))
        }
    }

    @Test
    fun theSameCallsTapOpensItOnceSoBackReturnsToTheDashboard() {
        onScreen(startedBy(Notifications.call(context, call()))) { scenario ->
            assertScreen(LiveCallKey("call-7"))

            deliverAsNewIntent(scenario, Notifications.call(context, call()))
            assertScreen(LiveCallKey("call-7"))

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            assertScreen(DashboardKey)
        }
    }

    @Test
    fun theSetupRemindersTapLandsOnTheWizardFromAColdStart() {
        onScreen(startedBy(setupCard())) {
            assertScreen(OnboardingKey())
        }
    }

    @Test
    fun theSetupRemindersTapLandsOnTheWizardFromTheDashboard() {
        onScreen(startedBy(Notifications.bridge(context, BridgeNotice.Offline))) { scenario ->
            assertScreen(DashboardKey)

            deliverAsNewIntent(scenario, setupCard())

            assertScreen(OnboardingKey())
        }
    }

    @Test
    fun aCallTapOpensOverTheSectionTheOwnerIsOnAndBackReturnsToIt() {
        onScreen(startedBy(Notifications.bridge(context, BridgeNotice.Offline))) { scenario ->
            assertScreen(DashboardKey)
            suiteItem("Calls").performClick()
            assertScreen(CallsKey)

            deliverAsNewIntent(scenario, Notifications.call(context, call()))
            assertScreen(LiveCallKey("call-7"))

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            assertScreen(CallsKey)
        }
    }

    @Test
    fun theDashboardTapResetsOnlyTheDashboardSection() {
        onScreen(startedBy(Notifications.bridge(context, BridgeNotice.Offline))) { scenario ->
            suiteItem("Calls").performClick()
            assertScreen(CallsKey)

            deliverAsNewIntent(scenario, Notifications.bridge(context, BridgeNotice.Offline))

            assertScreen(DashboardKey)
            suiteItem("Calls").performClick()
            assertScreen(CallsKey)
        }
    }

    @Test
    fun theCallScreenSurvivesRecreation() {
        onScreen(startedBy(Notifications.call(context, call()))) { scenario ->
            assertScreen(LiveCallKey("call-7"))

            scenario.recreate()

            assertScreen(LiveCallKey("call-7"))
        }
    }

    @Test
    fun aSectionsDepthSurvivesRecreation() {
        onScreen(startedBy(Notifications.bridge(context, BridgeNotice.Offline))) { scenario ->
            suiteItem("Calls").performClick()
            assertScreen(CallsKey)
            val doctor = Intent().apply {
                setClassName(context.packageName, Notifications.HOST_ACTIVITY)
                putExtra(Notifications.EXTRA_ROUTE, Notifications.ROUTE_DOCTOR)
            }
            deliverIntent(scenario, doctor)
            assertScreen(DoctorKey)
            suiteItem("Home").performClick()
            assertScreen(DashboardKey)

            scenario.recreate()

            assertScreen(DashboardKey)
            suiteItem("Calls").performClick()
            assertScreen(DoctorKey)
        }
    }

    @Test
    fun aCallTapWithoutAnIdCannotOpenACallSoItReturnsToTheDashboard() {
        onScreen(startedBy(setupCard())) { scenario ->
            assertScreen(OnboardingKey())

            val withoutId = Intent().apply {
                setClassName(context.packageName, Notifications.HOST_ACTIVITY)
                putExtra(Notifications.EXTRA_ROUTE, Notifications.ROUTE_CALL)
            }
            scenario.onActivity { activity -> activity.receiveNewIntent(withoutId) }

            assertScreen(DashboardKey)
        }
    }

    @Test
    fun anIntentThatAsksForNothingIsIgnored() {
        assertNull(MainActivity.signalFor(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun theCardTapIsASingleTopActivityIntentThatTheHostCanStart() {
        val started = startedBy(Notifications.bridge(context, BridgeNotice.Offline))

        assertTrue(started.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertEquals(Notifications.ROUTE_DASHBOARD, started.getStringExtra(Notifications.EXTRA_ROUTE))
    }

    private companion object {
        const val NOW = 1_796_500_000_000L
    }
}
