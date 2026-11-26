package com.phonxiety.bridge.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `@Serializable` result models for the daemon's `/app/v1` API.  Every field
 * the daemon may omit is nullable or defaulted; unknown daemon fields are
 * ignored by the decoding [kotlinx.serialization.json.Json].
 */

@Serializable
data class DaemonInfo(
    @SerialName("daemon_version") val daemonVersion: String? = null,
    val protocol: Int? = null,
    @SerialName("protocol_versions") val protocolVersions: List<Int> = emptyList(),
    @SerialName("app_api") val appApi: Int? = null,
    @SerialName("settings_schema") val settingsSchema: Int? = null,
    @SerialName("voice_backend") val voiceBackend: String? = null,
    @SerialName("voice_controls") val voiceControls: VoiceControls = VoiceControls(),
    val attachments: AttachmentMode? = null,
    val capabilities: JsonObject? = null,
    @SerialName("control_caps") val controlCaps: List<String> = emptyList(),
    val phone: PhoneInfo = PhoneInfo(),
    val limits: ApiLimits = ApiLimits(),
    @SerialName("mark_kinds") val markKinds: List<String> = emptyList(),
    val connection: ConnectionInfo? = null
)

/**
 * The other addresses this daemon can be reached at, as it reports them on
 * `/app/v1/info`: its address-listener port, the certificate pin an address
 * link needs, and the addresses it knows it has — LAN first, then its tailnet
 * addresses when the Tailscale CLI is present on the daemon host.
 */
@Serializable
data class ConnectionInfo(
    @SerialName("device_port") val devicePort: Int? = null,
    val certificate: String? = null,
    val lan: List<String> = emptyList(),
    val tailscale: List<String> = emptyList()
)

/** How bulk data travels: its own stream, or base64 pages on the shared link. */
@Serializable
enum class AttachmentMode {
    @SerialName("stream")
    STREAM,

    @SerialName("paged")
    PAGED
}

@Serializable
data class VoiceControls(
    val instruction: Boolean = false,
    val pause: Boolean = false,
    val resume: Boolean = false,
    val summarize: Boolean = false,
    val dtmf: Boolean = false,
    val marks: Boolean = false,
    val hangup: Boolean = false,
    @SerialName("test_call") val testCall: Boolean = false,
    /** The daemon starts and stops a test call's loopback and tone itself when asked. */
    @SerialName("test_call_probes") val testCallProbes: Boolean = false,
    val loopback: Boolean = false,
    val tone: Boolean = false
)

@Serializable
data class PhoneInfo(
    val device: String? = null,
    @SerialName("app_api") val appApi: Int? = null,
    @SerialName("preferred_codec") val preferredCodec: String? = null,
    @SerialName("max_bitrate") val maxBitrate: Int? = null
)

@Serializable
data class ApiLimits(
    @SerialName("max_result_bytes") val maxResultBytes: Int? = null,
    @SerialName("audio_page_bytes") val audioPageBytes: Int? = null,
    @SerialName("transcript_page") val transcriptPage: Int? = null,
    @SerialName("events_wait_s") val eventsWaitSeconds: Int? = null,
    @SerialName("max_duration_s") val maxDurationSeconds: Int? = null,
    @SerialName("ring_timeout_s") val ringTimeoutSeconds: Int? = null,
    @SerialName("test_call_max_duration_s") val testCallMaxDurationSeconds: Int? = null,
    @SerialName("calls_per_hour") val callsPerHour: Int? = null
)

@Serializable
data class DaemonStatus(
    val daemon: JsonElement? = null,
    @SerialName("uptime_s") val uptimeSeconds: Double? = null,
    val phone: JsonElement? = null,
    @SerialName("active_calls") val activeCalls: List<JsonObject> = emptyList(),
    val link: StatusLink = StatusLink()
)

