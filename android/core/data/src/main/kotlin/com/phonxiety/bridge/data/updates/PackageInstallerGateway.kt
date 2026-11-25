package com.phonxiety.bridge.data.updates

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import com.phonxiety.bridge.root.PrivilegedPermissions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Installs an APK over this app.
 *
 * Privileged path: a `PackageInstaller` session with `setRequestUpdateOwnership(true)`, which needs
 * `INSTALL_SELF_UPDATES` (granted by the module's privapp allowlist) and shows no dialog. The
 * platform's own completion and confirmation intents arrive at [PackageInstallerResultReceiver].
 *
 * Fallback: the system package installer with a `FileProvider` URI, for an app installed by `adb`
 * before the module exists. The user confirms; this process usually restarts on success.
 */
@Singleton
class PackageInstallerGateway @Inject constructor(
    @ApplicationContext private val context: Context
) : ApkInstallGateway {
    private val appContext = context.applicationContext

    override suspend fun install(apk: File, onState: (AppInstallState) -> Unit): ApkInstallOutcome {
        if (!holdsSelfUpdatePermission()) return launchInstaller(apk)
        val outcome = installWithSession(apk, onState)
        // A policy on this device may still refuse the silent session; the user-confirmation path
        // is the documented fallback, not a dead end.
        return if (outcome is ApkInstallOutcome.Failed && outcome.code == InstallFailure.INSTALL_BLOCKED) {
            launchInstaller(apk)
        } else {
            outcome
        }
    }

    private fun holdsSelfUpdatePermission(): Boolean = appContext.checkSelfPermission(PrivilegedPermissions.INSTALL_SELF_UPDATES) ==
        PackageManager.PERMISSION_GRANTED

    // This app updates itself: `INSTALL_SELF_UPDATES` is checked before the session exists, and
    // `ENFORCE_UPDATE_OWNERSHIP` is what an installer of *other* packages needs.
    @SuppressLint("MissingPermission")
    private suspend fun installWithSession(apk: File, onState: (AppInstallState) -> Unit): ApkInstallOutcome {
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setRequestUpdateOwnership(true)
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (error: Exception) {
            return ApkInstallOutcome.Failed(InstallFailure.INSTALL_FAILED, error.message)
        }
        val statuses = Channel<InstallerStatus>(Channel.UNLIMITED)
        channels[sessionId] = statuses
        try {
            val session = installer.openSession(sessionId)
            try {
                session.openWrite(APK_ENTRY, 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val statusIntent = Intent(appContext, PackageInstallerResultReceiver::class.java)
                    .setPackage(appContext.packageName)
                    .putExtra(PackageInstallerResultReceiver.EXTRA_SESSION_ID, sessionId)
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    statusIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                )
                session.commit(pendingIntent.intentSender)
            } finally {
                session.close()
            }
            onState(AppInstallState.Installing(sessionId))
            return awaitOutcome(statuses, onState)
        } catch (error: SecurityException) {
            return ApkInstallOutcome.Failed(InstallFailure.INSTALL_BLOCKED, error.message)
        } catch (error: Exception) {
            return ApkInstallOutcome.Failed(InstallFailure.INSTALL_FAILED, error.message)
        } finally {
            channels.remove(sessionId)
            statuses.close()
        }
    }

    private suspend fun awaitOutcome(
        statuses: Channel<InstallerStatus>,
        onState: (AppInstallState) -> Unit
    ): ApkInstallOutcome {
        while (true) {
            val status = withTimeoutOrNull(INSTALL_TIMEOUT_MS) { statuses.receiveCatching().getOrNull() }
                ?: return ApkInstallOutcome.Failed(
                    InstallFailure.INSTALL_TIMED_OUT,
                    "the installer did not answer within ${INSTALL_TIMEOUT_MS / 1000} s"
                )
            when (status.status) {
                PackageInstaller.STATUS_SUCCESS -> return ApkInstallOutcome.Installed(null)

                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    onState(AppInstallState.AwaitingConfirmation)
                    status.confirmation?.let { confirmation ->
                        runCatching {
                            appContext.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }

                else -> return ApkInstallOutcome.Failed(failureFor(status.status), status.message)
            }
        }
    }

    /** The user-confirmation path: the system installer UI, with the APK shared by [FileProvider]. */
    private fun launchInstaller(apk: File): ApkInstallOutcome {
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData("package:${appContext.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { appContext.startActivity(settings) }
            return ApkInstallOutcome.Failed(
                InstallFailure.INSTALL_BLOCKED,
                "Allow \"Install unknown apps\" for Phonxiety, then install the update again."
            )
        }
        val uri = runCatching {
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", apk)
        }.getOrElse { error ->
            return ApkInstallOutcome.Failed(InstallFailure.INSTALL_FAILED, error.message)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { appContext.startActivity(intent) }
            .fold(
                onSuccess = { ApkInstallOutcome.AwaitingConfirmation },
                onFailure = { error -> ApkInstallOutcome.Failed(InstallFailure.INSTALL_FAILED, error.message) }
            )
    }

    private fun failureFor(status: Int): InstallFailure = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallFailure.INSTALL_BLOCKED
        PackageInstaller.STATUS_FAILURE_ABORTED -> InstallFailure.INSTALL_ABORTED
        PackageInstaller.STATUS_FAILURE_INVALID -> InstallFailure.NOT_AN_APK
        PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallFailure.INSTALL_CONFLICT
        PackageInstaller.STATUS_FAILURE_STORAGE -> InstallFailure.STORAGE_FULL
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallFailure.INSTALL_INCOMPATIBLE
        else -> InstallFailure.INSTALL_FAILED
    }

    companion object {
        private const val APK_ENTRY = "base.apk"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val INSTALL_TIMEOUT_MS = 10 * 60 * 1000L

        /** Live sessions by id; the receiver runs in this process and hands the status back here. */
        private val channels = ConcurrentHashMap<Int, Channel<InstallerStatus>>()

        internal fun deliver(status: InstallerStatus) {
            channels[status.sessionId]?.trySend(status)
        }
    }
}

/** One status the platform reported for a session, with the confirmation activity when it asks. */
data class InstallerStatus(
    val sessionId: Int,
    val status: Int,
    val message: String?,
    val confirmation: Intent?
)

/**
 * Receives `PackageInstaller` status intents for this app's session: success, failure, or a request
 * to show the platform's confirmation dialog.
 */
class PackageInstallerResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        if (sessionId < 0) return
        PackageInstallerGateway.deliver(
            InstallerStatus(
                sessionId = sessionId,
                status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
                message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                confirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            )
        )
    }

    companion object {
        const val EXTRA_SESSION_ID: String = "com.phonxiety.bridge.updates.SESSION_ID"
    }
}
