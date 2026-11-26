package com.phonxiety.bridge.data.updates

import com.phonxiety.bridge.root.ProcessRunner
import com.phonxiety.bridge.root.RootCommand
import com.phonxiety.bridge.root.RootShell
import com.phonxiety.bridge.root.ShellOutput
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The production staging boundary of a combined rollback: `/data/adb/phonxiety` is root-only, so
 * existence, the digest and the copy are all `su`'s answers. The fake shell below sees the archive
 * while this process's [File] cannot, which is exactly the phone's situation.
 */
class AndroidRollbackRootTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val archive = PublishedModuleArchive("0.3.0", 3, "a".repeat(64))
    private val generation = RootCommand.rollbackGenerationName(archive.sha256)!!
    private val archivePath = "/data/adb/phonxiety/$generation/module-previous.zip"

    @Test
    fun `the archive is hashed through root and installed by its root path`() = runTest {
        val runner = FakeRunner()
        val installer = RecordingInstaller()
        // The app process cannot stat anything under `/data/adb`.
        assertFalse(File(archivePath).isFile)

        val result = AndroidRollbackRoot(installer, store(runner)).stagePreviousModule(archive)

        assertTrue(result.ok)
        assertEquals(listOf(archivePath), installer.published)
        assertTrue(installer.appSide.isEmpty())
        assertTrue(runner.commands.contains("sha256sum $archivePath"))
    }

    @Test
    fun `bytes that no longer hash to the published identity never reach the installer`() = runTest {
        val installer = RecordingInstaller()
        val result = AndroidRollbackRoot(installer, store(FakeRunner(digest = "b".repeat(64))))
            .stagePreviousModule(archive)

        assertFalse(result.ok)
        assertTrue(result.detail!!.contains("b".repeat(64)))
        assertTrue(installer.published.isEmpty())
    }

    @Test
    fun `an archive the pointer does not name is explained, not installed`() = runTest {
        val installer = RecordingInstaller()
        val other = PublishedModuleArchive("0.3.0", 3, "c".repeat(64))
        val result = AndroidRollbackRoot(
            installer,
            store(FakeRunner(pointer = "g-${"c".repeat(64)}", identity = other))
        ).stagePreviousModule(archive)

        assertFalse(result.ok)
        assertTrue(result.detail!!.contains("not the checked one"))
        assertTrue(installer.published.isEmpty())
    }

    @Test
    fun `a generation root cannot read is explained before anything is installed`() = runTest {
        val installer = RecordingInstaller()
        val result = AndroidRollbackRoot(installer, store(FakeRunner(metadata = null)))
            .stagePreviousModule(archive)

        assertFalse(result.ok)
        assertTrue(result.detail!!.contains("/data/adb/phonxiety"))
        assertTrue(installer.published.isEmpty())
    }

    private fun store(runner: FakeRunner): RootRollbackStore = RootRollbackStore(RootShell(runner), File(temporary.root, "workspace"))

    private class RecordingInstaller : RootModuleInstaller {
        val published = mutableListOf<String>()
        val appSide = mutableListOf<File>()

        override suspend fun installModule(zip: File): ProviderInstall {
            appSide += zip
            return ProviderInstall.Installed
        }

        override suspend fun installPublishedModule(sourcePath: String): ProviderInstall {
            published += sourcePath
            return ProviderInstall.Installed
        }
    }

    private class FakeRunner(
        private val pointer: String = "g-${"a".repeat(64)}",
        private val identity: PublishedModuleArchive = PublishedModuleArchive("0.3.0", 3, "a".repeat(64)),
        private val digest: String = "a".repeat(64),
        private val metadata: String? = ModuleArchiveMeta.write(identity)
    ) : ProcessRunner {
        val commands = mutableListOf<String>()

        override suspend fun run(command: List<String>, timeoutMs: Long): ShellOutput {
            val script = command.last()
            commands += script
            return when {
                script.endsWith("/${RollbackFiles.POINTER}") -> ShellOutput(stdout = "$pointer\n")

                script.endsWith("/${RollbackFiles.ARCHIVE_META}") ->
                    metadata?.let { ShellOutput(stdout = it) } ?: ShellOutput(exitCode = 1)

                script.startsWith("sha256sum") -> ShellOutput(stdout = "$digest  /data/adb/phonxiety/module-previous.zip\n")

                else -> ShellOutput()
            }
        }
    }
}
