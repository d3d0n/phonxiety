package com.phonxiety.bridge.data

import com.phonxiety.bridge.model.CallEventsPage
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
import com.phonxiety.bridge.model.ValidationResult
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The typed answer to one daemon API call. */
sealed interface ApiOutcome<out T> {
    data class Ok<T>(val status: Int, val value: T) : ApiOutcome<T>

    data class Failure(val status: Int, val code: String, val message: String) : ApiOutcome<Nothing>
}

/**
 * Typed `/app/v1` helpers.  Every method is a suspend call returning either the
 * decoded result or the daemon's error code; nothing throws for an API failure.
 */
interface DaemonApi {
    suspend fun info(): ApiOutcome<DaemonInfo>

    suspend fun status(): ApiOutcome<DaemonStatus>

    suspend fun linkTest(
        seconds: Double? = null,
        codec: String? = null,
        transport: String? = null,
        bitrate: Int? = null
    ): ApiOutcome<LinkTestResult>

    suspend fun linkTests(limit: Int? = null): ApiOutcome<LinkTests>

    suspend fun probe(candidates: List<String>? = null): ApiOutcome<ProbeResult>

    suspend fun calls(
        limit: Int? = null,
        offset: Int? = null,
        before: Double? = null,
        outcome: String? = null,
        farEnd: String? = null
    ): ApiOutcome<CallsPage>

    suspend fun placeCall(request: PlaceCallRequest): ApiOutcome<CallSnapshot>

    suspend fun validateCall(request: PlaceCallRequest): ApiOutcome<ValidationResult>

    suspend fun testCall(request: TestCallRequest): ApiOutcome<CallSnapshot>

    suspend fun call(callId: String): ApiOutcome<CallSnapshot>

    suspend fun transcript(callId: String, cursor: Long? = null, limit: Int? = null): ApiOutcome<TranscriptPage>

    suspend fun events(callId: String, since: Long? = null, waitSeconds: Double? = null): ApiOutcome<CallEventsPage>

    suspend fun instruction(callId: String, text: String): ApiOutcome<InstructionResult>

    suspend fun pause(callId: String): ApiOutcome<PauseResult>

    suspend fun resume(callId: String): ApiOutcome<PauseResult>

    suspend fun summarize(callId: String): ApiOutcome<SummaryResult>

    suspend fun dtmf(callId: String, digits: String): ApiOutcome<DtmfResult>

    suspend fun hangup(callId: String): ApiOutcome<CallSnapshot>

    suspend fun marks(callId: String): ApiOutcome<MarksResponse>

    suspend fun addMark(callId: String, mark: String, note: String? = null): ApiOutcome<MarksResponse>

    suspend fun deleteCall(callId: String): ApiOutcome<DeleteCallResult>

    suspend fun settings(): ApiOutcome<SettingsSnapshot>

    suspend fun putSettings(values: JsonObject): ApiOutcome<SettingsUpdate>

    suspend fun loopback(enabled: Boolean, callId: String? = null): ApiOutcome<LoopbackResult>

    suspend fun tone(hz: Int? = null, dbfs: Double? = null, seconds: Double? = null, callId: String? = null): ApiOutcome<ToneResult>

    suspend fun doctor(): ApiOutcome<DoctorReport>

    suspend fun doctorFix(fix: String): ApiOutcome<DoctorFixResult>

    suspend fun diagnostics(): ApiOutcome<JsonObject>

    suspend fun createDiagnosticsBundle(): ApiOutcome<DiagnosticsBundle>

    suspend fun updateManifest(channel: String? = null): ApiOutcome<JsonObject>

    suspend fun compat(appApi: Int? = null, protocol: Int? = null): ApiOutcome<CompatReport>

    suspend fun rotatePair(rotateDeviceToken: Boolean = false): ApiOutcome<RotateResult>

    suspend fun downloadAudio(callId: String, format: String = "m4a", sink: OutputStream): ApiResult

    suspend fun downloadBundle(bundleId: String, sink: OutputStream): ApiResult

    /** One artifact from the daemon's `updates/` directory, for a `daemon:` release source. */
    suspend fun downloadUpdateArtifact(name: String, sink: OutputStream): ApiResult
}

