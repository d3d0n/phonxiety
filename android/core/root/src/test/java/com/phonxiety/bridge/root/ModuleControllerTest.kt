package com.phonxiety.bridge.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleControllerTest {
    private val magisk = magiskProvider()
    private val kernelSu = kernelSuProvider()

    private fun snapshot(
        detection: RootDetection = RootDetection(SuStatus.GRANTED, listOf(magisk)),
        module: ModuleState = ModuleState.Absent,
        overlay: Overlay = Overlay.BUILT_IN,
        verify: VerifyReport? = null,
        missingRuntime: List<RuntimePermission> = emptyList(),
        paired: Boolean = false,
        linkTestPassed: Boolean? = null,
        progress: ProgressRecord? = null,
        metamodulePending: Boolean = false
    ): ProbeSnapshot = ProbeSnapshot(
        detection = detection,
        module = module,
        overlay = overlay,
        verify = verify,
        missingRuntime = missingRuntime,
        paired = paired,
        linkTestPassed = linkTestPassed,
        progress = progress,
        metamodulePending = metamodulePending
    )

    private fun probe(snapshot: ProbeSnapshot): Transition = ModuleController.reduce(WizardState.Checking, WizardEvent.ProbeCompleted(snapshot))

    // --- detection routing ------------------------------------------------------------------

    @Test
    fun noRootCarriesTheManagerHint() {
        val state = probe(
            snapshot(detection = RootDetection(SuStatus.NOT_ROOTED, listOf(magisk)))
        ).state

        assertEquals(WizardState.NoRoot(listOf(magisk)), state)
    }

    @Test
    fun deniedNamesTheProviderAndKeepsTheOutput() {
        val state = probe(
            snapshot(
                detection = RootDetection(SuStatus.DENIED, listOf(magisk), "Permission denied")
            )
        ).state

        assertEquals(WizardState.RootDenied(magisk, "Permission denied"), state)
    }

    @Test
    fun grantingRootInTheManagerResumesWithoutAnotherStep() {
        val denied = probe(
            snapshot(
                detection = RootDetection(SuStatus.DENIED, listOf(magisk), "Permission denied")
            )
        ).state
        assertEquals(WizardState.RootDenied(magisk, "Permission denied"), denied)

        // The user taps "Open Magisk", grants Phonxiety in Superuser and comes back: the host
        // re-probes on resume, and this is the snapshot it feeds with the grant now in place.
        val resumed = ModuleController.reduce(
            denied,
            WizardEvent.ProbeCompleted(snapshot())
        )

        assertEquals(WizardState.ReadyToInstall(magisk), resumed.state)
    }

    @Test
    fun recheckFromDeniedShowsTheSpinnerAndProbesAgain() {
        val denied = WizardState.RootDenied(magisk, "Permission denied")
        val transition = ModuleController.reduce(denied, WizardEvent.RecheckRequested)

        assertEquals(WizardState.Checking, transition.state)
        assertEquals(listOf<WizardEffect>(WizardEffect.RunProbe), transition.effects)
    }

    @Test
    fun suTimeoutIsExplicitInsteadOfAGuess() {
        val state = probe(
            snapshot(detection = RootDetection(SuStatus.TIMED_OUT, suOutput = ""))
        ).state

        assertTrue(state is WizardState.Indeterminate)
        assertTrue((state as WizardState.Indeterminate).message.contains("20 s"))
    }

    @Test
    fun aHidingManagerMeansAPendingGrantNotAPhoneWithoutRoot() {
        val state = probe(
            snapshot(
                detection = RootDetection(
                    su = SuStatus.NOT_ROOTED,
                    providers = listOf(kernelSu),
                    suOutput = "Cannot run program \"su\""
                )
            )
        ).state

        // KernelSU hides su from a package it has not allowed: the wizard names the grant.
        assertEquals(WizardState.RootDenied(kernelSu, "Cannot run program \"su\""), state)
    }

    @Test
    fun twoHidingManagersListBothGrants() {
        val apatch = apatchProvider()
        val state = probe(
            snapshot(detection = RootDetection(SuStatus.NOT_ROOTED, listOf(kernelSu, apatch)))
        ).state

        val denied = state as WizardState.RootDenied
        assertEquals(kernelSu, denied.provider)
        assertEquals(listOf(apatch), denied.coProviders)
    }

    @Test
    fun magiskOnlyWithNoSuMeansMagiskIsNotActive() {
        val state = probe(
            snapshot(detection = RootDetection(SuStatus.NOT_ROOTED, listOf(magisk)))
        ).state

        assertEquals(WizardState.NoRoot(listOf(magisk)), state)
    }

    @Test
    fun theProviderThatAnsweredSuIsTheActiveOneAndTheRestAreLeftovers() {
        val state = probe(
            snapshot(
                detection = RootDetection(
                    su = SuStatus.GRANTED,
                    providers = listOf(magisk, kernelSu),
                    active = RootId.KERNELSU
                )
            )
        ).state

        // Magisk's files remain from the migration; they do not block KernelSU.
        assertEquals(WizardState.ReadyToInstall(kernelSu), state)
    }

    @Test
    fun magiskActiveWithKernelSuLeftoversInstallsThroughMagisk() {
        val state = probe(
            snapshot(
                detection = RootDetection(
                    su = SuStatus.GRANTED,
                    providers = listOf(magisk, kernelSu),
                    active = RootId.MAGISK
                )
            )
        ).state

        assertEquals(WizardState.ReadyToInstall(magisk), state)
    }

    @Test
    fun conflictRefusesInstall() {
        val state = probe(
            snapshot(detection = RootDetection(SuStatus.GRANTED, listOf(magisk, kernelSu)))
        ).state

        assertEquals(WizardState.Conflict(listOf(magisk, kernelSu)), state)
    }

    @Test
    fun grantedRootWithoutAProviderIsIndeterminate() {
        val state = probe(
            snapshot(detection = RootDetection(SuStatus.GRANTED, emptyList()))
        ).state

        assertTrue(state is WizardState.Indeterminate)
    }

    @Test
    fun unreadableModuleTreeIsIndeterminate() {
        val state = probe(snapshot(module = ModuleState.Unknown)).state

        assertTrue(state is WizardState.Indeterminate)
    }

    // --- prerequisites and install ----------------------------------------------------------

    @Test
    fun absentModuleWithBuiltInOverlayIsReadyToInstall() {
        val state = probe(snapshot()).state

        assertEquals(WizardState.ReadyToInstall(magisk, ModuleState.Absent, false), state)
    }

    @Test
    fun kernelSuWithoutMetamoduleStopsAtProviderPrep() {
        val state = probe(
            snapshot(
                detection = RootDetection(SuStatus.GRANTED, listOf(kernelSu)),
                overlay = Overlay.METAMODULE_MISSING
            )
        ).state

        assertEquals(WizardState.ProviderPrep(kernelSu, PrepReason.METAMODULE_MISSING), state)
    }

    @Test
    fun preparingProviderInstallsTheMetamoduleZip() {
        val transition = ModuleController.reduce(
            WizardState.ProviderPrep(kernelSu, PrepReason.METAMODULE_MISSING),
            WizardEvent.PrepareProviderRequested
        )

        assertEquals(
            WizardState.Installing(kernelSu, InstallOperation.PREPARE_METAMODULE, ModuleState.Absent),
            transition.state
        )
        assertEquals(
            listOf(
                WizardEffect.InstallModule(RootId.KERNELSU, InstallOperation.PREPARE_METAMODULE),
                WizardEffect.PersistProgress(InstallOperation.PREPARE_METAMODULE, RootId.KERNELSU)
            ),
            transition.effects
        )
    }

    @Test
    fun aFailedMetamoduleRetriesThePreparationNotAModuleUpdate() {
        val failed = WizardState.InstallFailed(
            provider = kernelSu,
            operation = InstallOperation.PREPARE_METAMODULE,
            module = ModuleState.Absent,
            result = ProviderResult.Failure("meta-overlayfs SHA-256 mismatch", exitCode = 3)
        )

        val transition = ModuleController.reduce(failed, WizardEvent.RetryRequested)

        assertEquals(
            WizardState.ProviderPrep(kernelSu, PrepReason.METAMODULE_MISSING),
            transition.state
        )
    }

    @Test
    fun preparedMetamoduleAsksForOneReboot() {
        val installing = ModuleController.reduce(
            WizardState.ProviderPrep(kernelSu, PrepReason.METAMODULE_MISSING),
            WizardEvent.PrepareProviderRequested
        ).state

        val transition = ModuleController.reduce(
            installing,
            WizardEvent.InstallResult(ProviderResult.Success("meta-overlayfs: installed"))
        )

        assertEquals(
            WizardState.RebootPending(kernelSu, RebootReason.METAMODULE_INSTALL, ModuleState.Absent),
            transition.state
        )
    }

    // --- KernelSU metamodule: two boots ------------------------------------------------------

    @Test
    fun aStagedMetamoduleAsksForTheRebootAndInstallsNothing() {
        val transition = probe(
            snapshot(
                detection = RootDetection(SuStatus.GRANTED, listOf(kernelSu)),
                overlay = Overlay.METAMODULE_MISSING,
                metamodulePending = true
            )
        )

        assertEquals(
            WizardState.RebootPending(kernelSu, RebootReason.METAMODULE_INSTALL, ModuleState.Absent),
            transition.state
        )
        assertEquals(
            listOf(WizardEffect.PersistProgress(InstallOperation.PREPARE_METAMODULE, RootId.KERNELSU)),
            transition.effects
        )
    }

    @Test
    fun theMetamoduleRebootKeepsTheProgressAsAPreparation() {
        val pending = WizardState.RebootPending(
            kernelSu,
            RebootReason.METAMODULE_INSTALL,
            ModuleState.Absent
        )

        val transition = ModuleController.reduce(pending, WizardEvent.RebootRequested)

        assertEquals(pending, transition.state)
        assertEquals(
            listOf(
                WizardEffect.Reboot(RebootReason.METAMODULE_INSTALL),
                WizardEffect.PersistProgress(InstallOperation.PREPARE_METAMODULE, RootId.KERNELSU)
            ),
            transition.effects
        )
    }

    @Test
    fun theWizardRebuiltFromAStagedMetamoduleLandsOnTheRebootStep() {
        // Process death before the reboot: nothing survives except the probe and the journal.
        val transition = probe(
            snapshot(
                detection = RootDetection(SuStatus.GRANTED, listOf(kernelSu)),
                overlay = Overlay.METAMODULE_MISSING,
                metamodulePending = true,
                progress = ProgressRecord(InstallOperation.PREPARE_METAMODULE, RootId.KERNELSU, 1L)
            )
        )

        val pending = transition.state as WizardState.RebootPending
        assertEquals(RebootReason.METAMODULE_INSTALL, pending.reason)
        assertTrue(transition.effects.none { it is WizardEffect.InstallModule })
        assertEquals(
            listOf(WizardEffect.PersistProgress(InstallOperation.PREPARE_METAMODULE, RootId.KERNELSU)),
            transition.effects
        )
    }

    @Test
    fun afterTheMetamoduleBootTheProbeIsReadyToInstallTheModule() {
        val transition = probe(
            snapshot(
                detection = RootDetection(SuStatus.GRANTED, listOf(kernelSu)),
                overlay = Overlay.METAMODULE_PRESENT,
                metamodulePending = false,
                progress = ProgressRecord(InstallOperation.PREPARE_METAMODULE, RootId.KERNELSU, 1L)
            )
        )

        assertEquals(WizardState.ReadyToInstall(kernelSu, ModuleState.Absent, false), transition.state)
    }

    // --- Magisk and APatch keep their single-boot flow ---------------------------------------

    private val apatch = apatchProvider()

    @Test
    fun magiskInstallsThenRebootsThenResumesAfterTheBoot() {
        val ready = probe(snapshot()).state
        assertEquals(WizardState.ReadyToInstall(magisk, ModuleState.Absent, false), ready)

        val installing = ModuleController.reduce(ready, WizardEvent.InstallRequested)
        assertEquals(
            listOf(
                WizardEffect.InstallModule(RootId.MAGISK, InstallOperation.INSTALL),
                WizardEffect.PersistProgress(InstallOperation.INSTALL, RootId.MAGISK)
            ),
            installing.effects
        )

        val pending = ModuleController.reduce(
            installing.state,
            WizardEvent.InstallResult(ProviderResult.Success("installed"))
        ).state
        assertEquals(
            WizardState.RebootPending(magisk, RebootReason.MODULE_INSTALL, ModuleState.Absent),
            pending
        )

        assertEquals(
            listOf(
                WizardEffect.Reboot(RebootReason.MODULE_INSTALL),
                WizardEffect.PersistProgress(InstallOperation.INSTALL, RootId.MAGISK)
            ),
            ModuleController.reduce(pending, WizardEvent.RebootRequested).effects
        )

        val afterBoot = probe(
            snapshot(module = ModuleState.Enabled("0.3.0", 3), verify = passingReport(), paired = true)
        )
        assertEquals(WizardState.FirstLinkTest(magisk, null), afterBoot.state)
    }

    @Test
    fun apatchInstallsThenRebootsWithTheInstallOperation() {
        val ready = probe(snapshot(detection = RootDetection(SuStatus.GRANTED, listOf(apatch)))).state
        assertEquals(WizardState.ReadyToInstall(apatch, ModuleState.Absent, false), ready)

        val installing = ModuleController.reduce(ready, WizardEvent.InstallRequested)
        assertEquals(InstallOperation.INSTALL, (installing.state as WizardState.Installing).operation)

        val pending = ModuleController.reduce(
            installing.state,
            WizardEvent.InstallResult(ProviderResult.Success("installed"))
        ).state
        assertEquals(
            WizardState.RebootPending(apatch, RebootReason.MODULE_INSTALL, ModuleState.Absent),
            pending
        )
        assertEquals(
            listOf(
                WizardEffect.Reboot(RebootReason.MODULE_INSTALL),
                WizardEffect.PersistProgress(InstallOperation.INSTALL, RootId.APATCH)
            ),
            ModuleController.reduce(pending, WizardEvent.RebootRequested).effects
        )

        val afterBoot = probe(
            snapshot(
                detection = RootDetection(SuStatus.GRANTED, listOf(apatch)),
                module = ModuleState.Enabled("0.3.0", 3),
                verify = passingReport(),
                paired = true
            )
        )
        assertEquals(WizardState.FirstLinkTest(apatch, null), afterBoot.state)
    }

    @Test
    fun apatchStagedUpdateStillAsksForItsOwnReboot() {
        val transition = probe(
            snapshot(
                detection = RootDetection(SuStatus.GRANTED, listOf(apatch)),
                module = ModuleState.Staged("0.4.0", 4)
            )
        )

        assertEquals(
            WizardState.RebootPending(apatch, RebootReason.MODULE_UPDATE, ModuleState.Staged("0.4.0", 4)),
            transition.state
        )
        assertEquals(
            listOf(WizardEffect.PersistProgress(InstallOperation.UPDATE, RootId.APATCH)),
            transition.effects
        )

        assertEquals(
            listOf(
                WizardEffect.Reboot(RebootReason.MODULE_UPDATE),
                WizardEffect.PersistProgress(InstallOperation.UPDATE, RootId.APATCH)
            ),
            ModuleController.reduce(transition.state, WizardEvent.RebootRequested).effects
        )

        val afterBoot = probe(
            snapshot(
                detection = RootDetection(SuStatus.GRANTED, listOf(apatch)),
                module = ModuleState.Enabled("0.4.0", 4),
                verify = passingReport(),
                paired = true
            )
        )
        assertEquals(WizardState.FirstLinkTest(apatch, null), afterBoot.state)
    }

    @Test
    fun magiskAndApatchRollbackRebootKeepsTheRollbackProgress() {
        listOf(magisk to RootId.MAGISK, apatch to RootId.APATCH).forEach { (provider, id) ->
            val installing = ModuleController.reduce(
                WizardState.ReadyToInstall(provider, ModuleState.Enabled("0.3.0", 3)),
                WizardEvent.RollbackRequested
            )
            assertEquals(InstallOperation.ROLLBACK, (installing.state as WizardState.Installing).operation)

            val pending = ModuleController.reduce(
                installing.state,
                WizardEvent.InstallResult(ProviderResult.Success("removed"))
            ).state
            assertEquals(
                WizardState.RebootPending(provider, RebootReason.MODULE_ROLLBACK, ModuleState.Enabled("0.3.0", 3)),
                pending
            )
            assertEquals(
                listOf(
                    WizardEffect.Reboot(RebootReason.MODULE_ROLLBACK),
                    WizardEffect.PersistProgress(InstallOperation.ROLLBACK, id)
                ),
                ModuleController.reduce(pending, WizardEvent.RebootRequested).effects
            )
        }
    }

    @Test
    fun installRequestedRunsTheProviderAndPersistsProgress() {
        val ready = WizardState.ReadyToInstall(magisk, ModuleState.Absent, false)

        val transition = ModuleController.reduce(ready, WizardEvent.InstallRequested)

        assertEquals(WizardState.Installing(magisk, InstallOperation.INSTALL, ModuleState.Absent), transition.state)
        assertEquals(
            listOf(
                WizardEffect.InstallModule(RootId.MAGISK, InstallOperation.INSTALL),
                WizardEffect.PersistProgress(InstallOperation.INSTALL, RootId.MAGISK)
            ),
            transition.effects
        )
    }

    @Test
    fun installSuccessWaitsForTheUserToReboot() {
        val installing = WizardState.Installing(magisk, InstallOperation.INSTALL, ModuleState.Absent)

        val transition = ModuleController.reduce(
            installing,
            WizardEvent.InstallResult(ProviderResult.Success("installed"))
        )

        assertEquals(
            WizardState.RebootPending(magisk, RebootReason.MODULE_INSTALL, ModuleState.Absent),
            transition.state
        )
        assertEquals(emptyList<WizardEffect>(), transition.effects)
    }

    @Test
    fun rebootEmitsTheEffectAndStaysPending() {
        val pending = WizardState.RebootPending(magisk, RebootReason.MODULE_INSTALL)
        val transition = ModuleController.reduce(pending, WizardEvent.RebootRequested)

        assertEquals(pending, transition.state)
        assertEquals(
            listOf(
                WizardEffect.Reboot(RebootReason.MODULE_INSTALL),
                WizardEffect.PersistProgress(InstallOperation.INSTALL, RootId.MAGISK)
            ),
            transition.effects
        )
    }

    @Test
    fun aRebootThatFailedKeepsTheStepAndSaysWhy() {
        val pending = WizardState.RebootPending(magisk, RebootReason.MODULE_INSTALL, ModuleState.Absent)

        val failed = ModuleController.reduce(
            pending,
            WizardEvent.RebootFailed("svc: Unable to set property \"sys.powerctl\": PROP_ERROR_PERMISSION_DENIED")
        ).state

        assertEquals(
            WizardState.RebootPending(
                magisk,
                RebootReason.MODULE_INSTALL,
                ModuleState.Absent,
                "svc: Unable to set property \"sys.powerctl\": PROP_ERROR_PERMISSION_DENIED"
            ),
            failed
        )
    }

    @Test
    fun aRebootThatDidNotHappenIsNotReportedAsDone() {
        val pending = WizardState.RebootPending(magisk, RebootReason.MODULE_INSTALL, ModuleState.Absent)

        val unconfirmed = ModuleController.reduce(pending, WizardEvent.RebootNotConfirmed).state

        val failure = (unconfirmed as WizardState.RebootPending).failure.orEmpty()
        assertTrue(failure, failure.contains("still running 20 s after the reboot request"))
    }

    @Test
    fun retryingTheRebootClearsTheLastFailure() {
        val failed = WizardState.RebootPending(
            magisk,
            RebootReason.MODULE_INSTALL,
            ModuleState.Absent,
            "PROP_ERROR_PERMISSION_DENIED"
        )

        val retry = ModuleController.reduce(failed, WizardEvent.RebootRequested)

        assertEquals(WizardState.RebootPending(magisk, RebootReason.MODULE_INSTALL, ModuleState.Absent), retry.state)
        assertEquals(WizardEffect.Reboot(RebootReason.MODULE_INSTALL), retry.effects.first())
    }

    @Test
    fun installFailureShowsTheProviderOutputTail() {
        val installing = WizardState.Installing(kernelSu, InstallOperation.INSTALL, ModuleState.Absent)
        val result = ProviderResult.Failure("line 1\nline 2\nline 3", 1)

        val transition = ModuleController.reduce(installing, WizardEvent.InstallResult(result))

        val failed = transition.state as WizardState.InstallFailed
        assertEquals(kernelSu, failed.provider)
        assertEquals(listOf("line 1", "line 2", "line 3"), failed.outputTail)
    }

    @Test
    fun retryReturnsToReadyToInstallAndKeepsUpdateIntent() {
        val failed = WizardState.InstallFailed(
            magisk,
            InstallOperation.UPDATE,
            ModuleState.Enabled("0.2.0", 2),
            ProviderResult.Failure("nope", 1)
        )

        assertEquals(
            WizardState.ReadyToInstall(magisk, ModuleState.Enabled("0.2.0", 2), true),
            ModuleController.reduce(failed, WizardEvent.RetryRequested).state
        )
    }

    @Test
    fun rollbackReinstallsTheKeptZip() {
        val enabled = WizardState.Verify(magisk, passingReport())

        val transition = ModuleController.reduce(enabled, WizardEvent.RollbackRequested)

        assertEquals(
            WizardState.Installing(magisk, InstallOperation.ROLLBACK, ModuleState.Unknown),
            transition.state
        )
        assertTrue(
            transition.effects.contains(WizardEffect.InstallModule(RootId.MAGISK, InstallOperation.ROLLBACK))
        )
    }

    @Test
    fun updateRequestedAsksForConfirmationBeforeInstalling() {
        val installed = WizardState.Doctor(
            magisk,
            DoctorReason.VERIFY_FAILED,
            module = ModuleState.Enabled("0.3.0", 3)
        )

        assertEquals(
            WizardState.ReadyToInstall(magisk, ModuleState.Enabled("0.3.0", 3), true),
            ModuleController.reduce(installed, WizardEvent.UpdateRequested).state
        )
    }

    // --- verify and resume ------------------------------------------------------------------

    @Test
    fun installedModuleWithoutReportRunsVerifyFirst() {
        val transition = probe(snapshot(module = ModuleState.Enabled("0.3.0", 3)))

        assertEquals(WizardState.Verify(magisk, null), transition.state)
        assertEquals(listOf(WizardEffect.RunVerify), transition.effects)
    }

    @Test
    fun resumeAfterRebootClearsProgressAndContinuesFlow() {
        val transition = probe(
            snapshot(
                module = ModuleState.Enabled("0.3.0", 3),
                verify = passingReport(),
                paired = true,
                progress = ProgressRecord(InstallOperation.INSTALL, RootId.MAGISK, 1L)
            )
        )

        assertEquals(WizardState.FirstLinkTest(magisk, null), transition.state)
        assertEquals(
            listOf(WizardEffect.ClearProgress, WizardEffect.RunLinkTest),
            transition.effects
        )
    }

    @Test
    fun verifyFailureLandsOnDoctorWithTheFailedChecks() {
        val report = failingReport(VerifyCheck.APK_IN_PRIV_APP)

        val state = probe(snapshot(module = ModuleState.Enabled("0.3.0", 3), verify = report)).state

        val doctor = state as WizardState.Doctor
        assertEquals(DoctorReason.VERIFY_FAILED, doctor.reason)
        assertEquals(listOf(VerifyCheck.APK_IN_PRIV_APP), doctor.report!!.failures)
    }

    @Test
    fun disabledModuleIsItsOwnDoctorReason() {
        val report = failingReport(VerifyCheck.MODULE_ENABLED, VerifyCheck.NO_DISABLE_FLAG)

        val state = probe(snapshot(module = ModuleState.Disabled("0.3.0", 3), verify = report)).state

        assertEquals(
            DoctorReason.MODULE_DISABLED,
            (state as WizardState.Doctor).reason
        )
    }

    @Test
    fun pendingRemovalIsItsOwnDoctorReason() {
        val state = probe(snapshot(module = ModuleState.RemovePending)).state

        assertEquals(DoctorReason.MODULE_PENDING_REMOVAL, (state as WizardState.Doctor).reason)
    }

    @Test
    fun stagedUpdateNeedsABoot() {
        val transition = probe(snapshot(module = ModuleState.Staged("0.4.0", 4)))

        assertEquals(
            WizardState.RebootPending(magisk, RebootReason.MODULE_UPDATE, ModuleState.Staged("0.4.0", 4)),
            transition.state
        )
        assertEquals(
            listOf(WizardEffect.PersistProgress(InstallOperation.UPDATE, RootId.MAGISK)),
            transition.effects
        )
    }

    // --- wizard tail ------------------------------------------------------------------------

    @Test
    fun missingRuntimePermissionsComeBeforePairing() {
        val state = probe(
            snapshot(
                module = ModuleState.Enabled("0.3.0", 3),
                verify = passingReport(),
                missingRuntime = listOf(RuntimePermission.RECORD_AUDIO)
            )
        )

        assertEquals(
            WizardState.NeedsRuntime(magisk, listOf(RuntimePermission.RECORD_AUDIO)),
            state.state
        )
        assertTrue(state.effects.contains(WizardEffect.RequestRuntimePermissions))

        val granted = ModuleController.reduce(
            state.state,
            WizardEvent.RuntimePermissionsChanged(emptyList())
        )
        assertEquals(WizardState.NeedsPairing(magisk), granted.state)
        assertEquals(listOf(WizardEffect.StartPairing), granted.effects)
    }

    @Test
    fun theLocalNetworkGrantIsRequiredBeforePairing() {
        val missing = listOf(RuntimePermission.ACCESS_LOCAL_NETWORK)
        val transition = probe(
            snapshot(
                module = ModuleState.Enabled("0.3.0", 3),
                verify = passingReport(),
                missingRuntime = missing
            )
        )

        assertEquals(WizardState.NeedsRuntime(magisk, missing), transition.state)
        assertEquals(listOf(WizardEffect.ClearProgress, WizardEffect.RequestRuntimePermissions), transition.effects)
    }

    @Test
    fun pairingStartsTheFirstLinkTest() {
        val transition = ModuleController.reduce(
            WizardState.NeedsPairing(magisk),
            WizardEvent.PairingCompleted
        )

        assertEquals(WizardState.FirstLinkTest(magisk, null), transition.state)
        assertEquals(listOf(WizardEffect.RunLinkTest), transition.effects)
    }

    @Test
    fun passingLinkTestStartsTheService() {
        val transition = ModuleController.reduce(
            WizardState.FirstLinkTest(magisk, null),
            WizardEvent.LinkTestCompleted(passed = true)
        )

        assertEquals(WizardState.Ready, transition.state)
        assertEquals(listOf(WizardEffect.StartService), transition.effects)
    }

    @Test
    fun failingLinkTestGoesToDoctor() {
        val transition = ModuleController.reduce(
            WizardState.FirstLinkTest(magisk, null),
            WizardEvent.LinkTestCompleted(passed = false, detail = "p95 900 ms")
        )

        val doctor = transition.state as WizardState.Doctor
        assertEquals(DoctorReason.LINK_TEST_FAILED, doctor.reason)
        assertEquals("p95 900 ms", doctor.detail)
    }

    @Test
    fun repairedDoctorReachesReady() {
        val transition = ModuleController.reduce(
            WizardState.Doctor(magisk, DoctorReason.VERIFY_FAILED),
            WizardEvent.Repaired
        )

        assertEquals(WizardState.Ready, transition.state)
        assertEquals(listOf(WizardEffect.StartService), transition.effects)
    }

    @Test
    fun recheckReturnsToChecking() {
        assertEquals(
            WizardState.Checking,
            ModuleController.reduce(WizardState.RootDenied(magisk), WizardEvent.RecheckRequested).state
        )
    }

    @Test
    fun eventsForAnotherStateAreIgnored() {
        val ready = WizardState.Ready

        assertEquals(ready, ModuleController.reduce(ready, WizardEvent.InstallRequested).state)
        assertEquals(
            ready,
            ModuleController.reduce(ready, WizardEvent.InstallResult(ProviderResult.Success("x"))).state
        )
    }
}
