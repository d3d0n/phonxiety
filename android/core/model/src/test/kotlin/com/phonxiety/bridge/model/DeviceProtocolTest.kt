package com.phonxiety.bridge.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun envelopesKeepTheirTypeUnderDefaultJson() {
        val plain = Json
        val auth = json.parseToJsonElement(plain.encodeToString(DeviceAuth.serializer(), DeviceAuth(token = "t"))).jsonObject
        val request = json.parseToJsonElement(
            plain.encodeToString(DeviceApiRequest.serializer(), DeviceApiRequest(id = "1", method = "GET", path = "/app/v1/info", appToken = "a"))
        ).jsonObject
        val header = json.parseToJsonElement(
            plain.encodeToString(AttachmentHeader.serializer(), AttachmentHeader(id = "1", appToken = "a", path = "/app/v1/x"))
        ).jsonObject

        assertEquals("auth", auth["type"]?.jsonPrimitive?.content)
        assertEquals("api", request["type"]?.jsonPrimitive?.content)
        assertEquals("attachment", header["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun v3HelloCarriesTheNewFields() {
        val hello = DeviceOutbound.Hello(
            device = "pixel",
            bootId = "android-boot-1",
            capabilities = DeviceCapabilities(callControl = true, audioCodecs = listOf("pcm", "opus")),
            activeCall = DeviceActiveCall("call-1", "active")
        )
        val encoded = json.parseToJsonElement(DeviceProtocol.encode(hello)).jsonObject

        assertEquals("hello", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("3", encoded["version"]?.jsonPrimitive?.content)
        assertEquals("1", encoded["app_api"]?.jsonPrimitive?.content)
        assertEquals("pcm", encoded["preferred_codec"]?.jsonPrimitive?.content)
        assertEquals("32000", encoded["max_bitrate"]?.jsonPrimitive?.content)
        assertEquals("call-1", encoded["active_call"]?.jsonObject?.get("call_id")?.jsonPrimitive?.content)
        assertTrue(encoded["control_caps"].toString().contains("set_prefs"))
        assertTrue(encoded["control_caps"].toString().contains("loopback_start"))
        assertTrue(encoded["control_caps"].toString().contains("tone_start"))
        assertTrue(encoded["control_caps"].toString().contains("diagnostics_snapshot"))
    }

    @Test
    fun commandsDecodeWithUnknownKeysIgnored() {
        val dial = DeviceProtocol.decodeCommand(
            """{"type":"command","id":"a","command":"dial","call_id":"c","destination":"+99890","extra":1}"""
        ) as DeviceCommand.Dial
        assertEquals("a", dial.id)
        assertEquals("c", dial.callId)
        assertEquals("+99890", dial.destination)

        val audio = DeviceProtocol.decodeCommand(
            """{"type":"command","id":"b","command":"start_audio","call_id":"c","epoch":3,"codec":"opus","bitrate":24000}"""
        ) as DeviceCommand.StartAudio
        assertEquals(3L, audio.epoch)
        assertEquals("opus", audio.codec)
        assertEquals(24_000, audio.bitrate)

        val prefs = DeviceProtocol.decodeCommand(
            """{"type":"command","id":"c","command":"set_prefs","codec":"pcm","bitrate":16000}"""
        ) as DeviceCommand.SetPrefs
        assertEquals("pcm", prefs.codec)

        val tone = DeviceProtocol.decodeCommand(
            """{"type":"command","id":"d","command":"tone_start","hz":440,"dbfs":-12.5}"""
        ) as DeviceCommand.ToneStart
        assertEquals(440, tone.hz)
        assertEquals(-12.5, tone.dbfs, 0.0)

        val loopback = DeviceProtocol.decodeCommand(
            """{"type":"command","id":"e","command":"loopback_stop"}"""
        ) as DeviceCommand.LoopbackStop
        assertNull(loopback.callId)

        val diagnostics = DeviceProtocol.decodeCommand(
            """{"type":"command","id":"f","command":"diagnostics_snapshot"}"""
        )
        assertTrue(diagnostics is DeviceCommand.Diagnostics)
    }

    @Test(expected = SerializationException::class)
    fun unknownCommandIsRejected() {
        DeviceProtocol.decodeCommand("""{"type":"command","id":"1","command":"launch_missiles"}""")
    }

    @Test
    fun eventsKeepTheirWireNames() {
        val route = json.parseToJsonElement(
            DeviceProtocol.encode(DeviceOutbound.LinkRoute(path = "relay", rttMs = 42))
        ).jsonObject
        assertEquals("link_route", route["type"]?.jsonPrimitive?.content)
        assertEquals("relay", route["path"]?.jsonPrimitive?.content)
        assertEquals("42", route["rtt_ms"]?.jsonPrimitive?.content)

        val levels = json.parseToJsonElement(
            DeviceProtocol.encode(DeviceOutbound.AudioLevels("c", -21.5f, -33.0f, 2))
        ).jsonObject
        assertEquals("audio_levels", levels["type"]?.jsonPrimitive?.content)
        assertEquals("-21.5", levels["rx_dbfs"]?.jsonPrimitive?.content)
        assertEquals("2", levels["queued"]?.jsonPrimitive?.content)

        val call = json.parseToJsonElement(
            DeviceProtocol.encode(
                DeviceOutbound.CallState("c", "ended", DisconnectCause(16, "NORMAL", "normal"))
            )
        ).jsonObject
        assertEquals("call_state", call["type"]?.jsonPrimitive?.content)
        assertEquals("16", call["disconnect_cause"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun responsesCarryDataOrError() {
        val ok = json.parseToJsonElement(
            DeviceProtocol.encode(
                DeviceOutbound.Response("1", true, buildJsonObject { put("pong", true) })
            )
        ).jsonObject
        assertEquals("true", ok["ok"]?.jsonPrimitive?.content)

        val failure = json.parseToJsonElement(
            DeviceProtocol.encode(DeviceOutbound.Response("2", false, error = "unknown_command"))
        ).jsonObject
        assertEquals("unknown_command", failure["error"]?.jsonPrimitive?.content)
        assertEquals("2", failure["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun apiResultDecodesSuccessAndError() {
        val ok = ControlEnvelope.decodeApiResult(
            """{"type":"api_result","id":"x","status":200,"result":{"daemon_version":"0.4.0"}}"""
        )
        assertEquals(200, ok.status)
        assertEquals("0.4.0", ok.result?.jsonObject?.get("daemon_version")?.jsonPrimitive?.content)

        val error = ControlEnvelope.decodeApiResult(
            """{"type":"api_result","id":"y","status":401,"error":{"code":"unauthorized","message":"bad token"}}"""
        )
        assertEquals("unauthorized", error.error?.code)
    }

    @Test
    fun appApiRequestUsesTheWireEnvelope() {
        val wire = Json {
            encodeDefaults = true
            explicitNulls = false
        }
            .encodeToString(
                DeviceApiRequest.serializer(),
                DeviceApiRequest(
                    id = "req-1",
                    method = "GET",
                    path = "/app/v1/info",
                    query = mapOf("x" to "y"),
                    appToken = "app-token"
                )
            )
        val encoded = json.parseToJsonElement(wire).jsonObject
        assertEquals("api", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("req-1", encoded["id"]?.jsonPrimitive?.content)
        assertEquals("app-token", encoded["app_token"]?.jsonPrimitive?.content)
        assertEquals("y", encoded["query"]?.jsonObject?.get("x")?.jsonPrimitive?.content)
    }

    @Test
    fun attachmentControlDecodesEndAndError() {
        val end = ControlEnvelope.decodeAttachmentControl(
            """{"type":"end","total":12,"sha256":"abc"}"""
        ) as AttachmentControl.End
        assertEquals(12L, end.total)
        assertEquals("abc", end.sha256)

        val error = ControlEnvelope.decodeAttachmentControl(
            """{"type":"error","code":"audio_unavailable","message":"none"}"""
        ) as AttachmentControl.Error
        assertEquals("audio_unavailable", error.code)
    }
}
