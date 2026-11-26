package com.phonxiety.bridge.root

import java.io.File

/**
 * The root-provider abstraction. One implementation per root solution; instances are
 * built by [RootProviders.create] from the [DetectedProvider] the detector returned.
 */
sealed interface RootProvider {
    val detected: DetectedProvider

    val id: RootId get() = detected.id
    val version: String get() = detected.version

    suspend fun capabilities(): RootCapabilities
    suspend fun installModule(zip: File): ProviderResult

    /**
     * Installs a zip that already lives in root-owned storage (`/data/adb`). `/data/adb` is
     * readable by root only, so the app cannot stat that file: existence is decided by `su`'s own
     * `cp`, which fails and says so when the path is not there.
     */
    suspend fun installPublishedModule(sourcePath: String): ProviderResult
    suspend fun enableModule(id: String): ProviderResult
    suspend fun disableModule(id: String): ProviderResult
    suspend fun removeModule(id: String): ProviderResult
    suspend fun moduleState(id: String): ModuleState
    suspend fun reboot(reason: RebootReason): ProviderResult
}

/** Parsed `module.prop`, the only metadata every provider reads. */
data class ModuleProp(
    val id: String?,
    val name: String?,
    val version: String?,
    val versionCode: Int?,
    val metamodule: Boolean,
    val updateJson: String?
)

fun parseModuleProp(text: String): ModuleProp {
    var id: String? = null
    var name: String? = null
    var version: String? = null
    var versionCode: Int? = null
    var metamodule = false
    var updateJson: String? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val equals = line.indexOf('=')
        if (equals <= 0) continue
        when (line.substring(0, equals).trim()) {
            "id" -> id = line.substring(equals + 1).trim()

            "name" -> name = line.substring(equals + 1).trim()

            "version" -> version = line.substring(equals + 1).trim()

            "versionCode" -> versionCode = line.substring(equals + 1).trim().toIntOrNull()

            "metamodule" -> metamodule = line.substring(equals + 1).trim().let {
                it == "1" || it.equals("true", ignoreCase = true)
            }

            "updateJson" -> updateJson = line.substring(equals + 1).trim()
        }
    }
    return ModuleProp(id, name, version, versionCode, metamodule, updateJson)
}

/** Reads the lifecycle state of one module. Shared by every provider: the layout is identical. */
suspend fun readModuleState(environment: RootEnvironment, moduleId: String): ModuleState {
    val updateEntries = environment.listDir(RootCommand.MODULES_UPDATE_DIR)
    if (updateEntries != null && moduleId in updateEntries) {
        val prop = readModuleProp(environment, "${RootCommand.MODULES_UPDATE_DIR}/$moduleId")
        return ModuleState.Staged(prop?.version, prop?.versionCode ?: 0)
    }
    val moduleEntries = environment.listDir(RootCommand.MODULES_DIR) ?: return ModuleState.Unknown
    if (moduleId !in moduleEntries) return ModuleState.Absent
    val dir = "${RootCommand.MODULES_DIR}/$moduleId"
    val entries = environment.listDir(dir) ?: return ModuleState.Unknown
    val prop = readModuleProp(environment, dir)
    if (ModuleFlag.REMOVE.fileName in entries) return ModuleState.RemovePending
    if (ModuleFlag.DISABLE.fileName in entries) {
        return ModuleState.Disabled(prop?.version, prop?.versionCode ?: 0)
    }
    return ModuleState.Enabled(prop?.version, prop?.versionCode ?: 0)
}

/** The id of an installed metamodule, or null. Only KernelSU and KernelSU-Next need one. */
suspend fun findMetamodule(environment: RootEnvironment): String? {
    val modules = environment.listDir(RootCommand.MODULES_DIR) ?: return null
    for (moduleId in modules) {
        val prop = readModuleProp(environment, "${RootCommand.MODULES_DIR}/$moduleId") ?: continue
        if (prop.metamodule) return moduleId
    }
    return null
}

/**
 * The marker `ksud` leaves in `/data/adb/modules/<id>` while a module is staged for the next boot
 * (`userspace/ksud/src/module.rs`: `ensure_file_exists(modules/<id>/update)`; the boot handler
 * renames `modules_update/<id>` over `modules/<id>` and drops the marker).
 */
internal const val MODULE_UPDATE_MARKER: String = "update"

/**
 * True when [moduleId] sits in `/data/adb/modules` with the `update` marker: staged, not mounted.
 * A directory that cannot be listed proves nothing, so it answers false and keeps today's routing.
 */
