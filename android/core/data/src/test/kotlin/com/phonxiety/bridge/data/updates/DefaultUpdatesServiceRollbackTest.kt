package com.phonxiety.bridge.data.updates

import android.content.Context
import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.AppApi
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.data.DefaultDaemonApi
import com.phonxiety.bridge.root.ModuleState
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The rollback after a reboot: the root-owned journal decides the next step, before any fresh plan.
 * With the manual reboot done and the removal failed, the phone runs the staged module next to the
 * newer data app — a fresh plan would choose an app-only install, which Android refuses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultUpdatesServiceRollbackTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val archive = PublishedModuleArchive("0.3.0", 3, "a".repeat(64))

    private companion object {
        const val SYSTEM_APK: String = "/system/priv-app/phonxiety-bridge/phonxiety-bridge.apk"
    }

    @Test
    fun `a failed removal is finished by its journal, never by an app only install`() = runTest {
        val root = FakeRoot()
        val privileged = RecordingPrivilegedInstaller()
        val service = service(
            journal = journal(RollbackJournalStep.FAILED),
            // The manual reboot applied the staged module: the journal's target is what runs.
            moduleState = ModuleState.Enabled("0.3.0", 3),
            // The mounted system APK and the kept APK are both 3, so a recomputed plan is AppOnly.
            mounted = MountedSystemApkVersion.Present(SYSTEM_APK, "0.3.0", 3),
            keeper = FakeKeeper(File(context.cacheDir, "previous.apk"), 3),
            root = root,
            privileged = privileged
        )
        assertEquals(
            RollbackPlan.AppOnly("0.3.0", 3),
            RollbackPlanner.plan(
                RollbackFacts(
                    keptApkExists = true,
                    keptVersionName = "0.3.0",
                    keptVersionCode = 3,
                    runningVersionCode = 4,
                    mounted = MountedSystemApkVersion.Present(SYSTEM_APK, "0.3.0", 3),
                    archive = RollbackArchiveState.Present(archive)
                )
            )
        )

        service.rollbackApp()

        assertEquals(0, privileged.installs)
        assertEquals(listOf("prepare", "start"), root.calls)
        assertEquals(3L, root.journal?.targets?.moduleVersionCode)
        assertEquals(RollbackState.Started("0.3.0"), service.moduleRollback.value)
    }

    @Test
    fun `a staged journal target is not reinstalled before the hand-off`() = runTest {
        val root = FakeRoot()
        val service = service(
            journal = journal(RollbackJournalStep.FAILED),
            // The reboot has not happened yet: the journal's target waits in modules_update.
            moduleState = ModuleState.Staged("0.3.0", 3),
            keeper = FakeKeeper(null, 0),
            root = root
        )

        service.rollbackApp()

        assertEquals(listOf("prepare", "start"), root.calls)
        assertEquals(3L, root.journal?.targets?.moduleVersionCode)
        assertEquals(archive.sha256, root.journal?.targets?.moduleSha256)
        assertEquals(RollbackState.Started("0.3.0"), service.moduleRollback.value)
    }

    @Test
    fun `a staged module that is not the journal's target is staged again`() = runTest {
        val root = FakeRoot()
        val service = service(
            journal = journal(RollbackJournalStep.FAILED),
            moduleState = ModuleState.Staged("0.3.0", 4),
            keeper = FakeKeeper(null, 0),
            root = root
        )

        service.rollbackApp()

        assertEquals(listOf("prepare", "stage", "start"), root.calls)
    }

    @Test
    fun `recovery reads the journal's generation even when the pointer names another digest`() = runTest {
        val other = PublishedModuleArchive("0.3.0", 3, "b".repeat(64))
        val root = FakeRoot()
        val service = service(
            journal = journal(RollbackJournalStep.FAILED),
            moduleState = ModuleState.Enabled("0.3.0", 3),
            keeper = FakeKeeper(null, 0),
            root = root,
            archives = FakeArchives(RollbackArchiveState.Present(other)) { sha256 ->
                if (sha256 == archive.sha256) RollbackArchiveState.Present(archive) else RollbackArchiveState.Missing
            }
        )

        service.rollbackApp()

        assertEquals(listOf("prepare", "start"), root.calls)
        assertEquals(archive.sha256, root.journal?.targets?.moduleSha256)
    }

    @Test
    fun `a same version archive with a different digest is refused, never written into the journal`() = runTest {
        val other = PublishedModuleArchive("0.3.0", 3, "b".repeat(64))
        val journals = FakeJournalStore(journal(RollbackJournalStep.FAILED))
        val root = FakeRoot()
        val service = service(
            journalStore = journals,
            moduleState = ModuleState.Enabled("0.3.0", 3),
            keeper = FakeKeeper(null, 0),
            root = root,
            archives = FakeArchives(RollbackArchiveState.Present(other)) {
                RollbackArchiveState.Mismatch(archive.sha256, other.sha256)
            }
        )

        service.rollbackApp()

        val failed = service.moduleRollback.value as RollbackState.Failed
        assertEquals(RollbackFailure.ARCHIVE_MISMATCH, failed.code)
        assertTrue(failed.detail!!.contains(archive.sha256))
        assertTrue(failed.detail!!.contains(other.sha256))
        assertTrue(root.calls.isEmpty())
        assertEquals(archive.sha256, journals.journal?.targets?.moduleSha256)
    }

    @Test
    fun `a recorded generation that is gone is never replaced by the pointer's archive`() = runTest {
        val other = PublishedModuleArchive("0.3.0", 3, "b".repeat(64))
        val journals = FakeJournalStore(journal(RollbackJournalStep.FAILED))
        val root = FakeRoot()
        val service = service(
            journalStore = journals,
            moduleState = ModuleState.Enabled("0.3.0", 3),
            keeper = FakeKeeper(null, 0),
            root = root,
            archives = FakeArchives(RollbackArchiveState.Present(other))
        )

        service.rollbackApp()

        val failed = service.moduleRollback.value as RollbackState.Failed
        assertEquals(RollbackFailure.NO_PREVIOUS_MODULE, failed.code)
        assertTrue(failed.detail!!.contains(archive.sha256))
        assertTrue(root.calls.isEmpty())
        assertEquals(archive.sha256, journals.journal?.targets?.moduleSha256)
    }

    @Test
    fun `the journal needs no kept apk that an app data clear removed`() = runTest {
        val root = FakeRoot()
        val privileged = RecordingPrivilegedInstaller()
        val service = service(
            journal = journal(RollbackJournalStep.UNKNOWN),
            moduleState = ModuleState.Enabled("0.3.0", 3),
            keeper = FakeKeeper(null, 0),
            root = root,
            privileged = privileged
        )

        service.rollbackApp()

        assertEquals(0, privileged.installs)
        assertEquals(listOf("prepare", "start"), root.calls)
    }

    @Test
    fun `a journaled rollback whose module is not applied stages it again before the hand-off`() = runTest {
        val root = FakeRoot()
        val service = service(
            journal = journal(RollbackJournalStep.MODULE_STAGED),
            // No reboot: the newer module is still what the phone runs.
            moduleState = ModuleState.Enabled("0.4.0", 4),
            keeper = FakeKeeper(null, 0),
            root = root
        )

        service.rollbackApp()

        assertEquals(listOf("prepare", "stage", "start"), root.calls)
    }

    @Test
    fun `a recorded result reconciles Started on refresh and leaves an actionable pending state`() = runTest {
        val journals = FakeJournalStore(journal(RollbackJournalStep.MODULE_STAGED))
        val service = service(
            journalStore = journals,
            moduleState = ModuleState.Enabled("0.4.0", 4),
            keeper = FakeKeeper(null, 0)
        )
        service.rollbackApp()
        assertEquals(RollbackState.Started("0.3.0"), service.moduleRollback.value)

        // The detached script records a failure while this process is still alive.
        journals.journal = journal(RollbackJournalStep.FAILED)
        service.check()

        assertEquals(RollbackState.Idle, service.moduleRollback.value)
        val pending = service.rollbackRecovery.value as RollbackRecovery.Pending
        assertEquals(RollbackPendingReason.REMOVAL_FAILED, pending.reason)
    }

    @Test
    fun `a script that never started is a failure naming the staged module`() = runTest {
        val service = service(
            journal = journal(RollbackJournalStep.MODULE_STAGED),
            moduleState = ModuleState.Enabled("0.4.0", 4),
            keeper = FakeKeeper(null, 0),
            root = FakeRoot(start = RootStepResult(false, "su: denied"))
        )

        service.rollbackApp()

        val failed = service.moduleRollback.value as RollbackState.Failed
        assertEquals(RollbackFailure.REMOVAL_NOT_STARTED, failed.code)
        assertEquals(listOf(RollbackStep.MODULE_STAGED), failed.applied)
    }

    private fun service(
        journal: RollbackJournal? = null,
        journalStore: FakeJournalStore = FakeJournalStore(journal),
        moduleState: ModuleState = ModuleState.Absent,
        keeper: PreviousApkKeeper = FakeKeeper(null, 0),
        root: FakeRoot = FakeRoot(),
        privileged: PrivilegedApkInstaller = RecordingPrivilegedInstaller(),
        mounted: MountedSystemApkVersion = MountedSystemApkVersion.Present(SYSTEM_APK, "0.4.0", 4),
        archives: ModuleArchivePublisher = FakeArchives(RollbackArchiveState.Present(archive)) {
            RollbackArchiveState.Present(archive)
        }
    ): DefaultUpdatesService {
        val daemon: DaemonApi = DefaultDaemonApi(DownAppApi())
        return DefaultUpdatesService(
            context = context,
            daemonApi = daemon,
            inventory = UpdateInventory { InstalledVersions(appVersion = "0.4.0", appVersionCode = 4, deviceSdk = 35) },
            downloader = ReleaseDownloader { _, _, _ -> error("the rollback never downloads") },
            verifier = ApkVerifier { _, _, _ -> ApkVerification.NotAnApk },
            installGateway = ApkInstallGateway { _, _ -> ApkInstallOutcome.Failed(InstallFailure.INSTALL_FAILED, null) },
            keeper = keeper,
            privilegedInstaller = privileged,
            moduleGateway = UnusedModuleGateway,
            previousModule = PreviousModuleCapture { CaptureResult.NothingToCapture },
            mountedApk = MountedSystemApk { mounted },
            archives = archives,
            journalStore = journalStore,
            rollbackRoot = root,
            rootState = RootModuleState { moduleState }
        )
    }

    private fun journal(step: RollbackJournalStep): RollbackJournal = RollbackJournal(
        createdAtMillis = 1L,
        step = step,
        targets = RollbackTargets(
            packageName = "com.phonxiety.bridge",
            moduleVersionName = "0.3.0",
            moduleVersionCode = 3,
            moduleSha256 = archive.sha256,
            fromAppVersionName = "0.4.0",
            fromAppVersionCode = 4
        )
    )

    /** The device link is down: the rollback card must work without it. */
    private class DownAppApi : AppApi {
        override suspend fun request(
            method: String,
            path: String,
            query: Map<String, String>,
            body: JsonObject?
        ): ApiResult = ApiResult.Failure(0, "link_down", "the device link is down")

        override suspend fun download(path: String, query: Map<String, String>, sink: OutputStream): ApiResult = ApiResult.Failure(0, "link_down", "the device link is down")
    }

    private class FakeJournalStore(var journal: RollbackJournal?) : RollbackJournalStore {
        override suspend fun readJournal(): RollbackJournal? = journal

        override suspend fun removeJournal(): Boolean {
            journal = null
            return true
        }
    }

    private class FakeArchives(
        private val state: RollbackArchiveState,
        private val generation: (String) -> RollbackArchiveState = { RollbackArchiveState.Missing }
    ) : ModuleArchivePublisher {
        override suspend fun publish(archive: File, metadata: File): Boolean = true

        override suspend fun read(): RollbackArchiveState = state

        override suspend fun readGeneration(sha256: String): RollbackArchiveState = generation(sha256)
    }

    private class FakeRoot(private val start: RootStepResult = RootStepResult(true)) : RollbackRootSteps {
        val calls = mutableListOf<String>()
        var journal: RollbackJournal? = null

        override suspend fun prepare(journal: RollbackJournal): RootStepResult {
            calls += "prepare"
            this.journal = journal
            return RootStepResult(true)
        }

        override suspend fun stagePreviousModule(archive: PublishedModuleArchive): RootStepResult {
            calls += "stage"
            return RootStepResult(true)
        }

        override suspend fun startRemovalScript(): RootStepResult {
            calls += "start"
            return start
        }
    }

    private class FakeKeeper(private val apk: File?, private val code: Long) : PreviousApkKeeper {
        override suspend fun keepCurrentApk(): File? = apk

        override fun keptApk(): File? = apk

        override fun keptVersionName(): String? = if (apk == null) null else "0.3.0"

        override fun keptVersionCode(): Long = code
    }

    private class RecordingPrivilegedInstaller : PrivilegedApkInstaller {
        var installs = 0

        override suspend fun installWithRoot(apk: File): ApkInstallOutcome {
            installs++
            return ApkInstallOutcome.Installed("0.3.0")
        }
    }

    private object UnusedModuleGateway : ModuleInstallGateway {
        override suspend fun buildZip(updateJsonUrl: String?): File = error("no module install in this test")

        override suspend fun install(zip: File): ProviderInstall = error("no module install in this test")

        override suspend fun reboot(): ProviderInstall = error("no module install in this test")
    }
}