@Serializable
data class StatusLink(
    @SerialName("endpoint_id") val endpointId: String? = null,
    @SerialName("address_port") val addressPort: Int? = null,
    val certificate: String? = null,
    @SerialName("iroh_available") val irohAvailable: Boolean = false
)

@Serializable
data class LinkTestResult(
    val id: String? = null,
    val at: String? = null,
    val codec: String? = null,
    val transport: String? = null,
    val peer: String? = null,
    val route: String? = null,
    @SerialName("route_after") val routeAfter: String? = null,
    val seconds: Double? = null,
    @SerialName("frame_bytes") val frameBytes: Int? = null,
    val frames: LinkTestFrames = LinkTestFrames(),
    val p50: Long? = null,
    val p95: Long? = null,
    val max: Long? = null,
    val loss: Double? = null,
    val reorder: Int? = null,
    @SerialName("throughput_bps") val throughputBps: Long? = null,
    @SerialName("ping_ms") val pingMs: Spread? = null,
    @SerialName("send_stall_ms") val sendStallMs: Long? = null,
    val healthy: Boolean = false,
    val verdict: String? = null
)

@Serializable
data class LinkTestFrames(val sent: Int = 0, val echoed: Int = 0)

@Serializable
data class Spread(val median: Long? = null, val p95: Long? = null, val max: Long? = null)

@Serializable
data class LinkTests(val tests: List<LinkTestResult> = emptyList())

@Serializable
data class ProbeResult(
    val candidates: List<ProbeCandidate> = emptyList(),
    @SerialName("checked_at") val checkedAt: String? = null
)

@Serializable
data class ProbeCandidate(
    val name: String,
    val available: Boolean = false,
    val reachable: Boolean = false,
    @SerialName("rtt_ms") val rttMs: Double? = null,
    val detail: String? = null,
    val reason: String? = null,
    val address: String? = null
)

// ---------------------------------------------------------------------------
// Calls
// ---------------------------------------------------------------------------

@Serializable
data class CallSummary(
    @SerialName("call_id") val callId: String,
    val state: String? = null,
    val destination: String? = null,
    val goal: String? = null,
    @SerialName("call_outcome") val callOutcome: String? = null,
    @SerialName("task_outcome") val taskOutcome: String? = null,
    @SerialName("result_status") val resultStatus: String? = null,
    val summary: String? = null,
    val error: String? = null,
    @SerialName("far_end") val farEnd: String? = null,
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("connected_at") val connectedAt: Double? = null,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("duration_s") val durationSeconds: Double? = null,
    val marks: Int = 0,
    @SerialName("has_recording") val hasRecording: Boolean = false,
    val live: Boolean = false
)

@Serializable
data class CallsPage(
    val calls: List<CallSummary> = emptyList(),
    val offset: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_before") val nextBefore: Double? = null,
    val total: Int = 0
)

@Serializable
data class CallSnapshot(
    @SerialName("call_id") val callId: String,
    @SerialName("request_id") val requestId: String? = null,
    val destination: String? = null,
    val state: String? = null,
    @SerialName("call_outcome") val callOutcome: String? = null,
    @SerialName("task_outcome") val taskOutcome: String? = null,
    @SerialName("result_status") val resultStatus: String? = null,
    val summary: String? = null,
    val fields: JsonObject = JsonObject(emptyMap()),
    val error: String? = null,
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("connected_at") val connectedAt: Double? = null,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("disconnect_cause") val disconnectCause: JsonObject? = null,
    val transcript: List<TranscriptTurn> = emptyList(),
    @SerialName("current_partials") val currentPartials: List<PartialTurn> = emptyList(),
    @SerialName("next_cursor") val nextCursor: Long? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("transcript_complete") val transcriptComplete: Boolean = false,
    val request: JsonObject? = null,
    val marks: List<CallMark> = emptyList(),
    @SerialName("far_end") val farEnd: String? = null,
    @SerialName("audio_codec") val audioCodec: String? = null,
    val live: JsonObject? = null,
    val counters: JsonObject? = null,
    @SerialName("confirmation_id") val confirmationId: String? = null,
    val profile: String? = null,
    /** The archive's recording descriptor (`file`, `compressed.file`); absent for a live call. */
    val recording: JsonObject? = null
)

