package com.phonxiety.bridge.engine

import android.content.Context
import android.telecom.Call
import com.phonxiety.bridge.EngineStateStore
import com.phonxiety.bridge.audio.AudioStatsPublisher
import com.phonxiety.bridge.model.DeviceCommand
import com.phonxiety.bridge.model.DeviceOutbound
import com.phonxiety.bridge.model.DeviceProtocol
import com.phonxiety.bridge.telecom.TelecomObserver
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/**
 * The daemon's `hangup` replies, byte for byte.
 *
 * The reply bytes are the protocol contract with the daemon: the extracted
 * hang-up core may be shared with the local end, but `ending`, the terminal
 * state and `disconnect_failed` must serialize exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OwnedHangupTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    /** A Call whose adapter records `disconnectCall`; a failing adapter proves the error reply. */
    private class TelecomCall(private val failDisconnect: Boolean = false) {
        val disconnects = AtomicInteger(0)
        val call: Call

        init {
            val adapterClass = Class.forName("android.telecom.InCallAdapter")
            val constructor = adapterClass.declaredConstructors.first {
                it.parameterTypes.size == 1 && it.parameterTypes[0].isInterface
            }
            val iface = constructor.parameterTypes[0]
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, _ ->
                if (method.name == "disconnectCall") {
                    disconnects.incrementAndGet()
                    if (failDisconnect) throw IllegalStateException("the adapter refused")
                }
                null
            }
            call = Shadow.newInstanceOf(Call::class.java)
            call.javaClass.getDeclaredField("mInCallAdapter").apply { isAccessible = true }
                .set(call, constructor.newInstance(proxy))
        }
    }

    /** A real [CommandService] and [CallSession] behind one single-thread command executor. */
    private inner class Harness {
        val calls = FakeCallStore()
        val session = CallSession(calls, OWNER, clock = { 1_000L })
        val store = EngineStateStore()
        val replies = LinkedBlockingQueue<DeviceOutbound.Response>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "commands").apply { isDaemon = true }
        }
        val wire: (DeviceOutbound) -> Boolean = { message ->
            if (message is DeviceOutbound.Response) replies += message
            true
        }
        val audio = AudioStatsPublisher(context, session, store, wire, executor, { true }, { _ -> false })
        val service: CommandService

        init {
            session.beginDial(
                callId = CALL_ID,
                destination = DESTINATION,
                phoneAccount = null,
                unrelatedCall = { false },
                linkUp = { true },
                running = { true }
            )
            val json = Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }
            val diagnostics = DiagnosticsService(
                context,
                session,
                { error("unused") },
                store,
                wire,
                json,
                { true },
                OWNER
            )
            service = CommandService(
                context = context,
                session = session,
                dials = calls,
                telecom = null,
                store = store,
                audio = audio,
                wire = wire,
                executor = executor,
                linkUp = { true },
                sendFrame = { _, _ -> true },
                unrelatedCall = { false },
                isRunning = { true },
                audioSettings = AudioSettings(),
                diagnostics = diagnostics,
                ownerPackage = OWNER
            )
        }
    }

    private fun Harness.attach(call: Call) {
        synchronized(session.lock) {
            session.owned!!.call = call
            session.owned!!.telecomState = Call.STATE_ACTIVE
        }
    }

    private fun Harness.beginDial(callId: String, destination: String) {
        session.beginDial(
            callId = callId,
            destination = destination,
            phoneAccount = null,
            unrelatedCall = { false },
            linkUp = { true },
            running = { true }
        )
    }

    private fun Harness.dispatch(command: DeviceCommand) {
        service.dispatch(command.id, DeviceProtocol.encodeCommand(command))
    }

    /** The next reply; the blocked wait is the test's only barrier, never a sleep. */
    private fun Harness.awaitedReply(id: String): DeviceOutbound.Response {
        val reply = replies.poll(5, TimeUnit.SECONDS)
        assertNotNull("command $id did not answer", reply)
        assertEquals(id, reply.id)
        return reply
    }

    private fun Harness.encodedHangup(
        id: String,
        callId: String = CALL_ID
    ): String {
        dispatch(DeviceCommand.Hangup(id = id, callId = callId))
        return DeviceProtocol.encode(awaitedReply(id))
    }

    /** A ping behind the local end: its reply proves the end has already run. */
    private fun Harness.drainCommands() {
        dispatch(DeviceCommand.Ping(id = "after"))
        awaitedReply("after")
    }

    private fun setDetails(call: Call, destination: String, creationMillis: Long, state: Int) {
        val details = Shadow.newInstanceOf(Call.Details::class.java)
        fun set(name: String, value: Any) {
            details.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(details, value)
        }
        set("mHandle", android.net.Uri.fromParts("tel", destination, null))
        set("mState", state)
        set("mCallDirection", Call.Details.DIRECTION_OUTGOING)
        set("mCreationTimeMillis", creationMillis)
        call.javaClass.getDeclaredField("mDetails").apply { isAccessible = true }.set(call, details)
    }

    @Test
    fun theLocalEndDisconnectsOnlyTheOwnedCall() {
        val harness = Harness()
        val owned = TelecomCall()
        // Another live call: Telecom has it, the engine does not.
        val unrelated = TelecomCall()
        harness.attach(owned.call)

        assertTrue("the owned call was not queued for the local end", harness.service.endOwnedLocally(null))
        harness.drainCommands()

        assertEquals("the owned call was not disconnected", 1, owned.disconnects.get())
        assertEquals("an unrelated call was disconnected", 0, unrelated.disconnects.get())
    }

    @Test
    fun aPendingDialIsTombstonedAndTheLateCallIsDisconnectedOnArrival() {
        val harness = Harness()
        val startedAt = synchronized(harness.session.lock) { harness.session.owned!!.dialStartedWall }

        // Telecom has not surfaced the dial yet.
        assertTrue(harness.service.endOwnedLocally(CALL_ID))
        harness.drainCommands()

        assertEquals("the tombstone was not written", listOf(CALL_ID), harness.calls.orphanCalls)
        assertNull("the pending intent was not dropped", synchronized(harness.session.lock) { harness.session.owned })

        val late = TelecomCall()
        setDetails(late.call, DESTINATION, startedAt, Call.STATE_DIALING)
        val observer = TelecomObserver(
            session = harness.session,
            dials = harness.calls,
            store = harness.store,
            report = StateReportPublisher(harness.session, harness.calls, harness.store, harness.wire),
            audio = harness.audio,
            ownerPackage = OWNER,
            isRunning = { true },
            cancelTone = {}
        )
        try {
            observer.onCallAdded(late.call)
        } finally {
            observer.close()
        }

        assertEquals("the late call was not disconnected on arrival", 1, late.disconnects.get())
        assertTrue("the tombstone was not consumed", harness.calls.snapshot.orphans.isEmpty())
    }

    @Test
    fun aPreviousCallIdLeavesTheSuccessorConnected() {
        val harness = Harness()
        val previous = TelecomCall()
        harness.attach(previous.call)

        // A is ended here; Telecom finishes A and the engine then takes B.
        assertTrue("the previous call was not queued for the local end", harness.service.endOwnedLocally(null))
        harness.drainCommands()
        assertEquals("the previous call was not disconnected", 1, previous.disconnects.get())

        synchronized(harness.session.lock) { harness.session.owned = null }
        harness.beginDial("call-2", "+998907654321")
        val successor = TelecomCall()
        synchronized(harness.session.lock) {
            harness.session.owned!!.call = successor.call
            harness.session.owned!!.telecomState = Call.STATE_ACTIVE
        }

        assertFalse("a previous call id was queued", harness.service.endOwnedLocally(CALL_ID))
        harness.drainCommands()

        assertEquals("the previous call id disconnected the successor", 0, successor.disconnects.get())
        assertNotNull("the previous call id dropped the successor", synchronized(harness.session.lock) { harness.session.owned })
        assertTrue("a previous call id wrote a tombstone", harness.calls.orphanCalls.isEmpty())
    }

    @Test
    fun theDaemonHangupRepliesAreUnchanged() {
        val harness = Harness()
        harness.attach(TelecomCall().call)

        assertEquals(
            "the ending reply changed",
            """{"type":"response","id":"hang","ok":true,"data":{"call_id":"$CALL_ID","state":"ending"}}""",
            harness.encodedHangup("hang")
        )

        // The call is no longer owned but the session still remembers it.
        synchronized(harness.session.lock) {
            harness.session.owned = null
            harness.session.rememberLocked(CALL_ID, DESTINATION, "failed")
        }
        assertEquals(
            "the terminal reply changed",
            """{"type":"response","id":"hang-2","ok":true,"data":{"call_id":"$CALL_ID","state":"ended"}}""",
            harness.encodedHangup("hang-2")
        )

        val failing = Harness()
        failing.attach(TelecomCall(failDisconnect = true).call)
        assertEquals(
            "the disconnect_failed reply changed",
            """{"type":"response","id":"hang","ok":false,"error":"disconnect_failed"}""",
            failing.encodedHangup("hang")
        )
    }

    private companion object {
        const val OWNER = "com.phonxiety.bridge"
        const val CALL_ID = "call-1"
        const val DESTINATION = "+998901234567"
    }
}
