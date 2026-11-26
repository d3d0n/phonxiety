package com.phonxiety.bridge.feature.onboarding

import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.AppApi
import com.phonxiety.bridge.data.PairResult
import com.phonxiety.bridge.data.PairingRepository
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.InstallOperation
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.Overlay
import com.phonxiety.bridge.root.ProgressRecord
import com.phonxiety.bridge.root.ProgressStore
import com.phonxiety.bridge.root.ProviderResult
import com.phonxiety.bridge.root.RebootReason
import com.phonxiety.bridge.root.RootCapabilities
import com.phonxiety.bridge.root.RootDetection
import com.phonxiety.bridge.root.RuntimePermission
import com.phonxiety.bridge.root.SuStatus
import com.phonxiety.bridge.root.VerifyCheck
import com.phonxiety.bridge.root.VerifyOutcome
import com.phonxiety.bridge.root.VerifyReport
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Scripted root backend: records calls, returns configured results. */
class FakeBackend : RootBackend {
    var detection: RootDetection = RootDetection(
        su = SuStatus.NOT_ROOTED,
        providers = emptyList()
    )
    var module: ModuleState = ModuleState.Unknown
    var capabilities: RootCapabilities = RootCapabilities(
        systemOverlay = Overlay.BUILT_IN,
        hotInstall = false,
        updateJson = false,
        webUi = false,
        actionButton = false,
        bootStages = emptySet(),
        managerPackage = null,
        coexistenceWarning = null
    )
    var verify: VerifyReport = VerifyReport(VerifyCheck.entries.associateWith { VerifyOutcome.PASS })
    var installResult: ProviderResult = ProviderResult.Success("installed")
    var rebootResult: ProviderResult = ProviderResult.Success("rebooting")

    val installs = mutableListOf<InstallOperation>()
    val reboots = mutableListOf<RebootReason>()

    /** Probes that reached [detect]; an observation that must not probe leaves it unchanged. */
    var detects = 0

    /** Suspends [detect] until completed, for interleaving an observation with a probe. */
    var detectGate: CompletableDeferred<Unit>? = null

    override suspend fun detect(): RootDetection {
        detects++
        detectGate?.await()
        return detection
    }

    override suspend fun capabilities(provider: DetectedProvider): RootCapabilities = capabilities

    override suspend fun moduleState(provider: DetectedProvider): ModuleState = module

    override suspend fun verify(module: ModuleState): VerifyReport = verify

    override suspend fun install(provider: DetectedProvider, operation: InstallOperation): ProviderResult {
        installs += operation
        return installResult
    }

    override suspend fun reboot(provider: DetectedProvider, reason: RebootReason): ProviderResult {
        reboots += reason
        return rebootResult
    }
}

class FakePairing : PairingRepository {
    val pairedState = MutableStateFlow(false)
    override val paired: StateFlow<Boolean> = pairedState
    var pairResult: PairResult = PairResult.Paired

    /** Suspends the attempt until completed, for interleaving an observation with a pairing. */
    var pairGate: CompletableDeferred<Unit>? = null

    /** Only a stored pairing publishes: a rejected attempt leaves nothing readable behind. */
    override suspend fun pair(link: String): PairResult {
        pairGate?.await()
        if (pairResult is PairResult.Paired) pairedState.value = true
        return pairResult
    }

    override suspend fun unpair() {
        pairedState.value = false
    }
}

class FakeAppApi : AppApi {
    var linkTest: ApiResult = ApiResult.Failure(500, "not_configured", "no link test configured")
    val requestedBodies = mutableListOf<Map<String, JsonElement>>()
    val requestedPaths = mutableListOf<String>()

    /** Suspends a request after it is recorded, for keeping a link test in flight. */
    var requestGate: CompletableDeferred<Unit>? = null

    override suspend fun request(
        method: String,
        path: String,
        query: Map<String, String>,
        body: JsonObject?
    ): ApiResult {
        requestedPaths += path
        if (body != null) requestedBodies += body
        requestGate?.await()
        return linkTest
    }

    override suspend fun download(path: String, query: Map<String, String>, sink: OutputStream): ApiResult = ApiResult.Failure(501, "not_used", "downloads are not used by these tests")
}

class FakeProgressStore : ProgressStore {
    var record: ProgressRecord? = null

    override fun read(): ProgressRecord? = record

    override fun begin(operation: InstallOperation, provider: com.phonxiety.bridge.root.RootId) {
        record = ProgressRecord(operation, provider, startedAtMillis = 0)
    }

    override fun clear() {
        record = null
    }
}

class FakePermissions : RuntimePermissionChecker {
    var missingState: MutableList<RuntimePermission> = mutableListOf()

    override fun missing(): List<RuntimePermission> = missingState.toList()
}

/** A live link, for tests whose first link test must actually run. */
fun readyLink(): MutableStateFlow<LinkState> = MutableStateFlow(
    LinkState.Up(
        kind = LinkKind.IROH,
        path = PathKind.DIRECT,
        address = null,
        rttMs = 12,
        sinceEpochMs = 0,
        reconnects = 0
    )
)

/** A transport that has not come up yet: a stored pairing without a live link. */
fun connectingLink(): MutableStateFlow<LinkState> = MutableStateFlow(LinkState.Connecting(LinkKind.IROH))
