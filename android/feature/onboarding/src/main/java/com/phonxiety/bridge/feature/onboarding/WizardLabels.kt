package com.phonxiety.bridge.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.phonxiety.bridge.designsystem.component.SetupStep
import com.phonxiety.bridge.designsystem.component.SetupStepState
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.root.ApatchProvider
import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.DoctorReason
import com.phonxiety.bridge.root.IndeterminateReason
import com.phonxiety.bridge.root.InstallOperation
import com.phonxiety.bridge.root.KernelSuProvider
import com.phonxiety.bridge.root.MagiskProvider
import com.phonxiety.bridge.root.ProviderResult
import com.phonxiety.bridge.root.RebootReason
import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.RuntimePermission
import com.phonxiety.bridge.root.VerifyCheck
import com.phonxiety.bridge.root.VerifyOutcome
import com.phonxiety.bridge.root.WizardState

internal fun RootId.managerName(): String = when (this) {
    RootId.MAGISK -> "Magisk"
    RootId.KERNELSU -> "KernelSU"
    RootId.KERNELSU_NEXT -> "KernelSU-Next"
    RootId.APATCH -> "APatch"
}

/** The manager app to launch; it is also the only place a grant can be given. */
internal fun RootId.managerPackage(): String = when (this) {
    RootId.MAGISK -> MagiskProvider.MANAGER_PACKAGE
    RootId.KERNELSU -> KernelSuProvider.KERNELSU_MANAGER_PACKAGE
    RootId.KERNELSU_NEXT -> KernelSuProvider.KERNELSU_NEXT_MANAGER_PACKAGE
    RootId.APATCH -> ApatchProvider.MANAGER_PACKAGE
}

/** Where the grant lives per manager, in the manager's own vocabulary. */
@Composable
internal fun RootId.grantInstruction(): String = stringResource(
    when (this) {
        RootId.MAGISK -> R.string.onboarding_grant_magisk
        RootId.KERNELSU -> R.string.onboarding_grant_kernelsu
        RootId.KERNELSU_NEXT -> R.string.onboarding_grant_kernelsu_next
        RootId.APATCH -> R.string.onboarding_grant_apatch
    }
)

/**
 * The provider's own version in the shape a person checks. The APatch binary answers with a build
 * hash and a build code; the code is the version people know, so the hash is dropped here:
 * `APatch 11282`, not `APatch e8e4651 (11282)`.
 */
private val VERSION_WITH_CODE = Regex("""(.*)\s+\((\d+)\)$""")
private val BUILD_HASH = Regex("[0-9a-f]{7,40}")

internal fun DetectedProvider.versionLabel(): String? {
    val raw = version.trim()
    if (raw.isEmpty() || raw.equals("unknown", ignoreCase = true)) return null
    val match = VERSION_WITH_CODE.matchEntire(raw)
    val name = match?.groupValues?.get(1)?.trim() ?: raw
    val code = match?.groupValues?.get(2)
    val nameIsHash = BUILD_HASH.matches(name)
    return when {
        nameIsHash && code != null -> code
        nameIsHash -> null
        code != null -> "$name ($code)"
        else -> name
    }
}

/** `Magisk 31.1`, `APatch 11282`, or just `APatch` when the device printed no version. */
internal fun DetectedProvider.providerLabel(): String {
    val version = versionLabel()
    return if (version == null) id.managerName() else "${id.managerName()} $version"
}

