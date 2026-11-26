package com.phonxiety.bridge.data.updates

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rollback decision and the combined sequence: which plan a phone gets, that the module is
 * staged before the hand-off, and that every failure reports what was already applied.
 */
class RollbackTest {
    private val archive = PublishedModuleArchive("0.3.0", 3, "a".repeat(64))
    private val running = RunningApp("com.phonxiety.bridge", "0.4.0", 4)

    @Test
    fun `a mounted system image newer than the kept apk needs the previous module`() {
        val plan = RollbackPlanner.plan(
            facts(
                mounted = MountedSystemApkVersion.Present("/system/priv-app/x.apk", "0.4.0", 4),
                archive = RollbackArchiveState.Present(archive)
            )
        )

        assertEquals(
            RollbackPlan.WithPreviousModule("0.4.0", 4, "0.3.0", 3, archive.sha256),
            plan
        )
    }

    @Test
    fun `the mounted system image at or below the kept apk keeps the app only rollback`() {
        val equal = RollbackPlanner.plan(
            facts(mounted = MountedSystemApkVersion.Present("/system/priv-app/x.apk", "0.3.0", 3))
        )
        val older = RollbackPlanner.plan(
            facts(mounted = MountedSystemApkVersion.Present("/system/priv-app/x.apk", "0.2.0", 2))
        )
        val absent = RollbackPlanner.plan(facts(mounted = MountedSystemApkVersion.Absent))

        assertEquals(RollbackPlan.AppOnly("0.3.0", 3), equal)
        assertEquals(RollbackPlan.AppOnly("0.3.0", 3), older)
        assertEquals(RollbackPlan.AppOnly("0.3.0", 3), absent)
    }

    @Test
    fun `a newer system image without a kept module is explained, not offered`() {
        val plan = RollbackPlanner.plan(
            facts(
                mounted = MountedSystemApkVersion.Present("/system/priv-app/x.apk", "0.4.0", 4),
                archive = RollbackArchiveState.Missing
            )
        )

        val unavailable = plan as RollbackPlan.Unavailable
        assertEquals(RollbackBlocker.NO_PREVIOUS_MODULE, unavailable.blocker)
        assertEquals(4L, unavailable.moduleVersionCode)
        assertEquals("0.3.0", unavailable.keptVersionName)
    }

    @Test
    fun `unreadable probes are explained instead of guessed`() {
        val unreadableSystem = RollbackPlanner.plan(facts(mounted = MountedSystemApkVersion.Unreadable))
        val unreadableStorage = RollbackPlanner.plan(
            facts(
                mounted = MountedSystemApkVersion.Present("/system/priv-app/x.apk", "0.4.0", 4),
                archive = RollbackArchiveState.Unreadable
            )
        )
        val stale = RollbackPlanner.plan(
            facts(
                mounted = MountedSystemApkVersion.Present("/system/priv-app/x.apk", "0.4.0", 4),
                archive = RollbackArchiveState.Present(PublishedModuleArchive("0.4.0", 4, "b".repeat(64)))
            )
        )
        val mismatched = RollbackPlanner.plan(
            facts(
                mounted = MountedSystemApkVersion.Present("/system/priv-app/x.apk", "0.4.0", 4),
                archive = RollbackArchiveState.Mismatch("b".repeat(64), "c".repeat(64))
            )
        )

        assertEquals(
            RollbackBlocker.SYSTEM_VERSION_UNKNOWN,
            (unreadableSystem as RollbackPlan.Unavailable).blocker
        )
        assertEquals(
            RollbackBlocker.STORAGE_UNREADABLE,
            (unreadableStorage as RollbackPlan.Unavailable).blocker
        )
        assertEquals(RollbackBlocker.STALE_ARCHIVE, (stale as RollbackPlan.Unavailable).blocker)
        assertEquals(RollbackBlocker.ARCHIVE_MISMATCH, (mismatched as RollbackPlan.Unavailable).blocker)
    }

    @Test
    fun `without a kept apk nothing is offered`() {
        val plan = RollbackPlanner.plan(facts(keptApk = false))

        assertEquals(RollbackBlocker.NO_KEPT_APK, (plan as RollbackPlan.Unavailable).blocker)
    }

