package com.phonxiety.bridge.feature.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.data.updates.AppInstallState
import com.phonxiety.bridge.data.updates.CompatibilityNote
import com.phonxiety.bridge.data.updates.InstallFailure
import com.phonxiety.bridge.data.updates.ModuleInstallFailure
import com.phonxiety.bridge.data.updates.ModuleInstallState
import com.phonxiety.bridge.data.updates.RollbackBlocker
import com.phonxiety.bridge.data.updates.RollbackFailure
import com.phonxiety.bridge.data.updates.RollbackPendingReason
import com.phonxiety.bridge.data.updates.RollbackPlan
import com.phonxiety.bridge.data.updates.RollbackRecovery
import com.phonxiety.bridge.data.updates.RollbackState
import com.phonxiety.bridge.data.updates.UpdateCheck
import com.phonxiety.bridge.data.updates.UpdateCheckError
import com.phonxiety.bridge.data.updates.UpdateRowState
import com.phonxiety.bridge.data.updates.UpdateStatus
import com.phonxiety.bridge.data.updates.UpdateTarget
import com.phonxiety.bridge.data.updates.UpdateVerdict
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ActionEmphasis
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.ScreenScaffold
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.designsystem.privilegedTitle
import java.text.NumberFormat

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
 */
@Composable
fun UpdatesScreen(
    state: UpdatesUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onInstallApp: () -> Unit,
    onInstallModule: () -> Unit,
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeaderStatus(state)
                    }
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
    // The one command that fixes a missing document is a separate monospace line, shown open.
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

