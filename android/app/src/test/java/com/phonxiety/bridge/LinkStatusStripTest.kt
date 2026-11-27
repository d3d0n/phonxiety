package com.phonxiety.bridge

import com.phonxiety.bridge.designsystem.component.LinkPath
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.model.PathKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the strip prints for each link state; the hide rule itself lives in [LinkStatusTest]. */
class LinkStatusStripTest {
    @Test
    fun anOfflineLinkReadsAsNoLink() {
        val copy = linkStripCopy(LinkSummary(LinkLevel.OFFLINE))

        assertEquals(R.string.link_strip_offline, copy.label)
        assertEquals(StatusTone.NEGATIVE, copy.tone)
        assertNull(copy.path)
    }

    @Test
    fun aConnectingLinkReadsAsAReconnectAndPulses() {
        val copy = linkStripCopy(LinkSummary(LinkLevel.CONNECTING))

        assertEquals(R.string.link_strip_connecting, copy.label)
        assertEquals(StatusTone.WARNING, copy.tone)
        assertTrue(copy.pulse)
    }

    @Test
    fun aConnectedLinkShowsItsPathInsteadOfALabel() {
        val relay = linkStripCopy(LinkSummary(LinkLevel.UP, PathKind.RELAY, rttMs = 120))
        val direct = linkStripCopy(LinkSummary(LinkLevel.UP, PathKind.DIRECT, rttMs = 24))

        assertNull(relay.label)
        assertEquals(LinkPath.RELAY, relay.path)
        assertEquals(LinkPath.DIRECT, direct.path)
    }
}
