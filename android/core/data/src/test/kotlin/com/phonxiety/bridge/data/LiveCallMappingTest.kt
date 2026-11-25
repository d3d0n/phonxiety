package com.phonxiety.bridge.data

import com.phonxiety.bridge.model.CallEvent
import com.phonxiety.bridge.model.CallPhase
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The vocabulary the live call screen has to translate. The JSON below is the exact shape the
 * daemon's events long-poll emits: `app_api._sync` forwards the `role` the controller stored in
 * `controller._transcript`, and the voice backends report `assistant` and `user` (`gemini.py`).
 */
class LiveCallMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun event(raw: String): CallEvent = json.decodeFromString(raw)

    @Test
    fun theDaemonsTurnRolesNameTheAgentAndTheFarEnd() {
        val assistant = event(
            """{"type":"transcript","revision":4,"sequence":303,"role":"assistant",""" +
                """"text":"Hi, I'm testing a call from the app.","final":true,""" +
                """"at":"2026-11-26T10:00:20+00:00"}"""
        )
        val user = event(
            """{"type":"transcript","revision":6,"sequence":304,"role":"user",""" +
                """"text":"One, two, three","final":true,"at":"2026-11-26T10:00:24+00:00"}"""
        )

        assertEquals(Speaker.AGENT, speakerOf(assistant.role))
        assertEquals(Speaker.FAR_END, speakerOf(user.role))
    }

    @Test
    fun partialTurnsUseTheSameWordsAsFinalOnes() {
        val partial = event(
            """{"type":"partial","revision":3,"role":"assistant","text":"Hi, I'm",""" +
                """"final":false,"at":"2026-11-26T10:00:19+00:00"}"""
        )

        assertEquals(Speaker.AGENT, speakerOf(partial.role))
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
