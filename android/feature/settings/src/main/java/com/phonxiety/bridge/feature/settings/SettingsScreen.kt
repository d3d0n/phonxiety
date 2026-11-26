package com.phonxiety.bridge.feature.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.PhoneCodec
import com.phonxiety.bridge.PhoneSettings
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.ScreenScaffold
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.designsystem.component.ToggleRow
import com.phonxiety.bridge.net.ConnectionMode
import com.phonxiety.bridge.net.RelayModeSetting
import com.phonxiety.bridge.net.RelayOptions
import java.text.NumberFormat
import java.util.Date
import kotlin.math.roundToInt

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
    val resources = LocalResources.current
    val message = state.message?.asString(resources)
    val undoLabel = stringResource(R.string.settings_autotune_undo)
    LaunchedEffect(state.message) {
        if (message == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = message,
            actionLabel = if (state.undoAvailable) undoLabel else null,
            withDismissAction = false,
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoRevertEverything()
        viewModel.consumeMessage()
    }
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
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = modifier
    )
}

/** The screen itself: a pure function of [state], so a screenshot can pin it. */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    modifier: Modifier = Modifier,
    callbacks: SettingsCallbacks = SettingsCallbacks(),
    snackbarHost: @Composable () -> Unit = {}
) {
    val daemon = state.daemon
    ScreenScaffold(
        title = stringResource(R.string.settings_title),
        modifier = modifier,
        onBack = callbacks.onBack,
        snackbarHost = snackbarHost
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ConnectionCard(state.phone, state.connection, callbacks)
                AudioCard(state.phone, callbacks)
                DeviceCard(state.phone, callbacks)
                DaemonCard(daemon, callbacks)
                AutoTuneCard(state.autoTune, callbacks)
            }
            // The daemon card is three screens tall, so a pending save stays reachable from
            // anywhere in it.
            if (daemon.dirty || daemon.saving) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    PrimaryActionButton(
                        label = stringResource(
                            if (daemon.saving) R.string.settings_action_saving else R.string.settings_action_save_daemon
                        ),
                        onClick = callbacks.onSaveDaemon,
                        enabled = daemon.dirty && daemon.valid,
                        busy = daemon.saving,
                        supportingText = if (daemon.valid) {
                            null
                        } else {
                            stringResource(R.string.settings_message_unsaved_invalid)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
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
private fun ConnectionCard(phone: PhoneSettings, connection: ConnectionUi, callbacks: SettingsCallbacks) {
    val mode = ConnectionMode.of(phone.connectionMode)
    val relayMode = RelayModeSetting.of(phone.relayMode)
    StatusSectionCard(
        title = stringResource(R.string.settings_card_connection_title),
        subtitle = stringResource(R.string.settings_card_connection_subtitle),
        status = {
            StatusPill(
                label = stringResource(mode.labelRes()),
                tone = if (mode == ConnectionMode.AUTO) StatusTone.NEUTRAL else StatusTone.POSITIVE
            )
        }
    ) {
        GroupLabel(stringResource(R.string.settings_connection_transport))
        ChipRow {
            ConnectionMode.entries.forEach { entry ->
                ChoiceChip(
                    label = stringResource(entry.labelRes()),
                    selected = mode == entry,
                    onClick = { callbacks.onConnectionMode(entry.wire) }
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_connection_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        GroupLabel(stringResource(R.string.settings_connection_relays))
        ChipRow {
            RelayModeSetting.entries.forEach { entry ->
                ChoiceChip(
                    label = stringResource(entry.labelRes()),
                    selected = relayMode == entry,
                    onClick = { callbacks.onRelayMode(entry.wire) }
                )
            }
        }
        when (relayMode) {
            RelayModeSetting.CUSTOM -> FormField(
                label = stringResource(R.string.settings_connection_relays_custom),
                value = RelayOptions.join(phone.relayUrls),
                singleLine = false
            ) { callbacks.onRelayUrls(it) }

            RelayModeSetting.DAEMON -> {
                val daemonRelays = phone.daemonRelayUrls
                Text(
                    text = if (daemonRelays.isEmpty()) {
                        stringResource(R.string.settings_connection_relays_none)
                    } else {
                        stringResource(R.string.settings_connection_relays_from_daemon, daemonRelays.joinToString())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> Unit
        }

        GroupLabel(stringResource(R.string.settings_connection_addresses))
        connection.error?.let {
            ErrorCard(
                title = stringResource(R.string.settings_connection_addresses_error_title),
                message = stringResource(R.string.settings_connection_addresses_error_message),
                output = it.asString()
            )
        }
        if (connection.candidates.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_connection_addresses_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = pluralStringResource(
                    R.plurals.settings_connection_addresses_count,
                    connection.candidates.size,
                    connection.candidates.size
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(
                    R.string.settings_connection_addresses_split,
                    connection.lanCount,
                    connection.tailnetCount
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        InCardTextButton(
            label = stringResource(
                if (connection.refreshing) R.string.settings_connection_reading else R.string.settings_connection_reload
            ),
            onClick = callbacks.onRefreshCandidates,
            enabled = !connection.refreshing
        )
    }
}

@Composable
private fun AudioCard(phone: PhoneSettings, callbacks: SettingsCallbacks) {
    SectionCard(
        title = stringResource(R.string.settings_card_audio_title),
        subtitle = stringResource(R.string.settings_card_audio_subtitle)
    ) {
        GroupLabel(stringResource(R.string.settings_audio_codec))
        ChipRow {
            listOf(
                PhoneCodec.AUTO to R.string.settings_codec_auto,
                PhoneCodec.OPUS to R.string.settings_codec_opus,
                PhoneCodec.PCM to R.string.settings_codec_pcm
            ).forEach { (value, label) ->
                ChoiceChip(
                    label = stringResource(label),
                    selected = phone.preferredCodec == value,
                    group = stringResource(R.string.settings_audio_codec),
                    onClick = { callbacks.onCodec(value) }
                )
            }
        }
        BitrateControl(phone, callbacks)
        ToggleRow(
            title = stringResource(R.string.settings_toggle_wifi_title),
            detail = stringResource(R.string.settings_toggle_wifi_detail),
            checked = phone.wifiLowLatency,
            onCheckedChange = callbacks.onWifiLowLatency
        )
        ToggleRow(
            title = stringResource(R.string.settings_toggle_thermal_title),
            detail = stringResource(R.string.settings_toggle_thermal_detail),
            checked = phone.thermalAwareness,
            onCheckedChange = callbacks.onThermalAwareness
        )
        ToggleRow(
            title = stringResource(R.string.settings_toggle_battery_title),
            detail = stringResource(R.string.settings_toggle_battery_detail, AutoTune.LOW_BATTERY_PERCENT),
            checked = phone.batteryAwareness,
            onCheckedChange = callbacks.onBatteryAwareness
        )
    }
}

@Composable
private fun BitrateControl(phone: PhoneSettings, callbacks: SettingsCallbacks) {
    var slider by remember(phone.maxBitrate) { mutableFloatStateOf(phone.maxBitrate.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.settings_audio_bitrate_value, formatKbit(phone.maxBitrate)),
            style = MaterialTheme.typography.bodyMedium
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(
                symbol = "−",
                label = stringResource(R.string.settings_audio_lower_bitrate),
                enabled = phone.maxBitrate > PhoneSettings.MIN_BITRATE
            ) {
                callbacks.onMaxBitrate((phone.maxBitrate - BITRATE_STEP).coerceAtLeast(PhoneSettings.MIN_BITRATE))
            }
            val caption = stringResource(R.string.settings_audio_bitrate_caption)
            val valueText = formatKbit(slider.roundToInt())
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = {
                    callbacks.onMaxBitrate(
                        slider.roundToInt().coerceIn(PhoneSettings.MIN_BITRATE, PhoneSettings.MAX_BITRATE)
                    )
                },
                valueRange = PhoneSettings.MIN_BITRATE.toFloat()..PhoneSettings.MAX_BITRATE.toFloat(),
                steps = BITRATE_STEPS,
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = caption
                        stateDescription = valueText
                    }
            )
            StepButton(
                symbol = "+",
                label = stringResource(R.string.settings_audio_raise_bitrate),
                enabled = phone.maxBitrate < PhoneSettings.MAX_BITRATE
            ) {
                callbacks.onMaxBitrate((phone.maxBitrate + BITRATE_STEP).coerceAtMost(PhoneSettings.MAX_BITRATE))
            }
        }
        Text(
            text = stringResource(
                R.string.settings_audio_bitrate_range,
                formatKbit(PhoneSettings.MIN_BITRATE),
                formatKbit(PhoneSettings.MAX_BITRATE)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StepButton(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = label },
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(symbol)
    }
}

@Composable
private fun DeviceCard(phone: PhoneSettings, callbacks: SettingsCallbacks) {
    SectionCard(
        title = stringResource(R.string.settings_card_device_title),
        subtitle = stringResource(R.string.settings_card_device_subtitle)
    ) {
        GroupLabel(stringResource(R.string.settings_device_log_level))
        ChipRow {
            PhoneSettings.LOG_LEVELS.forEach { level ->
                ChoiceChip(
                    label = stringResource(logLevelLabel(level)),
                    selected = phone.logLevel == level,
                    onClick = { callbacks.onLogLevel(level) }
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_device_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DaemonCard(daemon: DaemonSettingsUi, callbacks: SettingsCallbacks) {
    StatusSectionCard(
        title = stringResource(R.string.settings_card_daemon_title),
        subtitle = stringResource(R.string.settings_card_daemon_subtitle),
        status = {
            when {
                daemon.saving -> StatusPill(
                    label = stringResource(R.string.settings_daemon_status_saving),
                    tone = StatusTone.WARNING,
                    pulse = true
                )

                daemon.dirty -> StatusPill(
                    label = stringResource(R.string.settings_daemon_status_edited),
                    tone = StatusTone.WARNING
                )

                else -> StatusPill(
                    label = stringResource(R.string.settings_daemon_status_loaded),
                    tone = StatusTone.NEUTRAL
                )
            }
        }
    ) {
        daemon.error?.let {
            // A refused save keeps the edits; reloading would replace them, so only a failed read
            // asks for a reload.
            ErrorCard(
                title = stringResource(
                    if (daemon.errorOnSave) R.string.settings_daemon_save_error_title else R.string.settings_daemon_error_title
                ),
                message = stringResource(
                    if (daemon.errorOnSave) R.string.settings_daemon_save_error_message else R.string.settings_daemon_error_message
                ),
                output = it.asString()
            )
        }
        if (daemon.changesApplied.isNotEmpty()) {
            Text(
                text = stringResource(R.string.settings_daemon_applied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            MonospaceLines(daemon.changesApplied)
        }
        if (daemon.restartRequiredFields.isNotEmpty()) {
            Text(
                text = pluralStringResource(
                    R.plurals.settings_daemon_restart_needed,
                    daemon.restartRequiredFields.size,
                    daemon.restartRequiredFields.size
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            MonospaceLines(daemon.restartRequiredFields)
        }

        GroupLabel(stringResource(R.string.settings_daemon_group_connection))
        FormField(
            label = stringResource(R.string.settings_daemon_relays),
            value = daemon.form.irohRelays,
            singleLine = false
        ) { value -> callbacks.onForm { it.copy(irohRelays = value) } }

        GroupLabel(stringResource(R.string.settings_daemon_group_audio))
        Text(
            text = stringResource(R.string.settings_daemon_group_audio_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ChipRow {
            listOf(
                "opus" to R.string.settings_codec_opus,
                "pcm" to R.string.settings_codec_pcm
            ).forEach { (value, label) ->
                ChoiceChip(
                    label = stringResource(label),
                    selected = daemon.form.audioCodec == value,
                    group = stringResource(R.string.settings_daemon_group_audio),
                    onClick = { callbacks.onForm { it.copy(audioCodec = value) } }
                )
            }
        }
        FormField(
            label = stringResource(R.string.settings_daemon_opus_bitrate),
            value = daemon.form.opusBitrate,
            numeric = true,
            hint = stringResource(R.string.settings_daemon_units_bits),
            isError = DaemonNumericField.OPUS_BITRATE in daemon.invalidFields
        ) { value -> callbacks.onForm { it.copy(opusBitrate = value) } }

        GroupLabel(stringResource(R.string.settings_daemon_group_calls))
        FormField(
            label = stringResource(R.string.settings_daemon_ring_timeout),
            value = daemon.form.ringTimeout,
            numeric = true,
            hint = stringResource(R.string.settings_daemon_units_seconds),
            isError = DaemonNumericField.RING_TIMEOUT in daemon.invalidFields
        ) { value -> callbacks.onForm { it.copy(ringTimeout = value) } }
        FormField(
            label = stringResource(R.string.settings_daemon_max_call_length),
            value = daemon.form.maxDuration,
            numeric = true,
            hint = stringResource(R.string.settings_daemon_units_seconds),
            isError = DaemonNumericField.MAX_DURATION in daemon.invalidFields
        ) { value -> callbacks.onForm { it.copy(maxDuration = value) } }
        FormField(
            label = stringResource(R.string.settings_daemon_result_fields),
            value = daemon.form.defaultResultFields,
            hint = stringResource(R.string.settings_daemon_result_fields_support)
        ) { value -> callbacks.onForm { it.copy(defaultResultFields = value) } }
        FormField(
            label = stringResource(R.string.settings_daemon_constraints),
            value = daemon.form.defaultConstraints,
            singleLine = false
        ) { value -> callbacks.onForm { it.copy(defaultConstraints = value) } }
        FormField(
            label = stringResource(R.string.settings_daemon_language_hint),
            value = daemon.form.languageHint,
            hint = stringResource(R.string.settings_daemon_language_hint_support)
        ) { value -> callbacks.onForm { it.copy(languageHint = value) } }

        GroupLabel(stringResource(R.string.settings_daemon_group_voice))
        ChipRow {
            listOf(
                "codex" to R.string.settings_daemon_backend_codex,
                "gemini" to R.string.settings_daemon_backend_gemini,
                "deepgram" to R.string.settings_daemon_backend_deepgram
            ).forEach { (value, label) ->
                ChoiceChip(
                    label = stringResource(label),
                    selected = daemon.form.voiceBackend == value,
                    onClick = { callbacks.onForm { it.copy(voiceBackend = value) } }
                )
            }
        }
        FormField(
            label = stringResource(R.string.settings_daemon_codex_voice),
            value = daemon.form.codexVoice
        ) { value -> callbacks.onForm { it.copy(codexVoice = value) } }
        FormField(
            label = stringResource(R.string.settings_daemon_codex_model),
            value = daemon.form.codexTaskModel
        ) { value -> callbacks.onForm { it.copy(codexTaskModel = value) } }
        FormField(
            label = stringResource(R.string.settings_daemon_gemini_voice),
            value = daemon.form.geminiVoice
        ) { value -> callbacks.onForm { it.copy(geminiVoice = value) } }
        FormField(
            label = stringResource(R.string.settings_daemon_gemini_model),
            value = daemon.form.geminiModel
        ) { value -> callbacks.onForm { it.copy(geminiModel = value) } }

        InCardTextButton(
            label = stringResource(
                if (daemon.loading) R.string.settings_connection_reading else R.string.settings_daemon_reload
            ),
            onClick = callbacks.onRefreshDaemon,
            enabled = !daemon.loading
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoTuneCard(autoTune: AutoTuneUi, callbacks: SettingsCallbacks) {
    StatusSectionCard(
        title = stringResource(R.string.settings_card_autotune_title),
        subtitle = stringResource(R.string.settings_card_autotune_subtitle),
        status = {
            StatusPill(
                label = stringResource(
                    if (autoTune.enabled) R.string.settings_autotune_on else R.string.settings_autotune_off
                ),
                tone = if (autoTune.enabled) StatusTone.POSITIVE else StatusTone.NEUTRAL
            )
        }
    ) {
        ToggleRow(
            title = stringResource(R.string.settings_autotune_toggle_title),
            detail = stringResource(R.string.settings_autotune_toggle_detail),
            checked = autoTune.enabled,
            onCheckedChange = callbacks.onAutoTune
        )
        if (autoTune.enabled) {
            val context = LocalContext.current
            val time = remember(context) { DateFormat.getTimeFormat(context) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                InCardTextButton(
                    label = stringResource(R.string.settings_autotune_evaluate),
                    onClick = callbacks.onEvaluateNow
                )
            }
            autoTune.lastEvaluatedAtMs?.let { at ->
                Text(
                    text = stringResource(R.string.settings_autotune_checked_at, time.format(Date(at))),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            autoTune.suggestions.forEach { suggestion ->
                Text(
                    text = suggestion.asString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (autoTune.changes.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_autotune_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            autoTune.changes.forEach { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = entry.title().asString(),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_autotune_change_line,
                            time.format(Date(entry.atMs)),
                            entry.reasonText().asString()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (entry.reverted) {
                        StatusPill(
                            label = stringResource(R.string.settings_autotune_reverted),
                            tone = StatusTone.NEUTRAL
                        )
                    } else {
                        InCardTextButton(
                            label = stringResource(R.string.settings_autotune_revert),
                            onClick = { callbacks.onRevertChange(entry.id) }
                        )
                    }
                }
            }
            if (autoTune.changes.isNotEmpty()) {
                InCardTextButton(
                    label = stringResource(R.string.settings_autotune_revert_all),
                    onClick = callbacks.onRevertEverything
                )
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

/**
 * A choice chip. [group] names the setting it belongs to, because "Opus" on the phone and "Opus"
 * on the daemon are different choices that must not read the same.
 */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, group: String? = null) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = if (group == null) {
            Modifier
        } else {
            Modifier.semantics { contentDescription = "$label, $group" }
        },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            }
        } else {
            null
        }
    )
}

/** An in-card action starts at the card's text edge instead of 12 dp to the right of it. */
@Composable
private fun InCardTextButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(start = 0.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Text(label)
    }
}

@Composable
private fun MonospaceLines(lines: List<String>) {
    Text(
        text = lines.joinToString("\n"),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun FormField(
    label: String,
    value: String,
    singleLine: Boolean = true,
    numeric: Boolean = false,
    hint: String? = null,
    isError: Boolean = false,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        isError = isError,
        supportingText = when {
            isError -> {
                { Text(stringResource(R.string.settings_error_whole_number)) }
            }

            hint != null -> {
                { Text(hint) }
            }

            else -> null
        },
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun ConnectionMode.labelRes(): Int = when (this) {
    ConnectionMode.AUTO -> R.string.settings_mode_auto
    ConnectionMode.IROH_ONLY -> R.string.settings_mode_iroh
    ConnectionMode.LAN_ONLY -> R.string.settings_mode_lan
    ConnectionMode.TAILSCALE_ONLY -> R.string.settings_mode_tailscale
}

private fun RelayModeSetting.labelRes(): Int = when (this) {
    RelayModeSetting.DEFAULT -> R.string.settings_relay_default
    RelayModeSetting.DAEMON -> R.string.settings_relay_daemon
    RelayModeSetting.CUSTOM -> R.string.settings_relay_custom
    RelayModeSetting.DISABLED -> R.string.settings_relay_disabled
}

private fun logLevelLabel(level: String): Int = when (level) {
    "debug" -> R.string.settings_loglevel_debug
    "info" -> R.string.settings_loglevel_info
    "warn" -> R.string.settings_loglevel_warn
    "error" -> R.string.settings_loglevel_error
    else -> R.string.settings_loglevel_info
}

/** "32 kbit/s" with a no-break space and the reader's digits. */
private fun formatKbit(bitsPerSecond: Int): String {
    val number = NumberFormat.getIntegerInstance().format(bitsPerSecond / 1000)
    return "$number\u00A0kbit/s"
}

private const val STACK_STATUS_AT_FONT_SCALE = 1.5f
private const val BITRATE_STEP = 4_000
private const val BITRATE_STEPS = 11

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
                reasonId = TuneReason.BITRATE_LOWER_P95_LOSS.name,
                reasonArgs = listOf("512\u00A0ms", "4%", "16\u00A0kbit/s")
            )
        ),
        suggestions = listOf(
            UiText.Res(
                R.string.settings_suggestion_relay_only,
                listOf("40\u00A0s")
            )
        ),
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