    @Test
    fun `the combined rollback prepares locally, stages the module, then hands the removal off`() = runTest {
        val publisher = FakePublisher(RollbackArchiveState.Present(archive))
        val root = FakeRoot()
        val states = mutableListOf<RollbackState>()

        val result = ModuleRollbackCoordinator(publisher, root, running, clock = { 7L }) { states += it }
            .rollback(expectedPreviousVersionCode = 3)

        assertEquals(RollbackState.Started("0.3.0"), result)
        assertEquals(listOf("prepare", "stage", "start"), root.calls)
        assertEquals(
            listOf(RollbackState.Preparing, RollbackState.StagingModule, RollbackState.StartingRemoval),
            states.take(3)
        )
        val journal = root.journal!!
        assertEquals(RollbackJournalStep.MODULE_STAGED, journal.step)
        assertEquals(7L, journal.createdAtMillis)
        assertEquals(
            RollbackTargets("com.phonxiety.bridge", "0.3.0", 3, archive.sha256, "0.4.0", 4),
            journal.targets
        )
    }

    @Test
    fun `a retry of an already staged archive only repeats the hand-off`() = runTest {
        val root = FakeRoot()
        val result = ModuleRollbackCoordinator(
            FakePublisher(RollbackArchiveState.Present(archive)),
            root,
            running,
            stagedArchiveSha256 = archive.sha256
        ) {}.rollback(expectedPreviousVersionCode = 3)

        assertEquals(RollbackState.Started("0.3.0"), result)
        assertEquals(listOf("prepare", "start"), root.calls)
    }

    @Test
    fun `recovery resolves the archive by the journal's digest, not by the pointer`() = runTest {
        val other = PublishedModuleArchive("0.3.0", 3, "b".repeat(64))
        val root = FakeRoot()
        val result = ModuleRollbackCoordinator(
            FakePublisher(
                state = RollbackArchiveState.Present(other),
                generations = mapOf(archive.sha256 to RollbackArchiveState.Present(archive))
            ),
            root,
            running,
            stagedArchiveSha256 = archive.sha256,
            recoverySha256 = archive.sha256
        ) {}.rollback(expectedPreviousVersionCode = 3)

        assertEquals(RollbackState.Started("0.3.0"), result)
        assertEquals(listOf("prepare", "start"), root.calls)
        assertEquals(archive.sha256, root.journal?.targets?.moduleSha256)
    }

    @Test
    fun `recovery refuses a same-version archive whose digest is not the recorded one`() = runTest {
        val other = PublishedModuleArchive("0.3.0", 3, "b".repeat(64))
        val root = FakeRoot()
        val result = ModuleRollbackCoordinator(
            // The pointer names a valid archive of the same version; the recorded generation is gone.
            FakePublisher(
                state = RollbackArchiveState.Present(other),
                generations = mapOf(archive.sha256 to RollbackArchiveState.Mismatch(archive.sha256, other.sha256))
            ),
            root,
            running,
            stagedArchiveSha256 = null,
            recoverySha256 = archive.sha256
        ) {}.rollback(expectedPreviousVersionCode = 3)

        val failure = result as RollbackState.Failed
        assertEquals(RollbackFailure.ARCHIVE_MISMATCH, failure.code)
        assertTrue(failure.detail!!.contains(archive.sha256))
        assertTrue(failure.detail!!.contains(other.sha256))
        assertTrue(root.calls.isEmpty())
        // The journal's recorded target is never rewritten to the substitute archive.
        assertTrue(root.journal == null)
    }

    @Test
    fun `recovery never substitutes a pointer archive for a missing recorded generation`() = runTest {
        val other = PublishedModuleArchive("0.3.0", 3, "b".repeat(64))
        val root = FakeRoot()
        val result = ModuleRollbackCoordinator(
            FakePublisher(state = RollbackArchiveState.Present(other)),
            root,
            running,
            recoverySha256 = archive.sha256
        ) {}.rollback(expectedPreviousVersionCode = 3)

        val failure = result as RollbackState.Failed
        assertEquals(RollbackFailure.NO_PREVIOUS_MODULE, failure.code)
        assertTrue(failure.detail!!.contains(archive.sha256))
        assertTrue(root.calls.isEmpty())
    }

