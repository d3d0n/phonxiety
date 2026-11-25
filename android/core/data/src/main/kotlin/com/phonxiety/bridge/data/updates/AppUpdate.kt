package com.phonxiety.bridge.data.updates

import java.io.File

/**
 * Where the App row is. Every state is reachable from the screen, so a stalled download, a refused
 * certificate and the platform's own confirmation dialog each look different.
 */
sealed interface AppInstallState {
    data object Idle : AppInstallState

    /** [progress] is 0..1 when the server sent a length, null when it did not. */
    data class Downloading(val progress: Float?) : AppInstallState

    data object Verifying : AppInstallState

    data class Installing(val sessionId: Int?) : AppInstallState

    /** The platform is asking the user (or Play Protect is scanning); it finishes this install. */
    data object AwaitingConfirmation : AppInstallState

    data class Installed(val versionName: String?) : AppInstallState

    data class Failed(val code: InstallFailure, val detail: String? = null) : AppInstallState

    val busy: Boolean
        get() = this is Downloading || this is Verifying || this is Installing || this is AwaitingConfirmation
}

/** Why an app install stopped; each code has its own sentence on the screen. */
enum class InstallFailure {
    DOWNLOAD_FAILED,
    HASH_MISMATCH,
    SIGNING_CERT_MISMATCH,
    WRONG_PACKAGE,
    NOT_AN_APK,
    DOWNGRADE_REFUSED,
    INSTALL_BLOCKED,
    INSTALL_ABORTED,
    INSTALL_CONFLICT,
    INSTALL_INCOMPATIBLE,
    STORAGE_FULL,
    INSTALL_TIMED_OUT,
    INSTALL_FAILED,
    NO_KEPT_APK,
    ROOT_UNAVAILABLE,
    ROLLBACK_FAILED
}

/** A downloaded artifact and the digest of exactly the bytes that landed on disk. */
data class DownloadedFile(val file: File, val sha256: String, val size: Long)

/** Bytes for one release artifact: https, or the daemon's own update directory over the link. */
fun interface ReleaseDownloader {
    suspend fun download(source: ReleaseSource, destination: File, onProgress: (Float?) -> Unit): DownloadedFile
}

sealed interface ApkVerification {
    data class Valid(val versionName: String?, val versionCode: Long) : ApkVerification

    data class HashMismatch(val expected: String, val actual: String) : ApkVerification

    /** The APK's signers differ from the installed app's, or do not include the pinned certificate. */
    data class SigningCertMismatch(val expected: Set<String>, val found: Set<String>) : ApkVerification

    data class WrongPackage(val found: String?) : ApkVerification

    data object NotAnApk : ApkVerification
}

/**
 * Verifies the bytes *and* the signer: a matching SHA-256 over a differently signed APK is still an
 * attacker with the update URL, and Android would refuse it after the download anyway.
 */
fun interface ApkVerifier {
    fun verify(file: File, expectedSha256: String, expectedSigningCertSha256: String): ApkVerification
}

sealed interface ApkInstallOutcome {
    data class Installed(val versionName: String?) : ApkInstallOutcome

    data object AwaitingConfirmation : ApkInstallOutcome

    data class Failed(val code: InstallFailure, val detail: String?) : ApkInstallOutcome
}

/**
 * Installs an APK over this app: a `PackageInstaller` session when the app holds
 * `INSTALL_SELF_UPDATES`, the system confirmation intent otherwise. Progress is reported through
 * [onState] so the screen can show a session id or the pending-dialog state.
 */
fun interface ApkInstallGateway {
    suspend fun install(apk: File, onState: (AppInstallState) -> Unit): ApkInstallOutcome
}

/** The APK this update replaces, kept in app files so a rollback needs no network. */
interface PreviousApkKeeper {
    suspend fun keepCurrentApk(): File?

    fun keptApk(): File?

    fun keptVersionName(): String?

    fun keptVersionCode(): Long
}

/** `pm install -r -d` through [com.phonxiety.bridge.root.RootShell]; downgrades need root. */
fun interface PrivilegedApkInstaller {
    suspend fun installWithRoot(apk: File): ApkInstallOutcome
}

/**
 * Download → verify → install, with the kept APK taken *before* the install so a failure leaves a
 * rollback. It holds no Android types: every refusal is a fake in `AppUpdateCoordinatorTest`.
 */
