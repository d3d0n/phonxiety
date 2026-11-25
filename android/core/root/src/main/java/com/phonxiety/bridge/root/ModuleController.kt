package com.phonxiety.bridge.root

import java.io.File
import java.util.Properties

/** Runtime permissions the wizard asks for in the NeedsRuntime state. */
enum class RuntimePermission(val androidName: String) {
    RECORD_AUDIO("android.permission.RECORD_AUDIO"),
    CALL_PHONE("android.permission.CALL_PHONE"),
    READ_PHONE_STATE("android.permission.READ_PHONE_STATE"),
    MODIFY_AUDIO_SETTINGS("android.permission.MODIFY_AUDIO_SETTINGS"),
    POST_NOTIFICATIONS("android.permission.POST_NOTIFICATIONS"),
    ACCESS_LOCAL_NETWORK("android.permission.ACCESS_LOCAL_NETWORK")
}

/** What the wizard is trying to do while it is in [WizardState.Installing]. */
enum class InstallOperation { INSTALL, UPDATE, ROLLBACK, PREPARE_METAMODULE }

/** Why the wizard stopped on Doctor; each reason names the fix. */
enum class DoctorReason {
    VERIFY_FAILED,
    MODULE_DISABLED,
    MODULE_PENDING_REMOVAL,
    LINK_TEST_FAILED
}

/** The install in progress, persisted so the wizard resumes after the reboot it caused. */
data class ProgressRecord(
    val operation: InstallOperation,
    val provider: RootId,
    val startedAtMillis: Long
)

/**
 * Persists [ProgressRecord] across process death and reboot. Injectable: the app passes a file in
 * its files dir, tests pass their own.
 */
interface ProgressStore {
    fun read(): ProgressRecord?

    fun begin(operation: InstallOperation, provider: RootId)

    fun clear()
}

/** A tiny properties file; a module install either happened or it did not, so no journal is needed. */
class FileProgressStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis
) : ProgressStore {
    override fun read(): ProgressRecord? {
        if (!file.isFile) return null
        val properties = runCatching {
            Properties().apply { file.inputStream().use { load(it) } }
        }.getOrNull() ?: return null
        val operation = properties.getProperty(KEY_OPERATION)
            ?.let { name -> InstallOperation.entries.firstOrNull { it.name == name } }
            ?: return null
        val provider = properties.getProperty(KEY_PROVIDER)
            ?.let { name -> RootId.entries.firstOrNull { it.name == name } }
            ?: return null
        val startedAt = properties.getProperty(KEY_STARTED)?.toLongOrNull() ?: 0L
        return ProgressRecord(operation, provider, startedAt)
    }

    override fun begin(operation: InstallOperation, provider: RootId) {
        file.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty(KEY_OPERATION, operation.name)
            setProperty(KEY_PROVIDER, provider.name)
            setProperty(KEY_STARTED, clock().toString())
        }
        val temporary = File(file.path + ".tmp")
        temporary.outputStream().use { properties.store(it, null) }
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    override fun clear() {
        file.delete()
        File(file.path + ".tmp").delete()
    }

    private companion object {
        const val KEY_OPERATION = "operation"
        const val KEY_PROVIDER = "provider"
        const val KEY_STARTED = "startedAtMillis"
    }
}

/**
 * Everything the wizard needs to decide its state, gathered by the caller in one pass:
 * provider detection, module directory, overlay capability, the verify report, runtime grants,
 * pairing and link-test status, plus any install left in progress by a previous boot.
 */
data class ProbeSnapshot(
    val detection: RootDetection,
    val module: ModuleState = ModuleState.Unknown,
    val overlay: Overlay = Overlay.BUILT_IN,
    val verify: VerifyReport? = null,
    val missingRuntime: List<RuntimePermission> = emptyList(),
    val paired: Boolean = false,
    val linkTestPassed: Boolean? = null,
    val progress: ProgressRecord? = null
)

/** The install/verify wizard state machine. The UI renders it; it holds no Android types. */
sealed interface WizardState {
    data object Checking : WizardState

    /** One provider answered; the ordering of the list is the detector's stable order. */
    data class NoRoot(val providers: List<DetectedProvider> = emptyList()) : WizardState

    data class RootDenied(
        val provider: DetectedProvider?,
        val output: String? = null
    ) : WizardState

    data class ProviderPrep(
        val provider: DetectedProvider,
        val reason: PrepReason = PrepReason.METAMODULE_MISSING
    ) : WizardState

    data class ReadyToInstall(
        val provider: DetectedProvider,
        val module: ModuleState = ModuleState.Absent,
        val update: Boolean = false
    ) : WizardState

    data class Installing(
        val provider: DetectedProvider,
        val operation: InstallOperation,
        val module: ModuleState = ModuleState.Unknown
    ) : WizardState

