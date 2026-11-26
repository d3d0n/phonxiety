package com.phonxiety.bridge.feature.doctor

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.designsystem.LocalStatusColors
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.ScreenScaffold
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import java.util.Date
import kotlinx.coroutines.launch

/** What the screen reports back; every callback has a no-op default so previews and tests stay short. */
data class DoctorCallbacks(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onFix: (String) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onDismissConfirm: () -> Unit = {},
    val onSelect: (String?) -> Unit = {},
    val onOpenRecovery: (RecoveryId) -> Unit = {},
    val onCloseRecovery: () -> Unit = {},
    val onDaemonFix: (String) -> Unit = {}
)

/** The Doctor entry point; the app host only supplies navigation. */
@Composable
fun DoctorRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DoctorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.doctor_recovery_copied)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.onPermissionsResult() }
    val batteryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { viewModel.onReturnedToForeground() }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DoctorEffect.RequestRuntimePermissions ->
                    permissionLauncher.launch(effect.permissions.toTypedArray())

                DoctorEffect.RequestBatteryExemption -> batteryLauncher.launch(batteryExemptionIntent(context))

                DoctorEffect.OpenNotificationSettings -> runCatching {
                    context.startActivity(notificationSettingsIntent(context))
                }
            }
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message.asString(resources))
        viewModel.dismissMessage()
    }

    LifecycleResumeEffect(viewModel) {
        viewModel.onReturnedToForeground()
        onPauseOrDispose { }
    }

    DoctorScreen(
        state = state,
        callbacks = DoctorCallbacks(
            onBack = onBack,
            onRefresh = viewModel::refresh,
            onFix = viewModel::requestFix,
            onConfirm = viewModel::confirmFix,
            onDismissConfirm = viewModel::dismissConfirm,
            onSelect = viewModel::select,
            onOpenRecovery = viewModel::openRecovery,
            onCloseRecovery = viewModel::closeRecovery,
            onDaemonFix = viewModel::requestDaemonFix
        ),
        onCopy = { text ->
            copyToClipboard(context, text)
            scope.launch { snackbar.showSnackbar(copied) }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = modifier
    )
}

/**
 * The self-check, the repairs and the recovery pages. On a window of 600dp or more the checks stay
 * in the left column while the selected check, the daemon fixes and the page share the right one.
 * On a phone a tapped check opens its detail card directly under the groups.
 */
@Composable
fun DoctorScreen(
    state: DoctorUiState,
    callbacks: DoctorCallbacks,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit = {},
    snackbarHost: @Composable () -> Unit = {}
) {
    val page = state.page
    val title = page?.title?.asString() ?: stringResource(R.string.doctor_title)
    ScreenScaffold(
        title = title,
        modifier = modifier,
        onBack = if (page != null) callbacks.onCloseRecovery else callbacks.onBack,
        snackbarHost = snackbarHost
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            if (wide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SummaryCard(state, callbacks)
                        CheckGroups(state, callbacks)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (page != null) {
                            RecoveryContent(page, onCopy = onCopy)
                        } else {
                            SelectedRowCard(state, callbacks)
                            DaemonFixesCard(state, callbacks)
                            ChangeLogCard(state)
                            GuidedRecoveryCard(state, callbacks)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (page != null) {
                        RecoveryContent(page, onCopy = onCopy)
                    } else {
                        SummaryCard(state, callbacks)
                        CheckGroups(state, callbacks)
                        if (state.selected != null) {
                            val requester = remember { BringIntoViewRequester() }
                            LaunchedEffect(state.selected) { requester.bringIntoView() }
                            Box(Modifier.bringIntoViewRequester(requester)) {
                                SelectedRowCard(state, callbacks)
                            }
                        }
                        DaemonFixesCard(state, callbacks)
                        ChangeLogCard(state)
                        GuidedRecoveryCard(state, callbacks)
                    }
                }
            }
        }
        state.pending?.let { request ->
            ConfirmFixDialog(request, callbacks.onConfirm, callbacks.onDismissConfirm)
        }
    }
}

/**
 * A card whose status pill drops under the title at large font scales: beside a 200 % title the
 * pill leaves so little width that Compose breaks the title mid-word.
 */
