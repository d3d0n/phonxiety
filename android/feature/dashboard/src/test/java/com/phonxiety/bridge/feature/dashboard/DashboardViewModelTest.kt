package com.phonxiety.bridge.feature.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.PairResult
import com.phonxiety.bridge.data.PairingRepository
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
    fun uptimeIsReadableAtAGlance() {
        assertEquals("<1 m", formatUptime(1_000, 30_000))
        assertEquals("12 m", formatUptime(0, 12 * 60_000L))
        assertEquals("2 h 05 m", formatUptime(0, 125 * 60_000L))
        assertEquals("3 h", formatUptime(0, 180 * 60_000L))
    }

    @Test
    fun pathMappingNeverClaimsADirectPath() {
        assertEquals(com.phonxiety.bridge.designsystem.component.LinkPath.DIRECT, PathKind.DIRECT.toDesignPath())
        assertEquals(com.phonxiety.bridge.designsystem.component.LinkPath.RELAY, PathKind.RELAY.toDesignPath())
        assertEquals(com.phonxiety.bridge.designsystem.component.LinkPath.RELAY, PathKind.UNKNOWN.toDesignPath())
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
