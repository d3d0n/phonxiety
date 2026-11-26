package com.phonxiety.bridge.feature.updates

import com.phonxiety.bridge.data.updates.ApkRelease
import com.phonxiety.bridge.data.updates.AppInstallState
import com.phonxiety.bridge.data.updates.DaemonRelease
import com.phonxiety.bridge.data.updates.InstallFailure
import com.phonxiety.bridge.data.updates.InstalledVersions
import com.phonxiety.bridge.data.updates.ModuleRelease
import com.phonxiety.bridge.data.updates.RollbackBlocker
import com.phonxiety.bridge.data.updates.RollbackFailure
import com.phonxiety.bridge.data.updates.RollbackPendingReason
import com.phonxiety.bridge.data.updates.RollbackPlan
import com.phonxiety.bridge.data.updates.RollbackRecovery
import com.phonxiety.bridge.data.updates.RollbackState
import com.phonxiety.bridge.data.updates.RollbackStep
import com.phonxiety.bridge.data.updates.UpdateCheck
import com.phonxiety.bridge.data.updates.UpdateCheckError
import com.phonxiety.bridge.data.updates.UpdateCompat
import com.phonxiety.bridge.data.updates.UpdateManifest

/**
 * Fixed data for previews and screenshot tests: a 0.4.0 release published by the daemon, compared
 * with a phone on 0.4.0 (current), 0.3.0 (an update is offered) or an older Android (incompatible).
 */
object UpdatesSamples {
    private val ALL_PERMISSIONS = setOf(
        "android.permission.CAPTURE_AUDIO_OUTPUT",
        "android.permission.MODIFY_PHONE_STATE",
        "android.permission.CONTROL_INCALL_EXPERIENCE",
        "android.permission.INSTALL_SELF_UPDATES",
        "android.permission.REBOOT",
        "android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND"
    )

    val manifest = UpdateManifest(
        version = "0.4.0",
        versionCode = 4,
        zipUrl = "https://example.test/phonxiety-bridge-0.4.0.zip",
        changelog = "https://example.test/CHANGELOG.md",
        updateJsonUrl = "https://example.test/update.json",
        publishedAt = "2026-11-26T12:00:00+00:00",
        apk = ApkRelease(
            version = "0.4.0",
            versionCode = 4,
            url = "https://example.test/phonxiety-bridge-0.4.0.apk",
            sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            size = 7_340_032,
            signingCertSha256 = "aaaabbbbccccddddeeeeffff0000111122223333444455556666777788889999",
            minSdk = 34
        ),
        module = ModuleRelease(
            version = "0.4.0",
            versionCode = 4,
            zipUrl = "https://example.test/phonxiety-bridge-0.4.0.zip",
            sha256 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
            size = 1_048_576,
            schemaVersion = 1,
            requiresReboot = true,
            permissions = ALL_PERMISSIONS.toList()
        ),
        daemon = DaemonRelease(minVersion = "0.1.0", protocol = 3, appApi = 1)
    )

    fun installed(
        appVersionCode: Long = 4,
        appVersion: String = "0.4.0",
        moduleVersionCode: Int = 4,
        moduleVersion: String = "0.4.0",
        deviceSdk: Int = 35,
        daemonVersion: String? = "0.1.0",
        daemonCompatible: Boolean? = true,
        moduleInstalled: Boolean = true,
        moduleDisabled: Boolean = false,
        granted: Set<String> = ALL_PERMISSIONS,
        daemonNotes: List<String> = emptyList()
    ) = InstalledVersions(
        appVersion = appVersion,
        appVersionCode = appVersionCode,
        deviceSdk = deviceSdk,
        moduleVersion = moduleVersion,
        moduleVersionCode = moduleVersionCode,
        moduleInstalled = moduleInstalled,
        moduleDisabled = moduleDisabled,
        grantedPrivilegedPermissions = granted,
        daemonVersion = daemonVersion,
        daemonAppApi = 1,
        daemonProtocolVersions = listOf(3),
        daemonCompatible = daemonCompatible,
        daemonNotes = daemonNotes
    )

    fun check(installed: InstalledVersions = installed()): UpdateCheck = UpdateCheck(
        manifest = manifest,
        verdict = UpdateCompat.evaluate(manifest, installed),
        installed = installed,
        checkedAtEpochMs = 0L
    )

    fun upToDate(): UpdatesUiState = UpdatesUiState(check = check())

    fun updateAvailable(): UpdatesUiState = UpdatesUiState(
        check = check(installed(appVersionCode = 3, appVersion = "0.3.0", moduleVersionCode = 3, moduleVersion = "0.3.0"))
    )

    fun incompatible(): UpdatesUiState = UpdatesUiState(
        check = check(
            installed(
                deviceSdk = 33,
                daemonVersion = "0.0.9",
                daemonCompatible = false
            )
        )
    )

    fun installing(): UpdatesUiState = updateAvailable().copy(
        appInstall = AppInstallState.Installing(sessionId = 7)
    )

    fun downloading(): UpdatesUiState = updateAvailable().copy(
        appInstall = AppInstallState.Downloading(progress = 0.42f)
    )

    fun awaitingConfirmation(): UpdatesUiState = updateAvailable().copy(
        appInstall = AppInstallState.AwaitingConfirmation
    )

    fun failed(): UpdatesUiState = updateAvailable().copy(
        appInstall = AppInstallState.Failed(
            InstallFailure.SIGNING_CERT_MISMATCH,
            "installed certificate: aaaabbbbccccddddeeeeffff0000111122223333444455556666777788889999\n" +
                "archive certificate: 9999ffff888877776666555544443333222211110000999988887777666655554444"
        ),
        rollbackPlan = appOnlyRollback()
    )

