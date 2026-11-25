package com.phonxiety.bridge.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.PhoneCodec
import com.phonxiety.bridge.PhonePreferences
import com.phonxiety.bridge.PhoneSettings
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.DeviceState
import com.phonxiety.bridge.model.LinkTestFrames
import com.phonxiety.bridge.model.LinkTestResult
import com.phonxiety.bridge.model.LinkTests
import com.phonxiety.bridge.model.SettingsSnapshot
import com.phonxiety.bridge.model.SettingsUpdate
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsViewModelTest {
    private lateinit var context: Context
    private lateinit var api: FakeDaemonApi
    private lateinit var prefs: FakePhonePreferences
    private lateinit var bridge: FakeBridgeStateRepository
    private val json = Json { encodeDefaults = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "settings").deleteRecursively()
        api = FakeDaemonApi()
        prefs = FakePhonePreferences()
        bridge = FakeBridgeStateRepository()
        api.settingsHandler = { ApiOutcome.Ok(200, snapshot(DaemonSettingsValues())) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): SettingsViewModel = SettingsViewModel(api, bridge, prefs, context)

    private fun snapshot(values: DaemonSettingsValues): SettingsSnapshot = SettingsSnapshot(
        schema = 1,
        values = json.encodeToJsonElement(DaemonSettingsValues.serializer(), values).jsonObject,
        restartRequiredFields = listOf("language_hint", "codex_task_model")
    )

    private fun ok(values: DaemonSettingsValues, changed: List<String>): SettingsUpdate = SettingsUpdate(
        values = json.encodeToJsonElement(DaemonSettingsValues.serializer(), values).jsonObject,
        changed = changed,
        restartRequired = changed.any { it == "language_hint" },
        restartRequiredFields = changed.filter { it == "language_hint" }
    )

    @Test
    fun `the daemon form loads from the daemon and saves only the edited keys`() = runBlocking<Unit> {
        api.settingsHandler = {
            ApiOutcome.Ok(
                200,
                snapshot(
                    DaemonSettingsValues(
                        codexVoice = "sol",
                        ringTimeout = 60,
                        defaultResultFields = listOf("heard"),
                        irohRelays = listOf("https://relay.example")
                    )
                )
            )
        }
        api.putSettingsHandler = { body ->
            val merged = DaemonSettingsValues(ringTimeout = body["ring_timeout_s"]?.toString()?.toInt() ?: 60)
            ApiOutcome.Ok(200, ok(merged, body.keys.toList()))
        }
        val vm = viewModel()
        withTimeout(5_000) { vm.state.first { !it.daemon.loading } }
        assertEquals("sol", vm.state.value.daemon.form.codexVoice)
        assertEquals("heard", vm.state.value.daemon.form.defaultResultFields)
        assertFalse(vm.state.value.daemon.dirty)

        vm.updateForm { it.copy(ringTimeout = "45") }
        assertTrue(vm.state.value.daemon.dirty)
        vm.saveDaemonSettings()
        withTimeout(5_000) { vm.state.first { it.daemon.values.ringTimeout == 45 } }
        assertEquals(setOf("ring_timeout_s"), api.putSettingsBodies.last().keys)
        assertEquals(45, vm.state.value.daemon.values.ringTimeout)
        assertEquals(emptyList<String>(), vm.state.value.daemon.restartRequiredFields)
    }

    @Test
    fun `phone preferences are written where the engine reads them`() = runBlocking<Unit> {
        val vm = viewModel()
        vm.setCodec("opus")
        vm.setMaxBitrate(48_000)
        vm.setWifiLowLatency(true)
        vm.setLogLevel("debug")
        withTimeout(5_000) { vm.state.first { it.phone.logLevel == "debug" } }
        assertEquals(PhoneCodec.OPUS, prefs.settings.value.preferredCodec)
        assertEquals(48_000, prefs.settings.value.maxBitrate)
        assertTrue(prefs.settings.value.wifiLowLatency)
        assertEquals("debug", prefs.settings.value.logLevel)
    }

    @Test
    fun `auto-tune applies measured changes, logs them and reverts them all`() = runBlocking<Unit> {
        prefs.settings.value = PhoneSettings(
            preferredCodec = PhoneCodec.PCM,
            maxBitrate = 32_000,
            wifiLowLatency = true,
            autoTuneEnabled = true
        )
        bridge.flow.value = BridgeState(device = DeviceState(thermal = "SEVERE", batteryPercent = 80, charging = true))
        api.linkTestsHandler = {
            ApiOutcome.Ok(
                200,
                LinkTests(
                    tests = listOf(
                        LinkTestResult(
                            codec = "opus",
                            p50 = 210,
                            p95 = 512,
                            max = 610,
                            loss = 0.04,
                            frames = LinkTestFrames(sent = 150, echoed = 144),
                            throughputBps = 24_000,
                            healthy = false,
                            verdict = "poor"
                        )
                    )
                )
            )
        }
        val vm = viewModel()
        withTimeout(5_000) { vm.state.first { !it.daemon.loading } }
        vm.setAutoTuneEnabled(true)
        vm.evaluateNow()
        withTimeout(5_000) { vm.state.first { it.autoTune.changes.size >= 3 } }

        assertEquals("opus", prefs.settings.value.preferredCodec)
        assertEquals(16_000, prefs.settings.value.maxBitrate)
        assertFalse(prefs.settings.value.wifiLowLatency)
        val entries = vm.state.value.autoTune.changes
        assertTrue(entries.all { it.reason.isNotBlank() })
        assertTrue(entries.any { it.key == TuneKey.WIFI_LOW_LATENCY.key })

        // A second evaluation over the same evidence must not double-log anything.
        vm.evaluateNow()
        withTimeout(5_000) { vm.state.first { it.autoTune.lastEvaluatedAtMs != null } }
        assertEquals(entries.size, vm.state.value.autoTune.changes.size)

        vm.revertEverything()
        withTimeout(5_000) { vm.state.first { state -> state.autoTune.changes.all { it.reverted } } }
        assertEquals("pcm", prefs.settings.value.preferredCodec)
        assertEquals(32_000, prefs.settings.value.maxBitrate)
        assertTrue(prefs.settings.value.wifiLowLatency)
    }

    @Test
    fun `auto-tune off means no evaluation`() = runBlocking<Unit> {
        prefs.settings.value = PhoneSettings(wifiLowLatency = true, autoTuneEnabled = false)
        bridge.flow.value = BridgeState(device = DeviceState(thermal = "SEVERE"))
        api.linkTestsHandler = { ApiOutcome.Ok(200, LinkTests()) }
        val vm = viewModel()
        withTimeout(5_000) { vm.state.first { !it.daemon.loading } }
        vm.evaluateNow()
        kotlinx.coroutines.delay(200)
        assertTrue(vm.state.value.autoTune.changes.isEmpty())
        assertNull(vm.state.value.autoTune.lastEvaluatedAtMs)
        assertTrue(prefs.settings.value.wifiLowLatency)
    }
}

class FakeBridgeStateRepository(initial: BridgeState = BridgeState()) : BridgeStateRepository {
    val flow = MutableStateFlow(initial)
    override val state: StateFlow<BridgeState> = flow
}

class FakePhonePreferences(initial: PhoneSettings = PhoneSettings()) : PhonePreferences {
    override val settings = MutableStateFlow(initial)

    override suspend fun update(block: (PhoneSettings) -> PhoneSettings): PhoneSettings {
        val next = block(settings.value)
        settings.value = next
        return next
    }
}
