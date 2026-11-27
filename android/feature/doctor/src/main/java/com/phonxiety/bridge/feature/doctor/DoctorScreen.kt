package com.phonxiety.bridge.feature.doctor

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ScreenScaffold
import com.phonxiety.bridge.feature.doctor.cards.ChangeLogCard
import com.phonxiety.bridge.feature.doctor.cards.CheckGroups
import com.phonxiety.bridge.feature.doctor.cards.DaemonFixesCard
import com.phonxiety.bridge.feature.doctor.cards.GuidedRecoveryCard
import com.phonxiety.bridge.feature.doctor.cards.RebootNotice
import com.phonxiety.bridge.feature.doctor.cards.RecoveryContent
import com.phonxiety.bridge.feature.doctor.cards.SelectedRowCard
import com.phonxiety.bridge.feature.doctor.cards.SummaryCard
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
                            GuidedRecoveryCard(state, callbacks)
                            ChangeLogCard(state)
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
                        GuidedRecoveryCard(state, callbacks)
                        ChangeLogCard(state)
                    }
                }
            }
        }
        state.pending?.let { request ->
            ConfirmFixDialog(request, callbacks.onConfirm, callbacks.onDismissConfirm)
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
            // At 200 % font on a 320 dp phone the body is taller than the dialog: scroll it rather
            // than clip the rollback sentence.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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

@SuppressLint("BatteryLife")
private fun batteryExemptionIntent(context: Context): Intent = Intent(
    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    "package:${context.packageName}".toUri()
)

private fun notificationSettingsIntent(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.doctor_clipboard_label), text))
}

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
