package com.phonxiety.bridge.feature.widgets

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.BridgeRuntime
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The daemon half of the Hang up button: one attempt over a live link, bounded, and the local end
 * of the engine-owned call when the daemon did not succeed or when there is no link to ask over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallEndHangUpTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aDaemonSuccessSkipsTheLocalEnd() = runTest {
        val daemon = AtomicInteger(0)
        val local = AtomicInteger(0)

        CallEnd.hangUp(
            context,
            callId = "call-1",
            linkUp = true,
            daemon = {
                daemon.incrementAndGet()
                ApiOutcome.Ok(200, Unit)
            },
            local = { _ -> local.incrementAndGet() }
        )

        assertEquals("the daemon was not asked exactly once", 1, daemon.get())
        assertEquals("the local end ran although the daemon succeeded", 0, local.get())
    }

    @Test
    fun aDaemonThatDoesNotSucceedIsNotRetriedAndTheLocalEndRuns() = runTest {
        val daemon = AtomicInteger(0)
        val local = AtomicInteger(0)

        CallEnd.hangUp(
            context,
            callId = "call-1",
            linkUp = true,
            daemon = {
                daemon.incrementAndGet()
                ApiOutcome.Failure(500, "daemon_error", "no")
            },
            local = { _ -> local.incrementAndGet() }
        )

        assertEquals("the daemon was not asked exactly once", 1, daemon.get())
        assertEquals("the local end did not run", 1, local.get())
    }

    @Test
    fun aDaemonThatNeverAnswersGivesUpAtTheBoundAndUsesTheLocalEnd() = runTest {
        val daemon = AtomicInteger(0)
        val local = AtomicInteger(0)

        CallEnd.hangUp(
            context,
            callId = "call-1",
            linkUp = true,
            daemon = {
                daemon.incrementAndGet()
                awaitCancellation()
            },
            local = { _ -> local.incrementAndGet() }
        )

        assertEquals("the daemon was not asked exactly once", 1, daemon.get())
        assertEquals("the daemon attempt was not bounded at 5 s", 5_000L, testScheduler.currentTime)
        assertEquals("the local end did not run", 1, local.get())
    }

    @Test
    fun withoutALinkTheDaemonIsNotAskedAndTheLocalEndRunsAtOnce() = runTest {
        val daemon = AtomicInteger(0)
        val local = AtomicInteger(0)

        CallEnd.hangUp(
            context,
            callId = "call-1",
            linkUp = false,
            daemon = {
                daemon.incrementAndGet()
                awaitCancellation()
            },
            local = { _ -> local.incrementAndGet() }
        )

        assertEquals("the daemon was asked without a link", 0, daemon.get())
        assertEquals("the hang-up waited for a link", 0L, testScheduler.currentTime)
        assertEquals("the local end did not run", 1, local.get())
    }

    /**
     * The fallback ends the call captured when the button was pressed, never whatever call the
     * state names when the daemon attempt gives up: A's hang-up must not reach B after a turnover.
     */
    @Test
    fun theFallbackEndsTheCallCapturedAtEntryAcrossATurnover() = runTest {
        val state = runtimeState()
        val seen = mutableListOf<String?>()
        try {
            CallEnd.hangUp(
                context,
                callId = "A",
                linkUp = true,
                daemon = {
                    // The engine's call turns over to B while the request waits.
                    state.value = BridgeState(call = CallState("B", "+******0000", CallPhase.ACTIVE, 0))
                    awaitCancellation()
                },
                local = { id -> seen += id }
            )
        } finally {
            state.value = BridgeState()
        }

        assertEquals("the fallback did not end the call captured at entry", listOf<String?>("A"), seen)
    }

    /** The runtime's own state flow, the process-wide value the hang-up's default id reads. */
    private fun runtimeState(): MutableStateFlow<BridgeState> {
        val field = BridgeRuntime::class.java.getDeclaredField("mutableState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(BridgeRuntime) as MutableStateFlow<BridgeState>
    }
}
