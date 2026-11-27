package com.phonxiety.bridge.engine

import android.content.Context
import com.phonxiety.bridge.AppInfoRefresh
import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.CapabilityWatch
import com.phonxiety.bridge.EngineStateStore
import com.phonxiety.bridge.model.DeviceOutbound
import com.phonxiety.bridge.model.DeviceProtocol
import com.phonxiety.bridge.model.DisconnectCause
import com.phonxiety.bridge.model.LinkDownReason
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.net.ApiExchange
import com.phonxiety.bridge.net.ApiRequestTable
import com.phonxiety.bridge.net.AttemptOutcome
import com.phonxiety.bridge.net.CandidateStore
import com.phonxiety.bridge.net.DeviceLink
import com.phonxiety.bridge.net.DeviceLinkFactory
import com.phonxiety.bridge.net.LinkReadiness
import com.phonxiety.bridge.net.LinkScheduler
import com.phonxiety.bridge.net.LinkSupervisor
import com.phonxiety.bridge.net.LinkTask
import com.phonxiety.bridge.net.RequestFailure
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The retained terminal report through the real link wiring: a `DeviceLink`
 * that refuses a frame, a real [LinkSupervisor] and [LinkEvents], and a real
 * [ApiExchange] with a request already waiting for the handshake.
 *
 * The accepted hello must carry the retained `ended` first and only then
 * release the waiting app request; the wire order proves the replay sits
 * between the hello and the request.  Everything runs on one thread: the
 * waiting request is started under [Dispatchers.Unconfined] and resumes inline
 * in `helloQueued`, so the recorded frames need no polling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HelloReplayTest {
    private val cause = DisconnectCause(code = 2, label = "NORMAL", reason = "normal")

    /** A scheduler whose tasks run only when the test says so. */
    private class TestScheduler : LinkScheduler {
        private val pending = mutableListOf<Pair<Long, () -> Unit>>()

        override fun after(delayMs: Long, task: () -> Unit): LinkTask {
            val entry = delayMs to task
            pending += entry
            return LinkTask { pending.remove(entry) }
        }

        fun runDue() {
            val due = pending.toList()
            pending.clear()
            due.forEach { it.second() }
        }
    }

    private class FakeFactory : DeviceLinkFactory {
        val created = mutableListOf<FakeLink>()

        fun last(): FakeLink = created.last()

        override fun create(): DeviceLink = FakeLink().also { created += it }

        override fun shutdown() = Unit

        override fun attemptEnded(outcome: AttemptOutcome, kind: LinkKind?) = Unit
    }

    private class FakeLink : DeviceLink {
        private var listener: DeviceLink.Listener? = null
        val sentText: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        override fun start(listener: DeviceLink.Listener) {
            this.listener = listener
        }

        override fun sendText(message: String): Boolean {
            sentText += message
            return true
        }

        override fun sendBinary(frame: ByteArray, length: Int) = Unit

        override val backlogBytes: Long get() = 0L

        override fun cancel() = Unit

        override fun route(): DeviceLink.Route? = null

        fun open() = listener!!.onLinkOpened()

        fun down(reason: LinkDownReason, detail: String? = null) = listener!!.onLinkDown(reason, detail)
    }

    private class Harness(context: Context) {
        val session = CallSession(FakeCallStore(), "com.phonxiety.bridge", clock = { 1_000L })
        val store = EngineStateStore()
        val audioSettings = AudioSettings()
        val hello = HelloState(LinkKind.WEBSOCKET)
        val factory = FakeFactory()
        val scheduler = TestScheduler()
        val table = ApiRequestTable()
        val readiness = LinkReadiness()
        val requests = ApiExchange(table, readiness, { 0L }, { wire -> supervisor.sendText(wire) })
        val publisher: StateReportPublisher
        lateinit var supervisor: LinkSupervisor
        val settings: SettingsSync
        val events: LinkEvents

        init {
            val wire = { message: DeviceOutbound -> supervisor.sendText(DeviceProtocol.encode(message)) }
            publisher = StateReportPublisher(session, FakeCallStore(), store, wire)
            settings = SettingsSync(
                context = context,
                links = { supervisor },
                scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "settings").apply { isDaemon = true }
                },
                audioSettings = audioSettings,
                hello = hello,
                wire = wire,
                ownsCall = { session.ownsCall() },
                isRunning = { true }
            )
            val appInfo = AppInfoRefresh(
                store = CandidateStore.of(context),
                identity = { "pairing" },
                admission = session.admission,
                callOwned = { session.ownsCall() },
                linkUp = { supervisor.isUp() && hello.delivered },
                token = { "app-token" },
                pairingEpoch = { 0L },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                exchange = { _, _ -> error("no info request in this test") },
                apply = {}
            )
            events = LinkEvents(
                context = context,
                session = session,
                store = store,
                publisher = publisher,
                wire = wire,
                links = { supervisor },
                settings = settings,
                hello = hello,
                appInfo = appInfo,
                requests = requests,
                routeText = {},
                capabilityWatch = CapabilityWatch(),
                linkGeneration = AtomicLong(0L),
                deviceBootId = "boot-1",
                config = BridgeConfig.Address("wss://127.0.0.1:1/device", "device-token", null),
                audioSettings = audioSettings,
                appTokenPresent = { true },
                unrelatedCall = { false },
                onCommand = { _, _ -> },
                onAppAccess = { _, _ -> },
                onLinkDown = {}
            )
            supervisor = LinkSupervisor(factory, events, scheduler) { 0L }
        }

        fun ownedCall(): CallSession.OwnedCall {
            session.beginDial(
                callId = "call-1",
                destination = "+998901234567",
                phoneAccount = null,
                unrelatedCall = { false },
                linkUp = { true },
                running = { true }
            )
            return session.owned!!
        }
    }

    private fun kindOf(wire: String, ended: String): String = when {
        wire.startsWith("""{"type":"hello"""") -> "hello"
        wire == ended -> "ended"
        else -> "request"
    }

    @Test
    fun aRefusedEndedFollowsTheHelloAndPrecedesTheWaitingRequest() {
        val harness = Harness(RuntimeEnvironment.getApplication())
        val call = harness.ownedCall()

        harness.supervisor.start()
        harness.factory.last().open()
        assertTrue(harness.supervisor.isUp())
        harness.factory.last().down(LinkDownReason.CLOSED)

        // The link that would carry the ending cannot: the frame is retained.
        harness.publisher.report(call, "ended", cause)

        // An app request is already waiting for the next handshake.  Unconfined
        // starts it in place and it suspends on the readiness gate.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val request = scope.launch(Dispatchers.Unconfined) {
            harness.requests.exchange("req-1", """{"type":"api","id":"req-1"}""", 60_000)
        }
        assertEquals(1, harness.table.size())

        harness.scheduler.runDue()
        val second = harness.factory.last()
        second.open()

        // The hello releases the waiting request inline on this thread; the replay
        // already ran between the two.
        val ended = DeviceProtocol.encode(DeviceOutbound.CallState("call-1", "ended", cause))
        assertEquals(
            listOf("hello", "ended", "request"),
            second.sentText.toList().map { kindOf(it, ended) }
        )
        assertEquals(1, second.sentText.toList().count { it == ended })

        harness.requests.stop(RequestFailure.ShuttingDown)
        assertTrue(request.isCompleted)

        // A later reconnect has nothing retained: the accepted frame was cleared.
        second.down(LinkDownReason.CLOSED)
        harness.scheduler.runDue()
        val third = harness.factory.last()
        third.open()
        assertEquals(0, third.sentText.toList().count { it == ended })
        assertEquals(listOf("hello"), third.sentText.toList().map { kindOf(it, ended) })
    }

    /**
     * The typed reason reaches the store through the real link wiring:
     * `LinkError.code` keeps the spelling surfaces have always shown, the
     * typed reason is the value itself, and a native close keeps its words.
     */
    @Test
    fun theTypedReasonReachesTheStateStore() {
        val harness = Harness(RuntimeEnvironment.getApplication())
        harness.supervisor.start()
        harness.factory.last().open()

        harness.factory.last().down(LinkDownReason.NETWORK_LOST)
        val known = harness.store.state.value.lastError!!
        assertEquals("network_lost", known.code)
        assertEquals(LinkDownReason.NETWORK_LOST, known.reason)
        assertEquals("the network is gone", known.message)
        assertNull(known.detail)

        harness.scheduler.runDue()
        harness.factory.last().open()
        harness.factory.last().down(LinkDownReason.OTHER, "timed out by peer")
        val native = harness.store.state.value.lastError!!
        assertEquals("timed out by peer", native.code)
        assertEquals(LinkDownReason.OTHER, native.reason)
        assertEquals("timed out by peer", native.detail)
        assertEquals("timed out by peer", native.message)
    }
}
