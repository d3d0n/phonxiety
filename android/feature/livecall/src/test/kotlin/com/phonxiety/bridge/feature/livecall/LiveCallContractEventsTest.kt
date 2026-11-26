package com.phonxiety.bridge.feature.livecall

import com.phonxiety.bridge.model.CallEvent
import com.phonxiety.bridge.model.CallMark
import com.phonxiety.bridge.model.TranscriptTurn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The events half of the shared `contracts/app-api` pages, through the same reducer
 * `LiveCallViewModel` applies every polled batch with.
 *
 * Each event the daemon sends must move the state its branch owns: a renamed event
 * type falls through the `when` and changes nothing, and a renamed payload key leaves
 * the field the branch copies at its seeded value. Both fail below, on the contract
 * files themselves rather than on a hand-written event.
 */
class LiveCallContractEventsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val events: List<CallEvent> = FILES.flatMap { file ->
        val text = javaClass.classLoader!!.getResourceAsStream("app-api/$file")!!
            .bufferedReader()
            .use { it.readText() }
        val body = json.parseToJsonElement(text).jsonObject
            .getValue("response").jsonObject
            .getValue("body").jsonObject
        body.getValue("events").jsonArray.map { json.decodeFromJsonElement(CallEvent.serializer(), it) }
    }

    @Test
    fun everyContractedEventMovesTheStateItOwns() {
        val types = events.map { it.type }.toSet()
        assertEquals(
            "the contracts carry event kinds no assertion covers: ${types - EFFECTS.keys - EVENT_EXEMPTIONS.keys}",
            emptySet<String>(),
            types - EFFECTS.keys - EVENT_EXEMPTIONS.keys
        )
        assertEquals(
            "these event kinds are no longer in the contracts: ${EFFECTS.keys - types}",
            emptySet<String>(),
            EFFECTS.keys - types
        )
        assertEquals(
            "these exemptions name event kinds the contracts do not carry: ${EVENT_EXEMPTIONS.keys - types}",
            emptySet<String>(),
            EVENT_EXEMPTIONS.keys - types
        )
        for (event in events) {
            val reduced = reduceCallEvents(SEEDED, listOf(event), mutableSetOf())
            assertNotEquals(
                "${event.type} changed nothing: the reducer has no branch for it," +
                    " or the payload key its branch reads was renamed",
                SEEDED,
                reduced
            )
            EFFECTS.getValue(event.type)(event, reduced)
        }
    }

    @Test
    fun aRepeatedTranscriptEventIsAppliedOnce() {
        val turn = events.first { it.type == "transcript" }
        val applied = mutableSetOf<Long>()
        val once = reduceCallEvents(SEEDED, listOf(turn), applied)
        val twice = reduceCallEvents(once, listOf(turn), applied)
        assertEquals(once.turns, twice.turns)
        assertEquals(1, twice.turns.size - SEEDED.turns.size)
    }

    private companion object {
        val FILES = listOf("call_events_live.json", "call_events_ended.json")

        /**
         * The state every event is applied to: each field a branch writes starts at a
         * value the contract's own payload does not carry, so a no-op is visible.
         */
        val SEEDED = LiveCallUiState(
            callId = "00000000-0000-4000-8000-000000000001",
            state = "dialing",
            callOutcome = "no_answer",
            resultStatus = "pending",
            activity = "busy",
            muted = true,
            summary = "a seeded summary",
            summaryFields = buildJsonObject { put("seeded", true) },
            turns = listOf(TranscriptTurn(sequence = 99, role = "assistant", text = "seeded", at = 0.0)),
            marks = listOf(CallMark(mark = "seeded", at = 0.0, note = "seeded")),
            ended = false
        )

        /**
         * What each contracted event kind must leave in the state, taken from its own
         * payload.  The payload keys the reducer copies are required, so a kind that
         * stops sending one fails here rather than leaving the screen on its seed.
         */
        val EFFECTS: Map<String, (CallEvent, LiveCallUiState) -> Unit> = mapOf(
            "state" to { event, state ->
                assertNotNull("a state event must name its state", event.state)
                assertEquals(event.state, state.state)
                assertEquals(event.callOutcome, state.callOutcome)
            },
            "transcript" to { event, state ->
                val turn = state.turns.last()
                assertNotNull(event.sequence)
                assertNotNull(event.role)
                assertNotNull(event.text)
                assertEquals(event.sequence, turn.sequence)
                assertEquals(event.role, turn.role)
                assertEquals(event.text, turn.text)
                assertEquals(event.finalTurn ?: true, turn.finalTurn)
            },
            "partial" to { event, state ->
                assertEquals(event.text, state.partials.last().text)
                assertEquals(event.role, state.partials.last().role)
            },
            "marks" to { event, state ->
                assertNotNull("a marks event must carry the marks", event.marks)
                assertEquals(event.marks, state.marks)
            },
            "muted" to { event, state ->
                assertNotNull("a muted event must carry the flag", event.muted)
                assertEquals(event.muted, state.muted)
            },
            "agent_activity" to { event, state ->
                assertNotNull("an activity event must carry the activity", event.activity)
                assertEquals(event.activity, state.activity)
            },
            "result" to { event, state ->
                assertNotNull(event.summary)
                assertNotNull(event.fields)
                assertEquals(event.summary, state.summary)
                assertEquals(event.fields, state.summaryFields)
                assertEquals(event.resultStatus ?: SEEDED.resultStatus, state.resultStatus)
            },
            "ended" to { event, state ->
                assertTrue(state.ended)
                assertNotNull(event.state)
                assertNotNull(event.resultStatus)
                assertEquals(event.state, state.state)
                assertEquals(event.resultStatus, state.resultStatus)
            }
        )

        /** Event kinds that legitimately change nothing, with the reason. */
        val EVENT_EXEMPTIONS: Map<String, String> = emptyMap()
    }
}
