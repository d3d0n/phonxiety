package com.phonxiety.bridge.net

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link's destructive action runs at most once, serialized with its own
 * cancellation, and only ever recycles the endpoint generation it bound — a
 * stale callback that lost the race to replacement must not close the endpoint
 * the replacement dialled through.
 */
class IrohLinkLifecycleTest {
    private class Registry {
        private val monitor = Any()
        private var current = 0L
        private val recycled = mutableListOf<Long>()

        fun bind(): Long = synchronized(monitor) { ++current }

        fun recycle(generation: Long): Boolean = synchronized(monitor) {
            if (current != generation) return false
            recycled += generation
            current = 0L
            true
        }

        fun recycled(): List<Long> = synchronized(monitor) { recycled.toList() }

        fun currentGeneration(): Long = synchronized(monitor) { current }
    }

    @Test
    fun anEscalationAfterCancellationDoesNotRecycleTheReplacementEndpoint() {
        val registry = Registry()
        val first = registry.bind()
        val lifecycle = IrohLinkLifecycle(
            recycleEndpoint = registry::recycle,
            isClosed = { false }
        )
        lifecycle.bind(first)

        // The link is cancelled and a replacement dials a fresh endpoint.
        lifecycle.close()
        val replacement = registry.bind()
        var reported = false

        val outcome = lifecycle.run(recycle = true) { reported = true }

        assertEquals(IrohActionOutcome.LINK_CLOSED, outcome)
        assertFalse(reported)
        assertTrue(registry.recycled().isEmpty())
        assertEquals(replacement, registry.currentGeneration())
    }

    @Test
    fun aLinkCannotRecycleAnEndpointBoundAfterIt() {
        val registry = Registry()
        val first = registry.bind()
        val lifecycle = IrohLinkLifecycle(
            recycleEndpoint = registry::recycle,
            isClosed = { false }
        )
        lifecycle.bind(first)
        // A replacement already rebound the process-wide endpoint even though
        // this link was not closed yet: its generation is no longer current.
        registry.bind()

        val outcome = lifecycle.run(recycle = true) { }

        assertEquals(IrohActionOutcome.EXECUTED, outcome)
        assertTrue(registry.recycled().isEmpty())
    }

    @Test
    fun cancellationWaitsForAnInFlightRecycle() {
        val registry = Registry()
        val generation = registry.bind()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val lifecycle = IrohLinkLifecycle(
            recycleEndpoint = { target ->
                started.countDown()
                // The cancellation request arrives while the endpoint recycle
                // is being applied.
                release.await(5, TimeUnit.SECONDS)
                registry.recycle(target)
            },
            isClosed = { false }
        )
        lifecycle.bind(generation)

        val escalation = Thread {
            lifecycle.run(recycle = true) { }
        }
        escalation.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val canceller = Thread {
            lifecycle.close()
            cancelled.countDown()
        }
        canceller.start()
        // The cancellation is serialized behind the recycle in flight.
        assertFalse(cancelled.await(100, TimeUnit.MILLISECONDS))

        release.countDown()
        escalation.join(5_000)
        canceller.join(5_000)
        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        assertEquals(listOf(generation), registry.recycled())
    }

    @Test
    fun reportingDownIsNotHeldUnderTheLifecycleMonitor() {
        val registry = Registry()
        val lifecycle = IrohLinkLifecycle(
            recycleEndpoint = registry::recycle,
            isClosed = { false }
        )
        lifecycle.bind(registry.bind())
        val insideReport = CountDownLatch(1)
        val release = CountDownLatch(1)

        val escalation = Thread {
            // fail() -> supervisor -> link cancel() must not deadlock on this.
            lifecycle.run(recycle = false) {
                insideReport.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        escalation.start()
        assertTrue(insideReport.await(5, TimeUnit.SECONDS))

        val cancelled = CountDownLatch(1)
        val canceller = Thread {
            lifecycle.close()
            cancelled.countDown()
        }
        canceller.start()
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))

        release.countDown()
        escalation.join(5_000)
    }

    @Test
    fun aClosedLinkFromElsewhereIsNotActedOn() {
        val registry = Registry()
        var closed = false
        val lifecycle = IrohLinkLifecycle(
            recycleEndpoint = registry::recycle,
            isClosed = { closed }
        )
        lifecycle.bind(registry.bind())

        closed = true
        val outcome = lifecycle.run(recycle = true) { }

        assertEquals(IrohActionOutcome.LINK_CLOSED, outcome)
        // close() from the link's own failure path is enough, no extra call needed.
        lifecycle.close()
        assertTrue(registry.recycled().isEmpty())
    }

    @Test
    fun aReportDownRunsOnceAndOnlyWhileLive() {
        val registry = Registry()
        val lifecycle = IrohLinkLifecycle(
            recycleEndpoint = registry::recycle,
            isClosed = { false }
        )
        lifecycle.bind(registry.bind())
        var reports = 0

        assertEquals(IrohActionOutcome.EXECUTED, lifecycle.run(recycle = false) { reports++ })

        // Reporting down closes the link through fail(); the second call is stale.
        lifecycle.close()
        assertEquals(IrohActionOutcome.LINK_CLOSED, lifecycle.run(recycle = false) { reports++ })
        assertEquals(1, reports)
    }

    @Test
    fun whenLiveActsOnlyWhileTheLinkCanStillAct() {
        var closed = false
        var acted = 0
        val lifecycle = IrohLinkLifecycle(
            recycleEndpoint = { false },
            isClosed = { closed }
        )

        val live = lifecycle.whenLive {
            acted++
            7
        }
        assertEquals(7, live)
        assertEquals(1, acted)

        // A cancellation from the link's own failure path wins before the act.
        lifecycle.close()
        assertNull(lifecycle.whenLive { acted++ })
        assertEquals(1, acted)

        // So does a terminal state reported from elsewhere.
        closed = true
        assertNull(lifecycle.whenLive { acted++ })
        assertEquals(1, acted)
    }
}
