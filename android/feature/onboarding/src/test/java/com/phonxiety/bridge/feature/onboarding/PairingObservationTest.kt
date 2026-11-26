package com.phonxiety.bridge.feature.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.BridgeConfigLoader
import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.DevicePairingRepository
import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.RootDetection
import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.SuStatus
import com.phonxiety.bridge.root.WizardState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stored pairing is observed, not polled on resume: a config that appears while the wizard is
 * showing "Unpaired" (`mise run phone:pair` writes it behind the app's back) advances the wizard by
 * itself, and the resulting first link test waits for the transport instead of failing while the
 * link is still connecting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingObservationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        BridgeConfigLoader.delete(context)
        SetupPreferences(context).reset()
        setupProgressStore(context).clear()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aStoredPairingIsPickedUpWithoutAResumeAndWaitsForTheLink() = runBlocking {
        val provider = DetectedProvider(
            id = RootId.MAGISK,
            version = "31.1",
            managerPackage = "com.topjohnwu.magisk",
            managerInstalled = true
        )
        val backend = FakeBackend().apply {
            detection = RootDetection(SuStatus.GRANTED, listOf(provider))
            module = ModuleState.Enabled("0.3.0", 3)
        }
        val appApi = FakeAppApi().apply {
            linkTest = ApiResult.Success(200, buildJsonObject { put("healthy", true) })
        }
        val pairing = DevicePairingRepository(context)
        val link = connectingLink()
        val viewModel = OnboardingViewModel(context, pairing, appApi, backend, FakePermissions(), link)

        assertEquals(
            WizardState.NeedsPairing(provider),
            viewModel.wizard.awaitState { it is WizardState.NeedsPairing }
        )

        // The host task writes the config while the wizard is in the foreground; no resume event.
        BridgeConfigLoader.write(
            context,
            BridgeConfig.Address("wss://192.168.1.10:18763/device", token = "device-token", certSha256 = null)
        )
        pairing.refresh()

        assertEquals(
            WizardState.FirstLinkTest(provider),
            viewModel.wizard.awaitState { it is WizardState.FirstLinkTest }
        )
        // Still connecting: no request is sent, so nothing looks like a failed test.
        assertTrue(appApi.requestedPaths.isEmpty())

        link.value = readyLink().value

        assertEquals(WizardState.Ready, viewModel.wizard.awaitState { it == WizardState.Ready })
        assertEquals(1, appApi.requestedPaths.count { it == "/app/v1/link-test" })
    }

    private suspend fun <T> StateFlow<T>.awaitState(predicate: (T) -> Boolean): T = withTimeout(20_000) { first(predicate) }
}
