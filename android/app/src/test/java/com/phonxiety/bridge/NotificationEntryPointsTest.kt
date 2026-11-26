package com.phonxiety.bridge

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import com.phonxiety.bridge.model.PathKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Each card's own tap, delivered as the platform delivers it, and the screen it lands on — from a
 * cold start and from a screen the owner was already on (`onNewIntent`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationEntryPointsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun intentOf(pendingIntent: PendingIntent): Intent = requireNotNull(shadowOf(pendingIntent).savedIntent) { "the card has no tap" }

    /** The app's own two steps: the activity turns the intent into a signal, the host opens it. */
    private fun screenFor(intent: Intent, from: NavKey): NavKey {
        val signal = requireNotNull(MainActivity.signalFor(intent)) { "the delivered intent asked for nothing" }
        val backStack = NavBackStack<NavKey>(from)
        backStack.openSignal(signal)
        return backStack.last()
    }

    private fun call(phase: CallPhase = CallPhase.ACTIVE) = CallState(
        callId = "call-7",
        destinationMasked = "+******4567",
        phase = phase,
        startedAtEpochMs = NOW
    )

    @Test
    fun theDeviceLinkCardsTapLandsOnTheDashboardFromAColdStart() {
        val card = Notifications.bridge(context, BridgeNotice.Offline)

        assertEquals(DashboardKey, screenFor(intentOf(card.contentIntent), from = DashboardKey))
    }

    @Test
    fun theDeviceLinkCardsTapReturnsToTheDashboardFromAnotherScreen() {
        val card = Notifications.bridge(context, BridgeNotice.Linked(PathKind.DIRECT, 24))

        assertEquals(DashboardKey, screenFor(intentOf(card.contentIntent), from = SettingsKey))
    }

    @Test
    fun theCallCardsTapLandsOnThatCallFromAColdStart() {
        val card = Notifications.call(context, call())

        val screen = screenFor(intentOf(card.contentIntent), from = DashboardKey)

        assertEquals(LiveCallKey("call-7"), screen)
    }

    @Test
    fun theCallCardsTapLandsOnThatCallFromAnotherScreen() {
        val card = Notifications.call(context, call(CallPhase.DIALING))

        assertEquals(LiveCallKey("call-7"), screenFor(intentOf(card.contentIntent), from = CallsKey))
    }

    @Test
    fun theSameCallsTapBringsItsScreenToTheTopInsteadOfStackingIt() {
        val intent = intentOf(Notifications.call(context, call()).contentIntent)
        val backStack = NavBackStack<NavKey>(DashboardKey, LiveCallKey("call-7"), CallsKey)

        backStack.openSignal(requireNotNull(MainActivity.signalFor(intent)))

        assertEquals(listOf<NavKey>(DashboardKey, CallsKey, LiveCallKey("call-7")), backStack.toList())
    }

    @Test
    fun theSetupRemindersTapLandsOnTheWizard() {
        val card = Notifications.setup(context, "Finish the module install", "Open phonxiety to verify it.")

        assertEquals(OnboardingKey(), screenFor(intentOf(card.contentIntent), from = DashboardKey))
        assertEquals(OnboardingKey(), screenFor(intentOf(card.contentIntent), from = SettingsKey))
    }

    @Test
    fun aCallTapWithoutAnIdCannotOpenACallSoItOpensTheDashboard() {
        val intent = Intent().apply {
            setClassName(context.packageName, Notifications.HOST_ACTIVITY)
            putExtra(Notifications.EXTRA_ROUTE, Notifications.ROUTE_CALL)
        }

        assertEquals(DashboardKey, screenFor(intent, from = CallsKey))
    }

    @Test
    fun anIntentThatAsksForNothingIsIgnored() {
        assertNull(MainActivity.signalFor(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun theActivityReadsAColdStartIntentAndANewIntentThroughTheSameScreenRule() {
        val card = Notifications.call(context, call())
        val controller = Robolectric.buildActivity(MainActivity::class.java, intentOf(card.contentIntent)).create()

        val coldStart = NavBackStack<NavKey>(DashboardKey)
        coldStart.openSignal(requireNotNull(controller.get().pendingSignal))
        assertEquals(LiveCallKey("call-7"), coldStart.last())

        val setup = Notifications.setup(context, "Finish the module install", "Open phonxiety to verify it.")
        controller.newIntent(intentOf(setup.contentIntent))

        val newIntent = NavBackStack<NavKey>(DashboardKey, CallsKey)
        newIntent.openSignal(requireNotNull(controller.get().pendingSignal))
        assertEquals(OnboardingKey(), newIntent.last())
    }

    private companion object {
        const val NOW = 1_796_500_000_000L
    }
}
