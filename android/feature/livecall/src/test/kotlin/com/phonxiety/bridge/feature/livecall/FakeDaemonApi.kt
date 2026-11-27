package com.phonxiety.bridge.feature.livecall

import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.data.DeleteOutcome
import com.phonxiety.bridge.data.HangUpOutcome
import com.phonxiety.bridge.data.MarkOutcome
import com.phonxiety.bridge.data.PauseOutcome
import com.phonxiety.bridge.data.ResumeOutcome
import com.phonxiety.bridge.data.asDeleteOutcome
import com.phonxiety.bridge.data.asHangUpOutcome
import com.phonxiety.bridge.data.asMarkOutcome
import com.phonxiety.bridge.data.asPauseOutcome
import com.phonxiety.bridge.data.asResumeOutcome
import com.phonxiety.bridge.model.CallEventsPage
import com.phonxiety.bridge.model.CallId
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.CallsPage
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
import com.phonxiety.bridge.model.ValidationIssue
import com.phonxiety.bridge.model.ValidationResult
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject

/**
 * One daemon for every feature test: each handler is a queue the test fills, and every call is
 * recorded so a test can assert the cursor, the confirmation id or the instruction text.
 */
class FakeDaemonApi : DaemonApi {
    val eventsRequests = mutableListOf<Long?>()
    val validateRequests = mutableListOf<PlaceCallRequest>()
    val placedRequests = mutableListOf<PlaceCallRequest>()
    val instructionRequests = mutableListOf<String>()
    val dtmfRequests = mutableListOf<String>()
    val markRequests = mutableListOf<String>()
    val hangupRequests = mutableListOf<String>()
    var infoCalls = 0
    var hangupCalls = 0
    var pauseCalls = 0
    var resumeCalls = 0
    var summarizeCalls = 0

    var info: ApiOutcome<DaemonInfo> = ApiOutcome.Ok(200, DaemonInfo())
    var callSnapshot: ApiOutcome<CallSnapshot> = ApiOutcome.Failure(404, "call_not_found", "no call")
    var events: MutableList<ApiOutcome<CallEventsPage>> = mutableListOf()
    var validation: ApiOutcome<ValidationResult> = ApiOutcome.Ok(200, ValidationResult(valid = true))
    var place: ApiOutcome<CallSnapshot> = ApiOutcome.Failure(500, "not_configured", "no place handler")
    var instruction: ApiOutcome<InstructionResult> = ApiOutcome.Ok(200, InstructionResult(delivered = true))
    var pause: ApiOutcome<PauseResult> = ApiOutcome.Ok(200, PauseResult(muted = true))
    var resume: ApiOutcome<PauseResult> = ApiOutcome.Ok(200, PauseResult(muted = false))
    var summarize: ApiOutcome<SummaryResult> = ApiOutcome.Ok(200, SummaryResult(summary = "agreed"))
    var dtmf: ApiOutcome<DtmfResult> = ApiOutcome.Ok(200, DtmfResult())
    var marks: ApiOutcome<MarksResponse> = ApiOutcome.Ok(200, MarksResponse())
    var hangup: ApiOutcome<CallSnapshot> = ApiOutcome.Failure(500, "not_configured", "no hangup handler")

    /**
     * When set, a hang-up waits for it before answering, so a test can land an event in between
     * and see what a late answer installs on the screen.
     */
    var hangupGate: CompletableDeferred<Unit>? = null

    override suspend fun info(): ApiOutcome<DaemonInfo> {
        infoCalls++
        return info
    }

    override suspend fun events(callId: CallId, since: Long?, waitSeconds: Double?): ApiOutcome<CallEventsPage> {
        eventsRequests += since
        return if (events.isEmpty()) {
            ApiOutcome.Failure(504, "timeout", "nothing more happened")
        } else {
            events.removeAt(0)
        }
    }

    override suspend fun call(callId: CallId): ApiOutcome<CallSnapshot> = callSnapshot

    override suspend fun validateCall(request: PlaceCallRequest): ApiOutcome<ValidationResult> {
        validateRequests += request
        return validation
    }

