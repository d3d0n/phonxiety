package com.phonxiety.bridge

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Chooses the host's first destination while the splash covers the gap.
 *
 * The wizard's journal is the only ground truth, and it is read once, outside composition, on a
 * worker so the first frame is never a disk read. A read can block its thread — the first
 * preference load does — which is why it cannot share the thread that runs the deadline: the
 * deadline is an independent main-thread timer, and the first of the two answers publishes the
 * start. A read that misses [deadlineMs] renders the dashboard, whose setup card leads back into
 * the wizard, instead of leaving the owner on a splash that never ends.
 */
internal class StartDestinationController(
    private val deadlineMs: Long = SPLASH_DEADLINE_MS,
    private val readDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val resolved = MutableStateFlow<NavKey?>(null)

    /** The worker that runs the read; the deadline does not wait for it. */
    private val readers = CoroutineScope(SupervisorJob() + readDispatcher)

    /** The host's start destination, or `null` while the splash is still covering the read. */
    val start: StateFlow<NavKey?> = resolved.asStateFlow()

    /** Whether the splash is still covering an unresolved start. */
    fun keepsSplash(): Boolean = resolved.value == null

    /**
     * Races one read against the deadline. The read runs on [readDispatcher] and the deadline on
     * the caller's dispatcher, so a blocking read cannot hold the timer. Whichever answers first
     * publishes the start exactly once; a late answer after the deadline finds the slot taken and
     * changes nothing.
     */
    suspend fun resolve(read: () -> Boolean) {
        val reader = readers.async { read() }
        val ready = withTimeoutOrNull(deadlineMs) {
            try {
                reader.await()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                null
            }
        }
        // A read still blocked past the deadline is abandoned: cancelling is all that is left, and
        // the host never waits for it.
        if (!reader.isCompleted) reader.cancel()
        resolved.compareAndSet(null, if (ready == false) OnboardingKey() else DashboardKey)
    }

    companion object {
        /** The longest the splash may stay up for this read. */
        const val SPLASH_DEADLINE_MS = 400L
    }
}
