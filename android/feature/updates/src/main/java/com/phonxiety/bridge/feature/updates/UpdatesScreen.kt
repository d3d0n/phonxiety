package com.phonxiety.bridge.feature.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.data.updates.UpdateCheck
import com.phonxiety.bridge.data.updates.UpdateCheckError
import com.phonxiety.bridge.data.updates.UpdateRowState
import com.phonxiety.bridge.data.updates.UpdateTarget
import com.phonxiety.bridge.data.updates.UpdateVerdict
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.ScreenScaffold
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone

/** The Updates entry point; the app host only supplies navigation. */
@Composable
fun UpdatesRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UpdatesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    UpdatesScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onInstallApp = viewModel::installApp,
        onInstallModule = viewModel::installModule,
        onConfirmUpdate = viewModel::confirmUpdate,
        onDismissUpdate = viewModel::dismissUpdateConfirmation,
        onRollback = viewModel::rollback,
        onConfirmRollback = viewModel::confirmRollback,
        onDismissRollback = viewModel::dismissRollbackConfirmation,
        onDismissRollbackRecovery = viewModel::dismissRollbackRecovery,
        onDismiss = viewModel::dismiss,
        modifier = modifier
    )
}

/**
 * App, Module and Daemon rows: installed against available, the compatibility verdict and exactly
 * one action per row. The daemon is not installed from the phone, so its row explains what to run
 * on the computer instead of pretending to have a button. While a call runs, every action that
 * would end it is disabled and says why; the view model refuses the same actions again.
 *
 * An install replaces the running app or reboots the phone, so tapping its action asks first: the
 * pending target lives in the view model's state, not in this screen, and survives a recreation.
 */
