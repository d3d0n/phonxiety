package com.phonxiety.bridge.data.updates

import android.content.Context
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.DaemonApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why the check could not produce a verdict. */
enum class UpdateCheckError {
    /** The daemon did not answer: the app API runs over the device link. */
    LINK_DOWN,

    /** The daemon has no `updates/manifest.json`; `mise run phone:publish` writes it. */
    NO_MANIFEST,

    /** The document exists but has no release on the requested channel. */
    UNKNOWN_CHANNEL,

    /** The document exists but a required field is missing or malformed. */
    MANIFEST_INVALID
}

/** One check: the published document, what this phone runs, and the verdict between them. */
data class UpdateCheck(
    val manifest: UpdateManifest? = null,
    val verdict: UpdateVerdict? = null,
    val installed: InstalledVersions? = null,
    val error: UpdateCheckError? = null,
    val detail: String? = null,
    val checkedAtEpochMs: Long = 0L
) {
    val checked: Boolean get() = manifest != null && verdict != null
}

/**
 * The Updates screen's data surface: one check, three actions. Actions never overlap: a second tap
 * while a download or an install is running is ignored, and the state flows are the only progress.
 */
interface UpdatesService {
    val appInstall: StateFlow<AppInstallState>

    val moduleInstall: StateFlow<ModuleInstallState>

    val rollbackAvailable: StateFlow<Boolean>

    suspend fun check(channel: String? = null): UpdateCheck

    /** Download, verify and install the APK in [manifest]; the previous APK is kept for a rollback. */
    suspend fun installApp(manifest: UpdateManifest)

    /** Rebuild the module zip from this build and install it; a reboot follows. */
    suspend fun installModule(manifest: UpdateManifest)

    /** Reinstall the kept previous APK as root (a downgrade needs `pm install -r -d`). */
    suspend fun rollbackApp()

    /** Clear a finished or failed action so the screen returns to its rows. */
    fun dismiss()
}

@Singleton
class DefaultUpdatesService @Inject constructor(
    @ApplicationContext context: Context,
    private val daemonApi: DaemonApi,
    private val inventory: UpdateInventory,
    private val downloader: ReleaseDownloader,
    private val verifier: ApkVerifier,
    private val installGateway: ApkInstallGateway,
    private val keeper: PreviousApkKeeper,
    private val privilegedInstaller: PrivilegedApkInstaller,
    private val moduleGateway: ModuleInstallGateway
) : UpdatesService {
    private val appContext = context.applicationContext
    private val pendingDirectory = File(appContext.filesDir, PENDING_DIRECTORY)
    private val action = Mutex()

    private val _appInstall = MutableStateFlow<AppInstallState>(AppInstallState.Idle)
    private val _moduleInstall = MutableStateFlow<ModuleInstallState>(ModuleInstallState.Idle)
    private val _rollbackAvailable = MutableStateFlow(false)

    override val appInstall: StateFlow<AppInstallState> = _appInstall.asStateFlow()
    override val moduleInstall: StateFlow<ModuleInstallState> = _moduleInstall.asStateFlow()
    override val rollbackAvailable: StateFlow<Boolean> = _rollbackAvailable.asStateFlow()

    override suspend fun check(channel: String?): UpdateCheck {
        val checkedAt = System.currentTimeMillis()
        _rollbackAvailable.value = keeper.keptApk() != null
        val manifest = when (val outcome = daemonApi.updateManifest(channel)) {
            is ApiOutcome.Failure -> return UpdateCheck(
                error = when (outcome.code) {
                    "manifest_unavailable" -> UpdateCheckError.NO_MANIFEST
                    "unknown_channel" -> UpdateCheckError.UNKNOWN_CHANNEL
                    else -> UpdateCheckError.LINK_DOWN
                },
                detail = outcome.message,
                checkedAtEpochMs = checkedAt
            )

            is ApiOutcome.Ok -> when (val parsed = UpdateManifestCodec.parse(outcome.value)) {
                is ManifestParse.Invalid -> return UpdateCheck(
                    error = UpdateCheckError.MANIFEST_INVALID,
                    detail = parsed.detail,
                    checkedAtEpochMs = checkedAt
                )

                is ManifestParse.Valid -> parsed.manifest
            }
        }
        val installed = inventory.read()
        return UpdateCheck(
            manifest = manifest,
            verdict = UpdateCompat.evaluate(manifest, installed),
            installed = installed,
            checkedAtEpochMs = checkedAt
        )
    }

    override suspend fun installApp(manifest: UpdateManifest) {
        action.withLock {
            if (_appInstall.value.busy) return
            val coordinator = AppUpdateCoordinator(
                downloader = downloader,
                verifier = verifier,
                installer = installGateway,
                keeper = keeper,
                currentVersionCode = ::installedVersionCode,
                pendingDirectory = pendingDirectory,
                onState = { _appInstall.value = it }
            )
            coordinator.install(manifest.apk)
            _rollbackAvailable.value = keeper.keptApk() != null
        }
    }

    override suspend fun installModule(manifest: UpdateManifest) {
        action.withLock {
            if (_moduleInstall.value.busy) return
            ModuleUpdateCoordinator(moduleGateway) { _moduleInstall.value = it }.install(manifest)
        }
    }

    override suspend fun rollbackApp() {
        action.withLock {
            if (_appInstall.value.busy) return
            AppRollbackCoordinator(keeper, privilegedInstaller) { _appInstall.value = it }.rollback()
            _rollbackAvailable.value = keeper.keptApk() != null
        }
    }

    override fun dismiss() {
        _appInstall.value = AppInstallState.Idle
        _moduleInstall.value = ModuleInstallState.Idle
    }

    private fun installedVersionCode(): Long = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    private companion object {
        const val PENDING_DIRECTORY: String = "updates"
    }
}
