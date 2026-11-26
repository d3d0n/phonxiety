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
        assertTrue(controls.instruction)
        assertFalse(controls.pause)
        assertFalse(controls.dtmf)
        assertTrue(viewModel.state.value.controlsKnown)
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
        val viewModel = LiveCallViewModel(api, bridge)
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
        val viewModel = LiveCallViewModel(api, bridge)
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

    private fun viewModel(api: FakeDaemonApi): LiveCallViewModel = LiveCallViewModel(api, FakeBridgeState())

    private companion object {
        const val CALL_ID = "call-2026-11-26"
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
    transcript: List<TranscriptTurn> = emptyList()
): CallSnapshot = CallSnapshot(
    callId = "call-2026-11-26",
    destination = "+998901234567",
    state = state,
    callOutcome = callOutcome,
    taskOutcome = taskOutcome,
    resultStatus = resultStatus,
    summary = summary,
    fields = JsonObject(fields.mapValues { (_, value) -> kotlinx.serialization.json.JsonPrimitive(value) }),
    createdAt = 1_764_000_000.0,
    connectedAt = 1_764_000_005.0,
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
