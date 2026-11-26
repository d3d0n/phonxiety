package com.phonxiety.bridge.root

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * APatch 11282 needs a metamodule exactly as KernelSU does: `apd` has no overlay of its own and
 * mounts through `/data/adb/metamodule`. These tests walk the capability probe into the wizard's
 * own routing, so the two-boot flow is asserted end to end without a phone.
 */
class ApatchMetamoduleTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val apatch = apatchProvider("11282")
    private val detection = RootDetection(SuStatus.GRANTED, listOf(apatch), active = RootId.APATCH)

    @Test
    fun withoutAMetamoduleTheWizardPreparesOne() = runTest {
        val state = wizardState(capabilities(metamoduleSupported = true, environment = modules()))

        assertEquals(WizardState.ProviderPrep(apatch, PrepReason.METAMODULE_MISSING), state)
    }

    @Test
    fun aStagedMetamoduleLeadsToTheRebootStep() = runTest {
        val environment = modules().apply {
            dirs[RootCommand.MODULES_UPDATE_DIR] = listOf("meta-overlayfs")
            files["${RootCommand.MODULES_UPDATE_DIR}/meta-overlayfs/module.prop"] =
                "id=meta-overlayfs\nname=OverlayFS MetaModule\nmetamodule=1\nversion=1.3.1\n"
        }

        val state = wizardState(capabilities(metamoduleSupported = true, environment = environment))

        assertEquals(
            WizardState.RebootPending(apatch, RebootReason.METAMODULE_INSTALL, ModuleState.Absent),
            state
        )
    }

    @Test
    fun anActiveMetamoduleLeadsToTheModuleInstall() = runTest {
        val environment = modules().apply {
            dirs[RootCommand.MODULES_DIR] = listOf("meta-overlayfs")
            files["${RootCommand.MODULES_DIR}/meta-overlayfs/module.prop"] =
                "id=meta-overlayfs\nname=OverlayFS MetaModule\nmetamodule=1\nversion=1.3.1\n"
        }

        val state = wizardState(capabilities(metamoduleSupported = true, environment = environment))

        assertEquals(WizardState.ReadyToInstall(apatch, ModuleState.Absent), state)
    }

    @Test
    fun anApdWithoutTheLiteralStaysBuiltInAndReady() = runTest {
        val capabilities = capabilities(metamoduleSupported = false, environment = modules())

        assertEquals(Overlay.BUILT_IN, capabilities.systemOverlay)
        assertEquals(WizardState.ReadyToInstall(apatch, ModuleState.Absent), wizardState(capabilities))
    }

    @Test
    fun theMetamoduleInstallsThroughApd() = runTest {
        val zip = temporaryFolder.newFile("meta-overlayfs.zip").apply { writeText("zip") }
        val runner = RecordingRunner(probe(supported = true))
        val provider = ApatchProvider(apatch, RootShell(runner), modules())

        provider.installModule(zip)

        assertEquals(
            "apd module install ${RootCommand.STAGING_DIR}/${MODULE_ZIP_NAME}",
            runner.scripts.last()
        )
        assertTrue(runner.scripts.first().startsWith("cp -f "))
    }

    private suspend fun capabilities(metamoduleSupported: Boolean, environment: FakeEnvironment): RootCapabilities = ApatchProvider(apatch, RootShell(RecordingRunner(probe(metamoduleSupported))), environment).capabilities()

    private fun wizardState(capabilities: RootCapabilities): WizardState = ModuleController.reduce(
        WizardState.Checking,
        WizardEvent.ProbeCompleted(
            ProbeSnapshot(
                detection = detection,
                module = ModuleState.Absent,
                overlay = capabilities.systemOverlay,
                metamodulePending = capabilities.metamodulePending
            )
        )
    ).state

    private fun modules(): FakeEnvironment = FakeEnvironment().apply {
        dirs[RootCommand.MODULES_DIR] = emptyList()
    }

    /** The capability probe answers [supported]; every other command succeeds. */
    private fun probe(supported: Boolean): (String) -> ShellOutput = { script ->
        if (script == RootCommand.apdSupportsMetamodules().script) {
            ShellOutput(exitCode = if (supported) 0 else 1)
        } else {
            ShellOutput()
        }
    }
}