@Composable
private fun CompatibilityCard(verdict: UpdateVerdict) {
    // A note belongs to the card that carries the fix: a blocked row's notes are shown here once,
    // and its own card does not repeat them.
    val problems = verdict.rows.filter { it.status == UpdateStatus.INCOMPATIBLE }.flatMap { it.notes }
    SectionCard(
        title = stringResource(R.string.updates_compatibility_title),
        subtitle = stringResource(R.string.updates_compatibility_subtitle),
        status = {
            if (verdict.compatible) {
                StatusPill(stringResource(R.string.updates_compatibility_ok_pill), StatusTone.POSITIVE)
            } else {
                StatusPill(stringResource(R.string.updates_compatibility_bad_pill), StatusTone.NEGATIVE)
            }
        }
    ) {
        if (problems.isEmpty()) {
            Text(
                text = stringResource(R.string.updates_compatibility_ok),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            problems.forEach { note ->
                NoteItem(note, tone = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun NoteItem(note: CompatibilityNote, tone: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = note.text().asString(),
            style = MaterialTheme.typography.bodySmall,
            color = tone
        )
        if (note is CompatibilityNote.NewPrivilegedGrants) {
            PermissionDetails(note.permissions)
        }
    }
}

/** The raw permission identifiers stay one disclosure below their human titles. */
@Composable
private fun PermissionDetails(permissions: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(
        onClick = { expanded = !expanded },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
    ) {
        Text(
            stringResource(
                if (expanded) R.string.updates_hide_details else R.string.updates_show_details
            )
        )
    }
    if (expanded) {
        Column(
            modifier = Modifier.padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            permissions.forEach { permission ->
                Column {
                    Text(
                        text = privilegedTitle(permission).asString(),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = permission,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RowCard(
    row: UpdateRowState,
    action: @Composable () -> Unit,
    extraRows: List<Pair<String, String>> = emptyList()
) {
    SectionCard(
        title = row.target.title(),
        subtitle = row.target.subtitle(),
        status = { StatusPill(row.status.label(), row.status.tone()) }
    ) {
        KeyValueRow(
            label = stringResource(R.string.updates_row_installed),
            value = row.installedVersion ?: stringResource(R.string.updates_none),
            mono = true
        )
        extraRows.forEach { (label, value) -> KeyValueRow(label = label, value = value) }
        KeyValueRow(
            label = stringResource(R.string.updates_row_available),
            value = row.availableVersion ?: stringResource(R.string.updates_none),
            mono = true
        )
        if (row.status != UpdateStatus.INCOMPATIBLE) {
            row.notes.forEach { note ->
                NoteItem(note, tone = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action()
    }
}

/** The module's own state is its own row: the installed version stays a pure version. */
@Composable
private fun moduleExtraRows(check: UpdateCheck?): List<Pair<String, String>> = if (check?.installed?.moduleDisabled == true) {
    listOf(
        stringResource(R.string.updates_module_state) to
            stringResource(R.string.updates_module_disabled)
    )
} else {
    emptyList()
}

@Composable
private fun AppAction(
    row: UpdateRowState,
    install: AppInstallState,
    callActive: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    when (install) {
        AppInstallState.Idle -> if (row.status == UpdateStatus.UPDATE_AVAILABLE) {
            val version = row.availableVersion
            PrimaryActionButton(
                label = version?.let { stringResource(R.string.updates_install_app, it) }
                    ?: stringResource(R.string.updates_install_app_generic),
                onClick = onInstall,
                enabled = !callActive,
                supportingText = if (callActive) {
                    stringResource(R.string.updates_blocked_call_app)
                } else {
                    stringResource(R.string.updates_install_app_support)
                }
            )
        }

        is AppInstallState.Downloading -> PrimaryActionButton(
            label = install.progress?.let {
                stringResource(R.string.updates_downloading_percent, downloadPercent(it))
            } ?: stringResource(R.string.updates_downloading),
            onClick = {},
            busy = true,
            progress = install.progress
        )

        AppInstallState.Verifying -> PrimaryActionButton(
            label = stringResource(R.string.updates_verifying),
            onClick = {},
            busy = true
        )

        is AppInstallState.Installing -> PrimaryActionButton(
            label = row.availableVersion?.let {
                stringResource(R.string.updates_installing_version, it)
            } ?: stringResource(R.string.updates_installing),
            onClick = {},
            busy = true
        )

        AppInstallState.AwaitingConfirmation -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(stringResource(R.string.updates_awaiting_pill), StatusTone.ACCENT, pulse = true)
            Text(
                text = stringResource(R.string.updates_awaiting_text),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        is AppInstallState.Installed -> Text(
            text = install.versionName?.let {
                stringResource(R.string.updates_installed_done, it)
            } ?: stringResource(R.string.updates_installed_done_unknown),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary
        )

        is AppInstallState.Failed -> InstallFailureCard(
            title = install.code.title(),
            message = install.code.message(),
            detail = install.detail,
            callActive = callActive,
            blockedCopy = stringResource(R.string.updates_blocked_call_app),
            retryLabel = stringResource(R.string.updates_install_again),
            onRetry = onInstall,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun ModuleAction(
    row: UpdateRowState,
    install: ModuleInstallState,
    callActive: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    when (install) {
        ModuleInstallState.Idle -> if (row.status == UpdateStatus.UPDATE_AVAILABLE) {
            val version = row.availableVersion
            PrimaryActionButton(
                label = version?.let { stringResource(R.string.updates_install_module, it) }
                    ?: stringResource(R.string.updates_install_module_generic),
                onClick = onInstall,
                // The payload is the running APK: installing before the app update would rebuild
                // the old module with the old allowlist and send the phone into a reboot for it.
                enabled = !callActive && !row.needsAppFirst,
                emphasis = ActionEmphasis.TONAL,
                supportingText = when {
                    callActive -> stringResource(R.string.updates_blocked_call_module)
                    row.needsAppFirst -> stringResource(R.string.updates_install_module_after_app)
                    row.requiresReboot -> stringResource(R.string.updates_install_module_support_reboot)
                    else -> stringResource(R.string.updates_install_module_support)
                }
            )
        }

        ModuleInstallState.Building -> PrimaryActionButton(
            label = stringResource(R.string.updates_module_building),
            onClick = {},
            busy = true
        )

        ModuleInstallState.Installing -> PrimaryActionButton(
            label = stringResource(R.string.updates_module_installing),
            onClick = {},
            busy = true
        )

        is ModuleInstallState.RebootPending -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(stringResource(R.string.updates_module_reboot_pill), StatusTone.WARNING)
            Text(
                text = install.detail ?: stringResource(R.string.updates_module_reboot_detail),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        is ModuleInstallState.Failed -> InstallFailureCard(
            title = install.code.title(),
            message = install.code.message(),
            detail = install.detail,
            callActive = callActive,
            blockedCopy = stringResource(R.string.updates_blocked_call_module),
            retryLabel = stringResource(R.string.updates_install_module_again),
            onRetry = onInstall,
            onDismiss = onDismiss
        )
    }
}

/**
 * A failure with its retry: the cause first, the next step second, and the provider's or the
 * installer's own output either behind Details or as its own monospace line. Every card names its
 * own retry, because two identical "Try again" buttons cannot be told apart by a screen reader.
 * Retrying is an install, so a running call blocks it.
 */
@Composable
private fun InstallFailureCard(
    title: String,
    message: String,
    detail: String?,
    callActive: Boolean,
    blockedCopy: String,
    retryLabel: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    inlineDetail: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ErrorCard(
            title = title,
            message = if (callActive) "$message\n\n$blockedCopy" else message,
            output = detail.takeUnless { inlineDetail },
            actionLabel = retryLabel.takeUnless { callActive },
            onAction = onRetry.takeUnless { callActive },
            dismissLabel = stringResource(R.string.updates_dismiss),
            onDismiss = onDismiss
        )
        if (inlineDetail) {
            detail?.takeIf { it.isNotBlank() }?.let { CommandLine(it) }
        }
    }
}

@Composable
private fun DaemonCard(row: UpdateRowState) {
    SectionCard(
        title = stringResource(R.string.updates_target_daemon),
        subtitle = stringResource(R.string.updates_target_daemon_subtitle),
        status = { StatusPill(row.status.label(), row.status.tone()) }
    ) {
        KeyValueRow(
            label = stringResource(R.string.updates_row_installed),
            value = row.installedVersion ?: stringResource(R.string.updates_none),
            mono = true
        )
        KeyValueRow(
            label = stringResource(R.string.updates_row_needs_at_least),
            value = row.availableVersion ?: stringResource(R.string.updates_none),
            mono = true
        )
        if (row.status != UpdateStatus.INCOMPATIBLE) {
            row.notes.forEach { note ->
                NoteItem(note, tone = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (row.status == UpdateStatus.UPDATE_AVAILABLE) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.updates_daemon_instructions),
                style = MaterialTheme.typography.bodyMedium
            )
            CommandLine(stringResource(R.string.updates_command_setup))
            CommandLine(stringResource(R.string.updates_command_daemon_install))
        }
    }
}

/** A command is its own monospace line: selectable, and never inside prose. */
@Composable
private fun CommandLine(command: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        SelectionContainer {
            Text(
                text = command,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * The Rollback card: what the phone can go back to, any unfinished rollback from a previous boot,
 * and the one action the plan allows. A plan that cannot work is explained instead of offered.
 *
 * It reads the phone, so it renders — an app-only rollback's progress and failure included — even
 * while the device link is down and the manifest is missing.
 */
@Composable
private fun RollbackSection(
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
private fun ConfirmRollbackDialog(
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

@Composable
private fun UpdateTarget.title(): String = stringResource(
    when (this) {
        UpdateTarget.APP -> R.string.updates_target_app
        UpdateTarget.MODULE -> R.string.updates_target_module
        UpdateTarget.DAEMON -> R.string.updates_target_daemon
    }
)

@Composable
private fun UpdateTarget.subtitle(): String = stringResource(
    when (this) {
        UpdateTarget.APP -> R.string.updates_target_app_subtitle
        UpdateTarget.MODULE -> R.string.updates_target_module_subtitle
        UpdateTarget.DAEMON -> R.string.updates_target_daemon_subtitle
    }
)

@Composable
private fun UpdateStatus.label(): String = stringResource(
    when (this) {
        UpdateStatus.UP_TO_DATE -> R.string.updates_status_up_to_date
        UpdateStatus.UPDATE_AVAILABLE -> R.string.updates_status_update_available
        UpdateStatus.INCOMPATIBLE -> R.string.updates_status_incompatible
        UpdateStatus.UNKNOWN -> R.string.updates_status_unknown
    }
)

private fun UpdateStatus.tone(): StatusTone = when (this) {
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
