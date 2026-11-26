package com.phonxiety.bridge

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Hang up button: the intent the card sends, the receiver that receives it, and what the
 * receiver does when the hang-up work fails.
 *
 * The Telecom half of `CallEnd` (`Call.disconnect()`) cannot run on the JVM — `android.telecom.Call`
 * is final and has no public constructor, so no test can hand it a call to disconnect. These tests
 * cover the receiver's own contract; the disconnect itself belongs to the device pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val hangUp: Intent
        get() {
            val card = Notifications.call(
                context,
                CallState("call-1", "+******4567", CallPhase.ACTIVE, NOW)
            )
            return requireNotNull(shadowOf(card.actions.single().actionIntent).savedIntent)
        }

    @Test
    fun theCallCardsHangUpReachesTheReceiverAndRunsTheOneHangUp() {
        val ran = CountDownLatch(1)
        val seen = AtomicReference<Context>()

        NotificationActions { appContext ->
            seen.set(appContext)
            ran.countDown()
        }.onReceive(context, hangUp)

        assertTrue("the hang-up did not run", ran.await(5, TimeUnit.SECONDS))
        assertEquals(context.packageName, seen.get().packageName)
    }

    @Test
    fun aDaemonFailureDoesNotEscapeTheReceiver() {
        val ran = CountDownLatch(1)
        val receiver = NotificationActions {
            ran.countDown()
            throw IOException("the daemon did not answer")
        }

        // The broadcast returns first; the work runs on the receiver's own scope and is contained.
        receiver.onReceive(context, hangUp)

        assertTrue("the hang-up did not run", ran.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun theSystemFindsTheReceiverInTheManifest() {
        val app = context as Application

        val declared = shadowOf(app).registeredReceivers.any { it.broadcastReceiver is NotificationActions }

        assertTrue("the manifest does not declare the hang-up receiver", declared)
    }

    @Test
    fun anotherBroadcastsIsIgnored() {
        val ran = CountDownLatch(1)

        NotificationActions { ran.countDown() }
            .onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertFalse("an unrelated broadcast ran the hang-up", ran.await(200, TimeUnit.MILLISECONDS))
    }

    private companion object {
        const val NOW = 1_796_500_000_000L
    }
}
