package com.phonxiety.bridge.feature.widgets

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.data.ApiOutcome
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The daemon half of the Hang up button: one attempt over a live link, bounded, and Telecom when
 * the daemon did not succeed or when there is no link to ask over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallEndHangUpTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aDaemonSuccessSkipsTelecom() = runTest {
        val daemon = AtomicInteger(0)
        val telecom = AtomicInteger(0)

        CallEnd.hangUp(
            context,
            callId = "call-1",
            linkUp = true,
            daemon = {
                daemon.incrementAndGet()
                ApiOutcome.Ok(200, Unit)
            },
            telecom = { telecom.incrementAndGet() }
        )

        assertEquals("the daemon was not asked exactly once", 1, daemon.get())
        assertEquals("Telecom was used although the daemon succeeded", 0, telecom.get())
    }

    @Test
    fun aDaemonThatDoesNotSucceedIsNotRetriedAndTelecomRuns() = runTest {
        val daemon = AtomicInteger(0)
        val telecom = AtomicInteger(0)

        CallEnd.hangUp(
            context,
            callId = "call-1",
            linkUp = true,
            daemon = {
                daemon.incrementAndGet()
                ApiOutcome.Failure(500, "daemon_error", "no")
            },
            telecom = { telecom.incrementAndGet() }
        )

        assertEquals("the daemon was not asked exactly once", 1, daemon.get())
        assertEquals("the Telecom fallback did not run", 1, telecom.get())
    }

    @Test
    fun aDaemonThatNeverAnswersGivesUpAtTheBoundAndUsesTelecom() = runTest {
        val daemon = AtomicInteger(0)
        val telecom = AtomicInteger(0)

        CallEnd.hangUp(
            context,
            callId = "call-1",
            linkUp = true,
            daemon = {
                daemon.incrementAndGet()
                awaitCancellation()
            },
            telecom = { telecom.incrementAndGet() }
        )

        assertEquals("the daemon was not asked exactly once", 1, daemon.get())
        assertEquals("the daemon attempt was not bounded at 5 s", 5_000L, testScheduler.currentTime)
        assertEquals("the Telecom fallback did not run", 1, telecom.get())
    }

    @Test
    fun withoutALinkTheDaemonIsNotAskedAndTelecomRunsAtOnce() = runTest {
        val daemon = AtomicInteger(0)
        val telecom = AtomicInteger(0)

        CallEnd.hangUp(
            context,
            callId = "call-1",
            linkUp = false,
            daemon = {
                daemon.incrementAndGet()
                awaitCancellation()
            },
            telecom = { telecom.incrementAndGet() }
        )

        assertEquals("the daemon was asked without a link", 0, daemon.get())
        assertEquals("the hang-up waited for a link", 0L, testScheduler.currentTime)
        assertEquals("the Telecom fallback did not run", 1, telecom.get())
    }
}
