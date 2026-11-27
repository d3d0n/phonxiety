package com.phonxiety.bridge.net

import com.phonxiety.bridge.CallAdmission
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun aFinishedLinkNeverAsksThePolicySoTheReplacementCanStillRecycle() {
        val calls = CallAdmission()
        val old = IrohLinkAdmission()
        // The old link ends while its network callback is still on its way.
        old.finish()
        val asked = AtomicInteger()
        val stale = calls.tryBeginIdleLinkAction { false }!!
        val attached = old.attachIf(stale) {
            asked.incrementAndGet()
            policy.admitRecycle(callOwned = false) == IrohNetworkDecision.RECYCLE
        }
        assertFalse(attached)
        stale.release()
        assertEquals(0, asked.get())

        // The replacement link's own network change is not held by a cooldown
        // the stale callback never spent.
        val replacement = IrohLinkAdmission()
        val fresh = calls.tryBeginIdleLinkAction { false }!!
        assertTrue(replacement.attachIf(fresh) { policy.admitRecycle(callOwned = false) == IrohNetworkDecision.RECYCLE })
        replacement.finish()
    }
}