    fun checkFailed(): UpdatesUiState = UpdatesUiState(
        check = UpdateCheck(
            error = UpdateCheckError.LINK_DOWN,
            detail = "the daemon did not answer",
            checkedAtEpochMs = 0L
        )
    )

    fun manifestMissing(): UpdatesUiState = UpdatesUiState(
        check = UpdateCheck(error = UpdateCheckError.NO_MANIFEST, checkedAtEpochMs = 0L)
    )

    /** A module update that grants two privileged permissions this installation does not hold. */
    fun modulePermissions(): UpdatesUiState = UpdatesUiState(
        check = check(
            installed(
                appVersionCode = 3,
                appVersion = "0.3.0",
                moduleVersionCode = 3,
                moduleVersion = "0.3.0",
                granted = setOf(
                    "android.permission.CAPTURE_AUDIO_OUTPUT",
                    "android.permission.MODIFY_PHONE_STATE",
                    "android.permission.INSTALL_SELF_UPDATES"
                )
            )
        )
    )

    fun moduleDisabled(): UpdatesUiState = UpdatesUiState(
        check = check(
            installed(
                appVersionCode = 4,
                appVersion = "0.4.0",
                moduleVersionCode = 3,
                moduleVersion = "0.3.0",
                moduleDisabled = true
            )
        )
    )

    /**
     * The release's app already runs, the installed module has the same version code and still
     * lacks the new grant: the module row offers the install, not "Up to date".
     */
    fun moduleGrantMissing(): UpdatesUiState = UpdatesUiState(
        check = check(
            installed(
                granted = ALL_PERMISSIONS - "android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND"
            )
        )
    )

    /** A call is running: every action that would end it is disabled and says why. */
    fun callActive(): UpdatesUiState = updateAvailable().copy(
        rollbackPlan = appOnlyRollback(),
        callActive = true
    )

    fun appOnlyRollback(): RollbackPlan.AppOnly = RollbackPlan.AppOnly(keptVersionName = "0.3.0", keptVersionCode = 3)

    fun moduleRollback(): RollbackPlan.WithPreviousModule = RollbackPlan.WithPreviousModule(
        moduleVersionName = "0.4.0",
        moduleVersionCode = 4,
        previousVersionName = "0.3.0",
        previousVersionCode = 3,
        previousSha256 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    )

    fun rollbackAppOnly(): UpdatesUiState = upToDate().copy(rollbackPlan = appOnlyRollback())

    fun rollbackWithModule(): UpdatesUiState = upToDate().copy(rollbackPlan = moduleRollback())

    fun rollbackConfirming(): UpdatesUiState = rollbackWithModule().copy(
        rollbackConfirmation = moduleRollback()
    )

    fun rollbackConfirmingAppOnly(): UpdatesUiState = rollbackAppOnly().copy(
        rollbackConfirmation = appOnlyRollback()
    )

    fun rollbackBlocked(): UpdatesUiState = upToDate().copy(
        rollbackPlan = RollbackPlan.Unavailable(
            blocker = RollbackBlocker.NO_PREVIOUS_MODULE,
            moduleVersion = "0.4.0",
            moduleVersionCode = 4,
            keptVersionName = "0.3.0",
            keptVersionCode = 3
        )
    )

    fun rollbackUnfinished(): UpdatesUiState = rollbackWithModule().copy(
        rollbackRecovery = RollbackRecovery.Pending(
            reason = RollbackPendingReason.REMOVAL_FAILED,
            detail = "Failure [DELETE_FAILED_INTERNAL_ERROR]"
        )
    )

    fun rollbackCompleted(): UpdatesUiState = upToDate().copy(
        // After a completed rollback the phone runs the kept version, so the rollback section has
        // nothing left to offer; offering to reinstall the previous app here was a bug seen on the phone.
        rollbackPlan = RollbackPlan.Unavailable(
            blocker = RollbackBlocker.APP_NOT_NEWER,
            keptVersionName = "0.3.0",
            keptVersionCode = 3
        ),
        rollbackRecovery = RollbackRecovery.Completed(versionName = "0.3.0")
    )

    fun rollbackStarted(): UpdatesUiState = rollbackWithModule().copy(
        moduleRollback = RollbackState.Started(versionName = "0.3.0")
    )

    fun rollbackFailed(): UpdatesUiState = rollbackWithModule().copy(
        moduleRollback = RollbackState.Failed(
            RollbackFailure.REMOVAL_NOT_STARTED,
            "su: permission denied",
            applied = listOf(RollbackStep.MODULE_STAGED)
        )
    )

    /** The link is down, so no manifest and no app row: the rollback card still carries the failure. */
    fun rollbackAppOnlyFailedOffline(): UpdatesUiState = UpdatesUiState(
        check = UpdateCheck(
            error = UpdateCheckError.LINK_DOWN,
            detail = "the daemon did not answer",
            checkedAtEpochMs = 0L
        ),
        rollbackPlan = appOnlyRollback(),
        rollbackAppInstall = AppInstallState.Failed(
            InstallFailure.ROOT_UNAVAILABLE,
            "su: permission denied"
        )
    )

    fun rollbackAppOnlyRunningOffline(): UpdatesUiState = UpdatesUiState(
        check = UpdateCheck(
            error = UpdateCheckError.LINK_DOWN,
            detail = "the daemon did not answer",
            checkedAtEpochMs = 0L
        ),
        rollbackPlan = appOnlyRollback(),
        rollbackAppInstall = AppInstallState.Installing(sessionId = null)
    )
}
