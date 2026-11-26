package com.phonxiety.bridge.net

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link lifecycle as the engine sees it: reconnect backoff, owner identity,
 * the host-activity deadline and the audio backlog cap.
 */
class LinkSupervisorTest {
    private val clock = ManualClock()
    private val scheduler = ManualScheduler(clock)
    private val factory = RecordingFactory()
    private val calls = RecordingCallbacks()

    private fun supervisor() = LinkSupervisor(factory, calls, scheduler) { clock.now }

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
        assertEquals(listOf("{}"), link.sentText)

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
        var helloQueued = true
        var helloThrows = false

        override fun onLinkOpened(): Boolean {
            opened += Unit
            if (helloThrows) throw IllegalStateException("hello failed")
            return helloQueued
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

        fun last(): FakeLink = created.last()

        override fun create(): DeviceLink = FakeLink().also { created += it }

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

    /** A link the test opens, feeds and drops by hand. */
    private class FakeLink : DeviceLink {
        private var listener: DeviceLink.Listener? = null

        var backlog = 0L
        var cancelling = 0
        val sentText = mutableListOf<String>()
        val sentFrameLengths = mutableListOf<Int>()

        override val backlogBytes: Long get() = backlog

        override fun start(listener: DeviceLink.Listener) {
            this.listener = listener
        }

        override fun sendText(message: String): Boolean {
            sentText += message
            return true
        }

        override fun sendBinary(frame: ByteArray, length: Int) {
            sentFrameLengths += length
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
