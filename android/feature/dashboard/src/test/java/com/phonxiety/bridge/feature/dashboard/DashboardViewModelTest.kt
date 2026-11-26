package com.phonxiety.bridge.feature.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.PairResult
import com.phonxiety.bridge.data.PairingRepository
import com.phonxiety.bridge.designsystem.component.LinkPath
import com.phonxiety.bridge.designsystem.formatDuration
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The dashboard ViewModel against fake repositories: combinable, measurable and pairable. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DashboardViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun combinesBridgeStateAndPairing() = runTest(dispatcher) {
        val bridge = FakeBridgeStateRepository(DIRECT)
        val pairing = FakePairingRepository(pairedNow = true)
        val viewModel = DashboardViewModel(
            ApplicationProvider.getApplicationContext<Context>(),
            bridge,
            pairing
        )

        viewModel.state.test {
            awaitItem()
            val item = awaitItem()
            assertTrue(item.paired)
            assertEquals(DIRECT, item.bridge)
        }
    }

    @Test
    fun unpairCallsTheRepository() = runTest(dispatcher) {
        val pairing = FakePairingRepository(pairedNow = true)
        val viewModel = DashboardViewModel(
            ApplicationProvider.getApplicationContext<Context>(),
            FakeBridgeStateRepository(DIRECT),
            pairing
        )

        viewModel.unpair()
        testScheduler.advanceUntilIdle()
        assertFalse(pairing.paired.value)
        assertTrue(pairing.unpairCalls > 0)
    }

    @Test
    fun uptimeReadsAsProseWithWholeUnits() {
        assertEquals("1 s", formatDuration(resources, 1))
        assertEquals("12 min", formatDuration(resources, 12 * 60L))
        assertEquals("2 h 05 min", formatDuration(resources, 125 * 60L))
        assertEquals("3 h 00 min", formatDuration(resources, 180 * 60L))
    }

    @Test
    fun aClockReadIsNotTheSameAsAMeasurement() {
        // A missing start is a dash; the formatters are only ever asked about a known start.
        assertEquals(74L, secondsBetween(1_000, 75_000))
        assertEquals(0L, secondsBetween(75_000, 1_000))
    }

    @Test
    fun pathMappingNeverClaimsAMeasuredPath() {
        assertEquals(LinkPath.DIRECT, PathKind.DIRECT.toDesignPath())
        assertEquals(LinkPath.RELAY, PathKind.RELAY.toDesignPath())
        // No route was selected, so nothing was measured: the pill reads "Unknown".
        assertEquals(LinkPath.UNKNOWN, PathKind.UNKNOWN.toDesignPath())
    }

    @Test
    fun thermalAndNetworkNamesBecomeWords() {
        assertEquals(ThermalState.NONE, thermalState("NONE"))
        assertEquals(ThermalState.NONE, thermalState("none"))
        assertEquals(ThermalState.WARM, thermalState("LIGHT"))
        assertEquals(ThermalState.HOT, thermalState("MODERATE"))
        assertEquals(ThermalState.HOT, thermalState("SEVERE"))
        assertEquals(ThermalState.UNKNOWN, thermalState("PLEASANT"))
        assertEquals(ThermalState.UNKNOWN, thermalState(null))

        assertEquals(NetworkKind.WIFI, networkKind("wifi"))
        assertEquals(NetworkKind.MOBILE, networkKind("cellular"))
        assertEquals(NetworkKind.ETHERNET, networkKind("ethernet"))
        assertEquals(NetworkKind.UNKNOWN, networkKind("homing_pigeon"))
    }

    @Test
    fun measuredValuesKeepTheirUnitAndTheirSign() {
        // −52 dBm with a Unicode minus and a no-break space, so the column lines up.
        assertEquals("−52\u00A0dBm", formatDbm(-52))
        assertEquals("3\u00A0dBm", formatDbm(3))
        // Playback drops are samples at 48 kHz; the tile reports the audio time they cost.
        assertEquals(0L, playbackDropMillis(0))
        assertEquals(1_000L, playbackDropMillis(48_000))
    }

    @Test
    fun knownPermissionsHaveTheirOwnWord() {
        assertEquals(R.string.dashboard_permission_microphone, knownPermission("android.permission.RECORD_AUDIO"))
        assertEquals(R.string.dashboard_permission_local_network, knownPermission("android.permission.ACCESS_LOCAL_NETWORK"))
        assertNull(knownPermission("android.permission.SOMETHING_NEW"))
    }

    @Test
    fun daemonTokensAreShownAsWords() {
        assertEquals("Reservation time", humanizeToken("reservation_time"))
        assertEquals("Follow-up", humanizeToken("follow-up"))
        assertEquals("Opus", humanizeToken("opus"))
    }

    private companion object {
        val DIRECT = BridgeState(
            link = LinkState.Up(
                kind = LinkKind.IROH,
                path = PathKind.DIRECT,
                address = "6c3a1f9b",
                rttMs = 41,
                sinceEpochMs = 0,
                reconnects = 0
            )
        )
    }
}

private class FakeBridgeStateRepository(state: BridgeState) : BridgeStateRepository {
    private val mutable = MutableStateFlow(state)
    override val state: StateFlow<BridgeState> = mutable
}

private class FakePairingRepository(pairedNow: Boolean) : PairingRepository {
    private val mutable = MutableStateFlow(pairedNow)
    override val paired: StateFlow<Boolean> = mutable
    var unpairCalls = 0

    override suspend fun pair(link: String): PairResult {
        mutable.value = true
        return PairResult.Paired
    }

    override suspend fun unpair() {
        unpairCalls++
        mutable.value = false
    }
}
