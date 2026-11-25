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

/** One row: installed against available, with the notes that justify the verdict. */
data class UpdateRowState(
    val target: UpdateTarget,
    val installedVersion: String?,
    val availableVersion: String?,
    val status: UpdateStatus,
    val requiresReboot: Boolean = false,
    val notes: List<String> = emptyList()
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
    fun evaluate(manifest: UpdateManifest, installed: InstalledVersions): UpdateVerdict = UpdateVerdict(
        app = appRow(manifest.apk, installed),
        module = moduleRow(manifest.module, installed),
        daemon = daemonRow(manifest.daemon, installed)
    )

    private fun appRow(apk: ApkRelease, installed: InstalledVersions): UpdateRowState {
        if (installed.deviceSdk < apk.minSdk) {
            return UpdateRowState(
                target = UpdateTarget.APP,
                installedVersion = installed.appVersion,
                availableVersion = apk.version,
                status = UpdateStatus.INCOMPATIBLE,
                notes = listOf("This release needs Android API ${apk.minSdk}; this phone is API ${installed.deviceSdk}.")
            )
        }
        if (apk.versionCode > installed.appVersionCode) {
            return UpdateRowState(
                target = UpdateTarget.APP,
                installedVersion = installed.appVersion,
                availableVersion = apk.version,
                status = UpdateStatus.UPDATE_AVAILABLE
            )
        }
        return UpdateRowState(
            target = UpdateTarget.APP,
            installedVersion = installed.appVersion,
            availableVersion = apk.version,
            status = UpdateStatus.UP_TO_DATE
        )
    }

    private fun moduleRow(module: ModuleRelease, installed: InstalledVersions): UpdateRowState {
        if (module.schemaVersion > MODULE_SCHEMA_VERSION) {
            return UpdateRowState(
                target = UpdateTarget.MODULE,
                installedVersion = installed.moduleVersion,
                availableVersion = module.version,
                status = UpdateStatus.INCOMPATIBLE,
                notes = listOf(
                    "This module release uses payload schema ${module.schemaVersion}; this app understands up to " +
                        "$MODULE_SCHEMA_VERSION. Update the app first."
                )
            )
        }
        if (installed.moduleInstalled == null) {
            return UpdateRowState(
                target = UpdateTarget.MODULE,
                installedVersion = installed.moduleVersion,
                availableVersion = module.version,
                status = UpdateStatus.UNKNOWN,
                notes = listOf("The module state could not be read; root access is needed to check it.")
            )
        }
        if (!installed.moduleInstalled) {
            return UpdateRowState(
                target = UpdateTarget.MODULE,
                installedVersion = null,
                availableVersion = module.version,
                status = UpdateStatus.UPDATE_AVAILABLE,
                requiresReboot = true,
                notes = listOf("The module is not installed, so this phone has no privileged copies.")
            )
        }
        val installedCode = (installed.moduleVersionCode ?: 0).toLong()
        if (module.versionCode > installedCode) {
            val missingPermissions = module.permissions.toSet() - installed.grantedPrivilegedPermissions
            val notes = buildList {
                if (installed.moduleDisabled) add("The installed module is disabled; enable it in Doctor.")
                if (module.requiresReboot) add("A reboot applies the module update.")
                if (missingPermissions.isNotEmpty()) {
                    add(
                        "New privileged grants in this release: " +
                            missingPermissions.sorted().joinToString(", ") + ". Reboot to apply them."
                    )
                }
            }
            return UpdateRowState(
                target = UpdateTarget.MODULE,
                installedVersion = installed.moduleVersion,
                availableVersion = module.version,
                status = UpdateStatus.UPDATE_AVAILABLE,
                requiresReboot = module.requiresReboot || missingPermissions.isNotEmpty(),
                notes = notes
            )
        }
        return UpdateRowState(
            target = UpdateTarget.MODULE,
            installedVersion = installed.moduleVersion,
            availableVersion = module.version,
            status = UpdateStatus.UP_TO_DATE,
            notes = listOfNotNull(
                "The installed module is disabled; enable it in Doctor.".takeIf { installed.moduleDisabled }
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
                notes = installed.daemonNotes.ifEmpty {
                    listOf("This app and the daemon disagree about the protocol; update the one named above.")
                }
            )
        }
        if (installed.daemonVersion == null) {
            return UpdateRowState(
                target = UpdateTarget.DAEMON,
                installedVersion = null,
                availableVersion = daemon.minVersion,
                status = UpdateStatus.UNKNOWN,
                notes = listOf("The daemon did not answer, so its version is unknown.")
            )
        }
        if (VersionOrder.compare(installed.daemonVersion, daemon.minVersion) < 0) {
            return UpdateRowState(
                target = UpdateTarget.DAEMON,
                installedVersion = installed.daemonVersion,
                availableVersion = daemon.minVersion,
                status = UpdateStatus.UPDATE_AVAILABLE,
                notes = listOf("This app needs daemon ${daemon.minVersion} or newer on the computer.")
            )
        }
        val appApi = installed.daemonAppApi
        if (appApi != null && appApi < daemon.appApi) {
            return UpdateRowState(
                target = UpdateTarget.DAEMON,
                installedVersion = installed.daemonVersion,
                availableVersion = daemon.minVersion,
                status = UpdateStatus.UPDATE_AVAILABLE,
                notes = listOf("The daemon speaks app API $appApi; this app needs app API ${daemon.appApi}.")
            )
        }
        if (installed.daemonProtocolVersions.isNotEmpty() && daemon.protocol !in installed.daemonProtocolVersions) {
            return UpdateRowState(
                target = UpdateTarget.DAEMON,
                installedVersion = installed.daemonVersion,
                availableVersion = daemon.minVersion,
                status = UpdateStatus.INCOMPATIBLE,
                notes = listOf(
                    "The daemon accepts device protocol " +
                        installed.daemonProtocolVersions.joinToString(", ") + "; this app speaks ${daemon.protocol}."
                )
            )
        }
        return UpdateRowState(
            target = UpdateTarget.DAEMON,
            installedVersion = installed.daemonVersion,
            availableVersion = daemon.minVersion,
            status = UpdateStatus.UP_TO_DATE,
            notes = installed.daemonNotes
        )
    }
}
