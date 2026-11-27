package com.phonxiety.bridge.feature.updates

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.phonxiety.bridge.data.updates.InstallFailure
import com.phonxiety.bridge.data.updates.ModuleInstallFailure
import com.phonxiety.bridge.data.updates.RollbackFailure
import com.phonxiety.bridge.data.updates.UpdateStatus
import com.phonxiety.bridge.data.updates.UpdateTarget
import com.phonxiety.bridge.designsystem.component.StatusTone
import java.text.NumberFormat

@Composable
internal fun UpdateTarget.title(): String = stringResource(
    when (this) {
        UpdateTarget.APP -> R.string.updates_target_app
        UpdateTarget.MODULE -> R.string.updates_target_module
        UpdateTarget.DAEMON -> R.string.updates_target_daemon
    }
)

@Composable
internal fun UpdateTarget.subtitle(): String = stringResource(
    when (this) {
        UpdateTarget.APP -> R.string.updates_target_app_subtitle
        UpdateTarget.MODULE -> R.string.updates_target_module_subtitle
        UpdateTarget.DAEMON -> R.string.updates_target_daemon_subtitle
    }
)

@Composable
internal fun UpdateStatus.label(): String = stringResource(
    when (this) {
        UpdateStatus.UP_TO_DATE -> R.string.updates_status_up_to_date
        UpdateStatus.UPDATE_AVAILABLE -> R.string.updates_status_update_available
        UpdateStatus.INCOMPATIBLE -> R.string.updates_status_incompatible
        UpdateStatus.UNKNOWN -> R.string.updates_status_unknown
    }
)

internal fun UpdateStatus.tone(): StatusTone = when (this) {
    UpdateStatus.UP_TO_DATE -> StatusTone.POSITIVE
    UpdateStatus.UPDATE_AVAILABLE -> StatusTone.WARNING
    UpdateStatus.INCOMPATIBLE -> StatusTone.NEGATIVE
    UpdateStatus.UNKNOWN -> StatusTone.NEUTRAL
}

@Composable
internal fun InstallFailure.title(): String = stringResource(
    when (this) {
        InstallFailure.DOWNLOAD_FAILED -> R.string.updates_install_failed_download_title
        InstallFailure.HASH_MISMATCH -> R.string.updates_install_failed_hash_title
        InstallFailure.SIGNING_CERT_MISMATCH -> R.string.updates_install_failed_cert_title
        InstallFailure.WRONG_PACKAGE -> R.string.updates_install_failed_package_title
        InstallFailure.NOT_AN_APK -> R.string.updates_install_failed_not_apk_title
        InstallFailure.DOWNGRADE_REFUSED -> R.string.updates_install_failed_downgrade_title
        InstallFailure.INSTALL_BLOCKED -> R.string.updates_install_failed_blocked_title
        InstallFailure.INSTALL_ABORTED -> R.string.updates_install_failed_aborted_title
        InstallFailure.INSTALL_CONFLICT -> R.string.updates_install_failed_conflict_title
        InstallFailure.INSTALL_INCOMPATIBLE -> R.string.updates_install_failed_incompatible_title
        InstallFailure.STORAGE_FULL -> R.string.updates_install_failed_storage_title
        InstallFailure.INSTALL_TIMED_OUT -> R.string.updates_install_failed_timeout_title
        InstallFailure.INSTALL_FAILED -> R.string.updates_install_failed_install_title
        InstallFailure.NO_KEPT_APK -> R.string.updates_install_failed_no_kept_title
        InstallFailure.ROOT_UNAVAILABLE -> R.string.updates_install_failed_root_title
        InstallFailure.ROLLBACK_FAILED -> R.string.updates_install_failed_rollback_title
    }
)

@Composable
internal fun InstallFailure.message(): String = stringResource(
    when (this) {
        InstallFailure.DOWNLOAD_FAILED -> R.string.updates_install_failed_download_message
        InstallFailure.HASH_MISMATCH -> R.string.updates_install_failed_hash_message
        InstallFailure.SIGNING_CERT_MISMATCH -> R.string.updates_install_failed_cert_message
        InstallFailure.WRONG_PACKAGE -> R.string.updates_install_failed_package_message
        InstallFailure.NOT_AN_APK -> R.string.updates_install_failed_not_apk_message
        InstallFailure.DOWNGRADE_REFUSED -> R.string.updates_install_failed_downgrade_message
        InstallFailure.INSTALL_BLOCKED -> R.string.updates_install_failed_blocked_message
        InstallFailure.INSTALL_ABORTED -> R.string.updates_install_failed_aborted_message
        InstallFailure.INSTALL_CONFLICT -> R.string.updates_install_failed_conflict_message
        InstallFailure.INSTALL_INCOMPATIBLE -> R.string.updates_install_failed_incompatible_message
        InstallFailure.STORAGE_FULL -> R.string.updates_install_failed_storage_message
        InstallFailure.INSTALL_TIMED_OUT -> R.string.updates_install_failed_timeout_message
        InstallFailure.INSTALL_FAILED -> R.string.updates_install_failed_install_message
        InstallFailure.NO_KEPT_APK -> R.string.updates_install_failed_no_kept_message
        InstallFailure.ROOT_UNAVAILABLE -> R.string.updates_install_failed_root_message
        InstallFailure.ROLLBACK_FAILED -> R.string.updates_install_failed_rollback_message
    }
)

