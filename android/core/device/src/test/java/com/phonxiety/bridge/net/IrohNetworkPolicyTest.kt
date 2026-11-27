package com.phonxiety.bridge.net

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A network change may recycle the paired endpoint only while no call owns the
 * link and outside the cooldown, and only a live link may spend that cooldown.
 */
class IrohNetworkPolicyTest {
    private var now = 0L
    private val policy = IrohNetworkPolicy(clock = { now })

    @Test
    fun aRecycleHoldsTheNextOneForTheCooldown() {
        assertEquals(IrohNetworkDecision.RECYCLE, policy.admitRecycle(callOwned = false))
        now = IrohNetworkPolicy.COOLDOWN_MS - 1
        assertEquals(IrohNetworkDecision.HELD, policy.admitRecycle(callOwned = false))
        now = IrohNetworkPolicy.COOLDOWN_MS
        assertEquals(IrohNetworkDecision.RECYCLE, policy.admitRecycle(callOwned = false))
    }

    @Test
    fun aCallRefusesWithoutSpendingTheCooldown() {
        assertEquals(IrohNetworkDecision.DURING_CALL, policy.admitRecycle(callOwned = true))
        assertEquals(IrohNetworkDecision.RECYCLE, policy.admitRecycle(callOwned = false))
    }

    @Test
    fun aClosingLinkNeverAsksThePolicySoTheReplacementCanStillRecycle() {
        val recycled = mutableListOf<Long>()
        val record = { generation: Long -> recycled.add(generation) }
        val asked = AtomicInteger()
        // The old link is failing: its lifecycle is closed, while its link-down
        // publication (and the admission's finish) has not happened yet.
        val old = IrohLinkLifecycle(recycleEndpoint = record, isClosed = { false })
        old.bind(1)
        old.close()
        val stale = old.run(
            recycle = true,
            admit = { asked.incrementAndGet() == 0 || policy.admitRecycle(callOwned = false) == IrohNetworkDecision.RECYCLE }
        ) { }
        assertEquals(IrohActionOutcome.LINK_CLOSED, stale)
        assertEquals(0, asked.get())

        // The replacement's own network change is not held by a cooldown the
        // stale callback never spent.
        val replacement = IrohLinkLifecycle(recycleEndpoint = record, isClosed = { false })
        replacement.bind(2)
        val fresh = replacement.run(
            recycle = true,
            admit = { policy.admitRecycle(callOwned = false) == IrohNetworkDecision.RECYCLE }
        ) { }
        assertEquals(IrohActionOutcome.EXECUTED, fresh)
        assertEquals(listOf(2L), recycled)
    }

    @Test
    fun aRefusedRecycleTouchesNothing() {
        val recycled = mutableListOf<Long>()
        val record = { generation: Long -> recycled.add(generation) }
        var reported = false
        val live = IrohLinkLifecycle(recycleEndpoint = record, isClosed = { false })
        live.bind(1)
        val outcome = live.run(recycle = true, admit = { false }) { reported = true }
        assertEquals(IrohActionOutcome.REFUSED, outcome)
        assertEquals(emptyList<Long>(), recycled)
        assertEquals(false, reported)
    }
}
