@file:OptIn(ExperimentalSerializationApi::class)

package com.phonxiety.bridge.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/** Device protocol version this build speaks. */
const val DEVICE_PROTOCOL_VERSION = 3

/** App API version this build expects from the daemon. */
const val APP_API_VERSION = 1

/** Commands this phone accepts, advertised in `hello.control_caps`. */
val DEVICE_CONTROL_CAPS: List<String> = listOf(
    "set_prefs",
    "loopback_start",
    "loopback_stop",
    "tone_start",
    "tone_stop",
    "diagnostics_snapshot"
)

@Serializable
data class DeviceCapabilities(
    @SerialName("call_control") val callControl: Boolean = false,
    @SerialName("framework_audio") val frameworkAudio: Boolean = false,
    val busy: Boolean = false,
    @SerialName("link_test") val linkTest: Boolean = true,
    @SerialName("adb_tunnel") val adbTunnel: Boolean = false,
    @SerialName("audio_codecs") val audioCodecs: List<String> = listOf("pcm", "opus")
)

@Serializable
data class DeviceActiveCall(
    @SerialName("call_id") val callId: String,
    val state: String
)

// ---------------------------------------------------------------------------
// Control messages: daemon -> phone
// ---------------------------------------------------------------------------

/**
 * One command from the daemon.  The `command` field selects the subtype, so
 * the wire keeps its existing shape (`type`, `id`, `command`, fields).
 */
@Serializable
sealed class DeviceCommand {
    abstract val id: String

    @Serializable
    @SerialName("dial")
    data class Dial(
        override val id: String,
        @SerialName("call_id") val callId: String,
        val destination: String,
        @SerialName("phone_account") val phoneAccount: String? = null
    ) : DeviceCommand()

    @Serializable
    @SerialName("hangup")
    data class Hangup(
        override val id: String,
        @SerialName("call_id") val callId: String
    ) : DeviceCommand()

    @Serializable
    @SerialName("start_audio")
    data class StartAudio(
        override val id: String,
        @SerialName("call_id") val callId: String,
        val epoch: Long,
        val codec: String? = null,
        val bitrate: Int? = null
    ) : DeviceCommand()

    @Serializable
    @SerialName("flush")
    data class Flush(
        override val id: String,
        @SerialName("call_id") val callId: String,
        val epoch: Long
    ) : DeviceCommand()

    @Serializable
    @SerialName("dtmf")
    data class Dtmf(
        override val id: String,
        @SerialName("call_id") val callId: String,
        @SerialName("action_generation") val actionGeneration: Long,
        val digits: String,
        @SerialName("tone_ms") val toneMs: Int = 120,
        @SerialName("gap_ms") val gapMs: Int = 80
    ) : DeviceCommand()

    @Serializable
    @SerialName("cancel_actions")
    data class CancelActions(
        override val id: String,
        @SerialName("call_id") val callId: String,
        @SerialName("action_generation") val actionGeneration: Long
    ) : DeviceCommand()

    @Serializable
    @SerialName("ping")
    data class Ping(override val id: String) : DeviceCommand()

    @Serializable
    @SerialName("set_prefs")
    data class SetPrefs(
        override val id: String,
        val codec: String? = null,
        val bitrate: Int? = null
    ) : DeviceCommand()

    @Serializable
    @SerialName("loopback_start")
    data class LoopbackStart(
        override val id: String,
        @SerialName("call_id") val callId: String? = null
    ) : DeviceCommand()

    @Serializable
    @SerialName("loopback_stop")
    data class LoopbackStop(
        override val id: String,
        @SerialName("call_id") val callId: String? = null
    ) : DeviceCommand()

    @Serializable
    @SerialName("tone_start")
    data class ToneStart(
        override val id: String,
        @SerialName("call_id") val callId: String? = null,
        val hz: Int = 1_000,
        val dbfs: Double = -20.0
    ) : DeviceCommand()

    @Serializable
    @SerialName("tone_stop")
    data class ToneStop(
        override val id: String,
        @SerialName("call_id") val callId: String? = null
    ) : DeviceCommand()

    @Serializable
    @SerialName("diagnostics_snapshot")
    data class Diagnostics(override val id: String) : DeviceCommand()
}

// ---------------------------------------------------------------------------
// Control messages: phone -> daemon
// ---------------------------------------------------------------------------

