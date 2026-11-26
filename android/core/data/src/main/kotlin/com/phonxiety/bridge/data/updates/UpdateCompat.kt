package com.phonxiety.bridge.data.updates

/** The three rows of the Updates screen. */
enum class UpdateTarget { APP, MODULE, DAEMON }

/**
 * What the row's action is, and nothing about how it is drawn. [INCOMPATIBLE] means the phone or
 * the daemon cannot take the release at all; [UNKNOWN] means a probe could not answer (link down,
 * root denied) and the screen says so instead of guessing.
 */
enum class UpdateStatus { UP_TO_DATE, UPDATE_AVAILABLE, INCOMPATIBLE, UNKNOWN }

/**
 * What this phone runs right now. Every field that could not be read stays null: the verdict then
 * reports [UpdateStatus.UNKNOWN] for that row rather than inventing "up to date".
 */
data class InstalledVersions(
    val appVersion: String?,
    val appVersionCode: Long,
    val deviceSdk: Int,
    val moduleVersion: String? = null,
    val moduleVersionCode: Int? = null,
    /** null when the module state could not be read (root denied, provider conflict). */
    val moduleInstalled: Boolean? = null,
    /** The module is present but its `disable` flag is set; Doctor re-enables it. */
    val moduleDisabled: Boolean = false,
    /** Privileged permissions this installation actually holds; empty when the app is not a priv-app. */
    val grantedPrivilegedPermissions: Set<String> = emptySet(),
    val daemonVersion: String? = null,
    val daemonAppApi: Int? = null,
    val daemonProtocolVersions: List<Int> = emptyList(),
    /** `/compat`'s verdict for the versions this release speaks; null when the link is down. */
    val daemonCompatible: Boolean? = null,
    val daemonNotes: List<String> = emptyList()
)

/**
 * One fact a row needs to explain itself, carrying the values a sentence needs instead of the
 * sentence itself. The Updates feature resolves each kind against its own resources, so the
 * compatibility decision stays here and user-visible copy stays in the feature. [Reported] holds
 * the daemon's own text, which is shown verbatim rather than replaced by an invented sentence.
 */
sealed interface CompatibilityNote {
    /** The app release needs a newer Android than this phone runs. */
    data class NeedsNewerAndroid(val requiredSdk: Int, val phoneSdk: Int) : CompatibilityNote

    /** The module release's payload schema is newer than this app understands. */
    data class NewerModuleSchema(val requiredSchema: Int, val supportedSchema: Int) : CompatibilityNote

    /** Root denied or the providers conflicted, so the module state could not be probed. */
    data object ModuleStateUnknown : CompatibilityNote

    /** No module is installed, so the phone has no privileged copies. */
    data object ModuleNotInstalled : CompatibilityNote

    /** The module is installed but its `disable` flag is set. */
    data object ModuleDisabled : CompatibilityNote

    /** A reboot mounts the module update. */
    data object ModuleRebootRequired : CompatibilityNote

    /** Privileged permissions the release grants that this installation does not hold. */
    data class NewPrivilegedGrants(val permissions: List<String>) : CompatibilityNote

    /**
     * The module's payload is the APK that runs here, so its install follows the app update. Shown on
     * the app row while the running app still predates the release.
     */
    data object ModuleInstallFollows : CompatibilityNote

    /** The daemon on the computer is older than this release needs. */
    data class NeedsNewerDaemon(val minVersion: String) : CompatibilityNote

    /** The daemon answers an older app API than this release needs. */
    data class DaemonApiOlder(val daemonAppApi: Int, val requiredAppApi: Int) : CompatibilityNote

    /** The daemon does not accept the device protocol this app speaks. */
    data class DaemonProtocolUnsupported(
        val daemonProtocols: List<Int>,
        val appProtocol: Int
    ) : CompatibilityNote

    /** The daemon did not answer, so its version is unknown. */
    data object DaemonUnreachable : CompatibilityNote

    /** The daemon's own text, verbatim. */
    data class Reported(val text: String) : CompatibilityNote
}