@Singleton
class DefaultDaemonApi @Inject constructor(
    private val api: AppApi
) : DaemonApi {
    internal val json: Json = DaemonJson

    override suspend fun info(): ApiOutcome<DaemonInfo> = call(DaemonInfo.serializer(), "GET", "/app/v1/info")

    override suspend fun status(): ApiOutcome<DaemonStatus> = call(DaemonStatus.serializer(), "GET", "/app/v1/status")

    override suspend fun linkTest(seconds: Double?, codec: String?, transport: String?, bitrate: Int?): ApiOutcome<LinkTestResult> = call(
        LinkTestResult.serializer(),
        "POST",
        "/app/v1/link-test",
        body = buildJsonObject {
            if (seconds != null) put("seconds", seconds)
            if (codec != null) put("codec", codec)
            if (transport != null) put("transport", transport)
            if (bitrate != null) put("bitrate", bitrate)
        }
    )

    override suspend fun linkTests(limit: Int?): ApiOutcome<LinkTests> = call(LinkTests.serializer(), "GET", "/app/v1/link-test", query = limit?.let { mapOf("limit" to it.toString()) } ?: emptyMap())

    override suspend fun probe(candidates: List<String>?): ApiOutcome<ProbeResult> = call(
        ProbeResult.serializer(),
        "POST",
        "/app/v1/probe",
        body = candidates?.let {
            buildJsonObject {
                put(
                    "candidates",
                    kotlinx.serialization.json.buildJsonArray { it.forEach { name -> add(kotlinx.serialization.json.JsonPrimitive(name)) } }
                )
            }
        }
    )

    override suspend fun calls(
        limit: Int?,
        offset: Int?,
        before: Double?,
        outcome: String?,
        farEnd: String?
    ): ApiOutcome<CallsPage> {
        val query = buildMap {
            limit?.let { put("limit", it.toString()) }
            offset?.let { put("offset", it.toString()) }
            before?.let { put("before", it.toString()) }
            outcome?.let { put("outcome", it) }
            farEnd?.let { put("farEnd", it) }
        }
        return call(CallsPage.serializer(), "GET", "/app/v1/calls", query = query)
    }

    override suspend fun placeCall(request: PlaceCallRequest): ApiOutcome<CallSnapshot> = call(CallSnapshot.serializer(), "POST", "/app/v1/calls", body = encode(PlaceCallRequest.serializer(), request))

    override suspend fun validateCall(request: PlaceCallRequest): ApiOutcome<ValidationResult> = call(ValidationResult.serializer(), "POST", "/app/v1/calls/validate", body = encode(PlaceCallRequest.serializer(), request))

    override suspend fun testCall(request: TestCallRequest): ApiOutcome<CallSnapshot> = call(CallSnapshot.serializer(), "POST", "/app/v1/test-call", body = encode(TestCallRequest.serializer(), request))

    override suspend fun call(callId: String): ApiOutcome<CallSnapshot> = call(CallSnapshot.serializer(), "GET", "/app/v1/calls/${encodeId(callId)}")

    override suspend fun transcript(callId: String, cursor: Long?, limit: Int?): ApiOutcome<TranscriptPage> {
        val query = buildMap {
            cursor?.let { put("cursor", it.toString()) }
            limit?.let { put("limit", it.toString()) }
        }
        return call(TranscriptPage.serializer(), "GET", "/app/v1/calls/${encodeId(callId)}/transcript", query = query)
    }

    override suspend fun events(callId: String, since: Long?, waitSeconds: Double?): ApiOutcome<CallEventsPage> {
        val query = buildMap {
            since?.let { put("since", it.toString()) }
            waitSeconds?.let { put("wait_s", it.toString()) }
        }
        return call(CallEventsPage.serializer(), "GET", "/app/v1/calls/${encodeId(callId)}/events", query = query)
    }

    override suspend fun instruction(callId: String, text: String): ApiOutcome<InstructionResult> = call(
        InstructionResult.serializer(),
        "POST",
        "/app/v1/calls/${encodeId(callId)}/instruction",
        body = buildJsonObject { put("text", text) }
    )

    override suspend fun pause(callId: String): ApiOutcome<PauseResult> = call(PauseResult.serializer(), "POST", "/app/v1/calls/${encodeId(callId)}/pause")

    override suspend fun resume(callId: String): ApiOutcome<PauseResult> = call(PauseResult.serializer(), "POST", "/app/v1/calls/${encodeId(callId)}/resume")

    override suspend fun summarize(callId: String): ApiOutcome<SummaryResult> = call(SummaryResult.serializer(), "POST", "/app/v1/calls/${encodeId(callId)}/summarize")

    override suspend fun dtmf(callId: String, digits: String): ApiOutcome<DtmfResult> = call(
        DtmfResult.serializer(),
        "POST",
        "/app/v1/calls/${encodeId(callId)}/dtmf",
        body = buildJsonObject { put("digits", digits) }
    )

    override suspend fun hangup(callId: String): ApiOutcome<CallSnapshot> = call(CallSnapshot.serializer(), "POST", "/app/v1/calls/${encodeId(callId)}/hangup")

    override suspend fun marks(callId: String): ApiOutcome<MarksResponse> = call(MarksResponse.serializer(), "GET", "/app/v1/calls/${encodeId(callId)}/marks")

    override suspend fun addMark(callId: String, mark: String, note: String?): ApiOutcome<MarksResponse> = call(
        MarksResponse.serializer(),
        "POST",
        "/app/v1/calls/${encodeId(callId)}/marks",
        body = buildJsonObject {
            put("mark", mark)
            if (note != null) put("note", note)
        }
    )

    override suspend fun deleteCall(callId: String): ApiOutcome<DeleteCallResult> = call(
        DeleteCallResult.serializer(),
        "DELETE",
        "/app/v1/calls/${encodeId(callId)}",
        body = buildJsonObject { put("confirm", callId) }
    )

    override suspend fun settings(): ApiOutcome<SettingsSnapshot> = call(SettingsSnapshot.serializer(), "GET", "/app/v1/settings")

    override suspend fun putSettings(values: JsonObject): ApiOutcome<SettingsUpdate> = call(
        SettingsUpdate.serializer(),
        "PUT",
        "/app/v1/settings",
        body = buildJsonObject { put("values", values) }
    )

    override suspend fun loopback(enabled: Boolean, callId: String?): ApiOutcome<LoopbackResult> = call(
        LoopbackResult.serializer(),
        "POST",
        "/app/v1/loopback",
        body = buildJsonObject {
            put("enabled", enabled)
            if (callId != null) put("call_id", callId)
        }
    )

    override suspend fun tone(hz: Int?, dbfs: Double?, seconds: Double?, callId: String?): ApiOutcome<ToneResult> = call(
        ToneResult.serializer(),
        "POST",
        "/app/v1/tone",
        body = buildJsonObject {
            if (hz != null) put("hz", hz)
            if (dbfs != null) put("dbfs", dbfs)
            if (seconds != null) put("seconds", seconds)
            if (callId != null) put("call_id", callId)
        }
    )

    override suspend fun doctor(): ApiOutcome<DoctorReport> = call(DoctorReport.serializer(), "GET", "/app/v1/doctor")

    override suspend fun doctorFix(fix: String): ApiOutcome<DoctorFixResult> = call(
        DoctorFixResult.serializer(),
        "POST",
        "/app/v1/doctor/fix",
        body = buildJsonObject { put("fix", fix) }
    )

    override suspend fun diagnostics(): ApiOutcome<JsonObject> = call(JsonObject.serializer(), "GET", "/app/v1/diagnostics")

    override suspend fun createDiagnosticsBundle(): ApiOutcome<DiagnosticsBundle> = call(DiagnosticsBundle.serializer(), "POST", "/app/v1/diagnostics/bundle")

    override suspend fun updateManifest(channel: String?): ApiOutcome<JsonObject> = call(
        JsonObject.serializer(),
        "GET",
        "/app/v1/update/manifest",
        query = channel?.let { mapOf("channel" to it) } ?: emptyMap()
    )

    override suspend fun compat(appApi: Int?, protocol: Int?): ApiOutcome<CompatReport> {
        val query = buildMap {
            appApi?.let { put("app_api", it.toString()) }
            protocol?.let { put("protocol", it.toString()) }
        }
        return call(CompatReport.serializer(), "GET", "/app/v1/compat", query = query)
    }

    override suspend fun rotatePair(rotateDeviceToken: Boolean): ApiOutcome<RotateResult> = call(
        RotateResult.serializer(),
        "POST",
        "/app/v1/pair/rotate",
        body = buildJsonObject { if (rotateDeviceToken) put("device_token", true) }
    )

    override suspend fun downloadAudio(callId: String, format: String, sink: OutputStream): ApiResult = api.download("/app/v1/calls/${encodeId(callId)}/audio", mapOf("format" to format), sink)

    override suspend fun downloadBundle(bundleId: String, sink: OutputStream): ApiResult = api.download("/app/v1/diagnostics/bundle/${encodeId(bundleId)}", emptyMap(), sink)

    override suspend fun downloadUpdateArtifact(name: String, sink: OutputStream): ApiResult = api.download(
        "/app/v1/update/artifact",
        mapOf("name" to name),
        sink
    )

    private suspend fun <T> call(
        deserializer: DeserializationStrategy<T>,
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null
    ): ApiOutcome<T> = when (val result = api.request(method, path, query, body)) {
        is ApiResult.Failure -> ApiOutcome.Failure(result.status, result.code, result.message)

        is ApiResult.Success -> try {
            ApiOutcome.Ok(result.status, json.decodeFromJsonElement(deserializer, result.result))
        } catch (e: Exception) {
            ApiOutcome.Failure(502, "decode_failed", "the daemon's answer is not a valid ${path.substringAfterLast('/')} result")
        }
    }

    private fun <T> encode(serializer: SerializationStrategy<T>, value: T): JsonObject = json.encodeToJsonElement(serializer, value).jsonObject

    /** Call ids are already restricted by the daemon; refuse path syntax here too. */
    private fun encodeId(id: String): String {
        require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it in "._-" }) { "invalid call id" }
        return id
    }
}