@Composable
fun UpdatesScreen(
    state: UpdatesUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onInstallApp: () -> Unit,
    onInstallModule: () -> Unit,
    onConfirmUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onRollback: () -> Unit,
    onConfirmRollback: () -> Unit,
    onDismissRollback: () -> Unit,
    onDismissRollbackRecovery: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        ScreenScaffold(
            title = stringResource(R.string.updates_title),
            onBack = onBack
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // The app bar holds only the title: a status pill there squeezes the title into a
                // column of single letters once the font scale doubles. The pill appears only for
                // the check itself — with a verdict, each row and the compatibility card already
                // carry their status, and repeating "Incompatible" here would say it twice.
                if (state.loading || state.check?.verdict == null) {
                    HeaderStatus(state)
                }
                if (state.callActive) {
                    Text(
                        text = stringResource(R.string.updates_call_active_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val check = state.check
                val verdict = check?.verdict
                when {
                    state.loading && check == null -> LoadingState(stringResource(R.string.updates_loading))

                    check?.error != null -> CheckErrorCard(check, onRefresh)

                    verdict != null -> {
                        CompatibilityCard(verdict)
                        RowCard(
                            row = verdict.app,
                            action = {
                                AppAction(
                                    row = verdict.app,
                                    install = state.appInstall,
                                    callActive = state.callActive,
                                    onInstall = onInstallApp,
                                    onDismiss = onDismiss
                                )
                            }
                        )
                        RowCard(
                            row = verdict.module,
                            extraRows = moduleExtraRows(check),
                            action = {
                                ModuleAction(
                                    row = verdict.module,
                                    install = state.moduleInstall,
                                    callActive = state.callActive,
                                    onInstall = onInstallModule,
                                    onDismiss = onDismiss
                                )
                            }
                        )
                        DaemonCard(verdict.daemon)
                        TextButton(onClick = onRefresh) {
                            Text(stringResource(R.string.updates_check_again))
                        }
                    }

                    else -> EmptyState(
                        title = stringResource(R.string.updates_empty_title),
                        message = stringResource(R.string.updates_empty_message),
                        action = {
                            TextButton(onClick = onRefresh) {
                                Text(stringResource(R.string.updates_check_again))
                            }
                        }
                    )
                }
                // The rollback reads the phone, not the daemon, so it stays usable when the check fails.
                RollbackSection(
                    plan = state.rollbackPlan,
                    recovery = state.rollbackRecovery,
                    recoveryDismissed = state.rollbackRecoveryDismissed,
                    rollback = state.moduleRollback,
                    appInstall = state.rollbackAppInstall,
                    callActive = state.callActive,
                    onRollback = onRollback,
                    onDismiss = onDismiss,
                    onDismissRecovery = onDismissRollbackRecovery
                )
            }
        }
        state.updateConfirmation?.let { target ->
            ConfirmUpdateDialog(
                target = target,
                row = target.row(state.check?.verdict),
                onConfirm = onConfirmUpdate,
                onDismiss = onDismissUpdate
            )
        }
        state.rollbackConfirmation?.let { confirmation ->
            ConfirmRollbackDialog(
                plan = confirmation,
                runningVersion = state.check?.installed?.appVersion,
                callActive = state.callActive,
                onConfirm = onConfirmRollback,
                onDismiss = onDismissRollback
            )
        }
    }
}

/** The check's own state, shown only while there is no verdict to read the rows from. */
@Composable
private fun HeaderStatus(state: UpdatesUiState) {
    when {
        state.loading ->
            StatusPill(stringResource(R.string.updates_status_checking), StatusTone.NEUTRAL, pulse = true)

        state.check?.error != null ->
            StatusPill(stringResource(R.string.updates_status_check_failed), StatusTone.NEGATIVE)

        else -> StatusPill(stringResource(R.string.updates_status_unknown), StatusTone.NEUTRAL)
    }
}

@Composable
private fun CheckErrorCard(check: UpdateCheck, onRefresh: () -> Unit) {
    val title = when (check.error) {
        UpdateCheckError.LINK_DOWN -> stringResource(R.string.updates_error_link_title)
        UpdateCheckError.NO_MANIFEST -> stringResource(R.string.updates_error_no_manifest_title)
        UpdateCheckError.UNKNOWN_CHANNEL -> stringResource(R.string.updates_error_channel_title)
        UpdateCheckError.MANIFEST_INVALID -> stringResource(R.string.updates_error_invalid_title)
        null -> stringResource(R.string.updates_error_unknown_title)
    }
    val message = when (check.error) {
        UpdateCheckError.LINK_DOWN -> stringResource(R.string.updates_error_link_message)
        UpdateCheckError.NO_MANIFEST -> stringResource(R.string.updates_error_no_manifest_message)
        UpdateCheckError.UNKNOWN_CHANNEL -> stringResource(R.string.updates_error_channel_message)
        UpdateCheckError.MANIFEST_INVALID -> stringResource(R.string.updates_error_invalid_message)
        null -> stringResource(R.string.updates_error_unknown_message)
    }
    // The one command that fixes a missing document is a separate code line, shown open.
    val command = check.error == UpdateCheckError.NO_MANIFEST
    ErrorCard(
        title = title,
        message = message,
        output = if (command) stringResource(R.string.updates_command_publish) else check.detail,
        initiallyExpanded = command,
        actionLabel = stringResource(R.string.updates_check_again),
        onAction = onRefresh
    )
}

/**
 * Both installs replace the running app or reboot the phone, so both ask first. The dialog names the
 * exact versions it changes, because two rows can offer different ones.
 */
@Composable
private fun ConfirmUpdateDialog(
    target: UpdateTarget,
    row: UpdateRowState?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (row == null) return
    val installed = row.installedVersion
    val available = row.availableVersion
    when (target) {
        UpdateTarget.APP -> AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    available?.let { stringResource(R.string.updates_dialog_update_app_title, it) }
                        ?: stringResource(R.string.updates_dialog_update_app_title_generic)
                )
            },
            text = {
                Text(
                    if (installed != null && available != null) {
                        stringResource(R.string.updates_dialog_update_app_body, installed, available)
                    } else {
                        stringResource(R.string.updates_dialog_update_app_body_unknown)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.updates_dialog_update_app_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.updates_cancel)) }
            }
        )

        UpdateTarget.MODULE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    available?.let { stringResource(R.string.updates_dialog_update_module_title, it) }
                        ?: stringResource(R.string.updates_dialog_update_module_title_generic)
                )
            },
            text = {
                Text(
                    stringResource(
                        if (row.requiresReboot) {
                            R.string.updates_dialog_update_module_body_reboot
                        } else {
                            R.string.updates_dialog_update_module_body
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.updates_dialog_update_module_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.updates_cancel)) }
            }
        )

        UpdateTarget.DAEMON -> Unit
    }
}

/** The row a target's install belongs to; the daemon has none, because this phone cannot install it. */
internal fun UpdateTarget.row(verdict: UpdateVerdict?): UpdateRowState? = when (this) {
    UpdateTarget.APP -> verdict?.app
    UpdateTarget.MODULE -> verdict?.module
    UpdateTarget.DAEMON -> null
}
