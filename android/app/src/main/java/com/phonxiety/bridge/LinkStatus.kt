package com.phonxiety.bridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the link strip currently says. */
internal sealed interface LinkStripState {
    /** The link is direct and healthy, so there is nothing to report. */
    data object Hidden : LinkStripState

    data class Visible(val summary: LinkSummary) : LinkStripState
}

/** How the link is doing, without the addresses and counters the strip does not print. */
internal data class LinkSummary(
    val level: LinkLevel,
    val path: PathKind? = null,
    val rttMs: Long? = null
)

internal enum class LinkLevel { OFFLINE, CONNECTING, UP }

/**
 * The strip's copy and tone are decided by this mapping alone, so two states that read the same
 * are one state and cannot restart the hide delay.
 */
internal fun linkSummaryOf(link: LinkState): LinkSummary = when (link) {
    LinkState.Offline -> LinkSummary(LinkLevel.OFFLINE)
    is LinkState.Connecting -> LinkSummary(LinkLevel.CONNECTING)
    is LinkState.Up -> LinkSummary(LinkLevel.UP, link.path, link.rttMs)
}

/** A direct, connected link has nothing worth a strip once it settles. */
internal val LinkSummary.isHealthyDirect: Boolean
    get() = level == LinkLevel.UP && path == PathKind.DIRECT

/** How long a healthy direct link keeps its strip up before the strip hides itself. */
internal const val STRIP_HIDE_DELAY_MS = 1_500L

/**
 * The strip's states: every degraded link shows a strip, and a direct healthy link shows the
 * summary once, keeps it for [hideDelayMs] and then hides — a boot that came up clean still gets a
 * moment of confirmation instead of a flash of nothing.
 *
 * The hide timer is driven by the healthy-direct status alone. The round trip is sampled every
 * second, and its display updates must neither restart the timer nor bring back a strip that has
 * already hidden; only a change of the status does either.
 */
internal fun linkStripStates(
    links: Flow<LinkState>,
    hideDelayMs: Long = STRIP_HIDE_DELAY_MS
): Flow<LinkStripState> = channelFlow {
    var hidden = false
    var hideTimer: Job? = null
    links.map(::linkSummaryOf).distinctUntilChanged().collect { summary ->
        if (!summary.isHealthyDirect) {
            hidden = false
            hideTimer?.cancel()
            hideTimer = null
            send(LinkStripState.Visible(summary))
            return@collect
        }
        // A display-only change after the delay: the strip stays hidden, the timer untouched.
        if (hidden) return@collect
        send(LinkStripState.Visible(summary))
        if (hideTimer == null) {
            hideTimer = launch {
                delay(hideDelayMs)
                hidden = true
                send(LinkStripState.Hidden)
            }
        }
    }
}

/** The host-scoped strip: one collector over the bridge state while anything observes it. */
@HiltViewModel
internal class LinkStatusViewModel @Inject constructor(
    repository: BridgeStateRepository
) : ViewModel() {
    val state: StateFlow<LinkStripState> = linkStripStates(repository.state.map { it.link })
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = LinkStripState.Hidden
        )

    private companion object {
        /** A rotation or a trip to the background must not replay the strip's hide delay. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