    data class InstallFailed(
        val provider: DetectedProvider,
        val operation: InstallOperation,
        val module: ModuleState,
        val result: ProviderResult,
        val outputTail: List<String> = emptyList()
    ) : WizardState

    data class RebootPending(
        val provider: DetectedProvider,
        val reason: RebootReason,
        val module: ModuleState = ModuleState.Unknown
    ) : WizardState

    data class Verify(
        val provider: DetectedProvider?,
        val report: VerifyReport? = null
    ) : WizardState

    data class NeedsRuntime(
        val provider: DetectedProvider?,
        val missing: List<RuntimePermission>
    ) : WizardState

    data class NeedsPairing(val provider: DetectedProvider?) : WizardState

    data class FirstLinkTest(
        val provider: DetectedProvider?,
        val detail: String? = null
    ) : WizardState

    data object Ready : WizardState

    data class Doctor(
        val provider: DetectedProvider?,
        val reason: DoctorReason,
        val report: VerifyReport? = null,
        val module: ModuleState = ModuleState.Unknown,
        val detail: String? = null
    ) : WizardState

    /** A probe could not answer; the captured output is shown instead of a guess. */
    data class Indeterminate(val message: String, val output: String? = null) : WizardState

    /** More than one root provider is present: installing would be a guess, so it is refused. */
    data class Conflict(val providers: List<DetectedProvider>) : WizardState
}

/** Why [WizardState.ProviderPrep] is showing. */
enum class PrepReason { METAMODULE_MISSING }

/** Everything that can move the wizard. Probe results are one event so the reducer stays pure. */
sealed interface WizardEvent {
    data class ProbeCompleted(val snapshot: ProbeSnapshot) : WizardEvent

    data object InstallRequested : WizardEvent

    data object RetryRequested : WizardEvent

    /** User acted outside the app (granted root, installed the metamodule): probe again. */
    data object RecheckRequested : WizardEvent

    data object PrepareProviderRequested : WizardEvent

    data object RollbackRequested : WizardEvent

    /** Module update offered from the Updates screen: asks for confirmation, then installs. */
    data object UpdateRequested : WizardEvent

    data class InstallResult(val result: ProviderResult) : WizardEvent

    data object RebootRequested : WizardEvent

    data class RuntimePermissionsChanged(val missing: List<RuntimePermission>) : WizardEvent

    data object PairingCompleted : WizardEvent

    data class LinkTestCompleted(val passed: Boolean, val detail: String? = null) : WizardEvent

    data object Repaired : WizardEvent
}

/** Side effects the hosting layer performs; the reducer itself never touches the device. */
sealed interface WizardEffect {
    data class InstallModule(val provider: RootId, val operation: InstallOperation) : WizardEffect

    data class Reboot(val reason: RebootReason) : WizardEffect

    data object RunProbe : WizardEffect

    data object RunVerify : WizardEffect

    data object RequestRuntimePermissions : WizardEffect

    data object StartPairing : WizardEffect

    data object RunLinkTest : WizardEffect

    data object StartService : WizardEffect

    data class PersistProgress(val operation: InstallOperation, val provider: RootId) : WizardEffect

    data object ClearProgress : WizardEffect
}

data class Transition(val state: WizardState, val effects: List<WizardEffect> = emptyList())

/**
 * Pure reducer for install, update, verify and rollback. The hosting layer gathers a
 * [ProbeSnapshot], feeds events, performs the emitted effects, and renders the state; every
 * decision (including resume after reboot) is made here so it is unit-testable.
 */
object ModuleController {
    const val MODULE_ID: String = "phonxiety_bridge"
    const val MODULE_NAME: String = "Phonxiety"

    fun reduce(state: WizardState, event: WizardEvent): Transition = when (event) {
        is WizardEvent.ProbeCompleted -> onProbe(event.snapshot)
        WizardEvent.InstallRequested -> onInstallRequested(state)
        WizardEvent.RetryRequested -> onRetry(state)
        WizardEvent.RecheckRequested -> Transition(WizardState.Checking, listOf(WizardEffect.RunProbe))
        WizardEvent.PrepareProviderRequested -> onPrepare(state)
        WizardEvent.RollbackRequested -> onRollback(state)
        WizardEvent.UpdateRequested -> onUpdateRequested(state)
        is WizardEvent.InstallResult -> onInstallResult(state, event.result)
        WizardEvent.RebootRequested -> onReboot(state)
        is WizardEvent.RuntimePermissionsChanged -> onRuntimePermissions(state, event.missing)
        WizardEvent.PairingCompleted -> onPaired(state)
        is WizardEvent.LinkTestCompleted -> onLinkTest(state, event)
        WizardEvent.Repaired -> onRepaired(state)
    }

