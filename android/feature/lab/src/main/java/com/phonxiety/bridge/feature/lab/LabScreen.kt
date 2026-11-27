package com.phonxiety.bridge.feature.lab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.RegisterScrollToTop
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ScreenScaffold
import com.phonxiety.bridge.designsystem.formatPhoneNumber
import com.phonxiety.bridge.feature.lab.cards.AudioCard
import com.phonxiety.bridge.feature.lab.cards.CodecTrialCard
import com.phonxiety.bridge.feature.lab.cards.DiagnosticsCard
import com.phonxiety.bridge.feature.lab.cards.FrameTapCard
import com.phonxiety.bridge.feature.lab.cards.LinkTestCard
import com.phonxiety.bridge.feature.lab.cards.LogCard
import com.phonxiety.bridge.feature.lab.cards.ProbeCard
import com.phonxiety.bridge.feature.lab.cards.TestCallCard
import com.phonxiety.bridge.feature.lab.cards.TestCallResultCard
import com.phonxiety.bridge.model.LinkTestResult
import kotlinx.coroutines.launch

/** Every action the lab screen offers; the route wires them to [LabViewModel]. */
data class LabCallbacks(
    val onBack: () -> Unit = {},
    val onLinkSeconds: (Float) -> Unit = {},
    val onLinkCodec: (String?) -> Unit = {},
    val onLinkTransport: (String) -> Unit = {},
    val onRunLinkTest: () -> Unit = {},
    val onToggleProbeCandidate: (String) -> Unit = {},
    val onRunProbe: () -> Unit = {},
    val onSetLoopback: (Boolean) -> Unit = {},
    val onToneHz: (Int) -> Unit = {},
    val onToneDbfs: (Double) -> Unit = {},
    val onPlayTone: () -> Unit = {},
    val onTestNumber: (String) -> Unit = {},
    val onRequestTestCall: () -> Unit = {},
    val onDismissTestCall: () -> Unit = {},
    val onConfirmTestCall: () -> Unit = {},
    val onTestLoopback: (Boolean) -> Unit = {},
    val onTestTone: (Boolean) -> Unit = {},
    val onTestToneSeconds: (Int) -> Unit = {},
    val onOpenCall: (String) -> Unit = {},
    val onRunCodecTrial: () -> Unit = {},
    val onApplyRecommendation: () -> Unit = {},
    val onLoadDiagnostics: () -> Unit = {},
    val onExportBundle: () -> Unit = {},
    val onLogFilter: (String) -> Unit = {},
    val onLogLevel: (String) -> Unit = {},
    val onCopyLogs: () -> Unit = {},
    val onExportLogs: () -> Unit = {},
    val onFrameTap: (Boolean) -> Unit = {}
)

/** The frozen key the navigation host re-taps to bring this section's first item back. */
internal const val LAB_SCROLL_TO_TOP_KEY = "lab"

/** The lab route: collects the ViewModel, hands the share sheet to the system, renders the screen. */
@Composable
fun LabRoute(
    onBack: () -> Unit,
    onOpenCall: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LabViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Leaving the Lab — a push to the call record included — stops the record read, and coming
    // back resumes it unless what it read was already complete.
    DisposableEffect(viewModel) {
        viewModel.onLabShown()
        onDispose { viewModel.onLabHidden() }
    }

    LaunchedEffect(state.share) {
        val share = state.share ?: return@LaunchedEffect
        val title = share.title.asString(resources)
        val launched = runCatching {
            context.startActivity(
                android.content.Intent.createChooser(
                    LabShare.intent(share.uri, share.mimeType, title),
                    title
                )
            )
        }
        if (launched.isFailure) snackbar.showSnackbar(resources.getString(R.string.lab_share_failed))
        viewModel.consumeShare()
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message.asString(resources))
        viewModel.consumeMessage()
    }

    LabScreen(
        state = state,
        snackbarHost = { SnackbarHost(snackbar) },
        callbacks = LabCallbacks(
            onBack = onBack,
            onLinkSeconds = viewModel::setLinkSeconds,
            onLinkCodec = viewModel::setLinkCodec,
            onLinkTransport = viewModel::setLinkTransport,
            onRunLinkTest = viewModel::runLinkTest,
            onToggleProbeCandidate = viewModel::toggleProbeCandidate,
            onRunProbe = viewModel::runProbe,
            onSetLoopback = viewModel::setLoopback,
            onToneHz = viewModel::setToneHz,
            onToneDbfs = viewModel::setToneDbfs,
            onPlayTone = viewModel::playTone,
            onTestNumber = viewModel::setTestNumber,
            onRequestTestCall = viewModel::requestTestCall,
            onDismissTestCall = viewModel::dismissTestCall,
            onConfirmTestCall = viewModel::confirmTestCall,
            onTestLoopback = viewModel::setTestCallLoopback,
            onTestTone = viewModel::setTestCallTone,
            onTestToneSeconds = viewModel::setTestCallToneSeconds,
            onOpenCall = onOpenCall,
            onRunCodecTrial = viewModel::runCodecTrial,
            onApplyRecommendation = viewModel::applyTrialRecommendation,
            onLoadDiagnostics = viewModel::loadDiagnostics,
            onExportBundle = viewModel::exportBundle,
            onLogFilter = viewModel::setLogFilter,
            onLogLevel = viewModel::setLogLevel,
            onCopyLogs = {
                copyToClipboard(context, "Phonxiety log", viewModel.logText())
                scope.launch { snackbar.showSnackbar(resources.getString(R.string.lab_log_copied)) }
            },
            onExportLogs = viewModel::exportLogs,
            onFrameTap = viewModel::setFrameTap
        ),
        modifier = modifier
    )
}

