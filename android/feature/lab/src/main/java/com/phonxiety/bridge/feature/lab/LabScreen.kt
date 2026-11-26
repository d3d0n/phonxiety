package com.phonxiety.bridge.feature.lab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.PhoneSettings
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ActionEmphasis
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.ScreenScaffold
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatTile
import com.phonxiety.bridge.designsystem.component.StatTone
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.designsystem.component.ToggleRow
import com.phonxiety.bridge.designsystem.formatPhoneNumber
import com.phonxiety.bridge.model.LinkTestResult
import java.text.DateFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
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

/** The tone frequencies the lab offers; a drag cannot land on 1 kHz by accident, a step can. */
internal val TONE_PRESETS_HZ = listOf(300, 440, 700, 1_000, 1_500, 2_000, 3_000, 4_000)

/** The lab route: collects the ViewModel, hands the share sheet to the system, renders the screen. */
@Composable
fun LabRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LabViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
            onExportLogs = viewModel::exportLogs
        ),
        modifier = modifier
    )
}

/** The screen itself: a pure function of [state] so a screenshot can pin every state. */
@Composable
fun LabScreen(
    state: LabUiState,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
    callbacks: LabCallbacks = LabCallbacks()
) {
    ScreenScaffold(
        title = stringResource(R.string.lab_title),
        modifier = modifier,
        onBack = callbacks.onBack,
        snackbarHost = snackbarHost
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LinkTestCard(state.link, callbacks)
            ProbeCard(state.probe, callbacks)
            AudioCard(state, callbacks)
            TestCallCard(state.testCall, callbacks)
            CodecTrialCard(state.trial, callbacks)
            DiagnosticsCard(state.diagnostics, callbacks)
            LogCard(state.logs, callbacks)
        }
    }
    if (state.testCall.confirming) {
        AlertDialog(
            onDismissRequest = callbacks.onDismissTestCall,
            title = { Text(stringResource(R.string.lab_test_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.lab_test_dialog_body,
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

@Composable
private fun LinkTestCard(link: LinkTestUi, callbacks: LabCallbacks) {
    val seconds = link.seconds.toInt()
    val durationName = stringResource(R.string.lab_link_duration)
    val durationState = stringResource(R.string.lab_link_duration_state, seconds)
    SectionCard(
        title = stringResource(R.string.lab_link_title),
        subtitle = stringResource(R.string.lab_link_subtitle),
        status = {
            link.last?.let {
                StatusPill(label = verdictLabel(it.verdict, it.healthy), tone = it.tone())
            }
        }
    ) {
        ChipRow {
            CodecChoice(stringResource(R.string.lab_codec_auto), link.codec == null) { callbacks.onLinkCodec(null) }
            CodecChoice(stringResource(R.string.lab_codec_opus), link.codec == "opus") { callbacks.onLinkCodec("opus") }
            CodecChoice(stringResource(R.string.lab_codec_pcm), link.codec == "pcm") { callbacks.onLinkCodec("pcm") }
        }
        ChipRow {
            CodecChoice(stringResource(R.string.lab_transport_stream), link.transport == "stream") {
                callbacks.onLinkTransport("stream")
            }
            CodecChoice(stringResource(R.string.lab_transport_datagram), link.transport == "datagram") {
                callbacks.onLinkTransport("datagram")
            }
        }
        Text(
            text = "$durationName · $durationState",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = link.seconds,
            onValueChange = callbacks.onLinkSeconds,
            valueRange = 1f..30f,
            modifier = Modifier.semantics {
                contentDescription = durationName
                stateDescription = durationState
            }
        )
        PrimaryActionButton(
            label = stringResource(if (link.running) R.string.lab_link_running else R.string.lab_link_run),
            onClick = callbacks.onRunLinkTest,
            enabled = !link.running,
            busy = link.running
        )
        if (link.running) {
            Text(
                text = stringResource(R.string.lab_link_sending, seconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        link.error?.let { failure ->
            ErrorCard(
                title = stringResource(R.string.lab_link_failed),
                message = failure.message.asString(),
                output = failure.code
            )
        }
        link.last?.let { result ->
            HorizontalDivider()
            LatencyStats(result)
            LatencyPercentileChart(result.p50, result.p95, result.max)
            LinkTestDetails(result)
        }
        if (link.history.isNotEmpty()) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.lab_history_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }
            )
            RttHistoryChart(link.history.mapNotNull { it.p95 }.reversed())
            link.history.take(5).forEach { run -> HistoryRow(run) }
        }
    }
}

/** Four measurements, two per row: the tiles keep their labels at 200 % text instead of colliding. */
@Composable
private fun LatencyStats(result: LinkTestResult) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = stringResource(R.string.lab_stat_median),
                value = result.p50?.let(::monoMillis) ?: stringResource(R.string.lab_none),
                mono = true,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = stringResource(R.string.lab_stat_p95),
                value = result.p95?.let(::monoMillis) ?: stringResource(R.string.lab_none),
                mono = true,
                tone = result.p95Tone(),
                caption = stringResource(R.string.lab_stat_p95_caption),
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = stringResource(R.string.lab_stat_worst),
                value = result.max?.let(::monoMillis) ?: stringResource(R.string.lab_none),
                mono = true,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = stringResource(R.string.lab_stat_loss),
                value = result.loss?.let(::percentText) ?: stringResource(R.string.lab_none),
                mono = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HistoryRow(run: LinkTestResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = localTime(run.at, LocalLabClock.current()) ?: stringResource(R.string.lab_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            StatusPill(label = verdictLabel(run.verdict, run.healthy), tone = run.tone())
        }
        Text(
            text = stringResource(
                R.string.lab_history_row,
                codecLabel(run.codec),
                run.p95?.let(::millisText) ?: stringResource(R.string.lab_none),
                run.loss?.let(::percentText) ?: stringResource(R.string.lab_none)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Each measured fact on its own labelled row, so no value travels without its name. */
@Composable
private fun LinkTestDetails(result: LinkTestResult) {
    result.route?.takeIf { it.isNotBlank() }?.let {
        KeyValueRow(label = stringResource(R.string.lab_link_path), value = it)
    }
    result.transport?.let {
        KeyValueRow(label = stringResource(R.string.lab_link_transport), value = transportLabel(it))
    }
    result.throughputBps?.let {
        KeyValueRow(label = stringResource(R.string.lab_link_throughput), value = rateLabel(it), mono = true)
    }
    val frames = result.frames
    if (frames.sent > 0) {
        KeyValueRow(
            label = stringResource(R.string.lab_link_frames),
            value = stringResource(R.string.lab_link_frames_value, frames.echoed, frames.sent),
            mono = true
        )
    }
}

@Composable
private fun ProbeCard(probe: ProbeUi, callbacks: LabCallbacks) {
    SectionCard(
        title = stringResource(R.string.lab_probe_title),
        subtitle = stringResource(R.string.lab_probe_subtitle)
    ) {
        Text(
            text = stringResource(R.string.lab_probe_paths),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ChipRow {
            PROBE_PATHS.forEach { name ->
                val selected = name in probe.selected
                FilterChip(
                    selected = selected,
                    onClick = { callbacks.onToggleProbeCandidate(name) },
                    label = { Text(pathLabel(name)) },
                    leadingIcon = { SelectedCheck(selected) }
                )
            }
        }
        PrimaryActionButton(
            label = stringResource(if (probe.running) R.string.lab_probe_running else R.string.lab_probe_run),
            onClick = callbacks.onRunProbe,
            enabled = !probe.running && probe.selected.isNotEmpty(),
            busy = probe.running
        )
        if (probe.running) {
            Text(
                text = stringResource(R.string.lab_probe_connecting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        probe.error?.let { failure ->
            ErrorCard(
                title = stringResource(R.string.lab_probe_failed),
                message = failure.message.asString(),
                output = failure.code
            )
        }
        probe.rows.forEach { row -> ProbeRow(row) }
        probe.checkedAt?.let {
            Text(
                text = stringResource(R.string.lab_probe_checked, localTime(it, LocalLabClock.current()) ?: it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A path's own verdict, its endpoint, and — separately — why it was or was not checked. */
@Composable
private fun ProbeRow(row: ProbeRowUi) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = pathLabel(row.key), style = MaterialTheme.typography.bodyMedium)
                row.endpoint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            StatusPill(label = verdictLabel(row.verdict), tone = verdictTone(row.verdict))
        }
        row.note?.let {
            Text(
                text = it.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        row.listenerNote?.let {
            Text(
                text = it.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        row.details?.let { details ->
            var open by rememberSaveable(row.key) { mutableStateOf(false) }
            TextButton(onClick = { open = !open }) {
                Text(stringResource(if (open) R.string.lab_probe_details_hide else R.string.lab_probe_details_show))
            }
            if (open) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AudioCard(state: LabUiState, callbacks: LabCallbacks) {
    val reason = state.audioDisabledReason
    SectionCard(
        title = stringResource(R.string.lab_audio_title),
        subtitle = stringResource(R.string.lab_audio_subtitle)
    ) {
        if (reason != null) {
            Text(
                text = reason.asString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            ToggleRow(
                title = stringResource(R.string.lab_audio_loopback),
                checked = state.audio.loopbackOn,
                onCheckedChange = callbacks.onSetLoopback,
                detail = stringResource(R.string.lab_audio_loopback_detail),
                enabled = !state.audio.busy && state.loopbackSupported != false
            )
            ToneFrequencySlider(audio = state.audio, onToneHz = callbacks.onToneHz)
            ToneLevelSlider(audio = state.audio, onToneDbfs = callbacks.onToneDbfs)
            PrimaryActionButton(
                label = stringResource(if (state.audio.busy) R.string.lab_audio_play_busy else R.string.lab_audio_play),
                onClick = callbacks.onPlayTone,
                enabled = !state.audio.busy && state.toneSupported != false,
                busy = state.audio.busy,
                supportingText = stringResource(R.string.lab_audio_play_supporting, TONE_SECONDS)
            )
        }
        state.audio.note?.let {
            Text(
                text = it.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.audio.error?.let { failure ->
            ErrorCard(
                title = stringResource(R.string.lab_audio_failed),
                message = failure.message.asString(),
                output = failure.code
            )
        }
    }
}

/**
 * The frequency is a list of presets: a 3 900-step drag cannot land on 1 kHz, and a reader who
 * cannot drag moves one step at a time.
 */
@Composable
private fun ToneFrequencySlider(audio: AudioUi, onToneHz: (Int) -> Unit) {
    val name = stringResource(R.string.lab_audio_tone_frequency)
    val valueText = stringResource(R.string.lab_audio_hz, numberText(audio.toneHz))
    val lastIndex = TONE_PRESETS_HZ.lastIndex
    val index = TONE_PRESETS_HZ.indexOfFirst { it >= audio.toneHz }.let { if (it < 0) lastIndex else it }
    NamedSlider(
        name = name,
        valueText = valueText,
        value = index.toFloat(),
        onValueChange = { raw ->
            val snapped = raw.roundToInt().coerceIn(0, lastIndex)
            onToneHz(TONE_PRESETS_HZ[snapped])
        },
        valueRange = 0f..lastIndex.toFloat(),
        steps = (lastIndex - 1).coerceAtLeast(0)
    )
}

/** Level in 2 dB steps; the readout carries the unit the call hears. */
@Composable
private fun ToneLevelSlider(audio: AudioUi, onToneDbfs: (Double) -> Unit) {
    val name = stringResource(R.string.lab_audio_tone_level)
    val valueText = stringResource(R.string.lab_audio_dbfs, numberText(audio.toneDbfs.roundToInt()))
    NamedSlider(
        name = name,
        valueText = valueText,
        value = audio.toneDbfs.toFloat(),
        onValueChange = { raw -> onToneDbfs(((raw / LEVEL_STEP_DB).roundToInt() * LEVEL_STEP_DB).toDouble()) },
        valueRange = -60f..0f,
        steps = 11
    )
}

@Composable
private fun NamedSlider(
    name: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int
) {
    Text(
        text = "$name · $valueText",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        modifier = Modifier.semantics {
            contentDescription = name
            stateDescription = valueText
        }
    )
}

@Composable
private fun TestCallCard(call: TestCallUi, callbacks: LabCallbacks) {
    SectionCard(
        title = stringResource(R.string.lab_test_title),
        subtitle = stringResource(R.string.lab_test_subtitle)
    ) {
        OutlinedTextField(
            value = call.number,
            onValueChange = callbacks.onTestNumber,
            label = { Text(stringResource(R.string.lab_test_number)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.lab_test_number_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PrimaryActionButton(
            label = stringResource(if (call.placing) R.string.lab_test_placing else R.string.lab_test_place),
            onClick = callbacks.onRequestTestCall,
            enabled = call.numberReady && !call.placing,
            busy = call.placing,
            supportingText = if (call.numberReady) null else stringResource(R.string.lab_test_need_number)
        )
        call.callId?.let {
            Text(
                text = stringResource(R.string.lab_test_placed, it),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
        call.error?.let { failure ->
            ErrorCard(
                title = stringResource(R.string.lab_test_failed),
                message = failure.message.asString(),
                output = failure.code
            )
        }
    }
}

@Composable
private fun CodecTrialCard(trial: CodecTrialUi, callbacks: LabCallbacks) {
    SectionCard(
        title = stringResource(R.string.lab_trial_title),
        subtitle = stringResource(R.string.lab_trial_subtitle)
    ) {
        PrimaryActionButton(
            label = trial.step?.asString() ?: stringResource(R.string.lab_trial_run),
            onClick = callbacks.onRunCodecTrial,
            enabled = !trial.running,
            busy = trial.running
        )
        trial.error?.let { failure ->
            ErrorCard(
                title = stringResource(R.string.lab_trial_failed),
                message = failure.message.asString(),
                output = failure.code
            )
        }
        if (trial.runs.isNotEmpty()) {
            HorizontalDivider()
            trial.runs.forEach { run -> TrialRow(run) }
        }
        trial.recommendation?.let { best ->
            val label = codecLabel(best.codec, best.bitrate)
            Text(
                text = stringResource(R.string.lab_trial_recommendation, label),
                style = MaterialTheme.typography.titleSmall
            )
            PrimaryActionButton(
                label = stringResource(R.string.lab_trial_keep, label),
                onClick = callbacks.onApplyRecommendation,
                emphasis = ActionEmphasis.TONAL
            )
        }
    }
}

@Composable
private fun TrialRow(run: CodecTrialRun) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = codecLabel(run.codec, run.bitrate),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            StatusPill(label = verdictLabel(run.result.verdict, run.result.healthy), tone = run.result.tone())
        }
        Text(
            text = stringResource(
                R.string.lab_trial_row,
                run.result.p95?.let(::millisText) ?: stringResource(R.string.lab_none),
                run.result.loss?.let(::percentText) ?: stringResource(R.string.lab_none)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DiagnosticsCard(diagnostics: DiagnosticsUi, callbacks: LabCallbacks) {
    var expanded by rememberSaveable(diagnostics.payload) { mutableStateOf(false) }
    SectionCard(
        title = stringResource(R.string.lab_diagnostics_title),
        subtitle = stringResource(R.string.lab_diagnostics_subtitle)
    ) {
        ActionRow {
            TextButton(onClick = callbacks.onLoadDiagnostics, enabled = !diagnostics.loading) {
                Text(
                    stringResource(
                        if (diagnostics.loading) R.string.lab_diagnostics_loading else R.string.lab_diagnostics_load
                    )
                )
            }
            TextButton(onClick = callbacks.onExportBundle, enabled = !diagnostics.exporting) {
                Text(
                    stringResource(
                        if (diagnostics.exporting) R.string.lab_diagnostics_building else R.string.lab_diagnostics_export
                    )
                )
            }
        }
        diagnostics.error?.let { failure ->
            ErrorCard(
                title = stringResource(R.string.lab_diagnostics_failed),
                message = failure.message.asString(),
                output = failure.code
            )
        }
        diagnostics.bundleId?.let {
            Text(
                text = stringResource(R.string.lab_diagnostics_bundle, it),
                style = MaterialTheme.typography.bodySmall
            )
        }
        diagnostics.payload?.let { payload ->
            val lines = payload.lines()
            PreviewText(
                lines = if (expanded) lines.take(MAX_PREVIEW_LINES) else lines.take(PREVIEW_LINES),
                expanded = expanded,
                more = lines.size > PREVIEW_LINES,
                onToggle = { expanded = !expanded }
            )
        }
    }
}

@Composable
private fun LogCard(logs: LogUi, callbacks: LabCallbacks) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        title = stringResource(R.string.lab_log_title),
        subtitle = stringResource(R.string.lab_log_subtitle)
    ) {
        OutlinedTextField(
            value = logs.filter,
            onValueChange = callbacks.onLogFilter,
            label = { Text(stringResource(R.string.lab_log_filter)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        ChipRow {
            PhoneSettings.LOG_LEVELS.forEach { level ->
                val selected = level == logs.level
                FilterChip(
                    selected = selected,
                    onClick = { callbacks.onLogLevel(level) },
                    label = { Text(logLevelLabel(level)) },
                    leadingIcon = { SelectedCheck(selected) }
                )
            }
        }
        ActionRow {
            TextButton(onClick = callbacks.onCopyLogs, enabled = logs.lines.isNotEmpty()) {
                Text(stringResource(R.string.lab_log_copy))
            }
            TextButton(onClick = callbacks.onExportLogs, enabled = logs.lines.isNotEmpty() && !logs.exporting) {
                Text(stringResource(if (logs.exporting) R.string.lab_log_writing else R.string.lab_log_export))
            }
            Text(
                text = pluralStringResource(R.plurals.lab_log_lines, logs.lines.size, logs.lines.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val lines = logs.lines.takeLast(if (expanded) LOG_MAX_LINES else PREVIEW_LINES)
        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.lab_log_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            PreviewText(
                lines = lines,
                expanded = expanded,
                more = logs.lines.size > PREVIEW_LINES,
                onToggle = { expanded = !expanded }
            )
        }
    }
}

/**
 * A monospace block with a disclosure instead of its own scroll box: a nested scroll swallows the
 * page's drag, and the page is what a reader needs to move.
 */
@Composable
private fun PreviewText(
    lines: List<String>,
    expanded: Boolean,
    more: Boolean,
    onToggle: () -> Unit
) {
    Text(
        text = lines.joinToString("\n"),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace
    )
    if (more) {
        TextButton(onClick = onToggle) {
            Text(stringResource(if (expanded) R.string.lab_show_less else R.string.lab_show_all))
        }
    }
}

@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Buttons wrap instead of scrolling: a clipped label reads as a broken control, not as content. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

@Composable
private fun CodecChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { SelectedCheck(selected) }
    )
}

@Composable
private fun SelectedCheck(selected: Boolean) {
    if (selected) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            modifier = Modifier.size(FilterChipDefaults.IconSize)
        )
    }
}

@Composable
private fun pathLabel(key: String): String = when (key) {
    "iroh" -> stringResource(R.string.lab_path_iroh)
    "lan" -> stringResource(R.string.lab_path_lan)
    "tailscale" -> stringResource(R.string.lab_path_tailscale)
    else -> humanize(key)
}

@Composable
private fun transportLabel(transport: String): String = when (transport.lowercase(Locale.US)) {
    "stream" -> stringResource(R.string.lab_transport_stream)
    "datagram" -> stringResource(R.string.lab_transport_datagram)
    else -> humanize(transport)
}

@Composable
private fun logLevelLabel(level: String): String = when (level.lowercase(Locale.US)) {
    "debug" -> stringResource(R.string.lab_level_debug)
    "info" -> stringResource(R.string.lab_level_info)
    "warn" -> stringResource(R.string.lab_level_warn)
    "error" -> stringResource(R.string.lab_level_error)
    else -> humanize(level)
}

@Composable
private fun codecLabel(codec: String?): String = when (codec?.lowercase(Locale.US)) {
    null -> stringResource(R.string.lab_codec_auto)
    "opus" -> stringResource(R.string.lab_codec_opus)
    "pcm" -> stringResource(R.string.lab_codec_pcm)
    else -> humanize(codec)
}

@Composable
private fun codecLabel(codec: String, bitrate: Int?): String = if (codec == "opus" && bitrate != null) {
    stringResource(R.string.lab_codec_opus_rate, numberText(bitrate / 1_000))
} else {
    codecLabel(codec)
}

@Composable
private fun verdictLabel(verdict: String?, healthy: Boolean): String = when {
    verdict == "good" || (verdict == null && healthy) -> stringResource(R.string.lab_verdict_good)
    verdict == "poor" -> stringResource(R.string.lab_verdict_poor)
    verdict == "failed" -> stringResource(R.string.lab_verdict_failed)
    verdict != null -> humanize(verdict)
    else -> stringResource(R.string.lab_none)
}

@Composable
private fun verdictLabel(verdict: ProbeVerdict): String = when (verdict) {
    ProbeVerdict.REACHED -> stringResource(R.string.lab_probe_reached)
    ProbeVerdict.NOT_REACHED -> stringResource(R.string.lab_probe_not_reached)
    ProbeVerdict.NOT_CHECKED -> stringResource(R.string.lab_probe_not_checked)
}

private fun verdictTone(verdict: ProbeVerdict): StatusTone = when (verdict) {
    ProbeVerdict.REACHED -> StatusTone.POSITIVE
    ProbeVerdict.NOT_REACHED -> StatusTone.WARNING
    ProbeVerdict.NOT_CHECKED -> StatusTone.NEUTRAL
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

/** A latency in a monospace slot: a narrow no-break space keeps the unit close to the digits. */
internal fun monoMillis(millis: Long): String = "$millis\u202Fms"

/** `32\u00A0kbit/s`; one decimal below 10 so a 1 500 bit/s run is not reported as 1 kbit/s. */
internal fun rateLabel(bps: Long): String {
    val kbit = bps / 1_000.0
    val value = if (kbit >= 10) {
        kbit.roundToLong().toString()
    } else {
        String.format(Locale.getDefault(), "%.1f", kbit)
    }
    return "$value\u202Fkbit/s"
}

/** A fraction as the locale writes percentages; a missing measurement never becomes 0 %. */
internal fun percentText(fraction: Double): String {
    val format = NumberFormat.getPercentInstance()
    format.minimumFractionDigits = 1
    format.maximumFractionDigits = 1
    return format.format(fraction)
}

/** A number in the reader's own grouping, so 1 000 and 1,000 are both natural. */
internal fun numberText(value: Number): String {
    val text = NumberFormat.getIntegerInstance().format(value.toLong())
    return text.replace('-', '\u2212')
}

/** The clock that decides whether a stamp is from today; screenshot tests pin it. */
internal val LocalLabClock = staticCompositionLocalOf<() -> Long> { { System.currentTimeMillis() } }

/**
 * Daemon timestamps are UTC ISO-8601; the lab is read on this phone, so a run from today shows its
 * time and an older one shows its date. A stamp without an offset is already local wall-clock (old
 * records and previews) and is kept.
 */
internal fun localTime(at: String?, nowMillis: Long = System.currentTimeMillis()): String? {
    if (at == null) return null
    val zone = ZoneId.systemDefault()
    val millis = (
        runCatching { OffsetDateTime.parse(at) }.getOrNull()?.toInstant()
            ?: runCatching { LocalDateTime.parse(at).atZone(zone).toInstant() }.getOrNull()
        )?.toEpochMilli()
        ?: return at.replace('T', ' ').take(19)
    val format = if (isSameDay(millis, nowMillis, zone)) {
        DateFormat.getTimeInstance(DateFormat.SHORT)
    } else {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }
    return format.format(Date(millis))
}

private fun isSameDay(first: Long, second: Long, zone: ZoneId): Boolean {
    val left = Instant.ofEpochMilli(first).atZone(zone).toLocalDate()
    val right = Instant.ofEpochMilli(second).atZone(zone).toLocalDate()
    return left == right
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

private const val LEVEL_STEP_DB = 5f
private const val PREVIEW_LINES = 12
private const val MAX_PREVIEW_LINES = 500
private const val LOG_MAX_LINES = 500
private const val TONE_SECONDS = 5
private val PROBE_PATHS = listOf("iroh", "lan", "tailscale")