/** One row: installed against available, with the notes that justify the verdict. */
data class UpdateRowState(
    val target: UpdateTarget,
    val installedVersion: String?,
    val availableVersion: String?,
    val status: UpdateStatus,
    val requiresReboot: Boolean = false,
    val notes: List<CompatibilityNote> = emptyList(),
    /**
     * The module install waits for the app row: the payload is rebuilt from the running APK, and
     * rebuilding it from an older one would put the old allowlist back.
     */
    val needsAppFirst: Boolean = false
) {
    val hasUpdate: Boolean get() = status == UpdateStatus.UPDATE_AVAILABLE
}

/** The whole screen's verdict: three rows and the global blockers the action buttons honour. */
data class UpdateVerdict(
    val app: UpdateRowState,
    val module: UpdateRowState,
    val daemon: UpdateRowState
) {
    val rows: List<UpdateRowState> get() = listOf(app, module, daemon)

    /** False when any row cannot run on this phone or against this daemon. */
    val compatible: Boolean get() = rows.none { it.status == UpdateStatus.INCOMPATIBLE }

    val hasUpdate: Boolean get() = rows.any { it.hasUpdate }
}

/**
 * Compares the published release with what the phone runs. Pure: the probes that fill
 * [InstalledVersions] live in [AndroidUpdateInventory], and every branch here is unit-tested.
 */
object UpdateCompat {
    fun evaluate(manifest: UpdateManifest, installed: InstalledVersions): UpdateVerdict {
        val missing = missingGrants(manifest.module, installed)
        val moduleStepFollows = installed.moduleInstalled == true && missing.isNotEmpty()
        return UpdateVerdict(
            app = appRow(manifest.apk, installed, moduleStepFollows = moduleStepFollows),
            module = moduleRow(manifest.module, manifest.apk, installed, missing),
            daemon = daemonRow(manifest.daemon, installed)
        )
    }

    /** Grants the release allowlists and this installation does not hold. */
    private fun missingGrants(module: ModuleRelease, installed: InstalledVersions): List<String> =
        (module.permissions.toSet() - installed.grantedPrivilegedPermissions).sorted()

    private fun appRow(
        apk: ApkRelease,
        installed: InstalledVersions,
        moduleStepFollows: Boolean
    ): UpdateRowState {
        if (installed.deviceSdk < apk.minSdk) {
            return UpdateRowState(
                target = UpdateTarget.APP,
                installedVersion = installed.appVersion,
                availableVersion = apk.version,
                status = UpdateStatus.INCOMPATIBLE,
                notes = listOf(CompatibilityNote.NeedsNewerAndroid(apk.minSdk, installed.deviceSdk))
            )
        }
        if (apk.versionCode > installed.appVersionCode) {
            return UpdateRowState(
                target = UpdateTarget.APP,
                installedVersion = installed.appVersion,
                availableVersion = apk.version,
                status = UpdateStatus.UPDATE_AVAILABLE,
                notes = if (moduleStepFollows) listOf(CompatibilityNote.ModuleInstallFollows) else emptyList()
            )
        }
        return UpdateRowState(
            target = UpdateTarget.APP,
            installedVersion = installed.appVersion,
            availableVersion = apk.version,
            status = UpdateStatus.UP_TO_DATE
        )
    }

