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
import androidx.compose.material3.AlertDialog
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
import com.phonxiety.bridge.data.updates.RollbackBlocker
import com.phonxiety.bridge.data.updates.RollbackFailure
import com.phonxiety.bridge.data.updates.RollbackPendingReason
import com.phonxiety.bridge.data.updates.RollbackPlan
import com.phonxiety.bridge.data.updates.RollbackRecovery
import com.phonxiety.bridge.data.updates.RollbackState
import com.phonxiety.bridge.data.updates.RollbackStep
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
    onConfirmRollback: () -> Unit,
    onDismissRollback: () -> Unit,
    onDismissRollbackRecovery: () -> Unit,
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
                    TextButton(onClick = onRefresh) { Text("Check again") }
                }

                else -> EmptyState(
                    title = "No update document",
                    message = "The daemon answered without a release to compare against."
                )
            }
            // The rollback reads the phone, not the daemon, so it stays usable when the check fails.
            RollbackSection(
                plan = state.rollbackPlan,
                recovery = state.rollbackRecovery,
                recoveryDismissed = state.rollbackRecoveryDismissed,
                rollback = state.moduleRollback,
                appInstall = state.rollbackAppInstall,
                onRollback = onRollback,
                onDismiss = onDismiss,
                onDismissRecovery = onDismissRollbackRecovery
            )
        }
        state.rollbackConfirmation?.let { confirmation ->
            ConfirmRollbackDialog(
                plan = confirmation,
                onConfirm = onConfirmRollback,
                onDismiss = onDismissRollback
            )
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
                supportingText = "The SHA-256 and the signing certificate are checked before the install. " +
                    "Android may also ask to scan the file or confirm with your screen lock; that is expected " +
                    "for an app installed from a file."
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
    onRollback: () -> Unit,
    onDismiss: () -> Unit,
    onDismissRecovery: () -> Unit
) {
    val shownRecovery = recovery?.takeUnless { recoveryDismissed }
    val pending = shownRecovery as? RollbackRecovery.Pending
    val nothingToOffer = shownRecovery == null && appInstall is AppInstallState.Idle &&
        (plan == null || (plan is RollbackPlan.Unavailable && plan.blocker == RollbackBlocker.NO_KEPT_APK))
    if (nothingToOffer) return

    SectionCard(title = "Rollback", subtitle = rollbackSubtitle(plan)) {
        // While the detached script owns the removal, the journal's module_staged step is expected.
        if (rollback !is RollbackState.Started) {
            shownRecovery?.let {
                RecoveryBlock(recovery = it, onRetry = onRollback, onDismiss = onDismissRecovery)
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
            onRollback = onRollback,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun PlanExplanation(plan: RollbackPlan) {
    when (plan) {
        is RollbackPlan.AppOnly -> Text(
            text = "The APK replaced by the last update is kept in app files. Reinstalling it is a downgrade, " +
                "which needs root.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        is RollbackPlan.WithPreviousModule -> Text(
            text = "The system image carries module ${plan.moduleVersionName ?: plan.moduleVersionCode}, newer than " +
                "the version this rollback returns to (${plan.previousVersionName ?: plan.previousVersionCode}), " +
                "so Android refuses an app-only downgrade. The rollback is one step: reinstall module " +
                "${plan.previousVersionName ?: plan.previousVersionCode} as root, remove the app update that " +
                "shadows it, then reboot this phone.",
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
    onRollback: () -> Unit,
    onDismiss: () -> Unit
) {
    when {
        appInstall is AppInstallState.Failed -> ErrorCard(
            title = appInstall.code.title(),
            message = appInstall.detail ?: appInstall.code.message(),
            actionLabel = "Dismiss",
            onAction = onDismiss
        )

        appInstall.busy -> PrimaryActionButton(
            label = "Reinstalling the previous app…",
            onClick = {},
            busy = true
        )

        appInstall is AppInstallState.Installed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill("Rolled back", StatusTone.POSITIVE)
            Text(
                text = "The phone runs ${appInstall.versionName ?: "the previous app"} again.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        else -> when (rollback) {
            RollbackState.Idle -> when (plan) {
                is RollbackPlan.AppOnly -> TextButton(onClick = onRollback) { Text("Reinstall the previous app") }

                is RollbackPlan.WithPreviousModule -> PrimaryActionButton(
                    label = "Roll back to ${plan.previousVersionName ?: "the previous module"}",
                    onClick = onRollback,
                    supportingText = "One step that ends with a reboot. The phone asks before starting it."
                )

                is RollbackPlan.Unavailable, null -> Unit
            }

            RollbackState.Preparing -> PrimaryActionButton(
                label = "Writing the rollback files…",
                onClick = {},
                busy = true
            )

            RollbackState.StagingModule -> PrimaryActionButton(
                label = "Staging the previous module…",
                onClick = {},
                busy = true
            )

            RollbackState.StartingRemoval -> PrimaryActionButton(
                label = "Starting the removal…",
                onClick = {},
                busy = true
            )

            is RollbackState.Started -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill("Rollback started", StatusTone.WARNING)
                Text(
                    text = "Module ${rollback.versionName ?: "of the previous version"} is staged. The app update is " +
                        "removed in the background and the phone reboots once that is verified. If the phone does " +
                        "not reboot, open Updates again to see what the script recorded.",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }

            is RollbackState.Failed -> ErrorCard(
                title = rollback.code.title(),
                message = rollback.message(),
                output = rollback.detail,
                actionLabel = "Dismiss",
                onAction = onDismiss
            )
        }
    }
}

@Composable
private fun RecoveryBlock(
    recovery: RollbackRecovery,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    when (recovery) {
        is RollbackRecovery.Completed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill("Rolled back", StatusTone.POSITIVE)
            Text(
                text = "The phone runs ${recovery.versionName ?: "the previous version"}: the module and the " +
                    "selected app match the rollback target.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        is RollbackRecovery.Pending -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill("Rollback unfinished", StatusTone.WARNING)
            Text(text = recovery.sentence(), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Finishing it repeats the removal this rollback recorded, by the version it targets. A " +
                    "reboot before that can leave the older module next to the newer app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) { Text("Finish the rollback") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun ConfirmRollbackDialog(
    plan: RollbackPlan.WithPreviousModule,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val previous = plan.previousVersionName ?: plan.previousVersionCode.toString()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Roll back to $previous?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Reinstalls module $previous as root, removes the app update installed on this phone so the " +
                        "module's copy is used again, and reboots once the removal is verified."
                )
                Text(
                    text = "Rollback: install ${plan.moduleVersionName ?: "the current release"} again from " +
                        "Updates to return to it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Removing the app update can erase this app's data, which means pairing this phone " +
                        "again. Newer data is usually kept because the versions match, but a reset is possible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "The phone reboots, so a call in progress ends.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Roll back and reboot") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
    )
}

private fun rollbackSubtitle(plan: RollbackPlan?): String = when (plan) {
    is RollbackPlan.WithPreviousModule -> "One step, then a reboot"
    is RollbackPlan.AppOnly -> "The previous app is kept on this phone"
    is RollbackPlan.Unavailable -> "Not available on this phone"
    null -> "Reading this phone"
}

private fun RollbackBlocker.explanation(plan: RollbackPlan.Unavailable): String {
    val system = plan.moduleVersion ?: plan.moduleVersionCode?.toString() ?: "a newer version"
    val kept = plan.keptVersionName ?: plan.keptVersionCode.toString()
    return when (this) {
        RollbackBlocker.NO_KEPT_APK -> "No previous APK is kept, so there is nothing to roll back to."

        RollbackBlocker.NO_PREVIOUS_MODULE ->
            "The system image carries module $system, newer than the kept app $kept. Android refuses an app-only " +
                "downgrade below the system image, and this phone has no archive of the previous module to put " +
                "back. Install the previous release's module from the update document (or flash it in the root " +
                "manager), then check again."

        RollbackBlocker.STORAGE_UNREADABLE ->
            "The rollback archive under /data/adb/phonxiety could not be read. Grant root to Phonxiety, then " +
                "check again."

        RollbackBlocker.SYSTEM_VERSION_UNKNOWN ->
            "The APK the module mounted on the system image could not be read, so the downgrade floor is " +
                "unknown. Reinstall the module from the update document, then check again."

        RollbackBlocker.STALE_ARCHIVE ->
            "The kept module archive is not older than the system image's copy ($system), so reinstalling it " +
                "would roll nothing back. Nothing is offered."

        RollbackBlocker.ARCHIVE_MISMATCH ->
            "The kept module archive does not hash to the value recorded with it, so its bytes are not the " +
                "previous module. Nothing is offered; install the module update again to keep a fresh copy."
    }
}

private fun RollbackRecovery.Pending.sentence(): String {
    val head = when (reason) {
        RollbackPendingReason.REMOVAL_NOT_STARTED ->
            "The previous module is staged, but the app update was not removed: the script never recorded a result."

        RollbackPendingReason.REMOVAL_FAILED ->
            "The previous module is staged, but removing the app update failed."

        RollbackPendingReason.REMOVAL_UNVERIFIED ->
            "The previous module is staged, but the removal could not be verified, so nothing was rebooted."

        RollbackPendingReason.OLD_APP_NOT_SELECTED ->
            "The removal was recorded, but the data-partition app copy is still what runs."

        RollbackPendingReason.VERSION_MISMATCH ->
            "The removal was recorded, but the phone does not match the rollback target."
    }
    return listOfNotNull(head, detail?.takeIf { it.isNotBlank() }?.let { "Details: $it" }).joinToString(" ")
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

internal fun RollbackFailure.title(): String = when (this) {
    RollbackFailure.NO_KEPT_APK -> "No previous app is kept"
    RollbackFailure.NO_PREVIOUS_MODULE -> "No previous module is kept"
    RollbackFailure.SYSTEM_VERSION_UNKNOWN -> "The system image's version is unknown"
    RollbackFailure.STORAGE_UNREADABLE -> "The rollback archive cannot be read"
    RollbackFailure.STALE_ARCHIVE -> "The kept module is not older"
    RollbackFailure.ARCHIVE_MISMATCH -> "The kept module changed since the check"
    RollbackFailure.PREPARATION_FAILED -> "The rollback files could not be written"
    RollbackFailure.MODULE_STAGE_FAILED -> "The previous module was not staged"
    RollbackFailure.REMOVAL_NOT_STARTED -> "The removal script did not start"
}

/** The sentence a failed rollback shows, always naming what the phone already changed. */
internal fun RollbackState.Failed.message(): String = when (code) {
    RollbackFailure.NO_KEPT_APK -> "No previous APK is kept, so there is nothing to roll back to."

    RollbackFailure.NO_PREVIOUS_MODULE ->
        "The system image is newer than the kept app and no archive of the previous module is kept, so nothing " +
            "can be put back. Nothing on the phone changed."

    RollbackFailure.SYSTEM_VERSION_UNKNOWN ->
        "The APK mounted on the system image could not be read, so the downgrade floor is unknown. Nothing on " +
            "the phone changed."

    RollbackFailure.STORAGE_UNREADABLE ->
        "The rollback archive under /data/adb/phonxiety could not be read. Nothing on the phone changed."

    RollbackFailure.STALE_ARCHIVE ->
        "The kept module archive is not older than the system image's copy, so it is not a rollback. Nothing " +
            "on the phone changed."

    RollbackFailure.ARCHIVE_MISMATCH ->
        "The published archive is not the one this check planned for. Nothing on the phone changed; check again " +
            "before rolling back."

    RollbackFailure.PREPARATION_FAILED ->
        "The script and the journal could not be written on this phone, so nothing was staged and nothing on the " +
            "phone changed. Free space in the app's storage, then try again."

    RollbackFailure.MODULE_STAGE_FAILED ->
        "The module installer refused the previous zip, so nothing on the phone changed."

    RollbackFailure.REMOVAL_NOT_STARTED ->
        "The previous module is staged, but the removal script could not start: the app update is still " +
            "installed, and a reboot now can leave the older module next to the newer app. Retry the rollback " +
            "before rebooting."
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
