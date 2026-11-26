package com.phonxiety.bridge.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test-only shapes the current daemon cannot produce: an unknown future field and an
 * older daemon document.  Everything the daemon emits today is decoded and checked by
 * [com.phonxiety.bridge.data.DaemonContractTest] against the shared `contracts/app-api`
 * files, so no fixture here duplicates an exchange.
 */
class ApiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun infoIgnoresAnUnknownFutureField() {
        // A later daemon may add a key; the app must decode the rest of the document.
        val info = json.decodeFromString(
            DaemonInfo.serializer(),
            """{"daemon_version":"0.4.0","app_api":1,"an_unknown_future_field":{"ignored":true}}"""
        )
        assertEquals("0.4.0", info.daemonVersion)
        assertEquals(1, info.appApi)
    }

    @Test
    fun aDaemonThatPredatesTheTestCallProbesSaysNothing() {
        // Older daemons omit the probe flag; the app must read false, not fail.
        val older = json.decodeFromString(DaemonInfo.serializer(), """{"voice_controls":{"test_call":true}}""")
        assertTrue(older.voiceControls.testCall)
        assertFalse(older.voiceControls.testCallProbes)
    }

    @Test
    fun aTranscriptTurnThatCarriesTheFinalFlagStillDecodes() {
        // Long-poll events carry `final`; the paged and archived turns do not, so no
        // contract exchange samples it and the flag keeps its own case here.
        val turn = wire.decodeFromString(
            TranscriptTurn.serializer(),
            """{"sequence":1,"role":"assistant","text":"Hello","final":true}"""
        )
        assertEquals(1L, turn.sequence)
        assertEquals("assistant", turn.role)
        assertEquals(true, turn.finalTurn)
    }

    @Test
    fun aMarkWithoutItsTimeStillDecodes() {
        // A manual mark may carry no timestamp; the note is optional as well.
        val mark = wire.decodeFromString(CallMark.serializer(), """{"mark":"agreed"}""")
        assertEquals("agreed", mark.mark)
        assertNull(mark.at)
        assertNull(mark.note)
        val noted = wire.decodeFromString(CallMark.serializer(), """{"mark":"agreed","note":"Friday 15:00"}""")
        assertEquals("Friday 15:00", noted.note)
    }

    // The client's own encoder: defaults omitted, nulls omitted, exactly as DefaultDaemonApi sends.
    private val wire = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun aTestCallCarriesTheProbesItAsksFor() {
        // No contract exchange carries probes, because the daemon starts them only on a
        // live call; this pins the request the app's own encoder produces for them.
        val body = wire.encodeToJsonElement(
            TestCallRequest.serializer(),
            TestCallRequest(
                destination = "+998901234567",
                maxDurationSeconds = 45,
                loopback = true,
                tone = TestCallTone(hz = 1_000, dbfs = -20.0, seconds = 5)
            )
        )
        // The tone's own defaults are the daemon's, so only the length is on the wire here.
        assertEquals(
            """{"destination":"+998901234567","max_duration_s":45,"loopback":true,""" +
                """"tone":{"seconds":5}}""",
            body.toString()
        )
        val tuned = wire.encodeToJsonElement(
            TestCallRequest.serializer(),
            TestCallRequest(destination = "+998901234567", tone = TestCallTone(hz = 700, dbfs = -40.0, seconds = 10))
        )
        assertEquals(
            """{"destination":"+998901234567","tone":{"hz":700,"dbfs":-40.0,"seconds":10}}""",
            tuned.toString()
        )
    }
}
