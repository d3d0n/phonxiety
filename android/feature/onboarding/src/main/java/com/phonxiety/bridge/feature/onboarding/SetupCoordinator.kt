package com.phonxiety.bridge.feature.onboarding

import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.AppApi
import com.phonxiety.bridge.data.PairResult
import com.phonxiety.bridge.data.PairingRepository
import com.phonxiety.bridge.root.ModuleController
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.Overlay
import com.phonxiety.bridge.root.ProbeSnapshot
import com.phonxiety.bridge.root.ProgressStore
import com.phonxiety.bridge.root.ProviderResult
import com.phonxiety.bridge.root.RootDetection
import com.phonxiety.bridge.root.SuStatus
import com.phonxiety.bridge.root.WizardEffect
import com.phonxiety.bridge.root.WizardEvent
import com.phonxiety.bridge.root.WizardState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Things only the hosting UI can do: system dialogs, the scanner, navigation. */
sealed interface SetupHostRequest {
    data class RequestRuntimePermissions(val permissions: List<String>) : SetupHostRequest

    data object RequestBatteryExemption : SetupHostRequest

    data object StartPairing : SetupHostRequest

    data class PairingRejected(val reason: String) : SetupHostRequest

    data class Message(val text: String) : SetupHostRequest

    /** The wizard is Ready and the service has been started. */
    data object Finished : SetupHostRequest
}

/**
 * Performs the wizard's [WizardEffect]s and nothing else: it owns no Android UI, no camera and no
 * navigation. The reducer ([ModuleController]) decides; this class gathers a [ProbeSnapshot],
 * runs provider commands, persists progress across the reboot, asks the host for dialogs and
 * reports back through [WizardEvent]s.
 */
