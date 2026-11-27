package com.phonxiety.bridge.feature.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.data.updates.AppInstallState
import com.phonxiety.bridge.data.updates.RollbackBlocker
import com.phonxiety.bridge.data.updates.RollbackPendingReason
import com.phonxiety.bridge.data.updates.RollbackPlan
import com.phonxiety.bridge.data.updates.RollbackRecovery
import com.phonxiety.bridge.data.updates.RollbackState
import com.phonxiety.bridge.designsystem.component.ActionEmphasis
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone

/**
 * The Rollback card: what the phone can go back to, any unfinished rollback from a previous boot,
 * and the one action the plan allows. A plan that cannot work is explained instead of offered.
 *
 * It reads the phone, so it renders — an app-only rollback's progress and failure included — even
 * while the device link is down and the manifest is missing.
 */
@Composable
internal fun RollbackSection(
    plan: RollbackPlan?,
    recovery: RollbackRecovery?,
    recoveryDismissed: Boolean,
    rollback: RollbackState,
    appInstall: AppInstallState,
    callActive: Boolean,
    onRollback: () -> Unit,
    onDismiss: () -> Unit,
    onDismissRecovery: () -> Unit
) {
    val shownRecovery = recovery?.takeUnless { recoveryDismissed }
    // Action ownership comes from the recovery itself, never from whether its notice is visible:
    // dismissing the notice must not expose a fresh plan while the recorded rollback is pending.
    val pending = recovery as? RollbackRecovery.Pending
    val silentBlocker = plan is RollbackPlan.Unavailable &&
        (plan.blocker == RollbackBlocker.NO_KEPT_APK || plan.blocker == RollbackBlocker.APP_NOT_NEWER)
    val nothingToOffer = recovery == null && appInstall is AppInstallState.Idle &&
        (plan == null || silentBlocker)
    if (nothingToOffer) return

    SectionCard(
        title = stringResource(R.string.updates_rollback_title),
        // A recorded rollback speaks for itself; a plan subtitle would describe a different plan.
        subtitle = rollbackSubtitle(plan).takeIf { shownRecovery == null }
    ) {
        // While the detached script owns the removal, the journal's module_staged step is expected.
        if (rollback !is RollbackState.Started) {
            shownRecovery?.let {
                RecoveryBlock(recovery = it, callActive = callActive, onRetry = onRollback, onDismiss = onDismissRecovery)
            }
        }
        // A recorded rollback owns the next step: retrying finishes it, so a fresh plan — which may
        // now say "nothing is kept" after an app-data clear — is neither explained nor offered.
        if (pending == null) {
            plan?.let { PlanExplanation(it) }
        }
        ActionState(
            plan = plan.takeIf { pending == null },
            rollback = rollback,
            appInstall = appInstall,
            callActive = callActive,
            onRollback = onRollback,
            onDismiss = onDismiss
        )
        // A dismissed notice hides the explanation, not the retry that finishes the journal.
        if (pending != null && shownRecovery == null && rollback == RollbackState.Idle && appInstall is AppInstallState.Idle) {
            PrimaryActionButton(
                label = stringResource(R.string.updates_rollback_finish),
                onClick = onRollback,
                enabled = !callActive,
                emphasis = ActionEmphasis.TEXT,
                supportingText = stringResource(R.string.updates_blocked_call_rollback).takeIf { callActive }
            )
        }
    }
}

