package com.phonxiety.bridge.data.updates

import android.content.Context
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.root.ModuleState
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

    /** What the Rollback card offers; null until the first check has probed the phone. */
    val rollbackPlan: StateFlow<RollbackPlan?>

    /** A rollback a previous boot left pending or completed, read from the root-owned journal. */
    val rollbackRecovery: StateFlow<RollbackRecovery?>

    /** The combined rollback's progress; [RollbackState.Idle] when it was not started. */
    val moduleRollback: StateFlow<RollbackState>

    suspend fun check(channel: String? = null): UpdateCheck

    /** Download, verify and install the APK in [manifest]; the previous APK is kept for a rollback. */
    suspend fun installApp(manifest: UpdateManifest)

    /** Rebuild the module zip from this build and install it; a reboot follows. */
    suspend fun installModule(manifest: UpdateManifest)

    /**
     * Roll back as [rollbackPlan] says: the kept APK alone when the mounted system APK is not newer,
     * otherwise the previous module, then the detached removal of the app update and a reboot.
     */
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
    private val moduleGateway: ModuleInstallGateway,
    private val previousModule: PreviousModuleCapture,
    private val mountedApk: MountedSystemApk,
    private val archives: ModuleArchivePublisher,
    private val journalStore: RollbackJournalStore,
    private val rollbackRoot: RollbackRootSteps,
    private val rootAccess: AndroidRootAccess
) : UpdatesService {
    private val appContext = context.applicationContext
    private val pendingDirectory = File(appContext.filesDir, PENDING_DIRECTORY)
    private val action = Mutex()

    private val _appInstall = MutableStateFlow<AppInstallState>(AppInstallState.Idle)
    private val _moduleInstall = MutableStateFlow<ModuleInstallState>(ModuleInstallState.Idle)
    private val _rollbackPlan = MutableStateFlow<RollbackPlan?>(null)
    private val _rollbackRecovery = MutableStateFlow<RollbackRecovery?>(null)
    private val _moduleRollback = MutableStateFlow<RollbackState>(RollbackState.Idle)

    override val appInstall: StateFlow<AppInstallState> = _appInstall.asStateFlow()
    override val moduleInstall: StateFlow<ModuleInstallState> = _moduleInstall.asStateFlow()
    override val rollbackPlan: StateFlow<RollbackPlan?> = _rollbackPlan.asStateFlow()
    override val rollbackRecovery: StateFlow<RollbackRecovery?> = _rollbackRecovery.asStateFlow()
    override val moduleRollback: StateFlow<RollbackState> = _moduleRollback.asStateFlow()

    override suspend fun check(channel: String?): UpdateCheck {
        val checkedAt = System.currentTimeMillis()
        // The rollback card and its recovery do not depend on the daemon: they read the phone.
        refreshRollback()
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
            refreshRollback()
        }
    }

    override suspend fun installModule(manifest: UpdateManifest) {
        action.withLock {
            if (_moduleInstall.value.busy) return
            ModuleUpdateCoordinator(moduleGateway, previousModule) { _moduleInstall.value = it }.install(manifest)
        }
    }

    override suspend fun rollbackApp() {
        action.withLock {
            if (_appInstall.value.busy || _moduleInstall.value.busy || _moduleRollback.value.busy) return
            val plan = RollbackPlanner.plan(rollbackFacts())
            _rollbackPlan.value = plan
            when (plan) {
                is RollbackPlan.AppOnly -> {
                    AppRollbackCoordinator(keeper, privilegedInstaller) { _appInstall.value = it }.rollback()
                    refreshRollback()
                }

                is RollbackPlan.WithPreviousModule -> {
                    val result = ModuleRollbackCoordinator(
                        archives = archives,
                        root = rollbackRoot,
                        app = runningApp(),
                        // A pending journal means that archive is already staged; retry only the hand-off.
                        stagedArchiveSha256 = journalStore.readJournal()?.targets?.moduleSha256,
                        onState = { _moduleRollback.value = it }
                    ).rollback(plan.previousVersionCode)
                    // A started rollback is completed by the detached script and the next boot; a
                    // failure is reported with what it already applied instead of being re-read.
                    if (result !is RollbackState.Started) refreshRollback()
                }

                is RollbackPlan.Unavailable -> {
                    _moduleRollback.value = RollbackState.Failed(
                        code = when (plan.blocker) {
                            RollbackBlocker.NO_KEPT_APK -> RollbackFailure.NO_KEPT_APK
                            RollbackBlocker.NO_PREVIOUS_MODULE -> RollbackFailure.NO_PREVIOUS_MODULE
                            RollbackBlocker.SYSTEM_VERSION_UNKNOWN -> RollbackFailure.SYSTEM_VERSION_UNKNOWN
                            RollbackBlocker.STORAGE_UNREADABLE -> RollbackFailure.STORAGE_UNREADABLE
                            RollbackBlocker.STALE_ARCHIVE -> RollbackFailure.STALE_ARCHIVE
                        }
                    )
                }
            }
        }
    }

    override fun dismiss() {
        _appInstall.value = AppInstallState.Idle
        _moduleInstall.value = ModuleInstallState.Idle
        _moduleRollback.value = RollbackState.Idle
    }

    /** Re-reads the plan and the journal; the rollback card must never depend on the daemon. */
    private suspend fun refreshRollback() {
        _rollbackPlan.value = RollbackPlanner.plan(rollbackFacts())
        val journal = journalStore.readJournal()
        val recovery = journal?.let {
            RollbackVerification.verify(
                journal = it,
                selectedApkPath = appContext.applicationInfo.sourceDir,
                runningVersionCode = installedVersionCode(),
                mountedModuleVersionCode = mountedModuleVersionCode()
            )
        }
        if (recovery is RollbackRecovery.Completed) journalStore.removeJournal()
        _rollbackRecovery.value = recovery
    }

    private suspend fun rollbackFacts(): RollbackFacts = RollbackFacts(
        keptApkExists = keeper.keptApk() != null,
        keptVersionName = keeper.keptVersionName(),
        keptVersionCode = keeper.keptVersionCode(),
        mounted = mountedApk.read(),
        archive = archives.read()
    )

    private fun runningApp(): RunningApp {
        val info = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        return RunningApp(
            packageName = appContext.packageName,
            versionName = info?.versionName,
            versionCode = info?.longVersionCode ?: installedVersionCode()
        )
    }

    /** The module actually mounted on the system image; a staged one has not been applied yet. */
    private suspend fun mountedModuleVersionCode(): Long? = when (val state = runCatching { rootAccess.moduleState() }.getOrNull()) {
        is ModuleState.Enabled -> state.versionCode.toLong()
        is ModuleState.Disabled -> state.versionCode.toLong()
        else -> null
    }

    private fun installedVersionCode(): Long = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    private companion object {
        const val PENDING_DIRECTORY: String = "updates"
    }
}
