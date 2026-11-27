package com.phonxiety.bridge.feature.livecall

import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.model.AudioState
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallEvent
import com.phonxiety.bridge.model.CallEventsPage
import com.phonxiety.bridge.model.CallMark
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.CallState
import com.phonxiety.bridge.model.DaemonInfo
import com.phonxiety.bridge.model.LiveCallHangUp
import com.phonxiety.bridge.model.MarksResponse
import com.phonxiety.bridge.model.SummaryResult
import com.phonxiety.bridge.model.TranscriptTurn
import com.phonxiety.bridge.model.VoiceControls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The live call against a scripted daemon: the cursor is the last applied revision, an event is
 * applied once, and a lost link resumes where it stopped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class LiveCallViewModelTest {
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
    fun transcriptArrivesOnceEvenWhenTheDaemonRepeatsIt() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.callSnapshot = ApiOutcome.Ok(200, snapshot(transcript = listOf(turn(1, "agent", "Hello"))))
        api.events += ApiOutcome.Ok(
            200,
            page(
                revision = 5,
                events = listOf(
                    stateEvent(3, "active"),
                    transcriptEvent(4, 1, "agent", "Hello"),
                    transcriptEvent(5, 2, "far_end", "Yes, who is this?")
                )
            )
        )
        // The same page again: a revision already applied must not duplicate a turn.
        api.events += ApiOutcome.Ok(
            200,
            page(
                revision = 5,
                events = listOf(transcriptEvent(5, 2, "far_end", "Yes, who is this?"))
            )
        )
        api.events += ApiOutcome.Ok(200, page(revision = 6, events = listOf(endedEvent(6))))

        val viewModel = viewModel(api)
        viewModel.bind(CALL_ID)
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), viewModel.state.value.turns.map { it.sequence })
        assertEquals(listOf<Long?>(0L, 5L, 5L), api.eventsRequests)
        assertTrue(viewModel.state.value.ended)
    }

    @Test
    fun aLostLinkResumesFromTheSameCursor() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.callSnapshot = ApiOutcome.Ok(200, snapshot())
        api.events += ApiOutcome.Failure(503, "link_down", "the bridge service is not running")
        api.events += ApiOutcome.Ok(
            200,
            page(revision = 2, events = listOf(transcriptEvent(2, 7, "agent", "Still here")))
        )
        api.events += ApiOutcome.Ok(200, page(revision = 3, events = listOf(endedEvent(3))))

        val viewModel = viewModel(api)
        viewModel.bind(CALL_ID)
        runCurrent()

        assertTrue(viewModel.state.value.reconnecting)

        advanceUntilIdle()

        assertEquals(listOf<Long?>(0L, 0L, 2L), api.eventsRequests)
        assertEquals(listOf(7L), viewModel.state.value.turns.map { it.sequence })
        assertFalse(viewModel.state.value.reconnecting)
        assertTrue(viewModel.state.value.ended)
    }

    @Test
    fun partialsAreReplacedNotAppended() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.callSnapshot = ApiOutcome.Ok(200, snapshot())
        api.events += ApiOutcome.Ok(
            200,
            page(
                revision = 2,
                events = listOf(
                    partialEvent(1, "agent", "I would like"),
                    partialEvent(2, "agent", "I would like to book")
                )
            )
        )
        api.events += ApiOutcome.Ok(200, page(revision = 3, events = listOf(endedEvent(3))))

        val viewModel = viewModel(api)
        viewModel.bind(CALL_ID)
        advanceUntilIdle()

        // The end of the call clears nothing the daemon did not clear; the last text wins.
        assertEquals(listOf("I would like to book"), viewModel.state.value.partials.map { it.text })
    }

    @Test
    fun theFinalRecordFillsInTheSummary() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.callSnapshot = ApiOutcome.Ok(200, snapshot(state = "active"))
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))
        api.callSnapshot = ApiOutcome.Ok(
            200,
            snapshot(
                state = "ended",
                callOutcome = "answered",
                taskOutcome = "complete",
                resultStatus = "ready",
                summary = "Table for two on Thursday at 20:00",
                fields = mapOf("time" to "20:00")
            )
        )

        val viewModel = viewModel(api)
        viewModel.bind(CALL_ID)
        advanceUntilIdle()

        assertEquals("Table for two on Thursday at 20:00", viewModel.state.value.summary)
        assertEquals("20:00", viewModel.state.value.summaryFields?.get("time")?.toString()?.trim('"'))
        assertEquals("answered", viewModel.state.value.callOutcome)
        assertTrue(viewModel.state.value.ended)
    }

    @Test
    fun controlsFollowTheVoiceBackend() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(
            200,
            com.phonxiety.bridge.model.DaemonInfo(
                voiceControls = VoiceControls(instruction = true, pause = false, summarize = true, dtmf = false, marks = true)
            )
        )
        api.callSnapshot = ApiOutcome.Ok(200, snapshot())
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))

        val viewModel = viewModel(api)
        viewModel.bind(CALL_ID)
        advanceUntilIdle()

        val controls = viewModel.state.value.controls
        assertEquals(ControlAvailability.ENABLED, controls.instruction)
        assertEquals(ControlAvailability.DISABLED, controls.pause)
        assertEquals(ControlAvailability.DISABLED, controls.dtmf)
        assertTrue("the daemon answered for every control", viewModel.state.value.controls.known)
    }

    @Test
    fun anInfoThatNeverAnswersLeavesEveryControlUnknown() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Failure(503, "link_down", "the daemon is not reachable")
        api.callSnapshot = ApiOutcome.Ok(200, snapshot())
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))

        val viewModel = viewModel(api)
        viewModel.bind(CALL_ID)
        advanceUntilIdle()

        val controls = viewModel.state.value.controls
        assertEquals(ControlAvailability.UNKNOWN, controls.hangup)
        assertEquals(ControlAvailability.UNKNOWN, controls.instruction)
        assertFalse("nothing was confirmed", controls.known)
        assertFalse("an unknown control is not offered", controls.hangup.isEnabled)
    }

    @Test
    fun hangUpWaitsForItsConfirmation() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo(voiceControls = VoiceControls(hangup = true)))
        api.callSnapshot = ApiOutcome.Ok(200, snapshot(state = "active"))
        api.hangup = ApiOutcome.Ok(200, snapshot(state = "ending"))

        val viewModel = viewModel(api)
        viewModel.bind(CALL_ID)
        runCurrent()

        viewModel.requestHangUp()
        runCurrent()
        assertTrue("the screen holds the pending confirmation", viewModel.state.value.pendingHangUp)
        assertEquals("nothing reached the daemon yet", 0, api.hangupCalls)

        viewModel.dismissHangUp()
        runCurrent()
        assertFalse(viewModel.state.value.pendingHangUp)
        assertEquals(0, api.hangupCalls)

        viewModel.requestHangUp()
        runCurrent()
        viewModel.confirmHangUp()
        runCurrent()
        assertFalse("confirming closes the pending value", viewModel.state.value.pendingHangUp)
        assertEquals(1, api.hangupCalls)
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))
        advanceUntilIdle()
    }

    @Test
    fun confirmingTwiceHangsUpOnce() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo(voiceControls = VoiceControls(hangup = true)))
        api.callSnapshot = ApiOutcome.Ok(200, snapshot(state = "active"))
        api.hangup = ApiOutcome.Ok(200, snapshot(state = "ending"))

        val viewModel = viewModel(api)
        viewModel.bind(CALL_ID)
        runCurrent()

        viewModel.requestHangUp()
        runCurrent()
        viewModel.confirmHangUp()
        viewModel.confirmHangUp()
        runCurrent()

        // The confirmation is single-use: the second confirm consumes nothing and sends nothing.
        assertEquals(1, api.hangupCalls)
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))
        advanceUntilIdle()
    }

    @Test
    fun anInfoFailureStillLeavesHangUpAvailableThroughTheEndCallPath() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Failure(503, "link_down", "the daemon is not reachable")
        api.callSnapshot = ApiOutcome.Ok(200, snapshot(state = "active"))
        val end = FakeCallEnd()

        val viewModel = viewModel(api, end)
        viewModel.bind(CALL_ID)
        runCurrent()
        assertEquals(
            "the voice backend never answered",
            ControlAvailability.UNKNOWN,
            viewModel.state.value.controls.hangup
        )

        viewModel.requestHangUp()
        runCurrent()
        assertTrue("hang-up still asks first", viewModel.state.value.pendingHangUp)

        viewModel.confirmHangUp()
        runCurrent()
        assertFalse("confirming closes the pending value", viewModel.state.value.pendingHangUp)
        assertEquals("the end-of-call path runs once", 1, end.calls)
        assertEquals("the unreported endpoint is not called", 0, api.hangupCalls)
        assertTrue(viewModel.state.value.hangingUp)

        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))
        advanceUntilIdle()
        assertTrue("the daemon's own end closes the screen", viewModel.state.value.ended)
    }

    @Test
    fun aRefusedHangUpAlsoUsesTheEndCallPath() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo(voiceControls = VoiceControls(hangup = false)))
        api.callSnapshot = ApiOutcome.Ok(200, snapshot(state = "active"))
        val end = FakeCallEnd()

        val viewModel = viewModel(api, end)
        viewModel.bind(CALL_ID)
        runCurrent()
        assertEquals(ControlAvailability.DISABLED, viewModel.state.value.controls.hangup)

        viewModel.requestHangUp()
        runCurrent()
        viewModel.confirmHangUp()
        runCurrent()

        assertEquals(1, end.calls)
        assertEquals(0, api.hangupCalls)
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))
        advanceUntilIdle()
    }

    @Test
    fun reloadAsksInfoAgainSoTheControlsCanSettle() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Failure(503, "link_down", "the daemon is not reachable")
        api.callSnapshot = ApiOutcome.Ok(200, snapshot(state = "active"))

        val viewModel = viewModel(api)
        viewModel.bind(CALL_ID)
        runCurrent()
        assertEquals(1, api.infoCalls)
        assertFalse(viewModel.state.value.controls.hangup.isEnabled)

        api.info = ApiOutcome.Ok(200, DaemonInfo(voiceControls = VoiceControls(hangup = true)))
        viewModel.reload()
        runCurrent()

        assertEquals("reload retries /info", 2, api.infoCalls)
        assertEquals(ControlAvailability.ENABLED, viewModel.state.value.controls.hangup)
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))
        advanceUntilIdle()
    }

    @Test
    fun tellingTheAgentSendsTheTextAndClearsTheBox() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.callSnapshot = ApiOutcome.Ok(200, snapshot())
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))

        val viewModel = viewModel(api)
        viewModel.bind(CALL_ID)
        advanceUntilIdle()

        viewModel.onInstructionChange("ask for Thursday")
        viewModel.tellAgent()
        advanceUntilIdle()

        assertEquals(listOf("ask for Thursday"), api.instructionRequests)
        assertEquals("", viewModel.state.value.instruction)
    }

    @Test
    fun pauseResumeSummarizeDtmfAndMarksReachTheDaemon() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.callSnapshot = ApiOutcome.Ok(200, snapshot())
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))
        api.summarize = ApiOutcome.Ok(200, SummaryResult(summary = "agreed", fields = JsonObject(mapOf("time" to kotlinx.serialization.json.JsonPrimitive("18:00")))))
        api.marks = ApiOutcome.Ok(200, MarksResponse(marks = listOf(CallMark(mark = "agreed"))))

        val viewModel = viewModel(api)
        viewModel.bind(CALL_ID)
        advanceUntilIdle()

        viewModel.togglePause()
        advanceUntilIdle()
        assertEquals(1, api.pauseCalls)
        assertTrue(viewModel.state.value.muted)

        viewModel.togglePause()
        advanceUntilIdle()
        assertEquals(1, api.resumeCalls)
        assertFalse(viewModel.state.value.muted)

        viewModel.summarizeNow()
        advanceUntilIdle()
        assertEquals("agreed", viewModel.state.value.summary)

        viewModel.sendDtmf("1")
        advanceUntilIdle()
        assertEquals(listOf("1"), api.dtmfRequests)

        viewModel.mark("agreed")
        advanceUntilIdle()
        assertEquals(listOf("agreed"), api.markRequests)
        assertEquals(listOf("agreed"), viewModel.state.value.marks.map { it.mark })
    }

    @Test
    fun hangingUpKeepsTheScreenInTheEndingStateUntilTheDaemonSaysEnded() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo(voiceControls = VoiceControls(hangup = true)))
        api.callSnapshot = ApiOutcome.Ok(200, snapshot(state = "active"))
        api.hangup = ApiOutcome.Ok(200, snapshot(state = "ending"))
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(stateEvent(1, "ending"))))

        val viewModel = viewModel(api)
        viewModel.bind(CALL_ID)
        runCurrent()

        viewModel.hangUp()
        runCurrent()

        assertTrue(viewModel.state.value.hangingUp)
        assertFalse(viewModel.state.value.ended)

        api.events += ApiOutcome.Ok(200, page(revision = 2, events = listOf(endedEvent(2))))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.hangingUp)
        assertTrue(viewModel.state.value.ended)
    }

    @Test
    fun bindingTwiceDoesNotStartASecondPoll() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.callSnapshot = ApiOutcome.Ok(200, snapshot())
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))

        val viewModel = viewModel(api)
        viewModel.bind(CALL_ID)
        viewModel.bind(CALL_ID)
        advanceUntilIdle()

        assertEquals(1, api.eventsRequests.size)
    }

    @Test
    fun thePhaseAndTheMetersComeFromThePhoneWhileTheDaemonSaysNothing() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.callSnapshot = ApiOutcome.Ok(200, snapshot(state = null))
        val bridge = FakeBridgeState()
        val viewModel = LiveCallViewModel(api, bridge, FakeCallEnd())
        viewModel.bind(CALL_ID)
        runCurrent()

        bridge.emit(BridgeState(call = CallState(CALL_ID, "+998 ·· 67", CallPhase.DIALING, 1_000)))
        runCurrent()
        assertEquals("dialing", viewModel.state.value.phase)

        bridge.emit(
            BridgeState(
                call = CallState(CALL_ID, "+998 ·· 67", CallPhase.ACTIVE, 1_000),
                audio = AudioState(codec = "opus", bitrate = 32_000, rxLevelDbfs = -18f, txLevelDbfs = -26f)
            )
        )
        runCurrent()
        val live = viewModel.state.value
        assertEquals("active", live.phase)
        assertEquals("opus", live.audio.codec)
        assertEquals(32_000, live.audio.bitrate)
        assertEquals(-18f, live.audio.rxLevelDbfs)
        assertEquals(-26f, live.audio.txLevelDbfs)

        bridge.emit(BridgeState(call = CallState(CALL_ID, "+998 ·· 67", CallPhase.ENDED, 1_000)))
        runCurrent()
        assertEquals("ended", viewModel.state.value.phase)

        // A daemon state, once one arrives, is the one the screen shows.
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))
        advanceUntilIdle()
    }

    @Test
    fun rawDaemonEventsKeepTheirRolesAndTheScreenNamesTheSpeakers() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.callSnapshot = ApiOutcome.Ok(200, snapshot())
        // The exact payload `app_api._sync` puts on the events long-poll.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val assistant = json.decodeFromString<CallEvent>(
            """{"type":"transcript","revision":4,"sequence":303,"role":"assistant",""" +
                """"text":"Hi, I'm testing a call from the app.","final":true,""" +
                """"at":"2026-11-26T10:00:20+00:00"}"""
        )
        val user = json.decodeFromString<CallEvent>(
            """{"type":"transcript","revision":6,"sequence":304,"role":"user",""" +
                """"text":"One, two, three","final":true,""" +
                """"at":"2026-11-26T10:00:24+00:00"}"""
        )
        api.events += ApiOutcome.Ok(200, page(revision = 6, events = listOf(assistant, user)))
        api.events += ApiOutcome.Ok(200, page(revision = 7, events = listOf(endedEvent(7))))

        val viewModel = viewModel(api)
        viewModel.bind(CALL_ID)
        advanceUntilIdle()

        val turns = viewModel.state.value.turns
        assertEquals(listOf("assistant", "user"), turns.map { it.role })
        val resources = org.robolectric.RuntimeEnvironment.getApplication().resources
        assertEquals(
            listOf("Agent", "Far end"),
            turns.map { speakerLabel(it.role, resources) }
        )
    }

    @Test
    fun thePhoneAdvancesAStaleSnapshotAndALaterEventCannotRollItBack() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        // The snapshot answers after Telecom already dialed, and still says "preparing".
        api.callSnapshot = ApiOutcome.Ok(200, snapshot(state = "preparing"))
        val bridge = FakeBridgeState()
        val viewModel = LiveCallViewModel(api, bridge, FakeCallEnd())
        viewModel.bind(CALL_ID)
        runCurrent()
        assertEquals("preparing", viewModel.state.value.state)

        bridge.emit(BridgeState(call = CallState(CALL_ID, "+998 ·· 67", CallPhase.ACTIVE, 1_000)))
        runCurrent()
        assertEquals("the phone's connection advances the stale record", "active", viewModel.state.value.phase)
        assertEquals("the record's own word is still what arrived", "preparing", viewModel.state.value.state)

        // A delayed event page repeats the same stale phase; it cannot undo the connection.
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(stateEvent(1, "preparing"))))
        runCurrent()
        assertEquals("active", viewModel.state.value.phase)

        // The daemon's terminal state is the call's end, whatever the phone last knew.
        api.events += ApiOutcome.Ok(200, page(revision = 2, events = listOf(endedEvent(2))))
        advanceUntilIdle()
        assertEquals("ended", viewModel.state.value.phase)
    }

    @Test
    fun aPhonesEndedPhaseDoesNotEndAStaleRecord() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo(voiceControls = VoiceControls(hangup = true)))
        api.callSnapshot = ApiOutcome.Ok(200, snapshot(state = "preparing"))
        val bridge = FakeBridgeState()
        val viewModel = LiveCallViewModel(api, bridge, FakeCallEnd())
        viewModel.bind(CALL_ID)
        runCurrent()

        // The phone hangs up while the record still says preparing: only the daemon records an end.
        bridge.emit(BridgeState(call = CallState(CALL_ID, "+998 ·· 67", CallPhase.ENDED, 1_000)))
        runCurrent()

        val state = viewModel.state.value
        assertEquals("the record's own word still stands", "preparing", state.phase)
        assertFalse("the phone's hang-up is not a recorded end", state.ended)
        assertNull("no end time without a recorded end", state.endedAtMs)
        assertNotNull("the timer still counts the record's running call", state.callSecondsElapsed)
        assertFalse(state.terminal)
        // Hang up stays the offered action: ended and its end time travel only with the record.
        assertEquals("the record still offers hang-up", ControlAvailability.ENABLED, state.controls.hangup)

        // The daemon's terminal state is the one place the end, its time and the result arrive.
        api.events += ApiOutcome.Ok(200, page(revision = 2, events = listOf(endedEvent(2))))
        advanceUntilIdle()
        val ended = viewModel.state.value
        assertEquals("ended", ended.phase)
        assertTrue("the recorded end closes the call", ended.ended)
        assertTrue(ended.terminal)
        assertNotNull("the recorded end carries its end time", ended.endedAtMs)
    }

    @Test
    fun anUnconfirmedEndKeepsTheRecordsReasonForTheScreen() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.callSnapshot = ApiOutcome.Ok(
            200,
            snapshot(state = "unknown").copy(error = "hangup_unconfirmed: the phone did not answer")
        )

        val viewModel = viewModel(api)
        viewModel.bind(CALL_ID)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.ended)
        assertEquals("unknown", state.phase)
        // The reason is projected, not dropped: the screen cannot explain the loss without it.
        assertEquals("hangup_unconfirmed: the phone did not answer", state.statusUnknownError)
    }

    /**
     * The count starts at the bridge's connect stamp: the engine records it once at the answer, so
     * a record `connected_at` from another clock cannot move the running number mid-call. The ended
     * header is the record's connected duration.
     */
    @Test
    fun theTimerKeepsTheBridgesConnect() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        val placedAt = (System.currentTimeMillis() - 600_000) / 1_000.0
        api.callSnapshot = ApiOutcome.Ok(200, snapshot(state = "preparing", createdAt = placedAt, connectedAt = null))
        val bridge = FakeBridgeState()
        val viewModel = LiveCallViewModel(api, bridge, FakeCallEnd())
        viewModel.bind(CALL_ID)
        runCurrent()

        assertNull("the record has no connect time yet", viewModel.state.value.connectedAtMs)
        assertTrue(
            "before the line is up the header counts from placement",
            viewModel.state.value.callSecondsElapsed!! >= 599
        )

        // The bridge reports the line active with the answer time the engine recorded.
        val bridgeConnect = System.currentTimeMillis() - 5_000
        bridge.emit(
            BridgeState(call = CallState(CALL_ID, "+998 ·· 67", CallPhase.ACTIVE, 1_000, bridgeConnect))
        )
        runCurrent()
        assertEquals("the bridge's stamp starts the running count", bridgeConnect, viewModel.state.value.timerStartMs)
        assertTrue(
            "the header counts the conversation from the answer",
            viewModel.state.value.callSecondsElapsed!! in 4..6
        )

        // The record's connected_at arrives a minute old, from another clock. The running count
        // stays on the bridge's stamp, so the number never jumps mid-call.
        val connectedAt = (System.currentTimeMillis() - 60_000) / 1_000.0
        api.callSnapshot = ApiOutcome.Ok(
            200,
            snapshot(state = "active", createdAt = placedAt, connectedAt = connectedAt)
        )
        viewModel.reload()
        runCurrent()
        assertEquals((connectedAt * 1_000).toLong(), viewModel.state.value.connectedAtMs)
        assertEquals("the bridge's stamp still starts the running count", bridgeConnect, viewModel.state.value.timerStartMs)
        assertTrue(
            "the record's older connect time does not move the running number",
            viewModel.state.value.callSecondsElapsed!! < 10
        )

        // The end keeps the record's connected duration on the header.
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))
        advanceUntilIdle()
        val ended = viewModel.state.value
        assertTrue(ended.ended)
        assertEquals(
            "the ended header is the record's connected duration",
            (ended.endedAtMs!! - ended.connectedAtMs!!) / 1_000,
            ended.callSecondsElapsed
        )
        assertEquals("the ended header starts at the record's connect time", ended.connectedAtMs, ended.timerStartMs)
    }

    /**
     * A screen opened on a call that connected a minute ago counts the conversation from the
     * answer, not from the moment it opened: the engine already recorded the connect on the bridge
     * call, and the first state the screen sees reads about a minute.
     */
    @Test
    fun aScreenOpenedMidCallCountsFromTheBridgeConnect() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        val connectedAtMs = System.currentTimeMillis() - 60_000
        api.callSnapshot = ApiOutcome.Ok(
            200,
            snapshot(
                state = "active",
                createdAt = (connectedAtMs - 20_000) / 1_000.0,
                connectedAt = connectedAtMs / 1_000.0
            )
        )
        val bridge = FakeBridgeState()
        // The call was already active when this screen appeared: the bridge's first value has the
        // connect stamp the engine recorded at the answer, minute-old ring included.
        bridge.emit(
            BridgeState(call = CallState(CALL_ID, "+998 ·· 67", CallPhase.ACTIVE, connectedAtMs - 80_000, connectedAtMs))
        )

        val viewModel = LiveCallViewModel(api, bridge, FakeCallEnd())
        viewModel.bind(CALL_ID)
        runCurrent()

        val state = viewModel.state.value
        assertEquals("the bridge's connect stamp starts the count", connectedAtMs, state.timerStartMs)
        assertTrue(
            "the first state already reads about a minute, not 00:00",
            state.callSecondsElapsed!! in 59..61
        )

        // Close the poll so the test's virtual clock has nothing left to spin on.
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))
        advanceUntilIdle()
    }

    /**
     * The end can arrive before the final record does. Until that record carries the duration, the
     * header counts from the answer the engine already recorded, not from placement: the ringing
     * time the answer reset must not come back for the seconds the record is late.
     */
    @Test
    fun theEndedHeaderKeepsTheBridgesConnectUntilTheRecordSuppliesIt() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        val placedAt = (System.currentTimeMillis() - 600_000) / 1_000.0
        api.callSnapshot = ApiOutcome.Ok(200, snapshot(state = "active", createdAt = placedAt, connectedAt = null))
        val bridge = FakeBridgeState()
        val viewModel = LiveCallViewModel(api, bridge, FakeCallEnd())
        viewModel.bind(CALL_ID)
        runCurrent()

        val bridgeConnect = System.currentTimeMillis() - 5_000
        bridge.emit(
            BridgeState(call = CallState(CALL_ID, "+998 ·· 67", CallPhase.ACTIVE, (placedAt * 1_000).toLong(), bridgeConnect))
        )
        runCurrent()
        assertEquals(bridgeConnect, viewModel.state.value.timerStartMs)

        // The end arrives first; the record read afterwards still has no connected_at.
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))
        advanceUntilIdle()

        val ended = viewModel.state.value
        assertTrue(ended.ended)
        assertNull("the record still has no connect time of its own", ended.connectedAtMs)
        assertEquals("the known answer is not replaced by placement", bridgeConnect, ended.timerStartMs)
        assertTrue(
            "the ended header counts the conversation, not the ten minutes before it",
            ended.callSecondsElapsed!! in 4..6
        )

        // The delayed record finally supplies its own duration, and an ended call is its header.
        val recordConnect = (System.currentTimeMillis() - 30_000) / 1_000.0
        api.callSnapshot = ApiOutcome.Ok(200, snapshot(state = "ended", createdAt = placedAt, connectedAt = recordConnect))
        viewModel.reload()
        runCurrent()
        assertEquals((recordConnect * 1_000).toLong(), viewModel.state.value.timerStartMs)
    }

    /**
     * The engine takes a running call out of its state when the link drops. The answer it recorded
     * belongs to the call on screen, so the count keeps it instead of falling back to placement.
     */
    @Test
    fun aLostLinkMidCallKeepsTheKnownAnswer() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.callSnapshot = ApiOutcome.Ok(
            200,
            snapshot(state = "active", createdAt = (System.currentTimeMillis() - 60_000) / 1_000.0, connectedAt = null)
        )
        val bridge = FakeBridgeState()
        val viewModel = LiveCallViewModel(api, bridge, FakeCallEnd())
        viewModel.bind(CALL_ID)
        runCurrent()

        val bridgeConnect = System.currentTimeMillis() - 5_000
        bridge.emit(BridgeState(call = CallState(CALL_ID, "+998 ·· 67", CallPhase.ACTIVE, 1_000, bridgeConnect)))
        runCurrent()
        assertEquals(bridgeConnect, viewModel.state.value.timerStartMs)

        // The link drops: the store no longer carries the running call.
        bridge.emit(BridgeState(call = null))
        runCurrent()
        assertNull("the phone reports no call any more", viewModel.state.value.localPhase)
        assertEquals(
            "the answer does not leave with the link",
            bridgeConnect,
            viewModel.state.value.timerStartMs
        )

        // The record's end arrives with no connect time of its own.
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.ended)
        assertEquals(
            "the ended header still starts at the answer",
            bridgeConnect,
            viewModel.state.value.timerStartMs
        )
    }

    /** The retained answer belongs to one call: binding another one does not inherit it. */
    @Test
    fun bindingAnotherCallDoesNotInheritTheAnswer() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.callSnapshot = ApiOutcome.Failure(404, "call_not_found", "no call")
        val bridge = FakeBridgeState()
        val viewModel = LiveCallViewModel(api, bridge, FakeCallEnd())
        viewModel.bind(CALL_ID)
        runCurrent()

        val bridgeConnect = System.currentTimeMillis() - 5_000
        val bridgeCall = CallState(CALL_ID, "+998 ·· 67", CallPhase.ACTIVE, 1_000, bridgeConnect)
        bridge.emit(BridgeState(call = bridgeCall))
        runCurrent()
        assertEquals(bridgeConnect, viewModel.state.value.bridgeConnectedAtMs)

        viewModel.bind("call-2026-11-27")
        runCurrent()
        assertNull("another call starts over", viewModel.state.value.bridgeConnectedAtMs)

        // The bridge still reports the previous call; it is not the one on screen.
        bridge.emit(BridgeState(call = bridgeCall))
        runCurrent()
        assertNull("the previous call's answer is not borrowed", viewModel.state.value.bridgeConnectedAtMs)

        // Both polls are open now; close them so nothing is left spinning on the test clock.
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))
        api.events += ApiOutcome.Ok(200, page(revision = 1, events = listOf(endedEvent(1))))
        advanceUntilIdle()
    }

    private fun viewModel(
        api: FakeDaemonApi,
        callEnd: LiveCallHangUp = FakeCallEnd()
    ): LiveCallViewModel = LiveCallViewModel(api, FakeBridgeState(), callEnd)

    private companion object {
        const val CALL_ID = "call-2026-11-26"
    }
}