/**
 * The screen itself: a pure function of [state] so a screenshot can pin every state. The page
 * registers its own scroll action under [LAB_SCROLL_TO_TOP_KEY], so re-tapping the section's tab
 * returns to the link card.
 */
@Composable
fun LabScreen(
    state: LabUiState,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
    callbacks: LabCallbacks = LabCallbacks()
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    RegisterScrollToTop(LAB_SCROLL_TO_TOP_KEY) { scope.launch { scrollState.animateScrollTo(0) } }
    ScreenScaffold(
        title = stringResource(R.string.lab_title),
        modifier = modifier,
        onBack = callbacks.onBack,
        snackbarHost = snackbarHost
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LinkTestCard(state.link, callbacks)
            ProbeCard(state.probe, callbacks)
            AudioCard(state, callbacks)
            TestCallCard(state, callbacks)
            state.testCall.result?.let { result -> TestCallResultCard(result, callbacks.onOpenCall) }
            CodecTrialCard(state.trial, callbacks)
            DiagnosticsCard(state.diagnostics, callbacks)
            LogCard(state.logs, callbacks)
            FrameTapCard(state.frameTap, callbacks)
        }
    }
    if (state.testCall.confirming) {
        AlertDialog(
            onDismissRequest = callbacks.onDismissTestCall,
            title = { Text(stringResource(R.string.lab_test_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        if (state.testCallLoopback || state.testCallTone) {
                            R.string.lab_test_dialog_body_probes
                        } else {
                            R.string.lab_test_dialog_body
                        },
                        formatPhoneNumber(state.testCall.number)
                    )
                )
            },
            confirmButton = {
                Button(onClick = callbacks.onConfirmTestCall) {
                    Text(stringResource(R.string.lab_test_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = callbacks.onDismissTestCall) {
                    Text(stringResource(R.string.lab_test_dialog_cancel))
                }
            }
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** A phone's history with a plausible run, for the screenshot harness and previews. */
internal fun previewLabState(): LabUiState {
    val run = LinkTestResult(
        at = "2026-11-26T10:24:05",
        codec = "opus",
        transport = "stream",
        route = "direct 192.168.1.20, 6 ms",
        seconds = 5.0,
        p50 = 38,
        p95 = 96,
        max = 180,
        loss = 0.0,
        throughputBps = 128_000,
        healthy = true,
        verdict = "good"
    )
    return LabUiState(
        link = LinkTestUi(
            seconds = 5f,
            last = run,
            history = listOf(run, run.copy(p95 = 140, at = "2026-11-26T09:51:11"), run.copy(p95 = 62))
        ),
        callPhase = "active",
        callId = "call-1",
        loopbackSupported = true,
        toneSupported = true,
        probe = ProbeUi(
            rows = listOf(
                ProbeRowUi(
                    key = "iroh",
                    verdict = ProbeVerdict.REACHED,
                    rttMs = 14,
                    note = UiText.Res(R.string.lab_probe_note_bridge_reached, listOf(millisText(14)))
                ),
                ProbeRowUi(
                    key = "lan",
                    endpoint = "192.168.1.20:18763",
                    verdict = ProbeVerdict.NOT_REACHED,
                    note = UiText.Res(R.string.lab_probe_note_unreached, listOf("192.168.1.20:18763")),
                    listenerNote = UiText.Res(R.string.lab_probe_listener_answers)
                ),
                ProbeRowUi(
                    key = "tailscale",
                    verdict = ProbeVerdict.NOT_CHECKED,
                    note = UiText.Res(R.string.lab_probe_reason_not_installed)
                )
            )
        ),
        testCall = TestCallUi(number = "+12025550123")
    )
}

@Preview
@Composable
private fun LabScreenPreview() {
    PhonxietyTheme(dynamicColor = false) {
        LabScreen(state = previewLabState())
    }
}
