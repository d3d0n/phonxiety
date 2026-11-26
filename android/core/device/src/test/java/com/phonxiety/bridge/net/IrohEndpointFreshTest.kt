package com.phonxiety.bridge.net

import com.phonxiety.bridge.model.LinkKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The iroh endpoint's recovery account and the fresh-bind section behind it: a
 * budget with an injected clock, the iroh-only outcome route, and a cache whose
 * detach is decided under the attempt's own lifecycle monitor.
 */
class IrohEndpointFreshTest {
    private class ManualClock {
        var now = 0L
    }

    private val clock = ManualClock()

    private fun budget() = IrohFailureBudget { clock.now }

    private fun lifecycle(closed: Boolean = false) = IrohLinkLifecycle(
        recycleEndpoint = { false },
        isClosed = { false }
    ).also { if (closed) it.close() }

    // ------------------------------------------------------------------
    // Budget
    // ------------------------------------------------------------------

    @Test
    fun theFirstTripNeedsNoHistoryAndIsAllowedAtClockZero() {
        val budget = budget()
        assertFalse(budget.pending())
        assertFalse(budget.failed())
        assertFalse(budget.failed())
        assertTrue(budget.failed())
        assertTrue(budget.pending())
    }

    @Test
    fun anOpenResetsTheStreak() {
        val budget = budget()
        assertFalse(budget.failed())
        assertFalse(budget.failed())
        budget.opened()
        assertFalse(budget.failed())
        assertFalse(budget.failed())
        assertFalse(budget.pending())

        // The third consecutive failure after the open is the one that trips.
        assertTrue(budget.failed())
        assertTrue(budget.pending())
    }

    @Test
    fun aPendingRequestIsConsumedOnceAndOnlyOnce() {
        val budget = budget()
        repeat(3) { budget.failed() }
        assertTrue(budget.pending())

        // Further failures do not queue a second request.
        assertFalse(budget.failed())
        assertTrue(budget.pending())

        assertTrue(budget.consume())
        assertFalse(budget.pending())
        assertFalse(budget.consume())
    }

    @Test
    fun furtherTripsWaitSixtySecondsFromTheActualRecycle() {
        val budget = budget()
        repeat(3) { budget.failed() }
        // The request sits unconsumed for 50 s before a bind actually recycles.
        clock.now = 50_000
        assertTrue(budget.consume())

        // 50 s after the request but only 50 s after the recycle: refused.
        clock.now = 100_000
        assertFalse(budget.failed())
        assertFalse(budget.pending())

        // Exactly at the boundary measured from the recycle: allowed.
        clock.now = 110_000
        assertTrue(budget.failed())
        assertTrue(budget.pending())
    }

    @Test
    fun theBoundaryIsExact() {
        val budget = budget()
        repeat(3) { budget.failed() }
        assertTrue(budget.consume())

        clock.now = IrohFailureBudget.COOLDOWN_MS - 1
        assertFalse(budget.failed())
        assertFalse(budget.pending())

        clock.now = IrohFailureBudget.COOLDOWN_MS
        assertTrue(budget.failed())
        assertTrue(budget.pending())
    }

    // ------------------------------------------------------------------
    // Outcome routing
    // ------------------------------------------------------------------

    @Test
    fun onlyKnownIrohOutcomesReachTheIrohFactory() {
        val reported = mutableListOf<AttemptOutcome>()
        val iroh = object : DeviceLinkFactory {
            override fun create(): DeviceLink = error("the router only reports outcomes")

            override fun shutdown() = Unit

            override fun attemptEnded(outcome: AttemptOutcome, kind: LinkKind?) {
                reported += outcome
            }
        }
        val router = IrohAttemptOutcomes { iroh }

        router.report(AttemptOutcome.FAILED_BEFORE_OPEN, null)
        router.report(AttemptOutcome.FAILED_BEFORE_OPEN, LinkKind.WEBSOCKET)
        router.report(AttemptOutcome.OPENED, LinkKind.TAILSCALE)
        assertTrue(reported.isEmpty())

        router.report(AttemptOutcome.FAILED_BEFORE_OPEN, LinkKind.IROH)
        router.report(AttemptOutcome.OPENED, LinkKind.IROH)
        assertEquals(listOf(AttemptOutcome.FAILED_BEFORE_OPEN, AttemptOutcome.OPENED), reported)
    }

    @Test
    fun noIrohFactoryMeansNothingToReport() {
        val router = IrohAttemptOutcomes { null }
        router.report(AttemptOutcome.FAILED_BEFORE_OPEN, LinkKind.IROH)
    }

    // ------------------------------------------------------------------
    // Fresh-bind section
    // ------------------------------------------------------------------

    @Test
    fun aStaleFreshAttemptNeverRecyclesItsReplacementEndpoint() = runBlocking {
        val budget = budget()
        repeat(3) { budget.failed() }

        val detached = mutableListOf<Long>()
        val cache = IrohEndpointCache<Long> { handle, _ -> detached += handle }
        assertEquals(1L, cache.acquire { it })

        // The fresh attempt is retired before it can bind; its replacement then
        // opens on the cached endpoint.
        val stale = lifecycle(closed = true)
        val replacement = lifecycle()
        replacement.bind(1L)
        assertEquals(1L, cache.acquire { it })

        // The stale attempt resumes: it must not detach what the replacement uses.
        assertEquals(1L, cache.acquire(BudgetFreshBind(budget, stale)) { it })
        assertTrue(detached.isEmpty())
        // Nor may it lose the request it failed to consume.
        assertTrue(budget.pending())

        // A live attempt still consumes it, once.
        assertEquals(2L, cache.acquire(BudgetFreshBind(budget, replacement)) { it })
        assertEquals(listOf(1L), detached)
        assertFalse(budget.pending())
    }

    @Test
    fun aPendingRequestSurvivesAnAttemptCancelledBeforeItBinds() = runBlocking {
        val budget = budget()
        repeat(3) { budget.failed() }

        val detached = mutableListOf<Long>()
        val cache = IrohEndpointCache<Long> { handle, _ -> detached += handle }
        assertEquals(1L, cache.acquire { it })

        val cancelled = lifecycle(closed = true)
        assertEquals(1L, cache.acquire(BudgetFreshBind(budget, cancelled)) { it })

        assertTrue(detached.isEmpty())
        assertTrue(budget.pending())
        // The cached endpoint is still there for the next live attempt.
        assertEquals(1L, cache.acquire { it })
    }

    @Test
    fun aBareBindConsumesTheRequestWhenNothingIsCached() = runBlocking {
        val budget = budget()
        repeat(3) { budget.failed() }

        val detached = mutableListOf<Long>()
        val cache = IrohEndpointCache<Long> { handle, _ -> detached += handle }
        val live = lifecycle()

        // No cached endpoint yet: the request is satisfied by the fresh bind.
        assertEquals(1L, cache.acquire(BudgetFreshBind(budget, live)) { it })
        assertTrue(detached.isEmpty())
        assertFalse(budget.pending())
    }

    @Test
    fun aGenerationScopedDetachNeverTouchesAReplacementEndpoint() = runBlocking {
        val cache = IrohEndpointCache<Long> { _, _ -> }
        assertEquals(1L, cache.acquire { it })

        assertEquals(1L, cache.detach(1L) { it })
        assertEquals(2L, cache.acquire { it })

        // The link that dialled generation 1 surfaces late: generation 2 is safe.
        assertNull(cache.detach(1L) { it })
        assertEquals(2L, cache.detach(2L) { it })
    }
}
