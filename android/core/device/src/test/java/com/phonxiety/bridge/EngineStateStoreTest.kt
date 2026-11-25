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

            store.audio("opus", 24_000)
            val audio = awaitItem().audio
            assertEquals("opus", audio.codec)
            assertEquals(24_000, audio.bitrate)

            store.audioLevels(-20.5f, -31.0f)
            assertEquals(-20.5f, awaitItem().audio.rxLevelDbfs)

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