    @Test
    fun `a file that cannot be written stops before anything is staged`() = runTest {
        val root = FakeRoot(prepare = RootStepResult(false, "no space left on device"))
        val result = ModuleRollbackCoordinator(
            FakePublisher(RollbackArchiveState.Present(archive)),
            root,
            running
        ) {}.rollback(expectedPreviousVersionCode = 3)

        val failure = result as RollbackState.Failed
        assertEquals(RollbackFailure.PREPARATION_FAILED, failure.code)
        assertEquals("no space left on device", failure.detail)
        assertTrue(failure.applied.isEmpty())
        assertEquals(listOf("prepare"), root.calls)
    }

    @Test
    fun `an archive whose bytes do not match its identity is refused before root runs`() = runTest {
        val root = FakeRoot()
        val result = ModuleRollbackCoordinator(
            FakePublisher(RollbackArchiveState.Mismatch(archive.sha256, "b".repeat(64))),
            root,
            running
        ) {}.rollback(expectedPreviousVersionCode = 3)

        assertEquals(RollbackFailure.ARCHIVE_MISMATCH, (result as RollbackState.Failed).code)
        assertTrue(root.calls.isEmpty())
    }

    @Test
    fun `an archive that is not the planned one is refused before root runs`() = runTest {
        val root = FakeRoot()
        val result = ModuleRollbackCoordinator(
            FakePublisher(RollbackArchiveState.Present(archive)),
            root,
            running
        ) {}.rollback(expectedPreviousVersionCode = 2)

        val failure = result as RollbackState.Failed
        assertEquals(RollbackFailure.ARCHIVE_MISMATCH, failure.code)
        assertTrue(failure.applied.isEmpty())
        assertTrue(root.calls.isEmpty())
    }

    @Test
    fun `a missing archive is refused before root runs`() = runTest {
        val root = FakeRoot()
        val result = ModuleRollbackCoordinator(
            FakePublisher(RollbackArchiveState.Missing),
            root,
            running
        ) {}.rollback(expectedPreviousVersionCode = 3)

        assertEquals(RollbackFailure.NO_PREVIOUS_MODULE, (result as RollbackState.Failed).code)
        assertTrue(root.calls.isEmpty())
    }

    @Test
    fun `a refused module stage changes nothing and never starts the script`() = runTest {
        val root = FakeRoot(stage = RootStepResult(false, "magisk: refused"))
        val result = ModuleRollbackCoordinator(
            FakePublisher(RollbackArchiveState.Present(archive)),
            root,
            running
        ) {}.rollback(expectedPreviousVersionCode = 3)

        val failure = result as RollbackState.Failed
        assertEquals(RollbackFailure.MODULE_STAGE_FAILED, failure.code)
        assertEquals("magisk: refused", failure.detail)
        assertTrue(failure.applied.isEmpty())
        assertEquals(listOf("prepare", "stage"), root.calls)
    }

    @Test
    fun `a script that does not start reports the staged module`() = runTest {
        val root = FakeRoot(start = RootStepResult(false, "su: denied"))
        val result = ModuleRollbackCoordinator(
            FakePublisher(RollbackArchiveState.Present(archive)),
            root,
            running
        ) {}.rollback(expectedPreviousVersionCode = 3)

        val failure = result as RollbackState.Failed
        assertEquals(RollbackFailure.REMOVAL_NOT_STARTED, failure.code)
        assertEquals("su: denied", failure.detail)
        assertEquals(listOf(RollbackStep.MODULE_STAGED), failure.applied)
    }

    @Test
    fun `the journal round trips and a corrupt one is not trusted`() {
        val journal = journal(RollbackJournalStep.REMOVED)

        assertEquals(journal, RollbackJournalCodec.parse(RollbackJournalCodec.write(journal)))
        assertEquals(null, RollbackJournalCodec.parse("{\"step\":\"nonsense\"}"))
        assertEquals(null, RollbackJournalCodec.parse("not json"))
        assertEquals(null, RollbackJournalCodec.parse(null))
    }

    @Test
    fun `an unfinished script run is pending recovery`() {
        assertEquals(
            RollbackPendingReason.REMOVAL_NOT_STARTED,
            (verify(journal(RollbackJournalStep.MODULE_STAGED)) as RollbackRecovery.Pending).reason
        )
        assertEquals(
            RollbackPendingReason.REMOVAL_FAILED,
            (verify(journal(RollbackJournalStep.FAILED)) as RollbackRecovery.Pending).reason
        )
        assertEquals(
            RollbackPendingReason.REMOVAL_UNVERIFIED,
            (verify(journal(RollbackJournalStep.UNKNOWN)) as RollbackRecovery.Pending).reason
        )
    }

