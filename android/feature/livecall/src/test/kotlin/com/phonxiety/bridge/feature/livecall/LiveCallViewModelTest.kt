package com.phonxiety.bridge.feature.livecall

import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallEvent
import com.phonxiety.bridge.model.CallEventsPage
import com.phonxiety.bridge.model.CallMark
import com.phonxiety.bridge.model.CallSnapshot
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
    fun pauseResumeSummariseDtmfAndMarksReachTheDaemon() = runTest(dispatcher) {
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

    private fun viewModel(api: FakeDaemonApi): LiveCallViewModel = LiveCallViewModel(api, FakeBridgeState())

    private companion object {
        const val CALL_ID = "call-2026-11-26"
    }
}

/** A bridge state that never changes: the live call only reads the meters from it. */
class FakeBridgeState : BridgeStateRepository {
    override val state: StateFlow<BridgeState> = kotlinx.coroutines.flow.MutableStateFlow(BridgeState())
}

fun snapshot(
    state: String = "active",
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