@Serializable
sealed class DeviceOutbound {
    @Serializable
    @SerialName("hello")
    data class Hello(
        val version: Int = DEVICE_PROTOCOL_VERSION,
        val device: String,
        @SerialName("boot_id") val bootId: String,
        val capabilities: DeviceCapabilities,
        @SerialName("active_call") val activeCall: DeviceActiveCall? = null,
        @SerialName("app_api") val appApi: Int = APP_API_VERSION,
        @SerialName("preferred_codec") val preferredCodec: String = "pcm",
        @SerialName("max_bitrate") val maxBitrate: Int = 32_000,
        @SerialName("control_caps") val controlCaps: List<String> = DEVICE_CONTROL_CAPS
    ) : DeviceOutbound()

    @Serializable
    @SerialName("response")
    data class Response(
        val id: String,
        val ok: Boolean,
        val data: JsonObject? = null,
        val error: String? = null
    ) : DeviceOutbound()

    @Serializable
    @SerialName("call_state")
    data class CallState(
        @SerialName("call_id") val callId: String,
        val state: String,
        @SerialName("disconnect_cause") val disconnectCause: DisconnectCause? = null
    ) : DeviceOutbound()

    @Serializable
    @SerialName("playback")
    data class Playback(
        @SerialName("call_id") val callId: String,
        val epoch: Long,
        @SerialName("played_samples") val playedSamples: Long,
        @SerialName("queued_samples") val queuedSamples: Long,
        @SerialName("drained_mark") val drainedMark: Long? = null
    ) : DeviceOutbound()

    @Serializable
    @SerialName("audio_error")
    data class AudioError(
        @SerialName("call_id") val callId: String,
        val error: String
    ) : DeviceOutbound()

    @Serializable
    @SerialName("link_route")
    data class LinkRoute(
        val path: String,
        @SerialName("rtt_ms") val rttMs: Long? = null
    ) : DeviceOutbound()

    /** The phone's codec/bitrate preference changed while the link stays up. */
    @Serializable
    @SerialName("set_prefs")
    data class SetPrefs(
        @SerialName("preferred_codec") val preferredCodec: String,
        @SerialName("max_bitrate") val maxBitrate: Int
    ) : DeviceOutbound()

    @Serializable
    @SerialName("audio_levels")
    data class AudioLevels(
        @SerialName("call_id") val callId: String,
        @SerialName("rx_dbfs") val rxDbfs: Float? = null,
        @SerialName("tx_dbfs") val txDbfs: Float? = null,
        val queued: Long = 0
    ) : DeviceOutbound()
}

@Serializable
data class DisconnectCause(
    val code: Int = 0,
    val label: String = "",
    val reason: String = ""
)

@Serializable
data class DeviceAuth(
    @EncodeDefault @SerialName("type") val type: String = "auth",
    val token: String
)

// ---------------------------------------------------------------------------
// App API envelope
// ---------------------------------------------------------------------------

@Serializable
data class DeviceApiRequest(
    val id: String,
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: JsonObject? = null,
    @SerialName("app_token") val appToken: String,
    @EncodeDefault @SerialName("type") val type: String = "api"
)

@Serializable
data class DeviceApiError(
    val code: String = "error",
    val message: String = ""
)

@Serializable
data class DeviceApiResult(
    val id: String,
    val status: Int,
    val result: kotlinx.serialization.json.JsonElement? = null,
    val error: DeviceApiError? = null,
    @EncodeDefault @SerialName("type") val type: String = "api_result"
)

// ---------------------------------------------------------------------------
// Attachments
// ---------------------------------------------------------------------------

@Serializable
data class AttachmentHeader(
    @EncodeDefault @SerialName("type") val type: String = "attachment",
    val id: String,
    @SerialName("app_token") val appToken: String,
    val path: String,
    val query: Map<String, String> = emptyMap()
)

@Serializable
sealed class AttachmentControl {
    @Serializable
    @SerialName("end")
    data class End(val total: Long, val sha256: String) : AttachmentControl()

    @Serializable
    @SerialName("error")
    data class Error(val code: String, val message: String) : AttachmentControl()
}

sealed interface AttachmentOutcome {
    data class Complete(val total: Long, val sha256: String) : AttachmentOutcome

    data class Failed(val code: String, val message: String) : AttachmentOutcome

    data object Unsupported : AttachmentOutcome
}

