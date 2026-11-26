package com.phonxiety.bridge

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.feature.onboarding.SetupPreferences
import com.phonxiety.bridge.feature.onboarding.setupProgressStore
import com.phonxiety.bridge.root.InstallOperation
import com.phonxiety.bridge.root.RootId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The reminder the reboot leaves behind: its own slot, its own channel, and silence — not a crash
 * — when the owner refused notifications or finished setup in the meantime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupResumeReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val app: Application
        get() = context as Application

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private val boot: Intent
        get() = Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(context.packageName)

    @Before
    fun clearSetup() {
        SetupPreferences(context).reset()
        setupProgressStore(context).clear()
        manager.cancelAll()
    }

    private fun installInFlight() {
        setupProgressStore(context).begin(InstallOperation.INSTALL, RootId.MAGISK)
    }

    @Test
    fun aRebootDuringAnInstallPostsTheSetupCardInItsOwnSlot() {
        installInFlight()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        SetupResumeReceiver().onReceive(context, boot)

        val card = requireNotNull(shadowOf(manager).getNotification(Notifications.SETUP_ID)) { "no setup reminder" }
        assertEquals(Notifications.SETUP_CHANNEL, card.channelId)
        assertEquals(
            Notifications.ROUTE_SETUP,
            shadowOf(card.contentIntent).savedIntent.getStringExtra(Notifications.EXTRA_ROUTE)
        )
        assertNull("the reminder replaced the device-link card", shadowOf(manager).getNotification(Notifications.BRIDGE_ID))
    }

    @Test
    fun theReminderNamesItsChannelForTheOwner() {
        installInFlight()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        SetupResumeReceiver().onReceive(context, boot)

        assertNotNull(manager.getNotificationChannel(Notifications.SETUP_CHANNEL))
        assertEquals(Notifications.SETUP_CHANNEL, manager.getNotificationChannel(Notifications.SETUP_CHANNEL).id)
    }

    @Test
    fun aRefusedNotificationPermissionPostsNothingAndDoesNotCrash() {
        installInFlight()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        SetupResumeReceiver().onReceive(context, boot)

        assertNull(shadowOf(manager).getNotification(Notifications.SETUP_ID))
    }

    @Test
    fun aFinishedSetupIsNotReminded() {
        installInFlight()
        SetupPreferences(context).ready = true
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        SetupResumeReceiver().onReceive(context, boot)

        assertNull(shadowOf(manager).getNotification(Notifications.SETUP_ID))
    }

    @Test
    fun noInstallInFlightIsNotReminded() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        SetupResumeReceiver().onReceive(context, boot)

        assertNull(shadowOf(manager).getNotification(Notifications.SETUP_ID))
    }

    @Test
    fun anUnrelatedBroadcastIsIgnored() {
        installInFlight()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        SetupResumeReceiver().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))

        assertNull(shadowOf(manager).getNotification(Notifications.SETUP_ID))
    }
}
