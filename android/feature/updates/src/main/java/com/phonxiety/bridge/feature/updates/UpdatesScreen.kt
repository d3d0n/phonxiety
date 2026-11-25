package com.phonxiety.bridge.feature.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.data.updates.AppInstallState
import com.phonxiety.bridge.data.updates.InstallFailure
import com.phonxiety.bridge.data.updates.ModuleInstallFailure
import com.phonxiety.bridge.data.updates.ModuleInstallState
import com.phonxiety.bridge.data.updates.UpdateCheck
import com.phonxiety.bridge.data.updates.UpdateCheckError
import com.phonxiety.bridge.data.updates.UpdateRowState
import com.phonxiety.bridge.data.updates.UpdateStatus
import com.phonxiety.bridge.data.updates.UpdateTarget
import com.phonxiety.bridge.data.updates.UpdateVerdict
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.SectionCard
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
        onRollback = viewModel::rollback,
        onDismiss = viewModel::dismiss,
        modifier = modifier
    )
}

/**
 * App, Module and Daemon rows: installed against available, the compatibility verdict and exactly
 * one action per row. The daemon is not installed from the phone, so its row explains what to run
 * on the computer instead of pretending to have a button.
 */
@Composable
fun UpdatesScreen(
    state: UpdatesUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onInstallApp: () -> Unit,
    onInstallModule: () -> Unit,
    onRollback: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Header(state.check?.verdict, loading = state.loading || state.check == null, onBack = onBack)
            val check = state.check
            val verdict = check?.verdict
            when {
                state.loading && check == null -> LoadingState("Checking versions…")

                check?.error != null -> CheckErrorCard(check, onRefresh)

                verdict != null -> {
                    CompatibilityCard(verdict)
                    RowCard(
                        row = verdict.app,
                        installedExtra = null,
                        action = {
                            AppAction(
                                row = verdict.app,
                                install = state.appInstall,
                                onInstall = onInstallApp,
                                onDismiss = onDismiss
                            )
                        }
                    )
                    RowCard(
                        row = verdict.module,
                        installedExtra = check.installed?.let { installed ->
                            if (installed.moduleDisabled) "disabled" else null
                        },
                        action = {
                            ModuleAction(
                                row = verdict.module,
                                install = state.moduleInstall,
                                onInstall = onInstallModule,
                                onDismiss = onDismiss
                            )
                        }
                    )
                    DaemonCard(verdict.daemon)
                    if (state.rollbackAvailable && !state.appInstall.busy) {
                        RollbackCard(onRollback)
                    }
                    TextButton(onClick = onRefresh) { Text("Check again") }
                }

                else -> EmptyState(
                    title = "No update document",
                    message = "The daemon answered without a release to compare against."
                )
            }
        }
    }
}

@Composable
private fun Header(verdict: UpdateVerdict?, loading: Boolean, onBack: () -> Unit) {
    Column {
        TextButton(onClick = onBack, modifier = Modifier.padding(bottom = 4.dp)) { Text("Back") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Updates", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "App, module and daemon",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                loading -> StatusPill("Checking", StatusTone.NEUTRAL, pulse = true)
                verdict == null -> StatusPill("No data", StatusTone.NEUTRAL)
                !verdict.compatible -> StatusPill("Incompatible", StatusTone.NEGATIVE)
                verdict.hasUpdate -> StatusPill("Update available", StatusTone.WARNING)
                else -> StatusPill("Up to date", StatusTone.POSITIVE)
            }
        }
    }
}

@Composable
private fun CheckErrorCard(check: UpdateCheck, onRefresh: () -> Unit) {
    val (title, message) = when (check.error) {
        UpdateCheckError.LINK_DOWN ->
            "The daemon did not answer" to "The update document arrives over the device link. Check the dashboard, then try again."

        UpdateCheckError.NO_MANIFEST ->
            "No update document is published" to "On the computer run `mise run phone:publish` to write update.json, then check again."

        UpdateCheckError.UNKNOWN_CHANNEL ->
            "No release on this channel" to "The document has no release under the channel this app asked for."

        UpdateCheckError.MANIFEST_INVALID ->
            "The update document cannot be used" to "It exists but a required field is missing or malformed, so nothing is offered."

        null -> "The check failed" to "No reason was reported."
    }
    ErrorCard(
        title = title,
        message = message,
        output = check.detail,
        actionLabel = "Try again",
        onAction = onRefresh
    )
}