@Composable
private fun StatusSectionCard(
    title: String,
    subtitle: String? = null,
    status: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val stacked = LocalDensity.current.fontScale >= STACK_STATUS_AT_FONT_SCALE
    SectionCard(
        title = title,
        subtitle = subtitle,
        status = if (stacked) null else status
    ) {
        if (stacked && status != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), content = status)
        }
        content()
    }
}

@Composable
private fun SummaryCard(state: DoctorUiState, callbacks: DoctorCallbacks) {
    StatusSectionCard(
        title = stringResource(R.string.doctor_card_summary),
        subtitle = summarySubtitle(state),
        status = { OverallPill(state) }
    ) {
        when {
            state.checking -> LoadingState(stringResource(R.string.doctor_summary_reading))

            state.readError != null -> ErrorCard(
                title = stringResource(R.string.doctor_read_error_title),
                message = requireNotNull(state.readError).asString(),
                actionLabel = stringResource(R.string.doctor_action_run_checks),
                onAction = callbacks.onRefresh
            )

            else -> {
                Text(
                    text = summaryLine(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(onClick = callbacks.onRefresh, enabled = state.running == null) {
                        Text(stringResource(R.string.doctor_action_run_checks))
                    }
                    if (state.running != null) {
                        Text(
                            text = stringResource(
                                R.string.doctor_running,
                                stringResource(state.running.labelRes)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckGroups(state: DoctorUiState, callbacks: DoctorCallbacks) {
    DoctorGroup.entries.forEach { group ->
        val rows = state.rowsOf(group)
        if (rows.isEmpty()) return@forEach
        SectionCard(title = stringResource(group.titleRes)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { row -> CheckRow(row, state, callbacks) }
            }
        }
    }
}

@Composable
private fun CheckRow(row: DoctorRow, state: DoctorUiState, callbacks: DoctorCallbacks) {
    val selected = state.selected == row.id
    val risk = row.plan?.let { DoctorPolicy.effectiveRisk(it, state.callActive) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.Button,
                onClick = { callbacks.onSelect(row.id) }
            )
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.title.asString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            SeverityBadge(row.severity)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(20.dp)
            )
        }
        EvidenceText(row)
        if (row.needsFix && !selected) {
            RowActionButtons(row, risk, state, callbacks)
        }
    }
}

/** A row's actions carry the check name, so two identical labels never read the same. */
@Composable
private fun RowActionButtons(
    row: DoctorRow,
    risk: FixClass?,
    state: DoctorUiState,
    callbacks: DoctorCallbacks
) {
    val checkName = row.title.asString()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (row.plan != null && risk != null) {
            val label = stringResource(row.plan.fix.labelRes)
            TextButton(
                onClick = { callbacks.onFix(row.id) },
                enabled = state.running == null && risk != FixClass.NEVER,
                modifier = Modifier.semantics { contentDescription = "$label: $checkName" }
            ) {
                Text(label)
            }
            if (risk == FixClass.NEVER) {
                Text(
                    text = stringResource(R.string.doctor_blocked_in_call),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val recovery = DoctorPolicy.recoveryFor(row.kind)
        if (recovery != null) {
            val label = stringResource(R.string.doctor_open_recovery)
            TextButton(
                onClick = { callbacks.onOpenRecovery(recovery) },
                modifier = Modifier.semantics { contentDescription = "$label: $checkName" }
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun EvidenceText(row: DoctorRow) {
    val parts = ArrayList<String>(row.evidence.size)
    for (part in row.evidence) parts += part.asString()
    SelectionContainer {
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SelectedRowCard(state: DoctorUiState, callbacks: DoctorCallbacks) {
    val row = state.row(state.selected)
    if (row == null) {
        SectionCard(title = stringResource(R.string.doctor_card_summary)) {
            EmptyState(
                title = stringResource(R.string.doctor_select_check_title),
                message = stringResource(R.string.doctor_select_check_message)
            )
        }
        return
    }
    val risk = row.plan?.let { DoctorPolicy.effectiveRisk(it, state.callActive) }
    val checkName = row.title.asString()
    StatusSectionCard(
        title = checkName,
        status = { SeverityBadge(row.severity) }
    ) {
        EvidenceText(row)
        val plan = row.plan
        if (plan != null) {
            Text(text = plan.effect.asString(), style = MaterialTheme.typography.bodyMedium)
            if (plan.needsReboot) {
                RebootNotice()
            }
            Text(
                text = stringResource(R.string.doctor_rollback_label),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = plan.rollback.asString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            val label = stringResource(plan.fix.labelRes)
            TextButton(
                onClick = { callbacks.onFix(row.id) },
                enabled = state.running == null && risk != FixClass.NEVER,
                modifier = Modifier.semantics { contentDescription = "$label: $checkName" }
            ) {
                Text(label)
            }
            if (risk == FixClass.NEVER) {
                Text(
                    text = stringResource(R.string.doctor_blocked_in_call),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DoctorPolicy.recoveryFor(row.kind)?.let { recovery ->
            val label = stringResource(R.string.doctor_open_recovery)
            TextButton(
                onClick = { callbacks.onOpenRecovery(recovery) },
                modifier = Modifier.semantics { contentDescription = "$label: $checkName" }
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun RebootNotice() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = null,
            tint = LocalStatusColors.current.warning.content,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = stringResource(R.string.doctor_reboot_notice),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun DaemonFixesCard(state: DoctorUiState, callbacks: DoctorCallbacks) {
    SectionCard(
        title = stringResource(R.string.doctor_card_daemon_fixes),
        subtitle = stringResource(R.string.doctor_daemon_fixes_subtitle)
    ) {
        when {
            state.checking -> LoadingState(stringResource(R.string.doctor_loading_report))

            state.daemonFixes.isEmpty() -> Text(
                text = stringResource(R.string.doctor_daemon_fixes_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            else -> state.daemonFixes.forEach { offer ->
                val risk = DoctorPolicy.effectiveRisk(offer.plan, state.callActive)
                Column(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = offer.description?.replaceFirstChar { it.uppercase() } ?: offer.fix,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        ClassPill(risk)
                    }
                    Text(
                        text = offer.fix,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val runLabel = stringResource(R.string.doctor_fix_run)
                    TextButton(
                        onClick = { callbacks.onDaemonFix(offer.fix) },
                        enabled = state.running == null && risk != FixClass.NEVER,
                        modifier = Modifier.semantics {
                            contentDescription = "$runLabel: ${offer.description ?: offer.fix}"
                        }
                    ) {
                        Text(runLabel)
                    }
                    if (risk == FixClass.NEVER) {
                        Text(
                            text = stringResource(R.string.doctor_blocked_in_call),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangeLogCard(state: DoctorUiState) {
    SectionCard(
        title = stringResource(R.string.doctor_card_changes),
        subtitle = stringResource(R.string.doctor_changes_subtitle)
    ) {
        when {
            state.checking -> LoadingState(stringResource(R.string.doctor_loading_report))

            state.changeLog.isEmpty() -> Text(
                text = stringResource(R.string.doctor_changes_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            else -> {
                val context = LocalContext.current
                val time = remember(context) { DateFormat.getTimeFormat(context) }
                state.changeLog.takeLast(CHANGE_LOG_ROWS).reversed().forEach { entry ->
                    SelectionContainer {
                        Text(
                            text = stringResource(
                                R.string.doctor_change_line,
                                time.format(Date(entry.atEpochMs)),
                                entry.text()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DoctorChangeEntry.text(): String = when (event) {
    null -> legacyText.orEmpty()

    DoctorLogEvent.BRIDGE_RESTARTED -> stringResource(R.string.doctor_log_bridge_restarted)

    DoctorLogEvent.PERMISSION_ASKED -> stringResource(R.string.doctor_log_permission_asked, *args.toTypedArray())

    DoctorLogEvent.NOTIFICATIONS_ASKED -> stringResource(R.string.doctor_log_notifications_asked)

    DoctorLogEvent.BACKGROUND_ASKED -> stringResource(R.string.doctor_log_background_asked)

    DoctorLogEvent.MODULE_ENABLED -> stringResource(R.string.doctor_log_module_enabled)

    DoctorLogEvent.MODULE_ENABLED_OUTPUT ->
        stringResource(R.string.doctor_log_module_enabled_output, *args.toTypedArray())

    DoctorLogEvent.MODULE_REINSTALLED -> stringResource(R.string.doctor_log_module_reinstalled)

    DoctorLogEvent.MODULE_REINSTALLED_OUTPUT ->
        stringResource(R.string.doctor_log_module_reinstalled_output, *args.toTypedArray())

    DoctorLogEvent.REBOOT_REQUESTED -> stringResource(R.string.doctor_log_reboot_requested)

    DoctorLogEvent.REBOOTED -> stringResource(R.string.doctor_log_rebooted)

    DoctorLogEvent.FIX_FAILED -> if (args.isEmpty()) {
        stringResource(R.string.doctor_log_fix_failed_no_output)
    } else {
        stringResource(R.string.doctor_log_fix_failed, args[0])
    }

    DoctorLogEvent.REBOOT_FAILED -> stringResource(R.string.doctor_log_reboot_failed, *args.toTypedArray())

    DoctorLogEvent.PAIRING_RECOVERY_OPENED -> stringResource(R.string.doctor_log_pairing_opened)

    DoctorLogEvent.DAEMON_FIX_APPLIED -> stringResource(R.string.doctor_log_daemon_fix, *args.toTypedArray())

    DoctorLogEvent.DAEMON_FIX_APPLIED_MORE ->
        stringResource(R.string.doctor_log_daemon_fix_more, *args.toTypedArray())
}

@Composable
private fun GuidedRecoveryCard(state: DoctorUiState, callbacks: DoctorCallbacks) {
    val provider = state.provider
    SectionCard(
        title = stringResource(R.string.doctor_card_recovery),
        subtitle = if (provider == null) {
            stringResource(R.string.doctor_recovery_subtitle_unknown)
        } else {
            stringResource(R.string.doctor_recovery_subtitle, provider.doctorTitle())
        }
    ) {
        RecoveryId.entries.forEach { id ->
            val page = remember(id, provider) { RecoveryBook.page(id, provider) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { callbacks.onOpenRecovery(id) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = page.title.asString(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecoveryContent(page: RecoveryPage, onCopy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = stringResource(R.string.doctor_recovery_what_happened)) {
            Text(
                text = page.happened.asString(),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        SectionCard(title = stringResource(R.string.doctor_recovery_on_phone)) {
            page.onPhone.forEachIndexed { index, step ->
                StepLine(index = index + 1, step = step, onCopy = onCopy)
            }
        }
        SectionCard(title = stringResource(R.string.doctor_recovery_from_computer)) {
            page.fromComputer.forEachIndexed { index, step ->
                StepLine(index = index + 1, step = step, onCopy = onCopy)
            }
        }
    }
}

@Composable
private fun StepLine(index: Int, step: RecoveryStep, onCopy: (String) -> Unit) {
    Column(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "$index. ${step.text.asString()}",
            style = MaterialTheme.typography.bodyMedium
        )
        step.command?.let { command ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = command,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                val copyLabel = stringResource(R.string.doctor_recovery_copy)
                TextButton(
                    onClick = { onCopy(command) },
                    modifier = Modifier.semantics { contentDescription = "$copyLabel: $command" }
                ) {
                    Text(copyLabel)
                }
            }
        }
    }
}

@Composable
private fun ConfirmFixDialog(request: FixRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val label = stringResource(request.plan.fix.labelRes)
    val action = if (request.plan.needsReboot) {
        stringResource(R.string.doctor_confirm_action_reboot, label)
    } else {
        label
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.doctor_confirm_title, label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.doctor_confirm_check_failed, request.checkTitle.asString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(text = request.plan.effect.asString(), style = MaterialTheme.typography.bodyMedium)
                if (request.plan.needsReboot) {
                    RebootNotice()
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.doctor_rollback_label),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = request.plan.rollback.asString(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(action) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.doctor_confirm_cancel)) }
        }
    )
}

/** A passing row shows a check, not a pill: twenty identical "OK" pills bury the one failure. */
@Composable
private fun SeverityBadge(severity: DoctorSeverity) {
    when (severity) {
        DoctorSeverity.OK -> Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = LocalStatusColors.current.positiveIndicator,
            modifier = Modifier.size(20.dp)
        )

        DoctorSeverity.WARN -> StatusPill(
            label = stringResource(R.string.doctor_severity_warning),
            tone = StatusTone.WARNING
        )

        DoctorSeverity.FAIL -> StatusPill(
            label = stringResource(R.string.doctor_severity_failed),
            tone = StatusTone.NEGATIVE
        )

        DoctorSeverity.UNKNOWN -> StatusPill(
            label = stringResource(R.string.doctor_severity_unknown),
            tone = StatusTone.NEUTRAL
        )
    }
}

@Composable
private fun OverallPill(state: DoctorUiState) {
    when {
        state.checking -> StatusPill(
            label = stringResource(R.string.doctor_status_reading),
            tone = StatusTone.NEUTRAL,
            pulse = true
        )

        state.readError != null -> StatusPill(
            label = stringResource(R.string.doctor_status_read_failed),
            tone = StatusTone.NEGATIVE
        )

        state.rows.isEmpty() || state.overall == DoctorSeverity.UNKNOWN -> StatusPill(
            label = stringResource(R.string.doctor_status_inconclusive),
            tone = StatusTone.NEUTRAL
        )

        state.overall == DoctorSeverity.OK -> StatusPill(
            label = stringResource(R.string.doctor_status_all_pass),
            tone = StatusTone.POSITIVE
        )

        else -> StatusPill(
            label = pluralStringResource(
                R.plurals.doctor_status_needs_fix,
                state.failing.size,
                state.failing.size
            ),
            tone = if (state.overall == DoctorSeverity.FAIL) StatusTone.NEGATIVE else StatusTone.WARNING
        )
    }
}

@Composable
private fun ClassPill(risk: FixClass) {
    val (label, tone) = when (risk) {
        FixClass.AUTOMATIC -> R.string.doctor_risk_automatic to StatusTone.NEUTRAL
        FixClass.CONFIRM -> R.string.doctor_risk_confirm to StatusTone.ACCENT
        FixClass.NEVER -> R.string.doctor_risk_never to StatusTone.NEGATIVE
    }
    StatusPill(label = stringResource(label), tone = tone)
}

@Composable
private fun summarySubtitle(state: DoctorUiState): String = when {
    state.checking -> stringResource(R.string.doctor_status_reading)

    state.readError != null -> stringResource(R.string.doctor_status_read_failed)

    state.rows.isEmpty() -> stringResource(R.string.doctor_status_not_checked)

    state.failing.isEmpty() -> pluralStringResource(
        R.plurals.doctor_summary_all_pass,
        state.rows.size,
        state.rows.size
    )

    else -> pluralStringResource(
        R.plurals.doctor_summary_passed,
        state.passed,
        state.passed,
        state.rows.size
    )
}

@Composable
private fun summaryLine(state: DoctorUiState): String = when {
    state.failing.isEmpty() && state.overall == DoctorSeverity.OK ->
        stringResource(R.string.doctor_summary_line_all_pass)

    state.overall == DoctorSeverity.UNKNOWN -> stringResource(R.string.doctor_summary_line_unknown)

    state.callActive -> stringResource(R.string.doctor_summary_line_call)

    else -> pluralStringResource(
        R.plurals.doctor_summary_line_needs_fix,
        state.failing.size,
        state.failing.size
    )
}

private const val STACK_STATUS_AT_FONT_SCALE = 1.5f
private const val CHANGE_LOG_ROWS = 6

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.doctor_clipboard_label), text))
}

// The phone is a sideloaded priv-app, and the exemption is the documented fix for heartbeat misses
// with the screen off; the dialog is the system's own, never a silent grant.
@SuppressLint("BatteryLife")
private fun batteryExemptionIntent(context: Context): Intent = Intent(
    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    "package:${context.packageName}".toUri()
)

private fun notificationSettingsIntent(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

@Preview(name = "Doctor – all green", widthDp = 411, heightDp = 2000)
@Composable
private fun DoctorAllGreenPreview() {
    PhonxietyTheme(dynamicColor = false) {
        DoctorScreen(state = DoctorSamples.allGreen(), callbacks = DoctorCallbacks())
    }
}

@Preview(name = "Doctor – failures", widthDp = 411, heightDp = 2000)
@Composable
private fun DoctorFailuresPreview() {
    PhonxietyTheme(dynamicColor = false) {
        DoctorScreen(state = DoctorSamples.severalFailures(), callbacks = DoctorCallbacks())
    }
}

@Preview(name = "Doctor – recovery", widthDp = 411, heightDp = 2000)
@Composable
private fun DoctorRecoveryPreview() {
    PhonxietyTheme(dynamicColor = false) {
        DoctorScreen(state = DoctorSamples.recovery(), callbacks = DoctorCallbacks())
    }
}

@Preview(name = "Doctor – confirm", widthDp = 411, heightDp = 2000)
@Composable
private fun DoctorConfirmPreview() {
    PhonxietyTheme(dynamicColor = false) {
        DoctorScreen(state = DoctorSamples.pendingFix(), callbacks = DoctorCallbacks())
    }
}
