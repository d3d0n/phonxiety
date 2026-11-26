package com.phonxiety.bridge.feature.doctor

import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.data.PairResult
import com.phonxiety.bridge.data.PairingRepository
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallEventsPage
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.CallState
import com.phonxiety.bridge.model.CallSummary
import com.phonxiety.bridge.model.CallsPage
import com.phonxiety.bridge.model.CompatReport
import com.phonxiety.bridge.model.DaemonInfo
import com.phonxiety.bridge.model.DaemonStatus
import com.phonxiety.bridge.model.DeleteCallResult
import com.phonxiety.bridge.model.DiagnosticsBundle
import com.phonxiety.bridge.model.DoctorCheck
import com.phonxiety.bridge.model.DoctorFix
import com.phonxiety.bridge.model.DoctorFixResult
import com.phonxiety.bridge.model.DoctorReport
import com.phonxiety.bridge.model.DtmfResult
import com.phonxiety.bridge.model.InstructionResult
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.LinkTestResult
import com.phonxiety.bridge.model.LinkTests
import com.phonxiety.bridge.model.LoopbackResult
import com.phonxiety.bridge.model.MarksResponse
import com.phonxiety.bridge.model.PathKind
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
import com.phonxiety.bridge.root.AppFacts
import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.Overlay
import com.phonxiety.bridge.root.PrivilegedPermissions
import com.phonxiety.bridge.root.ProviderResult
import com.phonxiety.bridge.root.RootDetection
import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.RuntimePermission
import com.phonxiety.bridge.root.SuStatus
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** Fixtures the policy, evaluator and ViewModel tests share. */
object TestData {
    val magisk = DetectedProvider(
        id = RootId.MAGISK,
        version = "31.1:MAGISK",
        managerPackage = "com.topjohnwu.magisk",
        managerInstalled = true
    )

    fun root(su: SuStatus = SuStatus.GRANTED, providers: List<DetectedProvider> = listOf(magisk)) = RootDetection(su = su, providers = providers)

    fun facts(
        apkPath: String = "/system/priv-app/phonxiety-bridge/base.apk",
        system: Boolean = true,
        updated: Boolean = false,
        granted: Set<String> = PrivilegedPermissions.ALL
    ) = AppFacts(
        packageName = "com.phonxiety.bridge",
        isSystemApp = system,
        apkPath = apkPath,
        grantedPermissions = granted,
        updatedSystemApp = updated
    )

    fun snapshot(
        root: RootDetection = root(),
        module: ModuleState? = ModuleState.Enabled(version = "0.3.0", versionCode = 3),
        overlay: Overlay? = Overlay.BUILT_IN,
        facts: AppFacts? = facts(),
        runtimeMissing: Set<RuntimePermission> = emptySet(),
        notifications: Boolean = true,
        battery: Boolean? = true
    ) = DoctorSnapshot(
        root = root,
        module = module,
        overlay = overlay,
        appFacts = facts,
        runtimeMissing = runtimeMissing,
        notificationsEnabled = notifications,
        batteryExempt = battery,
        checkedAtEpochMs = 1_796_500_000_000L
    )

    fun bridge(
        link: LinkState = LinkState.Up(
            kind = LinkKind.IROH,
            path = PathKind.DIRECT,
            address = "iroh 9f42c1",
            rttMs = 40,
            sinceEpochMs = 1_796_490_000_000L,
            reconnects = 0
        ),
        call: CallState? = null
    ) = BridgeState(link = link, call = call)

    fun call(phase: CallPhase = CallPhase.ACTIVE) = CallState(
        callId = "call-1",
        destinationMasked = "+998 •• •• 4412",
        phase = phase,
        startedAtEpochMs = 1_796_499_000_000L
    )

