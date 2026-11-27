package com.phonxiety.bridge.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Publication of the link's late-bound subscription.  A reader can fail the
 * link before Android's network watch finishes registering; that handle must
 * then be closed at once instead of surviving the link through its own native
 * lifetime.
 */
class IrohLinkWatchersTest {
    @Test
    fun aHandleOfferedAfterFailureIsRefusedAndClosedByItsCaller() {
        var failed = false
        val watchers = IrohLinkWatchers { failed }

        // A read loop fails while the network watch is still being registered.
        failed = true
        var closed = 0
        val attached = watchers.attachNetworkWatch { closed++ }

        assertFalse(attached)
        // The caller closes it immediately when publication is refused.
        if (!attached) closed++
        assertEquals(1, closed)
    }

    @Test
    fun detachingReleasesTheAttachedHandleAndStopsAcceptingMore() {
        val watchers = IrohLinkWatchers { false }
        var network = 0

        assertTrue(watchers.attachNetworkWatch { network++ })

        val releases = watchers.detachAll()
        assertEquals(1, releases.size)
        releases.forEach { release -> release() }
        assertEquals(1, network)

        // A second detach has nothing left, and new handles are refused.
        assertTrue(watchers.detachAll().isEmpty())
        assertFalse(watchers.attachNetworkWatch { network++ })
    }

    @Test
    fun aLinkAlreadyDownRefusesPublicationWithoutBeingDetachedFirst() {
        val watchers = IrohLinkWatchers { true }
        var closed = 0
        val release: () -> Unit = { closed++ }

        if (!watchers.attachNetworkWatch(release)) release()

        // The caller closed it immediately; nothing is retained to detach.
        assertEquals(1, closed)
        assertTrue(watchers.detachAll().isEmpty())
    }
}