@Composable
internal fun RollbackFailure.title(): String = stringResource(
    when (this) {
        RollbackFailure.NO_KEPT_APK -> R.string.updates_rollback_failed_no_kept_title
        RollbackFailure.NO_PREVIOUS_MODULE -> R.string.updates_rollback_failed_no_module_title
        RollbackFailure.SYSTEM_VERSION_UNKNOWN -> R.string.updates_rollback_failed_system_title
        RollbackFailure.STORAGE_UNREADABLE -> R.string.updates_rollback_failed_storage_title
        RollbackFailure.STALE_ARCHIVE -> R.string.updates_rollback_failed_stale_title
        RollbackFailure.ARCHIVE_MISMATCH -> R.string.updates_rollback_failed_mismatch_title
        RollbackFailure.APP_NOT_NEWER -> R.string.updates_rollback_failed_app_not_newer_title
        RollbackFailure.PREPARATION_FAILED -> R.string.updates_rollback_failed_preparation_title
        RollbackFailure.MODULE_STAGE_FAILED -> R.string.updates_rollback_failed_stage_title
        RollbackFailure.REMOVAL_NOT_STARTED -> R.string.updates_rollback_failed_removal_title
    }
)

@Composable
internal fun RollbackFailure.message(): String = stringResource(
    when (this) {
        RollbackFailure.NO_KEPT_APK -> R.string.updates_rollback_failed_no_kept_message
        RollbackFailure.NO_PREVIOUS_MODULE -> R.string.updates_rollback_failed_no_module_message
        RollbackFailure.SYSTEM_VERSION_UNKNOWN -> R.string.updates_rollback_failed_system_message
        RollbackFailure.STORAGE_UNREADABLE -> R.string.updates_rollback_failed_storage_message
        RollbackFailure.STALE_ARCHIVE -> R.string.updates_rollback_failed_stale_message
        RollbackFailure.ARCHIVE_MISMATCH -> R.string.updates_rollback_failed_mismatch_message
        RollbackFailure.APP_NOT_NEWER -> R.string.updates_rollback_failed_app_not_newer_message
        RollbackFailure.PREPARATION_FAILED -> R.string.updates_rollback_failed_preparation_message
        RollbackFailure.MODULE_STAGE_FAILED -> R.string.updates_rollback_failed_stage_message
        RollbackFailure.REMOVAL_NOT_STARTED -> R.string.updates_rollback_failed_removal_message
    }
)

@Composable
internal fun ModuleInstallFailure.title(): String = stringResource(
    when (this) {
        ModuleInstallFailure.BUILD_FAILED -> R.string.updates_module_failed_build_title
        ModuleInstallFailure.ROOT_UNAVAILABLE -> R.string.updates_module_failed_root_title
        ModuleInstallFailure.PROVIDER_CONFLICT -> R.string.updates_module_failed_conflict_title
        ModuleInstallFailure.INSTALL_FAILED -> R.string.updates_module_failed_install_title
        ModuleInstallFailure.REBOOT_FAILED -> R.string.updates_module_failed_reboot_title
    }
)

@Composable
internal fun ModuleInstallFailure.message(): String = stringResource(
    when (this) {
        ModuleInstallFailure.BUILD_FAILED -> R.string.updates_module_failed_build_message
        ModuleInstallFailure.ROOT_UNAVAILABLE -> R.string.updates_module_failed_root_message
        ModuleInstallFailure.PROVIDER_CONFLICT -> R.string.updates_module_failed_conflict_message
        ModuleInstallFailure.INSTALL_FAILED -> R.string.updates_module_failed_install_message
        ModuleInstallFailure.REBOOT_FAILED -> R.string.updates_module_failed_reboot_message
    }
)

/** `78%` in English and the locale's own percent form elsewhere, with no space before the sign. */
internal fun downloadPercent(fraction: Float): String {
    val format = NumberFormat.getPercentInstance()
    format.maximumFractionDigits = 0
    return format.format(fraction.coerceIn(0f, 1f))
}
