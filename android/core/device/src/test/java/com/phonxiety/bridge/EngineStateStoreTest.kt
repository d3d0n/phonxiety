package com.phonxiety.bridge

import app.cash.turbine.test
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.DeviceState
import com.phonxiety.bridge.model.LinkError
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineStateStoreTest {
    @Test
    fun linkCallAudioAndDeviceTransitionsArriveInOrder() = runTest {
        val store = EngineStateStore()
        store.state.test {
            assertTrue(awaitItem().link is LinkState.Offline)

            store.linkConnecting(LinkKind.IROH)
            val connecting = awaitItem().link as LinkState.Connecting
            assertEquals(LinkKind.IROH, connecting.candidate)

            store.linkUp(LinkKind.IROH, RouteSnapshot(relay = false, rttMs = 18), sinceEpochMs = 1_000, reconnects = 2)
            val up = awaitItem().link as LinkState.Up
            assertEquals(PathKind.DIRECT, up.path)
            assertEquals(18L, up.rttMs)
            assertEquals(2, up.reconnects)

            store.route(RouteSnapshot(relay = true, rttMs = 55))
            assertEquals(PathKind.RELAY, (awaitItem().link as LinkState.Up).path)

            store.callStarted("c1", "+******1234", 2_000)
            val dialing = awaitItem()
            assertEquals(CallPhase.DIALING, dialing.call?.phase)
            assertEquals("+******1234", dialing.call?.destinationMasked)

            store.callPhase(CallPhase.ACTIVE)
            assertEquals(CallPhase.ACTIVE, awaitItem().call?.phase)

            val stream = store.audioStreamStart()
            store.audio(stream, "opus", 24_000)
            val audio = awaitItem().audio
            assertEquals("opus", audio.codec)
            assertEquals(24_000, audio.bitrate)

            assertTrue(
                store.audioStats(
                    stream,
                    AudioStats(
                        codec = "opus",
                        bitrate = 24_000,
                        rxDbfs = -20.5f,
                        txDbfs = -31.0f,
                        framesOut = 0,
                        framesIn = 0,
                        drops = 0
                    )
                )
            )
            assertEquals(-20.5f, awaitItem().audio.rxLevelDbfs)
            assertEquals(-31.0f, store.state.value.audio.txLevelDbfs)
            store.device(DeviceState(batteryPercent = 74, network = "wifi", wifiRssi = -55))
            val device = awaitItem().device
            assertEquals(74, device.batteryPercent)
            assertEquals("wifi", device.network)

            store.linkDown(LinkError("host_timeout", "the daemon stopped answering", 3_000))
            val down = awaitItem()
            assertTrue(down.link is LinkState.Offline)
            assertNull(down.call)
            assertEquals("host_timeout", down.lastError?.code)
        }
    }

    @Test
    fun theConnectTimeIsRecordedOnceAtTheAnswer() {
        val store = EngineStateStore()
        store.callStarted("c1", "+******1234", 2_000)
        assertNull("dialing has no connect time yet", store.state.value.call?.connectedAtEpochMs)

        store.callPhase(CallPhase.DIALING)
        assertNull(store.state.value.call?.connectedAtEpochMs)

        // Telecom's own connect time is the base the system's call chip uses.
        store.callPhase(CallPhase.ACTIVE, connectTimeMillis = 1_500)
        assertEquals(1_500L, store.state.value.call?.connectedAtEpochMs)

        // A later ACTIVE with another Telecom value keeps the first stamp for this call.
        store.callPhase(CallPhase.ACTIVE, connectTimeMillis = 9_000)
        assertEquals(1_500L, store.state.value.call?.connectedAtEpochMs)

        store.callPhase(CallPhase.ENDING)
        assertEquals("the stamp survives the phases after the answer", 1_500L, store.state.value.call?.connectedAtEpochMs)
    }

    @Test
    fun theConnectTimeFallsBackToTheEnginesClock() {
        val store = EngineStateStore()
        store.callStarted("c1", "+******1234", 2_000)

        val before = System.currentTimeMillis()
        store.callPhase(CallPhase.ACTIVE)
        val after = System.currentTimeMillis()

        val stamp = store.state.value.call?.connectedAtEpochMs
        assertTrue("the engine's clock stamps the answer", stamp != null && stamp in before..after)
    }

    @Test
    fun aMissingTelecomConnectTimeFallsBackToTheEnginesClock() {
        // Telecom reports 0 before the line is up, and the engine may not see a details value.
        for (telecom in listOf<Long?>(null, 0L, -1L)) {
            val store = EngineStateStore()
            store.callStarted("c1", "+******1234", 2_000)
            val before = System.currentTimeMillis()
            store.callPhase(CallPhase.ACTIVE, connectTimeMillis = telecom)
            val after = System.currentTimeMillis()
            val stamp = store.state.value.call?.connectedAtEpochMs
            assertTrue("$telecom falls back to the clock", stamp != null && stamp in before..after)
        }
    }

    @Test
    fun aDuplicateDialKeepsTheCallsAnswerAndPlacement() {
        val store = EngineStateStore()
        store.callStarted("c1", "+******1234", 2_000)
        store.callPhase(CallPhase.ACTIVE, connectTimeMillis = 1_500)

        // The host asks for the same call again: the store shows it, and this is not a new dial.
        store.callStarted("c1", "+******1234", 9_000)
        val replayed = store.state.value.call!!
        assertEquals("the same call never steps back to dialing", CallPhase.ACTIVE, replayed.phase)
        assertEquals("the ring is not restarted", 2_000L, replayed.startedAtEpochMs)
        assertEquals("the answer keeps its stamp", 1_500L, replayed.connectedAtEpochMs)

        store.callPhaseReplayed(CallPhase.ACTIVE)
        assertEquals("the replay adds no answer of its own", 1_500L, store.state.value.call?.connectedAtEpochMs)

        // A different call is a different dial: everything starts over.
        store.callStarted("c2", "+******5678", 9_000)
        assertEquals(9_000L, store.state.value.call?.startedAtEpochMs)
        assertNull(store.state.value.call?.connectedAtEpochMs)
    }

    @Test
    fun aRecoveredCallNeverStampsAnAnswerTheEngineDidNotSee() {
        val store = EngineStateStore()
        // A restart replays the intent it owns: the placement is the durable one, the phase comes
        // from the ledger, and the engine's clock at recovery is not an answer.
        store.callStarted("c1", "+******1234", 2_000)
        store.callPhaseReplayed(CallPhase.ACTIVE)

        assertEquals(CallPhase.ACTIVE, store.state.value.call?.phase)
        assertNull("recovery must not invent a connect time", store.state.value.call?.connectedAtEpochMs)

        // Telecom surfaces the live call and its own connect time is the first real one.
        store.callPhase(CallPhase.ACTIVE, connectTimeMillis = 1_500)
        assertEquals(1_500L, store.state.value.call?.connectedAtEpochMs)
    }

    @Test
    fun aRecoveredCallTakesTelecomsConnectTimeAndKeepsIt() {
        val store = EngineStateStore()
        store.callStarted("c1", "+******1234", 2_000)
        // The live call was already there at recovery, so Telecom's timestamp is available.
        store.callPhaseReplayed(CallPhase.ACTIVE, connectTimeMillis = 1_500)
        assertEquals(1_500L, store.state.value.call?.connectedAtEpochMs)

        // Adoption repeats the phase with the same answer: the first stamp stands.
        store.callPhaseReplayed(CallPhase.ACTIVE, connectTimeMillis = 1_500)
        store.callPhase(CallPhase.ACTIVE, connectTimeMillis = 9_000)
        assertEquals(1_500L, store.state.value.call?.connectedAtEpochMs)
    }

    @Test
    fun theEndTimeIsRecordedOnceAtTheEnd() {
        val store = EngineStateStore()
        store.callStarted("c1", "+******1234", 2_000)
        store.callPhase(CallPhase.ACTIVE, connectTimeMillis = 1_500)
        assertNull("a live call has no end yet", store.state.value.call?.endedAtEpochMs)

        store.callPhase(CallPhase.ENDING)
        assertNull("a call on its way out is still running", store.state.value.call?.endedAtEpochMs)

        val before = System.currentTimeMillis()
        store.callPhase(CallPhase.ENDED)
        val after = System.currentTimeMillis()
        val ended = store.state.value.call?.endedAtEpochMs
        assertTrue("the engine's clock stamps the end", ended != null && ended in before..after)
        assertEquals("the answer keeps its own stamp", 1_500L, store.state.value.call?.connectedAtEpochMs)

        // A replayed terminal report keeps the first end, or the frozen duration would move.
        store.callPhaseReplayed(CallPhase.ENDED)
        assertEquals(ended, store.state.value.call?.endedAtEpochMs)
        store.callEnded()
        assertEquals(ended, store.state.value.call?.endedAtEpochMs)

        // A duplicate dial of the ended call is still that call: the end stays.
        store.callStarted("c1", "+******1234", 9_000)
        assertEquals(ended, store.state.value.call?.endedAtEpochMs)
    }

    @Test
    fun callEndedStampsTheEndAndTheNextCallStartsClean() {
        val store = EngineStateStore()
        store.callStarted("c1", "+******1234", 2_000)
        store.callPhase(CallPhase.ACTIVE, connectTimeMillis = 1_500)

        val before = System.currentTimeMillis()
        store.callEnded()
        val after = System.currentTimeMillis()
        val ended = store.state.value.call?.endedAtEpochMs
        assertEquals(CallPhase.ENDED, store.state.value.call?.phase)
        assertTrue(ended != null && ended in before..after)

        store.callStarted("c2", "+******5678", 9_000)
        assertNull("a new call has not ended", store.state.value.call?.endedAtEpochMs)
    }

    @Test
    fun endedCallStaysVisibleUntilTheNextCall() = runTest {
        val store = EngineStateStore()
        store.state.test {
            awaitItem()
            store.callStarted("c1", "+******1234", 1_000)
            awaitItem()
            store.callEnded()
            assertEquals(CallPhase.ENDED, awaitItem().call?.phase)

            store.callStarted("c2", "+******5678", 2_000)
            assertEquals("c2", awaitItem().call?.callId)
        }
    }

    @Test
    fun destinationMaskKeepsOnlyTheLastFourDigits() {
        assertEquals("+********4567", maskDestination("+998901234567"))
        assertEquals("1234", maskDestination("1234"))
        assertEquals("**3456", maskDestination("123456"))
    }

    @Test
    fun callPhasesMatchTheProtocolStrings() {
        assertEquals(CallPhase.DIALING, callPhaseOf("dialing"))
        assertEquals(CallPhase.ACTIVE, callPhaseOf("active"))
        assertEquals(CallPhase.ENDING, callPhaseOf("ending"))
        assertEquals(CallPhase.ENDED, callPhaseOf("ended"))
        assertEquals(CallPhase.ENDED, callPhaseOf("anything-else"))
    }
}
