package com.phonxiety.bridge.root

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Command composition, capability rules and reboot choice per provider. */
class ProviderCommandsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun zip(name: String = "module.zip"): File {
        val file = temporaryFolder.newFile(name)
        file.writeText("zip")
        return file
    }

    private fun shell(responder: (String) -> ShellOutput = { ShellOutput() }): Pair<RootShell, RecordingRunner> {
        val runner = RecordingRunner(responder)
        return RootShell(runner) to runner
    }

    @Test
    fun magiskInstallsThroughTheStagedZip() = runTest {
        val (rootShell, runner) = shell()
        val provider = MagiskProvider(magiskProvider(), rootShell, FakeEnvironment())
        val moduleZip = zip()

        val result = provider.installModule(moduleZip)

        assertTrue(result.isSuccess)
        val expectedZip = "${RootCommand.STAGING_DIR}/${MODULE_ZIP_NAME}"
        assertEquals(
            listOf(
                "cp -f ${moduleZip.absolutePath} $expectedZip",
                "magisk --install-module $expectedZip"
            ),
            runner.scripts
        )
    }

    @Test
    fun magiskLifecycleUsesFlagFiles() = runTest {
        val (rootShell, runner) = shell()
        val provider = MagiskProvider(magiskProvider(), rootShell, FakeEnvironment())

        provider.enableModule("phonxiety_bridge")
        provider.disableModule("phonxiety_bridge")
        provider.removeModule("phonxiety_bridge")

        assertEquals(
            listOf(
                "rm -f /data/adb/modules/phonxiety_bridge/disable",
                "touch /data/adb/modules/phonxiety_bridge/disable",
                "touch /data/adb/modules/phonxiety_bridge/remove"
            ),
            runner.scripts
        )
    }

    @Test
    fun kernelSuInstallsAndManagesThroughKsud() = runTest {
        val (rootShell, runner) = shell()
        val provider = KernelSuProvider(kernelSuProvider(), rootShell, FakeEnvironment())
        val moduleZip = zip()

        provider.installModule(moduleZip)
        provider.enableModule("phonxiety_bridge")
        provider.disableModule("phonxiety_bridge")
        provider.removeModule("phonxiety_bridge")

        val expectedZip = "${RootCommand.STAGING_DIR}/${MODULE_ZIP_NAME}"
        assertEquals(
            listOf(
                "cp -f ${moduleZip.absolutePath} $expectedZip",
                "ksud module install $expectedZip",
                "ksud module enable phonxiety_bridge",
                "ksud module disable phonxiety_bridge",
                "ksud module uninstall phonxiety_bridge"
            ),
            runner.scripts
        )
    }

    @Test
    fun kernelSuFallsBackToFlagFilesWhenTheCliFails() = runTest {
        val responses = ArrayDeque(
            listOf(
                ShellOutput(exitCode = 0), // cp
                ShellOutput(exitCode = 0), // ksud install
                ShellOutput(stderr = "unknown command", exitCode = 1), // ksud disable
                ShellOutput(exitCode = 0) // touch disable
            )
        )
        val runner = RecordingRunner { responses.removeFirstOrNull() ?: ShellOutput() }
        val provider = KernelSuProvider(
            kernelSuProvider(),
            RootShell(runner),
            FakeEnvironment()
        )

        provider.installModule(zip())
        val result = provider.disableModule("phonxiety_bridge")

        assertTrue(result.isSuccess)
        assertEquals("touch /data/adb/modules/phonxiety_bridge/disable", runner.scripts.last())
    }

    @Test
    fun apatchInstallsAndManagesThroughApd() = runTest {
        val (rootShell, runner) = shell()
        val provider = ApatchProvider(apatchProvider(), rootShell, FakeEnvironment())
        val moduleZip = zip()

        provider.installModule(moduleZip)
        provider.enableModule("phonxiety_bridge")
        provider.disableModule("phonxiety_bridge")
        provider.removeModule("phonxiety_bridge")

        val expectedZip = "${RootCommand.STAGING_DIR}/${MODULE_ZIP_NAME}"
        assertEquals(
            listOf(
                "cp -f ${moduleZip.absolutePath} $expectedZip",
                "apd module install $expectedZip",
                "apd module enable phonxiety_bridge",
                "apd module disable phonxiety_bridge",
                "apd module uninstall phonxiety_bridge"
            ),
            runner.scripts
        )
    }

    @Test
    fun missingZipFailsBeforeTouchingTheShell() = runTest {
        val (rootShell, runner) = shell()
        val provider = MagiskProvider(magiskProvider(), rootShell, FakeEnvironment())

        val result = provider.installModule(File(temporaryFolder.root, "absent.zip"))

        assertFalse(result.isSuccess)
        assertTrue(runner.scripts.isEmpty())
    }

    @Test
    fun kernelSuWithoutMetamoduleCannotOverlaySystem() = runTest {
        val environment = FakeEnvironment().apply {
            dirs[RootCommand.MODULES_DIR] = listOf("another_module")
            files["${RootCommand.MODULES_DIR}/another_module/module.prop"] = "id=another_module\n"
        }
        val provider = KernelSuProvider(kernelSuProvider(), shell().first, environment)

        val capabilities = provider.capabilities()

        assertEquals(Overlay.METAMODULE_MISSING, capabilities.systemOverlay)
        assertTrue(capabilities.hotInstall)
        assertTrue(capabilities.updateJson)
        assertTrue(capabilities.webUi)
        assertTrue(capabilities.actionButton)
        assertTrue(capabilities.coexistenceWarning != null)
    }

    @Test
    fun kernelSuMetamodulePresenceIsDetectedFromModuleProp() = runTest {
        val environment = FakeEnvironment().apply {
            dirs[RootCommand.MODULES_DIR] = listOf("meta-overlayfs", "phonxiety_bridge")
            files["${RootCommand.MODULES_DIR}/meta-overlayfs/module.prop"] =
                "id=meta-overlayfs\nname=meta-overlayfs\nmetamodule=1\n"
            files["${RootCommand.MODULES_DIR}/phonxiety_bridge/module.prop"] = "id=phonxiety_bridge\n"
        }
        val provider = KernelSuProvider(kernelSuProvider(), shell().first, environment)

        val capabilities = provider.capabilities()

        assertEquals(Overlay.METAMODULE_PRESENT, capabilities.systemOverlay)
        assertFalse(capabilities.metamodulePending)
    }

    @Test
    fun kernelSuStagedMetamoduleIsMissingAndPending() = runTest {
        val environment = stagedMetamoduleEnvironment()
        val provider = KernelSuProvider(kernelSuProvider(), shell().first, environment)

        val capabilities = provider.capabilities()

        // `ksud` has copied the module in and left its `update` marker: not mounted until a boot.
        assertEquals(Overlay.METAMODULE_MISSING, capabilities.systemOverlay)
        assertTrue(capabilities.metamodulePending)
    }

    @Test
    fun kernelSuNextStagedMetamoduleIsAlsoPending() = runTest {
        val provider = KernelSuProvider(
            kernelSuProvider(id = RootId.KERNELSU_NEXT),
            shell().first,
            stagedMetamoduleEnvironment()
        )

        assertEquals(Overlay.METAMODULE_MISSING, provider.capabilities().systemOverlay)
        assertTrue(provider.capabilities().metamodulePending)
    }

    @Test
    fun kernelSuUnreadableMetamoduleDirectoryIsNotTreatedAsPending() = runTest {
        val environment = FakeEnvironment().apply {
            dirs[RootCommand.MODULES_DIR] = listOf("meta-overlayfs")
            files["${RootCommand.MODULES_DIR}/meta-overlayfs/module.prop"] =
                "id=meta-overlayfs\nmetamodule=1\n"
        }

        assertFalse(metamoduleIsPending(environment, "meta-overlayfs"))
    }

    private fun stagedMetamoduleEnvironment(): FakeEnvironment = FakeEnvironment().apply {
        dirs[RootCommand.MODULES_DIR] = listOf("meta-overlayfs")
        dirs["${RootCommand.MODULES_DIR}/meta-overlayfs"] = listOf("module.prop", MODULE_UPDATE_MARKER)
        files["${RootCommand.MODULES_DIR}/meta-overlayfs/module.prop"] =
            "id=meta-overlayfs\nname=meta-overlayfs\nmetamodule=1\n"
    }

    @Test
    fun magiskAndApatchOverlayIsBuiltIn() = runTest {
        val environment = FakeEnvironment()
        val magisk = MagiskProvider(magiskProvider(), shell().first, environment)
        val apatch = ApatchProvider(apatchProvider(), shell().first, environment)

        assertEquals(Overlay.BUILT_IN, magisk.capabilities().systemOverlay)
        assertTrue(magisk.capabilities().updateJson)
        assertFalse(magisk.capabilities().webUi)
        assertEquals(Overlay.BUILT_IN, apatch.capabilities().systemOverlay)
        assertFalse(apatch.capabilities().updateJson)
        assertTrue(apatch.capabilities().webUi)
    }

    @Test
    fun rebootPrefersPowerManagerWhenThePermissionIsHeld() = runTest {
        val recorder = Rebooter(permitted = true)
        val (rootShell, runner) = shell()
        val provider = MagiskProvider(magiskProvider(), rootShell, FakeEnvironment(), recorder)

        val result = provider.reboot(RebootReason.MODULE_INSTALL)

        assertTrue(result.isSuccess)
        assertTrue(runner.scripts.isEmpty())
        assertEquals(listOf("module_update"), recorder.reasons)
    }

    @Test
    fun rebootFallsBackToSuWhenThePermissionIsMissing() = runTest {
        val recorder = Rebooter(permitted = false)
        val (rootShell, runner) = shell()
        val provider = ApatchProvider(apatchProvider(), rootShell, FakeEnvironment(), recorder)

        val result = provider.reboot(RebootReason.USER_REQUEST)

        assertTrue(result.isSuccess)
        assertEquals(listOf("reboot"), runner.scripts)
        assertTrue(recorder.reasons.isEmpty())
    }

    @Test
    fun moduleStateIsReadFromFlags() = runTest {
        val environment = FakeEnvironment().module("phonxiety_bridge", flags = setOf(ModuleFlag.DISABLE))
        val provider = MagiskProvider(magiskProvider(), shell().first, environment)

        val state = provider.moduleState("phonxiety_bridge")

        assertEquals(ModuleState.Disabled("0.3.0", 3), state)
    }

    @Test
    fun denialIsReportedInsteadOfFailure() = runTest {
        val (rootShell, _) = shell { ShellOutput(stderr = "Permission denied", exitCode = 1) }
        val provider = MagiskProvider(magiskProvider(), rootShell, FakeEnvironment())

        val result = provider.disableModule("phonxiety_bridge")

        assertTrue(result is ProviderResult.Denied)
    }

    private class Rebooter(private val permitted: Boolean) : SystemRebooter {
        val reasons = mutableListOf<String>()

        override fun hasRebootPermission(): Boolean = permitted

        override fun reboot(reason: String): Boolean {
            reasons += reason
            return true
        }
    }
}