    @Test
    fun `completion needs the selected system apk and the mounted module at the target`() {
        assertEquals(
            RollbackPendingReason.OLD_APP_NOT_SELECTED,
            (
                verify(
                    journal(RollbackJournalStep.REMOVED),
                    selectedPath = "/data/app/~~abc==/com.phonxiety.bridge/base.apk",
                    runningCode = 3,
                    moduleCode = 3
                ) as RollbackRecovery.Pending
                ).reason
        )
        assertEquals(
            RollbackPendingReason.VERSION_MISMATCH,
            (
                verify(journal(RollbackJournalStep.REMOVED), runningCode = 4, moduleCode = 3)
                    as RollbackRecovery.Pending
                ).reason
        )
        assertEquals(
            RollbackPendingReason.VERSION_MISMATCH,
            (
                verify(journal(RollbackJournalStep.REMOVED), runningCode = 3, moduleCode = 4)
                    as RollbackRecovery.Pending
                ).reason
        )
        assertEquals(
            RollbackRecovery.Completed("0.3.0"),
            verify(journal(RollbackJournalStep.ALREADY_ABSENT), runningCode = 3, moduleCode = 3)
        )
    }

    @Test
    fun `a recorded removal without the reboot is pending, never complete`() {
        // The data APK is gone, but the old module is still mounted: the running app is 4, not 3.
        assertEquals(
            RollbackPendingReason.VERSION_MISMATCH,
            (verify(journal(RollbackJournalStep.REMOVED), runningCode = 4, moduleCode = 4) as RollbackRecovery.Pending).reason
        )
    }

    @Test
    fun `a staged module is not an applied one`() {
        // A module in modules_update has the target code but has not mounted; that is not completion.
        assertEquals(
            RollbackPendingReason.VERSION_MISMATCH,
            (verify(journal(RollbackJournalStep.REMOVED), runningCode = 3, moduleCode = null) as RollbackRecovery.Pending).reason
        )
    }

    private fun verify(
        journal: RollbackJournal,
        selectedPath: String = "/system/priv-app/phonxiety-bridge/phonxiety-bridge.apk",
        runningCode: Long = 3L,
        moduleCode: Long? = 3L
    ): RollbackRecovery = RollbackVerification.verify(journal, selectedPath, runningCode, moduleCode)

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

    private fun facts(
        keptApk: Boolean = true,
        mounted: MountedSystemApkVersion = MountedSystemApkVersion.Present("/system/priv-app/x.apk", "0.4.0", 4),
        archive: RollbackArchiveState = RollbackArchiveState.Missing
    ) = RollbackFacts(
        keptApkExists = keptApk,
        keptVersionName = "0.3.0",
        keptVersionCode = 3,
        mounted = mounted,
        archive = archive
    )

    private class FakePublisher(
        private val state: RollbackArchiveState,
        private val generations: Map<String, RollbackArchiveState> = emptyMap()
    ) : ModuleArchivePublisher {
        override suspend fun publish(archive: java.io.File, metadata: java.io.File): Boolean = true

        override suspend fun read(): RollbackArchiveState = state

        override suspend fun readGeneration(sha256: String): RollbackArchiveState = generations[sha256] ?: RollbackArchiveState.Missing
    }

    private class FakeRoot(
        private val prepare: RootStepResult = RootStepResult(ok = true),
        private val stage: RootStepResult = RootStepResult(ok = true),
        private val start: RootStepResult = RootStepResult(ok = true)
    ) : RollbackRootSteps {
        val calls = mutableListOf<String>()
        var journal: RollbackJournal? = null

        override suspend fun prepare(journal: RollbackJournal): RootStepResult {
            calls += "prepare"
            this.journal = journal
            return prepare
        }

        override suspend fun stagePreviousModule(archive: PublishedModuleArchive): RootStepResult {
            calls += "stage"
            return stage
        }

        override suspend fun startRemovalScript(): RootStepResult {
            calls += "start"
            return start
        }
    }
}
