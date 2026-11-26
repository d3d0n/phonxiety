package com.phonxiety.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The three cards: their slots, their channels, their text, their tap and their lock-screen copy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationCardsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private fun up(path: PathKind, rttMs: Long?) = LinkState.Up(
        kind = LinkKind.IROH,
        path = path,
        address = "iroh 9f42c1",
        rttMs = rttMs,
        sinceEpochMs = NOW,
        reconnects = 0
    )

    private fun call(phase: CallPhase) = CallState(
        callId = "call-1",
        destinationMasked = "+******4567",
        phase = phase,
        startedAtEpochMs = NOW
    )

    private fun Notification.title(): String = requireNotNull(extras.getCharSequence(Notification.EXTRA_TITLE)) { "no title" }.toString()

    private fun Notification.text(): String = requireNotNull(extras.getCharSequence(Notification.EXTRA_TEXT)) { "no text" }.toString()

    private fun Notification.subText(): String? = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

    private fun intentOf(pendingIntent: PendingIntent) = requireNotNull(shadowOf(pendingIntent).savedIntent)

    @Test
    fun theChannelIdsAndImportanceAreTheOnesAlreadyShipped() {
        Notifications.ensureChannels(context)

        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(Notifications.BRIDGE_CHANNEL).importance
        )
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(Notifications.CALL_CHANNEL).importance
        )
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(Notifications.SETUP_CHANNEL).importance
        )
        assertEquals(
            Notification.VISIBILITY_PRIVATE,
            manager.getNotificationChannel(Notifications.BRIDGE_CHANNEL).lockscreenVisibility
        )
    }

    @Test
    fun anOwnersChannelChoiceSurvivesTheUpgrade() {
        manager.createNotificationChannel(
            NotificationChannel(Notifications.BRIDGE_CHANNEL, "Device link", NotificationManager.IMPORTANCE_MIN)
        )

        Notifications.ensureChannels(context)

        // The platform never updates a created channel, so the owner's silence stays.
        assertEquals(
            NotificationManager.IMPORTANCE_MIN,
            manager.getNotificationChannel(Notifications.BRIDGE_CHANNEL).importance
        )
    }

    @Test
    fun theSetupReminderCannotReplaceTheDeviceLinkOrTheCall() {
        assertNotEquals(Notifications.BRIDGE_ID, Notifications.SETUP_ID)
        assertNotEquals(Notifications.CALL_ID, Notifications.SETUP_ID)
        assertNotEquals(Notifications.BRIDGE_ID, Notifications.CALL_ID)
    }

    @Test
    fun theDirectPathCarriesItsLatency() {
        val card = Notifications.bridge(context, BridgeNotice.Linked(PathKind.DIRECT, 24))

        assertEquals("Connected to the computer", card.title())
        assertEquals("Direct \u00B7 24\u00A0ms", card.text())
    }

    @Test
    fun aLinkWithoutAMeasurementIsUnknownNotRelay() {
        val card = Notifications.bridge(context, BridgeNotice.Linked(PathKind.UNKNOWN, null))

        assertEquals("Path unknown", card.text())
        assertFalse(card.text().contains("Relay"))
    }

    @Test
    fun aCallOutranksTheLinkItRunsOver() {
        val state = BridgeState(link = up(PathKind.DIRECT, 24), call = call(CallPhase.ACTIVE))

        val card = Notifications.bridge(context, BridgeNotice.of(state))

        assertEquals("In call", card.title())
        assertEquals("+******4567", card.text())
        assertEquals("Live", card.subText())
    }

    @Test
    fun aFinishedCallLeavesTheDeviceLinkCardShowingTheLink() {
        val notice = BridgeNotice.of(BridgeState(link = up(PathKind.RELAY, 182), call = call(CallPhase.ENDED)))

        assertEquals(BridgeNotice.Linked(PathKind.RELAY, 182), notice)
    }

    @Test
    fun theLosingLinkSaysWhatItIsDoing() {
        val connecting = Notifications.bridge(context, BridgeNotice.of(BridgeState(link = LinkState.Connecting(LinkKind.IROH))))
        assertEquals("Reconnecting\u2026", connecting.title())
        assertEquals("Trying the next route", connecting.text())

        val offline = Notifications.bridge(context, BridgeNotice.of(BridgeState()))
        assertEquals("No link to the computer", offline.title())
        assertEquals("Tap to check the link", offline.text())
    }

    @Test
    fun theDeviceLinkCardHidesItsTimestampAndStaysOnThisDevice() {
        val card = Notifications.bridge(context, BridgeNotice.Offline)

        assertFalse(card.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertTrue((card.flags and Notification.FLAG_LOCAL_ONLY) != 0)
        assertTrue((card.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
        assertTrue((card.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertEquals(Notification.CATEGORY_SERVICE, card.category)
        assertEquals(Notification.VISIBILITY_PRIVATE, card.visibility)
    }

    @Test
    fun theDeviceLinkCardOpensTheDashboardAndHidesTheNumberOnTheLockScreen() {
        val card = Notifications.bridge(context, BridgeNotice.InCall("+******4567", CallPhase.ACTIVE))

        val tap = intentOf(card.contentIntent)
        assertEquals(Notifications.HOST_ACTIVITY, tap.component?.className)
        assertEquals(Notifications.ROUTE_DASHBOARD, tap.getStringExtra(Notifications.EXTRA_ROUTE))

        val public = requireNotNull(card.publicVersion) { "no lock-screen version" }
        assertEquals("phonxiety", public.title())
        assertEquals("Device link is running", public.text())
        assertFalse(public.text().contains("4567"))
        assertTrue(public.actions.isNullOrEmpty())
    }

    @Test
    fun theCallCardCarriesTheFarEndThePhaseAndARunningClock() {
        val card = Notifications.call(context, call(CallPhase.ACTIVE))

        assertEquals("Call in progress", card.title())
        assertEquals("+******4567", card.text())
        assertTrue(card.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(NOW, card.`when`)
        assertEquals(Notification.CATEGORY_STATUS, card.category)
    }

    @Test
    fun theCallCardWearsThePhaseInItsTitle() {
        assertEquals("Dialling", Notifications.call(context, call(CallPhase.DIALING)).title())
        assertEquals("Ending the call", Notifications.call(context, call(CallPhase.ENDING)).title())
    }

    @Test
    fun anEndedCallLeavesNoCallAndNoHangUp() {
        val card = Notifications.call(context, call(CallPhase.ENDED))

        assertEquals("Call bridge ready", card.title())
        assertEquals("Waiting for a call to start", card.text())
        assertTrue(card.actions.isNullOrEmpty())
        assertEquals(Notifications.ROUTE_DASHBOARD, intentOf(card.contentIntent).getStringExtra(Notifications.EXTRA_ROUTE))
    }

    @Test
    fun theCallTapFollowsTheCallEvenAfterTheNumberChanges() {
        assertEquals("call-1", intentOf(Notifications.call(context, call(CallPhase.DIALING)).contentIntent).getStringExtra(Notifications.EXTRA_CALL_ID))

        val updated = intentOf(
            Notifications.call(context, call(CallPhase.ACTIVE).copy(callId = "call-2")).contentIntent
        )

        assertEquals(Notifications.ROUTE_CALL, updated.getStringExtra(Notifications.EXTRA_ROUTE))
        assertEquals("call-2", updated.getStringExtra(Notifications.EXTRA_CALL_ID))
    }

    @Test
    fun theCallCardsLockScreenVersionHasNoNumberAndNoAction() {
        val public = requireNotNull(Notifications.call(context, call(CallPhase.ACTIVE)).publicVersion)

        assertEquals("phonxiety", public.title())
        assertEquals("Call in progress", public.text())
        assertFalse(public.text().contains("4567"))
        assertTrue(public.actions.isNullOrEmpty())
    }

    @Test
    fun hangUpIsTheCallsOnlyActionAndItIsABroadcastToTheAppsReceiver() {
        val card = Notifications.call(context, call(CallPhase.ACTIVE))

        val action = card.actions.single()
        assertEquals("Hang up", action.title.toString())
        val shadow = shadowOf(action.actionIntent)
        assertTrue(shadow.isBroadcast)
        val intent = requireNotNull(shadow.savedIntent)
        assertEquals(Notifications.ACTION_HANG_UP, intent.action)
        assertEquals(Notifications.ACTION_RECEIVER, intent.component?.className)
    }

    @Test
    fun theSetupReminderHasItsOwnSlotChannelAndExit() {
        val card = Notifications.setup(context, "Finish the module install", "Open phonxiety to verify it.")

        assertEquals(Notification.CATEGORY_ERROR, card.category)
        assertEquals(Notifications.SETUP_CHANNEL, card.channelId)
        assertTrue((card.flags and Notification.FLAG_AUTO_CANCEL) != 0)
        assertEquals(Notifications.ROUTE_SETUP, intentOf(card.contentIntent).getStringExtra(Notifications.EXTRA_ROUTE))
        assertEquals("Setup needs finishing", requireNotNull(card.publicVersion).text())
    }

    @Test
    fun aStateChangeUpdatesTheOneCardAndTeardownRemovesIt() {
        val slot = NotificationSlot<CallState?>(manager, Notifications.CALL_ID)
        val states = MutableStateFlow<CallState?>(null)

        slot.follow(states, CoroutineScope(Dispatchers.Main)) { Notifications.call(context, it) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Call bridge ready", shadowOf(manager).getNotification(Notifications.CALL_ID).title())

        states.value = call(CallPhase.ACTIVE)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Call in progress", shadowOf(manager).getNotification(Notifications.CALL_ID).title())
        assertEquals(1, shadowOf(manager).allNotifications.size)

        slot.close()
        assertNull(shadowOf(manager).getNotification(Notifications.CALL_ID))
    }

    @Test
    fun aPostThatWasBuildingWhenTeardownRanNeverLandsAfterTheRemoval() {
        val slot = NotificationSlot<Int>(manager, Notifications.CALL_ID)
        val building = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)

        val job = slot.follow(kotlinx.coroutines.flow.flowOf(0), CoroutineScope(Dispatchers.Default)) {
            building.countDown()
            assertTrue("the test never released the post", release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Notifications.call(context, call(CallPhase.ACTIVE))
        }

        assertTrue("the post never started", building.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // Exactly what both services do in onDestroy, with the post still in flight.
        slot.close()
        release.countDown()
        kotlinx.coroutines.runBlocking { job.join() }

        assertNull("a post that outlived teardown recreated the card", shadowOf(manager).getNotification(Notifications.CALL_ID))
    }

    @Test
    fun theLinkServicePostsItsCardInTheForegroundAndRemovesItOnTeardown() {
        val controller = org.robolectric.Robolectric.buildService(BridgeService::class.java).create()

        assertEquals(
            "No link to the computer",
            shadowOf(manager).getNotification(Notifications.BRIDGE_ID).title()
        )

        controller.destroy()

        assertNull(shadowOf(manager).getNotification(Notifications.BRIDGE_ID))
    }

    @Test
    fun theCallServicePostsItsCardInTheForegroundAndRemovesItOnTeardown() {
        val controller = org.robolectric.Robolectric.buildService(BridgeInCallService::class.java).create()

        assertEquals(
            "Call bridge ready",
            shadowOf(manager).getNotification(Notifications.CALL_ID).title()
        )

        controller.destroy()

        assertNull(shadowOf(manager).getNotification(Notifications.CALL_ID))
    }

    private companion object {
        const val NOW = 1_796_500_000_000L
    }
}