@Composable
internal fun WizardState.title(): String = stringResource(
    when (this) {
        WizardState.Checking -> R.string.onboarding_card_checking

        is WizardState.NoRoot -> R.string.onboarding_card_no_root

        is WizardState.RootDenied -> R.string.onboarding_card_root_denied

        is WizardState.ProviderPrep -> R.string.onboarding_card_provider_prep

        is WizardState.ReadyToInstall -> if (update) {
            R.string.onboarding_card_ready_to_update
        } else {
            R.string.onboarding_card_ready_to_install
        }

        is WizardState.Installing -> R.string.onboarding_card_installing

        is WizardState.InstallFailed -> R.string.onboarding_card_install_failed

        is WizardState.RebootPending -> R.string.onboarding_card_reboot_pending

        is WizardState.Verify -> R.string.onboarding_card_verify

        is WizardState.NeedsRuntime -> R.string.onboarding_card_needs_runtime

        is WizardState.NeedsPairing -> R.string.onboarding_card_needs_pairing

        is WizardState.FirstLinkTest -> R.string.onboarding_card_first_link_test

        WizardState.Ready -> R.string.onboarding_card_ready

        is WizardState.Doctor -> R.string.onboarding_card_doctor

        is WizardState.Indeterminate -> R.string.onboarding_card_indeterminate

        is WizardState.Conflict -> R.string.onboarding_card_conflict
    }
)

@Composable
internal fun WizardState.subtitle(): String? {
    val provider = when (this) {
        is WizardState.RootDenied -> this.provider
        is WizardState.ProviderPrep -> this.provider
        is WizardState.ReadyToInstall -> this.provider
        is WizardState.RebootPending -> this.provider
        is WizardState.NeedsPairing -> this.provider
        is WizardState.Doctor -> this.provider
        else -> null
    }
    return provider?.providerLabel()
}

@Composable
internal fun WizardState.pillLabel(): String = when (this) {
    WizardState.Checking -> stringResource(R.string.onboarding_pill_checking)

    is WizardState.NoRoot -> stringResource(R.string.onboarding_pill_no_root)

    is WizardState.RootDenied -> stringResource(R.string.onboarding_pill_root_denied)

    is WizardState.ProviderPrep -> stringResource(R.string.onboarding_pill_provider_prep)

    is WizardState.ReadyToInstall -> stringResource(R.string.onboarding_pill_ready)

    is WizardState.Installing -> stringResource(R.string.onboarding_pill_installing)

    is WizardState.InstallFailed -> stringResource(R.string.onboarding_pill_failed)

    is WizardState.RebootPending -> stringResource(R.string.onboarding_pill_reboot_needed)

    is WizardState.Verify -> {
        val verified = report
        when {
            verified == null -> stringResource(R.string.onboarding_pill_checking)
            verified.passed -> stringResource(R.string.onboarding_pill_verified)
            else -> stringResource(R.string.onboarding_pill_failed)
        }
    }

    is WizardState.NeedsRuntime -> pluralStringResource(
        R.plurals.onboarding_pill_permissions,
        missing.size,
        missing.size
    )

    is WizardState.NeedsPairing -> stringResource(R.string.onboarding_pill_not_paired)

    is WizardState.FirstLinkTest -> stringResource(R.string.onboarding_pill_testing)

    WizardState.Ready -> stringResource(R.string.onboarding_pill_ready)

    is WizardState.Doctor -> stringResource(R.string.onboarding_pill_doctor)

    is WizardState.Indeterminate -> stringResource(R.string.onboarding_pill_unknown)

    is WizardState.Conflict -> stringResource(R.string.onboarding_pill_conflict)
}

internal fun WizardState.pillTone(): StatusTone = when (this) {
    is WizardState.ReadyToInstall -> StatusTone.POSITIVE

    is WizardState.Verify -> if (report?.passed == true) StatusTone.POSITIVE else StatusTone.WARNING

    WizardState.Ready -> StatusTone.POSITIVE

    is WizardState.InstallFailed, is WizardState.NoRoot, is WizardState.RootDenied, is WizardState.Doctor,
    is WizardState.Conflict -> StatusTone.NEGATIVE

    is WizardState.Indeterminate -> StatusTone.NEUTRAL

    is WizardState.NeedsRuntime -> StatusTone.WARNING

    else -> StatusTone.ACCENT
}

/** A state where the screen is already showing what is happening and offers no action. */
internal fun WizardState.isTransitional(): Boolean = this is WizardState.Checking ||
    this is WizardState.Installing ||
    this is WizardState.FirstLinkTest ||
    (this is WizardState.Verify && report == null)

