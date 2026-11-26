package com.phonxiety.bridge.root

/** The four root solutions the app supports. */
enum class RootId { MAGISK, KERNELSU, KERNELSU_NEXT, APATCH }

/** How a provider gets files from a module's `system/` into the system partition. */
enum class Overlay {
    /** The provider mounts `system/` itself (Magisk magic mount, APatch OverlayFS). */
    BUILT_IN,

    /** KernelSU/Next with a metamodule installed (meta-overlayfs, meta-magicmount, ...). */
    METAMODULE_PRESENT,

    /** KernelSU/Next without a metamodule: an enabled module is silently not mounted. */
    METAMODULE_MISSING
}

/** Boot stages a provider can run module scripts in. We ship none, but capabilities report them. */
enum class BootStage { POST_FS_DATA, POST_MOUNT, SERVICE, BOOT_COMPLETED, PREINIT, INITRC }

/** Why the app is asking for a reboot; also the `PowerManager` reason string. */
enum class RebootReason {
    MODULE_INSTALL,

    /** A metamodule was staged; it mounts only after the next boot, before Phonxiety can install. */
    METAMODULE_INSTALL,
    MODULE_UPDATE,
    MODULE_ROLLBACK,
    USER_REQUEST;

    /** `PowerManager.reboot(reason)` on Android requires a short lowercase token. */
    val powerManagerReason: String
        get() = when (this) {
            MODULE_INSTALL, METAMODULE_INSTALL, MODULE_UPDATE, MODULE_ROLLBACK -> "module_update"
            USER_REQUEST -> "userrequested"
        }
}

/**
 * Module lifecycle as seen from `/data/adb/modules` and `/data/adb/modules_update`.
 *
 * [Unknown] exists for "the probe could not read /data/adb" — which happens when root is denied.
 * Treating that as [Absent] would make the wizard offer an install it cannot perform.
 */
sealed interface ModuleState {
    data object Absent : ModuleState

    /** Present in `/data/adb/modules_update`: installed but not yet applied by a boot. */
    data class Staged(val version: String?, val versionCode: Int) : ModuleState

    data class Enabled(val version: String?, val versionCode: Int) : ModuleState

    /** Disabled through the provider or by safe mode (the `disable` flag file). */
    data class Disabled(val version: String?, val versionCode: Int) : ModuleState

    /** The `remove` flag is set; the module disappears on the next boot. */
    data object RemovePending : ModuleState

    data object Unknown : ModuleState
}

/** Outcome of a provider command or a `su` invocation. */
sealed interface ProviderResult {
    data class Success(val output: String) : ProviderResult

    data class Failure(val output: String, val exitCode: Int) : ProviderResult

    /** `su` refused this app (manager policy) or the provider's command was rejected. */
    data class Denied(val output: String) : ProviderResult

    data class TimedOut(val afterMs: Long, val partialOutput: String) : ProviderResult

    /** The provider does not implement this operation (e.g. a CLI missing from this build). */
    data object Unsupported : ProviderResult

    val isSuccess: Boolean get() = this is Success

    /** Last [lines] non-blank output lines, for the installer log view. */
    fun tail(lines: Int = 12): List<String> {
        val text = when (this) {
            is Success -> output
            is Failure -> output
            is Denied -> output
            is TimedOut -> partialOutput
            Unsupported -> ""
        }
        return text.lineSequence().filter { it.isNotBlank() }.toList().takeLast(lines)
    }
}

/**
 * What a provider can do; every root solution reports the same shape.
 * `coexistenceWarning` is non-null whenever enabling this provider's module system can break another.
 */
