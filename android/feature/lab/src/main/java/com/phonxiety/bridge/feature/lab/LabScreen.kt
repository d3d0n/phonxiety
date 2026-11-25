package com.phonxiety.bridge.feature.lab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.PhoneSettings
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatTile
import com.phonxiety.bridge.designsystem.component.StatTone
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.model.LinkTestResult
import com.phonxiety.bridge.model.ProbeCandidate
import java.util.Locale
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
    val onRunCodecTrial: () -> Unit = {},
    val onApplyRecommendation: () -> Unit = {},
    val onLoadDiagnostics: () -> Unit = {},
    val onExportBundle: () -> Unit = {},
    val onLogFilter: (String) -> Unit = {},
    val onLogLevel: (String) -> Unit = {},
    val onCopyLogs: () -> Unit = {},
    val onExportLogs: () -> Unit = {}
)

/** The lab route: collects the ViewModel, hands the share sheet to the system, renders the screen. */
@Composable
fun LabRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LabViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.share) {
        val share = state.share ?: return@LaunchedEffect
        val launched = runCatching {
            context.startActivity(
                android.content.Intent.createChooser(
                    LabShare.intent(share.uri, share.mimeType, share.title),
                    share.title
                )
            )
        }
        if (launched.isFailure) snackbar.showSnackbar("No app accepted the file")
        viewModel.consumeShare()
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LabScreen(
            state = state,
            callbacks = LabCallbacks(
                onBack = onBack,
                onLinkSeconds = viewModel::setLinkSeconds,
                onLinkCodec = viewModel::setLinkCodec,
                onLinkTransport = viewModel::setLinkTransport,
                onRunLinkTest = viewModel::runLinkTest,
                onToggleProbeCandidate = viewModel::toggleProbeCandidate,
                onRunProbe = viewModel::runProbe,
                onSetLoopback = viewModel::setLoopback,
                onToneHz = { viewModel.setToneHz(it.toInt()) },
                onToneDbfs = viewModel::setToneDbfs,
                onPlayTone = viewModel::playTone,
                onTestNumber = viewModel::setTestNumber,
                onRequestTestCall = viewModel::requestTestCall,
                onDismissTestCall = viewModel::dismissTestCall,
                onConfirmTestCall = viewModel::confirmTestCall,
                onRunCodecTrial = viewModel::runCodecTrial,
                onApplyRecommendation = viewModel::applyTrialRecommendation,
                onLoadDiagnostics = viewModel::loadDiagnostics,
                onExportBundle = viewModel::exportBundle,
                onLogFilter = viewModel::setLogFilter,
                onLogLevel = viewModel::setLogLevel,
                onCopyLogs = {
                    copyToClipboard(context, "Phonxiety log", viewModel.logText())
                    scope.launch { snackbar.showSnackbar("Log copied") }
                },
                onExportLogs = viewModel::exportLogs
            ),
            modifier = modifier.padding(padding)
        )
    }
}

