package com.phonxiety.bridge.feature.onboarding

import com.phonxiety.bridge.BridgeRuntime
import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.AppApi
import com.phonxiety.bridge.data.PairResult
import com.phonxiety.bridge.data.PairingRepository
import com.phonxiety.bridge.model.LinkState
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val host: (SetupHostRequest) -> Unit,
    /** The bridge's link state; a stored pairing is not a live transport. */
    private val linkState: Flow<LinkState> = BridgeRuntime.state.map { it.link }
) {
    private val mutex = Mutex()
    private val _wizard = MutableStateFlow<WizardState>(WizardState.Checking)
    val wizard: StateFlow<WizardState> = _wizard.asStateFlow()

    private var detection: RootDetection? = null

    /**
     * The last detection, for the parts of the screen that are about the phone rather than about
     * the step: the leftovers of a provider the phone migrated away from.
     */
    private val _detection = MutableStateFlow<RootDetection?>(null)
    val rootDetection: StateFlow<RootDetection?> = _detection.asStateFlow()

    /**
     * A scanner or paste attempt is running. Observations of the stored pairing must not advance the
     * wizard while the attempt's own outcome decides what the person is told.
     */
    private var localPairingInFlight = false

    /** The automatic scan is one per visit to "Unpaired", never one per probe or recomposition. */
    private var autoPairingLaunched = false

    /**
     * Identifies the reboot request whose survival window is open, and whether one is. The window
     * is a plain delay on the caller's coroutine, so an id is what tells a timer left over from an
     * earlier attempt not to report on the attempt that is running now.
     */
    private var rebootAttempt = 0L
    private var rebootInFlight = false

    /** First entry and every "resume after reboot": re-derive the state from the device. */
    suspend fun start() {
        dispatch(WizardEvent.RecheckRequested)
    }

    suspend fun dispatch(event: WizardEvent) = mutex.withLock { handle(event) }

    /**
     * A stored pairing appeared while the wizard sat at "Unpaired": `mise run phone:pair`, the
     * service rewriting its config, or a link change the repository noticed. Being readable is all
     * it takes — a rejected attempt leaves no readable config, so the flag is never the leftover of
     * one — and the admission check runs under the same lock as every transition, so an observation
     * that lost the race is a no-op: a probe or first link test already admitted has moved the
     * wizard on, and an unresolved local attempt keeps the wizard where it stands.
     */
    suspend fun onPairingObserved() {
        mutex.withLock {
            if (localPairingInFlight) return@withLock
            if (_wizard.value !is WizardState.NeedsPairing) return@withLock
            // The probe a resume would run, so the wizard lands on the same next step by itself.
            handle(WizardEvent.RecheckRequested)
        }
    }

    /**
     * The probe a resume runs, admitted together with pairing observations so they cannot both act
     * on the same stored pairing. [unpairedAtRequest] is the wizard state when the resume was asked
     * for, read before the refresh: if the wizard was at "Unpaired" then and the observation (or a
     * pairing attempt) answered it while this call waited for the lock, probing again would repeat
     * the first link test or emit a second [SetupHostRequest.Finished].
     */
    suspend fun probeOnResume(unpairedAtRequest: Boolean) {
        mutex.withLock {
            if (unpairedAtRequest && _wizard.value !is WizardState.NeedsPairing) return@withLock
            handle(WizardEvent.RecheckRequested)
        }
    }

    /** Validates and stores a `phonxiety://pair` link or a scanned QR payload. */
    suspend fun pair(link: String) {
        mutex.withLock {
            localPairingInFlight = true
            try {
                val result = runCatching { pairing.pair(link) }
                    .getOrElse { error ->
                        PairResult.Invalid(error.message ?: "the pairing link could not be read")
                    }
                when (result) {
                    PairResult.Paired -> {
                        host(SetupHostRequest.Message("Paired with the computer"))
                        if (_wizard.value is WizardState.NeedsPairing) {
                            handle(WizardEvent.PairingCompleted)
                        }
                    }

                    is PairResult.Invalid -> host(SetupHostRequest.PairingRejected(result.reason))
                }
            } finally {
                localPairingInFlight = false
            }
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

    /**
     * "Reboot now". A real reboot takes this process down, so still being here after the survival
     * window means the phone did not restart, whichever way the command exited. One request at a
     * time: a tap while one is pending is the same request, and only the timer that belongs to the
     * running attempt may report on it. The wait runs on the caller's coroutine, outside the lock:
     * a resume must not queue behind it.
     */
    suspend fun requestReboot() {
        val attempt = mutex.withLock { beginReboot() } ?: return
        try {
            delay(REBOOT_SURVIVAL_WINDOW_MS)
            mutex.withLock {
                if (attempt != rebootAttempt) return@withLock
                rebootInFlight = false
                handle(WizardEvent.RebootNotConfirmed)
            }
        } finally {
            // Cancelled before the window elapsed: the request is over and reports nothing, but the
            // next tap must be able to start a fresh attempt.
            withContext(NonCancellable) {
                mutex.withLock { if (attempt == rebootAttempt) rebootInFlight = false }
            }
        }
    }

    /** Called under the lock: admits one request and returns the attempt its timer belongs to. */
    private suspend fun beginReboot(): Long? {
        if (rebootInFlight) return null
        if (_wizard.value !is WizardState.RebootPending) return null
        rebootInFlight = true
        val attempt = ++rebootAttempt
        handle(WizardEvent.RebootRequested)
        val pending = _wizard.value as? WizardState.RebootPending
        if (pending == null || pending.failure != null) {
            // The command itself failed (or the step moved on): there is no window to wait out.
            rebootInFlight = false
            return null
        }
        return attempt
    }

    private fun rebootDetail(result: ProviderResult): String = result.tail(lines = 6)
        .joinToString("\n")
        .ifEmpty { "the provider printed nothing" }

    /** Called after the system dialog returns, so the wizard moves on the device's answer. */
    suspend fun onPermissionsChanged() {
        dispatch(WizardEvent.RuntimePermissionsChanged(permissions.missing()))
    }

    private suspend fun handle(event: WizardEvent) {
        val transition = ModuleController.reduce(_wizard.value, event)
        _wizard.value = transition.state
        if (transition.state !is WizardState.NeedsPairing) {
            // Leaving "Unpaired" ends the visit: a later visit may auto-launch again.
            autoPairingLaunched = false
        }
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

            WizardEffect.StartPairing -> {
                // One automatic scanner per visit to "Unpaired". A probe round trip, a resume or a
                // recomposition must not reopen it (and reopening after a Cancelled scan would be
                // endless); the explicit Scan button bypasses this and always works. A request that
                // arrives after the wizard advanced is dropped instead of covering the next step.
                if (_wizard.value is WizardState.NeedsPairing && !autoPairingLaunched) {
                    autoPairingLaunched = true
                    host(SetupHostRequest.StartPairing)
                }
            }

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
                    val result = runCatching { backend.reboot(provider, effect.reason) }
                        .getOrElse { ProviderResult.Failure(it.message ?: "the reboot request threw", 1) }
                    // Never silent: a reboot that did not happen must not look like one that did.
                    if (!result.isSuccess) handle(WizardEvent.RebootFailed(rebootDetail(result)))
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
        _detection.value = detected
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
            // Only a pass is remembered: a past failure says nothing about the link now, so every
            // probe after one runs the test again instead of parking the wizard on the old result.
            linkTestPassed = if (paired && preferences.linkTestPassed == true) true else null,
            progress = runCatching { progressStore.read() }.getOrNull(),
            metamodulePending = capabilities?.metamodulePending ?: false,
            metamoduleInterrupted = capabilities?.metamoduleInterrupted ?: false,
            overlayProbeError = capabilities?.overlayProbeError
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

    /**
     * The first test needs a live link, not just a stored pairing: the scanner, paste and
     * `mise run phone:pair` all publish `paired` before the transport comes up, and a 503 from a
     * still-connecting link must not be recorded as a failed test. One request follows the wait.
     */
    private suspend fun awaitLink(): Boolean = withTimeoutOrNull(LINK_READY_TIMEOUT_MS) {
        linkState.first { it is LinkState.Up }
    } != null

    private suspend fun runLinkTest() {
        if (!awaitLink()) {
            handle(
                WizardEvent.LinkTestCompleted(
                    false,
                    "the link to the computer did not come up within ${LINK_READY_TIMEOUT_MS / 1000} s; " +
                        "check that the computer is running and reachable, then retry"
                )
            )
            return
        }
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
        const val LINK_READY_TIMEOUT_MS = 30_000L
        const val REBOOT_SURVIVAL_WINDOW_MS = ModuleController.REBOOT_SURVIVAL_WINDOW_SECONDS * 1000L
        const val LINK_TEST_SECONDS = 5
        const val LINK_TEST_TIMEOUT_MS = 25_000L
    }
}
