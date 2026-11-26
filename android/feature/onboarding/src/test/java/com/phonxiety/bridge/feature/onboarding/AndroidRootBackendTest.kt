package com.phonxiety.bridge.feature.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.InstallOperation
import com.phonxiety.bridge.root.ProcessRunner
import com.phonxiety.bridge.root.RootCommand
import com.phonxiety.bridge.root.RootEnvironment
import com.phonxiety.bridge.root.RootId
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The production backend's two install paths: a metamodule preparation stages exactly one module
 * (the pinned metamodule) and never builds the app zip, while a module install still does both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidRootBackendTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aMetamodulePreparationInstallsExactlyTheMetamodule() = runTest {
        val metamodule = temporaryFolder.newFile("meta-overlayfs-13100-1.3.1.zip").apply { writeText("zip") }
        val shell = RecordingShell()
        var zipBuilds = 0
        var metamoduleFetches = 0
        val backend = backend(
            shell = shell,
            zipBuilder = {
                zipBuilds++
                error("the app zip must not be built during a metamodule preparation")
            },
            metamoduleSource = {
                metamoduleFetches++
                MetamoduleResult.Ready(metamodule)
            }
        )

        val result = backend.install(KERNELSU, InstallOperation.PREPARE_METAMODULE)

        assertTrue(result.isSuccess)
        assertEquals(0, zipBuilds)
        assertEquals(1, metamoduleFetches)
        val staged = "${RootCommand.STAGING_DIR}/phonxiety_bridge.zip"
        assertEquals(
            listOf(
                "cp -f ${metamodule.absolutePath} $staged",
                "ksud module install $staged"
            ),
            shell.scripts
        )
        assertEquals(1, shell.scripts.count { it.startsWith("ksud module install ") })
    }

    @Test
    fun aFailedMetamoduleDownloadTouchesNothing() = runTest {
        val shell = RecordingShell()
        val backend = backend(
            shell = shell,
            metamoduleSource = { MetamoduleResult.Failed("meta-overlayfs SHA-256 mismatch") }
        )

        val result = backend.install(KERNELSU, InstallOperation.PREPARE_METAMODULE)

        assertFalse(result.isSuccess)
        assertTrue(shell.scripts.isEmpty())
    }

    @Test
    fun aModuleInstallStillBuildsTheAppZipAndInstallsIt() = runTest {
        val appZip = temporaryFolder.newFile("phonxiety_bridge.zip").apply { writeText("zip") }
        val shell = RecordingShell()
        var zipBuilds = 0
        var metamoduleFetches = 0
        val backend = backend(
            shell = shell,
            zipBuilder = {
                zipBuilds++
                appZip
            },
            metamoduleSource = {
                metamoduleFetches++
                error("the metamodule is only fetched for a preparation")
            }
        )

        val result = backend.install(KERNELSU, InstallOperation.INSTALL)

        assertTrue(result.isSuccess)
        assertEquals(1, zipBuilds)
        assertEquals(0, metamoduleFetches)
        assertEquals(1, shell.scripts.count { it.startsWith("ksud module install ") })
    }

    private fun backend(
        shell: RecordingShell,
        zipBuilder: suspend () -> File = { error("not expected") },
        metamoduleSource: suspend () -> MetamoduleResult = { error("not expected") }
    ): AndroidRootBackend = AndroidRootBackend(
        context = context,
        shell = RootShell(shell),
        environment = EmptyEnvironment,
        zipBuilder = zipBuilder,
        metamoduleSource = MetamoduleSource { metamoduleSource() }
    )

    /** Records the `su -c` scripts and answers with success. */
    private class RecordingShell : ProcessRunner {
        val commands = mutableListOf<List<String>>()

        val scripts: List<String> get() = commands.map { it[2] }

        override suspend fun run(command: List<String>, timeoutMs: Long): ShellOutput {
            commands += command
            return ShellOutput()
        }
    }

    private object EmptyEnvironment : RootEnvironment {
        override suspend fun listDir(path: String): List<String>? = null

        override suspend fun readFile(path: String): String? = null

        override fun installedPackages(): Set<String> = emptySet()
    }

    private companion object {
        val KERNELSU = DetectedProvider(
            id = RootId.KERNELSU,
            version = "3.1.0",
            managerPackage = "me.weishu.kernelsu",
            managerInstalled = true
        )
    }
}