@Composable
internal fun WizardState.toSteps(): List<SetupStep> {
    val needsMetamodule = this is WizardState.ProviderPrep ||
        (this is WizardState.RebootPending && reason == RebootReason.METAMODULE_INSTALL) ||
        (this is WizardState.Installing && operation == InstallOperation.PREPARE_METAMODULE) ||
        (this is WizardState.InstallFailed && operation == InstallOperation.PREPARE_METAMODULE)
    // Noun phrases only: a step title must not read like the button beside it.
    val titles = listOf(
        stringResource(R.string.onboarding_step_root),
        stringResource(
            if (needsMetamodule) R.string.onboarding_step_metamodule else R.string.onboarding_step_module
        ),
        stringResource(R.string.onboarding_step_reboot),
        stringResource(R.string.onboarding_step_permissions),
        stringResource(R.string.onboarding_step_pairing),
        stringResource(R.string.onboarding_step_link_test)
    )
    val detectDetail = (this as? WizardState.RebootPending)?.provider?.providerLabel()
    val provider = when (this) {
        is WizardState.ReadyToInstall -> this.provider
        is WizardState.Installing -> this.provider
        is WizardState.InstallFailed -> this.provider
        is WizardState.RebootPending -> this.provider
        is WizardState.ProviderPrep -> this.provider
        is WizardState.RootDenied -> this.provider
        else -> null
    }
    val metamoduleReboot = (this as? WizardState.RebootPending)?.reason == RebootReason.METAMODULE_INSTALL
    val moduleDetail = when {
        this is WizardState.InstallFailed && operation == InstallOperation.PREPARE_METAMODULE ->
            stringResource(R.string.onboarding_step_failed)

        this is WizardState.InstallFailed -> stringResource(R.string.onboarding_step_failed)

        metamoduleReboot -> stringResource(R.string.onboarding_step_overlay_staged)

        else -> null
    }
    val doneBefore: Int
    val active: Int?
    val failed: Int?
    when (this) {
        WizardState.Checking -> {
            doneBefore = 0
            active = 0
            failed = null
        }

        is WizardState.NoRoot, is WizardState.Indeterminate, is WizardState.Conflict -> {
            doneBefore = 0
            active = null
            failed = 0
        }

        is WizardState.RootDenied -> {
            doneBefore = 0
            active = 0
            failed = null
        }

        is WizardState.ProviderPrep, is WizardState.ReadyToInstall, is WizardState.Installing -> {
            doneBefore = 1
            active = 1
            failed = null
        }

        is WizardState.InstallFailed -> {
            doneBefore = 1
            active = null
            failed = 1
        }

        is WizardState.RebootPending -> {
            doneBefore = 2
            active = 2
            failed = null
        }

        is WizardState.Verify, is WizardState.NeedsRuntime -> {
            doneBefore = 3
            active = 3
            failed = null
        }

        is WizardState.NeedsPairing -> {
            doneBefore = 4
            active = 4
            failed = null
        }

        is WizardState.FirstLinkTest -> {
            doneBefore = 5
            active = 5
            failed = null
        }

        WizardState.Ready -> {
            doneBefore = 6
            active = null
            failed = null
        }

        is WizardState.Doctor -> {
            val step = when (reason) {
                DoctorReason.VERIFY_FAILED -> 3
                DoctorReason.MODULE_DISABLED, DoctorReason.MODULE_PENDING_REMOVAL -> 1
                DoctorReason.LINK_TEST_FAILED -> 5
            }
            doneBefore = step
            active = null
            failed = step
        }
    }
    return titles.mapIndexed { index, title ->
        SetupStep(
            title = title,
            state = when {
                failed == index -> SetupStepState.FAILED
                doneBefore > index -> SetupStepState.DONE
                active == index -> SetupStepState.ACTIVE
                else -> SetupStepState.PENDING
            },
            detail = when {
                index == 0 && this is WizardState.Conflict ->
                    stringResource(R.string.onboarding_step_two_managers)

                index == 0 && failed != 0 ->
                    detectDetail ?: provider?.providerLabel()

                index == 1 && moduleDetail != null -> moduleDetail

                index == 1 && doneBefore > 1 && !needsMetamodule ->
                    stringResource(R.string.onboarding_step_installed)

                index == 4 && doneBefore > 4 -> stringResource(R.string.onboarding_step_paired)

                else -> null
            }
        )
    }
}