suspend fun metamoduleIsPending(environment: RootEnvironment, moduleId: String): Boolean {
    val entries = environment.listDir("${RootCommand.MODULES_DIR}/$moduleId") ?: return false
    return MODULE_UPDATE_MARKER in entries
}

internal suspend fun readModuleProp(environment: RootEnvironment, dir: String): ModuleProp? = environment.readFile("$dir/module.prop")?.let(::parseModuleProp)

// ---------------------------------------------------------------------------------------------
// Shared plumbing
// ---------------------------------------------------------------------------------------------

internal const val MODULE_ZIP_NAME: String = "phonxiety_bridge.zip"

internal val STAGED_ZIP_PATH: String get() = "${RootCommand.STAGING_DIR}/$MODULE_ZIP_NAME"

internal val COEXISTENCE_WARNING: String =
    "KernelSU, KernelSU-Next and Magisk do not coexist: while any KernelSU module is enabled, " +
        "Magisk stops working entirely. Enable one module system only."

internal val APATCH_COEXISTENCE_WARNING: String =
    "APatch coexistence with Magisk is not documented; treat the two as exclusive module systems."

/** Last-resort denial detection: `su` policy and provider daemons both phrase it this way. */
private val DENIAL = Regex("(?i)(permission denied|not allowed|operation not permitted|denied)")

internal fun ShellOutput.toProviderResult(): ProviderResult = when {
    launchFailed -> ProviderResult.Failure(combined.ifEmpty { "su is not available" }, exitCode)
    timedOut -> ProviderResult.TimedOut(timeoutMs, stdout + if (stderr.isEmpty()) "" else "\n$stderr")
    exitCode == 0 -> ProviderResult.Success(combined)
    DENIAL.containsMatchIn(combined) -> ProviderResult.Denied(combined)
    else -> ProviderResult.Failure(combined.ifEmpty { "exit $exitCode" }, exitCode)
}

/** Runs a typed command; an argument that fails validation becomes a reported failure, not a crash. */
internal suspend fun RootShell.execSafely(build: () -> RootCommand): ProviderResult = try {
    exec(build()).toProviderResult()
} catch (error: IllegalArgumentException) {
    ProviderResult.Failure(error.message ?: "refused an unsafe command", EXIT_REFUSED)
}

internal const val EXIT_REFUSED: Int = 125

internal suspend fun stageModuleZip(shell: RootShell, zip: File): ProviderResult {
    if (!zip.isFile) return ProviderResult.Failure("module zip does not exist: ${zip.path}", -1)
    return stageRootOwnedModuleZip(shell, zip.absolutePath)
}

/**
 * Copies a module zip that already sits in root-owned storage into the staging directory. The
 * existence check is `su`'s own `cp`: `/data/adb` is root-only (0700), so an app-side
 * `File.isFile` on such a path answers "missing" for a file root can see and install.
 */
internal suspend fun stageRootOwnedModuleZip(shell: RootShell, sourcePath: String): ProviderResult = shell.execSafely { RootCommand.copy(sourcePath, MODULE_ZIP_NAME) }

internal suspend fun rebootThrough(
    shell: RootShell,
    rebooter: SystemRebooter?,
    reason: RebootReason
): ProviderResult {
    if (rebooter != null && rebooter.hasRebootPermission()) {
        val accepted = runCatching { rebooter.reboot(reason.powerManagerReason) }.getOrDefault(false)
        if (accepted) return ProviderResult.Success("reboot requested (${reason.powerManagerReason})")
    }
    return shell.execSafely { RootCommand.reboot() }
}

// ---------------------------------------------------------------------------------------------
// Magisk
// ---------------------------------------------------------------------------------------------

/**
 * Magisk v31.x. Mounting is built in (magic mount), modules live in `/data/adb/modules` and the
 * lifecycle flags `disable`/`remove` are ordinary files. `magisk --remove-modules` removes *all*
 * modules, so a single-module uninstall uses the `remove` flag.
 */
