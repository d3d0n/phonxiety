package com.phonxiety.bridge

import com.phonxiety.bridge.model.ConnectionInfo
import com.phonxiety.bridge.model.DaemonInfo
import com.phonxiety.bridge.model.DeviceApiRequest
import com.phonxiety.bridge.model.DeviceApiResult
import com.phonxiety.bridge.net.CandidateStore
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The info request follows the daemon's app access, waits for an idle link,
 * and never applies an answer that outlived its connection or its pairing.
 */
class AppInfoRefreshTest {
    private val pinA = "a".repeat(64)
    private val pinB = "b".repeat(64)
    private val lanA = "wss://192.168.1.10:18763/device"

    private fun store(): CandidateStore = CandidateStore(File(createTempDirectory("candidates").toFile(), "link-candidates.json"))

    private val json = Json { encodeDefaults = true }

    private fun answer(connection: ConnectionInfo?): DeviceApiResult = DeviceApiResult(
        id = "answer",
        status = 200,
        result = json.encodeToJsonElement(
            DaemonInfo.serializer(),
            DaemonInfo(daemonVersion = "0.4.0", connection = connection)
        )
    )

    private class Harness(
        val store: CandidateStore,
        val admission: CallAdmission = CallAdmission()
    ) {
        val requests = mutableListOf<DeviceApiRequest>()
        val applied = mutableListOf<DaemonInfo>()
        var callOwned = false
        var linkUp = true
        var token: String? = null
        var identity = "cert:${"a".repeat(64)}"
        var pairingEpoch = 0L
        var onEnqueue: (() -> Unit) -> Unit = { it() }
        var reply: suspend (DeviceApiRequest) -> DeviceApiResult = { request ->
            DeviceApiResult(request.id, 200, buildJsonObject { put("daemon_version", "0.4.0") })
        }
    }

    private fun kotlinx.coroutines.test.TestScope.refresh(harness: Harness): AppInfoRefresh = AppInfoRefresh(
        store = harness.store,
        identity = { harness.identity },
        admission = harness.admission,
        callOwned = { harness.callOwned },
        linkUp = { harness.linkUp },
        token = { harness.token },
        pairingEpoch = { harness.pairingEpoch },
        scope = this,
        exchange = { request, onEnqueued ->
            harness.requests += request
            harness.onEnqueue(onEnqueued)
            harness.reply(request)
        },
        apply = { harness.applied += it },
        nextId = { "info" }
    )

    @Test
    fun nothingIsAskedBeforeTheDaemonDeliversAppAccess() = runTest {
        val harness = Harness(store())
        harness.token = "app-token"
        val refresh = refresh(harness)

        refresh.linkOpened()
        refresh.tick()
        advanceUntilIdle()

        assertTrue(harness.requests.isEmpty())
    }

    @Test
    fun aDeliveredUnchangedTokenStillAsksOnceOnThisConnection() = runTest {
        val harness = Harness(store())
        harness.token = "app-token"
        val refresh = refresh(harness)

        refresh.linkOpened()
        refresh.delivered()
        advanceUntilIdle()
        refresh.delivered()
        advanceUntilIdle()

        assertEquals(1, harness.requests.size)
        assertEquals("app-token", harness.requests.single().appToken)
        assertEquals("/app/v1/info", harness.requests.single().path)
        assertEquals(1, harness.applied.size)
    }

    @Test
    fun aRequestDeferredByAnOwnedCallRunsOnlyWhenTheLinkIsIdle() = runTest {
        val harness = Harness(store())
        harness.token = "app-token"
        harness.callOwned = true
        val refresh = refresh(harness)

        refresh.linkOpened()
        refresh.delivered()
        advanceUntilIdle()
        assertTrue(harness.requests.isEmpty())

        harness.callOwned = false
        refresh.tick()
        advanceUntilIdle()

        assertEquals(1, harness.requests.size)
    }