internal fun WizardState.InstallFailed.output(): String = result.output()

internal fun WizardState.Doctor.output(): String {
    val reportText = report?.let { report ->
        report.checks.entries.joinToString("\n") { (check, outcome) ->
            "${check.debugLabel()}: ${outcome.debugLabel()}"
        }
    }
    return listOfNotNull(detail, reportText).joinToString("\n").ifEmpty { "no captured output" }
}

private fun ProviderResult.output(): String = tail(lines = 20).joinToString("\n").ifEmpty { "the provider printed nothing" }

@StringRes
internal fun installFailureTitle(operation: InstallOperation): Int = when (operation) {
    InstallOperation.INSTALL -> R.string.onboarding_failed_install
    InstallOperation.UPDATE -> R.string.onboarding_failed_update
    InstallOperation.ROLLBACK -> R.string.onboarding_failed_rollback
    InstallOperation.PREPARE_METAMODULE -> R.string.onboarding_failed_metamodule
}

@StringRes
internal fun VerifyCheck.label(): Int = when (this) {
    VerifyCheck.MODULE_PRESENT -> R.string.onboarding_check_module_present
    VerifyCheck.MODULE_ENABLED -> R.string.onboarding_check_module_enabled
    VerifyCheck.NO_DISABLE_FLAG -> R.string.onboarding_check_no_disable_flag
    VerifyCheck.NO_REMOVE_FLAG -> R.string.onboarding_check_no_remove_flag
    VerifyCheck.PACKAGE_IS_SYSTEM -> R.string.onboarding_check_package_is_system
    VerifyCheck.APK_IN_PRIV_APP -> R.string.onboarding_check_apk_in_priv_app
    VerifyCheck.PRIVILEGED_PERMISSIONS_GRANTED -> R.string.onboarding_check_privileged_grants
}

/** The details pane is evidence, not prose: its words stay stable in every locale. */
private fun VerifyCheck.debugLabel(): String = when (this) {
    VerifyCheck.MODULE_PRESENT -> "Module folder"
    VerifyCheck.MODULE_ENABLED -> "Module enabled"
    VerifyCheck.NO_DISABLE_FLAG -> "No disable flag"
    VerifyCheck.NO_REMOVE_FLAG -> "No remove flag"
    VerifyCheck.PACKAGE_IS_SYSTEM -> "System app"
    VerifyCheck.APK_IN_PRIV_APP -> "APK under /system/priv-app"
    VerifyCheck.PRIVILEGED_PERMISSIONS_GRANTED -> "Privileged grants"
}

@StringRes
internal fun VerifyOutcome.label(): Int = when (this) {
    VerifyOutcome.PASS -> R.string.onboarding_check_passed
    VerifyOutcome.FAIL -> R.string.onboarding_check_failed
    VerifyOutcome.UNKNOWN -> R.string.onboarding_check_not_checked
}

private fun VerifyOutcome.debugLabel(): String = when (this) {
    VerifyOutcome.PASS -> "pass"
    VerifyOutcome.FAIL -> "fail"
    VerifyOutcome.UNKNOWN -> "unknown"
}

@StringRes
internal fun RebootReason.humanLabel(): Int = when (this) {
    RebootReason.MODULE_INSTALL -> R.string.onboarding_reason_module_install
    RebootReason.METAMODULE_INSTALL -> R.string.onboarding_reason_metamodule_install
    RebootReason.MODULE_UPDATE -> R.string.onboarding_reason_module_update
    RebootReason.MODULE_ROLLBACK -> R.string.onboarding_reason_module_rollback
    RebootReason.USER_REQUEST -> R.string.onboarding_reason_user_request
}

