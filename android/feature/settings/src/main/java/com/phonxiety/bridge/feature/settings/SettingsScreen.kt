package com.phonxiety.bridge.feature.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.PhoneCodec
import com.phonxiety.bridge.PhoneSettings
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.net.ConnectionMode
import com.phonxiety.bridge.net.RelayModeSetting
import com.phonxiety.bridge.net.RelayOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every action the settings screen offers; the route wires them to [SettingsViewModel]. */
data class SettingsCallbacks(
    val onBack: () -> Unit = {},
    val onForm: ((DaemonForm) -> DaemonForm) -> Unit = {},
    val onSaveDaemon: () -> Unit = {},
    val onRefreshDaemon: () -> Unit = {},
    val onCodec: (String) -> Unit = {},
    val onMaxBitrate: (Int) -> Unit = {},
    val onWifiLowLatency: (Boolean) -> Unit = {},
    val onThermalAwareness: (Boolean) -> Unit = {},
    val onBatteryAwareness: (Boolean) -> Unit = {},
    val onLogLevel: (String) -> Unit = {},
    val onAutoTune: (Boolean) -> Unit = {},
    val onEvaluateNow: () -> Unit = {},
    val onRevertChange: (String) -> Unit = {},
    val onRevertEverything: () -> Unit = {},
    val onConnectionMode: (String) -> Unit = {},
    val onRelayMode: (String) -> Unit = {},
    val onRelayUrls: (String) -> Unit = {},
    val onRefreshCandidates: () -> Unit = {}
)

/** Settings and auto-tune; the daemon's values and the phone's own, side by side. */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.consumeMessage()
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        SettingsScreen(
            state = state,
            callbacks = SettingsCallbacks(
                onBack = onBack,
                onForm = viewModel::updateForm,
                onSaveDaemon = viewModel::saveDaemonSettings,
                onRefreshDaemon = viewModel::refreshDaemonSettings,
                onCodec = viewModel::setCodec,
                onMaxBitrate = viewModel::setMaxBitrate,
                onWifiLowLatency = viewModel::setWifiLowLatency,
                onThermalAwareness = viewModel::setThermalAwareness,
                onBatteryAwareness = viewModel::setBatteryAwareness,
                onLogLevel = viewModel::setLogLevel,
                onAutoTune = viewModel::setAutoTuneEnabled,
                onEvaluateNow = viewModel::evaluateNow,
                onRevertChange = viewModel::revertChange,
                onRevertEverything = viewModel::revertEverything,
                onConnectionMode = viewModel::setConnectionMode,
                onRelayMode = viewModel::setRelayMode,
                onRelayUrls = viewModel::setRelayUrls,
                onRefreshCandidates = viewModel::refreshCandidates
            ),
            modifier = modifier.padding(padding)
        )
    }
}

/** The screen itself: a pure function of [state], so a screenshot can pin it. */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    modifier: Modifier = Modifier,
    callbacks: SettingsCallbacks = SettingsCallbacks()
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = callbacks.onBack) { Text("Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        ConnectionCard(state.phone, state.connection, callbacks)
        AudioCard(state.phone, callbacks)
        DeviceCard(state.phone, callbacks)
        DaemonCard(state.daemon, callbacks)
        AutoTuneCard(state.autoTune, callbacks)
    }
}