/** The end-of-call path the ViewModel reaches when the daemon has not vouched for a hang-up. */
class FakeCallEnd : LiveCallHangUp {
    var calls = 0

    override suspend fun hangUp() {
        calls++
    }
}

/** A bridge state the test drives: the live call reads the meters and its own phase from it. */
class FakeBridgeState : BridgeStateRepository {
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(BridgeState())
    override val state: StateFlow<BridgeState> = mutable

    fun emit(state: BridgeState) {
        mutable.value = state
    }
}

fun snapshot(
    state: String? = "active",
    callOutcome: String? = null,
    taskOutcome: String? = null,
    resultStatus: String? = null,
    summary: String? = null,
    fields: Map<String, String> = emptyMap(),
    transcript: List<TranscriptTurn> = emptyList(),
    createdAt: Double = 1_764_000_000.0,
    connectedAt: Double? = 1_764_000_005.0
): CallSnapshot = CallSnapshot(
    callId = "call-2026-11-26",
    destination = "+998901234567",
    state = state,
    callOutcome = callOutcome,
    taskOutcome = taskOutcome,
    resultStatus = resultStatus,
    summary = summary,
    fields = JsonObject(fields.mapValues { (_, value) -> kotlinx.serialization.json.JsonPrimitive(value) }),
    createdAt = createdAt,
    connectedAt = connectedAt,
    endedAt = if (state == "ended") 1_764_000_065.0 else null,
    transcript = transcript,
    request = JsonObject(emptyMap())
)

fun turn(sequence: Long, role: String, text: String): TranscriptTurn = TranscriptTurn(
    sequence = sequence,
    role = role,
    text = text,
    at = 1_764_000_005.0 + sequence,
    finalTurn = true
)

fun page(revision: Long, events: List<CallEvent>): CallEventsPage = CallEventsPage(
    callId = "call-2026-11-26",
    revision = revision,
    state = events.lastOrNull()?.state,
    events = events
)

fun stateEvent(revision: Long, state: String): CallEvent = CallEvent(type = "state", revision = revision, state = state)

fun transcriptEvent(revision: Long, sequence: Long, role: String, text: String): CallEvent = CallEvent(
    type = "transcript",
    revision = revision,
    role = role,
    text = text,
    sequence = sequence,
    finalTurn = true
)

fun partialEvent(revision: Long, role: String, text: String): CallEvent = CallEvent(type = "partial", revision = revision, role = role, text = text, finalTurn = false)

fun endedEvent(revision: Long): CallEvent = CallEvent(
    type = "ended",
    revision = revision,
    state = "ended",
    callOutcome = "answered",
    resultStatus = "ready"
)
