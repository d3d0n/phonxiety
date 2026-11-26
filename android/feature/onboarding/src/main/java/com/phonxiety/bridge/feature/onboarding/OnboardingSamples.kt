package com.phonxiety.bridge.feature.onboarding

import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.DoctorReason
import com.phonxiety.bridge.root.InstallOperation
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.ProviderResult
import com.phonxiety.bridge.root.RebootReason
import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.RuntimePermission
import com.phonxiety.bridge.root.VerifyCheck
import com.phonxiety.bridge.root.VerifyOutcome
import com.phonxiety.bridge.root.VerifyReport
import com.phonxiety.bridge.root.WizardState

/** Deterministic wizard states for previews and screenshot tests; never used at runtime. */
object OnboardingSamples {
    val magisk = DetectedProvider(
        id = RootId.MAGISK,
        version = "31.1",
        managerPackage = "com.topjohnwu.magisk",
        managerInstalled = true,
        artefacts = listOf("/data/adb/magisk")
    )

    val kernelSu = DetectedProvider(
        id = RootId.KERNELSU,
        version = "3.1.0",
        managerPackage = "me.weishu.kernelsu",
        managerInstalled = true
    )

    val apatch = DetectedProvider(
        id = RootId.APATCH,
        version = "0.11.3",
        managerPackage = "me.bmax.apatch",
        managerInstalled = true
    )

    val kernelSuNext = DetectedProvider(
        id = RootId.KERNELSU_NEXT,
        version = "1.0.5 (10005)",
        managerPackage = "com.rifsxd.ksunext",
        managerInstalled = true
    )

    /** Manager-package versions, which is what a denied `su` can still report. */
    val magiskDenied = magisk.copy(version = "31.1 (31100)")
    val kernelSuDenied = kernelSu.copy(version = "3.1.0 (31000)")
    val apatchDenied = apatch.copy(version = "0.11.3 (11003)")

    val passedReport = VerifyReport(
        checks = VerifyCheck.entries.associateWith { VerifyOutcome.PASS }
    )

    val failedReport = VerifyReport(
        checks = passedReport.checks + mapOf(
            VerifyCheck.PRIVILEGED_PERMISSIONS_GRANTED to VerifyOutcome.FAIL,
            VerifyCheck.APK_IN_PRIV_APP to VerifyOutcome.UNKNOWN
        )
    )

    val installFailure = ProviderResult.Failure(
        output = "ksud: module.prop: versionCode must be an integer\nksud: install aborted, nothing was mounted",
        exitCode = 1
    )

    /** One screen per plan §3.1 state, in the order the wizard walks them. */
    val states: Map<String, WizardState> = linkedMapOf(
        "checking" to WizardState.Checking,
        "no_root" to WizardState.NoRoot(listOf(magisk, kernelSu, apatch)),
        "root_denied_magisk" to WizardState.RootDenied(magiskDenied, "Permission denied"),
        "root_denied_kernelsu" to WizardState.RootDenied(kernelSuDenied, "Permission denied"),
        "root_denied_kernelsu_next" to WizardState.RootDenied(kernelSuNext, "Permission denied"),
        "root_denied_apatch" to WizardState.RootDenied(apatchDenied, "Permission denied"),
        "provider_prep" to WizardState.ProviderPrep(kernelSu),
        "ready_to_install" to WizardState.ReadyToInstall(magisk),
        "installing" to WizardState.Installing(magisk, InstallOperation.INSTALL, ModuleState.Absent),
        "install_failed" to WizardState.InstallFailed(
            provider = magisk,
            operation = InstallOperation.INSTALL,
            module = ModuleState.Absent,
            result = installFailure
        ),
        "reboot_pending" to WizardState.RebootPending(magisk, RebootReason.MODULE_INSTALL),
        "reboot_failed" to WizardState.RebootPending(
            provider = magisk,
            reason = RebootReason.MODULE_INSTALL,
            module = ModuleState.Absent,
            failure = "svc: Unable to set property \"sys.powerctl\": PROP_ERROR_PERMISSION_DENIED"
        ),
        "reboot_pending_metamodule" to WizardState.RebootPending(
            kernelSu,
            RebootReason.METAMODULE_INSTALL,
            ModuleState.Absent
        ),
        "verify" to WizardState.Verify(magisk, passedReport),
        "verify_failed" to WizardState.Verify(magisk, failedReport),
        "needs_runtime" to WizardState.NeedsRuntime(
            magisk,
            listOf(
                RuntimePermission.RECORD_AUDIO,
                RuntimePermission.CALL_PHONE,
                RuntimePermission.POST_NOTIFICATIONS,
                RuntimePermission.ACCESS_LOCAL_NETWORK
            )
        ),
        "needs_pairing" to WizardState.NeedsPairing(magisk),
        "first_link_test" to WizardState.FirstLinkTest(magisk, "probing…"),
        "ready" to WizardState.Ready,
        "doctor" to WizardState.Doctor(
            provider = magisk,
            reason = DoctorReason.VERIFY_FAILED,
            report = failedReport,
            module = ModuleState.Enabled("0.3.0", 3)
        ),
        "indeterminate" to WizardState.Indeterminate(
            message = "su did not answer within the 20 s budget",
            output = "su: timeout after 20000 ms"
        ),
        "conflict" to WizardState.Conflict(listOf(magisk, kernelSu))
    )
}
