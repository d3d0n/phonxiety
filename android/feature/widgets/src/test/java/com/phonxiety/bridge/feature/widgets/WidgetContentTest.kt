package com.phonxiety.bridge.feature.widgets

import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import com.phonxiety.bridge.model.CallSummary
import com.phonxiety.bridge.model.LinkError
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The widget's four lines, derived from one state sample. */
class WidgetContentTest {
    private val now = 1_796_500_000_000L

    private fun up(path: PathKind, rtt: Long?, reconnects: Int = 1) = LinkState.Up(
        kind = LinkKind.IROH,
        path = path,
        address = "iroh 9f42c1",
        rttMs = rtt,
        sinceEpochMs = now - 60_000,
        reconnects = reconnects
    )

    @Test
    fun anUnpairedPhoneAsksForPairing() {
        val content = WidgetContentMapper.map(BridgeState(), paired = false, lastCall = null, nowEpochMs = now)
        assertEquals("Not paired", content.linkLabel)
        assertTrue(content.pathLabel.isEmpty())
        assertEquals(WidgetTone.IDLE, content.tone)
        assertNull(content.call)
        assertTrue(content.footer.contains("Pair"))
    }

    @Test
    fun aDirectLinkShowsTheMeasurement() {
        val content = WidgetContentMapper.map(
            bridge = BridgeState(link = up(PathKind.DIRECT, 38)),
            paired = true,
            lastCall = null,
            nowEpochMs = now
        )
        assertEquals("Linked", content.linkLabel)
        assertEquals("direct · 38 ms", content.pathLabel)
        assertEquals(WidgetTone.GOOD, content.tone)
        assertEquals("No calls yet", content.footer)
    }

    @Test
    fun aRelayLinkIsAWarningWithItsLatency() {
        val content = WidgetContentMapper.map(
            bridge = BridgeState(link = up(PathKind.RELAY, 182, reconnects = 4)),
            paired = true,
            lastCall = null,
            nowEpochMs = now
        )
        assertEquals("relay · 182 ms", content.pathLabel)
        assertEquals(WidgetTone.WARN, content.tone)
    }

    @Test
    fun anUnmeasuredPathStillRenders() {
        val content = WidgetContentMapper.map(
            bridge = BridgeState(link = up(PathKind.UNKNOWN, null)),
            paired = true,
            lastCall = null,
            nowEpochMs = now
        )
        assertEquals("path unknown", content.pathLabel)
        assertEquals(WidgetTone.WARN, content.tone)
    }

    @Test
    fun offlineNamesTheErrorCode() {
        val content = WidgetContentMapper.map(
            bridge = BridgeState(
                link = LinkState.Offline,
                lastError = LinkError("link_down", "the daemon is not answering", now)
            ),
            paired = true,
            lastCall = null,
            nowEpochMs = now
        )
        assertEquals("Offline", content.linkLabel)
        assertEquals("link_down", content.pathLabel)
        assertEquals(WidgetTone.BAD, content.tone)
    }

    @Test
    fun connectingSaysWhichTransport() {
        val content = WidgetContentMapper.map(
            bridge = BridgeState(link = LinkState.Connecting(LinkKind.IROH)),
            paired = true,
            lastCall = null,
            nowEpochMs = now
        )
        assertEquals("Connecting", content.linkLabel)
        assertEquals("iroh", content.pathLabel)
        assertEquals(WidgetTone.WARN, content.tone)
    }

    @Test
    fun aLiveCallCarriesItsTimerAndTheHangUpHint() {
        val call = CallState(
            callId = "call-1",
            destinationMasked = "+998 •• •• 4412",
            phase = CallPhase.ACTIVE,
            startedAtEpochMs = now - 83_000
        )
        val content = WidgetContentMapper.map(
            bridge = BridgeState(link = up(PathKind.DIRECT, 40), call = call),
            paired = true,
            lastCall = CallSummary(callId = "older", callOutcome = "answered"),
            nowEpochMs = now
        )
        assertEquals("+998 •• •• 4412", content.call?.title)
        assertEquals("01:23", content.call?.elapsed)
        assertTrue(requireNotNull(content.footer).contains("hang up"))
    }

    @Test
    fun anEndedCallIsNotShownAsLive() {
        val call = CallState(
            callId = "call-1",
            destinationMasked = "+998 •• •• 4412",
            phase = CallPhase.ENDED,
            startedAtEpochMs = now - 83_000
        )
        val content = WidgetContentMapper.map(
            bridge = BridgeState(link = up(PathKind.DIRECT, 40), call = call),
            paired = true,
            lastCall = null,
            nowEpochMs = now
        )
        assertNull(content.call)
    }

    @Test
    fun theLastCallShowsOutcomeAndDuration() {
        val summary = CallSummary(
            callId = "call-9",
            callOutcome = "answered",
            taskOutcome = "agreed",
            durationSeconds = 134.0
        )
        val content = WidgetContentMapper.map(
            bridge = BridgeState(link = up(PathKind.DIRECT, 40)),
            paired = true,
            lastCall = summary,
            nowEpochMs = now
        )
        assertEquals("last: answered · 02:14", content.footer)
    }

    @Test
    fun theTimerFormatsHoursAndClampsNegatives() {
        assertEquals("00:00", WidgetContentMapper.elapsed(null, now))
        assertEquals("00:00", WidgetContentMapper.elapsed(now + 5_000, now))
        assertEquals("59:59", WidgetContentMapper.elapsed(now - 3_599_000, now))
        assertEquals("1:00:00", WidgetContentMapper.elapsed(now - 3_600_000, now))
        assertEquals("12:34:56", WidgetContentMapper.elapsed(now - 45_296_000, now))
    }
}
