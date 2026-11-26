package com.phonxiety.bridge.feature.calls

import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.model.CallEventsPage
import com.phonxiety.bridge.model.CallMark
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.CallSummary
import com.phonxiety.bridge.model.CallsPage
import com.phonxiety.bridge.model.CompatReport
import com.phonxiety.bridge.model.DaemonInfo
import com.phonxiety.bridge.model.DaemonStatus
import com.phonxiety.bridge.model.DeleteCallResult
import com.phonxiety.bridge.model.DiagnosticsBundle
import com.phonxiety.bridge.model.DoctorFixResult
import com.phonxiety.bridge.model.DoctorReport
import com.phonxiety.bridge.model.DtmfResult
import com.phonxiety.bridge.model.InstructionResult
import com.phonxiety.bridge.model.LinkTestResult
import com.phonxiety.bridge.model.LinkTests
import com.phonxiety.bridge.model.LoopbackResult
import com.phonxiety.bridge.model.MarksResponse
import com.phonxiety.bridge.model.PauseResult
import com.phonxiety.bridge.model.PlaceCallRequest
import com.phonxiety.bridge.model.ProbeResult
import com.phonxiety.bridge.model.RotateResult
import com.phonxiety.bridge.model.SettingsSnapshot
import com.phonxiety.bridge.model.SettingsUpdate
import com.phonxiety.bridge.model.SummaryResult
import com.phonxiety.bridge.model.TestCallRequest
import com.phonxiety.bridge.model.ToneResult
import com.phonxiety.bridge.model.TranscriptPage
import com.phonxiety.bridge.model.TranscriptTurn
import com.phonxiety.bridge.model.ValidationResult
import java.io.OutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A daemon whose answers the test writes: pages by `before`, transcript by `cursor`, and a flag
 * that makes every read fail so the cache is exercised.
 */
class FakeCallsApi : DaemonApi {
    var offline = false
    var pages = mutableListOf<ApiOutcome<CallsPage>>()
    var beforeRequests = mutableListOf<Double?>()
    var detail: ApiOutcome<CallSnapshot>? = null
    var transcriptPages = mutableListOf<ApiOutcome<TranscriptPage>>()
    var transcriptCursors = mutableListOf<Long?>()
    var deleteResult: ApiOutcome<DeleteCallResult> = ApiOutcome.Ok(200, DeleteCallResult())
    var marksResult: ApiOutcome<MarksResponse> = ApiOutcome.Ok(200, MarksResponse())
    var markRequests = mutableListOf<String>()
    var audioBytes: ByteArray = byteArrayOf(1, 2, 3, 4)

    override suspend fun calls(limit: Int?, offset: Int?, before: Double?, outcome: String?, farEnd: String?): ApiOutcome<CallsPage> {
        beforeRequests += before
        if (offline) return ApiOutcome.Failure(503, "link_down", "the bridge service is not running")
        return pages.removeFirstOrNull() ?: ApiOutcome.Ok(200, CallsPage())
    }

    override suspend fun call(callId: String): ApiOutcome<CallSnapshot> {
        if (offline) return ApiOutcome.Failure(503, "link_down", "the bridge service is not running")
        return detail ?: ApiOutcome.Failure(404, "call_not_found", "no call $callId")
    }

    override suspend fun transcript(callId: String, cursor: Long?, limit: Int?): ApiOutcome<TranscriptPage> {
        transcriptCursors += cursor
        if (offline) return ApiOutcome.Failure(503, "link_down", "the bridge service is not running")
        return transcriptPages.removeFirstOrNull() ?: ApiOutcome.Ok(200, TranscriptPage(callId = callId))
    }

    override suspend fun deleteCall(callId: String): ApiOutcome<DeleteCallResult> {
        if (offline) return ApiOutcome.Failure(503, "link_down", "the bridge service is not running")
        return deleteResult
    }

    override suspend fun addMark(callId: String, mark: String, note: String?): ApiOutcome<MarksResponse> {
        markRequests += mark
        return marksResult
    }

    override suspend fun downloadAudio(callId: String, format: String, sink: OutputStream): ApiResult {
        if (offline) return ApiResult.Failure(503, "link_down", "the bridge service is not running")
        sink.write(audioBytes)
        return ApiResult.Success(200, JsonObject(mapOf("total" to JsonPrimitive(audioBytes.size))))
    }

    override suspend fun info(): ApiOutcome<DaemonInfo> = ApiOutcome.Ok(200, DaemonInfo())

