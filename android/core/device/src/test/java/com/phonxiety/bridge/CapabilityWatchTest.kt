package com.phonxiety.bridge

import com.phonxiety.bridge.model.DeviceOutbound
import com.phonxiety.bridge.model.DeviceProtocol
import com.phonxiety.bridge.net.DeviceLink
import com.phonxiety.bridge.net.DeviceLinkFactory
import com.phonxiety.bridge.net.LinkScheduler
import com.phonxiety.bridge.net.LinkSupervisor
import com.phonxiety.bridge.net.LinkTask
import kotlinx.serialization.json.Json
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call capabilities reach the daemon only through `hello`, so a permission
 * change while the link is up must re-handshake — but only for a real
 * difference, and never under a call or a dial reservation.
 *
 * Everything except the permission sample and the call reservation is the
 * production code path: [CapabilityRefresh], [CapabilityWatch], [CallAdmission],
 * [LinkSupervisor]'s hello gate and [helloFrame].
 */
class CapabilityWatchTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val scheduler = TestScheduler()
    private val factory = TestFactory()
    private val admission = CallAdmission()
    private val watch = CapabilityWatch()
    private val downs = mutableListOf<String>()

    /** What the 10 s permission sample would read. */
    private var sample = PhoneCapabilities(callControl = true, frameworkAudio = false)

    /** True while the engine owns or reserves a bridge call. */
    private var callOwned = false

    private val callbacks = object : LinkSupervisor.Callbacks {
        override fun onLinkOpened(): Boolean {
            val frame = helloFrame(
                capabilities = sample,
                device = "phone",
                bootId = "boot",
                busy = false,
                activeCall = null,
                preferredCodec = "pcm",
                maxBitrate = 32_000,
                adbTunnel = false
            )
            val sent = supervisor.sendHello(DeviceProtocol.encode(frame))
            if (sent) watch.helloSent(sample)
            return sent
        }

        override fun onLinkText(text: String) {}

        override fun onLinkBinary(bytes: ByteString) {}

        override fun onLinkDown(reason: String) {
            downs += reason
            watch.helloGone()
        }
    }

    private val supervisor: LinkSupervisor = LinkSupervisor(factory, callbacks, scheduler) { scheduler.now }

    private val refresh = CapabilityRefresh(
        watch = watch,
        admission = admission,
        callOwned = { callOwned },
        sample = { sample },
        reconnect = { supervisor.reconnect(it) }
    )

    private fun openLink() {
        supervisor.start()
        factory.last().open()
    }

    private fun helloOf(link: TestLink): DeviceOutbound.Hello = json.decodeFromString(
        DeviceOutbound.serializer(),
        link.sentText.first()
    ) as DeviceOutbound.Hello

    @Test
    fun anIdleChangeRefreshesTheLinkAndTheNewHelloCarriesIt() {
        openLink()
        assertEquals(false, helloOf(factory.last()).capabilities.frameworkAudio)

        sample = PhoneCapabilities(callControl = true, frameworkAudio = true)
        assertTrue(refresh.run())

        assertEquals(listOf(CapabilityRefresh.CAPABILITIES_CHANGED), downs)
        assertFalse(supervisor.isUp())

        // The usual reconnect re-handshakes, and that hello tells the daemon
        // what the phone can do now.
        scheduler.advance(LinkSupervisor.RECONNECT_BASE_MS)
        assertEquals(2, factory.created.size)
        val replacement = factory.last()
        replacement.open()

        val hello = helloOf(replacement)
        assertTrue(hello.capabilities.frameworkAudio)
        assertTrue(hello.capabilities.callControl)
        assertEquals(listOf(CapabilityRefresh.CAPABILITIES_CHANGED), downs)
        assertTrue(supervisor.isUp())
    }

    @Test
    fun unchangedSamplesNeverReconnect() {
        openLink()
        repeat(3) { assertFalse(refresh.run()) }

        assertEquals(1, factory.created.size)
        assertEquals(emptyList<String>(), downs)
        assertTrue(supervisor.isUp())
    }

    @Test
    fun aFreshHelloStopsFurtherReconnects() {
        openLink()
        sample = PhoneCapabilities(callControl = false, frameworkAudio = true)
        assertTrue(refresh.run())
        scheduler.advance(LinkSupervisor.RECONNECT_BASE_MS)
        factory.last().open()

        // The new hello announced the new sample: nothing is left to refresh.
        repeat(3) { assertFalse(refresh.run()) }
        assertEquals(1, downs.size)
        assertEquals(2, factory.created.size)
        assertTrue(supervisor.isUp())
    }

    @Test
    fun aChangeDuringACallWaitsForTheCallToEnd() {
        openLink()
        callOwned = true

        sample = PhoneCapabilities(callControl = false, frameworkAudio = true)
        assertFalse(refresh.run())
        assertTrue(supervisor.isUp())
        assertEquals(emptyList<String>(), downs)

        // Reverted before the call ended: the next sample must not refresh.
        sample = PhoneCapabilities(callControl = true, frameworkAudio = false)
        callOwned = false
        assertFalse(refresh.run())
        assertTrue(supervisor.isUp())
        assertEquals(emptyList<String>(), downs)

        // Still different at the next sample after the call: now it refreshes.
        sample = PhoneCapabilities(callControl = false, frameworkAudio = true)
        assertTrue(refresh.run())
        assertEquals(listOf(CapabilityRefresh.CAPABILITIES_CHANGED), downs)
    }

    @Test
    fun aRefreshInFlightKeepsADialOutAndAConcurrentRefreshDefers() {
        openLink()
        sample = PhoneCapabilities(callControl = false, frameworkAudio = true)
        val action = admission.tryBeginIdleLinkAction { callOwned }
        assertNotNull(action)
        try {
            // A dial that arrives while the refresh holds the door must not
            // reserve: the link is about to be dropped, never under a call.
            assertFalse(admission.awaitIdle(0))
            // Nor may a second sample drop the link on top of the first.
            assertFalse(refresh.run())
            assertTrue(supervisor.isUp())
            assertEquals(emptyList<String>(), downs)
        } finally {
            action!!.close()
        }

        // The change was not lost with the refused sample.
        assertTrue(refresh.run())
        assertEquals(listOf(CapabilityRefresh.CAPABILITIES_CHANGED), downs)
    }

    @Test
    fun aChangeWhileDownIsLeftToTheNextHello() {
        openLink()
        sample = PhoneCapabilities(callControl = false, frameworkAudio = true)
        factory.last().down("network_lost")
        assertEquals(listOf("network_lost"), downs)

        // Nothing to refresh while down; the next hello reads the sample itself.
        assertFalse(refresh.run())
        scheduler.advance(LinkSupervisor.RECONNECT_BASE_MS)
        factory.last().open()

        assertTrue(helloOf(factory.last()).capabilities.frameworkAudio)
        assertEquals(listOf("network_lost"), downs)
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** Time and timers, moved only by the test. */
    private class TestScheduler : LinkScheduler {
        private class Task(val due: Long, val order: Int, val action: () -> Unit)

        private val pending = mutableListOf<Task>()
        private var order = 0
        var now = 0L

        override fun after(delayMs: Long, task: () -> Unit): LinkTask {
            val scheduled = Task(now + delayMs, order++, task)
            pending += scheduled
            return LinkTask { pending.remove(scheduled) }
        }

        /** Runs everything due within [ms], in order, moving the clock with it. */
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = pending.filter { it.due <= target }
                    .minWithOrNull(compareBy({ it.due }, { it.order }))
                    ?: break
                pending.remove(next)
                now = next.due
                next.action()
            }
            now = target
        }
    }

    private class TestFactory : DeviceLinkFactory {
        val created = mutableListOf<TestLink>()

        fun last(): TestLink = created.last()

        override fun create(): DeviceLink = TestLink().also { created += it }

        override fun shutdown() = Unit
    }

    /** A link that opens and drops when the test says so and records what it carried. */
    private class TestLink : DeviceLink {
        private var listener: DeviceLink.Listener? = null
        val sentText = mutableListOf<String>()

        override val backlogBytes: Long get() = 0L

        override fun start(listener: DeviceLink.Listener) {
            this.listener = listener
        }

        override fun sendText(message: String): Boolean {
            sentText += message
            return true
        }

        override fun sendBinary(frame: ByteArray, length: Int) = Unit

        override fun cancel() = Unit

        override fun route(): DeviceLink.Route? = null

        fun open() = listener!!.onLinkOpened()

        fun down(reason: String) = listener!!.onLinkDown(reason)
    }
}
