package com.phonxiety.bridge.feature.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.data.updates.AppInstallState
import com.phonxiety.bridge.data.updates.CompatibilityNote
import com.phonxiety.bridge.data.updates.ModuleInstallState
import com.phonxiety.bridge.data.updates.UpdateCheck
import com.phonxiety.bridge.data.updates.UpdateRowState
import com.phonxiety.bridge.data.updates.UpdateStatus
import com.phonxiety.bridge.data.updates.UpdateVerdict
import com.phonxiety.bridge.designsystem.LocalPhonxietyType
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ActionEmphasis
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.designsystem.privilegedTitle
import com.phonxiety.bridge.model.FailureKind

@Composable
internal fun CompatibilityCard(verdict: UpdateVerdict) {
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
                        style = LocalPhonxietyType.current.code(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun RowCard(
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
internal fun moduleExtraRows(check: UpdateCheck?): List<Pair<String, String>> = if (check?.installed?.moduleDisabled == true) {
    listOf(
        stringResource(R.string.updates_module_state) to
            stringResource(R.string.updates_module_disabled)
    )
} else {
    emptyList()
}

@Composable
internal fun AppAction(
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
internal fun ModuleAction(
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
 * installer's own output either behind Details or as its own code line. Every card names its
 * own retry, because two identical "Try again" buttons cannot be told apart by a screen reader.
 * Retrying is an install, so a running call blocks it.
 */
@Composable
internal fun InstallFailureCard(
    title: String,
    message: String,
    detail: String?,
    callActive: Boolean,
    blockedCopy: String,
    retryLabel: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    inlineDetail: Boolean = false,
    kind: FailureKind = FailureKind.DAEMON
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ErrorCard(
            title = title,
            message = if (callActive) "$message\n\n$blockedCopy" else message,
            kind = kind,
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
internal fun DaemonCard(row: UpdateRowState) {
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

/** A command is its own code line: selectable, and never inside prose. */
@Composable
internal fun CommandLine(command: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        SelectionContainer {
            Text(
                text = command,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = LocalPhonxietyType.current.code(MaterialTheme.typography.bodySmall)
            )
        }
    }
}
