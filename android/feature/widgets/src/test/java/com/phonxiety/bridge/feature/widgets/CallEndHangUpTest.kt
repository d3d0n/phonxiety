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
 * The daemon half of the Hang up button: one attempt, bounded, and Telecom only when that
 * attempt did not end the call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallEndHangUpTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theDaemonIsAskedOnceAndAnAnswerEndsTheCall() = runTest {
        val daemon = AtomicInteger(0)
        val telecom = AtomicInteger(0)

        CallEnd.hangUp(
            context,
            callId = "call-1",
            daemon = {
                daemon.incrementAndGet()
                ApiOutcome.Ok(200, Unit)
            },
            telecom = { telecom.incrementAndGet() }
        )

        assertEquals("the daemon was not asked exactly once", 1, daemon.get())
        assertEquals("Telecom was used although the daemon ended the call", 0, telecom.get())
    }

    @Test
    fun aDaemonThatDoesNotEndTheCallIsNotRetriedAndTelecomRuns() = runTest {
        val daemon = AtomicInteger(0)
        val telecom = AtomicInteger(0)

        CallEnd.hangUp(
            context,
            callId = "call-1",
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
        val telecom = AtomicInteger(0)

        CallEnd.hangUp(
            context,
            callId = "call-1",
            daemon = { awaitCancellation() },
            telecom = { telecom.incrementAndGet() }
        )

        assertEquals("the daemon attempt was not bounded at 5 s", 5_000L, testScheduler.currentTime)
        assertEquals("the Telecom fallback did not run", 1, telecom.get())
    }
}
