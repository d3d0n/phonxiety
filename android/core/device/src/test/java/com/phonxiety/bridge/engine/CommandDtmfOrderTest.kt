package com.phonxiety.bridge.engine

import android.content.Context
import android.telecom.Call
import com.phonxiety.bridge.EngineStateStore
import com.phonxiety.bridge.audio.AudioStatsPublisher
import com.phonxiety.bridge.model.DeviceCommand
import com.phonxiety.bridge.model.DeviceOutbound
import com.phonxiety.bridge.model.DeviceProtocol
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/**
 * Command and DTMF cancellation order through [CommandService.dispatch] with
 * its real single-thread executors.
 *
 * A DTMF command queued behind `cancel_actions` for the generation it
 * cancelled is refused before it can start the tone runner, and the command
 * executor answers in dispatch order exactly as the pre-split engine did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommandDtmfOrderTest {
    private val played: MutableList<Char> = Collections.synchronizedList(mutableListOf())
    private val stopped: MutableList<Unit> = Collections.synchronizedList(mutableListOf())
    private val responses: MutableList<DeviceOutbound.Response> = Collections.synchronizedList(mutableListOf())

    private fun encode(command: DeviceCommand): String = DeviceProtocol.encodeCommand(command)

    /**
     * A [Call] object whose DTMF calls land in [played] and [stopped]: the
     * platform class routed through an adapter proxy, since the constructor is
     * not public API.
     */
    private fun shadowCall(): Call {
        val call = Shadow.newInstanceOf(Call::class.java)
        val adapterClass = Class.forName("android.telecom.InCallAdapter")
        val constructor = adapterClass.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0].isInterface
        } ?: error("no InCallAdapter(IInCallAdapter) constructor")
        val iface = constructor.parameterTypes[0]
        val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            when (method.name) {
                "playDtmfTone" -> played += args!![1] as Char
                "stopDtmfTone" -> stopped += Unit
            }
            null
        }
        val field = Call::class.java.declaredFields.firstOrNull { it.type == adapterClass }
            ?: error("Call has no InCallAdapter field")
        field.isAccessible = true
        field.set(call, constructor.newInstance(proxy))
        return call
    }

    private fun service(call: Call): CommandService {
        val context: Context = RuntimeEnvironment.getApplication()
        val calls = FakeCallStore()
        val session = CallSession(calls, "com.phonxiety.bridge", clock = { 1_000L })
        session.beginDial(
            callId = "call-1",
            destination = "+998901234567",
            phoneAccount = null,
            unrelatedCall = { false },
            linkUp = { true },
            running = { true }
        )
        synchronized(session.lock) {
            session.owned!!.call = call
            session.owned!!.telecomState = Call.STATE_ACTIVE
        }
        val store = EngineStateStore()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "commands").apply { isDaemon = true }
        }
        val wire = { message: DeviceOutbound ->
            if (message is DeviceOutbound.Response) responses += message
            true
        }
        val audio = AudioStatsPublisher(context, session, store, wire, executor, { true }, { _ -> false })
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
        val diagnostics = DiagnosticsService(context, session, { error("unused") }, store, wire, json, { true }, "com.phonxiety.bridge")
        return CommandService(
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
            ownerPackage = "com.phonxiety.bridge"
        )
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("the command did not answer in time", condition())
    }

    @Test
    fun digitsQueuedBehindACancelAreNotPlayedAndTheReplyOrderHolds() {
        val service = service(shadowCall())

        service.dispatch(
            "dtmf-1",
            encode(
                DeviceCommand.Dtmf(
                    id = "dtmf-1",
                    callId = "call-1",
                    actionGeneration = 1,
                    digits = "12",
                    toneMs = 1_000,
                    gapMs = 0
                )
            )
        )
        service.dispatch(
            "cancel",
            encode(DeviceCommand.CancelActions(id = "cancel", callId = "call-1", actionGeneration = 2))
        )
        // Same generation the cancel just retired: it must never start a tone run.
        service.dispatch(
            "dtmf-2",
            encode(
                DeviceCommand.Dtmf(
                    id = "dtmf-2",
                    callId = "call-1",
                    actionGeneration = 1,
                    digits = "345",
                    toneMs = 40,
                    gapMs = 0
                )
            )
        )

        await { responses.count { it.id == "dtmf-1" || it.id == "cancel" || it.id == "dtmf-2" } == 3 }

        assertTrue("digits queued behind the cancel must not play", played.none { it in "345" })
        val cancel = responses.first { it.id == "cancel" }
        assertTrue(cancel.ok)
        val stale = responses.first { it.id == "dtmf-2" }
        assertFalse(stale.ok)
        assertEquals("stale_generation", stale.error)
        // The interrupted runner answers `cancelled`, exactly as before the split.
        val interrupted = responses.first { it.id == "dtmf-1" }
        assertFalse(interrupted.ok)
        assertEquals("cancelled", interrupted.error)
        // The command executor answered the cancel before the command behind it.
        assertEquals(
            listOf("cancel", "dtmf-2"),
            responses.filter { it.id == "cancel" || it.id == "dtmf-2" }.map { it.id }
        )
    }

    @Test
    fun aCancelInterruptsTheToneInFlightAndStopsIt() {
        val service = service(shadowCall())
        service.dispatch(
            "dtmf-1",
            encode(
                DeviceCommand.Dtmf(
                    id = "dtmf-1",
                    callId = "call-1",
                    actionGeneration = 1,
                    digits = "12",
                    toneMs = 1_000,
                    gapMs = 0
                )
            )
        )
        val firstDigit = CountDownLatch(1)
        val waiter = Thread {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (played.isEmpty() && System.nanoTime() < deadline) Thread.sleep(1)
            firstDigit.countDown()
        }
        waiter.start()
        assertTrue(firstDigit.await(5, TimeUnit.SECONDS))
        service.dispatch(
            "cancel",
            encode(DeviceCommand.CancelActions(id = "cancel", callId = "call-1", actionGeneration = 2))
        )
        await { responses.any { it.id == "dtmf-1" } }

        assertEquals(listOf('1'), played.toList())
        assertTrue("the tone must be stopped when it is cut", stopped.isNotEmpty())
    }
}