    private fun moduleRow(
        module: ModuleRelease,
        apk: ApkRelease,
        installed: InstalledVersions,
        missingGrants: List<String>
    ): UpdateRowState {
        if (module.schemaVersion > MODULE_SCHEMA_VERSION) {
            return UpdateRowState(
                target = UpdateTarget.MODULE,
                installedVersion = installed.moduleVersion,
                availableVersion = module.version,
                status = UpdateStatus.INCOMPATIBLE,
                notes = listOf(CompatibilityNote.NewerModuleSchema(module.schemaVersion, MODULE_SCHEMA_VERSION))
            )
        }
        if (installed.moduleInstalled == null) {
            return UpdateRowState(
                target = UpdateTarget.MODULE,
                installedVersion = installed.moduleVersion,
                availableVersion = module.version,
                status = UpdateStatus.UNKNOWN,
                notes = listOf(CompatibilityNote.ModuleStateUnknown)
            )
        }
        // The module zip is rebuilt from the running APK, so an older app would rebuild the old
        // payload: the install waits until this phone runs the release's APK.
        val appReady = installed.appVersionCode >= apk.versionCode
        if (!installed.moduleInstalled) {
            return UpdateRowState(
                target = UpdateTarget.MODULE,
                installedVersion = null,
                availableVersion = module.version,
                status = UpdateStatus.UPDATE_AVAILABLE,
                requiresReboot = true,
                notes = listOf(CompatibilityNote.ModuleNotInstalled),
                needsAppFirst = !appReady
            )
        }
        val installedCode = (installed.moduleVersionCode ?: 0).toLong()
        // Equal version codes still need the install when the grant is absent: the previous module
        // was built from an app that did not request it.
        if (module.versionCode > installedCode || missingGrants.isNotEmpty()) {
            val notes = buildList {
                if (installed.moduleDisabled) add(CompatibilityNote.ModuleDisabled)
                if (module.requiresReboot) add(CompatibilityNote.ModuleRebootRequired)
                if (missingGrants.isNotEmpty()) add(CompatibilityNote.NewPrivilegedGrants(missingGrants))
            }
            return UpdateRowState(
                target = UpdateTarget.MODULE,
                installedVersion = installed.moduleVersion,
                availableVersion = module.version,
                status = UpdateStatus.UPDATE_AVAILABLE,
                requiresReboot = module.requiresReboot || missingGrants.isNotEmpty(),
                notes = notes,
                needsAppFirst = !appReady
            )
        }
        return UpdateRowState(
            target = UpdateTarget.MODULE,
            installedVersion = installed.moduleVersion,
            availableVersion = module.version,
            status = UpdateStatus.UP_TO_DATE,
            notes = listOfNotNull(
                CompatibilityNote.ModuleDisabled.takeIf { installed.moduleDisabled }
            )
        )
    }

    private fun daemonRow(daemon: DaemonRelease, installed: InstalledVersions): UpdateRowState {
        if (installed.daemonCompatible == false) {
            return UpdateRowState(
                target = UpdateTarget.DAEMON,
                installedVersion = installed.daemonVersion,
                availableVersion = daemon.minVersion,
                status = UpdateStatus.INCOMPATIBLE,
                notes = installed.daemonNotes.map(CompatibilityNote::Reported).ifEmpty {
                    listOf(CompatibilityNote.NeedsNewerDaemon(daemon.minVersion))
                }
            )
        }
        if (installed.daemonVersion == null) {
            return UpdateRowState(
                target = UpdateTarget.DAEMON,
                installedVersion = null,
                availableVersion = daemon.minVersion,
                status = UpdateStatus.UNKNOWN,
                notes = listOf(CompatibilityNote.DaemonUnreachable)
            )
        }
        if (VersionOrder.compare(installed.daemonVersion, daemon.minVersion) < 0) {
            return UpdateRowState(
                target = UpdateTarget.DAEMON,
                installedVersion = installed.daemonVersion,
                availableVersion = daemon.minVersion,
                status = UpdateStatus.UPDATE_AVAILABLE,
                notes = listOf(CompatibilityNote.NeedsNewerDaemon(daemon.minVersion))
            )
        }
        val appApi = installed.daemonAppApi
        if (appApi != null && appApi < daemon.appApi) {
            return UpdateRowState(
                target = UpdateTarget.DAEMON,
                installedVersion = installed.daemonVersion,
                availableVersion = daemon.minVersion,
                status = UpdateStatus.UPDATE_AVAILABLE,
                notes = listOf(CompatibilityNote.DaemonApiOlder(appApi, daemon.appApi))
            )
        }
        if (installed.daemonProtocolVersions.isNotEmpty() && daemon.protocol !in installed.daemonProtocolVersions) {
            return UpdateRowState(
                target = UpdateTarget.DAEMON,
                installedVersion = installed.daemonVersion,
                availableVersion = daemon.minVersion,
                status = UpdateStatus.INCOMPATIBLE,
                notes = listOf(
                    CompatibilityNote.DaemonProtocolUnsupported(installed.daemonProtocolVersions, daemon.protocol)
                )
            )
        }
        return UpdateRowState(
            target = UpdateTarget.DAEMON,
            installedVersion = installed.daemonVersion,
            availableVersion = daemon.minVersion,
            status = UpdateStatus.UP_TO_DATE,
            notes = installed.daemonNotes.map(CompatibilityNote::Reported)
        )
    }
}