    override suspend fun placeCall(request: PlaceCallRequest): ApiOutcome<CallSnapshot> {
        placedRequests += request
        return place
    }

    override suspend fun instruction(callId: CallId, text: String): ApiOutcome<InstructionResult> {
        instructionRequests += text
        return instruction
    }

    override suspend fun pause(callId: CallId): PauseOutcome {
        pauseCalls++
        return pause.asPauseOutcome()
    }

    override suspend fun resume(callId: CallId): ResumeOutcome {
        resumeCalls++
        return resume.asResumeOutcome()
    }

    override suspend fun summarize(callId: CallId): ApiOutcome<SummaryResult> {
        summarizeCalls++
        return summarize
    }

    override suspend fun dtmf(callId: CallId, digits: String): ApiOutcome<DtmfResult> {
        dtmfRequests += digits
        return dtmf
    }

    override suspend fun addMark(callId: CallId, mark: String, note: String?): MarkOutcome {
        markRequests += mark
        return marks.asMarkOutcome()
    }

    override suspend fun hangup(callId: CallId): HangUpOutcome {
        hangupRequests += callId.value
        hangupCalls++
        hangupGate?.await()
        return hangup.asHangUpOutcome()
    }

    override suspend fun status(): ApiOutcome<DaemonStatus> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun linkTest(seconds: Double?, codec: String?, transport: String?, bitrate: Int?): ApiOutcome<LinkTestResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun linkTests(limit: Int?): ApiOutcome<LinkTests> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun probe(candidates: List<String>?): ApiOutcome<ProbeResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun calls(limit: Int?, offset: Int?, before: Double?, outcome: String?, farEnd: String?): ApiOutcome<CallsPage> = ApiOutcome.Ok(200, CallsPage())

    override suspend fun testCall(request: TestCallRequest): ApiOutcome<CallSnapshot> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun transcript(callId: CallId, cursor: Long?, limit: Int?): ApiOutcome<TranscriptPage> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun marks(callId: CallId): ApiOutcome<MarksResponse> = marks

    override suspend fun deleteCall(callId: CallId): DeleteOutcome = ApiOutcome.Failure(501, "unused", "unused").asDeleteOutcome()

    override suspend fun settings(): ApiOutcome<SettingsSnapshot> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun putSettings(values: JsonObject): ApiOutcome<SettingsUpdate> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun loopback(enabled: Boolean, callId: CallId?): ApiOutcome<LoopbackResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun tone(hz: Int?, dbfs: Double?, seconds: Double?, callId: CallId?): ApiOutcome<ToneResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun doctor(): ApiOutcome<DoctorReport> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun doctorFix(fix: String): ApiOutcome<DoctorFixResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun diagnostics(): ApiOutcome<JsonObject> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun createDiagnosticsBundle(): ApiOutcome<DiagnosticsBundle> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun updateManifest(channel: String?): ApiOutcome<JsonObject> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun compat(appApi: Int?, protocol: Int?): ApiOutcome<com.phonxiety.bridge.model.CompatReport> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun rotatePair(rotateDeviceToken: Boolean): ApiOutcome<RotateResult> = ApiOutcome.Failure(501, "unused", "unused")

    override suspend fun downloadAudio(callId: CallId, format: String, sink: OutputStream): com.phonxiety.bridge.data.ApiResult = com.phonxiety.bridge.data.ApiResult.Success(200, JsonObject(emptyMap()))

    override suspend fun downloadUpdateArtifact(name: String, sink: OutputStream): com.phonxiety.bridge.data.ApiResult = com.phonxiety.bridge.data.ApiResult.Failure(501, "unused", "unused")

    override suspend fun downloadBundle(bundleId: String, sink: OutputStream): com.phonxiety.bridge.data.ApiResult = com.phonxiety.bridge.data.ApiResult.Success(200, JsonObject(emptyMap()))
}

/** The validation error the composer must render against the field it names. */
fun fieldInvalidError(field: String, message: String): ApiOutcome<ValidationResult> = ApiOutcome.Ok(
    200,
    ValidationResult(valid = false, errors = listOf(ValidationIssue(field, message)))
)