    private fun onProbe(snapshot: ProbeSnapshot): Transition {
        val detection = snapshot.detection
        val provider = detection.provider
        return when {
            detection.su == SuStatus.NOT_ROOTED ->
                Transition(WizardState.NoRoot(detection.providers))

            detection.su == SuStatus.TIMED_OUT -> Transition(
                WizardState.Indeterminate(
                    "su did not answer within the ${RootShell.DEFAULT_TIMEOUT_MS / 1000} s budget",
                    tail(detection.suOutput)
                )
            )

            detection.su == SuStatus.DENIED -> Transition(
                WizardState.RootDenied(detection.provider, tail(detection.suOutput))
            )

            detection.providers.size > 1 ->
                Transition(WizardState.Conflict(detection.providers))

            provider == null -> Transition(
                WizardState.Indeterminate(
                    "root is granted but no supported root provider was found",
                    tail(detection.suOutput)
                )
            )

            else -> route(checkNotNull(provider), snapshot)
        }
    }

    private fun route(provider: DetectedProvider, snapshot: ProbeSnapshot): Transition = when (val module = snapshot.module) {
        ModuleState.Unknown -> Transition(
            WizardState.Indeterminate("the module directory under /data/adb could not be read")
        )

        ModuleState.Absent -> when {
            snapshot.overlay == Overlay.METAMODULE_MISSING ->
                Transition(WizardState.ProviderPrep(provider, PrepReason.METAMODULE_MISSING))

            else -> Transition(
                WizardState.ReadyToInstall(provider, module, update = false)
            )
        }

        is ModuleState.Staged -> Transition(
            WizardState.RebootPending(provider, RebootReason.MODULE_UPDATE, module),
            listOf(WizardEffect.PersistProgress(InstallOperation.UPDATE, provider.id))
        )

        ModuleState.RemovePending -> Transition(
            WizardState.Doctor(
                provider,
                DoctorReason.MODULE_PENDING_REMOVAL,
                snapshot.verify,
                module
            )
        )

        is ModuleState.Disabled -> Transition(
            WizardState.Doctor(
                provider,
                DoctorReason.MODULE_DISABLED,
                snapshot.verify,
                module
            )
        )

        is ModuleState.Enabled -> routeEnabled(provider, snapshot)
    }

    private fun routeEnabled(provider: DetectedProvider, snapshot: ProbeSnapshot): Transition {
        val report = snapshot.verify
            ?: return Transition(WizardState.Verify(provider, null), listOf(WizardEffect.RunVerify))

        if (!report.passed) {
            return Transition(
                WizardState.Doctor(provider, DoctorReason.VERIFY_FAILED, report, snapshot.module),
                listOf(WizardEffect.ClearProgress)
            )
        }

        val settled = mutableListOf<WizardEffect>(WizardEffect.ClearProgress)
        return when {
            snapshot.missingRuntime.isNotEmpty() -> Transition(
                WizardState.NeedsRuntime(provider, snapshot.missingRuntime),
                settled + WizardEffect.RequestRuntimePermissions
            )

            !snapshot.paired -> Transition(WizardState.NeedsPairing(provider), settled)

            snapshot.linkTestPassed == true ->
                Transition(WizardState.Ready, settled + WizardEffect.StartService)

            snapshot.linkTestPassed == false -> Transition(
                WizardState.Doctor(
                    provider,
                    DoctorReason.LINK_TEST_FAILED,
                    report,
                    snapshot.module,
                    "the last link test failed"
                ),
                settled
            )

            else -> Transition(
                WizardState.FirstLinkTest(provider, null),
                settled + WizardEffect.RunLinkTest
            )
        }
    }

    private fun onInstallRequested(state: WizardState): Transition {
        if (state !is WizardState.ReadyToInstall) return Transition(state)
        val operation = if (state.update) InstallOperation.UPDATE else InstallOperation.INSTALL
        return Transition(
            WizardState.Installing(state.provider, operation, state.module),
            listOf(
                WizardEffect.InstallModule(state.provider.id, operation),
                WizardEffect.PersistProgress(operation, state.provider.id)
            )
        )
    }

    private fun onPrepare(state: WizardState): Transition {
        if (state !is WizardState.ProviderPrep) return Transition(state)
        val operation = InstallOperation.PREPARE_METAMODULE
        return Transition(
            WizardState.Installing(state.provider, operation, ModuleState.Absent),
            listOf(
                WizardEffect.InstallModule(state.provider.id, operation),
                WizardEffect.PersistProgress(operation, state.provider.id)
            )
        )
    }

