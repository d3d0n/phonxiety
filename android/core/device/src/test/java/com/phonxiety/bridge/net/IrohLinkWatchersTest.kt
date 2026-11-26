package com.phonxiety.bridge.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Publication of the link's late-bound subscriptions.  A reader can fail the
 * link before Android's network watch or the path monitor finishes
 * registering; those handles must then be closed at once instead of
 * surviving the link through their own native lifetimes.
 */
class IrohLinkWatchersTest {
    @Test
    fun aHandleOfferedAfterFailureIsRefusedAndClosedByItsCaller() {
        var failed = false
        val watchers = IrohLinkWatchers { failed }
        val monitor = IrohPathMonitor(
            IrohEscalationPolicy({ 0L }),
            object : LinkScheduler {
                override fun after(delayMs: Long, task: () -> Unit) = LinkTask { }
            },
            { false }
        ) { true }
        assertTrue(watchers.attachMonitor(monitor))

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
    fun detachingReleasesEveryAttachedHandleAndStopsAcceptingMore() {
        val watchers = IrohLinkWatchers { false }
        var network = 0
        var monitor = 0
        val pathMonitor = IrohPathMonitor(
            IrohEscalationPolicy({ 0L }),
            object : LinkScheduler {
                override fun after(delayMs: Long, task: () -> Unit) = LinkTask { }
            },
            { false }
        ) { true }

        assertTrue(watchers.attachNetworkWatch { network++ })
        assertTrue(watchers.attachMonitor(pathMonitor))
        assertSame(pathMonitor, watchers.monitor())

        val releases = watchers.detachAll()
        assertEquals(2, releases.size)
        releases.forEach { release -> release() }
        assertEquals(1, network)

        // The monitor is invisible as soon as the link is detached.
        assertNull(watchers.monitor())
        // A second detach has nothing left, and new handles are refused.
        assertTrue(watchers.detachAll().isEmpty())
        assertFalse(watchers.attachNetworkWatch { network++ })
        assertFalse(watchers.attachMonitor(pathMonitor))
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
        assertNull(watchers.monitor())
    }
}
