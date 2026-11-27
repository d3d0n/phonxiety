package com.phonxiety.bridge

import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The strip's rules: a degraded link always shows, a direct healthy link shows for the delay and
 * then hides, and two link states that read the same are one summary.
 *
 * The round trip is sampled every second. Its display updates must not restart the hide delay and
 * must not bring the strip back once it hid; only a change of the healthy-direct status may.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkStatusTest {
    private val links = MutableSharedFlow<LinkState>(replay = 1)

    @Test
    fun anOfflineLinkShowsTheStrip() = runTest {
        val states = collect()

        links.emit(LinkState.Offline)
        runCurrent()

        assertEquals(
            listOf<LinkStripState>(LinkStripState.Visible(LinkSummary(LinkLevel.OFFLINE))),
            states
        )
    }

    @Test
    fun aConnectingLinkShowsTheStrip() = runTest {
        val states = collect()

        links.emit(LinkState.Connecting(LinkKind.IROH))
        runCurrent()

        assertEquals(
            listOf<LinkStripState>(LinkStripState.Visible(LinkSummary(LinkLevel.CONNECTING))),
            states
        )
    }

    @Test
    fun aDirectHealthyLinkHidesTheStripAfterTheDelay() = runTest {
        val states = collect()

        links.emit(LinkState.Offline)
        runCurrent()
        links.emit(up(PathKind.DIRECT, rttMs = 24))
        runCurrent()

        // A clean boot gets a moment of confirmation, not a flash of nothing.
        assertEquals(
            listOf(
                LinkStripState.Visible(LinkSummary(LinkLevel.OFFLINE)),
                LinkStripState.Visible(LinkSummary(LinkLevel.UP, PathKind.DIRECT, rttMs = 24))
            ),
            states
        )

        advanceTimeBy(STRIP_HIDE_DELAY_MS - 1)
        runCurrent()
        assertEquals(2, states.size)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf<LinkStripState>(LinkStripState.Hidden), states.drop(2))
    }

    @Test
    fun roundTripSamplesDoNotRestartTheHideDelayOrBringTheStripBack() = runTest {
        val states = collect()

        links.emit(up(PathKind.DIRECT, rttMs = 24))
        runCurrent()
        assertEquals(1, states.size)

        // The link stays direct and healthy; only the round trip moves, every second.
        advanceTimeBy(600)
        links.emit(up(PathKind.DIRECT, rttMs = 25))
        runCurrent()
        advanceTimeBy(600)
        links.emit(up(PathKind.DIRECT, rttMs = 26))
        runCurrent()

        advanceTimeBy(299)
        runCurrent()
        assertEquals("a round trip sample restarted the hide delay", 3, states.size)
        assertEquals(0, states.count { it is LinkStripState.Hidden })

        advanceTimeBy(2)
        runCurrent()
        assertEquals(LinkStripState.Hidden, states.last())
        assertEquals(1, states.count { it is LinkStripState.Hidden })

        // A sample after the strip hid must not put it back on screen.
        advanceTimeBy(1_000)
        links.emit(up(PathKind.DIRECT, rttMs = 27))
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(LinkStripState.Hidden, states.last())
    }

    @Test
    fun aRelayLinkNeverHidesTheStrip() = runTest {
        val states = collect()

        links.emit(up(PathKind.RELAY, rttMs = 120))
        runCurrent()
        advanceTimeBy(STRIP_HIDE_DELAY_MS * 4)
        runCurrent()

        assertEquals(
            listOf<LinkStripState>(
                LinkStripState.Visible(LinkSummary(LinkLevel.UP, PathKind.RELAY, rttMs = 120))
            ),
            states
        )
    }

    @Test
    fun twoLinksThatReadTheSameAreOneSummary() = runTest {
        val states = collect()

        links.emit(up(PathKind.RELAY, rttMs = 120, address = "one"))
        runCurrent()
        links.emit(up(PathKind.RELAY, rttMs = 120, address = "two", sinceEpochMs = 42))
        runCurrent()

        // The address and the connect time changed; the strip's copy did not, so nothing re-emits.
        assertEquals(1, states.size)
    }

    @Test
    fun aDegradedLinkBringsTheStripBackAfterItHid() = runTest {
        val states = collect()

        links.emit(up(PathKind.DIRECT, rttMs = 24))
        runCurrent()
        advanceTimeBy(STRIP_HIDE_DELAY_MS + 1)
        runCurrent()
        links.emit(LinkState.Connecting(LinkKind.IROH))
        runCurrent()

        assertEquals(
            listOf(
                LinkStripState.Visible(LinkSummary(LinkLevel.UP, PathKind.DIRECT, rttMs = 24)),
                LinkStripState.Hidden,
                LinkStripState.Visible(LinkSummary(LinkLevel.CONNECTING))
            ),
            states
        )
    }

    private fun TestScope.collect(): List<LinkStripState> {
        val states = mutableListOf<LinkStripState>()
        backgroundScope.launch { linkStripStates(links).toList(states) }
        runCurrent()
        return states
    }

    private fun up(
        path: PathKind,
        rttMs: Long,
        address: String = "127.0.0.1",
        sinceEpochMs: Long = 0
    ): LinkState.Up = LinkState.Up(
        kind = LinkKind.IROH,
        path = path,
        address = address,
        rttMs = rttMs,
        sinceEpochMs = sinceEpochMs,
        reconnects = 0
    )
}