    private fun onRollback(state: WizardState): Transition {
        val provider = providerOf(state) ?: return Transition(state)
        val module = moduleOf(state)
        return Transition(
            WizardState.Installing(provider, InstallOperation.ROLLBACK, module),
            listOf(
                WizardEffect.InstallModule(provider.id, InstallOperation.ROLLBACK),
                WizardEffect.PersistProgress(InstallOperation.ROLLBACK, provider.id)
            )
        )
    }

    private fun onUpdateRequested(state: WizardState): Transition {
        val provider = providerOf(state) ?: return Transition(state)
        return Transition(
            WizardState.ReadyToInstall(provider, moduleOf(state), update = true)
        )
    }

    private fun onInstallResult(state: WizardState, result: ProviderResult): Transition {
        if (state !is WizardState.Installing) return Transition(state)
        if (result.isSuccess) {
            val reason = when (state.operation) {
                InstallOperation.INSTALL, InstallOperation.PREPARE_METAMODULE ->
                    RebootReason.MODULE_INSTALL

                InstallOperation.UPDATE -> RebootReason.MODULE_UPDATE

                InstallOperation.ROLLBACK -> RebootReason.MODULE_ROLLBACK
            }
            return Transition(WizardState.RebootPending(state.provider, reason, state.module))
        }
        return Transition(
            WizardState.InstallFailed(
                provider = state.provider,
                operation = state.operation,
                module = state.module,
                result = result,
                outputTail = result.tail()
            )
        )
    }

    private fun onReboot(state: WizardState): Transition {
        if (state !is WizardState.RebootPending) return Transition(state)
        return Transition(
            state,
            listOf(
                WizardEffect.Reboot(state.reason),
                WizardEffect.PersistProgress(
                    when (state.reason) {
                        RebootReason.MODULE_UPDATE -> InstallOperation.UPDATE
                        RebootReason.MODULE_ROLLBACK -> InstallOperation.ROLLBACK
                        else -> InstallOperation.INSTALL
                    },
                    state.provider.id
                )
            )
        )
    }

    private fun onRetry(state: WizardState): Transition {
        if (state !is WizardState.InstallFailed) return Transition(state)
        val update = state.operation != InstallOperation.INSTALL
        return Transition(WizardState.ReadyToInstall(state.provider, state.module, update))
    }

    private fun onRuntimePermissions(
        state: WizardState,
        missing: List<RuntimePermission>
    ): Transition {
        if (state !is WizardState.NeedsRuntime) return Transition(state)
        return if (missing.isEmpty()) {
            Transition(WizardState.NeedsPairing(state.provider), listOf(WizardEffect.StartPairing))
        } else {
            Transition(
                WizardState.NeedsRuntime(state.provider, missing),
                listOf(WizardEffect.RequestRuntimePermissions)
            )
        }
    }

    private fun onPaired(state: WizardState): Transition {
        if (state !is WizardState.NeedsPairing) return Transition(state)
        return Transition(
            WizardState.FirstLinkTest(state.provider, null),
            listOf(WizardEffect.RunLinkTest)
        )
    }

    private fun onLinkTest(state: WizardState, event: WizardEvent.LinkTestCompleted): Transition {
        if (state !is WizardState.FirstLinkTest) return Transition(state)
        return if (event.passed) {
            Transition(WizardState.Ready, listOf(WizardEffect.StartService))
        } else {
            Transition(
                WizardState.Doctor(
                    state.provider,
                    DoctorReason.LINK_TEST_FAILED,
                    detail = event.detail ?: "the first link test failed"
                )
            )
        }
    }

    private fun onRepaired(state: WizardState): Transition {
        if (state !is WizardState.Doctor) return Transition(state)
        return Transition(WizardState.Ready, listOf(WizardEffect.StartService))
    }

    private fun providerOf(state: WizardState): DetectedProvider? = when (state) {
        is WizardState.ReadyToInstall -> state.provider
        is WizardState.Installing -> state.provider
        is WizardState.InstallFailed -> state.provider
        is WizardState.RebootPending -> state.provider
        is WizardState.Verify -> state.provider
        is WizardState.NeedsRuntime -> state.provider
        is WizardState.NeedsPairing -> state.provider
        is WizardState.FirstLinkTest -> state.provider
        is WizardState.Doctor -> state.provider
        is WizardState.ProviderPrep -> state.provider
        is WizardState.RootDenied -> state.provider
        else -> null
    }

    private fun moduleOf(state: WizardState): ModuleState = when (state) {
        is WizardState.ReadyToInstall -> state.module
        is WizardState.Installing -> state.module
        is WizardState.InstallFailed -> state.module
        is WizardState.RebootPending -> state.module
        is WizardState.Doctor -> state.module
        else -> ModuleState.Unknown
    }

    private fun tail(text: String?): String? = text?.lineSequence()?.filter { it.isNotBlank() }?.toList()?.takeLast(20)?.joinToString("\n")
        ?.takeIf { it.isNotEmpty() }
}
