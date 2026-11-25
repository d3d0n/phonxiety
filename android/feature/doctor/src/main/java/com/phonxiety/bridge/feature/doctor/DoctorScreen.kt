package com.phonxiety.bridge.feature.doctor

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
        snackbar.showSnackbar(message)
        viewModel.dismissMessage()
    }

    LifecycleResumeEffect(viewModel) {
        viewModel.onReturnedToForeground()
        onPauseOrDispose { }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
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
                scope.launch { snackbar.showSnackbar("Command copied") }
            },
            modifier = modifier.padding(padding)
        )
    }
}

/**
 * The self-check, the repairs and the recovery pages. On a window of 600dp or more the checks stay
 * in the left column while the selected check, the daemon fixes and the page share the right one.
 */
@Composable
fun DoctorScreen(
    state: DoctorUiState,
    callbacks: DoctorCallbacks,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit = {}
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DoctorHeader(state, callbacks)
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
                    val page = state.page
                    if (page != null) {
                        RecoveryContent(page, onClose = callbacks.onCloseRecovery, onCopy = onCopy)
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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val page = state.page
                if (page != null) {
                    RecoveryContent(page, onClose = callbacks.onCloseRecovery, onCopy = onCopy)
                } else {
                    DoctorHeader(state, callbacks)
                    SummaryCard(state, callbacks)
                    CheckGroups(state, callbacks)
                    DaemonFixesCard(state, callbacks)
                    ChangeLogCard(state)
                    GuidedRecoveryCard(state, callbacks)
                }
            }
        }
        state.pending?.let { request ->
            ConfirmFixDialog(request, callbacks.onConfirm, callbacks.onDismissConfirm)
        }
    }
}

@Composable
private fun DoctorHeader(state: DoctorUiState, callbacks: DoctorCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = callbacks.onBack) { Text("Back") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Doctor", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Self-check, repairs and guided recovery",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SeverityPill(state.overall)
        }
    }
}