@Composable
private fun ConnectionCard(phone: PhoneSettings, connection: ConnectionUi, callbacks: SettingsCallbacks) {
    val mode = ConnectionMode.of(phone.connectionMode)
    val relayMode = RelayModeSetting.of(phone.relayMode)
    SectionCard(
        title = "Connection",
        subtitle = "Which transport reaches the daemon, and which relays the phone's iroh endpoint uses",
        status = {
            StatusPill(
                label = if (mode == ConnectionMode.AUTO) "auto" else mode.label,
                tone = if (mode == ConnectionMode.AUTO) StatusTone.NEUTRAL else StatusTone.POSITIVE
            )
        }
    ) {
        Text("Transport", style = MaterialTheme.typography.labelMedium)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConnectionMode.entries.forEach { entry ->
                FilterChip(
                    selected = mode == entry,
                    onClick = { callbacks.onConnectionMode(entry.wire) },
                    label = { Text(entry.label) }
                )
            }
        }
        Text(
            text = "Automatic probes every known address and keeps the best one; a pinned mode uses only that " +
                "transport, and the link never moves during a call.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()
        Text("iroh relays", style = MaterialTheme.typography.labelMedium)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RelayModeSetting.entries.forEach { entry ->
                FilterChip(
                    selected = relayMode == entry,
                    onClick = { callbacks.onRelayMode(entry.wire) },
                    label = { Text(entry.label) }
                )
            }
        }
        when (relayMode) {
            RelayModeSetting.CUSTOM -> FormField(
                label = "This phone's relay URLs, one per line",
                value = RelayOptions.join(phone.relayUrls),
                singleLine = false
            ) { callbacks.onRelayUrls(it) }

            RelayModeSetting.DAEMON -> {
                val daemonRelays = phone.daemonRelayUrls
                Text(
                    text = if (daemonRelays.isEmpty()) {
                        "The daemon lists no relays; its endpoint uses the defaults."
                    } else {
                        "From the daemon: ${daemonRelays.joinToString()}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> Unit
        }

        HorizontalDivider()
        Text("Known addresses", style = MaterialTheme.typography.labelMedium)
        connection.error?.let { ErrorCard(title = "Candidates", message = it) }
        Text(
            text = if (connection.candidates.isEmpty()) {
                "None yet: the daemon reports its LAN and tailnet addresses when it is reachable."
            } else {
                "${connection.candidates.size} address(es): ${connection.lanCount} on the LAN, ${connection.tailnetCount} in the tailnet"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = callbacks.onRefreshCandidates, enabled = !connection.refreshing) {
            Text(if (connection.refreshing) "Reading…" else "Refresh from the daemon")
        }
    }
}

@Composable
private fun AudioCard(phone: PhoneSettings, callbacks: SettingsCallbacks) {
    SectionCard(
        title = "Audio",
        subtitle = "The phone's preference travels in hello and set_prefs; it decides the next call"
    ) {
        Text("Codec", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(PhoneCodec.AUTO to "Auto", PhoneCodec.OPUS to "Opus", PhoneCodec.PCM to "PCM").forEach { (value, label) ->
                FilterChip(
                    selected = phone.preferredCodec == value,
                    onClick = { callbacks.onCodec(value) },
                    label = { Text(label) }
                )
            }
        }
        Text(
            text = "Max bitrate ${phone.maxBitrate / 1000} kbit/s",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = phone.maxBitrate.toFloat(),
            onValueChange = { callbacks.onMaxBitrate(it.toInt()) },
            valueRange = PhoneSettings.MIN_BITRATE.toFloat()..PhoneSettings.MAX_BITRATE.toFloat()
        )
        HorizontalDivider()
        ToggleRow(
            label = "Wi-Fi low-latency lock",
            detail = "Held while the link is up; released when auto-tune sees heat or a low battery",
            checked = phone.wifiLowLatency,
            onChange = callbacks.onWifiLowLatency
        )
        ToggleRow(
            label = "Watch thermal status",
            detail = "Let auto-tune release the Wi-Fi lock on a hot phone",
            checked = phone.thermalAwareness,
            onChange = callbacks.onThermalAwareness
        )
        ToggleRow(
            label = "Watch the battery",
            detail = "Let auto-tune release the Wi-Fi lock below ${AutoTune.LOW_BATTERY_PERCENT} %",
            checked = phone.batteryAwareness,
            onChange = callbacks.onBatteryAwareness
        )
    }
}

@Composable
private fun DeviceCard(phone: PhoneSettings, callbacks: SettingsCallbacks) {
    SectionCard(
        title = "Device",
        subtitle = "How much this phone records for the log viewer"
    ) {
        Text("Log level", style = MaterialTheme.typography.labelMedium)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhoneSettings.LOG_LEVELS.forEach { level ->
                FilterChip(
                    selected = phone.logLevel == level,
                    onClick = { callbacks.onLogLevel(level) },
                    label = { Text(level) }
                )
            }
        }
        Text(
            text = "The log viewer in the test lab keeps the lines at or above this level; the system log always gets everything.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DaemonCard(daemon: DaemonSettingsUi, callbacks: SettingsCallbacks) {
    SectionCard(
        title = "Daemon settings",
        subtitle = "Read from the daemon; a save sends only the changed keys",
        status = {
            when {
                daemon.saving -> StatusPill("saving", StatusTone.WARNING, pulse = true)
                daemon.dirty -> StatusPill("edited", StatusTone.WARNING)
                else -> StatusPill("loaded", StatusTone.NEUTRAL)
            }
        }
    ) {
        daemon.error?.let { ErrorCard(title = "Failed", message = it) }
        if (daemon.changesApplied.isNotEmpty()) {
            Text(
                text = "Applied: ${daemon.changesApplied.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (daemon.restartRequiredFields.isNotEmpty()) {
            Text(
                text = "Needs a daemon restart: ${daemon.restartRequiredFields.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Text("Connection", style = MaterialTheme.typography.titleSmall)
        FormField("Relay URLs, one per line", daemon.form.irohRelays, singleLine = false) { value ->
            callbacks.onForm { it.copy(irohRelays = value) }
        }

        Text("Daemon audio default (used when the phone says Auto)", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("opus", "pcm").forEach { codec ->
                FilterChip(
                    selected = daemon.form.audioCodec == codec,
                    onClick = { callbacks.onForm { it.copy(audioCodec = codec) } },
                    label = { Text(codec) }
                )
            }
        }
        FormField("Opus bitrate, bit/s", daemon.form.opusBitrate, numeric = true) { value ->
            callbacks.onForm { it.copy(opusBitrate = value) }
        }

        Text("Calls", style = MaterialTheme.typography.titleSmall)
        FormField("Ring timeout, s", daemon.form.ringTimeout, numeric = true) { value ->
            callbacks.onForm { it.copy(ringTimeout = value) }
        }
        FormField("Max duration, s", daemon.form.maxDuration, numeric = true) { value ->
            callbacks.onForm { it.copy(maxDuration = value) }
        }
        FormField("Default result fields, comma separated", daemon.form.defaultResultFields) { value ->
            callbacks.onForm { it.copy(defaultResultFields = value) }
        }
        FormField("Default constraints", daemon.form.defaultConstraints, singleLine = false) { value ->
            callbacks.onForm { it.copy(defaultConstraints = value) }
        }
        FormField("Language hint (en, ru-RU, …)", daemon.form.languageHint) { value ->
            callbacks.onForm { it.copy(languageHint = value) }
        }

        Text("Voice", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("codex", "gemini", "deepgram").forEach { backend ->
                FilterChip(
                    selected = daemon.form.voiceBackend == backend,
                    onClick = { callbacks.onForm { it.copy(voiceBackend = backend) } },
                    label = { Text(backend) }
                )
            }
        }
        FormField("Codex voice", daemon.form.codexVoice) { value ->
            callbacks.onForm { it.copy(codexVoice = value) }
        }
        FormField("Codex task model", daemon.form.codexTaskModel) { value ->
            callbacks.onForm { it.copy(codexTaskModel = value) }
        }
        FormField("Gemini voice", daemon.form.geminiVoice) { value ->
            callbacks.onForm { it.copy(geminiVoice = value) }
        }
        FormField("Gemini model", daemon.form.geminiModel) { value ->
            callbacks.onForm { it.copy(geminiModel = value) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = callbacks.onRefreshDaemon, enabled = !daemon.loading) {
                Text(if (daemon.loading) "Reading…" else "Reload")
            }
            PrimaryActionButton(
                label = if (daemon.saving) "Saving…" else "Save daemon settings",
                onClick = callbacks.onSaveDaemon,
                enabled = !daemon.saving && daemon.dirty,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AutoTuneCard(autoTune: AutoTuneUi, callbacks: SettingsCallbacks) {
    SectionCard(
        title = "Auto-tune",
        subtitle = "Measured changes only, each with its reason and a revert",
        status = {
            StatusPill(
                label = if (autoTune.enabled) "on" else "off",
                tone = if (autoTune.enabled) StatusTone.POSITIVE else StatusTone.NEUTRAL
            )
        }
    ) {
        ToggleRow(
            label = "Auto-tune",
            detail = "Off by default. Changes the codec, bitrate and Wi-Fi lock only when a measurement asks for it",
            checked = autoTune.enabled,
            onChange = callbacks.onAutoTune
        )
        if (autoTune.enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = callbacks.onEvaluateNow) { Text("Evaluate now") }
                TextButton(onClick = callbacks.onRevertEverything) { Text("Revert everything") }
                autoTune.lastEvaluatedAtMs?.let { at ->
                    Text(
                        text = "checked ${TIME.format(Date(at))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            autoTune.suggestions.forEach { suggestion ->
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (autoTune.changes.isEmpty()) {
                Text(
                    text = "No automatic change yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            autoTune.changes.forEach { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (entry.reverted) {
                            StatusPill("reverted", StatusTone.NEUTRAL)
                        } else {
                            TextButton(onClick = { callbacks.onRevertChange(entry.id) }) { Text("Revert") }
                        }
                    }
                    Text(
                        text = "${TIME.format(Date(entry.atMs))} · ${entry.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    singleLine: Boolean = true,
    numeric: Boolean = false,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth()
    )
}

private val TIME = SimpleDateFormat("HH:mm", Locale.US)

/** A loaded screen with a pending change and one logged decision, for previews and screenshots. */
internal fun previewSettingsState(): SettingsUiState = SettingsUiState(
    daemon = DaemonSettingsUi(
        values = DaemonSettingsValues(irohRelays = listOf("https://relay.example")),
        form = DaemonForm(
            codexVoice = "sol",
            ringTimeout = "45",
            maxDuration = "600",
            defaultResultFields = "heard, price",
            languageHint = "ru-RU",
            irohRelays = "https://relay.example"
        ),
        restartRequiredFields = listOf("language_hint")
    ),
    phone = PhoneSettings(
        preferredCodec = PhoneCodec.OPUS,
        maxBitrate = 32_000,
        wifiLowLatency = true,
        relayMode = "daemon",
        daemonRelayUrls = listOf("https://relay.example")
    ),
    connection = ConnectionUi(
        candidates = listOf(
            com.phonxiety.bridge.net.StoredCandidate("wss://192.168.1.10:18763/device", "a".repeat(64)),
            com.phonxiety.bridge.net.StoredCandidate("wss://100.64.0.9:18763/device", "a".repeat(64))
        )
    ),
    autoTune = AutoTuneUi(
        enabled = true,
        changes = listOf(
            AutoTuneLogEntry(
                id = "1",
                atMs = 1_764_146_000_000,
                key = TuneKey.MAX_BITRATE.key,
                from = "32000",
                to = "16000",
                reason = "link test p95 512 ms, loss 4%: dropped to Opus 16 kbit/s"
            )
        ),
        suggestions = listOf("Relay-only for 40 s: point the app at your own relay, or run a link test"),
        lastEvaluatedAtMs = 1_764_146_120_000
    )
)

@Preview
@Composable
private fun SettingsScreenPreview() {
    PhonxietyTheme(dynamicColor = false) {
        SettingsScreen(state = previewSettingsState())
    }
}
