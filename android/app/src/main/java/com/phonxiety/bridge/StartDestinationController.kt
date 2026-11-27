package com.phonxiety.bridge

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Chooses the host's first destination while the splash covers the gap.
 *
 * The wizard's journal is the only ground truth, and it is read once, outside composition, so the
 * first frame is never a disk read. The splash is held for at most [deadlineMs]; a read that
 * misses the deadline renders the dashboard, whose setup card leads back into the wizard, instead
 * of leaving the owner on a splash that never ends.
 */
internal class StartDestinationController(
    private val deadlineMs: Long = SPLASH_DEADLINE_MS
) {
    private val resolved = MutableStateFlow<NavKey?>(null)

    /** The host's start destination, or `null` while the splash is still covering the read. */
    val start: StateFlow<NavKey?> = resolved.asStateFlow()

    /** Whether the splash is still covering an unresolved start. */
    fun keepsSplash(): Boolean = resolved.value == null

    /**
     * Runs one read. Returns when the read answers or the deadline passes, whichever is first;
     * the host renders from [start] either way.
     */
    suspend fun resolve(read: suspend () -> Boolean) {
        val ready = withTimeoutOrNull(deadlineMs) { read() }
        resolved.value = if (ready == false) OnboardingKey() else DashboardKey
    }

    companion object {
        /** The longest the splash may stay up for this read. */
        const val SPLASH_DEADLINE_MS = 400L
    }
}
