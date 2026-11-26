package com.phonxiety.bridge.net

import com.phonxiety.bridge.model.LinkKind
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link lifecycle as the engine sees it: reconnect backoff, owner identity,
 * the host-activity deadline, the hello gate and the audio backlog cap.
 */
class LinkSupervisorTest {
    private companion object {
        /** What the engine queues first on every link. */
        const val HELLO = """{"type":"hello","version":3}"""
    }

    private val clock = ManualClock()
    private val scheduler = ManualScheduler(clock)
    private val factory = RecordingFactory()
    private val calls = RecordingCallbacks()

    private fun supervisor() = LinkSupervisor(factory, calls, scheduler) { clock.now }
        .also { calls.supervisor = it }

    private fun supervisor(factory: DeviceLinkFactory) = LinkSupervisor(factory, calls, scheduler) { clock.now }
        .also { calls.supervisor = it }

    @Test
    fun reconnectBackoffDoublesToEightSecondsAndResetsOnOpen() {
        val supervisor = supervisor()
        supervisor.start()
        assertEquals(1, factory.created.size)

        val delays = mutableListOf<Long>()
        repeat(5) {
            factory.last().down("closed")
            delays += scheduler.scheduledDelays.last()
            scheduler.advance(scheduler.scheduledDelays.last())
        }

        // 1 s, ×2, capped at 8 s.
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), delays)
        assertEquals(6, factory.created.size)

        // A successful open resets the delay to the base.
        factory.last().open()
        assertTrue(supervisor.isUp())
        assertEquals(1, calls.opened.size)

        factory.last().down("closed")
        assertEquals(1_000L, scheduler.scheduledDelays.last())
    }

    @Test
    fun aNetworkComingBackRetriesAtOnceFromTheBaseDelay() {
        val supervisor = supervisor()
        supervisor.start()
        repeat(3) {
            factory.last().down("connect_failed")
            scheduler.advance(scheduler.scheduledDelays.last())
        }
        factory.last().down("connect_failed") // now waiting 8 s
        assertEquals(8_000L, scheduler.scheduledDelays.last())
        val attempts = factory.created.size

        supervisor.networkAvailable()
        assertEquals(attempts + 1, factory.created.size)
        scheduler.advance(8_000) // the cancelled wait must not start a second attempt
        assertEquals(attempts + 1, factory.created.size)

        factory.last().down("connect_failed")
        assertEquals(1_000L, scheduler.scheduledDelays.last())
    }

    @Test
    fun callbacksFromAReplacedLinkAreIgnored() {
        val supervisor = supervisor()
        supervisor.start()
        val first = factory.last()
        first.open()
        assertEquals(1, calls.opened.size)

        first.down("closed")
        scheduler.advance(1_000)
        val second = factory.last()
        assertTrue(second !== first)
        second.open()
        assertEquals(2, calls.opened.size)

        // A replaced link must not move host activity, deliver traffic or
        // report the current link down.
        first.text("""{"type":"command"}""")
        first.binary(ByteArray(16).toByteString())
        first.down("closed")

        assertEquals(emptyList<String>(), calls.texts)
        assertEquals(0, calls.binaries.size)
        assertEquals(listOf("closed"), calls.downs)
        assertTrue(supervisor.isUp())
        assertEquals(1, scheduler.reconnectDelays.size)

        // Nor may it open: the live link keeps the slot and the stale one is closed.
        first.open()
        assertEquals(2, calls.opened.size)
        assertEquals(1, first.cancelling)
        assertTrue(second.cancelling == 0)
    }

    @Test
    fun hostTimeoutFiresOnlyAfterTenSecondsWithoutHostActivity() {
        val supervisor = supervisor()
        supervisor.start()
        val link = factory.last()
        link.open()

        clock.now = 5_000
        link.text("""{"type":"command"}""")
        assertEquals(1, calls.texts.size)

        // 10 s after that message is still within the deadline.
        clock.now = 15_000
        supervisor.tick()
        assertEquals(emptyList<String>(), calls.downs)
        assertTrue(supervisor.isUp())

        clock.now = 15_001
        supervisor.tick()
        assertEquals(listOf("host_timeout"), calls.downs)
        assertFalse(supervisor.isUp())
        assertEquals(1, link.cancelling)
    }

    @Test
    fun sendFrameRefusesAboveTheBacklogCapAndWhileDown() {
        val supervisor = supervisor()
        assertEquals(false, supervisor.sendFrame(ByteArray(4), 4))

        supervisor.start()
        val link = factory.last()
        assertFalse(supervisor.sendFrame(ByteArray(4), 4))
        assertFalse(supervisor.sendText("{}"))

        link.open()
        assertTrue(supervisor.sendText("{}"))
        assertEquals(listOf(HELLO, "{}"), link.sentText)

        // The cap itself still goes out; one byte more is dropped.
        link.backlog = LinkSupervisor.MAX_AUDIO_BACKLOG_BYTES
        assertTrue(supervisor.sendFrame(ByteArray(4), 4))
        link.backlog = LinkSupervisor.MAX_AUDIO_BACKLOG_BYTES + 1
        assertFalse(supervisor.sendFrame(ByteArray(4), 4))
        assertEquals(listOf(4), link.sentFrameLengths)

        link.down("closed")
        assertFalse(supervisor.sendFrame(ByteArray(4), 4))
        assertFalse(supervisor.sendText("{}"))
        assertEquals(listOf(4), link.sentFrameLengths)
    }

    @Test
    fun textBinaryAndAttachmentsAreRefusedUntilHelloIsQueued() = runBlocking {
        val supervisor = supervisor()
        val refused = mutableListOf<Boolean>()
        var upDuringHello = false
        calls.beforeHello = {
            // The link is live and its hello is not queued yet: this is the
            // window the gate exists for.  Preferences the daemon would read
            // as its first control frame must not reach the transport.
            upDuringHello = supervisor.isUp()
            refused += supervisor.sendText("""{"type":"api","id":"x"}""")
            refused += supervisor.sendFrame(ByteArray(4), 4)
            refused += runBlocking { supervisor.openAttachment("{}") } != null
        }
        supervisor.start()
        val link = factory.last()
        link.open()

        assertTrue(upDuringHello)
        assertEquals(listOf(false, false, false), refused)
        assertEquals(listOf(HELLO), link.sentText)
        assertEquals(0, link.sentFrameLengths.size)
        assertEquals(0, link.attachments)

        assertTrue(supervisor.sendText("""{"type":"api","id":"x"}"""))
        assertTrue(supervisor.sendFrame(ByteArray(4), 4))
        supervisor.openAttachment("{}")
        assertEquals(1, link.attachments)
    }

    @Test
    fun aProducerRacingASlowHelloCannotOvertakeIt() {
        val supervisor = supervisor()
        val producerSent = AtomicReference<Boolean>()
        calls.beforeHello = {
            // The hello's cold settings read is still running; a producer on
            // another thread tries the same link in that window.
            val producer = Thread { producerSent.set(supervisor.sendText("""{"type":"api"}""")) }
            producer.start()
            producer.join(5_000)
        }
        supervisor.start()
        val link = factory.last()
        link.open()

        assertEquals(false, producerSent.get())
        assertEquals(listOf(HELLO), link.sentText)
    }

    @Test
    fun aHelloTheTransportRefusesLeavesEverySendRefused() {
        factory.refuseFirstText = true
        val supervisor = supervisor()
        supervisor.start()
        val failed = factory.last()
        failed.open()

        assertEquals(listOf("hello_failed"), calls.downs)
        assertFalse(supervisor.isUp())
        assertEquals(1, failed.cancelling)
        // Nothing slips into the gap a refused hello left behind.
        assertFalse(supervisor.sendText("{}"))
        assertEquals(0, failed.sentText.size)
    }

    @Test
    fun aReplacementLinkStartsWithItsGateClosed() {
        val supervisor = supervisor()
        supervisor.start()
        factory.last().open()
        assertTrue(supervisor.sendText("{}"))

        factory.last().down("closed")
        assertFalse(supervisor.sendText("{}"))

        scheduler.advance(LinkSupervisor.RECONNECT_BASE_MS)
        val replacement = factory.last()
        assertFalse(supervisor.sendText("{}"))
        replacement.open()
        assertTrue(supervisor.sendText("{}"))
        assertEquals(listOf(HELLO, "{}"), replacement.sentText)
        assertEquals(0, replacement.cancelling)
    }

    @Test
    fun unqueuedHelloReportsTheLinkDown() {
        calls.helloQueued = false
        val supervisor = supervisor()
        supervisor.start()
        val failed = factory.last()
        failed.open()

        assertEquals(listOf("hello_failed"), calls.downs)
        assertFalse(supervisor.isUp())
        assertEquals(1, failed.cancelling)
        assertEquals(listOf(1_000L), scheduler.reconnectDelays)
    }

    @Test
    fun anAttemptThatNeverOpensIsDroppedAfterTheDeadlineAndReconnects() {
        val supervisor = supervisor()
        supervisor.start()
        val attempt = factory.last()
        assertEquals(1, factory.created.size)
        assertEquals(1, scheduler.pendingCount)

        scheduler.advance(LinkSupervisor.ATTEMPT_TIMEOUT_MS - 1)
        assertEquals(emptyList<String>(), calls.downs)
        assertEquals(0, attempt.cancelling)

        scheduler.advance(1)
        assertEquals(listOf("connect_timeout"), calls.downs)
        assertEquals(1, attempt.cancelling)
        // Only the backoff is left: the deadline is gone with the attempt.
        assertEquals(listOf(LinkSupervisor.ATTEMPT_TIMEOUT_MS, 1_000L), scheduler.scheduledDelays)
        assertEquals(1, scheduler.pendingCount)

        scheduler.advance(1_000)
        assertEquals(2, factory.created.size)

        // A link that opens keeps no attempt deadline at all.
        factory.last().open()
        assertTrue(supervisor.isUp())
        assertEquals(0, scheduler.pendingCount)
        scheduler.advance(LinkSupervisor.ATTEMPT_TIMEOUT_MS * 3)
        assertEquals(listOf("connect_timeout"), calls.downs)
        assertTrue(supervisor.isUp())
    }

    @Test
    fun aThrowingFactoryIsOneFailedAttemptAndTheSupervisorKeepsGoing() {
        val throwing = ThrowingFactory()
        val supervisor = LinkSupervisor(throwing, calls, scheduler) { clock.now }
            .also { calls.supervisor = it }
        supervisor.start()

        assertEquals(0, throwing.created.size)
        assertEquals(emptyList<String>(), calls.downs)
        assertEquals(listOf(1_000L), scheduler.scheduledDelays)

        // The failure doubles the backoff like any other attempt.
        scheduler.advance(1_000)
        assertEquals(listOf(1_000L, 2_000L), scheduler.scheduledDelays)
        assertEquals(0, throwing.created.size)

        throwing.fail = false
        scheduler.advance(2_000)
        assertEquals(1, throwing.created.size)
        throwing.last().open()
        assertTrue(supervisor.isUp())

        // A throw after a working link is one more failed attempt.
        throwing.fail = true
        throwing.last().down("closed")
        assertEquals(1_000L, scheduler.scheduledDelays.last())
        scheduler.advance(1_000)
        assertEquals(1, throwing.created.size)
        assertEquals(listOf(1_000L, 2_000L, 1_000L, 2_000L), scheduler.reconnectDelays)
        throwing.fail = false
        scheduler.advance(2_000)
        assertEquals(2, throwing.created.size)
        throwing.last().open()
        assertTrue(supervisor.isUp())
    }

    @Test
    fun anOpenRacingTheAttemptDeadlineIsNeverTornDownByIt() {
        val supervisor = supervisor()
        supervisor.start()
        val attempt = factory.last()

        // The deadline task is already dispatched when the transport opens: the
        // open runs first, on the same thread, exactly as two racing threads see it.
        scheduler.beforeAction = { attempt.open() }
        scheduler.advance(LinkSupervisor.ATTEMPT_TIMEOUT_MS)

        assertTrue(supervisor.isUp())
        assertEquals(listOf(Unit), calls.opened)
        assertEquals(emptyList<String>(), calls.downs)
        assertEquals(0, attempt.cancelling)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun aLateOpenAfterTheDeadlineCannotResurrectTheLink() {
        val supervisor = supervisor()
        supervisor.start()
        val attempt = factory.last()

        scheduler.advance(LinkSupervisor.ATTEMPT_TIMEOUT_MS)
        assertEquals(listOf("connect_timeout"), calls.downs)
        assertEquals(1, attempt.cancelling)
        assertFalse(supervisor.isUp())

        // The retired attempt opens late: refused and closed, never delivered.
        attempt.open()
        assertEquals(2, attempt.cancelling)
        assertEquals(0, calls.opened.size)
        assertFalse(supervisor.isUp())
        assertEquals(listOf("connect_timeout"), calls.downs)

        // The successor dials normally; the late open did not take its slot.
        scheduler.advance(1_000)
        assertEquals(2, factory.created.size)
        factory.last().open()
        assertTrue(supervisor.isUp())
        assertEquals(0, factory.last().cancelling)
    }

    @Test
    fun aThrowingHelloReportsTheLinkDownAndReconnects() {
        calls.helloThrows = true
        val supervisor = supervisor()
        supervisor.start()
        val failed = factory.last()
        failed.open()

        assertEquals(listOf("hello_failed"), calls.downs)
        assertFalse(supervisor.isUp())
        // The failed transport is cancelled, not left running beside the re-dial.
        assertEquals(1, failed.cancelling)
        // The attempt deadline is gone: only the reconnect is pending.
        assertEquals(listOf(1_000L), scheduler.reconnectDelays)
        assertEquals(1, scheduler.pendingCount)

        calls.helloThrows = false
        scheduler.advance(1_000)
        assertEquals(2, factory.created.size)
        factory.last().open()
        assertTrue(supervisor.isUp())
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun cancelClosesTheLinkAndStopsReconnecting() {
        val supervisor = supervisor()
        supervisor.start()
        val link = factory.last()
        link.open()

        supervisor.cancel()
        assertEquals(1, link.cancelling)
        assertFalse(supervisor.isUp())

        // A late down after cancel reports nothing and schedules nothing.
        link.down("closed")
        assertEquals(emptyList<String>(), calls.downs)
        assertEquals(0, scheduler.reconnectDelays.size)
        assertEquals(1, factory.created.size)

        // Start after cancel does not resurrect the link.
        supervisor.start()
        assertEquals(1, factory.created.size)

        supervisor.shutdown()
        assertEquals(1, factory.shutdowns)
    }

    @Test
    fun cancelDropsTheAttemptDeadline() {
        val supervisor = supervisor()
        supervisor.start()
        assertEquals(1, scheduler.pendingCount)

        supervisor.cancel()
        assertEquals(0, scheduler.pendingCount)
        scheduler.advance(LinkSupervisor.ATTEMPT_TIMEOUT_MS * 2)
        assertEquals(emptyList<String>(), calls.downs)
        assertEquals(1, factory.created.size)
    }

    // ------------------------------------------------------------------
    // Attempt outcomes: one classification per accepted attempt
    // ------------------------------------------------------------------

    @Test
    fun aFailureAfterAnAcceptedOpenIsNotCounted() {
        val budget = IrohFailureBudget { clock.now }
        val factory = BudgetFactory(budget, LinkKind.IROH)
        val supervisor = supervisor(factory)

        // `hello_failed` arrives after the transport opened...
        calls.helloQueued = false
        supervisor.start()
        factory.last().open()
        assertEquals(listOf("hello_failed"), calls.downs)
        scheduler.advance(scheduler.reconnectDelays.last())

        // ...and `connect_failed` can arrive after QUIC connected too.
        calls.helloQueued = true
        repeat(2) {
            val link = factory.last()
            link.open()
            link.down("connect_failed")
            scheduler.advance(scheduler.reconnectDelays.last())
        }

        assertEquals(listOf(AttemptOutcome.OPENED, AttemptOutcome.OPENED, AttemptOutcome.OPENED), factory.outcomes.map { it.first })
        assertFalse(budget.pending())
    }

    @Test
    fun duplicateAndLateDownsAreClassifiedOnce() {
        val budget = IrohFailureBudget { clock.now }
        val factory = BudgetFactory(budget, LinkKind.IROH)
        val supervisor = supervisor(factory)
        supervisor.start()

        // Two attempts, each reporting down twice: the second report comes from
        // an attempt that is no longer current and must change nothing.
        repeat(2) {
            val link = factory.last()
            link.down("connect_failed")
            link.down("closed")
            scheduler.advance(scheduler.reconnectDelays.last())
        }
        assertEquals(2, factory.outcomes.count { it.first == AttemptOutcome.FAILED_BEFORE_OPEN })
        assertFalse(budget.pending())

        // The third real failure is the one that trips: duplicates never counted.
        factory.last().down("connect_failed")
        assertTrue(budget.pending())
        assertEquals(3, factory.outcomes.count { it.first == AttemptOutcome.FAILED_BEFORE_OPEN })
    }

    @Test
    fun anOpenRacingTheAttemptDeadlineIsClassifiedAsOpenedOnly() {
        val budget = IrohFailureBudget { clock.now }
        val factory = BudgetFactory(budget, LinkKind.IROH)
        val supervisor = supervisor(factory)
        supervisor.start()

        // The deadline task is already dispatched when the transport opens.
        scheduler.beforeAction = { factory.last().open() }
        scheduler.advance(LinkSupervisor.ATTEMPT_TIMEOUT_MS)

        assertEquals(listOf(AttemptOutcome.OPENED), factory.outcomes.map { it.first })
        assertFalse(budget.pending())
        assertTrue(supervisor.isUp())
    }

    @Test
    fun mixedTransportsOnlyCountingIrohFailures() {
        val budget = IrohFailureBudget { clock.now }
        val factory = BudgetFactory(budget, LinkKind.IROH)
        val supervisor = supervisor(factory)
        supervisor.start()

        // iroh, LAN, iroh, Tailscale, iroh: three iroh failures trip, the LAN
        // failures neither add to the streak nor reset it.  The kind is fixed
        // when the attempt is created, so it is set before the reconnect.
        factory.last().down("connect_failed")
        factory.kind = LinkKind.WEBSOCKET
        scheduler.advance(scheduler.reconnectDelays.last())
        factory.last().down("connect_failed")
        factory.kind = LinkKind.IROH
        scheduler.advance(scheduler.reconnectDelays.last())
        factory.last().down("connect_failed")
        factory.kind = LinkKind.TAILSCALE
        scheduler.advance(scheduler.reconnectDelays.last())
        factory.last().down("connect_failed")
        assertFalse(budget.pending())

        factory.kind = LinkKind.IROH
        scheduler.advance(scheduler.reconnectDelays.last())
        factory.last().down("connect_failed")
        assertTrue(budget.pending())
    }

    @Test
    fun anUnknownKindConstructionFailureNeverCountsAndStillReconnects() {
        val budget = IrohFailureBudget { clock.now }
        val factory = BudgetFactory(budget, LinkKind.IROH).also { it.throwCreate = true }
        val supervisor = supervisor(factory)
        supervisor.start()

        scheduler.advance(1_000)
        scheduler.advance(2_000)

        // Nothing was selected, so every failure is unknown to the budget...
        assertEquals(0, factory.created.size)
        assertEquals(
            listOf(AttemptOutcome.FAILED_BEFORE_OPEN, AttemptOutcome.FAILED_BEFORE_OPEN, AttemptOutcome.FAILED_BEFORE_OPEN),
            factory.outcomes.map { it.first }
        )
        assertTrue(factory.outcomes.all { it.second == null })
        assertFalse(budget.pending())

        // ...while reconnect and backoff run exactly as before.
        assertEquals(listOf(1_000L, 2_000L, 4_000L), scheduler.reconnectDelays)
    }

    @Test
    fun threeIrohFailuresTripOneRequestThatTheNextBindConsumesOnce() = runBlocking {
        val budget = IrohFailureBudget { clock.now }
        val factory = BudgetFactory(budget, LinkKind.IROH)
        val supervisor = supervisor(factory)
        supervisor.start()

        repeat(3) { round ->
            scheduler.advance(LinkSupervisor.ATTEMPT_TIMEOUT_MS)
            if (round < 2) scheduler.advance(scheduler.reconnectDelays.last())
        }
        assertTrue(budget.pending())

        // The endpoint a replacement already bound.
        val detached = mutableListOf<Long>()
        val cache = IrohEndpointCache<Long> { handle, _ -> detached += handle }
        assertEquals(1L, cache.acquire { it })

        // The next iroh attempt that binds consumes the one request.
        val attempt = IrohLinkLifecycle(recycleEndpoint = { false }, isClosed = { false })
        assertEquals(2L, cache.acquire(BudgetFreshBind(budget, attempt)) { it })
        assertEquals(listOf(1L), detached)
        assertFalse(budget.pending())

        // A second bind on a live attempt reuses the new endpoint.
        assertEquals(2L, cache.acquire(BudgetFreshBind(budget, attempt)) { it })
        assertEquals(listOf(1L), detached)
    }

    /**
     * The ordering race behind the recovery budget: an outcome reported after the supervisor
     * lock is released can land after newer attempts' outcomes, so a late `OPENED` would reset
     * the streak they built and three failures in a row would never trip. Every outcome is
     * therefore applied inside the transition that classifies it (open, down, attempt timeout).
     * The test asserts exactly that, so no thread timing can decide its verdict.
     */
    @Test
    fun everyOutcomeIsAppliedInsideItsTransition() {
        val budget = IrohFailureBudget { clock.now }
        lateinit var supervisor: LinkSupervisor
        val outside = mutableListOf<AttemptOutcome>()
        val factory = BudgetFactory(budget, LinkKind.IROH).also { f ->
            f.beforeBudget = { outcome -> if (!supervisor.inTransition()) outside += outcome }
        }
        supervisor = supervisor(factory)
        supervisor.start()

        factory.last().open()
        // The engine moves the live link to another candidate; the replacements fail before opening.
        assertTrue(supervisor.reconnect("connection_mode"))
        scheduler.advance(scheduler.reconnectDelays.last())
        factory.last().down("connect_failed")
        scheduler.advance(scheduler.reconnectDelays.last())
        factory.last().down("connect_failed")
        scheduler.advance(scheduler.reconnectDelays.last())
        scheduler.advance(LinkSupervisor.ATTEMPT_TIMEOUT_MS)

        assertEquals("outcomes applied outside their transition", emptyList<AttemptOutcome>(), outside)
        assertEquals(
            listOf(
                AttemptOutcome.OPENED,
                AttemptOutcome.FAILED_BEFORE_OPEN,
                AttemptOutcome.FAILED_BEFORE_OPEN,
                AttemptOutcome.FAILED_BEFORE_OPEN
            ),
            factory.outcomes.map { it.first }
        )
        assertTrue(budget.pending())
        assertTrue(budget.consume())
        assertFalse(budget.consume())
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class ManualClock {
        var now = 0L
    }

    /** A scheduler whose time only moves when the test says so. */
    private class ManualScheduler(private val clock: ManualClock) : LinkScheduler {
        private class Task(val due: Long, val order: Int, val action: () -> Unit)

        private val pending = mutableListOf<Task>()
        private var order = 0

        val scheduledDelays = mutableListOf<Long>()

        /**
         * Runs just before the next scheduled action, on the same thread: a test
         * uses it to deliver a callback at the instant a task was dispatched.
         */
        var beforeAction: (() -> Unit)? = null

        /** Reconnect delays, without the per-attempt deadline. */
        val reconnectDelays: List<Long>
            get() = scheduledDelays.filter { it != LinkSupervisor.ATTEMPT_TIMEOUT_MS }

        /** Tasks still armed, so a cancelled deadline is observable. */
        val pendingCount: Int get() = pending.size

        override fun after(delayMs: Long, task: () -> Unit): LinkTask {
            scheduledDelays += delayMs
            val scheduled = Task(clock.now + delayMs, order++) {
                val hook = beforeAction
                beforeAction = null
                hook?.invoke()
                task()
            }
            pending += scheduled
            return LinkTask { pending.remove(scheduled) }
        }

        /** Runs everything due within [ms], in order, moving the clock with it. */
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

    private class RecordingCallbacks : LinkSupervisor.Callbacks {
        val opened = mutableListOf<Unit>()
        val texts = mutableListOf<String>()
        val binaries = mutableListOf<ByteString>()
        val downs = mutableListOf<String>()

        /** The engine's supervisor; the test wires it before start(). */
        lateinit var supervisor: LinkSupervisor

        /** False: the engine could not queue its hello (like an unwired send). */
        var helloQueued = true
        var helloThrows = false

        /** Runs inside onLinkOpened before the hello, e.g. a producer racing a slow settings read. */
        var beforeHello: (() -> Unit)? = null

        override fun onLinkOpened(): Boolean {
            opened += Unit
            if (helloThrows) throw IllegalStateException("hello failed")
            beforeHello?.invoke()
            if (!helloQueued) return false
            return supervisor.sendHello(HELLO)
        }

        override fun onLinkText(text: String) {
            texts += text
        }

        override fun onLinkBinary(bytes: ByteString) {
            binaries += bytes
        }

        override fun onLinkDown(reason: String) {
            downs += reason
        }
    }

    private class RecordingFactory : DeviceLinkFactory {
        val created = mutableListOf<FakeLink>()
        var shutdowns = 0

        /** The next created link refuses its first text frame, like a closed transport. */
        var refuseFirstText = false

        fun last(): FakeLink = created.last()

        override fun create(): DeviceLink {
            val link = FakeLink()
            link.refuseNextText = refuseFirstText
            refuseFirstText = false
            created += link
            return link
        }

        override fun shutdown() {
            shutdowns++
        }
    }

    /** A factory whose create() can fail the way a candidate plan or probe does. */
    private class ThrowingFactory : DeviceLinkFactory {
        var fail = true
        val created = mutableListOf<FakeLink>()

        fun last(): FakeLink = created.last()

        override fun create(): DeviceLink {
            if (fail) throw IllegalStateException("no candidate")
            return FakeLink().also { created += it }
        }

        override fun shutdown() = Unit
    }

    /**
     * A factory whose outcomes take the production route: the real router and a
     * real budget, so the supervisor-to-budget path is exercised end to end.
     */
    private class BudgetFactory(
        private val budget: IrohFailureBudget,
        var kind: LinkKind? = null
    ) : DeviceLinkFactory {
        val created = mutableListOf<FakeLink>()
        val outcomes = mutableListOf<Pair<AttemptOutcome, LinkKind?>>()
        var throwCreate = false

        /** Runs inside the report, before the budget sees the outcome. */
        var beforeBudget: ((AttemptOutcome) -> Unit)? = null

        private val router = IrohAttemptOutcomes {
            object : DeviceLinkFactory {
                override fun create(): DeviceLink = error("the router only reports outcomes")

                override fun shutdown() = Unit

                override fun attemptEnded(outcome: AttemptOutcome, kind: LinkKind?) {
                    beforeBudget?.invoke(outcome)
                    budget.note(outcome)
                }
            }
        }

        fun last(): FakeLink = created.last()

        override fun create(): DeviceLink {
            if (throwCreate) throw IllegalStateException("no candidate")
            return FakeLink().also {
                it.kind = kind
                created += it
            }
        }

        override fun shutdown() = Unit

        override fun attemptEnded(outcome: AttemptOutcome, kind: LinkKind?) {
            outcomes += outcome to kind
            router.report(outcome, kind)
        }
    }

    /** A link the test opens, feeds and drops by hand. */
    private class FakeLink : DeviceLink {
        private var listener: DeviceLink.Listener? = null

        override var kind: LinkKind? = null

        var backlog = 0L
        var cancelling = 0
        var refuseNextText = false
        var attachments = 0
        val sentText = mutableListOf<String>()
        val sentFrameLengths = mutableListOf<Int>()

        override val backlogBytes: Long get() = backlog

        override val supportsAttachments: Boolean get() = true

        override fun start(listener: DeviceLink.Listener) {
            this.listener = listener
        }

        override fun sendText(message: String): Boolean {
            if (refuseNextText) {
                refuseNextText = false
                return false
            }
            sentText += message
            return true
        }

        override fun sendBinary(frame: ByteArray, length: Int) {
            sentFrameLengths += length
        }

        override suspend fun openAttachment(header: String): AttachmentChannel? {
            attachments++
            return null
        }

        override fun cancel() {
            cancelling++
        }

        override fun route(): DeviceLink.Route? = null

        fun open() = listener!!.onLinkOpened()

        fun text(text: String) = listener!!.onLinkText(text)

        fun binary(bytes: ByteString) = listener!!.onLinkBinary(bytes)

        fun down(reason: String) = listener!!.onLinkDown(reason)
    }
}