@Composable
private fun PlanExplanation(plan: RollbackPlan) {
    when (plan) {
        is RollbackPlan.AppOnly -> Text(
            text = stringResource(R.string.updates_rollback_app_only_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        is RollbackPlan.WithPreviousModule -> Text(
            text = stringResource(
                R.string.updates_rollback_with_module_explanation,
                plan.moduleVersionName ?: plan.moduleVersionCode.toString(),
                plan.previousVersionName ?: plan.previousVersionCode.toString()
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        is RollbackPlan.Unavailable -> Text(
            text = plan.blocker.explanation(plan),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActionState(
    plan: RollbackPlan?,
    rollback: RollbackState,
    appInstall: AppInstallState,
    callActive: Boolean,
    onRollback: () -> Unit,
    onDismiss: () -> Unit
) {
    when {
        appInstall is AppInstallState.Failed -> InstallFailureCard(
            title = appInstall.code.title(),
            message = appInstall.code.message(),
            detail = appInstall.detail,
            callActive = callActive,
            blockedCopy = stringResource(R.string.updates_blocked_call_rollback_app),
            retryLabel = stringResource(R.string.updates_reinstall_again),
            onRetry = onRollback,
            onDismiss = onDismiss,
            inlineDetail = true
        )

        appInstall.busy -> PrimaryActionButton(
            label = stringResource(R.string.updates_rollback_reinstalling),
            onClick = {},
            busy = true
        )

        appInstall is AppInstallState.Installed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(stringResource(R.string.updates_rollback_done_pill), StatusTone.POSITIVE)
            Text(
                text = stringResource(
                    R.string.updates_rollback_done,
                    appInstall.versionName ?: stringResource(R.string.updates_rollback_previous_app)
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        else -> when (rollback) {
            RollbackState.Idle -> when (plan) {
                is RollbackPlan.AppOnly -> PrimaryActionButton(
                    label = stringResource(R.string.updates_rollback_action_app),
                    onClick = onRollback,
                    enabled = !callActive,
                    emphasis = ActionEmphasis.TEXT,
                    supportingText = stringResource(R.string.updates_blocked_call_rollback_app).takeIf { callActive }
                )

                is RollbackPlan.WithPreviousModule -> PrimaryActionButton(
                    label = stringResource(
                        R.string.updates_rollback_action_module,
                        plan.previousVersionName ?: plan.previousVersionCode.toString()
                    ),
                    onClick = onRollback,
                    enabled = !callActive,
                    supportingText = if (callActive) {
                        stringResource(R.string.updates_blocked_call_rollback)
                    } else {
                        stringResource(R.string.updates_rollback_support_module)
                    }
                )

                is RollbackPlan.Unavailable, null -> Unit
            }

            RollbackState.Preparing -> PrimaryActionButton(
                label = stringResource(R.string.updates_rollback_preparing),
                onClick = {},
                busy = true
            )

            RollbackState.StagingModule -> PrimaryActionButton(
                label = stringResource(R.string.updates_rollback_staging),
                onClick = {},
                busy = true
            )

            RollbackState.StartingRemoval -> PrimaryActionButton(
                label = stringResource(R.string.updates_rollback_starting),
                onClick = {},
                busy = true
            )

            is RollbackState.Started -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(stringResource(R.string.updates_rollback_started_pill), StatusTone.WARNING)
                Text(
                    text = rollback.versionName?.let {
                        stringResource(R.string.updates_rollback_started_text, it)
                    } ?: stringResource(R.string.updates_rollback_started_previous),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.updates_dismiss)) }
            }

            is RollbackState.Failed -> InstallFailureCard(
                title = rollback.code.title(),
                message = rollback.code.message(),
                detail = rollback.detail,
                callActive = callActive,
                blockedCopy = stringResource(R.string.updates_blocked_call_rollback),
                retryLabel = stringResource(R.string.updates_rollback_again),
                onRetry = onRollback,
                onDismiss = onDismiss,
                inlineDetail = true
            )
        }
    }
}

@Composable
private fun RecoveryBlock(
    recovery: RollbackRecovery,
    callActive: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    when (recovery) {
        is RollbackRecovery.Completed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(stringResource(R.string.updates_rollback_done_pill), StatusTone.POSITIVE)
            Text(
                text = stringResource(
                    R.string.updates_rollback_completed,
                    recovery.versionName ?: stringResource(R.string.updates_rollback_previous_version)
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        is RollbackRecovery.Pending -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(stringResource(R.string.updates_rollback_unfinished_pill), StatusTone.WARNING)
            Text(text = recovery.sentence(), style = MaterialTheme.typography.bodyMedium)
            recovery.detail?.takeIf { it.isNotBlank() }?.let { CommandLine(it) }
            Text(
                text = stringResource(R.string.updates_rollback_pending_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry, enabled = !callActive) {
                    Text(stringResource(R.string.updates_rollback_finish))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.updates_dismiss)) }
            }
            if (callActive) {
                Text(
                    text = stringResource(R.string.updates_blocked_call_rollback),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Both rollbacks change the app under the running process, so both ask before they start. */
@Composable
internal fun ConfirmRollbackDialog(
    plan: RollbackPlan,
    runningVersion: String?,
    callActive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    when (plan) {
        is RollbackPlan.AppOnly -> {
            val kept = plan.keptVersionName ?: plan.keptVersionCode.toString()
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.updates_dialog_app_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = runningVersion?.let {
                                stringResource(R.string.updates_dialog_app_body, it, kept)
                            } ?: stringResource(R.string.updates_dialog_app_body_unknown, kept)
                        )
                        if (callActive) {
                            Text(
                                text = stringResource(R.string.updates_dialog_call_blocked),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onConfirm, enabled = !callActive) {
                        Text(stringResource(R.string.updates_dialog_app_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.updates_cancel)) }
                }
            )
        }

        is RollbackPlan.WithPreviousModule -> {
            val previous = plan.previousVersionName ?: plan.previousVersionCode.toString()
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.updates_dialog_module_title, previous)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.updates_dialog_module_body, previous))
                        Text(
                            text = stringResource(
                                R.string.updates_dialog_module_return,
                                plan.moduleVersionName ?: plan.moduleVersionCode.toString()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.updates_dialog_module_data),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.updates_dialog_module_call),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        if (callActive) {
                            Text(
                                text = stringResource(R.string.updates_dialog_call_blocked),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onConfirm, enabled = !callActive) {
                        Text(stringResource(R.string.updates_dialog_module_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.updates_cancel)) }
                }
            )
        }

        is RollbackPlan.Unavailable -> Unit
    }
}

@Composable
private fun rollbackSubtitle(plan: RollbackPlan?): String = when (plan) {
    is RollbackPlan.WithPreviousModule -> stringResource(R.string.updates_rollback_subtitle_one_step)
    is RollbackPlan.AppOnly -> stringResource(R.string.updates_rollback_subtitle_app_only)
    is RollbackPlan.Unavailable -> stringResource(R.string.updates_rollback_subtitle_unavailable)
    null -> stringResource(R.string.updates_rollback_subtitle_reading)
}

@Composable
private fun RollbackBlocker.explanation(plan: RollbackPlan.Unavailable): String {
    val system = plan.moduleVersion ?: plan.moduleVersionCode?.toString()
        ?: stringResource(R.string.updates_rollback_newer_module)
    val kept = plan.keptVersionName ?: plan.keptVersionCode.toString()
    return when (this) {
        RollbackBlocker.NO_KEPT_APK -> stringResource(R.string.updates_blocker_no_kept_apk)

        RollbackBlocker.NO_PREVIOUS_MODULE ->
            stringResource(R.string.updates_blocker_no_previous_module, system, kept)

        RollbackBlocker.STORAGE_UNREADABLE -> stringResource(R.string.updates_blocker_storage_unreadable)

        RollbackBlocker.SYSTEM_VERSION_UNKNOWN ->
            stringResource(R.string.updates_blocker_system_version_unknown)

        RollbackBlocker.STALE_ARCHIVE -> stringResource(R.string.updates_blocker_stale_archive, system)

        RollbackBlocker.ARCHIVE_MISMATCH -> stringResource(R.string.updates_blocker_archive_mismatch)

        RollbackBlocker.APP_NOT_NEWER -> stringResource(R.string.updates_blocker_app_not_newer)
    }
}

@Composable
private fun RollbackRecovery.Pending.sentence(): String = when (reason) {
    RollbackPendingReason.REMOVAL_NOT_STARTED ->
        stringResource(R.string.updates_pending_removal_not_started)

    RollbackPendingReason.REMOVAL_FAILED -> stringResource(R.string.updates_pending_removal_failed)

    RollbackPendingReason.REMOVAL_UNVERIFIED -> stringResource(R.string.updates_pending_removal_unverified)

    RollbackPendingReason.OLD_APP_NOT_SELECTED -> stringResource(R.string.updates_pending_old_app)

    RollbackPendingReason.VERSION_MISMATCH -> stringResource(R.string.updates_pending_version_mismatch)
}
