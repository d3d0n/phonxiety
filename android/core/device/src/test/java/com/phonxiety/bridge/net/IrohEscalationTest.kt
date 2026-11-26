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
    private var refuse = false
    private val escalations = mutableListOf<IrohEscalation>()
    private val source = FakeSource()

    private fun monitor() = IrohPathMonitor(policy, scheduler, { owned }) { escalation ->
        escalations += escalation
        !refuse
    }

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
    fun aNetworkChangeInsideTheCooldownIsNotAdmitted() {
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 20_000
        assertNotNull(policy.evaluate(callOwned = false))

        // A network notification right after the escalation must not recycle
        // again: only an admitted action may act.
        clock.now = 25_000
        policy.noteNetworkChange()
        assertEquals(IrohNetworkDecision.HELD, policy.admitNetworkRecycle(callOwned = false))

        clock.now = 79_999
        assertEquals(IrohNetworkDecision.HELD, policy.admitNetworkRecycle(callOwned = false))
        clock.now = 80_000
        assertEquals(IrohNetworkDecision.RECYCLE, policy.admitNetworkRecycle(callOwned = false))
    }

    @Test
    fun aNetworkChangeDuringACallTakesNoActionAndNoCooldown() {
        policy.observePath(IrohPathKind.RELAY, callOwned = true)
        clock.now = 25_000
        policy.noteNetworkChange()
        assertEquals(IrohNetworkDecision.DURING_CALL, policy.admitNetworkRecycle(callOwned = true))

        // The move during the call must not poison the next escalation.
        clock.now = 30_000
        assertEquals(IrohNetworkDecision.RECYCLE, policy.admitNetworkRecycle(callOwned = false))
    }

    @Test
    fun aNetworkRecycleHoldsTheCooldownForTheNextEscalation() {
        clock.now = 10_000
        assertEquals(IrohNetworkDecision.RECYCLE, policy.admitNetworkRecycle(callOwned = false))

        // Relay again at 11 s; due at 31 s, but the network recycle is a
        // recent action, so nothing happens before the cooldown at 70 s.
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 40_000
        assertNull(policy.evaluate(callOwned = false))
        assertEquals(30_000L, policy.nextCheckMs(callOwned = false))

        clock.now = 70_000
        val escalation = policy.evaluate(callOwned = false)
        assertNotNull(escalation)
        // The network recycle counts as the first action of the window.
        assertTrue(escalation!!.recycleEndpoint)
    }

    @Test
    fun aReconnectAfterNineteenSecondsOfRelayGetsTheFullTwentySecondsAgain() {
        policy.beginConnection()
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 19_000
        // The link drops: the monitor retires before the replacement starts.
        policy.endConnection(1L)

        // The reconnect starts at 21 s and counts only its own relay
        // residence, never the disconnect time.
        clock.now = 21_000
        policy.beginConnection()
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 40_999
        assertNull(policy.evaluate(callOwned = false))
        clock.now = 41_000
        assertNotNull(policy.evaluate(callOwned = false))
    }

    @Test
    fun aRetiredConnectionDropsRelayResidenceAndKeepsTheCooldown() {
        policy.beginConnection()
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        clock.now = 20_000
        assertNotNull(policy.evaluate(callOwned = false)) // cooldown until 80 s
        policy.endConnection(1L)

        // The replacement connection starts relay-only at 30 s; its own
        // residence is due at 50 s, but the previous action still holds.
        clock.now = 30_000
        policy.beginConnection()
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        assertEquals(20_000L, policy.nextCheckMs(callOwned = false))
        clock.now = 50_000
        assertNull(policy.evaluate(callOwned = false))
        assertEquals(30_000L, policy.nextCheckMs(callOwned = false))
        clock.now = 80_000
        assertNotNull(policy.evaluate(callOwned = false))
    }

    @Test
    fun aRetiredMonitorForgetsAPendingEscalation() {
        policy.beginConnection()
        policy.observePath(IrohPathKind.RELAY, callOwned = true)
        clock.now = 25_000
        assertNull(policy.evaluate(callOwned = true)) // pending during the call

        policy.endConnection(1L)
        clock.now = 30_000
        policy.beginConnection()
        policy.observePath(IrohPathKind.RELAY, callOwned = false)
        // The stale pending escalation is gone: only fresh residence counts.
        assertNull(policy.evaluate(callOwned = false))
        assertEquals(20_000L, policy.nextCheckMs(callOwned = false))
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
    fun theMonitorRefusesARecycleInsideTheCooldown() {
        val monitor = monitor()
        monitor.start(source)
        source.path(IrohPathKind.RELAY)
        scheduler.advance(20_000)
        assertEquals(1, escalations.size)

        // A replacement link sees the relay and the network moves right after.
        clock.now = 21_000
        val second = monitor()
        second.start(source)
        source.path(IrohPathKind.RELAY)
        clock.now = 22_000
        second.noteNetworkChange()
        assertEquals(IrohNetworkDecision.HELD, second.admitNetworkRecycle())

        // The cooldown from the first escalation holds until 80 s.
        clock.now = 79_999
        assertEquals(IrohNetworkDecision.HELD, second.admitNetworkRecycle())
        clock.now = 80_000
        assertEquals(IrohNetworkDecision.RECYCLE, second.admitNetworkRecycle())
    }

    @Test
    fun aRefusedEscalationStaysArmedAndIsDeliveredAfterTheCooldown() {
        refuse = true
        val monitor = monitor()
        monitor.start(source)
        source.path(IrohPathKind.RELAY)
        scheduler.advance(20_000)
        assertEquals(1, escalations.size)
        assertEquals(1, source.stops)

        // The link refused (a call took the link in the race): the sample loop
        // keeps the watch alive, so the escalation comes back after the cooldown.
        refuse = false
        clock.now = 21_000
        monitor.observe(IrohPathKind.RELAY)
        scheduler.advance(59_000)
        assertEquals(2, escalations.size)
    }

    @Test
    fun aStaleMonitorRetirementDoesNotWipeTheReplacementGeneration() {
        val first = monitor()
        first.start(source)
        clock.now = 5_000
        val second = monitor()
        second.start(source)
        source.path(IrohPathKind.RELAY)

        // The old generation retires after the new one started: its reset must
        // not touch the new connection's residence.
        clock.now = 10_000
        first.cancel()
        clock.now = 24_999
        assertTrue(escalations.isEmpty())
        scheduler.advance(1)
        assertEquals(1, escalations.size)
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