@Composable
private fun SummaryCard(state: DoctorUiState, callbacks: DoctorCallbacks) {
    SectionCard(title = "Self-check", subtitle = summarySubtitle(state)) {
        when {
            state.checking -> LoadingState("Reading the phone and the daemon…")

            state.readError != null -> ErrorCard(
                title = "The phone could not be read",
                message = state.readError,
                actionLabel = "Re-check",
                onAction = callbacks.onRefresh
            )

            else -> {
                Text(
                    text = summaryLine(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = callbacks.onRefresh, enabled = state.running == null) {
                        Text("Re-check")
                    }
                    if (state.running != null) {
                        Text(
                            text = "Running: ${state.running.label}",
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
        SectionCard(title = group.title) {
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CheckRow(row, state, callbacks)
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
            .clickable { callbacks.onSelect(row.id) }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            SeverityPill(row.severity)
        }
        Text(
            text = row.evidence,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (row.needsFix) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (row.plan != null && risk != null) {
                    TextButton(
                        onClick = { callbacks.onFix(row.id) },
                        enabled = state.running == null && risk != FixClass.NEVER
                    ) {
                        Text(if (risk == FixClass.NEVER) "Blocked during a call" else row.plan.fix.label)
                    }
                }
                val recovery = DoctorPolicy.recoveryFor(row.kind)
                if (recovery != null) {
                    TextButton(onClick = { callbacks.onOpenRecovery(recovery) }) { Text("Recovery") }
                }
            }
        }
    }
}

@Composable
private fun SelectedRowCard(state: DoctorUiState, callbacks: DoctorCallbacks) {
    val row = state.row(state.selected)
    if (row == null) {
        SectionCard(title = "Detail") {
            EmptyState(
                title = "Select a check",
                message = "The evidence, the fix and its rollback appear here."
            )
        }
        return
    }
    val risk = row.plan?.let { DoctorPolicy.effectiveRisk(it, state.callActive) }
    SectionCard(title = row.title, status = { SeverityPill(row.severity) }) {
        Text(text = row.evidence, style = MaterialTheme.typography.bodyMedium)
        val plan = row.plan
        if (plan != null) {
            Text(text = plan.effect, style = MaterialTheme.typography.bodySmall)
            Text(
                text = "Rollback: ${plan.rollback}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (plan.needsReboot) {
                Text(
                    text = "The phone reboots when the command succeeds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            TextButton(
                onClick = { callbacks.onFix(row.id) },
                enabled = state.running == null && risk != FixClass.NEVER
            ) {
                Text(if (risk == FixClass.NEVER) "Blocked during a call" else plan.fix.label)
            }
        }
        DoctorPolicy.recoveryFor(row.kind)?.let { recovery ->
            TextButton(onClick = { callbacks.onOpenRecovery(recovery) }) { Text("Open guided recovery") }
        }
    }
}

@Composable
private fun DaemonFixesCard(state: DoctorUiState, callbacks: DoctorCallbacks) {
    SectionCard(title = "Daemon fixes", subtitle = "Reported by the daemon; each one says what it restarts.") {
        if (state.daemonFixes.isEmpty()) {
            Text(
                text = "The daemon offered no fixes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.daemonFixes.forEach { offer ->
                val risk = DoctorPolicy.effectiveRisk(offer.plan, state.callActive)
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = offer.fix,
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        ClassPill(risk)
                    }
                    offer.description?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { callbacks.onDaemonFix(offer.fix) },
                        enabled = state.running == null && risk != FixClass.NEVER
                    ) {
                        Text(if (risk == FixClass.NEVER) "Blocked during a call" else "Apply")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangeLogCard(state: DoctorUiState) {
    SectionCard(title = "What Doctor changed", subtitle = "Fixes it ran without asking are recorded here.") {
        if (state.changeLog.isEmpty()) {
            Text(
                text = "Doctor has not changed anything yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.changeLog.takeLast(CHANGE_LOG_ROWS).reversed().forEach { entry ->
                Text(
                    text = "${clock(entry.atEpochMs)}  ${entry.text}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun GuidedRecoveryCard(state: DoctorUiState, callbacks: DoctorCallbacks) {
    SectionCard(
        title = "Guided recovery",
        subtitle = "Commands are filled in for ${RecoveryBook.providerName(state.provider)}."
    ) {
        RecoveryId.entries.forEach { id ->
            val page = RecoveryBook.page(id, state.provider)
            TextButton(onClick = { callbacks.onOpenRecovery(id) }, modifier = Modifier.fillMaxWidth()) {
                Text(text = page.title, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RecoveryContent(page: RecoveryPage, onClose: () -> Unit, onCopy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onClose) { Text("Back to the checks") }
        SectionCard(title = page.title) {
            Text(
                text = "What happened",
                style = MaterialTheme.typography.titleSmall
            )
            Text(text = page.happened, style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard(title = "On the phone") {
            page.onPhone.forEachIndexed { index, step ->
                StepLine(index = index + 1, step = step, onCopy = onCopy)
            }
        }
        SectionCard(title = "From the computer") {
            page.fromComputer.forEachIndexed { index, step ->
                StepLine(index = index + 1, step = step, onCopy = onCopy)
            }
        }
    }
}

@Composable
private fun StepLine(index: Int, step: RecoveryStep, onCopy: (String) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = "$index. ${step.text}", style = MaterialTheme.typography.bodyMedium)
        step.command?.let { command ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onCopy(command) }) { Text("Copy") }
            }
        }
    }
}

@Composable
private fun ConfirmFixDialog(request: FixRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.plan.effect)
                Text(
                    text = "Rollback: ${request.plan.rollback}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (request.plan.needsReboot) {
                    Text(
                        text = "The phone reboots when the command succeeds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Run it") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        }
    )
}

@Composable
private fun SeverityPill(severity: DoctorSeverity) {
    val (label, tone) = when (severity) {
        DoctorSeverity.OK -> "OK" to StatusTone.POSITIVE
        DoctorSeverity.WARN -> "Warning" to StatusTone.WARNING
        DoctorSeverity.FAIL -> "Failed" to StatusTone.NEGATIVE
        DoctorSeverity.UNKNOWN -> "Unknown" to StatusTone.NEUTRAL
    }
    StatusPill(label = label, tone = tone)
}

@Composable
private fun ClassPill(risk: FixClass) {
    val (label, tone) = when (risk) {
        FixClass.AUTOMATIC -> "Automatic" to StatusTone.ACCENT
        FixClass.CONFIRM -> "Asks first" to StatusTone.WARNING
        FixClass.NEVER -> "Not during a call" to StatusTone.NEGATIVE
    }
    StatusPill(label = label, tone = tone)
}

private fun summarySubtitle(state: DoctorUiState): String = when {
    state.checking -> "reading"
    state.readError != null -> "the read failed"
    state.rows.isEmpty() -> "nothing checked yet"
    state.failing.isEmpty() -> "${state.rows.size} checks, all green"
    else -> "${state.passed} of ${state.rows.size} checks pass"
}

private fun summaryLine(state: DoctorUiState): String = when {
    state.failing.isEmpty() && state.overall == DoctorSeverity.OK ->
        "Every check passes. Doctor keeps the link's evidence in this screen, so a repair starts from numbers."

    state.overall == DoctorSeverity.UNKNOWN ->
        "Doctor could not read enough to judge. Re-check after granting root access."

    state.callActive ->
        "A call is running: fixes that would drop it are not offered until the call ends."

    else ->
        "${state.failing.size} checks need a fix. Automatic ones ran while this screen opened; " +
            "the rest explain their effect before they act."
}

private fun clock(epochMs: Long): String = DateTimeFormatter
    .ofPattern("HH:mm")
    .withZone(ZoneOffset.UTC)
    .format(Instant.ofEpochMilli(epochMs))

private const val CHANGE_LOG_ROWS = 6

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Phonxiety command", text))
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

@Preview(name = "Doctor – all green", widthDp = 411, heightDp = 891)
@Composable
private fun DoctorAllGreenPreview() {
    PhonxietyTheme(dynamicColor = false) {
        DoctorScreen(state = DoctorSamples.allGreen(), callbacks = DoctorCallbacks())
    }
}

@Preview(name = "Doctor – failures", widthDp = 411, heightDp = 891)
@Composable
private fun DoctorFailuresPreview() {
    PhonxietyTheme(dynamicColor = false) {
        DoctorScreen(state = DoctorSamples.severalFailures(), callbacks = DoctorCallbacks())
    }
}

@Preview(name = "Doctor – recovery", widthDp = 411, heightDp = 891)
@Composable
private fun DoctorRecoveryPreview() {
    PhonxietyTheme(dynamicColor = false) {
        DoctorScreen(state = DoctorSamples.recovery(), callbacks = DoctorCallbacks())
    }
}

@Preview(name = "Doctor – two pane", widthDp = 900, heightDp = 800)
@Composable
private fun DoctorTwoPanePreview() {
    PhonxietyTheme(dynamicColor = false) {
        Box(Modifier.height(800.dp)) {
            DoctorScreen(state = DoctorSamples.severalFailures(), callbacks = DoctorCallbacks())
        }
    }
}

@Preview(name = "Doctor – confirm", widthDp = 411, heightDp = 891)
@Composable
private fun DoctorConfirmPreview() {
    PhonxietyTheme(dynamicColor = false) {
        DoctorScreen(state = DoctorSamples.pendingFix(), callbacks = DoctorCallbacks())
    }
}
