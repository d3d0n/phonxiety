package com.phonxiety.bridge.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay-only rule and its watchdog: 20 s of relay residence escalates,
 * the second escalation inside five minutes recycles the endpoint, the
 * cooldown holds across reconnects, and a call defers the decision without
 * losing it.
 */
class IrohEscalationTest {
    private val clock = ManualClock()
    private val scheduler = ManualScheduler(clock)
    private val policy = IrohEscalationPolicy({ clock.now })
    private var owned = false
    private val escalations = mutableListOf<IrohEscalation>()
    private val source = FakeSource()

    private fun monitor() = IrohPathMonitor(policy, scheduler, { owned }) { escalations += it }

    // ------------------------------------------------------------------
    // Policy
    // ------------------------------------------------------------------

    @Test
    fun nineteenSecondsOnTheRelayIsNotEnough() {
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 19_999
        assertNull(policy.evaluate(callOwned = false))
        assertEquals(1L, policy.nextCheckMs(callOwned = false))
    }

    @Test
    fun twentySecondsOnTheRelayEscalatesWithoutRecyclingTheEndpoint() {
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 20_000
        val escalation = policy.evaluate(callOwned = false)
        assertNotNull(escalation)
        assertFalse(escalation!!.recycleEndpoint)
    }

    @Test
    fun anUnknownPathIsNeverRelayResidence() {
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 10_000
        policy.observePath(IrohPathKind.UNKNOWN, callOwned = false)
        clock.now = 60_000
        assertNull(policy.evaluate(callOwned = false))
        assertNull(policy.nextCheckMs(callOwned = false))
    }

    @Test
    fun aDirectPathClearsRelayTimeAndPendingWork() {
        policy.observePath(IrohPathKind.RELAY, callOwned = true)
        clock.now = 25_000
        assertNull(policy.evaluate(callOwned = true))

        policy.observePath(IrohPathKind.DIRECT, callOwned = true)
        clock.now = 60_000
        assertNull(policy.evaluate(callOwned = false))

        // Relay residence starts over from the direct selection.
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 79_999
        assertNull(policy.evaluate(callOwned = false))
        clock.now = 80_000
        assertNotNull(policy.evaluate(callOwned = false))
    }

    @Test
    fun anEscalationDuringACallWaitsForTheCallToEndAndAFreshCheck() {
        policy.observePath(IrohPathKind.RELAY, callOwned = true)
        clock.now = 25_000
        assertNull(policy.evaluate(callOwned = true))

        // The call is over, but no path was observed since: the pending
        // escalation cannot be released on evidence from before the call.
        assertNull(policy.evaluate(callOwned = false))

        // The next check still sees the relay, and the cooldown has not been
        // used yet, so the pending escalation is released.
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        val escalation = policy.evaluate(callOwned = false)
        assertNotNull(escalation)
        assertFalse(escalation!!.recycleEndpoint)
    }

    @Test
    fun aPendingEscalationWaitsOutTheCooldown() {
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 20_000
        assertNotNull(policy.evaluate(callOwned = false))

        // Reconnected link, relay again, and a call starts.
        clock.now = 25_000
        policy.observePath(IrohPathKind.RELAY, callOwned = true)
        clock.now = 50_000
        assertNull(policy.evaluate(callOwned = true))

        // The call ends inside the cooldown: still nothing.
        clock.now = 60_000
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        assertNull(policy.evaluate(callOwned = false))
        assertEquals(20_000L, policy.nextCheckMs(callOwned = false))

        clock.now = 80_000
        assertNotNull(policy.evaluate(callOwned = false))
    }

    @Test
    fun aSecondEscalationWithinFiveMinutesRecyclesTheEndpoint() {
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 20_000
        assertFalse(policy.evaluate(callOwned = false)!!.recycleEndpoint)

        clock.now = 70_000
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 90_000
        val second = policy.evaluate(callOwned = false)
        assertNotNull(second)
        assertTrue(second!!.recycleEndpoint)
    }

    @Test
    fun anEscalationLongAfterTheFirstOneOnlyReconnects() {
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 20_000
        assertNotNull(policy.evaluate(callOwned = false))

        clock.now = 330_000
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 350_000
        val next = policy.evaluate(callOwned = false)
        assertNotNull(next)
        assertFalse(next!!.recycleEndpoint)
    }

    @Test
    fun theCooldownDelaysTheNextEscalation() {
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 20_000
        assertNotNull(policy.evaluate(callOwned = false))

        clock.now = 30_000
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 50_000
        assertNull(policy.evaluate(callOwned = false))
        assertEquals(30_000L, policy.nextCheckMs(callOwned = false))

        clock.now = 79_999
        assertNull(policy.evaluate(callOwned = false))
        clock.now = 80_000
        assertNotNull(policy.evaluate(callOwned = false))
    }

