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

    /**
     * The published rollback archive lives under root-only `/data/adb`, where this process cannot
     * even stat it. The staging must therefore ask `su`: a `cp` it can perform is the existence
     * check, and this file — invisible here — must still install.
     */
    @Test
    fun aZipOnlyRootCanSeeIsStagedThroughSu() = runTest {
        val generation = RootCommand.rollbackGenerationName("a".repeat(64))!!
        val archive = "/data/adb/phonxiety/$generation/module-previous.zip"
        assertFalse(File(archive).isFile)

        val expectedZip = "${RootCommand.STAGING_DIR}/${MODULE_ZIP_NAME}"
        val factories: List<Pair<(RootShell) -> RootProvider, String>> = listOf(
            { rootShell: RootShell -> MagiskProvider(magiskProvider(), rootShell, FakeEnvironment()) } to
                "magisk --install-module $expectedZip",
            { rootShell: RootShell -> KernelSuProvider(kernelSuProvider(), rootShell, FakeEnvironment()) } to
                "ksud module install $expectedZip",
            { rootShell: RootShell -> ApatchProvider(apatchProvider(), rootShell, FakeEnvironment()) } to
                "apd module install $expectedZip"
        )
        for ((factory, installCommand) in factories) {
            val (rootShell, runner) = shell()

            val result = factory(rootShell).installPublishedModule(archive)

            assertTrue(result.isSuccess)
            assertEquals(listOf("cp -f $archive $expectedZip", installCommand), runner.scripts)
        }
    }

    @Test
    fun aRootCopyThatCannotFindTheArchiveReportsWhatSuSaid() = runTest {
        val archive = "/data/adb/phonxiety/g-${"b".repeat(64)}/module-previous.zip"
        val (rootShell, runner) = shell { script ->
            if (script.startsWith("cp -f")) {
                ShellOutput(stderr = "cp: $archive: No such file or directory", exitCode = 1)
            } else {
                ShellOutput()
            }
        }

        val result = MagiskProvider(magiskProvider(), rootShell, FakeEnvironment()).installPublishedModule(archive)

        assertFalse(result.isSuccess)
        assertTrue(result.tail().joinToString(" ").contains("No such file"))
        assertEquals(1, runner.scripts.size)
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
        // KernelSU v3.3.1 `module.rs` stages a module with this literal marker file.
        dirs["${RootCommand.MODULES_DIR}/meta-overlayfs"] = listOf("module.prop", "update")
        files["${RootCommand.MODULES_DIR}/meta-overlayfs/module.prop"] =
            "id=meta-overlayfs\nname=meta-overlayfs\nmetamodule=1\n"
    }

    @Test
    fun magiskOverlayIsBuiltIn() = runTest {
        val environment = FakeEnvironment()
        val magisk = MagiskProvider(magiskProvider(), shell().first, environment)

        assertEquals(Overlay.BUILT_IN, magisk.capabilities().systemOverlay)
        assertTrue(magisk.capabilities().updateJson)
        assertFalse(magisk.capabilities().webUi)
    }

    @Test
    fun anApdWithoutMetamoduleSupportKeepsTheBuiltInOverlay() = runTest {
        val environment = FakeEnvironment()
        val apatch = ApatchProvider(apatchProvider(), shell(apdProbe(supported = false)).first, environment)

        val capabilities = apatch.capabilities()

        assertEquals(Overlay.BUILT_IN, capabilities.systemOverlay)
        assertFalse(capabilities.metamodulePending)
        assertFalse(capabilities.updateJson)
        assertTrue(capabilities.webUi)
    }

    @Test
    fun apatchWithoutAMetamoduleCannotOverlaySystem() = runTest {
        val environment = FakeEnvironment().apply { dirs[RootCommand.MODULES_DIR] = listOf("phonxiety_bridge") }
        val apatch = ApatchProvider(apatchProvider(), shell(apdProbe(supported = true)).first, environment)

        val capabilities = apatch.capabilities()

        // apd 11282 has no overlay of its own: without a metamodule an enabled module is unstyled.
        assertEquals(Overlay.METAMODULE_MISSING, capabilities.systemOverlay)
        assertFalse(capabilities.metamodulePending)
    }

    @Test
    fun apatchStagedMetamoduleIsMissingAndPending() = runTest {
        // `apd module install` extracts into modules_update/<id> and creates the symlink over the
        // still empty modules/<id>: the staged copy is the pending signal, not the `update` marker.
        val environment = FakeEnvironment().apply {
            dirs[RootCommand.MODULES_DIR] = listOf("meta-overlayfs")
            dirs["${RootCommand.MODULES_DIR}/meta-overlayfs"] = emptyList()
            dirs[RootCommand.MODULES_UPDATE_DIR] = listOf("meta-overlayfs")
            files["${RootCommand.MODULES_UPDATE_DIR}/meta-overlayfs/module.prop"] =
                "id=meta-overlayfs\nname=OverlayFS MetaModule\nmetamodule=1\nversion=1.3.1\n"
        }
        val apatch = ApatchProvider(apatchProvider(), shell(apdProbe(supported = true)).first, environment)

        val capabilities = apatch.capabilities()

        assertEquals(Overlay.METAMODULE_MISSING, capabilities.systemOverlay)
        assertTrue(capabilities.metamodulePending)
    }

    @Test
    fun apatchActiveMetamoduleMountsSystem() = runTest {
        val environment = FakeEnvironment().apply {
            dirs[RootCommand.MODULES_DIR] = listOf("meta-overlayfs")
            files["${RootCommand.MODULES_DIR}/meta-overlayfs/module.prop"] =
                "id=meta-overlayfs\nname=OverlayFS MetaModule\nmetamodule=1\nversion=1.3.1\n"
        }
        val apatch = ApatchProvider(apatchProvider("11282"), shell(apdProbe(supported = true)).first, environment)

        val capabilities = apatch.capabilities()

        assertEquals(Overlay.METAMODULE_PRESENT, capabilities.systemOverlay)
        assertFalse(capabilities.metamodulePending)
    }

    /** Answers the capability probe and everything else with success. */
    private fun apdProbe(supported: Boolean): (String) -> ShellOutput = { script ->
        if (script == RootCommand.apdSupportsMetamodules().script) {
            ShellOutput(exitCode = if (supported) 0 else 1)
        } else {
            ShellOutput()
        }
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
        // PowerManager over binder, with the plain binary as the fallback: a shell-context
        // `reboot` alone is refused with PROP_ERROR_PERMISSION_DENIED on Android 17.
        assertEquals(
            listOf("/system/bin/svc power reboot userrequested || /system/bin/reboot userrequested"),
            runner.scripts
        )
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