@Composable
private fun CompatibilityCard(verdict: UpdateVerdict) {
    val notes = verdict.rows.filter { it.status == UpdateStatus.INCOMPATIBLE }.flatMap { it.notes }
    SectionCard(
        title = "Compatibility",
        subtitle = "Protocol, app API and module schema",
        status = {
            if (verdict.compatible) {
                StatusPill("Compatible", StatusTone.POSITIVE)
            } else {
                StatusPill("Incompatible", StatusTone.NEGATIVE)
            }
        }
    ) {
        if (notes.isEmpty()) {
            Text(
                text = "This phone, the module and the daemon agree on every version this release needs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            notes.forEach { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun RowCard(
    row: UpdateRowState,
    installedExtra: String?,
    action: @Composable () -> Unit
) {
    SectionCard(
        title = row.target.title(),
        subtitle = row.target.subtitle(),
        status = { StatusPill(row.status.label(), row.status.tone()) }
    ) {
        KeyValueRow(
            label = "Installed",
            value = listOfNotNull(row.installedVersion, installedExtra).joinToString(" · ").ifEmpty { "—" },
            mono = true
        )
        KeyValueRow(label = "Available", value = row.availableVersion ?: "—", mono = true)
        row.notes.forEach { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        action()
    }
}

@Composable
private fun AppAction(
    row: UpdateRowState,
    install: AppInstallState,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    when (install) {
        AppInstallState.Idle -> if (row.status == UpdateStatus.UPDATE_AVAILABLE) {
            PrimaryActionButton(
                label = "Install ${row.availableVersion ?: "the update"}",
                onClick = onInstall,
                supportingText = "The SHA-256 and the signing certificate are checked before the install."
            )
        }

        is AppInstallState.Downloading -> PrimaryActionButton(
            label = "Downloading…",
            onClick = {},
            busy = true,
            progress = install.progress
        )

        AppInstallState.Verifying -> PrimaryActionButton(label = "Checking the download…", onClick = {}, busy = true)

        is AppInstallState.Installing -> PrimaryActionButton(label = "Installing…", onClick = {}, busy = true)

        AppInstallState.AwaitingConfirmation -> PrimaryActionButton(
            label = "Confirm in the system dialog",
            onClick = {},
            busy = true,
            supportingText = "Finish the update in the dialog the system opened."
        )

        is AppInstallState.Installed -> Text(
            text = "Installed ${install.versionName ?: "the update"}. The service restarts by itself.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary
        )

        is AppInstallState.Failed -> ErrorCard(
            title = install.code.title(),
            message = install.detail ?: install.code.message(),
            actionLabel = "Dismiss",
            onAction = onDismiss
        )
    }
}

@Composable
private fun ModuleAction(
    row: UpdateRowState,
    install: ModuleInstallState,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    when (install) {
        ModuleInstallState.Idle -> if (row.status == UpdateStatus.UPDATE_AVAILABLE) {
            PrimaryActionButton(
                label = "Install module ${row.availableVersion ?: ""}".trim(),
                onClick = onInstall,
                supportingText = if (row.requiresReboot) {
                    "The module is rebuilt from this build and installed as root; a reboot follows."
                } else {
                    "The module is rebuilt from this build and installed as root."
                }
            )
        }

        ModuleInstallState.Building -> PrimaryActionButton(label = "Rebuilding the module…", onClick = {}, busy = true)

        ModuleInstallState.Installing -> PrimaryActionButton(label = "Installing the module…", onClick = {}, busy = true)

        is ModuleInstallState.RebootPending -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill("Reboot needed", StatusTone.WARNING)
            Text(
                text = install.detail ?: "The module is installed. Reboot this phone to mount the new overlay.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        is ModuleInstallState.Failed -> ErrorCard(
            title = install.code.title(),
            message = install.detail ?: install.code.message(),
            actionLabel = "Dismiss",
            onAction = onDismiss
        )
    }
}

@Composable
private fun DaemonCard(row: UpdateRowState) {
    SectionCard(
        title = "Daemon",
        subtitle = "Runs on your computer; this phone cannot install it",
        status = { StatusPill(row.status.label(), row.status.tone()) }
    ) {
        KeyValueRow(label = "Installed", value = row.installedVersion ?: "—", mono = true)
        KeyValueRow(label = "Needs at least", value = row.availableVersion ?: "—", mono = true)
        row.notes.forEach { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (row.status == UpdateStatus.UPDATE_AVAILABLE) {
            HorizontalDivider()
            Text(
                text = "On your computer: run `mise run setup`, then restart the daemon with `mise run daemon:install`.",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun RollbackCard(onRollback: () -> Unit) {
    SectionCard(
        title = "Rollback",
        subtitle = "The previous app is kept on this phone"
    ) {
        Text(
            text = "The APK replaced by the last update is kept in app files. Reinstalling it is a downgrade, " +
                "which needs root.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onRollback) { Text("Reinstall the previous app") }
    }
}

private fun UpdateTarget.title(): String = when (this) {
    UpdateTarget.APP -> "App"
    UpdateTarget.MODULE -> "Module"
    UpdateTarget.DAEMON -> "Daemon"
}

private fun UpdateTarget.subtitle(): String = when (this) {
    UpdateTarget.APP -> "The APK on this phone"
    UpdateTarget.MODULE -> "The privileged module and its allowlist"
    UpdateTarget.DAEMON -> "Runs on your computer"
}

private fun UpdateStatus.label(): String = when (this) {
    UpdateStatus.UP_TO_DATE -> "Up to date"
    UpdateStatus.UPDATE_AVAILABLE -> "Update available"
    UpdateStatus.INCOMPATIBLE -> "Incompatible"
    UpdateStatus.UNKNOWN -> "Unknown"
}

private fun UpdateStatus.tone(): StatusTone = when (this) {
    UpdateStatus.UP_TO_DATE -> StatusTone.POSITIVE
    UpdateStatus.UPDATE_AVAILABLE -> StatusTone.WARNING
    UpdateStatus.INCOMPATIBLE -> StatusTone.NEGATIVE
    UpdateStatus.UNKNOWN -> StatusTone.NEUTRAL
}

internal fun InstallFailure.title(): String = when (this) {
    InstallFailure.DOWNLOAD_FAILED -> "The download failed"
    InstallFailure.HASH_MISMATCH -> "The download is not the published file"
    InstallFailure.SIGNING_CERT_MISMATCH -> "The update is signed with a different key"
    InstallFailure.WRONG_PACKAGE -> "The file is a different app"
    InstallFailure.NOT_AN_APK -> "The file is not a readable APK"
    InstallFailure.DOWNGRADE_REFUSED -> "The file is older than the installed app"
    InstallFailure.INSTALL_BLOCKED -> "The system blocked the install"
    InstallFailure.INSTALL_ABORTED -> "The install was cancelled"
    InstallFailure.INSTALL_CONFLICT -> "The install conflicted with the installed app"
    InstallFailure.INSTALL_INCOMPATIBLE -> "The update does not fit this phone"
    InstallFailure.STORAGE_FULL -> "There is not enough free space"
    InstallFailure.INSTALL_TIMED_OUT -> "The installer did not answer"
    InstallFailure.INSTALL_FAILED -> "The install failed"
    InstallFailure.NO_KEPT_APK -> "No previous app is kept"
    InstallFailure.ROOT_UNAVAILABLE -> "Root is not available"
    InstallFailure.ROLLBACK_FAILED -> "The rollback failed"
}

internal fun InstallFailure.message(): String = when (this) {
    InstallFailure.HASH_MISMATCH -> "The bytes on disk do not match the SHA-256 in the update document."
    InstallFailure.SIGNING_CERT_MISMATCH -> "The archive's signing certificate is not the one this app was installed with, so it is refused."
    InstallFailure.WRONG_PACKAGE -> "The archive's package name is not this app's."
    InstallFailure.NOT_AN_APK -> "The download is not a readable APK."
    InstallFailure.DOWNGRADE_REFUSED -> "An older version is never installed over a newer one."
    InstallFailure.INSTALL_BLOCKED -> "Another policy or the system installer refused this update."
    InstallFailure.INSTALL_ABORTED -> "The install was cancelled before it finished."
    InstallFailure.INSTALL_CONFLICT -> "The archive conflicts with data of the installed app."
    InstallFailure.INSTALL_INCOMPATIBLE -> "The APK targets a phone or an SDK this one is not."
    InstallFailure.STORAGE_FULL -> "The installer could not write the app."
    InstallFailure.INSTALL_TIMED_OUT -> "The system installer never reported a result."
    InstallFailure.INSTALL_FAILED -> "The system installer reported a failure."
    InstallFailure.NO_KEPT_APK -> "No previous APK is kept, so there is nothing to roll back to."
    InstallFailure.ROOT_UNAVAILABLE -> "Root access is needed to install an older version."
    InstallFailure.ROLLBACK_FAILED -> "`pm install` did not accept the kept APK."
    InstallFailure.DOWNLOAD_FAILED -> "The release could not be downloaded."
}

internal fun ModuleInstallFailure.title(): String = when (this) {
    ModuleInstallFailure.BUILD_FAILED -> "The module could not be rebuilt"
    ModuleInstallFailure.ROOT_UNAVAILABLE -> "Root is not available"
    ModuleInstallFailure.PROVIDER_CONFLICT -> "More than one root provider answered"
    ModuleInstallFailure.INSTALL_FAILED -> "The module install failed"
    ModuleInstallFailure.REBOOT_FAILED -> "The reboot was not accepted"
}

internal fun ModuleInstallFailure.message(): String = when (this) {
    ModuleInstallFailure.BUILD_FAILED -> "The APK or the module payload assets could not be read."
    ModuleInstallFailure.ROOT_UNAVAILABLE -> "Grant root to Phonxiety in the manager, then try again."
    ModuleInstallFailure.PROVIDER_CONFLICT -> "Magisk, KernelSU and APatch module systems conflict; keep one enabled before installing."
    ModuleInstallFailure.INSTALL_FAILED -> "The provider's installer reported a failure; its output is above."
    ModuleInstallFailure.REBOOT_FAILED -> "The module is installed; reboot this phone by hand to apply it."
}
