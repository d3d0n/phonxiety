package com.phonxiety.bridge

import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The strip's rules: a degraded link always shows, a direct healthy link hides after the delay,
 * and two link states that read the same are one summary, so a reconnect that only changes the
 * address neither restarts the delay nor flickers the strip.
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

        links.emit(LinkState.Connecting(com.phonxiety.bridge.model.LinkKind.IROH))
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

        // The strip that was up stays up for the delay instead of vanishing on the first good frame.
        assertEquals(1, states.size)

        advanceTimeBy(STRIP_HIDE_DELAY_MS - 1)
        runCurrent()
        assertEquals(1, states.size)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf<LinkStripState>(LinkStripState.Hidden), states.drop(1))
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
        links.emit(LinkState.Connecting(com.phonxiety.bridge.model.LinkKind.IROH))
        runCurrent()

        assertEquals(
            listOf(
                LinkStripState.Hidden,
                LinkStripState.Visible(LinkSummary(LinkLevel.CONNECTING))
            ),
            states
        )
    }

    private fun kotlinx.coroutines.test.TestScope.collect(): List<LinkStripState> {
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
        kind = com.phonxiety.bridge.model.LinkKind.IROH,
        path = path,
        address = address,
        rttMs = rttMs,
        sinceEpochMs = sinceEpochMs,
        reconnects = 0
    )
}