/** The screen itself: a pure function of [state] so a screenshot can pin every state. */
@Composable
fun LabScreen(
    state: LabUiState,
    modifier: Modifier = Modifier,
    callbacks: LabCallbacks = LabCallbacks()
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Header(title = "Test lab", onBack = callbacks.onBack)
        LinkTestCard(state.link, callbacks)
        ProbeCard(state.probe, callbacks)
        AudioCard(state, callbacks)
        TestCallCard(state.testCall, callbacks)
        CodecTrialCard(state.trial, callbacks)
        DiagnosticsCard(state.diagnostics, callbacks)
        LogCard(state.logs, callbacks)
    }
    if (state.testCall.confirming) {
        AlertDialog(
            onDismissRequest = callbacks.onDismissTestCall,
            title = { Text("Place the test call?") },
            text = {
                Text(
                    "The lab dials ${state.testCall.number} with a canned brief, " +
                        "stops at ${state.testCall.maxDurationSeconds} s and never retries. " +
                        "The number is the one configured here, not a typed one."
                )
            },
            confirmButton = {
                Button(onClick = callbacks.onConfirmTestCall) { Text("Call") }
            },
            dismissButton = {
                TextButton(onClick = callbacks.onDismissTestCall) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun LinkTestCard(link: LinkTestUi, callbacks: LabCallbacks) {
    SectionCard(
        title = "Link test",
        subtitle = "Call-sized frames in both directions; the numbers are what the link carried",
        status = {
            link.last?.let { StatusPill(it.verdict ?: if (it.healthy) "good" else "poor", it.tone()) }
        }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CodecChoice("Auto", link.codec == null, Modifier.weight(1f)) { callbacks.onLinkCodec(null) }
            CodecChoice("Opus", link.codec == "opus", Modifier.weight(1f)) { callbacks.onLinkCodec("opus") }
            CodecChoice("PCM", link.codec == "pcm", Modifier.weight(1f)) { callbacks.onLinkCodec("pcm") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CodecChoice("Stream", link.transport == "stream", Modifier.weight(1f)) { callbacks.onLinkTransport("stream") }
            CodecChoice("Datagram", link.transport == "datagram", Modifier.weight(1f)) { callbacks.onLinkTransport("datagram") }
        }
        Text(
            text = "Duration ${link.seconds.toInt()} s",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = link.seconds,
            onValueChange = callbacks.onLinkSeconds,
            valueRange = 1f..30f,
            steps = 28
        )
        PrimaryActionButton(
            label = if (link.running) "Measuring…" else "Run link test",
            onClick = callbacks.onRunLinkTest,
            enabled = !link.running
        )
        if (link.running) LoadingState(message = "Sending frames for ${link.seconds.toInt()} s")
        link.error?.let { ErrorCard(title = "Failed", message = it) }
        link.last?.let { result ->
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatTile("p50", result.p50?.let { "$it ms" } ?: "—", mono = true)
                StatTile("p95", result.p95?.let { "$it ms" } ?: "—", mono = true, tone = result.p95Tone())
                StatTile("max", result.max?.let { "$it ms" } ?: "—", mono = true)
                StatTile("loss", result.loss?.let { "${(it * 100).format(1)}%" } ?: "—", mono = true)
            }
            LatencyPercentileChart(result.p50, result.p95, result.max)
            LinkTestDetails(result)
        }
        if (link.history.isNotEmpty()) {
            HorizontalDivider()
            Text("History", style = MaterialTheme.typography.titleSmall)
            RttHistoryChart(link.history.mapNotNull { it.p95 }.reversed())
            link.history.take(5).forEach { run ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = run.at?.let { it.replace('T', ' ').take(19) } ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${run.codec ?: "auto"} · p95 ${run.p95 ?: "—"} · ${(run.loss ?: 0.0).times(100).format(1)}%",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    StatusPill(run.verdict ?: "—", run.tone())
                }
            }
        }
    }
}

@Composable
private fun LinkTestDetails(result: LinkTestResult) {
    Text(
        text = listOfNotNull(
            result.route?.let { "route $it" },
            result.transport?.let { "$it transport" },
            result.throughputBps?.let { "${it / 1000} kbit/s" },
            result.frames.takeIf { it.sent > 0 }?.let { "${it.echoed}/${it.sent} frames" }
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ProbeCard(probe: ProbeUi, callbacks: LabCallbacks) {
    SectionCard(
        title = "Path probe",
        subtitle = "What the daemon can reach, from the daemon's side"
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("iroh", "lan", "tailscale").forEach { name ->
                FilterChip(
                    selected = name in probe.selected,
                    onClick = { callbacks.onToggleProbeCandidate(name) },
                    label = { Text(name) }
                )
            }
        }
        PrimaryActionButton(
            label = if (probe.running) "Probing…" else "Probe selected paths",
            onClick = callbacks.onRunProbe,
            enabled = !probe.running && probe.selected.isNotEmpty()
        )
        if (probe.running) LoadingState(message = "Connecting to each candidate")
        probe.error?.let { ErrorCard(title = "Failed", message = it) }
        probe.candidates.forEach { candidate -> ProbeRow(candidate) }
        probe.checkedAt?.let {
            Text(
                text = "checked $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProbeRow(candidate: ProbeCandidate) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(candidate.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = listOfNotNull(
                    candidate.address,
                    candidate.rttMs?.let { "${it.format(0)} ms" },
                    candidate.detail,
                    candidate.reason
                ).joinToString(" · ").ifEmpty { "no detail" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StatusPill(
            label = when {
                candidate.reachable -> "reachable"
                candidate.available -> "unreachable"
                else -> "unavailable"
            },
            tone = when {
                candidate.reachable -> StatusTone.POSITIVE
                candidate.available -> StatusTone.WARNING
                else -> StatusTone.NEUTRAL
            }
        )
    }
}

@Composable
private fun AudioCard(state: LabUiState, callbacks: LabCallbacks) {
    val reason = state.audioDisabledReason
    SectionCard(
        title = "Loopback and tone",
        subtitle = "Both run inside an owned call; the audio path is the real one"
    ) {
        if (reason != null) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Echo the uplink back", modifier = Modifier.weight(1f))
                Switch(
                    checked = state.audio.loopbackOn,
                    onCheckedChange = callbacks.onSetLoopback,
                    enabled = !state.audio.busy && state.loopbackSupported != false
                )
            }
            Text(
                text = "Tone ${state.audio.toneHz} Hz at ${state.audio.toneDbfs.format(0)} dBFS for 5 s",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = state.audio.toneHz.toFloat(),
                onValueChange = { callbacks.onToneHz(it.toInt()) },
                valueRange = 100f..4_000f
            )
            Slider(
                value = state.audio.toneDbfs.toFloat(),
                onValueChange = { callbacks.onToneDbfs(it.toDouble()) },
                valueRange = -60f..0f
            )
            PrimaryActionButton(
                label = if (state.audio.busy) "Working…" else "Play tone",
                onClick = callbacks.onPlayTone,
                enabled = !state.audio.busy && state.toneSupported != false
            )
        }
        state.audio.note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.audio.error?.let { ErrorCard(title = "Failed", message = it) }
    }
}

@Composable
private fun TestCallCard(call: TestCallUi, callbacks: LabCallbacks) {
    SectionCard(
        title = "Test call",
        subtitle = "A real call with a canned brief, at most ${call.maxDurationSeconds} s, never retried"
    ) {
        OutlinedTextField(
            value = call.number,
            onValueChange = callbacks.onTestNumber,
            label = { Text("Test number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "The lab only dials the number configured here; nothing else can be called from this screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PrimaryActionButton(
            label = if (call.placing) "Placing…" else "Place a ${call.maxDurationSeconds} s test call",
            onClick = callbacks.onRequestTestCall,
            enabled = call.numberReady && !call.placing
        )
        if (!call.numberReady) {
            Text(
                text = "Set the test number first",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        call.callId?.let {
            Text("Placed: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        call.error?.let { ErrorCard(title = "Failed", message = it) }
    }
}

@Composable
private fun CodecTrialCard(trial: CodecTrialUi, callbacks: LabCallbacks) {
    SectionCard(
        title = "Codec trial",
        subtitle = "The same probe at Opus 16, 32, 64 kbit/s and PCM, then the best one is kept"
    ) {
        PrimaryActionButton(
            label = if (trial.running) trial.step ?: "Measuring…" else "Compare codecs",
            onClick = callbacks.onRunCodecTrial,
            enabled = !trial.running
        )
        trial.error?.let { ErrorCard(title = "Failed", message = it) }
        if (trial.runs.isNotEmpty()) {
            HorizontalDivider()
            trial.runs.forEach { run ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(run.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "p95 ${run.result.p95 ?: "—"} · loss ${(run.result.loss ?: 0.0).times(100).format(1)}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    StatusPill(run.result.verdict ?: "—", run.result.tone())
                }
            }
        }
        trial.recommendation?.let { best ->
            Text(
                text = "Recommendation: ${best.label}",
                style = MaterialTheme.typography.titleSmall
            )
            PrimaryActionButton(
                label = "Keep ${best.label}",
                onClick = callbacks.onApplyRecommendation
            )
        }
    }
}

@Composable
private fun DiagnosticsCard(diagnostics: DiagnosticsUi, callbacks: LabCallbacks) {
    SectionCard(
        title = "Diagnostics",
        subtitle = "The daemon's own view: versions, paths, counters, redacted settings"
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = callbacks.onLoadDiagnostics, enabled = !diagnostics.loading) {
                Text(if (diagnostics.loading) "Reading…" else "Load")
            }
            TextButton(onClick = callbacks.onExportBundle, enabled = !diagnostics.exporting) {
                Text(if (diagnostics.exporting) "Building…" else "Export bundle")
            }
        }
        diagnostics.error?.let { ErrorCard(title = "Failed", message = it) }
        diagnostics.bundleId?.let {
            Text("Bundle $it downloaded and shared", style = MaterialTheme.typography.bodySmall)
        }
        diagnostics.payload?.let { payload ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = payload,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun LogCard(logs: LogUi, callbacks: LabCallbacks) {
    SectionCard(
        title = "App log",
        subtitle = "The bridge's own recent lines, kept in memory"
    ) {
        OutlinedTextField(
            value = logs.filter,
            onValueChange = callbacks.onLogFilter,
            label = { Text("Filter") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PhoneSettings.LOG_LEVELS.forEach { level ->
                FilterChip(
                    selected = level == logs.level,
                    onClick = { callbacks.onLogLevel(level) },
                    label = { Text(level) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = callbacks.onCopyLogs, enabled = logs.lines.isNotEmpty()) { Text("Copy") }
            TextButton(onClick = callbacks.onExportLogs, enabled = logs.lines.isNotEmpty() && !logs.exporting) {
                Text(if (logs.exporting) "Writing…" else "Share")
            }
            Text(
                text = "${logs.lines.size} lines",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = logs.lines.takeLast(500).joinToString("\n").ifEmpty { "Nothing logged at this level yet" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun CodecChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier
    )
}

private fun LinkTestResult.tone(): StatusTone = when {
    verdict == "good" || healthy -> StatusTone.POSITIVE
    verdict == "failed" -> StatusTone.NEGATIVE
    verdict == "poor" -> StatusTone.WARNING
    else -> StatusTone.NEUTRAL
}

private fun LinkTestResult.p95Tone(): StatTone {
    val value = p95 ?: return StatTone.NEUTRAL
    return when {
        value > 400 -> StatTone.NEGATIVE
        value > 200 -> StatTone.WARNING
        else -> StatTone.POSITIVE
    }
}

private fun Double.format(digits: Int): String = String.format(Locale.US, "%.${digits}f", this)

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
        probe = ProbeUi(
            candidates = listOf(
                ProbeCandidate("iroh", available = true, reachable = true, rttMs = 14.0, detail = "direct path"),
                ProbeCandidate("lan", available = true, reachable = true, rttMs = 3.0, address = "192.168.1.20"),
                ProbeCandidate("tailscale", available = false, reason = "not_installed")
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
