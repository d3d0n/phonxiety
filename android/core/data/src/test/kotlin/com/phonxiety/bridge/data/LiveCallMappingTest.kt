package com.phonxiety.bridge.data

import com.phonxiety.bridge.model.CallEvent
import com.phonxiety.bridge.model.CallPhase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The vocabulary the live call screen has to translate, read from both shared
 * `contracts/app-api/call_events_*.json` pages: the daemon's events long-poll
 * (`app_api._sync`) forwards the `role` the controller stored from the voice backend.
 * Every event kind's own effect is checked by the live-call module's contract test.
 */
class LiveCallMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val events: List<CallEvent> by lazy {
        listOf("call_events_live.json", "call_events_ended.json").flatMap { file ->
            val text = javaClass.classLoader!!.getResourceAsStream("app-api/$file")!!
                .bufferedReader()
                .use { it.readText() }
            val body = Json.parseToJsonElement(text).jsonObject
                .getValue("response").jsonObject
                .getValue("body").jsonObject
            body.getValue("events").jsonArray.map { json.decodeFromJsonElement(CallEvent.serializer(), it) }
        }
    }

    private fun event(type: String, role: String? = null): CallEvent = events.first { it.type == type && (role == null || it.role == role) }

    @Test
    fun theDaemonsTurnRolesNameTheAgentAndTheFarEnd() {
        assertEquals(Speaker.AGENT, speakerOf(event("transcript", "assistant").role))
        assertEquals(Speaker.FAR_END, speakerOf(event("transcript", "user").role))
    }

    @Test
    fun partialTurnsUseTheSameWordsAsFinalOnes() {
        assertEquals(
            speakerOf(event("transcript", "user").role),
            speakerOf(event("partial").role)
        )
    }

    @Test
    fun olderRecordsAndUnknownSpeakersStillMap() {
        assertEquals(Speaker.AGENT, speakerOf("agent"))
        assertEquals(Speaker.FAR_END, speakerOf("far_end"))
        assertEquals(Speaker.FAR_END, speakerOf(" User "))
        assertEquals(Speaker.OTHER, speakerOf("supervisor"))
        assertEquals(Speaker.OTHER, speakerOf(null))
    }

    @Test
    fun theLocalPhaseSpeaksTheDaemonsWords() {
        assertEquals("dialing", daemonCallState(CallPhase.DIALING))
        assertEquals("active", daemonCallState(CallPhase.ACTIVE))
        assertEquals("ending", daemonCallState(CallPhase.ENDING))
        assertEquals("ended", daemonCallState(CallPhase.ENDED))
        assertNull(daemonCallState(null))
    }

    @Test
    fun theEngineCountsBitsAndTheScreenCountsKilobits() {
        assertEquals(32, bitrateKbps(32_000))
        assertEquals(6, bitrateKbps(6_000))
        assertNull(bitrateKbps(null))
    }
}