    @Test
    fun aNetworkChangeSharesTheCooldown() {
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 20_000
        assertNotNull(policy.evaluate(callOwned = false))

        clock.now = 30_000
        policy.observeNetworkChange()
        policy.observePath(IrohPathKind.RELAY, callOwned = false)

        clock.now = 89_999
        assertNull(policy.evaluate(callOwned = false))
        clock.now = 90_000
        assertNotNull(policy.evaluate(callOwned = false))
    }

    // ------------------------------------------------------------------
    // Monitor wiring
    // ------------------------------------------------------------------

    @Test
    fun relayOnlyReachesTheLinkDownReportThroughTheArmedDeadline() {
        val monitor = monitor()
        monitor.start(source)
        source.path(IrohPathKind.RELAY)

        // No further path event arrives; only the armed deadline can fire.
        scheduler.advance(20_000)
        assertEquals(1, escalations.size)
        assertFalse(escalations[0].recycleEndpoint)
    }

    @Test
    fun theOnceASecondSampleAloneCanEscalate() {
        val monitor = monitor()
        monitor.start(source)
        repeat(20) { second ->
            clock.now = second * 1_000L
            monitor.observe(IrohPathKind.RELAY)
        }
        assertTrue(escalations.isEmpty())

        clock.now = 20_000
        monitor.observe(IrohPathKind.RELAY)
        assertEquals(1, escalations.size)
    }

    @Test
    fun cancellingStopsTheSubscriptionAndIgnoresLateCallbacks() {
        val monitor = monitor()
        monitor.start(source)
        source.path(IrohPathKind.RELAY)
        monitor.cancel()
        assertEquals(1, source.stops)

        // A stale event from the replaced link, and a deadline that somehow
        // still fires, must both be ignored.
        source.path(IrohPathKind.RELAY)
        clock.now = 60_000
        scheduler.advance(60_000)
        assertTrue(escalations.isEmpty())
    }

    @Test
    fun aReplacedLinkKeepsTheEscalationHistoryForTheNextOne() {
        val first = monitor()
        first.start(source)
        source.path(IrohPathKind.RELAY)
        scheduler.advance(20_000)
        assertEquals(1, escalations.size)
        first.cancel()

        // Reconnect: a new link, the same policy, relay-only again inside five minutes.
        clock.now = 25_000
        val second = monitor()
        second.start(source)
        source.path(IrohPathKind.RELAY)
        scheduler.advance(61_000)

        assertEquals(2, escalations.size)
        assertTrue(escalations[1].recycleEndpoint)
        assertEquals(2, source.stops)
    }

    @Test
    fun aCallDefersTheEscalationUntilTheEndAndTheCooldown() {
        owned = true
        val monitor = monitor()
        monitor.start(source)
        source.path(IrohPathKind.RELAY)
        scheduler.advance(25_000)
        assertTrue(escalations.isEmpty())

        owned = false
        clock.now = 30_000
        source.path(IrohPathKind.RELAY)
        assertEquals(1, escalations.size)
        assertFalse(escalations[0].recycleEndpoint)
    }

    @Test
    fun aNetworkChangeRestartsTheCooldownAtTheMonitor() {
        val monitor = monitor()
        monitor.start(source)
        source.path(IrohPathKind.RELAY)
        scheduler.advance(20_000)
        assertEquals(1, escalations.size)

        // A replacement link, then a network change right after it sees relay.
        clock.now = 21_000
        val second = monitor()
        second.start(source)
        source.path(IrohPathKind.RELAY)
        clock.now = 22_000
        second.networkChanged()
        source.path(IrohPathKind.RELAY)

        // The relay residence that began at 22 s is due at 42 s, but the
        // network change moved the cooldown to 82 s.
        scheduler.advance(57_999)
        assertEquals(1, escalations.size)
        scheduler.advance(2_001)
        assertEquals(2, escalations.size)
        assertTrue(escalations[1].recycleEndpoint)
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class ManualClock {
        var now = 0L
    }

    private class ManualScheduler(private val clock: ManualClock) : LinkScheduler {
        private class Task(val due: Long, val order: Int, val action: () -> Unit)

        private val pending = mutableListOf<Task>()
        private var order = 0

        override fun after(delayMs: Long, task: () -> Unit): LinkTask {
            val scheduled = Task(clock.now + delayMs, order++, task)
            pending += scheduled
            return LinkTask { pending.remove(scheduled) }
        }

        /** Runs everything due within [ms], moving the clock with it. */
        fun advance(ms: Long) {
            val target = clock.now + ms
            while (true) {
                val next = pending.filter { it.due <= target }
                    .minWithOrNull(compareBy({ it.due }, { it.order }))
                    ?: break
                pending.remove(next)
                clock.now = next.due
                next.action()
            }
            clock.now = target
        }
    }

    private class FakeSource : IrohPathSource {
        private var callback: ((IrohPathKind) -> Unit)? = null
        var stops = 0
            private set

        override fun subscribe(onPath: (IrohPathKind) -> Unit): IrohPathWatch {
            callback = onPath
            return IrohPathWatch { stops++ }
        }

        fun path(kind: IrohPathKind) {
            callback?.invoke(kind)
        }
    }
}