class MagiskProvider(
    override val detected: DetectedProvider,
    private val shell: RootShell,
    private val environment: RootEnvironment,
    private val rebooter: SystemRebooter? = null
) : RootProvider {
    override suspend fun capabilities(): RootCapabilities = RootCapabilities(
        systemOverlay = Overlay.BUILT_IN,
        hotInstall = false,
        updateJson = true,
        webUi = false,
        actionButton = true,
        bootStages = setOf(BootStage.POST_FS_DATA, BootStage.SERVICE),
        managerPackage = detected.managerPackage ?: MANAGER_PACKAGE,
        coexistenceWarning = COEXISTENCE_WARNING
    )

    override suspend fun installModule(zip: File): ProviderResult {
        val staged = stageModuleZip(shell, zip)
        if (!staged.isSuccess) return staged
        return shell.execSafely { RootCommand.magiskInstallModule(STAGED_ZIP_PATH) }
    }

    override suspend fun installPublishedModule(sourcePath: String): ProviderResult {
        val staged = stageRootOwnedModuleZip(shell, sourcePath)
        if (!staged.isSuccess) return staged
        return shell.execSafely { RootCommand.magiskInstallModule(STAGED_ZIP_PATH) }
    }

    override suspend fun enableModule(id: String): ProviderResult = shell.execSafely { RootCommand.clearModuleFlag(id, ModuleFlag.DISABLE) }

    override suspend fun disableModule(id: String): ProviderResult = shell.execSafely { RootCommand.setModuleFlag(id, ModuleFlag.DISABLE) }

    override suspend fun removeModule(id: String): ProviderResult = shell.execSafely { RootCommand.setModuleFlag(id, ModuleFlag.REMOVE) }

    /** Doctor's bootloop recovery only: `magisk --remove-modules` removes every module. */
    suspend fun removeAllModules(): ProviderResult = shell.execSafely { RootCommand.magiskRemoveAllModules() }

    override suspend fun moduleState(id: String): ModuleState = readModuleState(environment, id)

    override suspend fun reboot(reason: RebootReason): ProviderResult = rebootThrough(shell, rebooter, reason)

    companion object {
        const val MANAGER_PACKAGE: String = "com.topjohnwu.magisk"
    }
}

// ---------------------------------------------------------------------------------------------
// KernelSU and KernelSU-Next
// ---------------------------------------------------------------------------------------------

/**
 * KernelSU 3.x and KernelSU-Next. Identical module layout (`/data/adb/ksu`, `ksud`), so both use
 * the same commands; the only functional difference is the manager package. `system/` is mounted
 * by a metamodule, hence [Overlay.METAMODULE_MISSING] when none is installed.
 */
class KernelSuProvider(
    override val detected: DetectedProvider,
    private val shell: RootShell,
    private val environment: RootEnvironment,
    private val rebooter: SystemRebooter? = null
) : RootProvider {
    override suspend fun capabilities(): RootCapabilities {
        val metamodule = findMetamodule(environment)
        val pending = metamodule != null && metamoduleIsPending(environment, metamodule)
        return RootCapabilities(
            systemOverlay = if (metamodule != null && !pending) {
                Overlay.METAMODULE_PRESENT
            } else {
                Overlay.METAMODULE_MISSING
            },
            hotInstall = true,
            updateJson = true,
            webUi = true,
            actionButton = true,
            bootStages = setOf(
                BootStage.POST_FS_DATA,
                BootStage.POST_MOUNT,
                BootStage.SERVICE,
                BootStage.BOOT_COMPLETED
            ),
            managerPackage = detected.managerPackage ?: defaultManagerPackage(detected.id),
            coexistenceWarning = COEXISTENCE_WARNING,
            metamodulePending = pending
        )
    }

    override suspend fun installModule(zip: File): ProviderResult {
        val staged = stageModuleZip(shell, zip)
        if (!staged.isSuccess) return staged
        return shell.execSafely { RootCommand.ksudModule(ModuleAction.INSTALL, STAGED_ZIP_PATH) }
    }

    override suspend fun installPublishedModule(sourcePath: String): ProviderResult {
        val staged = stageRootOwnedModuleZip(shell, sourcePath)
        if (!staged.isSuccess) return staged
        return shell.execSafely { RootCommand.ksudModule(ModuleAction.INSTALL, STAGED_ZIP_PATH) }
    }

    override suspend fun enableModule(id: String): ProviderResult {
        val result = shell.execSafely { RootCommand.ksudModule(ModuleAction.ENABLE, id) }
        if (result.isSuccess) return result
        return shell.execSafely { RootCommand.clearModuleFlag(id, ModuleFlag.DISABLE) }
    }

    override suspend fun disableModule(id: String): ProviderResult {
        val result = shell.execSafely { RootCommand.ksudModule(ModuleAction.DISABLE, id) }
        if (result.isSuccess) return result
        return shell.execSafely { RootCommand.setModuleFlag(id, ModuleFlag.DISABLE) }
    }

    override suspend fun removeModule(id: String): ProviderResult {
        val result = shell.execSafely { RootCommand.ksudModule(ModuleAction.UNINSTALL, id) }
        if (result.isSuccess) return result
        return shell.execSafely { RootCommand.setModuleFlag(id, ModuleFlag.REMOVE) }
    }

    override suspend fun moduleState(id: String): ModuleState = readModuleState(environment, id)

    override suspend fun reboot(reason: RebootReason): ProviderResult = rebootThrough(shell, rebooter, reason)

    companion object {
        const val KERNELSU_MANAGER_PACKAGE: String = "me.weishu.kernelsu"
        const val KERNELSU_NEXT_MANAGER_PACKAGE: String = "com.rifsxd.ksunext"

        fun defaultManagerPackage(id: RootId): String = when (id) {
            RootId.KERNELSU_NEXT -> KERNELSU_NEXT_MANAGER_PACKAGE
            else -> KERNELSU_MANAGER_PACKAGE
        }
    }
}