    fun report(
        status: String = "ok",
        checks: List<DoctorCheck> = listOf(
            DoctorCheck("daemon", "ok", "phonxietyd answering (pid 42)"),
            DoctorCheck("phone", "ok", "Pixel 6a connected"),
            DoctorCheck("voice_backend", "ok", "gemini is configured"),
            DoctorCheck("disk", "ok", "18 GiB free"),
            DoctorCheck("relays", "ok", "using iroh's public relays")
        ),
        fixes: List<DoctorFix> = listOf(
            DoctorFix("relay_config", "re-read the relay list from disk", restartRequired = true),
            DoctorFix("state_dirs", "recreate the archive and diagnostics directories", restartRequired = false)
        )
    ) = DoctorReport(
        status = status,
        checks = checks,
        fixes = fixes,
        checkedAt = "2026-11-26T09:00:00+00:00"
    )

    fun rows(
        snapshot: DoctorSnapshot = snapshot(),
        bridge: BridgeState = bridge(),
        paired: Boolean = true,
        daemon: DaemonProbe = DaemonProbe.Reached(report()),
        callActive: Boolean = false
    ): List<DoctorRow> = DoctorEvaluator.evaluate(snapshot, bridge, paired, daemon, callActive)

    fun row(rows: List<DoctorRow>, id: String): DoctorRow = rows.first { it.id == id }
}

/** A scripted device: one snapshot now, an optional one after the first read, and recorded actions. */
class FakeDoctorSource(
    var snapshot: DoctorSnapshot,
    var afterFirstRead: DoctorSnapshot? = null,
    var enableResult: ProviderResult = ProviderResult.Success("enabled"),
    var reinstallResult: ProviderResult = ProviderResult.Success("installed"),
    var rebootResult: ProviderResult = ProviderResult.Success("rebooting")
) : DoctorSource {
    var reads: Int = 0
        private set
    var readFailure: Throwable? = null
    var enables: Int = 0
        private set
    var reinstalls: Int = 0
        private set
    var reboots: Int = 0
        private set

    override suspend fun read(): DoctorSnapshot {
        readFailure?.let { throw it }
        reads += 1
        val after = afterFirstRead
        return if (reads > 1 && after != null) after else snapshot
    }

    override suspend fun enableModule(): ProviderResult {
        enables += 1
        return enableResult
    }

    override suspend fun reinstallModule(): ProviderResult {
        reinstalls += 1
        return reinstallResult
    }

    override suspend fun reboot(): ProviderResult {
        reboots += 1
        return rebootResult
    }
}

class FakeServiceControl : DoctorServiceControl {
    var restarts: Int = 0
        private set

    override fun restart() {
        restarts += 1
    }
}

class FakeChangeLog : DoctorChangeLog {
    val recorded: MutableList<DoctorChangeEntry> = mutableListOf()

    override fun entries(): List<DoctorChangeEntry> = recorded.toList()

    override fun record(entry: DoctorChangeEntry) {
        recorded += entry
    }
}

class FakeBridgeState(initial: BridgeState = BridgeState()) : BridgeStateRepository {
    val flow = MutableStateFlow(initial)
    override val state: StateFlow<BridgeState> = flow
}

class FakePairing(paired: Boolean = true) : PairingRepository {
    val state = MutableStateFlow(paired)
    override val paired: StateFlow<Boolean> = state
    var pairResult: PairResult = PairResult.Paired

    override suspend fun pair(link: String): PairResult {
        if (pairResult is PairResult.Paired) state.value = true
        return pairResult
    }

    override suspend fun unpair() {
        state.value = false
    }
}

/** Doctor's two daemon calls are scripted; every other endpoint fails loudly if it is reached. */
class FakeDaemonApi : DaemonApi {
    var doctorOutcome: ApiOutcome<DoctorReport> =
        ApiOutcome.Failure(503, "link_down", "the bridge service is not running")
    var fixOutcome: ApiOutcome<DoctorFixResult> = ApiOutcome.Ok(200, DoctorFixResult())
    var callsOutcome: ApiOutcome<CallsPage> = ApiOutcome.Ok(200, CallsPage())
    val appliedFixes: MutableList<String> = mutableListOf()