    override suspend fun status(): ApiOutcome<DaemonStatus> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun linkTest(seconds: Double?, codec: String?, transport: String?, bitrate: Int?): ApiOutcome<LinkTestResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun linkTests(limit: Int?): ApiOutcome<LinkTests> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun probe(candidates: List<String>?): ApiOutcome<ProbeResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun placeCall(request: PlaceCallRequest): ApiOutcome<CallSnapshot> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun validateCall(request: PlaceCallRequest): ApiOutcome<ValidationResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun testCall(request: TestCallRequest): ApiOutcome<CallSnapshot> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun events(callId: String, since: Long?, waitSeconds: Double?): ApiOutcome<CallEventsPage> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun instruction(callId: String, text: String): ApiOutcome<InstructionResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun pause(callId: String): ApiOutcome<PauseResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun resume(callId: String): ApiOutcome<PauseResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun summarize(callId: String): ApiOutcome<SummaryResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun dtmf(callId: String, digits: String): ApiOutcome<DtmfResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun hangup(callId: String): ApiOutcome<CallSnapshot> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun marks(callId: String): ApiOutcome<MarksResponse> = marksResult

    override suspend fun settings(): ApiOutcome<SettingsSnapshot> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun putSettings(values: JsonObject): ApiOutcome<SettingsUpdate> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun loopback(enabled: Boolean, callId: String?): ApiOutcome<LoopbackResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun tone(hz: Int?, dbfs: Double?, seconds: Double?, callId: String?): ApiOutcome<ToneResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun doctor(): ApiOutcome<DoctorReport> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun doctorFix(fix: String): ApiOutcome<DoctorFixResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun diagnostics(): ApiOutcome<JsonObject> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun createDiagnosticsBundle(): ApiOutcome<DiagnosticsBundle> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun updateManifest(channel: String?): ApiOutcome<JsonObject> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun compat(appApi: Int?, protocol: Int?): ApiOutcome<CompatReport> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun rotatePair(rotateDeviceToken: Boolean): ApiOutcome<RotateResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun downloadUpdateArtifact(name: String, sink: OutputStream): com.phonxiety.bridge.data.ApiResult = com.phonxiety.bridge.data.ApiResult.Failure(501, "unused", "unused")

    override suspend fun downloadBundle(bundleId: String, sink: OutputStream): ApiResult = ApiResult.Failure(501, "unused", "unused")

    private fun <T> MutableList<T>.removeFirstOrNull(): T? = if (isEmpty()) null else removeAt(0)
}

fun summary(
    callId: String,
    destination: String = "+998901234567",
    state: String = "ended",
    callOutcome: String? = "answered",
    summary: String? = "Table booked",
    farEnd: String? = "Bella Napoli",
    createdAt: Double = 1_764_000_000.0,
    live: Boolean = false,
    marks: Int = 0,
    hasRecording: Boolean = true
): CallSummary = CallSummary(
    callId = callId,
    state = state,
    destination = destination,
    goal = "Book a table",
    callOutcome = callOutcome,
    summary = summary,
    farEnd = farEnd,
    createdAt = createdAt,
    connectedAt = createdAt + 5,
    endedAt = createdAt + 65,
    durationSeconds = 60.0,
    marks = marks,
    hasRecording = hasRecording,
    live = live
)

fun detailSnapshot(
    callId: String = "call-1",
    destination: String = "+998901234567",
    transcript: List<TranscriptTurn> = listOf(TranscriptTurn(1, "agent", "Hello", 1_764_000_006.0, true)),
    marks: List<CallMark> = emptyList(),
    live: Boolean = false,
    summary: String? = if (live) null else "Table for two on Thursday",
    fields: JsonObject = if (live) JsonObject(emptyMap()) else JsonObject(mapOf("time" to JsonPrimitive("20:00")))
): CallSnapshot = CallSnapshot(
    callId = callId,
    destination = destination,
    state = if (live) "active" else "ended",
    callOutcome = if (live) null else "answered",
    taskOutcome = if (live) null else "complete",
    resultStatus = if (live) null else "ready",
    summary = summary,
    fields = fields,
    createdAt = 1_764_000_000.0,
    connectedAt = 1_764_000_005.0,
    endedAt = if (live) null else 1_764_000_065.0,
    transcript = transcript,
    request = JsonObject(
        mapOf(
            "destination" to JsonPrimitive("+998901234567"),
            "goal" to JsonPrimitive("Book a table for two"),
            "context" to JsonPrimitive(""),
            "constraints" to JsonPrimitive("no deposit"),
            "result_fields" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("time"))),
            "limits" to JsonObject(
                mapOf("max_duration_s" to JsonPrimitive(900), "ring_timeout_s" to JsonPrimitive(60))
            )
        )
    ),
    marks = marks,
    farEnd = "Bella Napoli",
    counters = JsonObject(mapOf("tx_frames" to JsonPrimitive(2400))),
    recording = JsonObject(mapOf("file" to JsonPrimitive("call-1.wav"))),
    live = if (live) JsonObject(mapOf("agent_activity" to JsonPrimitive("speaking"))) else null
)