class AppUpdateCoordinator(
    private val downloader: ReleaseDownloader,
    private val verifier: ApkVerifier,
    private val installer: ApkInstallGateway,
    private val keeper: PreviousApkKeeper,
    private val currentVersionCode: () -> Long,
    private val pendingDirectory: File,
    private val onState: (AppInstallState) -> Unit
) {
    suspend fun install(apk: ApkRelease): AppInstallState {
        val destination = File(pendingDirectory, "phonxiety-${apk.versionCode}.apk")
        destination.delete()
        onState(AppInstallState.Downloading(null))
        val downloaded = try {
            downloader.download(apk.source(), destination) { progress ->
                onState(AppInstallState.Downloading(progress))
            }
        } catch (error: Exception) {
            destination.delete()
            return finish(AppInstallState.Failed(InstallFailure.DOWNLOAD_FAILED, error.message))
        }

        onState(AppInstallState.Verifying)
        if (!downloaded.sha256.equals(apk.sha256, ignoreCase = true)) {
            destination.delete()
            return finish(
                AppInstallState.Failed(
                    InstallFailure.HASH_MISMATCH,
                    "expected ${apk.sha256}, downloaded ${downloaded.sha256}"
                )
            )
        }

        val verification = verifier.verify(downloaded.file, apk.sha256, apk.signingCertSha256)
        val checked = when (verification) {
            is ApkVerification.Valid -> verification

            is ApkVerification.HashMismatch -> {
                destination.delete()
                return finish(
                    AppInstallState.Failed(
                        InstallFailure.HASH_MISMATCH,
                        "expected ${verification.expected}, read ${verification.actual}"
                    )
                )
            }

            is ApkVerification.SigningCertMismatch -> {
                destination.delete()
                return finish(
                    AppInstallState.Failed(
                        InstallFailure.SIGNING_CERT_MISMATCH,
                        "installed ${verification.expected.joinToString()}, archive ${verification.found.joinToString()}"
                    )
                )
            }

            is ApkVerification.WrongPackage -> {
                destination.delete()
                return finish(AppInstallState.Failed(InstallFailure.WRONG_PACKAGE, verification.found))
            }

            ApkVerification.NotAnApk -> {
                destination.delete()
                return finish(AppInstallState.Failed(InstallFailure.NOT_AN_APK, "the download is not a readable APK"))
            }
        }

        if (checked.versionCode < currentVersionCode()) {
            destination.delete()
            return finish(
                AppInstallState.Failed(
                    InstallFailure.DOWNGRADE_REFUSED,
                    "the archive is ${checked.versionCode}, the installed app is ${currentVersionCode()}"
                )
            )
        }

        keeper.keepCurrentApk()
        onState(AppInstallState.Installing(null))
        val outcome = try {
            installer.install(downloaded.file, onState)
        } catch (error: SecurityException) {
            ApkInstallOutcome.Failed(InstallFailure.INSTALL_BLOCKED, error.message)
        } catch (error: Exception) {
            ApkInstallOutcome.Failed(InstallFailure.INSTALL_FAILED, error.message)
        }

        return when (outcome) {
            is ApkInstallOutcome.Installed -> {
                destination.delete()
                finish(AppInstallState.Installed(outcome.versionName ?: checked.versionName))
            }

            ApkInstallOutcome.AwaitingConfirmation -> finish(AppInstallState.AwaitingConfirmation)

            is ApkInstallOutcome.Failed -> {
                destination.delete()
                finish(AppInstallState.Failed(outcome.code, outcome.detail))
            }
        }
    }

    private fun finish(state: AppInstallState): AppInstallState {
        onState(state)
        return state
    }
}

/** Reinstalls the kept previous APK; root is what allows the version code to go backwards. */
class AppRollbackCoordinator(
    private val keeper: PreviousApkKeeper,
    private val installer: PrivilegedApkInstaller,
    private val onState: (AppInstallState) -> Unit
) {
    suspend fun rollback(): AppInstallState {
        val kept = keeper.keptApk()
            ?: return finish(AppInstallState.Failed(InstallFailure.NO_KEPT_APK, "no previous APK was kept"))
        onState(AppInstallState.Installing(null))
        val outcome = installer.installWithRoot(kept)
        return when (outcome) {
            is ApkInstallOutcome.Installed ->
                finish(AppInstallState.Installed(keeper.keptVersionName() ?: outcome.versionName))

            ApkInstallOutcome.AwaitingConfirmation ->
                finish(AppInstallState.Failed(InstallFailure.ROLLBACK_FAILED, "root did not confirm the rollback"))

            is ApkInstallOutcome.Failed -> finish(AppInstallState.Failed(outcome.code, outcome.detail))
        }
    }

    private fun finish(state: AppInstallState): AppInstallState {
        onState(state)
        return state
    }
}