    override suspend fun doctor(): ApiOutcome<DoctorReport> = doctorOutcome

    override suspend fun doctorFix(fix: String): ApiOutcome<DoctorFixResult> {
        appliedFixes += fix
        return fixOutcome
    }

    override suspend fun calls(limit: Int?, offset: Int?, before: Double?, outcome: String?, farEnd: String?) = callsOutcome

    override suspend fun info(): ApiOutcome<DaemonInfo> = unused()
    override suspend fun status(): ApiOutcome<DaemonStatus> = unused()
    override suspend fun linkTest(
        seconds: Double?,
        codec: String?,
        transport: String?,
        bitrate: Int?
    ): ApiOutcome<LinkTestResult> = unused()
    override suspend fun linkTests(limit: Int?): ApiOutcome<LinkTests> = unused()
    override suspend fun probe(candidates: List<String>?): ApiOutcome<ProbeResult> = unused()
    override suspend fun placeCall(request: PlaceCallRequest): ApiOutcome<CallSnapshot> = unused()
    override suspend fun validateCall(request: PlaceCallRequest): ApiOutcome<ValidationResult> = unused()
    override suspend fun testCall(request: TestCallRequest): ApiOutcome<CallSnapshot> = unused()
    override suspend fun call(callId: String): ApiOutcome<CallSnapshot> = unused()
    override suspend fun transcript(callId: String, cursor: Long?, limit: Int?): ApiOutcome<TranscriptPage> = unused()
    override suspend fun events(callId: String, since: Long?, waitSeconds: Double?): ApiOutcome<CallEventsPage> = unused()
    override suspend fun instruction(callId: String, text: String): ApiOutcome<InstructionResult> = unused()
    override suspend fun pause(callId: String): ApiOutcome<PauseResult> = unused()
    override suspend fun resume(callId: String): ApiOutcome<PauseResult> = unused()
    override suspend fun summarize(callId: String): ApiOutcome<SummaryResult> = unused()
    override suspend fun dtmf(callId: String, digits: String): ApiOutcome<DtmfResult> = unused()
    override suspend fun hangup(callId: String): ApiOutcome<CallSnapshot> = unused()
    override suspend fun marks(callId: String): ApiOutcome<MarksResponse> = unused()
    override suspend fun addMark(callId: String, mark: String, note: String?): ApiOutcome<MarksResponse> = unused()
    override suspend fun deleteCall(callId: String): ApiOutcome<DeleteCallResult> = unused()
    override suspend fun settings(): ApiOutcome<SettingsSnapshot> = unused()
    override suspend fun putSettings(values: JsonObject): ApiOutcome<SettingsUpdate> = unused()
    override suspend fun loopback(enabled: Boolean, callId: String?): ApiOutcome<LoopbackResult> = unused()
    override suspend fun tone(hz: Int?, dbfs: Double?, seconds: Double?, callId: String?): ApiOutcome<ToneResult> = unused()
    override suspend fun diagnostics(): ApiOutcome<JsonObject> = unused()
    override suspend fun createDiagnosticsBundle(): ApiOutcome<DiagnosticsBundle> = unused()
    override suspend fun updateManifest(channel: String?): ApiOutcome<JsonObject> = unused()
    override suspend fun compat(appApi: Int?, protocol: Int?): ApiOutcome<CompatReport> = unused()
    override suspend fun rotatePair(rotateDeviceToken: Boolean): ApiOutcome<RotateResult> = unused()
    override suspend fun downloadAudio(callId: String, format: String, sink: OutputStream): ApiResult = unused()
    override suspend fun downloadBundle(bundleId: String, sink: OutputStream): ApiResult = unused()
    override suspend fun downloadUpdateArtifact(name: String, sink: OutputStream): ApiResult = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by Doctor")
}