data class RootCapabilities(
    val systemOverlay: Overlay,
    val hotInstall: Boolean,
    val updateJson: Boolean,
    val webUi: Boolean,
    val actionButton: Boolean,
    val bootStages: Set<BootStage>,
    val managerPackage: String?,
    val coexistenceWarning: String?,
    /**
     * A metamodule's install completed and waits for the boot that activates it: KernelSU's `ksud`
     * writes an `update` marker, APatch's `apd` keeps the staged copy under `modules_update` and its
     * installer writes `modules/<id>/update` plus `module.prop`. Reported by KernelSU, KernelSU-Next
     * and a metamodule-era `apd`.
     */
    val metamodulePending: Boolean = false,
    /**
     * A metamodule is staged but its installer never finished: the staged copy exists without the
     * completion evidence the manager writes at the end. A boot would promote the incomplete copy, so
     * the install has to run again instead. Reported by APatch only.
     */
    val metamoduleInterrupted: Boolean = false,
    /**
     * The provider's overlay capability probe did not answer: it could not start, timed out, or exited
     * with a status that is neither `grep`'s 0 (match) nor 1 (no match). [systemOverlay] is then
     * provisional, and the wizard refuses to install rather than guess whether a metamodule is needed.
     */
    val overlayProbeError: String? = null
)

/** Result of probing `su -c id`. */
enum class SuStatus {
    GRANTED,
    DENIED,
    NOT_ROOTED,

    /** `su` started but did not answer inside [RootShell.DEFAULT_TIMEOUT_MS]. */
    TIMED_OUT
}

/**
 * One provider found on the device, with the evidence that found it.
 * Instances are produced by [RootDetector]; [RootProviders.create] turns one into a [RootProvider].
 */
data class DetectedProvider(
    val id: RootId,
    val version: String,
    val managerPackage: String?,
    val managerInstalled: Boolean,
    val artefacts: List<String> = emptyList(),
    /** Set when the evidence cannot distinguish KernelSU from KernelSU-Next, or similar. */
    val note: String? = null
)

/**
 * Everything [RootDetector] learned in one pass.
 *
 * [providers] lists every provider with evidence on the phone; [active] names the one whose own
 * `su` answered (its `su -v` banner, or the probe's SELinux domain). Providers left over from an
 * earlier root solution stay in [providers] and are reported as [leftovers]: their files and their
 * manager app are not deleted by this app.
 */
data class RootDetection(
    val su: SuStatus,
    val providers: List<DetectedProvider> = emptyList(),
    /** Combined stdout/stderr of the `su -c id` probe, for the "could not tell" screen. */
    val suOutput: String? = null,
    /**
     * The provider that answered `su`. [RootId.KERNELSU] here means the KernelSU family: the
     * banner and the context cannot tell KernelSU from KernelSU-Next.
     */
    val active: RootId? = null
) {
    /**
     * Installing is a guess only when nobody can say which provider answers `su` and artefacts of
     * two or more exist. A migration's leftovers are evidence of the past, not a conflict.
     */
    val conflict: Boolean get() = active == null && providers.size > 1

    /** The provider the wizard and the Doctor act on. */
    val provider: DetectedProvider?
        get() {
            val activeId = active ?: return providers.singleOrNull()
            return providers.firstOrNull { it.id.sameFamily(activeId) }
        }

    /** Providers whose artefacts remain but which did not answer `su`. Refused nowhere, removed never. */
    val leftovers: List<DetectedProvider>
        get() {
            val activeId = active ?: return emptyList()
            return providers.filter { !it.id.sameFamily(activeId) }
        }
}

/**
 * KernelSU and KernelSU-Next answer with the same SELinux domain, and their banners differ only by
 * a suffix, so they are one family wherever the evidence cannot separate them.
 */
internal fun RootId.sameFamily(other: RootId?): Boolean {
    if (other == null) return false
    if (this == other) return true
    val kernelSu = setOf(RootId.KERNELSU, RootId.KERNELSU_NEXT)
    return this in kernelSu && other in kernelSu
}

/** One or more providers answered: installing would be a guess, so the wizard refuses. */
class ProviderConflictException(val providers: List<DetectedProvider>) :
    IllegalStateException(
        "more than one root provider is present (" +
            providers.joinToString { it.id.name } +
            "); refusing to install anything until the user picks one"
    )

/**
 * Prefers the platform reboot (the app holds `android.permission.REBOOT` once installed as a
 * priv-app); falls back to `su -c reboot`. Implementations live where `android.*` is available.
 */
interface SystemRebooter {
    fun hasRebootPermission(): Boolean

    /** Returns true when the reboot was accepted (the call normally does not return). */
    fun reboot(reason: String): Boolean
}