@Serializable
data class TranscriptTurn(
    val sequence: Long = 0,
    val role: String? = null,
    val text: String? = null,
    val at: Double? = null,
    @SerialName("final") val finalTurn: Boolean? = null
)

@Serializable
data class PartialTurn(
    val role: String? = null,
    val text: String? = null,
    val at: Double? = null
)

@Serializable
data class CallMark(
    val mark: String,
    val at: Double? = null,
    val note: String? = null
)

@Serializable
data class TranscriptPage(
    @SerialName("call_id") val callId: String,
    val transcript: List<TranscriptTurn> = emptyList(),
    @SerialName("next_cursor") val nextCursor: Long? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("current_partials") val currentPartials: List<PartialTurn> = emptyList(),
    @SerialName("transcript_complete") val transcriptComplete: Boolean = false
)

@Serializable
data class CallEventsPage(
    @SerialName("call_id") val callId: String,
    val revision: Long = 0,
    val state: String? = null,
    @SerialName("call_outcome") val callOutcome: String? = null,
    @SerialName("result_status") val resultStatus: String? = null,
    val events: List<CallEvent> = emptyList(),
    val truncated: Boolean = false
)

/** One live-call delta; every event type shares this shape. */
@Serializable
data class CallEvent(
    val type: String,
    val revision: Long = 0,
    val state: String? = null,
    @SerialName("call_outcome") val callOutcome: String? = null,
    @SerialName("result_status") val resultStatus: String? = null,
    val at: String? = null,
    val role: String? = null,
    val text: String? = null,
    val sequence: Long? = null,
    @SerialName("final") val finalTurn: Boolean? = null,
    val muted: Boolean? = null,
    val activity: String? = null,
    val marks: List<CallMark>? = null,
    @SerialName("task_outcome") val taskOutcome: String? = null,
    val summary: String? = null,
    val fields: JsonObject? = null
)

@Serializable
data class PlaceCallRequest(
    val destination: String,
    val goal: String,
    val context: String = "",
    val constraints: String = "",
    @SerialName("max_duration_s") val maxDurationSeconds: Int? = null,
    @SerialName("ring_timeout_s") val ringTimeoutSeconds: Int? = null,
    @SerialName("result_fields") val resultFields: List<String> = emptyList(),
    @SerialName("phone_account") val phoneAccount: String? = null,
    @SerialName("confirmation_id") val confirmationId: String? = null,
    @SerialName("request_id") val requestId: String? = null
)

/**
 * A test call, with the audio probes the daemon may start on its own once the line is up.
 *
 * [loopback] and [tone] stay null when the caller does not ask for them, so a request without
 * probes is byte-for-byte the one every daemon already understands: a daemon that predates the
 * probes sees no new keys at all, and one that has them is told exactly what to start.
 */
@Serializable
data class TestCallRequest(
    val destination: String,
    @SerialName("max_duration_s") val maxDurationSeconds: Int? = null,
    @SerialName("phone_account") val phoneAccount: String? = null,
    val loopback: Boolean? = null,
    val tone: TestCallTone? = null
)

/** The tone a test call plays to the far end: [seconds] is required and valid from 1 to 10. */
@Serializable
data class TestCallTone(
    val hz: Int = 1_000,
    val dbfs: Double = -20.0,
    val seconds: Int
)

@Serializable
data class ValidationIssue(val location: String, val message: String)

@Serializable
data class ValidationResult(
    val valid: Boolean = false,
    val errors: List<ValidationIssue> = emptyList(),
    val warnings: List<String> = emptyList(),
    val request: JsonObject? = null
)

@Serializable
data class InstructionResult(
    @SerialName("call_id") val callId: String? = null,
    val delivered: Boolean = false
)

