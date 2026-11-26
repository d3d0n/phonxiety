package com.phonxiety.bridge.data.updates

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The combined rollback on the phone: the previous module is staged through the existing provider
 * install path, then the removal is handed to the detached root script. Nothing here removes the
 * module, and nothing after the hand-off depends on this process staying alive.
 */
@Singleton
class AndroidRollbackRoot @Inject constructor(
    private val rootAccess: AndroidRootAccess,
    private val store: RootRollbackStore
) : RollbackRootSteps {
    override suspend fun stagePreviousModule(archive: PublishedModuleArchive): RootStepResult = when (val result = rootAccess.installModule(File(RollbackFiles.ARCHIVE_PATH))) {
        ProviderInstall.Installed -> RootStepResult(ok = true)
        is ProviderInstall.Failed -> RootStepResult(ok = false, detail = result.detail)
    }

    override suspend fun startRemovalScript(journal: RollbackJournal): RootStepResult = store.startScript(journal)
}