// ---------------------------------------------------------------------------------------------
// APatch
// ---------------------------------------------------------------------------------------------

/**
 * APatch: `/data/adb/ap`, `apd`, OverlayFS mounting built in, arm64 kernels only, and no documented
 * `updateJson` support — the app's own updater stays authoritative there.
 */
class ApatchProvider(
    override val detected: DetectedProvider,
    private val shell: RootShell,
    private val environment: RootEnvironment,
    private val rebooter: SystemRebooter? = null
) : RootProvider {
    override suspend fun capabilities(): RootCapabilities = RootCapabilities(
        systemOverlay = Overlay.BUILT_IN,
        hotInstall = false,
        updateJson = false,
        webUi = true,
        actionButton = true,
        bootStages = setOf(
            BootStage.POST_FS_DATA,
            BootStage.POST_MOUNT,
            BootStage.SERVICE,
            BootStage.BOOT_COMPLETED
        ),
        managerPackage = detected.managerPackage ?: MANAGER_PACKAGE,
        coexistenceWarning = APATCH_COEXISTENCE_WARNING
    )

    override suspend fun installModule(zip: File): ProviderResult {
        val staged = stageModuleZip(shell, zip)
        if (!staged.isSuccess) return staged
        return shell.execSafely { RootCommand.apdModule(ModuleAction.INSTALL, STAGED_ZIP_PATH) }
    }

    override suspend fun installPublishedModule(sourcePath: String): ProviderResult {
        val staged = stageRootOwnedModuleZip(shell, sourcePath)
        if (!staged.isSuccess) return staged
        return shell.execSafely { RootCommand.apdModule(ModuleAction.INSTALL, STAGED_ZIP_PATH) }
    }

    override suspend fun enableModule(id: String): ProviderResult {
        val result = shell.execSafely { RootCommand.apdModule(ModuleAction.ENABLE, id) }
        if (result.isSuccess) return result
        return shell.execSafely { RootCommand.clearModuleFlag(id, ModuleFlag.DISABLE) }
    }

    override suspend fun disableModule(id: String): ProviderResult {
        val result = shell.execSafely { RootCommand.apdModule(ModuleAction.DISABLE, id) }
        if (result.isSuccess) return result
        return shell.execSafely { RootCommand.setModuleFlag(id, ModuleFlag.DISABLE) }
    }

    override suspend fun removeModule(id: String): ProviderResult {
        val result = shell.execSafely { RootCommand.apdModule(ModuleAction.UNINSTALL, id) }
        if (result.isSuccess) return result
        return shell.execSafely { RootCommand.setModuleFlag(id, ModuleFlag.REMOVE) }
    }

    override suspend fun moduleState(id: String): ModuleState = readModuleState(environment, id)

    override suspend fun reboot(reason: RebootReason): ProviderResult = rebootThrough(shell, rebooter, reason)

    companion object {
        const val MANAGER_PACKAGE: String = "me.bmax.apatch"
    }
}

/** Builds the provider object that matches a detection result. */
object RootProviders {
    fun create(
        detected: DetectedProvider,
        shell: RootShell,
        environment: RootEnvironment,
        rebooter: SystemRebooter? = null
    ): RootProvider = when (detected.id) {
        RootId.MAGISK -> MagiskProvider(detected, shell, environment, rebooter)

        RootId.KERNELSU, RootId.KERNELSU_NEXT ->
            KernelSuProvider(detected, shell, environment, rebooter)

        RootId.APATCH -> ApatchProvider(detected, shell, environment, rebooter)
    }
}
