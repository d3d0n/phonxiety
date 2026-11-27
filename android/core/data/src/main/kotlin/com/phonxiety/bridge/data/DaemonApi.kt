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
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

    /**
     * Test seam for the retry jitter.  Production uses [Random.Default]; a test seeds
     * it so the pauses are exact and the virtual clock they run on is predictable.
     */
    internal var retryRandom: Random = Random.Default

    override suspend fun info(): ApiOutcome<DaemonInfo> = call(DaemonInfo.serializer(), "GET", "/app/v1/info")

    override suspend fun status(): ApiOutcome<DaemonStatus> = call(DaemonStatus.serializer(), "GET", "/app/v1/status")

    override suspend fun linkTest(seconds: Double?, codec: String?, transport: String?, bitrate: Int?): ApiOutcome<LinkTestResult> = call(
        LinkTestResult.serializer(),
        "POST",
        "/app/v1/link-test",
        body = bodyOf(
            "seconds" to seconds,
            "codec" to codec,
            "transport" to transport,
            "bitrate" to bitrate
        )
    )

    override suspend fun linkTests(limit: Int?): ApiOutcome<LinkTests> = call(
        LinkTests.serializer(),
        "GET",
        "/app/v1/link-test",
        query = queryOf("limit" to limit)
    )

    override suspend fun probe(candidates: List<String>?): ApiOutcome<ProbeResult> = call(
        ProbeResult.serializer(),
        "POST",
        "/app/v1/probe",
        body = candidates?.let { bodyOf("candidates" to it) }
    )

    override suspend fun calls(
        limit: Int?,
        offset: Int?,
        before: Double?,
        outcome: String?,
        farEnd: String?
    ): ApiOutcome<CallsPage> {
        val query = queryOf(
            "limit" to limit,
            "offset" to offset,
            "before" to before,
            "outcome" to outcome,
            "farEnd" to farEnd
        )
        return call(CallsPage.serializer(), "GET", "/app/v1/calls", query = query)
    }

    override suspend fun placeCall(request: PlaceCallRequest): ApiOutcome<CallSnapshot> = call(CallSnapshot.serializer(), "POST", "/app/v1/calls", body = encode(PlaceCallRequest.serializer(), request))

    override suspend fun validateCall(request: PlaceCallRequest): ApiOutcome<ValidationResult> = call(ValidationResult.serializer(), "POST", "/app/v1/calls/validate", body = encode(PlaceCallRequest.serializer(), request))

    override suspend fun testCall(request: TestCallRequest): ApiOutcome<CallSnapshot> = call(CallSnapshot.serializer(), "POST", "/app/v1/test-call", body = encode(TestCallRequest.serializer(), request))

    override suspend fun call(callId: String): ApiOutcome<CallSnapshot> = call(CallSnapshot.serializer(), "GET", "/app/v1/calls/${encodeId(callId)}")

    override suspend fun transcript(callId: String, cursor: Long?, limit: Int?): ApiOutcome<TranscriptPage> {
        val query = queryOf("cursor" to cursor, "limit" to limit)
        return call(TranscriptPage.serializer(), "GET", "/app/v1/calls/${encodeId(callId)}/transcript", query = query)
    }

    override suspend fun events(callId: String, since: Long?, waitSeconds: Double?): ApiOutcome<CallEventsPage> {
        val query = queryOf("since" to since, "wait_s" to waitSeconds)
        // The long poll is a wait, not a read: another attempt would only lengthen the caller's.
        return call(CallEventsPage.serializer(), "GET", "/app/v1/calls/${encodeId(callId)}/events", query = query, retryable = false)
    }

    override suspend fun instruction(callId: String, text: String): ApiOutcome<InstructionResult> = call(
        InstructionResult.serializer(),
        "POST",
        "/app/v1/calls/${encodeId(callId)}/instruction",
        body = bodyOf("text" to text)
    )

    override suspend fun pause(callId: String): ApiOutcome<PauseResult> = call(PauseResult.serializer(), "POST", "/app/v1/calls/${encodeId(callId)}/pause")

    override suspend fun resume(callId: String): ApiOutcome<PauseResult> = call(PauseResult.serializer(), "POST", "/app/v1/calls/${encodeId(callId)}/resume")

    override suspend fun summarize(callId: String): ApiOutcome<SummaryResult> = call(SummaryResult.serializer(), "POST", "/app/v1/calls/${encodeId(callId)}/summarize")

    override suspend fun dtmf(callId: String, digits: String): ApiOutcome<DtmfResult> = call(
        DtmfResult.serializer(),
        "POST",
        "/app/v1/calls/${encodeId(callId)}/dtmf",
        body = bodyOf("digits" to digits)
    )

    override suspend fun hangup(callId: String): ApiOutcome<CallSnapshot> = call(CallSnapshot.serializer(), "POST", "/app/v1/calls/${encodeId(callId)}/hangup")

    override suspend fun marks(callId: String): ApiOutcome<MarksResponse> = call(MarksResponse.serializer(), "GET", "/app/v1/calls/${encodeId(callId)}/marks")

    override suspend fun addMark(callId: String, mark: String, note: String?): ApiOutcome<MarksResponse> = call(
        MarksResponse.serializer(),
        "POST",
        "/app/v1/calls/${encodeId(callId)}/marks",
        body = bodyOf("mark" to mark, "note" to note)
    )

    override suspend fun deleteCall(callId: String): ApiOutcome<DeleteCallResult> = call(
        DeleteCallResult.serializer(),
        "DELETE",
        "/app/v1/calls/${encodeId(callId)}",
        body = bodyOf("confirm" to callId)
    )

    override suspend fun settings(): ApiOutcome<SettingsSnapshot> = call(SettingsSnapshot.serializer(), "GET", "/app/v1/settings")

    override suspend fun putSettings(values: JsonObject): ApiOutcome<SettingsUpdate> = call(
        SettingsUpdate.serializer(),
        "PUT",
        "/app/v1/settings",
        body = bodyOf("values" to values)
    )

    override suspend fun loopback(enabled: Boolean, callId: String?): ApiOutcome<LoopbackResult> = call(
        LoopbackResult.serializer(),
        "POST",
        "/app/v1/loopback",
        body = bodyOf("enabled" to enabled, "call_id" to callId)
    )

    override suspend fun tone(hz: Int?, dbfs: Double?, seconds: Double?, callId: String?): ApiOutcome<ToneResult> = call(
        ToneResult.serializer(),
        "POST",
        "/app/v1/tone",
        body = bodyOf(
            "hz" to hz,
            "dbfs" to dbfs,
            "seconds" to seconds,
            "call_id" to callId
        )
    )

    override suspend fun doctor(): ApiOutcome<DoctorReport> = call(DoctorReport.serializer(), "GET", "/app/v1/doctor")

    override suspend fun doctorFix(fix: String): ApiOutcome<DoctorFixResult> = call(
        DoctorFixResult.serializer(),
        "POST",
        "/app/v1/doctor/fix",
        body = bodyOf("fix" to fix)
    )

    override suspend fun diagnostics(): ApiOutcome<JsonObject> = call(JsonObject.serializer(), "GET", "/app/v1/diagnostics")

    override suspend fun createDiagnosticsBundle(): ApiOutcome<DiagnosticsBundle> = call(DiagnosticsBundle.serializer(), "POST", "/app/v1/diagnostics/bundle")

    override suspend fun updateManifest(channel: String?): ApiOutcome<JsonObject> = call(
        JsonObject.serializer(),
        "GET",
        "/app/v1/update/manifest",
        query = queryOf("channel" to channel)
    )

    override suspend fun compat(appApi: Int?, protocol: Int?): ApiOutcome<CompatReport> {
        val query = queryOf("app_api" to appApi, "protocol" to protocol)
        return call(CompatReport.serializer(), "GET", "/app/v1/compat", query = query)
    }

    override suspend fun rotatePair(rotateDeviceToken: Boolean): ApiOutcome<RotateResult> = call(
        RotateResult.serializer(),
        "POST",
        "/app/v1/pair/rotate",
        body = bodyOf("device_token" to true.takeIf { rotateDeviceToken })
    )

    override suspend fun downloadAudio(callId: String, format: String, sink: OutputStream): ApiResult = api.download("/app/v1/calls/${encodeId(callId)}/audio", mapOf("format" to format), sink)

    override suspend fun downloadBundle(bundleId: String, sink: OutputStream): ApiResult = api.download("/app/v1/diagnostics/bundle/${encodeId(bundleId)}", emptyMap(), sink)

    override suspend fun downloadUpdateArtifact(name: String, sink: OutputStream): ApiResult = api.download(
        "/app/v1/update/artifact",
        mapOf("name" to name),
        sink
    )

    /**
     * Runs one request and, for a read whose answer says "not now", up to
     * [MAX_RETRIES] more attempts with a jittered pause between them.
     *
     * No deadline is created here.  The caller's own operation deadline is the one
     * that counts: it cancels the pause, so the attempts can never outlive it, and
     * the link's own per-exchange deadline still bounds every single attempt.
     */
    private suspend fun <T> call(
        deserializer: DeserializationStrategy<T>,
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null,
        retryable: Boolean = true
    ): ApiOutcome<T> {
        var retries = 0
        while (true) {
            val outcome = attempt(deserializer, method, path, query, body)
            val failure = outcome as? ApiOutcome.Failure ?: return outcome
            if (!retryable || method != "GET" || retries == MAX_RETRIES || !failure.isWorthAnotherAttempt()) {
                return failure
            }
            retries++
            delay(retryDelayMillis(retries))
        }
    }

    private suspend fun <T> attempt(
        deserializer: DeserializationStrategy<T>,
        method: String,
        path: String,
        query: Map<String, String>,
        body: JsonObject?
    ): ApiOutcome<T> = when (val result = api.request(method, path, query, body)) {
        is ApiResult.Failure -> ApiOutcome.Failure(result.status, result.code, result.message)

        is ApiResult.Success -> try {
            ApiOutcome.Ok(result.status, json.decodeFromJsonElement(deserializer, result.result))
        } catch (e: Exception) {
            ApiOutcome.Failure(502, "decode_failed", "the daemon's answer is not a valid ${path.substringAfterLast('/')} result")
        }
    }

    /** A gateway or an engine that answered "not now"; anything else is the caller's answer. */
    private fun ApiOutcome.Failure.isWorthAnotherAttempt(): Boolean = status in RETRYABLE_STATUS || code == "transport_error"

    /** Wait before retry [retry]: a base per attempt plus a random part under that base. */
    private fun retryDelayMillis(retry: Int): Long {
        val base = RETRY_BASE_MILLIS shl (retry - 1)
        return base + retryRandom.nextLong(base)
    }

    /** Query parameters in call order; a null value is left out, so it never reaches the wire. */
    private fun queryOf(vararg fields: Pair<String, Any?>): Map<String, String> = buildMap {
        for ((name, value) in fields) if (value != null) put(name, value.toString())
    }

    /**
     * One JSON object body; a null value leaves its key out of the object entirely,
     * so an omitted optional parameter is an absent parameter, not an explicit null.
     */
    private fun bodyOf(vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((name, value) in fields) {
            when (value) {
                null -> Unit
                is JsonElement -> put(name, value)
                is Boolean -> put(name, value)
                is Number -> put(name, value)
                is String -> put(name, value)
                is List<*> -> put(name, buildJsonArray { value.forEach { add(JsonPrimitive(it.toString())) } })
                else -> throw IllegalArgumentException("$name: a body value must be a JSON value or a list of strings")
            }
        }
    }

    private fun <T> encode(serializer: SerializationStrategy<T>, value: T): JsonObject = json.encodeToJsonElement(serializer, value).jsonObject

    /** Call ids are already restricted by the daemon; refuse path syntax here too. */
    private fun encodeId(id: String): String {
        require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it in "._-" }) { "invalid call id" }
        return id
    }

    private companion object {
        /** A read gets two more attempts; a write, a download and the long poll never do. */
        const val MAX_RETRIES = 2

        /** First pause 200–399 ms, second 400–799 ms: long enough for a restarted gateway. */
        const val RETRY_BASE_MILLIS = 200L

        /** A gateway that could not answer now; a 4xx is the caller's own answer. */
        val RETRYABLE_STATUS = setOf(502, 503, 504)
    }
}