@StringRes
internal fun doctorTitle(reason: DoctorReason): Int = when (reason) {
    DoctorReason.VERIFY_FAILED -> R.string.onboarding_doctor_verify_failed
    DoctorReason.MODULE_DISABLED -> R.string.onboarding_doctor_module_disabled
    DoctorReason.MODULE_PENDING_REMOVAL -> R.string.onboarding_doctor_module_pending_removal
    DoctorReason.LINK_TEST_FAILED -> R.string.onboarding_doctor_link_test_failed
}

@StringRes
internal fun doctorMessage(reason: DoctorReason): Int = when (reason) {
    DoctorReason.VERIFY_FAILED -> R.string.onboarding_doctor_verify_failed_message
    DoctorReason.MODULE_DISABLED -> R.string.onboarding_doctor_module_disabled_message
    DoctorReason.MODULE_PENDING_REMOVAL -> R.string.onboarding_doctor_module_pending_removal_message
    DoctorReason.LINK_TEST_FAILED -> R.string.onboarding_doctor_link_test_failed_message
}

@StringRes
internal fun unknownTitle(reason: IndeterminateReason): Int = when (reason) {
    IndeterminateReason.SHELL_TIMED_OUT -> R.string.onboarding_unknown_shell_title
    IndeterminateReason.NO_PROVIDER -> R.string.onboarding_unknown_no_provider_title
    IndeterminateReason.MODULE_UNREADABLE -> R.string.onboarding_unknown_module_title
    IndeterminateReason.OVERLAY_PROBE_FAILED -> R.string.onboarding_unknown_overlay_title
    IndeterminateReason.PROBE_FAILED -> R.string.onboarding_unknown_probe_title
}

@StringRes
internal fun unknownMessage(reason: IndeterminateReason): Int = when (reason) {
    IndeterminateReason.SHELL_TIMED_OUT -> R.string.onboarding_unknown_shell_message
    IndeterminateReason.NO_PROVIDER -> R.string.onboarding_unknown_no_provider_message
    IndeterminateReason.MODULE_UNREADABLE -> R.string.onboarding_unknown_module_message
    IndeterminateReason.OVERLAY_PROBE_FAILED -> R.string.onboarding_unknown_overlay_message
    IndeterminateReason.PROBE_FAILED -> R.string.onboarding_unknown_probe_message
}

@StringRes
internal fun RuntimePermission.humanLabel(): Int = when (this) {
    RuntimePermission.RECORD_AUDIO -> R.string.onboarding_permission_microphone
    RuntimePermission.CALL_PHONE -> R.string.onboarding_permission_calls
    RuntimePermission.READ_PHONE_STATE -> R.string.onboarding_permission_phone_state
    RuntimePermission.MODIFY_AUDIO_SETTINGS -> R.string.onboarding_permission_audio_settings
    RuntimePermission.POST_NOTIFICATIONS -> R.string.onboarding_permission_notifications
    RuntimePermission.ACCESS_LOCAL_NETWORK -> R.string.onboarding_permission_local_network
}

@StringRes
internal fun RuntimePermission.reason(): Int = when (this) {
    RuntimePermission.RECORD_AUDIO -> R.string.onboarding_permission_microphone_reason
    RuntimePermission.CALL_PHONE -> R.string.onboarding_permission_calls_reason
    RuntimePermission.READ_PHONE_STATE -> R.string.onboarding_permission_phone_state_reason
    RuntimePermission.MODIFY_AUDIO_SETTINGS -> R.string.onboarding_permission_audio_settings_reason
    RuntimePermission.POST_NOTIFICATIONS -> R.string.onboarding_permission_notifications_reason
    RuntimePermission.ACCESS_LOCAL_NETWORK -> R.string.onboarding_permission_local_network_reason
}
