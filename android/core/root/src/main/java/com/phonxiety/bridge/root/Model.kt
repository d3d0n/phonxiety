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
    MODULE_UPDATE,
    MODULE_ROLLBACK,
    USER_REQUEST;

    /** `PowerManager.reboot(reason)` on Android requires a short lowercase token. */
    val powerManagerReason: String
        get() = when (this) {
            MODULE_INSTALL, MODULE_UPDATE, MODULE_ROLLBACK -> "module_update"
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
    val coexistenceWarning: String?
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

/** Everything [RootDetector] learned in one pass. */
data class RootDetection(
    val su: SuStatus,
    val providers: List<DetectedProvider> = emptyList(),
    /** Combined stdout/stderr of the `su -c id` probe, for the "could not tell" screen. */
    val suOutput: String? = null
) {
    val conflict: Boolean get() = providers.size > 1
    val provider: DetectedProvider? get() = providers.singleOrNull()
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