@Serializable
data class PauseResult(
    @SerialName("call_id") val callId: String? = null,
    val muted: Boolean = false,
    val state: String? = null,
    val paused: Boolean? = null
)

@Serializable
data class SummaryResult(
    @SerialName("call_id") val callId: String? = null,
    @SerialName("task_outcome") val taskOutcome: String? = null,
    val summary: String? = null,
    val fields: JsonObject? = null
)

@Serializable
data class DtmfResult(
    @SerialName("call_id") val callId: String? = null,
    val digits: String? = null,
    val result: JsonObject? = null
)

@Serializable
data class MarksResponse(
    @SerialName("call_id") val callId: String? = null,
    val marks: List<CallMark> = emptyList()
)

@Serializable
data class DeleteCallResult(
    @SerialName("call_id") val callId: String? = null,
    val deleted: List<String> = emptyList()
)

// ---------------------------------------------------------------------------
// Settings, lab, doctor, diagnostics, updates, pairing
// ---------------------------------------------------------------------------

@Serializable
data class SettingsSnapshot(
    val schema: Int? = null,
    val values: JsonObject = JsonObject(emptyMap()),
    @SerialName("restart_required_fields") val restartRequiredFields: List<String> = emptyList(),
    val choices: JsonObject? = null
)

@Serializable
data class SettingsUpdate(
    val values: JsonObject = JsonObject(emptyMap()),
    val changed: List<String> = emptyList(),
    @SerialName("restart_required") val restartRequired: Boolean = false,
    @SerialName("restart_required_fields") val restartRequiredFields: List<String> = emptyList()
)

@Serializable
data class LoopbackResult(
    val enabled: Boolean = false,
    val result: JsonObject? = null
)

@Serializable
data class ToneResult(
    val started: Boolean = false,
    val hz: Int? = null,
    val dbfs: Double? = null,
    @SerialName("stop_after_s") val stopAfterSeconds: Double? = null,
    val result: JsonObject? = null
)

@Serializable
data class DoctorReport(
    val status: String? = null,
    val checks: List<DoctorCheck> = emptyList(),
    val fixes: List<DoctorFix> = emptyList(),
    @SerialName("checked_at") val checkedAt: String? = null
)

@Serializable
data class DoctorCheck(val name: String, val status: String? = null, val detail: String? = null)

@Serializable
data class DoctorFix(
    val fix: String,
    val description: String? = null,
    @SerialName("restart_required") val restartRequired: Boolean = false
)

@Serializable
data class DoctorFixResult(
    val applied: List<DoctorApplied> = emptyList(),
    @SerialName("restart_required") val restartRequired: Boolean = false
)

@Serializable
data class DoctorApplied(val fix: String, val detail: String? = null)

@Serializable
data class DiagnosticsBundle(
    @SerialName("bundle_id") val bundleId: String,
    val size: Long = 0,
    @SerialName("created_at") val createdAt: String? = null,
    val attachment: String? = null
)

@Serializable
data class CompatReport(
    @SerialName("daemon_version") val daemonVersion: String? = null,
    @SerialName("app_api") val appApi: Int? = null,
    val protocol: Int? = null,
    @SerialName("protocol_versions") val protocolVersions: List<Int> = emptyList(),
    val client: JsonObject? = null,
    val compatible: Boolean = false,
    val notes: List<String> = emptyList()
)

@Serializable
data class RotateResult(
    @SerialName("app_token") val appToken: String? = null,
    @SerialName("device_token") val deviceToken: String? = null,
    @SerialName("restart_required") val restartRequired: Boolean = false
)

/** One paged base64 payload from a WebSocket link. */
@Serializable
data class AttachmentPage(
    val total: Long = 0,
    val offset: Int = 0,
    val limit: Int = 0,
    val data: String = "",
    @SerialName("bundle_id") val bundleId: String? = null,
    val format: String? = null
)