    @Test
    fun anAnswerForAReconnectedLinkIsNotApplied() = runTest {
        val harness = Harness(store())
        harness.token = "app-token"
        val gate = CompletableDeferred<Unit>()
        harness.reply = {
            gate.await()
            answer(null)
        }
        val refresh = refresh(harness)

        refresh.linkOpened()
        refresh.delivered()
        runCurrent()
        assertEquals(1, harness.requests.size)

        // The connection was replaced while the answer was in flight.
        refresh.linkDown()
        refresh.linkOpened()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(harness.applied.isEmpty())
    }

    @Test
    fun anAnswerForThePreviousPairingIsNotStoredUnderTheNewOne() = runTest {
        val store = store()
        store.rebind("cert:$pinA", emptyList())
        val harness = Harness(store)
        harness.identity = "cert:$pinA"
        harness.token = "token-a"
        val gate = CompletableDeferred<Unit>()
        harness.reply = {
            gate.await()
            answer(ConnectionInfo(lan = listOf(lanA), certificate = pinA))
        }
        val refresh = refresh(harness)

        refresh.linkOpened()
        refresh.delivered()
        runCurrent()

        // The pairing moves (the gate would advance its generation) and the store
        // is rebound while the previous daemon's answer is in flight; this engine
        // still belongs to the previous pairing.
        store.rebind("cert:$pinB", emptyList())
        harness.pairingEpoch = 1L
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("cert:$pinB", store.pairingIdentity())
        assertTrue(store.load().isEmpty())
        assertTrue("capabilities from the previous pairing are not published", harness.applied.isEmpty())
    }

    @Test
    fun aPairingThatMovesWhileTheAnswerIsInFlightAppliesNeitherEffect() = runTest {
        val store = store()
        store.rebind("cert:$pinA", emptyList())
        val harness = Harness(store)
        harness.identity = "cert:$pinA"
        harness.token = "token-a"
        val gate = CompletableDeferred<Unit>()
        harness.reply = {
            gate.await()
            answer(ConnectionInfo(lan = listOf(lanA), certificate = pinA))
        }
        val refresh = refresh(harness)

        refresh.linkOpened()
        refresh.delivered()
        runCurrent()

        // The pairing generation moves before the answer is decoded; the engine
        // itself is still the old one.
        harness.pairingEpoch = 1L
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue("no capability is published under the new pairing", harness.applied.isEmpty())
        assertTrue("no address is stored under the new pairing", store.load().isEmpty())
    }

    @Test
    fun theIdleDoorIsHeldThroughTheEnqueueAndNeverAcrossTheWait() = runTest {
        val harness = Harness(store())
        harness.token = "app-token"
        val gate = CompletableDeferred<Unit>()
        harness.reply = {
            gate.await()
            answer(null)
        }
        harness.onEnqueue = { enqueue ->
            // While the frame is being handed over, a call cannot be reserved.
            assertNull("the door is held for the enqueue", harness.admission.tryBeginIdleLinkAction { false })
            enqueue()
            // Released before the answer is awaited.
            val after = harness.admission.tryBeginIdleLinkAction { false }
            assertNotNull("the door is free while the answer is awaited", after)
            after?.close()
        }
        val refresh = refresh(harness)

        refresh.linkOpened()
        refresh.delivered()
        runCurrent()
        assertEquals(1, harness.requests.size)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, harness.applied.size)
    }

    @Test
    fun aCallReservedBetweenSchedulingAndExecutionRefusesTheRequest() = runTest {
        val harness = Harness(store())
        harness.token = "app-token"
        val refresh = refresh(harness)

        refresh.linkOpened()
        refresh.delivered()
        // The coroutine has not run yet; a call is reserved first.
        harness.callOwned = true
        runCurrent()
        assertTrue("no request starts under a reserved call", harness.requests.isEmpty())

        harness.callOwned = false
        refresh.tick()
        runCurrent()
        assertEquals(1, harness.requests.size)
    }
}
