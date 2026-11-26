package com.phonxiety.bridge.data.updates

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The combined rollback on the phone: the previous module is verified and staged through root, then
 * the removal is handed to the detached root script. Nothing here removes the module, and nothing
 * after the hand-off depends on this process staying alive.
 *
 * The archive lives under root-only `/data/adb/phonxiety`, so every question about it — does the
 * generation exist, is it the digest its metadata names, can it be copied — is asked through `su`.
 */
@Singleton
class AndroidRollbackRoot @Inject constructor(
    private val rootAccess: RootModuleInstaller,
    private val store: RootRollbackStore
) : RollbackRootSteps {
    override suspend fun prepare(journal: RollbackJournal): RootStepResult = store.prepareScript(journal)

    override suspend fun stagePreviousModule(archive: PublishedModuleArchive): RootStepResult {
        val published = when (val lookup = store.publishedArchive()) {
            is PublishedArchiveLookup.Found -> lookup.file

            PublishedArchiveLookup.Missing ->
                return RootStepResult(false, "no previous module archive is published under ${RollbackFiles.DIRECTORY}")

            PublishedArchiveLookup.Unreadable ->
                return RootStepResult(false, "${RollbackFiles.DIRECTORY} could not be read through root")
        }
        if (!published.archive.sha256.equals(archive.sha256, ignoreCase = true)) {
            return RootStepResult(
                false,
                "the published archive now carries ${published.archive.versionName ?: published.archive.versionCode}, " +
                    "not the checked one"
            )
        }
        // The digest of the exact bytes root would copy: the app cannot read this file itself.
        val digest = store.digestOf(published.generation)
            ?: return RootStepResult(false, "root could not hash ${published.path}")
        if (!digest.equals(archive.sha256, ignoreCase = true)) {
            return RootStepResult(false, "${published.path} hashes to $digest, its metadata names ${archive.sha256}")
        }
        return when (val result = rootAccess.installPublishedModule(published.path)) {
            ProviderInstall.Installed -> RootStepResult(ok = true)
            is ProviderInstall.Failed -> RootStepResult(ok = false, detail = result.detail)
        }
    }

    override suspend fun startRemovalScript(): RootStepResult = store.startScript()
}
