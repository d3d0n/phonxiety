package com.phonxiety.bridge.feature.lab

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.BridgeConfigLoader
import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.PhonePreferences
import com.phonxiety.bridge.PhoneSettings
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import com.phonxiety.bridge.model.ConnectionInfo
import com.phonxiety.bridge.model.DaemonInfo
import com.phonxiety.bridge.model.LinkTests
import com.phonxiety.bridge.model.LoopbackResult
import com.phonxiety.bridge.model.ProbeCandidate
import com.phonxiety.bridge.model.ProbeResult
import com.phonxiety.bridge.model.TestCallRequest
import com.phonxiety.bridge.model.ToneResult
import com.phonxiety.bridge.model.VoiceControls
import com.phonxiety.bridge.net.LanPathProbe
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class LabViewModelTest {
    private lateinit var context: Context
    private lateinit var api: FakeDaemonApi
    private lateinit var prefs: FakePhonePreferences
    private lateinit var bridge: FakeBridgeStateRepository
    private lateinit var lanCheck: FakeLanPathCheck

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "lab").deleteRecursively()
        BridgeLog.clear()
        api = FakeDaemonApi()
        prefs = FakePhonePreferences()
        bridge = FakeBridgeStateRepository()
        lanCheck = FakeLanPathCheck()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): LabViewModel = LabViewModel(api, bridge, prefs, lanCheck, context)

    @Test
    fun `a finished link test is shown and kept across a new screen`() = runBlocking<Unit> {
        api.linkTestHandler = { _, codec, transport, _ ->
            ApiOutcome.Ok(200, healthyLinkTest(p95 = 96, codec = codec ?: "opus"))
        }
        val vm = viewModel()
        vm.setLinkSeconds(3f)
        vm.setLinkCodec("opus")
        vm.setLinkTransport("datagram")
        vm.runLinkTest()
        withTimeout(5_000) { vm.state.first { it.link.last != null } }

        val state = vm.state.value
        assertEquals(96L, state.link.last?.p95)
        assertEquals(1, state.link.history.size)
        assertFalse(state.link.running)

        // A fresh screen loads the same run from disk: the history outlives the ViewModel.
        val reopened = viewModel()
        withTimeout(5_000) { reopened.state.first { it.link.history.isNotEmpty() } }
        assertEquals(96L, reopened.state.value.link.history.first().p95)
    }

    @Test
    fun `loopback and a tone are refused until an owned call is active`() = runBlocking<Unit> {
        var loopbackCalls = 0
        api.loopbackHandler = { enabled, callId ->
            loopbackCalls += 1
            ApiOutcome.Ok(200, LoopbackResult(enabled = enabled, result = null))
        }
        val vm = viewModel()
        vm.setLoopback(true)
        assertEquals(0, loopbackCalls)
        assertFalse(vm.state.value.audioEnabled)
        assertNotNull(vm.state.value.audioDisabledReason)

        bridge.flow.value = BridgeState(call = CallState("call-1", "+1•••23", CallPhase.ACTIVE, 0L))
        withTimeout(5_000) { vm.state.first { it.callPhase == "active" } }
        assertTrue(vm.state.value.audioEnabled)
        vm.setLoopback(true)
        withTimeout(5_000) { vm.state.first { it.audio.loopbackOn } }
        assertEquals(1, loopbackCalls)
    }

    @Test
    fun `the test call only uses the configured number`() = runBlocking<Unit> {
        var requested: TestCallRequest? = null
        api.testCallHandler = { request ->
            requested = request
            ApiOutcome.Ok(200, com.phonxiety.bridge.model.CallSnapshot(callId = "test-call", profile = "test"))
        }
        val vm = viewModel()
        vm.requestTestCall()
        assertFalse(vm.state.value.testCall.confirming)

        vm.setTestNumber("+1 (202) 555-0123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }
        vm.requestTestCall()
        assertTrue(vm.state.value.testCall.confirming)
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.callId != null } }
        assertEquals("+12025550123", requested?.destination)
        assertEquals(30, requested?.maxDurationSeconds)
        assertEquals("+12025550123", prefs.settings.value.testNumber)
    }

    @Test
    fun `the codec trial keeps the richest healthy opus profile`() = runBlocking<Unit> {
        api.linkTestHandler = { _, codec, _, bitrate ->
            val poor = codec == "pcm" || (bitrate ?: 0) >= 64_000
            ApiOutcome.Ok(
                200,
                healthyLinkTest(p95 = if (poor) 700 else 90, loss = if (poor) 0.08 else 0.0, codec = codec ?: "opus")
                    .copy(healthy = !poor, verdict = if (poor) "poor" else "good")
            )
        }
        val vm = viewModel()
        vm.runCodecTrial()
        withTimeout(5_000) { vm.state.first { it.trial.recommendation != null } }
        val best = vm.state.value.trial.recommendation
        assertEquals(32_000, best?.bitrate)
        assertEquals(4, vm.state.value.trial.runs.size)

        vm.applyTrialRecommendation()
        withTimeout(5_000) { vm.state.first { prefs.settings.value.maxBitrate == 32_000 } }
        assertEquals("opus", prefs.settings.value.preferredCodec)
        assertEquals(32_000, prefs.settings.value.maxBitrate)
    }

    @Test
    fun `probe results replace the rows without losing the selection`() = runBlocking<Unit> {
        api.probeHandler = { candidates ->
            ApiOutcome.Ok(
                200,
                ProbeResult(
                    candidates = candidates.orEmpty().map { name ->
                        ProbeCandidate(name, available = true, reachable = true, rttMs = 12.0)
                    },
                    checkedAt = "2026-11-26T10:00:00"
                )
            )
        }
        val vm = viewModel()
        vm.toggleProbeCandidate("lan")
        vm.runProbe()
        withTimeout(5_000) { vm.state.first { it.probe.candidates.isNotEmpty() } }
        assertEquals(setOf("iroh", "tailscale"), vm.state.value.probe.selected)
        assertEquals(2, vm.state.value.probe.candidates.size)
        assertNull(vm.state.value.probe.error)
    }

    @Test
    fun `the lan row shows the phone's own probe, not the daemon's listener check alone`() = runBlocking<Unit> {
        val url = "wss://192.0.2.10:18763/device"
        val pin = "cc".repeat(32)
        api.probeHandler = {
            ApiOutcome.Ok(
                200,
                ProbeResult(
                    // The daemon, probed while the link is on iroh, could not see the phone's path.
                    candidates = listOf(ProbeCandidate("lan", available = true, reachable = false, reason = "no_listener_answer")),
                    checkedAt = "2026-11-26T10:00:00+00:00"
                )
            )
        }
        api.infoHandler = { ApiOutcome.Ok(200, DaemonInfo(connection = ConnectionInfo(lan = listOf(url), certificate = pin))) }
        BridgeConfigLoader.write(context, BridgeConfig.Address(url, "dev-token", pin))
        lanCheck.results = { urls -> urls.map { LanPathProbe(it, reachable = true, rttMs = 9) } }
        val vm = viewModel()
        vm.toggleProbeCandidate("iroh")
        vm.toggleProbeCandidate("tailscale")
        vm.runProbe()
        withTimeout(5_000) { vm.state.first { it.probe.candidates.isNotEmpty() } }
        val row = vm.state.value.probe.candidates.single()
        assertTrue(row.reachable)
        assertEquals(9.0, row.rttMs)
        assertEquals(url, row.address)
        assertNull(row.reason)
        assertTrue(row.detail.orEmpty().contains("this phone: tls+auth to $url in 9 ms"))
        assertEquals(listOf(url), lanCheck.calls.single().first)
        assertEquals(pin, lanCheck.calls.single().second)
        assertEquals("dev-token", lanCheck.calls.single().third)
    }

    @Test
    fun `a phone that cannot reach the listener says so while the daemon still answers`() = runBlocking<Unit> {
        val url = "wss://192.0.2.10:18763/device"
        val pin = "cc".repeat(32)
        api.probeHandler = {
            ApiOutcome.Ok(
                200,
                ProbeResult(
                    candidates = listOf(
                        ProbeCandidate("lan", available = true, reachable = true, rttMs = 4.0, detail = "the pinned listener answers on this host's LAN address")
                    ),
                    checkedAt = "2026-11-26T10:00:00+00:00"
                )
            )
        }
        api.infoHandler = { ApiOutcome.Ok(200, DaemonInfo(connection = ConnectionInfo(lan = listOf(url), certificate = pin))) }
        BridgeConfigLoader.write(context, BridgeConfig.Address(url, "dev-token", pin))
        lanCheck.results = { urls -> urls.map { LanPathProbe(it, reachable = false, rttMs = null) } }
        val vm = viewModel()
        vm.toggleProbeCandidate("iroh")
        vm.toggleProbeCandidate("tailscale")
        vm.runProbe()
        withTimeout(5_000) { vm.state.first { it.probe.candidates.isNotEmpty() } }
        val row = vm.state.value.probe.candidates.single()
        assertFalse(row.reachable)
        assertNull(row.rttMs)
        assertEquals("unreachable_from_phone", row.reason)
        assertTrue(row.detail.orEmpty().contains("this phone could not reach $url"))
        assertTrue(row.detail.orEmpty().contains("the daemon's listener answers on its own LAN address"))
    }

    @Test
    fun `without a LAN address the daemon's row is kept and says why`() = runBlocking<Unit> {
        api.probeHandler = {
            ApiOutcome.Ok(
                200,
                ProbeResult(
                    candidates = listOf(ProbeCandidate("lan", available = false, reachable = false, reason = "no_lan_address")),
                    checkedAt = "2026-11-26T10:00:00+00:00"
                )
            )
        }
        api.infoHandler = { ApiOutcome.Ok(200, DaemonInfo(connection = ConnectionInfo())) }
        val vm = viewModel()
        vm.toggleProbeCandidate("iroh")
        vm.toggleProbeCandidate("tailscale")
        vm.runProbe()
        withTimeout(5_000) { vm.state.first { it.probe.candidates.isNotEmpty() } }
        val row = vm.state.value.probe.candidates.single()
        assertFalse(row.reachable)
        assertTrue(row.detail.orEmpty().contains("the daemon reports no LAN address"))
        assertTrue(lanCheck.calls.isEmpty())
    }

    @Test
    fun `a daemon-reachable listener is not shown as reachable when the phone cannot check it`() = runBlocking<Unit> {
        api.probeHandler = {
            ApiOutcome.Ok(
                200,
                ProbeResult(
                    candidates = listOf(ProbeCandidate("lan", available = true, reachable = true, rttMs = 3.0)),
                    checkedAt = "2026-11-26T10:00:00+00:00"
                )
            )
        }
        api.infoHandler = { ApiOutcome.Failure(503, "link_down", "no link") }
        val vm = viewModel()
        vm.toggleProbeCandidate("iroh")
        vm.toggleProbeCandidate("tailscale")
        vm.runProbe()
        withTimeout(5_000) { vm.state.first { it.probe.candidates.isNotEmpty() } }
        val row = vm.state.value.probe.candidates.single()
        assertFalse(row.reachable)
        assertEquals(null, row.rttMs)
        assertEquals("inconclusive", row.reason)
        assertTrue(lanCheck.calls.isEmpty())
    }

    @Test
    fun `diagnostics text renders and the log view honours the filter`() = runBlocking<Unit> {
        api.diagnosticsHandler = {
            ApiOutcome.Ok(
                200,
                kotlinx.serialization.json.Json.parseToJsonElement("""{"daemon":{"version":"0.3.0"}}""")
                    as kotlinx.serialization.json.JsonObject
            )
        }
        val vm = viewModel()
        vm.loadDiagnostics()
        withTimeout(5_000) { vm.state.first { it.diagnostics.payload != null } }
        assertTrue(vm.state.value.diagnostics.payload.orEmpty().contains("\"version\": \"0.3.0\""))

        BridgeLog.i("PhonxietyBridge", "link up")
        BridgeLog.w("PhonxietyBridge", "heartbeat late")
        vm.setLogFilter("heartbeat")
        withTimeout(5_000) { vm.state.first { it.logs.lines.isNotEmpty() } }
        assertEquals(1, vm.state.value.logs.lines.size)
        vm.setLogFilter("")
        assertTrue(vm.state.value.logs.lines.size >= 2)
    }

    @Test
    fun `loopback support comes from the daemon info`() = runBlocking<Unit> {
        api.infoHandler = { ApiOutcome.Ok(200, DaemonInfo(voiceControls = VoiceControls(loopback = false, tone = true))) }
        val vm = viewModel()
        withTimeout(5_000) { vm.state.first { it.loopbackSupported != null } }
        assertEquals(false, vm.state.value.loopbackSupported)
        assertEquals(true, vm.state.value.toneSupported)
        // Link tests are not blocked by the audio capability.
        api.linkTestsHandler = { ApiOutcome.Ok(200, LinkTests()) }
        vm.runLinkTest()
        withTimeout(5_000) { vm.state.first { it.link.error != null } }
    }
}

class FakeBridgeStateRepository(initial: BridgeState = BridgeState()) : BridgeStateRepository {
    val flow = MutableStateFlow(initial)
    override val state: StateFlow<BridgeState> = flow
}

class FakeLanPathCheck : LanPathCheck {
    val calls = mutableListOf<Triple<List<String>, String?, String>>()
    var results: (List<String>) -> List<LanPathProbe> = { urls -> urls.map { LanPathProbe(it, reachable = true, rttMs = 5) } }

    override fun probe(urls: List<String>, certSha256: String?, token: String): List<LanPathProbe> {
        calls += Triple(urls, certSha256, token)
        return results(urls)
    }
}

class FakePhonePreferences(initial: PhoneSettings = PhoneSettings()) : PhonePreferences {
    override val settings = MutableStateFlow(initial)

    override suspend fun update(block: (PhoneSettings) -> PhoneSettings): PhoneSettings {
        val next = block(settings.value)
        settings.value = next
        return next
    }
}
