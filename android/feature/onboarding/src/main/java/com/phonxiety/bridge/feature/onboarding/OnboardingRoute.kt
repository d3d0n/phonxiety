package com.phonxiety.bridge.feature.onboarding

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.root.WizardState
import kotlinx.coroutines.launch

/**
 * The wizard wired to the device: hosts the permission dialogs, the battery-exemption intent, the
 * QR scanner and the deep-link pairing, and forwards [SetupHostRequest]s from the coordinator.
 */
@Composable
fun OnboardingRoute(
    initialPairLink: String?,
    onFinished: () -> Unit,
    onOpenDoctor: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val wizard by viewModel.wizard.collectAsStateWithLifecycle()
    val pairingError by viewModel.pairingError.collectAsStateWithLifecycle()
    val detection by viewModel.detection.collectAsStateWithLifecycle()
    val installPhase by viewModel.installPhase.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var inlineScanner by remember { mutableStateOf(false) }
    var showPairing by rememberSaveable { mutableStateOf(false) }
    var pendingMessage by remember { mutableStateOf<UiText?>(null) }

    // Resolved in composition so a locale or font-size change re-reads them.
    val scannerUnavailable = stringResource(R.string.onboarding_scanner_unavailable)
    val scannerDidNotOpen = stringResource(R.string.onboarding_scanner_did_not_open)
    val openFailed = stringResource(R.string.onboarding_open_failed)
    val openLinkFailed = stringResource(R.string.onboarding_open_link_failed)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.onPermissionsResult() }
    val batteryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { }

    val scanner = rememberQrScanner(
        onCode = viewModel::pair,
        onUnavailable = {
            // The device's own reason stays in the log; the person gets one sentence and two ways on.
            scope.launch { snackbar.showSnackbar(scannerUnavailable) }
            inlineScanner = true
        },
        onFailed = {
            scope.launch { snackbar.showSnackbar(scannerDidNotOpen) }
        }
    )

    LaunchedEffect(viewModel) {
        viewModel.requests.collect { request ->
            when (request) {
                is SetupHostRequest.RequestRuntimePermissions ->
                    permissionLauncher.launch(request.permissions.toTypedArray())

                SetupHostRequest.RequestBatteryExemption ->
                    batteryLauncher.launch(batteryExemptionIntent(context))

                SetupHostRequest.StartPairing ->
                    // The automatic launch belongs to the pairing visit that asked for it. A request
                    // consumed after the wizard advanced (or after an external pairing) must not open
                    // a scanner over the next step; the explicit Scan button does not come through here.
                    if (wizard is WizardState.NeedsPairing) scanner()

                // The rejection is rendered inline, next to the field and the card, not twice.
                is SetupHostRequest.PairingRejected -> Unit

                is SetupHostRequest.Message -> pendingMessage = request.text

                SetupHostRequest.Finished -> onFinished()
            }
        }
    }

    // Resolved in composition, then shown; the state is cleared so a config change does not repeat it.
    val messageText = pendingMessage?.asString()
    LaunchedEffect(messageText) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            pendingMessage = null
        }
    }

    LaunchedEffect(initialPairLink) {
        if (!initialPairLink.isNullOrBlank()) viewModel.pair(initialPairLink)
    }

    LifecycleResumeEffect(Unit) {
        viewModel.onReturnedToForeground()
        onPauseOrDispose { }
    }

    if (inlineScanner) {
        QrScanDialog(
            onCode = { raw ->
                inlineScanner = false
                viewModel.pair(raw)
            },
            onDismiss = { inlineScanner = false },
            // The dialog can promise the paste field and deliver it: it opens over the same screen.
            onPasteRequested = {
                inlineScanner = false
                showPairing = true
            }
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        OnboardingScreen(
            state = OnboardingUiState(
                wizard = wizard,
                pairingError = pairingError,
                leftovers = detection?.leftovers.orEmpty(),
                installPhase = installPhase
            ),
            callbacks = OnboardingCallbacks(
                onPrimary = viewModel::onPrimary,
                onSecondary = viewModel::onSecondary,
                onScanQr = scanner,
                onCameraScan = { inlineScanner = true },
                onPairLink = viewModel::pair,
                onDismissPairingError = viewModel::dismissPairingError,
                onOpenManager = { pkg ->
                    openManager(context, pkg, scope, snackbar, openFailed)
                },
                onOpenUrl = { url ->
                    openUrl(context, url, scope, snackbar, openLinkFailed)
                },
                onOpenDoctor = onOpenDoctor,
                onRebootLater = viewModel::onRebootLater,
                onFinished = onFinished
            ),
            pairingSheetVisible = showPairing,
            onPairingSheetVisibleChange = { showPairing = it },
            modifier = modifier.padding(padding)
        )
    }
}

// The phone is sideloaded as a priv-app, not distributed through Play, and the exemption is the
// documented fix for heartbeat misses with the screen off (mobile client plan §3.6).
@SuppressLint("BatteryLife")
private fun batteryExemptionIntent(context: Context): Intent = Intent(
    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    "package:${context.packageName}".toUri()
)

private fun openManager(
    context: Context,
    packageName: String,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    failureMessage: String
) {
    val launch = context.packageManager.getLaunchIntentForPackage(packageName)
    try {
        if (launch != null) {
            context.startActivity(launch)
        } else {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
            )
        }
    } catch (error: Exception) {
        scope.launch { snackbar.showSnackbar(failureMessage) }
    }
}

private fun openUrl(
    context: Context,
    url: String,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    failureMessage: String
) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (error: Exception) {
        scope.launch { snackbar.showSnackbar(failureMessage) }
    }
}
