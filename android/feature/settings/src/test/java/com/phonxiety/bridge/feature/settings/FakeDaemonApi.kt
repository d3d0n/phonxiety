package com.phonxiety.bridge.feature.settings

import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.data.DeleteOutcome
import com.phonxiety.bridge.data.HangUpOutcome
import com.phonxiety.bridge.data.MarkOutcome
import com.phonxiety.bridge.data.PauseOutcome
import com.phonxiety.bridge.data.ResumeOutcome
import com.phonxiety.bridge.model.CallEventsPage
import com.phonxiety.bridge.model.CallId
import com.phonxiety.bridge.model.CallSnapshot
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
import kotlinx.serialization.json.JsonObject

/** The app API with every call replaceable; an unset call answers 503 instead of throwing. */
open class FakeDaemonApi : DaemonApi {
    var infoHandler: suspend () -> ApiOutcome<DaemonInfo> = { unset("info") }
    var linkTestHandler: suspend (Double?, String?, String?, Int?) -> ApiOutcome<LinkTestResult> =
        { _, _, _, _ -> unset("linkTest") }
    var linkTestsHandler: suspend (Int?) -> ApiOutcome<LinkTests> = { unset("linkTests") }
    var probeHandler: suspend (List<String>?) -> ApiOutcome<ProbeResult> = { unset("probe") }
    var settingsHandler: suspend () -> ApiOutcome<SettingsSnapshot> = { unset("settings") }
    var putSettingsHandler: suspend (JsonObject) -> ApiOutcome<SettingsUpdate> = { unset("putSettings") }
    var loopbackHandler: suspend (Boolean, CallId?) -> ApiOutcome<LoopbackResult> = { _, _ -> unset("loopback") }
    var toneHandler: suspend (Int?, Double?, Double?, CallId?) -> ApiOutcome<ToneResult> =
        { _, _, _, _ -> unset("tone") }
    var testCallHandler: suspend (TestCallRequest) -> ApiOutcome<CallSnapshot> = { unset("testCall") }
    var diagnosticsHandler: suspend () -> ApiOutcome<JsonObject> = { unset("diagnostics") }
    var bundleHandler: suspend () -> ApiOutcome<DiagnosticsBundle> = { unset("bundle") }
    var downloadBundleHandler: suspend (String, OutputStream) -> ApiResult = { _, _ -> ApiResult.Failure(503, "unset", "downloadBundle") }

    val downloadedBundles = mutableListOf<String>()
    val putSettingsBodies = mutableListOf<JsonObject>()

    override suspend fun info(): ApiOutcome<DaemonInfo> = infoHandler()

    override suspend fun linkTest(seconds: Double?, codec: String?, transport: String?, bitrate: Int?): ApiOutcome<LinkTestResult> = linkTestHandler(seconds, codec, transport, bitrate)

    override suspend fun linkTests(limit: Int?): ApiOutcome<LinkTests> = linkTestsHandler(limit)

    override suspend fun probe(candidates: List<String>?): ApiOutcome<ProbeResult> = probeHandler(candidates)

    override suspend fun settings(): ApiOutcome<SettingsSnapshot> = settingsHandler()

    override suspend fun putSettings(values: JsonObject): ApiOutcome<SettingsUpdate> {
        putSettingsBodies += values
        return putSettingsHandler(values)
    }

    override suspend fun loopback(enabled: Boolean, callId: CallId?): ApiOutcome<LoopbackResult> = loopbackHandler(enabled, callId)

    override suspend fun tone(hz: Int?, dbfs: Double?, seconds: Double?, callId: CallId?): ApiOutcome<ToneResult> = toneHandler(hz, dbfs, seconds, callId)

    override suspend fun testCall(request: TestCallRequest): ApiOutcome<CallSnapshot> = testCallHandler(request)

    override suspend fun diagnostics(): ApiOutcome<JsonObject> = diagnosticsHandler()

    override suspend fun createDiagnosticsBundle(): ApiOutcome<DiagnosticsBundle> = bundleHandler()

    override suspend fun downloadBundle(bundleId: String, sink: OutputStream): ApiResult {
        downloadedBundles += bundleId
        return downloadBundleHandler(bundleId, sink)
    }

    override suspend fun status(): ApiOutcome<DaemonStatus> = unset("status")
    override suspend fun calls(limit: Int?, offset: Int?, before: Double?, outcome: String?, farEnd: String?): ApiOutcome<CallsPage> = unset("calls")
    override suspend fun placeCall(request: PlaceCallRequest): ApiOutcome<CallSnapshot> = unset("placeCall")
    override suspend fun validateCall(request: PlaceCallRequest): ApiOutcome<ValidationResult> = unset("validateCall")
    override suspend fun call(callId: CallId): ApiOutcome<CallSnapshot> = unset("call")
    override suspend fun transcript(callId: CallId, cursor: Long?, limit: Int?): ApiOutcome<TranscriptPage> = unset("transcript")
    override suspend fun events(callId: CallId, since: Long?, waitSeconds: Double?): ApiOutcome<CallEventsPage> = unset("events")
    override suspend fun instruction(callId: CallId, text: String): ApiOutcome<InstructionResult> = unset("instruction")
    override suspend fun pause(callId: CallId): PauseOutcome = error("pause")
    override suspend fun resume(callId: CallId): ResumeOutcome = error("resume")
    override suspend fun summarize(callId: CallId): ApiOutcome<SummaryResult> = unset("summarize")
    override suspend fun dtmf(callId: CallId, digits: String): ApiOutcome<DtmfResult> = unset("dtmf")
    override suspend fun hangup(callId: CallId): HangUpOutcome = error("hangup")
    override suspend fun marks(callId: CallId): ApiOutcome<MarksResponse> = unset("marks")
    override suspend fun addMark(callId: CallId, mark: String, note: String?): MarkOutcome = error("addMark")
    override suspend fun deleteCall(callId: CallId): DeleteOutcome = error("deleteCall")
    override suspend fun doctor(): ApiOutcome<DoctorReport> = unset("doctor")
    override suspend fun doctorFix(fix: String): ApiOutcome<DoctorFixResult> = unset("doctorFix")
    override suspend fun updateManifest(channel: String?): ApiOutcome<JsonObject> = unset("updateManifest")
    override suspend fun compat(appApi: Int?, protocol: Int?): ApiOutcome<CompatReport> = unset("compat")
    override suspend fun rotatePair(rotateDeviceToken: Boolean): ApiOutcome<RotateResult> = unset("rotatePair")
    override suspend fun downloadAudio(callId: CallId, format: String, sink: OutputStream): ApiResult = ApiResult.Failure(503, "unset", "downloadAudio")

    override suspend fun downloadUpdateArtifact(name: String, sink: OutputStream): ApiResult = ApiResult.Failure(503, "unset", "downloadUpdateArtifact")
}

private fun <T> unset(what: String): ApiOutcome<T> = ApiOutcome.Failure(503, "unset", "$what is not configured in this test")

/** A result good enough for a "healthy" run unless a field is overridden. */
fun healthyLinkTest(p95: Long = 80, loss: Double = 0.0, throughput: Long = 128_000, codec: String = "opus"): LinkTestResult = LinkTestResult(
    at = "2026-11-26T10:00:00",
    codec = codec,
    transport = "stream",
    route = "direct 10.0.0.2, 5 ms",
    seconds = 3.0,
    frames = com.phonxiety.bridge.model.LinkTestFrames(sent = 150, echoed = 150),
    p50 = 38,
    p95 = p95,
    max = p95 + 40,
    loss = loss,
    throughputBps = throughput,
    healthy = true,
    verdict = "good"
)
