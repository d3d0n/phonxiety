package com.phonxiety.bridge.root

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val state = wizardState(capabilities(support = supported(), environment = modules()))

        assertEquals(WizardState.ProviderPrep(apatch, PrepReason.METAMODULE_MISSING), state)
    }

    @Test
    fun aStagedMetamoduleLeadsToTheRebootStep() = runTest {
        val state = wizardState(capabilities(support = supported(), environment = stagedMetamodule(completed = true)))

        assertEquals(
            WizardState.RebootPending(apatch, RebootReason.METAMODULE_INSTALL, ModuleState.Absent),
            state
        )
    }

    @Test
    fun aPartialMetamoduleExtractionIsRetriedNotRebooted() = runTest {
        // The zip was unpacked into modules_update/<id> and apd was killed before installer.sh wrote
        // /data/adb/modules/<id>/update and module.prop. A reboot would promote that partial tree,
        // so the wizard must offer the preparation again.
        val state = wizardState(capabilities(support = supported(), environment = stagedMetamodule(completed = false)))

        assertTrue("expected an interrupted preparation, got $state", state is WizardState.InstallFailed)
        val failed = state as WizardState.InstallFailed
        assertEquals(InstallOperation.PREPARE_METAMODULE, failed.operation)
        assertTrue(failed.result.tail().joinToString(" ").contains("stopped before it finished"))
        assertEquals(
            WizardState.ProviderPrep(apatch, PrepReason.METAMODULE_MISSING),
            ModuleController.reduce(state, WizardEvent.RetryRequested).state
        )
    }

    @Test
    fun anActiveMetamoduleLeadsToTheModuleInstall() = runTest {
        val environment = modules().apply {
            dirs[RootCommand.MODULES_DIR] = listOf("meta-overlayfs")
            files["${RootCommand.MODULES_DIR}/meta-overlayfs/module.prop"] =
                "id=meta-overlayfs\nname=OverlayFS MetaModule\nmetamodule=1\nversion=1.3.1\n"
        }

        val state = wizardState(capabilities(support = supported(), environment = environment))

        assertEquals(WizardState.ReadyToInstall(apatch, ModuleState.Absent), state)
    }

    @Test
    fun anApdWithoutTheLiteralStaysBuiltInAndReady() = runTest {
        val capabilities = capabilities(support = ShellOutput(exitCode = 1), environment = modules())

        assertEquals(Overlay.BUILT_IN, capabilities.systemOverlay)
        assertNull(capabilities.overlayProbeError)
        assertEquals(WizardState.ReadyToInstall(apatch, ModuleState.Absent), wizardState(capabilities))
    }

    @Test
    fun aStagedMetamoduleWithoutItsCompletionMarkerIsInterrupted() = runTest {
        val capabilities = capabilities(support = supported(), environment = stagedMetamodule(completed = false))

        assertFalse(capabilities.metamodulePending)
        assertTrue(capabilities.metamoduleInterrupted)
    }

    @Test
    fun anUnansweredProbeBlocksTheInstallInsteadOfAssumingBuiltIn() = runTest {
        // Only grep's completed 0 and 1 answer the question; these four never did.
        val unanswered = listOf(
            "exit 2" to ShellOutput(stderr = "grep: /data/adb/apd: Permission denied", exitCode = 2),
            "exit 127" to ShellOutput(stderr = "grep: not found", exitCode = 127),
            "launch failure" to ShellOutput(
                stderr = "su is not available",
                exitCode = ProcessBuilderProcessRunner.EXIT_NOT_STARTED,
                launchFailed = true
            ),
            "timeout" to ShellOutput(timedOut = true, timeoutMs = RootShell.DEFAULT_TIMEOUT_MS)
        )
        unanswered.forEach { (label, output) ->
            val capabilities = capabilities(support = output, environment = modules())
            assertNotEquals(label, Overlay.BUILT_IN, capabilities.systemOverlay)
            assertNotNull("$label must carry the probe's failure", capabilities.overlayProbeError)

            val state = wizardState(capabilities)
            assertTrue("$label must block the install, got $state", state is WizardState.Indeterminate)
            assertEquals(
                "$label must explain itself",
                IndeterminateReason.OVERLAY_PROBE_FAILED,
                (state as WizardState.Indeterminate).reason
            )

            // Retryable: Recheck runs the probe again instead of leaving the wizard stuck.
            val rechecked = ModuleController.reduce(state, WizardEvent.RecheckRequested)
            assertEquals(WizardState.Checking, rechecked.state)
            assertEquals(listOf(WizardEffect.RunProbe), rechecked.effects)
        }
    }

    @Test
    fun theProbeFailureKeepsWhatSuPrinted() = runTest {
        val capabilities = capabilities(
            support = ShellOutput(stderr = "grep: /data/adb/apd: Permission denied", exitCode = 2),
            environment = modules()
        )

        assertTrue(requireNotNull(capabilities.overlayProbeError).contains("exited 2"))
        assertTrue(requireNotNull(capabilities.overlayProbeError).contains("Permission denied"))
    }

    @Test
    fun theMetamoduleInstallsThroughApd() = runTest {
        val zip = temporaryFolder.newFile("meta-overlayfs.zip").apply { writeText("zip") }
        val runner = RecordingRunner(responder(supported()))
        val provider = ApatchProvider(apatch, RootShell(runner), modules())

        provider.installModule(zip)

        assertEquals(
            "apd module install ${RootCommand.STAGING_DIR}/${MODULE_ZIP_NAME}",
            runner.scripts.last()
        )
        assertTrue(runner.scripts.first().startsWith("cp -f "))
    }

    private suspend fun capabilities(support: ShellOutput, environment: FakeEnvironment): RootCapabilities {
        val provider = ApatchProvider(apatch, RootShell(RecordingRunner(responder(support))), environment)
        return provider.capabilities()
    }

    private fun wizardState(capabilities: RootCapabilities): WizardState = ModuleController.reduce(
        WizardState.Checking,
        WizardEvent.ProbeCompleted(
            ProbeSnapshot(
                detection = detection,
                module = ModuleState.Absent,
                overlay = capabilities.systemOverlay,
                metamodulePending = capabilities.metamodulePending,
                metamoduleInterrupted = capabilities.metamoduleInterrupted,
                overlayProbeError = capabilities.overlayProbeError
            )
        )
    ).state

    private fun modules(): FakeEnvironment = FakeEnvironment().apply {
        dirs[RootCommand.MODULES_DIR] = emptyList()
    }

    /**
     * A metamodule staged under `modules_update/<id>`. `completed` adds what APatch 11282's
     * `apd/assets/installer.sh:405-410` writes at the end: `modules/<id>/update` and `module.prop`.
     */
    private fun stagedMetamodule(completed: Boolean): FakeEnvironment = modules().apply {
        dirs[RootCommand.MODULES_UPDATE_DIR] = listOf("meta-overlayfs")
        dirs["${RootCommand.MODULES_UPDATE_DIR}/meta-overlayfs"] = listOf("module.prop")
        files["${RootCommand.MODULES_UPDATE_DIR}/meta-overlayfs/module.prop"] = META_PROP
        if (completed) {
            dirs[RootCommand.MODULES_DIR] = listOf("meta-overlayfs")
            dirs["${RootCommand.MODULES_DIR}/meta-overlayfs"] = listOf("module.prop", "update")
            files["${RootCommand.MODULES_DIR}/meta-overlayfs/module.prop"] = META_PROP
        }
    }

    /** The probe answered 0: this apd carries the metamodule literal. */
    private fun supported(): ShellOutput = ShellOutput(exitCode = 0)

    /** Answers the capability probe with [support]; every other command succeeds. */
    private fun responder(support: ShellOutput): (String) -> ShellOutput = { script ->
        if (script == RootCommand.apdSupportsMetamodules().script) support else ShellOutput()
    }

    private companion object {
        const val META_PROP: String = "id=meta-overlayfs\nname=OverlayFS MetaModule\nmetamodule=1\nversion=1.3.1\n"
    }
}
