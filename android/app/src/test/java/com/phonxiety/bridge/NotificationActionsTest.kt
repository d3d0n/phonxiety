package com.phonxiety.bridge

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.feature.widgets.CallEnd
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
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
 * is final and has no public constructor — so the enders are the seams: the tests prove the daemon
 * failure is contained, the receiver completes, and the Telecom fallback is the one attempted.
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
    fun aThrowingDaemonStillFallsBackToTelecomThroughTheReceiverAndFinishes() {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val telecom = AtomicInteger(0)

        val receiver = NotificationActions(
            endCall = { appContext ->
                CallEnd.hangUp(
                    appContext,
                    callId = "call-1",
                    linkUp = true,
                    daemon = { throw IOException("the daemon did not answer") },
                    telecom = { telecom.incrementAndGet() }
                )
            },
            scope = scope
        )

        receiver.onReceive(context, hangUp)
        scheduler.advanceUntilIdle()

        assertEquals("the Telecom fallback was not attempted", 1, telecom.get())
        assertTrue(
            "the receiver left work running",
            scope.coroutineContext.job.children.none { it.isActive }
        )
    }

    @Test
    fun aDaemonAnswerEndsTheCallWithoutTouchingTelecom() = runTest {
        val telecom = AtomicInteger(0)

        CallEnd.hangUp(
            context,
            callId = "call-1",
            linkUp = true,
            daemon = { ApiOutcome.Ok(200, Unit) },
            telecom = { telecom.incrementAndGet() }
        )

        assertEquals(0, telecom.get())
    }

    @Test
    fun aThrowingDaemonFallsBackToTelecom() = runTest {
        val telecom = AtomicInteger(0)

        CallEnd.hangUp(
            context,
            callId = "call-1",
            linkUp = true,
            daemon = { throw IOException("the daemon did not answer") },
            telecom = { telecom.incrementAndGet() }
        )

        assertEquals(1, telecom.get())
    }

    @Test
    fun aDaemonFailureOutcomeFallsBackToTelecom() = runTest {
        val telecom = AtomicInteger(0)

        CallEnd.hangUp(
            context,
            callId = "call-1",
            linkUp = true,
            daemon = { ApiOutcome.Failure(500, "daemon_error", "no") },
            telecom = { telecom.incrementAndGet() }
        )

        assertEquals(1, telecom.get())
    }

    @Test
    fun withNoCallThereIsNothingTheDaemonCouldEndSoTelecomIsUsed() = runTest {
        val telecom = AtomicInteger(0)
        val daemon = AtomicInteger(0)

        CallEnd.hangUp(
            context,
            callId = null,
            linkUp = true,
            daemon = {
                daemon.incrementAndGet()
                ApiOutcome.Ok(200, Unit)
            },
            telecom = { telecom.incrementAndGet() }
        )

        assertEquals(0, daemon.get())
        assertEquals(1, telecom.get())
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