// ---------------------------------------------------------------------------
// Diagnostics
// ---------------------------------------------------------------------------

@Serializable
data class DiagnosticsSnapshot(
    val engine: EngineDiagnostics,
    val audio: AudioDiagnostics,
    val link: LinkDiagnostics,
    /** Process deaths the system recorded, newest first and bounded. */
    val crashes: List<CrashDiagnostics> = emptyList()
)

/**
 * One process death as `ApplicationExitInfo` reported it.  `memory_limiter` is set when the
 * description names Android's memory limiter (`MemoryLimiter:AnonSwap`), which is the case the
 * Doctor has to explain instead of treating as a generic crash.  `trace` carries the ANR trace or
 * the uncaught-exception stack when one was captured.
 */
@Serializable
data class CrashDiagnostics(
    @SerialName("at") val atEpochMs: Long,
    val kind: String,
    val reason: String,
    val description: String? = null,
    @SerialName("memory_limiter") val memoryLimiter: Boolean = false,
    val trace: String? = null
)

@Serializable
data class EngineDiagnostics(
    val running: Boolean,
    @SerialName("call_id") val callId: String? = null,
    val state: String? = null,
    val epoch: Long = -1,
    @SerialName("uptime_s") val uptimeSeconds: Long = 0,
    @SerialName("active_calls") val activeCalls: Int = 0
)

@Serializable
data class AudioDiagnostics(
    val codec: String? = null,
    val bitrate: Int? = null,
    @SerialName("captured_frames") val capturedFrames: Long = 0,
    @SerialName("unsent_frames") val unsentFrames: Long = 0,
    @SerialName("host_frames") val hostFrames: Long = 0,
    @SerialName("written_frames") val writtenFrames: Long = 0,
    @SerialName("overflow_samples") val overflowSamples: Long = 0,
    @SerialName("rx_dbfs") val rxDbfs: Float? = null,
    @SerialName("tx_dbfs") val txDbfs: Float? = null
)

@Serializable
data class LinkDiagnostics(
    val up: Boolean,
    val path: String? = null,
    @SerialName("rtt_ms") val rttMs: Long? = null,
    @SerialName("backlog_bytes") val backlogBytes: Long = 0,
    val reconnects: Int = 0
)

// ---------------------------------------------------------------------------
// Codec
// ---------------------------------------------------------------------------

/**
 * JSON settings for the device protocol.  Inbound messages ignore fields this
 * build does not know; unknown commands surface as [SerializationException] so
 * the engine can answer `unknown_command`.
 */
object DeviceProtocol {
    private val commandJson = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "command"
        encodeDefaults = true
        explicitNulls = false
    }

    private val outboundJson = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = false
    }

    fun decodeCommand(text: String): DeviceCommand = commandJson.decodeFromString(DeviceCommand.serializer(), text)

    fun encodeCommand(command: DeviceCommand): String = commandJson.encodeToString(DeviceCommand.serializer(), command)

    fun encode(message: DeviceOutbound): String = outboundJson.encodeToString(DeviceOutbound.serializer(), message)
}

/** Envelope decoding for the parts of a control message the protocol switches on. */
object ControlEnvelope {
    private val json = Json { ignoreUnknownKeys = true }

    fun type(text: String): String? = try {
        json.parseToJsonElement(text).let { element ->
            (element as? JsonObject)?.get("type")?.let { value ->
                (value as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            }
        }
    } catch (_: Exception) {
        null
    }

    /** The token in a daemon `{"type":"app_access","app_token":...}` message, when well-formed. */
    fun appAccessToken(text: String): String? = try {
        (json.parseToJsonElement(text) as? JsonObject)
            ?.get("app_token")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            ?.takeIf { it.isNotBlank() && it.length <= 256 }
    } catch (_: Exception) {
        null
    }

    fun id(text: String): String? = try {
        json.parseToJsonElement(text).let { element ->
            (element as? JsonObject)?.get("id")?.let { value ->
                (value as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            }
        }
    } catch (_: Exception) {
        null
    }

    fun decodeApiResult(text: String): DeviceApiResult = json.decodeFromString(DeviceApiResult.serializer(), text)

    fun decodeAttachmentControl(text: String): AttachmentControl? = try {
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }.decodeFromString(AttachmentControl.serializer(), text)
    } catch (_: SerializationException) {
        null
    }
}
