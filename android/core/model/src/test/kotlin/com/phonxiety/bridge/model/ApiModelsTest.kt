package com.phonxiety.bridge.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun infoMatchesTheDaemonDocument() {
        val info = json.decodeFromString(
            DaemonInfo.serializer(),
            """
            {
              "daemon_version": "0.4.0",
              "protocol": 3,
              "protocol_versions": [2, 3],
              "app_api": 1,
              "settings_schema": 2,
              "voice_backend": "codex",
              "voice_controls": {"instruction": true, "pause": true, "resume": true, "summarize": true,
                                 "dtmf": true, "marks": true, "hangup": true, "test_call": true,
                                 "loopback": true, "tone": false},
              "attachments": "stream",
              "capabilities": {"call_control": true},
              "control_caps": ["tone_start"],
              "phone": {"device": "pixel", "app_api": 1, "preferred_codec": "opus", "max_bitrate": 32000},
              "limits": {"max_result_bytes": 262144, "audio_page_bytes": 196608, "transcript_page": 200,
                         "events_wait_s": 25, "max_duration_s": 3600, "ring_timeout_s": 180,
                         "test_call_max_duration_s": 60, "calls_per_hour": 12},
              "mark_kinds": ["agreed", "refused"],
              "an_unknown_future_field": {"ignored": true}
            }
            """.trimIndent()
        )
        assertEquals("0.4.0", info.daemonVersion)
        assertEquals(3, info.protocol)
        assertEquals(1, info.appApi)
        assertTrue(info.voiceControls.loopback)
        assertEquals("opus", info.phone.preferredCodec)
        assertEquals(12, info.limits.callsPerHour)
        assertEquals(listOf("agreed", "refused"), info.markKinds)
    }

    @Test
    fun callsPageDecodesSummaries() {
        val page = json.decodeFromString(
            CallsPage.serializer(),
            """
            {"calls":[{"call_id":"c1","state":"ended","destination":"+99890","call_outcome":"answered",
                       "task_outcome":"succeeded","result_status":"ready","summary":"booked",
                       "created_at":1.5,"duration_s":30.0,"marks":2,"has_recording":true,"live":false}],
             "offset":0,"has_more":true,"next_before":1.5,"total":21}
            """.trimIndent()
        )
        assertEquals(1, page.calls.size)
        assertEquals("c1", page.calls[0].callId)
        assertEquals(2, page.calls[0].marks)
        assertTrue(page.hasMore)
        assertEquals(21, page.total)
    }

    @Test
    fun transcriptAndEventsDecode() {
        val transcript = json.decodeFromString(
            TranscriptPage.serializer(),
            """
            {"call_id":"c1","transcript":[{"sequence":7,"role":"agent","text":"hello","at":1.0,"final":true}],
             "next_cursor":7,"has_more":false,"current_partials":[{"role":"far_end","text":"hi"}],
             "transcript_complete":true}
            """.trimIndent()
        )
        assertEquals(7L, transcript.nextCursor)
        assertEquals(true, transcript.transcript[0].finalTurn)

        val events = json.decodeFromString(
            CallEventsPage.serializer(),
            """
            {"call_id":"c1","revision":3,"state":"active","call_outcome":"answered","result_status":"pending",
             "events":[{"type":"transcript","revision":2,"role":"agent","text":"hi","sequence":1,"final":true},
                       {"type":"marks","revision":3,"marks":[{"mark":"agreed","at":2.0}]}],
             "truncated":false}
            """.trimIndent()
        )
        assertEquals(2, events.events.size)
        assertEquals("agreed", events.events[1].marks?.get(0)?.mark)
    }

    @Test
    fun callSnapshotCoversLiveAndArchivedShapes() {
        val snapshot = json.decodeFromString(
            CallSnapshot.serializer(),
            """
            {"call_id":"c1","request_id":"r1","destination":"+99890","state":"active","call_outcome":"answered",
             "fields":{"heard":"yes"},"created_at":1.0,"connected_at":2.0,"ends_at":null,
             "transcript":[],"current_partials":[],"next_cursor":0,"has_more":false,
             "marks":[{"mark":"agreed"}],"far_end":"+99891","audio_codec":"opus",
             "live":{"muted":false},"counters":{"frames_out":10}}
            """.trimIndent()
        )
        assertEquals("c1", snapshot.callId)
        assertEquals("yes", snapshot.fields["heard"]?.toString()?.trim('"'))
        assertEquals(1, snapshot.marks.size)
        assertEquals("opus", snapshot.audioCodec)
        assertEquals("false", snapshot.live?.get("muted")?.toString())
    }

    @Test
    fun settingsAndDiagnosticsDecode() {
        val settings = json.decodeFromString(
            SettingsSnapshot.serializer(),
            """{"schema":2,"values":{"audio_codec":"opus"},"restart_required_fields":["audio_codec"],
                "choices":{"audio_codec":["pcm","opus"]}}"""
        )
        assertEquals("opus", settings.values["audio_codec"]?.toString()?.trim('"'))
        assertEquals(listOf("audio_codec"), settings.restartRequiredFields)

        val update = json.decodeFromString(
            SettingsUpdate.serializer(),
            """{"values":{"audio_codec":"pcm"},"changed":["audio_codec"],"restart_required":true,
                "restart_required_fields":["audio_codec"]}"""
        )
        assertTrue(update.restartRequired)
    }

    @Test
    fun doctorAndCompatDecode() {
        val doctor = json.decodeFromString(
            DoctorReport.serializer(),
            """{"status":"warn","checks":[{"name":"phone","status":"ok","detail":"pixel connected"}],
                "fixes":[{"fix":"state_dirs","description":"recreate","restart_required":false}],
                "checked_at":"2026-11-26T00:00:00+00:00"}"""
        )
        assertEquals("warn", doctor.status)
        assertEquals("state_dirs", doctor.fixes[0].fix)

        val compat = json.decodeFromString(
            CompatReport.serializer(),
            """{"daemon_version":"0.4.0","app_api":1,"protocol":3,"protocol_versions":[3],
                "client":{"app_api":1},"compatible":true,"notes":[]}"""
        )
        assertTrue(compat.compatible)
    }

    // The client's own encoder: defaults omitted, nulls omitted, exactly as DefaultDaemonApi sends.
    private val wire = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun aTestCallWithoutProbesCarriesNoProbeKeys() {
        val body = wire.encodeToJsonElement(
            TestCallRequest.serializer(),
            TestCallRequest(destination = "+998901234567", maxDurationSeconds = 30)
        )
        assertEquals(
            """{"destination":"+998901234567","max_duration_s":30}""",
            body.toString()
        )
    }

    @Test
    fun aTestCallCarriesTheProbesItAsksFor() {
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
        assertEquals("""{"destination":"+998901234567","tone":{"hz":700,"dbfs":-40.0,"seconds":10}}""", tuned.toString())
    }

    @Test
    fun infoAdvertisesTheTestCallProbes() {
        val info = json.decodeFromString(
            DaemonInfo.serializer(),
            """{"voice_controls":{"test_call":true,"test_call_probes":true,"loopback":true,"tone":true}}"""
        )
        assertTrue(info.voiceControls.testCallProbes)
        val older = json.decodeFromString(DaemonInfo.serializer(), """{"voice_controls":{"test_call":true}}""")
        assertFalse("a daemon that predates the probes says nothing", older.voiceControls.testCallProbes)
    }
}
