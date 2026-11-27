package com.phonxiety.bridge.feature.lab

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.BridgeConfigLoader
import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.PhonePreferences
import com.phonxiety.bridge.PhoneSettings
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.CallEventsSource
import com.phonxiety.bridge.data.LiveCallFeed
import com.phonxiety.bridge.data.LiveCallFront
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.model.AppGateway
import com.phonxiety.bridge.model.AttachmentHeader
import com.phonxiety.bridge.model.AttachmentOutcome
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallEvent
import com.phonxiety.bridge.model.CallEventsPage
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.CallState
import com.phonxiety.bridge.model.ConnectionInfo
import com.phonxiety.bridge.model.DaemonInfo
import com.phonxiety.bridge.model.DeviceApiRequest
import com.phonxiety.bridge.model.DeviceApiResult
import com.phonxiety.bridge.model.LinkTests
import com.phonxiety.bridge.model.LoopbackResult
import com.phonxiety.bridge.model.PairingGate
import com.phonxiety.bridge.model.ProbeCandidate
import com.phonxiety.bridge.model.ProbeResult
import com.phonxiety.bridge.model.TestCallRequest
import com.phonxiety.bridge.model.TestCallTone
import com.phonxiety.bridge.model.ToneResult
import com.phonxiety.bridge.model.VoiceControls
import com.phonxiety.bridge.net.CandidateMerge
import com.phonxiety.bridge.net.LanPathProbe
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
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
    /**
     * A controlled scheduler: virtual time and queued dispatch make every step deterministic, and
     * no test waits on a wall-clock moment for a transient state.
     */
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    /** Every view model this test made, cancelled when it ends. */
    private val viewModels = mutableListOf<LabViewModel>()

    /** The feed's own scopes: its loop outlives a screen, so the test owns and cancels them. */
    private val feedScopes = mutableListOf<CoroutineScope>()

    private lateinit var context: Context
    private lateinit var api: FakeDaemonApi
    private lateinit var prefs: FakePhonePreferences
    private lateinit var bridge: FakeBridgeStateRepository
    private lateinit var lanCheck: FakeLanPathCheck

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "lab").deleteRecursively()
        BridgeLog.clear()
        api = FakeDaemonApi()
        prefs = FakePhonePreferences()
        bridge = FakeBridgeStateRepository()
        lanCheck = FakeLanPathCheck()
        PairingGate.resetForTest()
        LiveCallFront.resetForTest()
    }

    @After
    fun tearDown() {
        // No read, collector or retry outlives the test that started it.
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        feedScopes.forEach { it.cancel() }
        feedScopes.clear()
        PairingGate.resetForTest()
        Dispatchers.resetMain()
    }

    /** Stores a pairing and makes it the one the gate expects, as a running service does. */
    private fun pair(config: BridgeConfig) {
        val stored = BridgeConfigLoader.write(context, config)
        PairingGate.expect(CandidateMerge.identity(stored))
    }

    /** The shared call feed of this test's daemon, on the test clock so its delays are virtual. */
    private fun TestScope.feed(): LiveCallFeed {
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        feedScopes += scope
        return LiveCallFeed(
            CallEventsSource { callId, since, waitSeconds -> api.events(callId, since, waitSeconds) },
            scope
        )
    }

    private fun TestScope.viewModel(): LabViewModel = LabViewModel(api, bridge, prefs, lanCheck, context, feed()).apply {
        // The record of a placed call is read on a one-second cadence in the app; tests do not wait.
        resultRetryDelayMs = 1
        viewModels += this
    }

    /**
     * Waits for a state the production code reaches through `Dispatchers.IO` (the link history
     * file, the LAN handshake). Those run on real threads, which a virtual clock does not drive,
     * so this one wait runs on real time; every other wait in this class is virtual.
     */
    private suspend fun LabViewModel.awaitIoState(predicate: (LabUiState) -> Boolean): LabUiState = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5_000) { state.first(predicate) }
    }

    @Test
    fun `a finished link test is shown and kept across a new screen`() = runTest(testDispatcher) {
        api.linkTestHandler = { _, codec, transport, _ ->
            ApiOutcome.Ok(200, healthyLinkTest(p95 = 96, codec = codec ?: "opus"))
        }
        val vm = viewModel()
        vm.setLinkSeconds(3f)
        vm.setLinkCodec("opus")
        vm.setLinkTransport("datagram")
        vm.runLinkTest()
        vm.awaitIoState { it.link.last != null }

        val state = vm.state.value
        assertEquals(96L, state.link.last?.p95)
        assertEquals(1, state.link.history.size)
        assertFalse(state.link.running)

        // A fresh screen loads the same run from disk: the history outlives the ViewModel.
        val reopened = viewModel()
        reopened.awaitIoState { it.link.history.isNotEmpty() }
        assertEquals(96L, reopened.state.value.link.history.first().p95)
    }

    @Test
    fun `loopback and a tone are refused until an owned call is active`() = runTest(testDispatcher) {
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
    fun `a failed loopback is retried as the same loopback and never as a tone`() = runTest(testDispatcher) {
        val requested = mutableListOf<Boolean>()
        var tones = 0
        api.loopbackHandler = { enabled, _ ->
            requested += enabled
            if (requested.size == 1) {
                ApiOutcome.Failure(503, "link_down", "the daemon is unreachable")
            } else {
                ApiOutcome.Ok(200, LoopbackResult(enabled = enabled, result = null))
            }
        }
        api.toneHandler = { _, _, _, _ ->
            tones += 1
            ApiOutcome.Ok(200, ToneResult(started = true))
        }
        val vm = viewModel()
        bridge.flow.value = BridgeState(call = CallState("call-1", "+1•••23", CallPhase.ACTIVE, 0L))
        withTimeout(5_000) { vm.state.first { it.callPhase == "active" } }

        vm.setLoopback(true)
        withTimeout(5_000) { vm.state.first { it.audio.error != null } }
        assertEquals(listOf(true), requested)
        assertEquals(AudioOp.LOOPBACK, vm.state.value.audio.failedOp)

        vm.retryAudio()
        withTimeout(5_000) { vm.state.first { it.audio.loopbackOn } }

        assertEquals(listOf(true, true), requested)
        assertEquals("a failed loopback is not a tone", 0, tones)
    }

    @Test
    fun `a loopback failure and its retry end with the call they belong to`() = runTest(testDispatcher) {
        api.loopbackHandler = { _, _ -> ApiOutcome.Failure(503, "link_down", "the daemon is unreachable") }
        val vm = viewModel()
        bridge.flow.value = BridgeState(call = CallState("call-1", "+1•••23", CallPhase.ACTIVE, 0L))
        withTimeout(5_000) { vm.state.first { it.callPhase == "active" } }
        vm.setLoopback(true)
        withTimeout(5_000) { vm.state.first { it.audio.error != null } }

        bridge.flow.value = BridgeState(call = CallState("call-1", "+1•••23", CallPhase.ENDED, 0L))
        withTimeout(5_000) { vm.state.first { it.callPhase == "ended" } }

        val audio = vm.state.value.audio
        assertNull("the failure went with its call", audio.error)
        assertNull("and so did its Retry", audio.failedOp)
    }

    @Test
    fun `a failure from the previous call offers no retry on the next one`() = runTest(testDispatcher) {
        val requests = mutableListOf<Pair<Boolean, String?>>()
        api.loopbackHandler = { enabled, callId ->
            requests += enabled to callId?.value
            if (requests.size == 1) {
                ApiOutcome.Failure(503, "link_down", "the daemon is unreachable")
            } else {
                ApiOutcome.Ok(200, LoopbackResult(enabled = enabled, result = null))
            }
        }
        val vm = viewModel()
        bridge.flow.value = BridgeState(call = CallState("call-1", "+1•••23", CallPhase.ACTIVE, 0L))
        withTimeout(5_000) { vm.state.first { it.callPhase == "active" } }
        vm.setLoopback(true)
        withTimeout(5_000) { vm.state.first { it.audio.error != null } }

        bridge.flow.value = BridgeState(call = CallState("call-2", "+1•••23", CallPhase.ACTIVE, 0L))
        withTimeout(5_000) { vm.state.first { it.callId == "call-2" } }

        assertNull("B is offered no Retry for A's failure", vm.state.value.audio.error)
        assertNull(vm.state.value.audio.failedOp)
        vm.retryAudio()
        advanceUntilIdle()
        assertEquals("nothing is sent to B", listOf(true to "call-1"), requests)
    }

    @Test
    fun `a late answer to the previous call changes nothing on the next one`() = runTest(testDispatcher) {
        val answer = CompletableDeferred<ApiOutcome<LoopbackResult>>()
        api.loopbackHandler = { _, _ -> answer.await() }
        val vm = viewModel()
        bridge.flow.value = BridgeState(call = CallState("call-1", "+1•••23", CallPhase.ACTIVE, 0L))
        withTimeout(5_000) { vm.state.first { it.callPhase == "active" } }
        vm.setLoopback(true)
        runCurrent()
        assertTrue(vm.state.value.audio.busy)

        bridge.flow.value = BridgeState(call = CallState("call-2", "+1•••23", CallPhase.ACTIVE, 0L))
        withTimeout(5_000) { vm.state.first { it.callId == "call-2" } }

        answer.complete(ApiOutcome.Ok(200, LoopbackResult(enabled = true, result = null)))
        withTimeout(5_000) { vm.state.first { !it.audio.busy } }

        val audio = vm.state.value.audio
        assertNull(audio.error)
        assertFalse("A's loopback is not B's", audio.loopbackOn)
        assertNull("A's answer is not B's note", audio.note)
    }

    @Test
    fun `a late failure of the previous call is not shown on the next one`() = runTest(testDispatcher) {
        val answer = CompletableDeferred<ApiOutcome<ToneResult>>()
        api.toneHandler = { _, _, _, _ -> answer.await() }
        val vm = viewModel()
        bridge.flow.value = BridgeState(call = CallState("call-1", "+1•••23", CallPhase.ACTIVE, 0L))
        withTimeout(5_000) { vm.state.first { it.callPhase == "active" } }
        vm.playTone()
        runCurrent()

        bridge.flow.value = BridgeState(call = CallState("call-2", "+1•••23", CallPhase.ACTIVE, 0L))
        withTimeout(5_000) { vm.state.first { it.callId == "call-2" } }

        answer.complete(ApiOutcome.Failure(503, "link_down", "the daemon is unreachable"))
        withTimeout(5_000) { vm.state.first { !it.audio.busy } }

        assertNull("A's failure is not B's", vm.state.value.audio.error)
        assertNull(vm.state.value.audio.failedOp)
    }

    @Test
    fun `a failed tone is retried with its own frequency and level`() = runTest(testDispatcher) {
        val tones = mutableListOf<Triple<Int?, Double?, String?>>()
        api.toneHandler = { hz, dbfs, _, callId ->
            tones += Triple(hz, dbfs, callId?.value)
            if (tones.size == 1) {
                ApiOutcome.Failure(503, "link_down", "the daemon is unreachable")
            } else {
                ApiOutcome.Ok(200, ToneResult(started = true))
            }
        }
        val vm = viewModel()
        bridge.flow.value = BridgeState(call = CallState("call-1", "+1•••23", CallPhase.ACTIVE, 0L))
        withTimeout(5_000) { vm.state.first { it.callPhase == "active" } }

        vm.setToneHz(700)
        vm.setToneDbfs(-12.0)
        vm.playTone()
        withTimeout(5_000) { vm.state.first { it.audio.error != null } }
        assertEquals(AudioOp.TONE, vm.state.value.audio.failedOp)

        // The sliders move after the failure: Retry still replays the tone that failed.
        vm.setToneHz(3_000)
        vm.setToneDbfs(-30.0)
        vm.retryAudio()
        withTimeout(5_000) { vm.state.first { it.audio.note != null } }

        val expected = listOf<Triple<Int?, Double?, String?>>(
            Triple(700, -12.0, "call-1"),
            Triple(700, -12.0, "call-1")
        )
        assertEquals(expected, tones)
    }

    @Test
    fun `a failed bundle export is retried as an export and not a read`() = runTest(testDispatcher) {
        var exports = 0
        var reads = 0
        api.diagnosticsHandler = {
            reads += 1
            ApiOutcome.Ok(200, kotlinx.serialization.json.Json.parseToJsonElement("{}") as kotlinx.serialization.json.JsonObject)
        }
        api.bundleHandler = {
            exports += 1
            ApiOutcome.Failure(503, "link_down", "the daemon is unreachable")
        }
        val vm = viewModel()

        vm.exportBundle()
        withTimeout(5_000) { vm.state.first { it.diagnostics.error != null } }
        assertEquals(DiagnosticsOp.EXPORT, vm.state.value.diagnostics.errorOp)
        assertEquals(1, exports)

        vm.retryDiagnostics()
        advanceUntilIdle()

        assertEquals(2, exports)
        assertEquals("a failed export is not a read", 0, reads)
    }

    @Test
    fun `after the call ends the audio card asks for a call again`() = runTest(testDispatcher) {
        val vm = viewModel()
        bridge.flow.value = BridgeState(call = CallState("call-1", "+1•••23", CallPhase.ACTIVE, 0L))
        withTimeout(5_000) { vm.state.first { it.callPhase == "active" } }
        assertTrue(vm.state.value.audioEnabled)
        assertNull(vm.state.value.audioDisabledReason)

        // The bridge keeps the call it last saw. That ended record is no call to run audio inside,
        // so the card goes back to asking for a call, not for an active one.
        bridge.flow.value = BridgeState(call = CallState("call-1", "+1•••23", CallPhase.ENDED, 0L))
        withTimeout(5_000) { vm.state.first { it.callPhase == "ended" } }
        assertFalse(vm.state.value.audioEnabled)
        assertEquals(UiText.Res(R.string.lab_audio_reason_no_call), vm.state.value.audioDisabledReason)
    }

    @Test
    fun `the test call only uses the configured number`() = runTest(testDispatcher) {
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

    /**
     * A9, single consumption: the open question is the permission to dial. A confirm with none open
     * places nothing, and however many taps the button gets while a placement runs, one call goes
     * out — the question is taken out of the state in the same swap that marks the placement.
     */
    @Test
    fun `a test call is placed once and a stale confirm places nothing`() = runTest(testDispatcher) {
        var placed = 0
        api.testCallHandler = {
            placed += 1
            ApiOutcome.Ok(200, com.phonxiety.bridge.model.CallSnapshot(callId = "test-call-$placed", profile = "test"))
        }
        val vm = viewModel()
        vm.setTestNumber("+1 (202) 555-0123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }

        vm.confirmTestCall()
        runCurrent()
        assertEquals("no question, no call", 0, placed)

        vm.requestTestCall()
        vm.confirmTestCall()
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.callId != null } }
        assertEquals("the double tap placed one call", 1, placed)

        vm.confirmTestCall()
        runCurrent()
        assertEquals("a later tap is not a question", 1, placed)
    }

    @Test
    fun `a slow store never replays an older number over a newer keystroke`() = runTest(testDispatcher) {
        // Every write is held until the test releases it, so an older value can land at an exact
        // moment: while the next, newer write is still in flight.
        val store = GatedPhonePreferences()
        prefs = store
        val vm = viewModel()

        vm.setTestNumber("+99890123456")
        assertEquals("the first write is the one the store took", "+99890123456", store.awaitWrite())

        vm.setTestNumber("+998901234567")
        // Release the older write: it lands now, and the newer one starts behind it.
        store.releaseOne()
        assertEquals("the newer write starts when the older one lands", "+998901234567", store.awaitWrite())
        assertEquals("the older value is what the store now holds", "+99890123456", store.settings.value.testNumber)
        assertEquals(
            "the draft is the newest keystroke, not the emitted older value",
            "+998901234567",
            vm.state.value.testCall.number
        )

        store.releaseOne()
        runCurrent()
        assertEquals(listOf("+99890123456", "+998901234567"), store.written)
        assertEquals("the stored number is the typed one", "+998901234567", store.settings.value.testNumber)
        assertEquals("the draft survives the newest write", "+998901234567", vm.state.value.testCall.number)

        // The number that reaches the daemon is the one that was typed, to the digit.
        var dialed: String? = null
        api.testCallHandler = { request ->
            dialed = request.destination
            ApiOutcome.Ok(200, CallSnapshot(callId = "test-call", profile = "test"))
        }
        vm.requestTestCall()
        vm.confirmTestCall()
        advanceUntilIdle()
        assertEquals("+998901234567", dialed)
    }

    @Test
    fun `typing a plus before the digits keeps the whole number`() = runTest(testDispatcher) {
        val store = FakePhonePreferences()
        prefs = store
        val vm = viewModel()

        // A lone `+` is what the first key of an international number produces; it stays.
        vm.setTestNumber("+")
        assertEquals("+", vm.state.value.testCall.number)
        assertFalse("a plus alone cannot be dialled", vm.state.value.testCall.numberReady)

        val typed = "+998901234567"
        for (index in typed.indices) {
            vm.setTestNumber(typed.substring(0, index + 1))
            assertEquals("every keystroke stays in the field", typed.substring(0, index + 1), vm.state.value.testCall.number)
        }
        assertTrue("the number is dialable", vm.state.value.testCall.numberReady)

        advanceUntilIdle()
        assertEquals("what is stored keeps the plus and the digits", typed, store.settings.value.testNumber)

        var dialed: String? = null
        api.testCallHandler = { request ->
            dialed = request.destination
            ApiOutcome.Ok(200, CallSnapshot(callId = "test-call", profile = "test"))
        }
        vm.requestTestCall()
        vm.confirmTestCall()
        advanceUntilIdle()
        assertEquals(typed, dialed)
    }

    @Test
    fun `a stored seed that arrives after editing started leaves the draft alone`() = runTest(testDispatcher) {
        val store = GatedPhonePreferences(initial = PhoneSettings(testNumber = "+12025550123"))
        prefs = store
        val vm = viewModel()

        // The keystroke lands before the one-time seed coroutine has run: the seed then reads a
        // value that is older than the draft, and must not write it back.
        vm.setTestNumber("+998901234567")
        runCurrent()
        assertEquals("the seeded value never replaces the draft", "+998901234567", vm.state.value.testCall.number)

        store.releaseOne()
        runCurrent()
        assertEquals("the draft is what the store kept", "+998901234567", store.settings.value.testNumber)
        assertEquals("+998901234567", vm.state.value.testCall.number)
    }

    @Test
    fun `the test call arms the return before it is placed and spends it on failure`() = runTest(testDispatcher) {
        var expectingDuringPlacement = false
        api.testCallHandler = {
            // The token has to be armed while the request is in flight: the daemon answers only
            // after Telecom may already have drawn its in-call screen.
            expectingDuringPlacement = LiveCallFront.awaitsCover()
            ApiOutcome.Ok(200, CallSnapshot(callId = "call-7", profile = "test"))
        }
        val vm = viewModel()
        vm.setTestNumber("+12025550123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }
        vm.requestTestCall()
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.callId != null } }
        assertTrue("placement happens inside an open return opportunity", expectingDuringPlacement)
        assertEquals("call-7", LiveCallFront.pendingCallId())
        assertEquals("call-7", vm.state.value.testCall.callId)

        // A placement the daemon refused covers nothing, so nothing may be pulled forward later.
        LiveCallFront.resetForTest()
        api.testCallHandler = { ApiOutcome.Failure(409, "unsupported_by_phone", "the phone cannot place calls") }
        vm.requestTestCall()
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.error != null } }
        assertFalse(LiveCallFront.awaitsCover())
        assertNull(LiveCallFront.placedCallId())
    }

    @Test
    fun `the probes travel only when the daemon advertises them`() = runTest(testDispatcher) {
        var placed: TestCallRequest? = null
        api.testCallHandler = { request ->
            placed = request
            ApiOutcome.Ok(200, CallSnapshot(callId = "call-8", profile = "test"))
        }
        api.infoHandler = {
            ApiOutcome.Ok(
                200,
                DaemonInfo(voiceControls = VoiceControls(testCallProbes = true, loopback = true, tone = true))
            )
        }
        val vm = viewModel()
        withTimeout(5_000) { vm.state.first { it.testCall.probesAvailable } }
        vm.setTestNumber("+12025550123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }
        vm.setTestCallLoopback(true)
        vm.setTestCallTone(true)
        vm.setTestCallToneSeconds(3)
        vm.requestTestCall()
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.callId != null } }
        assertEquals(true, placed?.loopback)
        assertEquals(TestCallTone(hz = 1_000, dbfs = -20.0, seconds = 3), placed?.tone)

        // Seconds beyond the daemon's range are clamped rather than rejected by the daemon.
        vm.setTestCallToneSeconds(30)
        assertEquals(10, vm.state.value.testCall.toneSeconds)
    }

    @Test
    fun `a daemon without the probes gets the request it already understands`() = runTest(testDispatcher) {
        var placed: TestCallRequest? = null
        api.testCallHandler = { request ->
            placed = request
            ApiOutcome.Ok(200, CallSnapshot(callId = "call-9", profile = "test"))
        }
        api.infoHandler = { ApiOutcome.Ok(200, DaemonInfo(voiceControls = VoiceControls(testCallProbes = false))) }
        val vm = viewModel()
        withTimeout(5_000) { vm.state.first { it.testCall.probesSupported != null } }
        assertFalse(vm.state.value.testCall.probesAvailable)
        vm.setTestNumber("+12025550123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }
        // The toggles are not shown, but a state that still carries them must not add the keys.
        vm.setTestCallLoopback(true)
        vm.setTestCallTone(true)
        vm.requestTestCall()
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.callId != null } }
        assertNull(placed?.loopback)
        assertNull(placed?.tone)
    }

    @Test
    fun `the result card reads the record until the summary is ready`() = runTest(testDispatcher) {
        api.testCallHandler = { ApiOutcome.Ok(200, CallSnapshot(callId = "call-9", profile = "test")) }
        api.callHandler = { callId ->
            if (api.callRequests.size < 3) {
                ApiOutcome.Ok(200, CallSnapshot(callId = callId.value, state = "ended", resultStatus = "pending"))
            } else {
                ApiOutcome.Ok(
                    200,
                    CallSnapshot(
                        callId = callId.value,
                        state = "ended",
                        callOutcome = "answered",
                        resultStatus = "ready",
                        summary = "The line answered and the tone was heard.",
                        connectedAt = 1_000.0,
                        endedAt = 1_032.0,
                        audioCodec = "opus"
                    )
                )
            }
        }
        val vm = viewModel()
        vm.resultRetryDelayMs = 1
        vm.setTestNumber("+12025550123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }
        vm.requestTestCall()
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.callId != null } }

        // The phone reports the call ended before the daemon has finished the record.
        bridge.flow.value = BridgeState(call = CallState("call-9", "+12025550123", CallPhase.ENDED, 0L))
        withTimeout(5_000) { vm.state.first { it.testCall.result?.summaryReady == true } }

        val card = vm.state.value.testCall.result!!
        assertEquals("call-9", card.callId)
        assertEquals("answered", card.outcome)
        assertEquals("opus", card.codec)
        assertEquals(32L, card.durationSeconds)
        assertEquals("The line answered and the tone was heard.", card.summary)
        assertFalse(card.waiting)
        assertEquals(3, api.callRequests.size)
        // A further ended state is the same answer: the record is not read again. The phone has to
        // report the call active first, or the second ended state is the same value and no
        // emission reaches the lab at all.
        bridge.flow.value = BridgeState(call = CallState("call-9", "+12025550123", CallPhase.ACTIVE, 0L))
        withTimeout(5_000) { vm.state.first { it.callPhase == "active" } }
        bridge.flow.value = BridgeState(call = CallState("call-9", "+12025550123", CallPhase.ENDED, 0L))
        withTimeout(5_000) { vm.state.first { it.callPhase == "ended" } }
        assertEquals(3, api.callRequests.size)
    }

    @Test
    fun `a record whose summary never arrives stops waiting`() = runTest(testDispatcher) {
        api.testCallHandler = { ApiOutcome.Ok(200, CallSnapshot(callId = "call-9", profile = "test")) }
        api.callHandler = { callId ->
            ApiOutcome.Ok(200, CallSnapshot(callId = callId.value, state = "ended", resultStatus = "pending"))
        }
        val vm = viewModel()
        vm.resultRetryDelayMs = 1
        vm.setTestNumber("+12025550123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }
        vm.requestTestCall()
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.callId != null } }
        bridge.flow.value = BridgeState(call = CallState("call-9", "+12025550123", CallPhase.ENDED, 0L))
        withTimeout(5_000) { vm.state.first { it.testCall.result?.waiting == false } }
        val card = vm.state.value.testCall.result!!
        assertEquals("pending", card.resultStatus)
        assertFalse(card.summaryReady)
        assertNull(card.error)
        assertEquals(8, api.callRequests.size)
    }

    @Test
    fun `a ready record with a blank summary has no summary`() {
        assertTrue(TestCallResultUi(callId = "c", resultStatus = "ready", summary = null).summaryAbsent)
        assertTrue(TestCallResultUi(callId = "c", resultStatus = "ready", summary = " ").summaryAbsent)
        assertTrue(TestCallResultUi(callId = "c", resultStatus = null, summary = null).summaryAbsent)
        assertFalse(TestCallResultUi(callId = "c", resultStatus = "pending", summary = null).summaryAbsent)
        assertFalse(TestCallResultUi(callId = "c", resultStatus = "ready", summary = "Said hello.").summaryAbsent)
    }

    @Test
    fun `a call that ends before Telecom reports it still gets its result`() = runTest(testDispatcher) {
        api.testCallHandler = { ApiOutcome.Ok(200, CallSnapshot(callId = "call-voice", profile = "test")) }
        api.callHandler = { callId ->
            ApiOutcome.Ok(
                200,
                CallSnapshot(
                    callId = callId.value,
                    state = "failed",
                    callOutcome = "failed",
                    resultStatus = "ready",
                    summary = "The voice engine did not start.",
                    error = "voice_unavailable"
                )
            )
        }
        val vm = viewModel()
        vm.setTestNumber("+12025550123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }
        vm.requestTestCall()
        vm.confirmTestCall()
        // Voice startup failed before dialing: no ended state ever reaches this screen.
        withTimeout(5_000) { vm.state.first { it.testCall.result?.waiting == false } }

        val card = vm.state.value.testCall.result!!
        assertEquals("call-voice", card.callId)
        assertEquals("failed", card.outcome)
        assertEquals("The voice engine did not start.", card.summary)
        assertNull(vm.state.value.callPhase)
        assertEquals(1, api.callRequests.size)
    }

    @Test
    fun `each probe travels only when this phone has it`() = runTest(testDispatcher) {
        val placed = mutableListOf<TestCallRequest>()
        api.testCallHandler = { request ->
            placed += request
            ApiOutcome.Ok(200, CallSnapshot(callId = "call-${placed.size}", profile = "test"))
        }
        api.callHandler = { callId ->
            ApiOutcome.Ok(200, CallSnapshot(callId = callId.value, state = "ended", resultStatus = "ready", summary = "done"))
        }
        // The daemon starts the probes itself, but this phone has loopback only.
        api.infoHandler = {
            ApiOutcome.Ok(
                200,
                DaemonInfo(voiceControls = VoiceControls(testCallProbes = true, loopback = true, tone = false))
            )
        }
        val vm = viewModel()
        withTimeout(5_000) { vm.state.first { it.testCall.probesSupported == true } }
        assertTrue(vm.state.value.testCallLoopbackAvailable)
        assertFalse(vm.state.value.testCallToneAvailable)
        vm.setTestNumber("+12025550123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }
        vm.setTestCallLoopback(true)
        vm.setTestCallTone(true)
        vm.requestTestCall()
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.callId != null } }
        assertEquals(true, placed.single().loopback)
        assertNull("a tone the phone cannot play is not asked for", placed.single().tone)
    }

    @Test
    fun `a phone without loopback is never asked for one`() = runTest(testDispatcher) {
        var placed: TestCallRequest? = null
        api.testCallHandler = { request ->
            placed = request
            ApiOutcome.Ok(200, CallSnapshot(callId = "call-9", profile = "test"))
        }
        api.infoHandler = {
            ApiOutcome.Ok(
                200,
                DaemonInfo(voiceControls = VoiceControls(testCallProbes = true, loopback = false, tone = true))
            )
        }
        val vm = viewModel()
        withTimeout(5_000) { vm.state.first { it.testCall.probesSupported == true } }
        assertFalse(vm.state.value.testCallLoopbackAvailable)
        assertTrue(vm.state.value.testCallToneAvailable)
        vm.setTestNumber("+12025550123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }
        vm.setTestCallLoopback(true)
        vm.setTestCallTone(true)
        vm.requestTestCall()
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.callId != null } }
        assertNull("a loopback the phone cannot run is not asked for", placed?.loopback)
        assertEquals(TestCallTone(hz = 1_000, dbfs = -20.0, seconds = 5), placed?.tone)
    }

    @Test
    fun `the automatic tone takes the frequency and level set before dialing`() = runTest(testDispatcher) {
        var placed: TestCallRequest? = null
        api.testCallHandler = { request ->
            placed = request
            ApiOutcome.Ok(200, CallSnapshot(callId = "call-tone", profile = "test"))
        }
        api.callHandler = { callId ->
            ApiOutcome.Ok(200, CallSnapshot(callId = callId.value, state = "ended", resultStatus = "ready", summary = "done"))
        }
        api.infoHandler = {
            ApiOutcome.Ok(200, DaemonInfo(voiceControls = VoiceControls(testCallProbes = true, tone = true)))
        }
        val vm = viewModel()
        withTimeout(5_000) { vm.state.first { it.testCall.probesSupported == true } }
        vm.setTestNumber("+12025550123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }
        vm.setTestCallTone(true)
        // No call is active: the tone settings belong to the phone, not to a live call.
        assertFalse(vm.state.value.audioEnabled)
        vm.setToneHz(1_500)
        vm.setToneDbfs(-35.0)
        vm.requestTestCall()
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.callId != null } }
        assertEquals(TestCallTone(hz = 1_500, dbfs = -35.0, seconds = 5), placed?.tone)
    }

    @Test
    fun `leaving the lab stops the read and coming back resumes it`() = runTest(testDispatcher) {
        api.testCallHandler = { ApiOutcome.Ok(200, CallSnapshot(callId = "call-hidden", profile = "test")) }
        api.callHandler = { callId ->
            ApiOutcome.Ok(200, CallSnapshot(callId = callId.value, state = "ended", resultStatus = "pending"))
        }
        // The second read of the record is held: the card is already published and the read parked
        // inside the fake, so "waiting" is a state the test stands on rather than races past.
        val heldRead = CompletableDeferred<Unit>()
        var reads = 0
        api.callGate = { if (++reads == 2) heldRead.await() }
        val vm = viewModel()
        vm.setTestNumber("+12025550123")
        vm.requestTestCall()
        vm.confirmTestCall()
        advanceUntilIdle()

        assertTrue("the card is waiting while the read is parked", vm.state.value.testCall.result?.waiting == true)
        val left = api.callRequests.size

        vm.onLabHidden()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("a hidden lab reads nothing more", left, api.callRequests.size)
        assertTrue("what was read stays on screen", vm.state.value.testCall.result?.waiting == true)

        heldRead.complete(Unit)
        vm.onLabShown()
        advanceUntilIdle()
        assertFalse("the read finishes once the lab is back", vm.state.value.testCall.result?.waiting == true)
        assertTrue("the read is taken up again on return", api.callRequests.size > left)
        assertEquals("call-hidden", vm.state.value.testCall.result?.callId)
    }

    @Test
    fun `a bridge update behind the call record starts no read`() = runTest(testDispatcher) {
        api.testCallHandler = { ApiOutcome.Ok(200, CallSnapshot(callId = "call-behind", profile = "test")) }
        api.callHandler = { callId ->
            ApiOutcome.Ok(200, CallSnapshot(callId = callId.value, state = "ended", resultStatus = "pending"))
        }
        val heldRead = CompletableDeferred<Unit>()
        var reads = 0
        // Held and never released: the test only needs the read parked while the hints arrive.
        api.callGate = { if (++reads == 2) heldRead.await() }
        val vm = viewModel()
        vm.setTestNumber("+12025550123")
        vm.requestTestCall()
        vm.confirmTestCall()
        advanceUntilIdle()
        assertTrue(vm.state.value.testCall.result?.waiting == true)

        // Pushing the call record hides the Lab; the bridge keeps talking to the retained view model.
        vm.onLabHidden()
        runCurrent()
        val left = api.callRequests.size
        bridge.flow.value = BridgeState(call = CallState("call-behind", "+12025550123", CallPhase.ENDED, 0L))
        bridge.flow.value = BridgeState(call = CallState("call-behind", "+12025550123", CallPhase.ENDED, 1L))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("an ended hint behind the call record reads nothing", left, api.callRequests.size)
        assertTrue("what was read stays as it was", vm.state.value.testCall.result?.waiting == true)
    }

    @Test
    fun `a record that never loads ends on an error and later hints leave it`() = runTest(testDispatcher) {
        api.testCallHandler = { ApiOutcome.Ok(200, CallSnapshot(callId = "call-lost", profile = "test")) }
        api.callHandler = { ApiOutcome.Failure(503, "link_down", "the bridge service is not running") }
        val vm = viewModel()
        vm.setTestNumber("+12025550123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }
        vm.requestTestCall()
        vm.confirmTestCall()

        withTimeout(5_000) { vm.state.first { it.testCall.result?.error != null } }
        val card = vm.state.value.testCall.result!!
        assertFalse(card.waiting)
        assertFalse(card.unfinished)
        assertEquals(8, api.callRequests.size)

        bridge.flow.value = BridgeState(call = CallState("call-lost", "+12025550123", CallPhase.ENDED, 0L))
        bridge.flow.value = BridgeState(call = CallState("call-lost", "+12025550123", CallPhase.ENDED, 1L))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("the answer is final: repeated ended hints read nothing", 8, api.callRequests.size)
    }

    @Test
    fun `a call the bridge never closes ends on an unfinished card`() = runTest(testDispatcher) {
        api.testCallHandler = { ApiOutcome.Ok(200, CallSnapshot(callId = "call-long", profile = "test")) }
        api.callHandler = { callId -> ApiOutcome.Ok(200, CallSnapshot(callId = callId.value, state = "active")) }
        val vm = viewModel()
        vm.setTestNumber("+12025550123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }
        vm.requestTestCall()
        vm.confirmTestCall()

        withTimeout(5_000) { vm.state.first { it.testCall.result?.unfinished == true } }
        val card = vm.state.value.testCall.result!!
        assertFalse(card.waiting)
        assertNull(card.error)
        assertEquals(90, api.callRequests.size)
    }

    @Test
    fun `a terminal event reads the record before the retry delay`() = runTest(testDispatcher) {
        api.testCallHandler = { ApiOutcome.Ok(200, CallSnapshot(callId = "call-event", profile = "test")) }
        api.callHandler = { callId ->
            if (api.callRequests.size < 2) {
                ApiOutcome.Ok(200, CallSnapshot(callId = callId.value, state = "active"))
            } else {
                ApiOutcome.Ok(
                    200,
                    CallSnapshot(
                        callId = callId.value,
                        state = "ended",
                        resultStatus = "ready",
                        summary = "The line answered and the tone was heard."
                    )
                )
            }
        }
        api.eventsHandler = {
            ApiOutcome.Ok(
                200,
                CallEventsPage(
                    callId = "call-event",
                    revision = 1,
                    state = "ended",
                    events = listOf(CallEvent(type = "ended", revision = 1, state = "ended"))
                )
            )
        }
        val vm = viewModel()
        vm.resultRetryDelayMs = 10_000
        vm.setTestNumber("+12025550123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }
        vm.requestTestCall()
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.callId != null } }

        // The record still says "active", and the retry delay is ten seconds. A second read this
        // early can only be the call's own terminal event waking the wait.
        advanceTimeBy(2)
        runCurrent()
        assertEquals("the terminal page woke the record read", 2, api.callRequests.size)

        withTimeout(5_000) { vm.state.first { it.testCall.result?.summary == "The line answered and the tone was heard." } }
        assertFalse(vm.state.value.testCall.result!!.unfinished)
    }

    @Test
    fun `a new placement never inherits the previous call's result`() = runTest(testDispatcher) {
        var placements = 0
        api.testCallHandler = { _ ->
            placements++
            ApiOutcome.Ok(200, CallSnapshot(callId = "call-$placements", profile = "test"))
        }
        api.callHandler = { callId ->
            if (callId.value == "call-1") {
                ApiOutcome.Ok(200, CallSnapshot(callId = callId.value, state = "ended", resultStatus = "pending"))
            } else {
                ApiOutcome.Ok(
                    200,
                    CallSnapshot(
                        callId = callId.value,
                        state = "ended",
                        callOutcome = "answered",
                        resultStatus = "ready",
                        summary = "The second call."
                    )
                )
            }
        }
        val vm = viewModel()
        vm.setTestNumber("+12025550123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }
        vm.requestTestCall()
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.result?.waiting == true } }

        // The first call's read is still waiting when the next placement starts.
        vm.requestTestCall()
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.result?.summary == "The second call." } }
        advanceUntilIdle()
        assertEquals("call-2", vm.state.value.testCall.result?.callId)
        assertTrue(
            "the first call's read stopped instead of using its whole budget",
            api.callRequests.count { it == "call-1" } < 8
        )
    }

    @Test
    fun `the codec trial keeps the richest healthy opus profile`() = runTest(testDispatcher) {
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
        vm.awaitIoState { it.trial.recommendation != null }
        val best = vm.state.value.trial.recommendation
        assertEquals(32_000, best?.bitrate)
        assertEquals(4, vm.state.value.trial.runs.size)

        vm.applyTrialRecommendation()
        advanceUntilIdle()
        assertEquals("opus", prefs.settings.value.preferredCodec)
        assertEquals(32_000, prefs.settings.value.maxBitrate)
    }

    @Test
    fun `the message record follows the stored preference and the switch writes it`() = runTest(testDispatcher) {
        prefs = FakePhonePreferences(PhoneSettings(frameTap = true))
        val vm = viewModel()
        advanceUntilIdle()
        // The switch shows what is stored, not a default this screen invented.
        assertTrue(vm.state.value.frameTap)

        vm.setFrameTap(false)
        advanceUntilIdle()
        assertFalse(prefs.settings.value.frameTap)
        assertFalse(vm.state.value.frameTap)

        vm.setFrameTap(true)
        advanceUntilIdle()
        assertTrue(prefs.settings.value.frameTap)
        assertTrue(vm.state.value.frameTap)
    }

    @Test
    fun `the message switch never flips back while its writes are on their way`() = runTest(testDispatcher) {
        val store = GatedFrameTapPreferences(PhoneSettings(frameTap = false))
        prefs = store
        val vm = viewModel()
        advanceUntilIdle()
        assertFalse("the store's initial off", vm.state.value.frameTap)
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(testDispatcher) { vm.state.collect { seen += it.frameTap } }

        vm.setFrameTap(true)
        runCurrent()
        assertTrue("the tap shows at once", vm.state.value.frameTap)
        assertTrue("the first write is the one the store took", store.awaitFrameTapWrite())

        vm.setFrameTap(false)
        runCurrent()
        assertFalse("the newest tap is the switch", vm.state.value.frameTap)
        assertFalse(store.awaitFrameTapWrite())
        val afterOff = seen.size

        // An unrelated write leaves the switch's stored value as it was: an old snapshot.
        store.writeElsewhere { it.copy(testNumber = "+998900000000") }
        runCurrent()
        assertFalse("an old snapshot does not undo the choice", vm.state.value.frameTap)

        // The earlier "on" write lands. Its value is not the choice any more.
        store.releaseFrameTapWrite()
        runCurrent()
        assertFalse("the switch never shows on again", vm.state.value.frameTap)
        assertTrue("the store did take the older write", store.settings.value.frameTap)

        // The last write lands: it is the choice that stands.
        store.releaseFrameTapWrite()
        advanceUntilIdle()
        assertFalse("the store kept the last choice", store.settings.value.frameTap)
        assertFalse(vm.state.value.frameTap)
        assertEquals(listOf(true, false), store.frameTapWrites)
        assertFalse("the switch was never on after the user chose off", seen.drop(afterOff).any { it })
    }

    @Test
    fun `a failed switch write's late read never undoes a newer tap`() = runTest(testDispatcher) {
        val store = HeldReadFrameTapPreferences(PhoneSettings(frameTap = false))
        val vm = LabViewModel(api, bridge, store, lanCheck, context, feed()).apply {
            resultRetryDelayMs = 1
            viewModels += this
        }
        advanceUntilIdle()

        // The "on" write fails; its read of the store starts with the off it holds, and waits.
        store.failNextWrite = true
        store.holdReads = true
        vm.setFrameTap(true)
        runCurrent()
        assertTrue("the failed write's read started", store.readStarted.isCompleted)

        // Meanwhile the user taps off, then on, and both writes land.
        store.holdReads = false
        vm.setFrameTap(false)
        runCurrent()
        vm.setFrameTap(true)
        runCurrent()
        assertTrue(store.stored.value.frameTap)
        assertTrue(vm.state.value.frameTap)

        // The old read answers with the off it began with: the newer tap stands.
        store.releaseRead.complete(Unit)
        advanceUntilIdle()
        assertTrue("an old read never undoes a newer tap", vm.state.value.frameTap)
    }

    @Test
    fun `probe results replace the rows without losing the selection`() = runTest(testDispatcher) {
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
        withTimeout(5_000) { vm.state.first { it.probe.rows.isNotEmpty() } }
        assertEquals(setOf("iroh", "tailscale"), vm.state.value.probe.selected)
        assertEquals(2, vm.state.value.probe.rows.size)
        assertNull(vm.state.value.probe.error)
    }

    @Test
    fun `the lan row shows the phone's own probe, not the daemon's listener check alone`() = runTest(testDispatcher) {
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
        pair(BridgeConfig.Address(url, "dev-token", pin))
        lanCheck.results = { urls -> urls.map { LanPathProbe(it, reachable = true, rttMs = 9) } }
        val vm = viewModel()
        vm.toggleProbeCandidate("iroh")
        vm.toggleProbeCandidate("tailscale")
        vm.runProbe()
        vm.awaitIoState { it.probe.rows.isNotEmpty() }
        val row = vm.state.value.probe.rows.single()
        // The handshake is the measurement: the daemon's stale "no listener" is not repeated.
        assertEquals(ProbeVerdict.REACHED, row.verdict)
        assertEquals(9L, row.rttMs)
        assertEquals("192.0.2.10:18763", row.endpoint)
        assertEquals(
            UiText.Res(R.string.lab_probe_note_reached, listOf("192.0.2.10:18763", "9\u00A0ms")),
            row.note
        )
        assertNull(row.listenerNote)
        assertEquals(listOf(url), lanCheck.calls.single().first)
        assertEquals(pin, lanCheck.calls.single().second)
        assertEquals("dev-token", lanCheck.calls.single().third)
    }

    @Test
    fun `a re-pair while the info answer is in flight stops the phone probe`() = runTest(testDispatcher) {
        val url = "wss://192.0.2.10:18763/device"
        val pin = "cc".repeat(32)
        api.probeHandler = {
            ApiOutcome.Ok(
                200,
                ProbeResult(
                    candidates = listOf(ProbeCandidate("lan", available = true, reachable = true, rttMs = 4.0)),
                    checkedAt = "2026-11-26T10:00:00+00:00"
                )
            )
        }
        var infoCalls = 0
        api.infoHandler = {
            // The view model reads info once on start; the pairing moves while
            // the probe's own answer is in flight.
            infoCalls += 1
            if (infoCalls > 1) PairingGate.expect("cert:another-daemon")
            ApiOutcome.Ok(200, DaemonInfo(connection = ConnectionInfo(lan = listOf(url), certificate = pin)))
        }
        pair(BridgeConfig.Address(url, "dev-token", pin))
        lanCheck.results = { urls -> urls.map { LanPathProbe(it, reachable = true, rttMs = 9) } }
        val vm = viewModel()
        vm.toggleProbeCandidate("iroh")
        vm.toggleProbeCandidate("tailscale")

        vm.runProbe()
        vm.awaitIoState { it.probe.rows.isNotEmpty() }

        // The handshake would have carried the new pairing's device token to
        // the previous daemon's pinned address; it never opens a socket.
        assertTrue(lanCheck.calls.isEmpty())
        val row = vm.state.value.probe.rows.single()
        assertEquals(ProbeVerdict.NOT_CHECKED, row.verdict)
        assertEquals(UiText.Res(R.string.lab_probe_note_stale), row.note)
    }

    @Test
    fun `the lan probe authenticates with the device credential of the bound pairing`() = runTest(testDispatcher) {
        val url = "wss://192.0.2.10:18763/device"
        val pin = "cc".repeat(32)
        api.probeHandler = {
            ApiOutcome.Ok(
                200,
                ProbeResult(
                    candidates = listOf(ProbeCandidate("lan", available = true, reachable = false, reason = "no_listener_answer")),
                    checkedAt = "2026-11-26T10:00:00+00:00"
                )
            )
        }
        api.infoHandler = { ApiOutcome.Ok(200, DaemonInfo(connection = ConnectionInfo(lan = listOf(url), certificate = pin))) }
        pair(BridgeConfig.Address(url, "dev-token", pin))
        // The running gateway carries an app-API token that the daemon does not
        // accept on the pinned device handshake.
        val identity = PairingGate.expectedIdentity()!!
        PairingGate.install(FakeLabGateway("app-token"), identity)
        val vm = viewModel()
        vm.toggleProbeCandidate("iroh")
        vm.toggleProbeCandidate("tailscale")
        vm.runProbe()
        vm.awaitIoState { it.probe.rows.isNotEmpty() }

        assertEquals("dev-token", lanCheck.calls.single().third)
    }

    @Test
    fun `a re-pair while the handshake is running discards its result`() = runTest(testDispatcher) {
        val url = "wss://192.0.2.10:18763/device"
        val pin = "cc".repeat(32)
        api.probeHandler = {
            ApiOutcome.Ok(
                200,
                ProbeResult(
                    candidates = listOf(ProbeCandidate("lan", available = true, reachable = true, rttMs = 4.0)),
                    checkedAt = "2026-11-26T10:00:00+00:00"
                )
            )
        }
        api.infoHandler = { ApiOutcome.Ok(200, DaemonInfo(connection = ConnectionInfo(lan = listOf(url), certificate = pin))) }
        pair(BridgeConfig.Address(url, "dev-token", pin))
        lanCheck.results = { urls ->
            // The pairing moves while the pinned handshake is in flight.
            PairingGate.expect("cert:another-daemon")
            urls.map { LanPathProbe(it, reachable = true, rttMs = 9) }
        }
        val vm = viewModel()
        vm.toggleProbeCandidate("iroh")
        vm.toggleProbeCandidate("tailscale")
        vm.runProbe()
        vm.awaitIoState { it.probe.rows.isNotEmpty() }

        assertEquals(1, lanCheck.calls.size)
        val row = vm.state.value.probe.rows.single()
        assertEquals(ProbeVerdict.NOT_CHECKED, row.verdict)
        assertEquals(UiText.Res(R.string.lab_probe_note_stale), row.note)
    }

    @Test
    fun `a phone that cannot reach the listener says so while the daemon still answers`() = runTest(testDispatcher) {
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
        pair(BridgeConfig.Address(url, "dev-token", pin))
        lanCheck.results = { urls -> urls.map { LanPathProbe(it, reachable = false, rttMs = null) } }
        val vm = viewModel()
        vm.toggleProbeCandidate("iroh")
        vm.toggleProbeCandidate("tailscale")
        vm.runProbe()
        vm.awaitIoState { it.probe.rows.isNotEmpty() }
        val row = vm.state.value.probe.rows.single()
        assertEquals(ProbeVerdict.NOT_REACHED, row.verdict)
        assertNull(row.rttMs)
        assertEquals(UiText.Res(R.string.lab_probe_note_unreached, listOf("192.0.2.10:18763")), row.note)
        assertEquals(UiText.Res(R.string.lab_probe_listener_answers), row.listenerNote)
    }

    @Test
    fun `without a LAN address the row says it was not checked and why`() = runTest(testDispatcher) {
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
        withTimeout(5_000) { vm.state.first { it.probe.rows.isNotEmpty() } }
        val row = vm.state.value.probe.rows.single()
        assertEquals(ProbeVerdict.NOT_CHECKED, row.verdict)
        assertEquals(UiText.Res(R.string.lab_probe_note_no_lan), row.note)
        assertEquals(UiText.Res(R.string.lab_probe_listener_silent), row.listenerNote)
        assertTrue(lanCheck.calls.isEmpty())
    }

    @Test
    fun `a daemon-reachable listener is not shown as reachable when the phone cannot check it`() = runTest(testDispatcher) {
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
        withTimeout(5_000) { vm.state.first { it.probe.rows.isNotEmpty() } }
        val row = vm.state.value.probe.rows.single()
        // Unmeasured is not unreachable: the daemon's "reachable" never becomes a 3 ms readout.
        assertEquals(ProbeVerdict.NOT_CHECKED, row.verdict)
        assertNull(row.rttMs)
        assertEquals(UiText.Res(R.string.lab_probe_note_no_info), row.note)
        assertTrue(lanCheck.calls.isEmpty())
    }

    @Test
    fun `a path the bridge did not measure stays not checked`() = runTest(testDispatcher) {
        api.probeHandler = {
            ApiOutcome.Ok(
                200,
                ProbeResult(
                    candidates = listOf(
                        ProbeCandidate("iroh", available = true, reachable = false, rttMs = null, reason = "not_measured")
                    ),
                    checkedAt = "2026-11-26T10:00:00+00:00"
                )
            )
        }
        val vm = viewModel()
        vm.toggleProbeCandidate("lan")
        vm.toggleProbeCandidate("tailscale")
        vm.runProbe()
        withTimeout(5_000) { vm.state.first { it.probe.rows.isNotEmpty() } }
        val row = vm.state.value.probe.rows.single()
        assertEquals(ProbeVerdict.NOT_CHECKED, row.verdict)
        assertNull(row.rttMs)
        assertEquals(UiText.Res(R.string.lab_probe_reason_not_measured), row.note)
    }

    @Test
    fun `a missing tailscale is the computer's and the daemon's own words stay under details`() = runTest(testDispatcher) {
        api.probeHandler = {
            ApiOutcome.Ok(
                200,
                ProbeResult(
                    candidates = listOf(
                        ProbeCandidate("tailscale", available = false, reachable = false, reason = "not_installed"),
                        ProbeCandidate(
                            "iroh",
                            available = false,
                            reachable = false,
                            detail = "the iroh endpoint is not bound on this daemon"
                        )
                    ),
                    checkedAt = "2026-11-26T10:00:00+00:00"
                )
            )
        }
        val vm = viewModel()
        vm.toggleProbeCandidate("lan")
        vm.runProbe()
        withTimeout(5_000) { vm.state.first { it.probe.rows.size == 2 } }
        val tailscale = vm.state.value.probe.rows.single { it.key == "tailscale" }
        assertEquals(UiText.Res(R.string.lab_probe_reason_not_installed), tailscale.note)
        assertTrue(
            "the note names the computer",
            context.getString(R.string.lab_probe_reason_not_installed).contains("computer")
        )
        assertEquals("reason: not_installed", tailscale.details)
        val iroh = vm.state.value.probe.rows.single { it.key == "iroh" }
        assertEquals("the iroh endpoint is not bound on this daemon", iroh.details)
        assertNull(iroh.rttMs)
    }

    @Test
    fun `diagnostics text renders and the log view honours the filter`() = runTest(testDispatcher) {
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
    fun `loopback support comes from the daemon info`() = runTest(testDispatcher) {
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

open class FakePhonePreferences(initial: PhoneSettings = PhoneSettings()) : PhonePreferences {
    override val settings = MutableStateFlow(initial)

    override suspend fun update(block: (PhoneSettings) -> PhoneSettings): PhoneSettings {
        val next = block(settings.value)
        settings.value = next
        return next
    }
}

/**
 * A store whose writes the test releases by hand: [awaitWrite] says which value a write has just
 * taken and [releaseOne] lets the oldest held write land. A value therefore lands at an exact
 * moment the test chooses — including while the next write is still in flight.
 */
class GatedPhonePreferences(initial: PhoneSettings = PhoneSettings()) : FakePhonePreferences(initial) {
    private val started = Channel<String>(Channel.UNLIMITED)
    private val releases = Channel<Unit>(Channel.UNLIMITED)

    /** The values the store has actually published, in order. */
    val written = mutableListOf<String>()

    /** Waits until a write starts and returns the value it will store. */
    suspend fun awaitWrite(): String = started.receive()

    /** Lets the oldest held write land. */
    fun releaseOne() {
        releases.trySend(Unit)
    }

    override suspend fun update(block: (PhoneSettings) -> PhoneSettings): PhoneSettings {
        val next = block(settings.value)
        started.send(next.testNumber)
        releases.receive()
        settings.value = next
        written += next.testNumber
        return next
    }
}

/**
 * A store whose switch writes the test releases by hand: [awaitFrameTapWrite] says which value a
 * write has just taken, [releaseFrameTapWrite] lets the oldest held write land, and
 * [writeElsewhere] lands an unrelated write over the same stored snapshot.
 */
class GatedFrameTapPreferences(initial: PhoneSettings = PhoneSettings()) : FakePhonePreferences(initial) {
    private val started = Channel<Boolean>(Channel.UNLIMITED)
    private val releases = Channel<Unit>(Channel.UNLIMITED)

    /** The switch value each held write actually stored, in the order the writes landed. */
    val frameTapWrites = mutableListOf<Boolean>()

    /** Waits until a switch write starts and returns the value it will store. */
    suspend fun awaitFrameTapWrite(): Boolean = started.receive()

    /** Lets the oldest held switch write land. */
    fun releaseFrameTapWrite() {
        releases.trySend(Unit)
    }

    /** An unrelated preference write: the stored snapshot re-emits with the switch unchanged. */
    fun writeElsewhere(block: (PhoneSettings) -> PhoneSettings) {
        settings.value = block(settings.value)
    }

    override suspend fun update(block: (PhoneSettings) -> PhoneSettings): PhoneSettings {
        started.send(block(settings.value).frameTap)
        releases.receive()
        // A real edit reads the store when it runs, not when it was asked: the value stored is the
        // one the block makes of whatever the store holds at that moment.
        val next = block(settings.value)
        settings.value = next
        frameTapWrites += next.frameTap
        return next
    }
}

/**
 * A store whose next switch write can fail and whose reads, once held, answer with what was stored
 * when they started: a read still on its way while newer writes land.
 */
class HeldReadFrameTapPreferences(initial: PhoneSettings) : PhonePreferences {
    val stored = MutableStateFlow(initial)
    var failNextWrite = false
    var holdReads = false
    val readStarted = CompletableDeferred<Unit>()
    val releaseRead = CompletableDeferred<Unit>()

    override val settings: Flow<PhoneSettings> = flow {
        val snapshot = stored.value
        if (holdReads) {
            readStarted.complete(Unit)
            releaseRead.await()
        }
        emit(snapshot)
    }

    override suspend fun update(block: (PhoneSettings) -> PhoneSettings): PhoneSettings {
        if (failNextWrite) {
            failNextWrite = false
            throw IOException("the store refused the write")
        }
        stored.value = block(stored.value)
        return stored.value
    }
}

/** A live app-API gateway with its own app token, for the credential-separation tests. */
class FakeLabGateway(private val token: String) : AppGateway {
    override val appToken: StateFlow<String?> = MutableStateFlow<String?>(token)

    override val supportsAttachments: Boolean get() = false

    override suspend fun exchange(request: DeviceApiRequest, deadlineMs: Long): DeviceApiResult = DeviceApiResult(request.id, 200, buildJsonObject { })

    override suspend fun receiveAttachment(header: AttachmentHeader, sink: OutputStream): AttachmentOutcome = AttachmentOutcome.Unsupported
}
