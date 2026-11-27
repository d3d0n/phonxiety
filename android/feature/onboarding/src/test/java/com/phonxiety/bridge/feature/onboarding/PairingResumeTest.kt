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
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A config written while the app runs reaches the wizard on the production resume path. The host
 * task (`mise run phone:pair`) and the service write `config.json` behind the app's back, and the
 * pairing repository serves a cache, so returning to the app refreshes it on IO and re-probes
 * instead of trusting what the first probe saw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingResumeTest {
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
    fun aConfigWrittenWhileTheAppRunsIsSeenOnReturnToTheApp() = runBlocking {
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
        val viewModel = OnboardingViewModel(
            context,
            DevicePairingRepository(context, Dispatchers.IO),
            appApi,
            backend,
            FakePermissions(),
            readyLink()
        )

        assertEquals(
            WizardState.NeedsPairing(provider),
            viewModel.wizard.awaitState { it is WizardState.NeedsPairing }
        )

        // `mise run phone:pair` writes the config while the wizard is showing "Unpaired".
        BridgeConfigLoader.write(
            context,
            BridgeConfig.Address("wss://192.168.1.10:18763/device", token = "device-token", certSha256 = null)
        )

        viewModel.onReturnedToForeground()

        assertEquals(WizardState.Ready, viewModel.wizard.awaitState { it == WizardState.Ready })
    }

    /**
     * The resume reads "Unpaired" on the caller's thread and the coroutine it launches starts
     * later. An observation that lands in that gap has already answered the resume, so the queued
     * probe must stay quiet: the wizard settles once, with one first link test and one "Finished".
     */
    @Test
    fun anObservationThatLandsBeforeTheResumeCoroutineStartsIsTheOnlyProbe() = runBlocking {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
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
        val repository = DevicePairingRepository(context, Dispatchers.IO)
        val viewModel = OnboardingViewModel(
            context,
            repository,
            appApi,
            backend,
            FakePermissions(),
            readyLink()
        )
        val hosts = Collections.synchronizedList(mutableListOf<SetupHostRequest>())
        val requests = launch(Dispatchers.IO) { viewModel.requests.collect { hosts += it } }
        try {
            awaitWizard(viewModel) { it is WizardState.NeedsPairing }

            BridgeConfigLoader.write(
                context,
                BridgeConfig.Address("wss://192.168.1.10:18763/device", token = "device-token", certSha256 = null)
            )
            // The main dispatcher is held: the resume coroutine is queued, not started, and the
            // observation below answers the resume before it ever runs.
            viewModel.onReturnedToForeground()

            repository.refresh()
            awaitWizard(viewModel) { it == WizardState.Ready }
            awaitUntil("the settled wizard finishes once") { hosts.count { it == SetupHostRequest.Finished } == 1 }

            // Now the queued probe runs; it must find the wizard settled and add nothing.
            runQueued(main)
            assertEquals(WizardState.Ready, viewModel.wizard.value)
            assertEquals(1, hosts.count { it == SetupHostRequest.Finished })
            assertEquals(1, appApi.requestedPaths.count { it == "/app/v1/link-test" })
        } finally {
            requests.cancel()
        }
    }

    /** Polls the wizard without touching the paused main dispatcher, so the gap stays open. */
    private fun awaitWizard(viewModel: OnboardingViewModel, predicate: (WizardState) -> Boolean) {
        awaitUntil("the wizard is ${viewModel.wizard.value}") { predicate(viewModel.wizard.value) }
    }

    private fun awaitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) fail(message)
            Thread.sleep(5)
        }
    }

    /** Runs the coroutines queued on the held main dispatcher, including their hops to IO. */
    private fun runQueued(dispatcher: TestDispatcher) {
        repeat(20) {
            dispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
        }
    }

    private suspend fun <T> StateFlow<T>.awaitState(predicate: (T) -> Boolean): T = withTimeout(20_000) { first(predicate) }
}
