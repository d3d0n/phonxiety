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
import com.phonxiety.bridge.data.LiveCallFront
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.CallState
import com.phonxiety.bridge.model.ConnectionInfo
import com.phonxiety.bridge.model.DaemonInfo
import com.phonxiety.bridge.model.LinkTests
import com.phonxiety.bridge.model.LoopbackResult
import com.phonxiety.bridge.model.ProbeCandidate
import com.phonxiety.bridge.model.ProbeResult
import com.phonxiety.bridge.model.TestCallRequest
import com.phonxiety.bridge.model.TestCallTone
import com.phonxiety.bridge.model.ToneResult
import com.phonxiety.bridge.model.VoiceControls
import com.phonxiety.bridge.net.LanPathProbe
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
        LiveCallFront.resetForTest()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): LabViewModel = LabViewModel(api, bridge, prefs, lanCheck, context).apply {
        // The record of a placed call is read on a one-second cadence in the app; tests do not wait.
        resultRetryDelayMs = 1
    }

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
    fun `a slow store never replays an older number over a newer keystroke`() = runBlocking<Unit> {
        // Every write takes a moment, like the disk: the stored value answers after later keystrokes.
        prefs = SlowPhonePreferences(writeDelayMs = 40)
        val vm = viewModel()
        val typed = "+998901234567"
        // A fast typer, one character at a time.
        for (length in 1..typed.length) {
            vm.setTestNumber(typed.take(length))
            delay(4)
        }
        withTimeout(5_000) { prefs.settings.first { it.testNumber == typed } }
        assertEquals("the draft wins until the user changes it", typed, vm.state.value.testCall.number)

        // The number that reaches the daemon is the one that was typed, to the digit.
        var dialed: String? = null
        api.testCallHandler = { request ->
            dialed = request.destination
            ApiOutcome.Ok(200, CallSnapshot(callId = "test-call", profile = "test"))
        }
        vm.requestTestCall()
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.callId != null } }
        assertEquals(typed, dialed)
    }

    @Test
    fun `the test call arms the return before it is placed and spends it on failure`() = runBlocking<Unit> {
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
    fun `the probes travel only when the daemon advertises them`() = runBlocking<Unit> {
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
    fun `a daemon without the probes gets the request it already understands`() = runBlocking<Unit> {
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
    fun `the result card reads the record until the summary is ready`() = runBlocking<Unit> {
        api.testCallHandler = { ApiOutcome.Ok(200, CallSnapshot(callId = "call-9", profile = "test")) }
        api.callHandler = { callId ->
            if (api.callRequests.size < 3) {
                ApiOutcome.Ok(200, CallSnapshot(callId = callId, state = "ended", resultStatus = "pending"))
            } else {
                ApiOutcome.Ok(
                    200,
                    CallSnapshot(
                        callId = callId,
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
    fun `a record whose summary never arrives stops waiting`() = runBlocking<Unit> {
        api.testCallHandler = { ApiOutcome.Ok(200, CallSnapshot(callId = "call-9", profile = "test")) }
        api.callHandler = { callId ->
            ApiOutcome.Ok(200, CallSnapshot(callId = callId, state = "ended", resultStatus = "pending"))
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
    fun `a call that ends before Telecom reports it still gets its result`() = runBlocking<Unit> {
        api.testCallHandler = { ApiOutcome.Ok(200, CallSnapshot(callId = "call-voice", profile = "test")) }
        api.callHandler = { callId ->
            ApiOutcome.Ok(
                200,
                CallSnapshot(
                    callId = callId,
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
    fun `each probe travels only when this phone has it`() = runBlocking<Unit> {
        val placed = mutableListOf<TestCallRequest>()
        api.testCallHandler = { request ->
            placed += request
            ApiOutcome.Ok(200, CallSnapshot(callId = "call-${placed.size}", profile = "test"))
        }
        api.callHandler = { callId ->
            ApiOutcome.Ok(200, CallSnapshot(callId = callId, state = "ended", resultStatus = "ready", summary = "done"))
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
    fun `a phone without loopback is never asked for one`() = runBlocking<Unit> {
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
    fun `the automatic tone takes the frequency and level set before dialing`() = runBlocking<Unit> {
        var placed: TestCallRequest? = null
        api.testCallHandler = { request ->
            placed = request
            ApiOutcome.Ok(200, CallSnapshot(callId = "call-tone", profile = "test"))
        }
        api.callHandler = { callId ->
            ApiOutcome.Ok(200, CallSnapshot(callId = callId, state = "ended", resultStatus = "ready", summary = "done"))
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
    fun `leaving the lab stops the read and coming back resumes it`() = runBlocking<Unit> {
        api.testCallHandler = { ApiOutcome.Ok(200, CallSnapshot(callId = "call-hidden", profile = "test")) }
        api.callHandler = { callId ->
            ApiOutcome.Ok(200, CallSnapshot(callId = callId, state = "ended", resultStatus = "pending"))
        }
        val vm = viewModel()
        vm.setTestNumber("+12025550123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }
        vm.requestTestCall()
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.result?.waiting == true } }

        vm.onLabHidden()
        delay(20)
        val left = api.callRequests.size
        delay(50)
        assertEquals("a hidden lab reads nothing more", left, api.callRequests.size)
        assertTrue("what was read stays on screen", vm.state.value.testCall.result?.waiting == true)

        vm.onLabShown()
        withTimeout(5_000) { vm.state.first { it.testCall.result?.waiting == false } }
        assertTrue("the read is taken up again on return", api.callRequests.size > left)
        assertEquals("call-hidden", vm.state.value.testCall.result?.callId)
    }

    @Test
    fun `a bridge update behind the call record starts no read`() = runBlocking<Unit> {
        api.testCallHandler = { ApiOutcome.Ok(200, CallSnapshot(callId = "call-behind", profile = "test")) }
        api.callHandler = { callId ->
            ApiOutcome.Ok(200, CallSnapshot(callId = callId, state = "ended", resultStatus = "pending"))
        }
        val vm = viewModel()
        vm.setTestNumber("+12025550123")
        withTimeout(5_000) { vm.state.first { it.testCall.number == "+12025550123" } }
        vm.requestTestCall()
        vm.confirmTestCall()
        withTimeout(5_000) { vm.state.first { it.testCall.result?.waiting == true } }

        // Pushing the call record hides the Lab; the bridge keeps talking to the retained view model.
        vm.onLabHidden()
        delay(20)
        val left = api.callRequests.size
        bridge.flow.value = BridgeState(call = CallState("call-behind", "+12025550123", CallPhase.ENDED, 0L))
        bridge.flow.value = BridgeState(call = CallState("call-behind", "+12025550123", CallPhase.ENDED, 1L))
        delay(50)
        assertEquals("an ended hint behind the call record reads nothing", left, api.callRequests.size)
        assertTrue("what was read stays as it was", vm.state.value.testCall.result?.waiting == true)
    }

    @Test
    fun `a record that never loads ends on an error and later hints leave it`() = runBlocking<Unit> {
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
        delay(50)
        assertEquals("the answer is final: repeated ended hints read nothing", 8, api.callRequests.size)
    }

    @Test
    fun `a call the bridge never closes ends on an unfinished card`() = runBlocking<Unit> {
        api.testCallHandler = { ApiOutcome.Ok(200, CallSnapshot(callId = "call-long", profile = "test")) }
        api.callHandler = { callId -> ApiOutcome.Ok(200, CallSnapshot(callId = callId, state = "active")) }
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
    fun `a new placement never inherits the previous call's result`() = runBlocking<Unit> {
        var placements = 0
        api.testCallHandler = { _ ->
            placements++
            ApiOutcome.Ok(200, CallSnapshot(callId = "call-$placements", profile = "test"))
        }
        api.callHandler = { callId ->
            if (callId == "call-1") {
                ApiOutcome.Ok(200, CallSnapshot(callId = callId, state = "ended", resultStatus = "pending"))
            } else {
                ApiOutcome.Ok(
                    200,
                    CallSnapshot(
                        callId = callId,
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
        delay(50)
        assertEquals("call-2", vm.state.value.testCall.result?.callId)
        assertTrue(
            "the first call's read stopped instead of using its whole budget",
            api.callRequests.count { it == "call-1" } < 8
        )
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
        withTimeout(5_000) { vm.state.first { it.probe.rows.isNotEmpty() } }
        assertEquals(setOf("iroh", "tailscale"), vm.state.value.probe.selected)
        assertEquals(2, vm.state.value.probe.rows.size)
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
        withTimeout(5_000) { vm.state.first { it.probe.rows.isNotEmpty() } }
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
        withTimeout(5_000) { vm.state.first { it.probe.rows.isNotEmpty() } }
        val row = vm.state.value.probe.rows.single()
        assertEquals(ProbeVerdict.NOT_REACHED, row.verdict)
        assertNull(row.rttMs)
        assertEquals(UiText.Res(R.string.lab_probe_note_unreached, listOf("192.0.2.10:18763")), row.note)
        assertEquals(UiText.Res(R.string.lab_probe_listener_answers), row.listenerNote)
    }

    @Test
    fun `without a LAN address the row says it was not checked and why`() = runBlocking<Unit> {
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
        withTimeout(5_000) { vm.state.first { it.probe.rows.isNotEmpty() } }
        val row = vm.state.value.probe.rows.single()
        // Unmeasured is not unreachable: the daemon's "reachable" never becomes a 3 ms readout.
        assertEquals(ProbeVerdict.NOT_CHECKED, row.verdict)
        assertNull(row.rttMs)
        assertEquals(UiText.Res(R.string.lab_probe_note_no_info), row.note)
        assertTrue(lanCheck.calls.isEmpty())
    }

    @Test
    fun `a path the bridge did not measure stays not checked`() = runBlocking<Unit> {
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
    fun `a missing tailscale is the computer's and the daemon's own words stay under details`() = runBlocking<Unit> {
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

open class FakePhonePreferences(initial: PhoneSettings = PhoneSettings()) : PhonePreferences {
    override val settings = MutableStateFlow(initial)

    override suspend fun update(block: (PhoneSettings) -> PhoneSettings): PhoneSettings {
        val next = block(settings.value)
        settings.value = next
        return next
    }
}

/** A store whose writes take time, like the disk: an answer arrives after later keystrokes. */
class SlowPhonePreferences(
    private val writeDelayMs: Long = 40,
    initial: PhoneSettings = PhoneSettings()
) : FakePhonePreferences(initial) {
    override suspend fun update(block: (PhoneSettings) -> PhoneSettings): PhoneSettings {
        delay(writeDelayMs)
        return super.update(block)
    }
}