class SetupCoordinator(
    private val backend: RootBackend,
    private val pairing: PairingRepository,
    private val appApi: AppApi,
    private val progressStore: ProgressStore,
    private val preferences: SetupPreferences,
    private val permissions: RuntimePermissionChecker,
    private val serviceStarter: () -> Unit,
    private val host: (SetupHostRequest) -> Unit
) {
    private val mutex = Mutex()
    private val _wizard = MutableStateFlow<WizardState>(WizardState.Checking)
    val wizard: StateFlow<WizardState> = _wizard.asStateFlow()

    private var detection: RootDetection? = null

    /** First entry and every "resume after reboot": re-derive the state from the device. */
    suspend fun start() {
        dispatch(WizardEvent.RecheckRequested)
    }

    suspend fun dispatch(event: WizardEvent) = mutex.withLock { handle(event) }

    /** Validates and stores a `phonxiety://pair` link or a scanned QR payload. */
    suspend fun pair(link: String) {
        val result = runCatching { pairing.pair(link) }
            .getOrElse { error -> PairResult.Invalid(error.message ?: "the pairing link could not be read") }
        when (result) {
            PairResult.Paired -> {
                host(SetupHostRequest.Message("Paired with the computer"))
                if (_wizard.value is WizardState.NeedsPairing) {
                    dispatch(WizardEvent.PairingCompleted)
                }
            }

            is PairResult.Invalid -> host(SetupHostRequest.PairingRejected(result.reason))
        }
    }

    fun requestRuntimePermissions() {
        val missing = permissions.missing()
        if (missing.isEmpty()) return
        host(SetupHostRequest.RequestRuntimePermissions(missing.map { it.androidName }))
    }

    fun requestBatteryExemption() {
        host(SetupHostRequest.RequestBatteryExemption)
    }

    /** Called after the system dialog returns, so the wizard moves on the device's answer. */
    suspend fun onPermissionsChanged() {
        dispatch(WizardEvent.RuntimePermissionsChanged(permissions.missing()))
    }

    private suspend fun handle(event: WizardEvent) {
        val transition = ModuleController.reduce(_wizard.value, event)
        _wizard.value = transition.state
        transition.effects.forEach { perform(it) }
    }

    private suspend fun perform(effect: WizardEffect) {
        when (effect) {
            WizardEffect.RunProbe, WizardEffect.RunVerify -> probeAndFeed()

            is WizardEffect.InstallModule -> installAndFeed(effect)

            is WizardEffect.PersistProgress -> runCatching {
                progressStore.begin(effect.operation, effect.provider)
            }

            WizardEffect.ClearProgress -> runCatching { progressStore.clear() }

            WizardEffect.RequestRuntimePermissions -> {
                requestRuntimePermissions()
                requestBatteryExemption()
            }

            WizardEffect.StartPairing -> host(SetupHostRequest.StartPairing)

            WizardEffect.RunLinkTest -> runLinkTest()

            WizardEffect.StartService -> {
                runCatching { serviceStarter() }
                preferences.ready = true
                host(SetupHostRequest.Finished)
            }

            is WizardEffect.Reboot -> {
                val provider = detection?.provider
                if (provider == null) {
                    host(SetupHostRequest.Message("no root provider was found; reboot from the power menu"))
                } else {
                    runCatching { backend.reboot(provider, effect.reason) }
                        .onFailure { host(SetupHostRequest.Message(it.message ?: "the reboot request failed")) }
                }
            }
        }
    }

    private suspend fun probeAndFeed() {
        val snapshot = try {
            gatherSnapshot()
        } catch (error: Throwable) {
            _wizard.value = WizardState.Indeterminate(
                message = error.message ?: "the device could not be read",
                output = error.stackTraceToString().lineSequence().take(20).joinToString("\n")
            )
            return
        }
        handle(WizardEvent.ProbeCompleted(snapshot))
    }

    private suspend fun gatherSnapshot(): ProbeSnapshot {
        val detected = backend.detect()
        detection = detected
        val provider = detected.provider
        val rooted = detected.su == SuStatus.GRANTED && provider != null
        val module = if (rooted) backend.moduleState(provider) else ModuleState.Unknown
        val capabilities = if (rooted) backend.capabilities(provider) else null
        val verify = if (module is ModuleState.Enabled) backend.verify(module) else null
        val paired = pairing.paired.value
        return ProbeSnapshot(
            detection = detected,
            module = module,
            overlay = capabilities?.systemOverlay ?: Overlay.BUILT_IN,
            verify = verify,
            missingRuntime = permissions.missing(),
            paired = paired,
            linkTestPassed = if (paired) preferences.linkTestPassed else null,
            progress = runCatching { progressStore.read() }.getOrNull()
        )
    }

    private suspend fun installAndFeed(effect: WizardEffect.InstallModule) {
        val provider = detection?.providers?.firstOrNull { it.id == effect.provider }
        if (provider == null) {
            handle(
                WizardEvent.InstallResult(
                    ProviderResult.Failure(
                        output = "the ${effect.provider.name} provider disappeared between probes",
                        exitCode = 127
                    )
                )
            )
            return
        }
        val result = try {
            backend.install(provider, effect.operation)
        } catch (error: Throwable) {
            ProviderResult.Failure(
                output = error.message ?: "the provider command threw",
                exitCode = 1
            )
        }
        handle(WizardEvent.InstallResult(result))
    }

    private suspend fun runLinkTest() {
        val outcome = withTimeoutOrNull(LINK_TEST_TIMEOUT_MS) {
            runCatching {
                appApi.request(
                    method = "POST",
                    path = "/app/v1/link-test",
                    body = buildJsonObject { put("seconds", LINK_TEST_SECONDS) }
                )
            }.getOrNull()
        }
        val event = when (outcome) {
            null -> WizardEvent.LinkTestCompleted(false, "the daemon did not answer within ${LINK_TEST_TIMEOUT_MS / 1000} s")

            is ApiResult.Success -> {
                val record = outcome.result as? JsonObject
                val healthy = record?.get("healthy")?.jsonPrimitive?.booleanOrNull == true
                if (healthy) preferences.linkTestPassed = true
                WizardEvent.LinkTestCompleted(healthy, describeLinkTest(record))
            }

            is ApiResult.Failure -> WizardEvent.LinkTestCompleted(
                false,
                "${outcome.code}: ${outcome.message}"
            )
        }
        handle(event)
    }

    private fun describeLinkTest(record: JsonObject?): String {
        if (record == null) return "the daemon returned no link-test record"
        val verdict = record["verdict"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val p50 = record["p50"]?.jsonPrimitive?.contentOrNull ?: "—"
        val loss = record["loss"]?.jsonPrimitive?.contentOrNull ?: "—"
        return "verdict $verdict · p50 $p50 ms · loss $loss"
    }

    private companion object {
        const val LINK_TEST_SECONDS = 5
        const val LINK_TEST_TIMEOUT_MS = 25_000L
    }
}
