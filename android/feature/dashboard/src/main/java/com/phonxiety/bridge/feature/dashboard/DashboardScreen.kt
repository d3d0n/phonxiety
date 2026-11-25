package com.phonxiety.bridge.feature.dashboard

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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.LevelMeter
import com.phonxiety.bridge.designsystem.component.LinkPath
import com.phonxiety.bridge.designsystem.component.LinkPathPill
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.StatTile
import com.phonxiety.bridge.designsystem.component.StatTone
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.model.AudioState
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import com.phonxiety.bridge.model.DeviceState
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import java.util.concurrent.TimeUnit

/** The dashboard entry point; the app host only supplies navigation. */
@Composable
fun DashboardRoute(
    onOpenSetup: () -> Unit,
    onOpenDoctor: () -> Unit,
    onNewCall: () -> Unit,
    onOpenLiveCall: (String) -> Unit,
    onRunLinkTest: () -> Unit,
    onOpenUpdates: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DashboardScreen(
        state = state,
        onOpenSetup = onOpenSetup,
        onOpenDoctor = onOpenDoctor,
        onNewCall = onNewCall,
        onOpenLiveCall = onOpenLiveCall,
        onRunLinkTest = onRunLinkTest,
        onOpenUpdates = onOpenUpdates,
        onUnpair = viewModel::unpair,
        modifier = modifier
    )
}

/**
 * Link, call, audio and device cards plus the permissions the bridge is missing. Every "connected"
 * claim on this screen is a measurement from [DashboardUiState.bridge]; nothing here probes.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onOpenSetup: () -> Unit,
    onOpenDoctor: () -> Unit,
    onNewCall: () -> Unit,
    onOpenLiveCall: (String) -> Unit,
    onRunLinkTest: () -> Unit,
    onOpenUpdates: () -> Unit,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
    nowEpochMs: Long = System.currentTimeMillis()
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
            DashboardHeader(state.bridge)
            state.bridge.lastError?.let { error ->
                ErrorCard(
                    title = "The link reported an error",
                    message = "${error.message} (${error.code})",
                    actionLabel = "Open Doctor",
                    onAction = onOpenDoctor
                )
            }
            if (!state.paired) {
                EmptyState(
                    title = "This phone is not paired",
                    message = "Pair it with the computer that runs the daemon, then the wizard finishes setup by itself."
                )
                PrimaryActionButton(label = "Set up this phone", onClick = onOpenSetup)
            } else {
                QuickActionsCard(
                    inCall = state.bridge.call != null,
                    onNewCall = onNewCall,
                    onRunLinkTest = onRunLinkTest,
                    onOpenDoctor = onOpenDoctor,
                    onOpenUpdates = onOpenUpdates
                )
                LinkCard(state.bridge.link, nowEpochMs)
                CallCard(state.bridge.call, nowEpochMs, onOpenLiveCall)
                AudioCard(state.bridge.audio)
                DeviceCard(state.bridge.device, state.simOperator)
                PermissionCard(state.bridge.device.missingPermissions, onOpenSetup)
                PairingCard(
                    inCall = state.bridge.call != null,
                    onOpenSetup = onOpenSetup,
                    onUnpair = onUnpair
                )
            }
        }
    }
}

@Composable
private fun DashboardHeader(bridge: BridgeState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "phonxiety", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Bridge control",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when (val link = bridge.link) {
            is LinkState.Up -> LinkPathPill(link.path.toDesignPath())
            is LinkState.Connecting -> StatusPill("Connecting", StatusTone.WARNING, pulse = true)
            LinkState.Offline -> StatusPill("Offline", StatusTone.NEUTRAL)
        }
    }
}

@Composable
private fun LinkCard(link: LinkState, nowEpochMs: Long) {
    SectionCard(
        title = "Link",
        subtitle = "How this phone reaches the daemon",
        status = {
            when (link) {
                is LinkState.Up -> LinkPathPill(link.path.toDesignPath())
                is LinkState.Connecting -> StatusPill("Connecting", StatusTone.WARNING, pulse = true)
                LinkState.Offline -> StatusPill("Offline", StatusTone.NEUTRAL)
            }
        }
    ) {
        when (link) {
            is LinkState.Up -> {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    StatTile(
                        label = "Transport",
                        value = link.kind.label(),
                        caption = link.path.label()
                    )
                    StatTile(
                        label = "RTT p50",
                        value = link.rttMs?.let { "$it ms" } ?: "—",
                        mono = true,
                        tone = rttTone(link.rttMs)
                    )
                    StatTile(
                        label = "Up for",
                        value = formatUptime(link.sinceEpochMs, nowEpochMs),
                        mono = true
                    )
                }
                KeyValueRow(label = "Reconnects", value = link.reconnects.toString(), mono = true)
                link.address?.let { address ->
                    KeyValueRow(label = "Endpoint", value = address, mono = true)
                }
            }

            is LinkState.Connecting -> {
                Text(
                    text = "Trying ${link.candidate.label()}…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LinkState.Offline -> {
                Text(
                    text = "No path to the daemon. The service keeps retrying; the last numbers are gone because they would be a guess.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The four things worth one tap: place a call, prove the link, repair it, update it. */
@Composable
private fun QuickActionsCard(
    inCall: Boolean,
    onNewCall: () -> Unit,
    onRunLinkTest: () -> Unit,
    onOpenDoctor: () -> Unit,
    onOpenUpdates: () -> Unit
) {
    SectionCard(title = "Actions") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onNewCall,
                modifier = Modifier.weight(1f),
                enabled = !inCall
            ) {
                Text("New call")
            }
            FilledTonalButton(onClick = onRunLinkTest, modifier = Modifier.weight(1f)) {
                Text("Link test")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onOpenDoctor, modifier = Modifier.weight(1f)) {
                Text("Doctor")
            }
            FilledTonalButton(onClick = onOpenUpdates, modifier = Modifier.weight(1f)) {
                Text("Updates")
            }
        }
    }
}

@Composable
private fun CallCard(call: CallState?, nowEpochMs: Long, onOpenLiveCall: (String) -> Unit) {
    SectionCard(
        title = "Call",
        status = {
            if (call == null) {
                StatusPill("Idle", StatusTone.NEUTRAL)
            } else {
                StatusPill(call.phase.label(), phaseTone(call.phase), pulse = call.phase == CallPhase.DIALING)
            }
        }
    ) {
        if (call == null) {
            Text(
                text = "No call is running. The agent's calls appear here while they are live.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                StatTile(label = "Destination", value = call.destinationMasked, mono = true)
                StatTile(
                    label = "Duration",
                    value = call.startedAtEpochMs?.let { formatUptime(it, nowEpochMs) } ?: "—",
                    mono = true
                )
                StatTile(label = "Phase", value = call.phase.label())
            }
            TextButton(onClick = { onOpenLiveCall(call.callId) }) { Text("Supervise this call") }
        }
    }
}

@Composable
private fun AudioCard(audio: AudioState) {
    SectionCard(
        title = "Audio",
        status = {
            val codec = audio.codec
            if (codec == null) {
                StatusPill("No stream", StatusTone.NEUTRAL)
            } else {
                StatusPill(codec, StatusTone.ACCENT)
            }
        }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            StatTile(
                label = "Bitrate",
                value = audio.bitrate?.let { "$it kbit/s" } ?: "—",
                mono = true
            )
            StatTile(label = "Frames out", value = audio.framesOut.toString(), mono = true)
            StatTile(
                label = "Drops",
                value = audio.drops.toString(),
                mono = true,
                tone = if (audio.drops > 0) StatTone.NEGATIVE else StatTone.NEUTRAL
            )
        }
        LevelMeter(
            label = "Microphone",
            levelDbfs = audio.txLevelDbfs,
            modifier = Modifier.fillMaxWidth()
        )
        LevelMeter(
            label = "Speaker",
            levelDbfs = audio.rxLevelDbfs,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DeviceCard(device: DeviceState, simOperator: String?) {
    SectionCard(title = "Phone") {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            StatTile(
                label = "Battery",
                value = device.batteryPercent?.let { "$it %" } ?: "—",
                mono = true,
                caption = when (device.charging) {
                    true -> "charging"
                    false -> "on battery"
                    null -> null
                }
            )
            StatTile(
                label = "Thermal",
                value = device.thermal ?: "—",
                tone = if (device.thermal == null || device.thermal == "NONE") {
                    StatTone.NEUTRAL
                } else {
                    StatTone.WARNING
                }
            )
            StatTile(
                label = "Doze",
                value = when (device.dozeExempt) {
                    true -> "Exempt"
                    false -> "Limited"
                    null -> "—"
                },
                mono = device.dozeExempt != null,
                tone = if (device.dozeExempt == true) StatTone.POSITIVE else StatTone.NEUTRAL
            )
        }
        if (device.network != null || device.wifiRssi != null) {
            KeyValueRow(
                label = "Network",
                value = listOfNotNull(
                    device.network,
                    device.wifiRssi?.let { "$it dBm" }
                ).joinToString(" · ")
            )
        }
        if (simOperator != null) {
            KeyValueRow(label = "SIM", value = simOperator)
        }
    }
}

@Composable
private fun PermissionCard(missing: List<String>, onOpenSetup: () -> Unit) {
    SectionCard(
        title = "Permissions",
        status = {
            if (missing.isEmpty()) {
                StatusPill("All granted", StatusTone.POSITIVE)
            } else {
                StatusPill("${missing.size} missing", StatusTone.NEGATIVE)
            }
        }
    ) {
        if (missing.isEmpty()) {
            Text(
                text = "Microphone, calls, notifications and the local network are granted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            missing.forEach { permission ->
                KeyValueRow(label = permission.label(), value = "Missing")
            }
            TextButton(onClick = onOpenSetup) { Text("Fix in setup") }
        }
    }
}

@Composable
private fun PairingCard(
    inCall: Boolean,
    onOpenSetup: () -> Unit,
    onUnpair: () -> Unit
) {
    var confirming by remember { mutableStateOf(false) }
    SectionCard(title = "Pairing") {
        Text(
            text = "This phone is paired with a daemon. The bearer token stays in device-protected storage and is never rendered.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onOpenSetup) { Text("Pair another computer") }
            TextButton(
                onClick = { confirming = true },
                enabled = !inCall
            ) {
                Text("Unpair")
            }
        }
        if (inCall) {
            Text(
                text = "Unpairing is blocked while a call is running.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Unpair this phone?") },
            text = { Text("The service stops and the stored pairing is deleted. You can pair again with the QR code on your computer.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onUnpair()
                    }
                ) {
                    Text("Unpair")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            }
        )
    }
}

internal fun PathKind.toDesignPath(): LinkPath = when (this) {
    PathKind.DIRECT -> LinkPath.DIRECT
    PathKind.RELAY -> LinkPath.RELAY
    PathKind.UNKNOWN -> LinkPath.RELAY
}

internal fun PathKind.label(): String = when (this) {
    PathKind.DIRECT -> "direct"
    PathKind.RELAY -> "relay"
    PathKind.UNKNOWN -> "unknown path"
}

internal fun LinkKind.label(): String = when (this) {
    LinkKind.IROH -> "iroh"
    LinkKind.WEBSOCKET -> "WebSocket"
    LinkKind.TAILSCALE -> "Tailscale"
}

internal fun CallPhase.label(): String = when (this) {
    CallPhase.DIALING -> "Dialing"
    CallPhase.ACTIVE -> "Active"
    CallPhase.ENDING -> "Ending"
    CallPhase.ENDED -> "Ended"
}

internal fun phaseTone(phase: CallPhase): StatusTone = when (phase) {
    CallPhase.DIALING -> StatusTone.WARNING
    CallPhase.ACTIVE -> StatusTone.POSITIVE
    CallPhase.ENDING -> StatusTone.WARNING
    CallPhase.ENDED -> StatusTone.NEUTRAL
}

internal fun rttTone(rttMs: Long?): StatTone = when {
    rttMs == null -> StatTone.NEUTRAL
    rttMs <= 150 -> StatTone.POSITIVE
    rttMs <= 400 -> StatTone.WARNING
    else -> StatTone.NEGATIVE
}

/** `12 m`, `2 h 05 m`; uptimes are read at a glance, not to the second. */
internal fun formatUptime(sinceEpochMs: Long, nowEpochMs: Long): String {
    val elapsed = (nowEpochMs - sinceEpochMs).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    return when {
        minutes < 1 -> "<1 m"

        minutes < 60 -> "$minutes m"

        else -> {
            val hours = minutes / 60
            val rest = minutes % 60
            if (rest == 0L) "$hours h" else "$hours h ${rest.toString().padStart(2, '0')} m"
        }
    }
}

internal fun String.label(): String = when (this) {
    "android.permission.RECORD_AUDIO" -> "Microphone"
    "android.permission.CALL_PHONE" -> "Phone calls"
    "android.permission.READ_PHONE_STATE" -> "Phone state"
    "android.permission.MODIFY_AUDIO_SETTINGS" -> "Audio settings"
    "android.permission.POST_NOTIFICATIONS" -> "Notifications"
    "android.permission.ACCESS_LOCAL_NETWORK" -> "Local network"
    else -> substringAfterLast('.')
}

@Preview(name = "Direct link", showBackground = true)
@Composable
private fun DirectPreview() {
    PhonxietyTheme(dynamicColor = false) {
        DashboardScreen(
            state = DashboardUiState(bridge = DashboardSamples.direct, paired = true),
            onOpenSetup = {},
            onOpenDoctor = {},
            onNewCall = {},
            onOpenLiveCall = {},
            onRunLinkTest = {},
            onOpenUpdates = {},
            onUnpair = {},
            nowEpochMs = DashboardSamples.now
        )
    }
}

@Preview(name = "Relay in call", showBackground = true)
@Composable
private fun RelayPreview() {
    PhonxietyTheme(dynamicColor = false) {
        DashboardScreen(
            state = DashboardUiState(bridge = DashboardSamples.relayInCall, paired = true),
            onOpenSetup = {},
            onOpenDoctor = {},
            onNewCall = {},
            onOpenLiveCall = {},
            onRunLinkTest = {},
            onOpenUpdates = {},
            onUnpair = {},
            nowEpochMs = DashboardSamples.now
        )
    }
}

@Preview(name = "Offline", showBackground = true)
@Composable
private fun OfflinePreview() {
    PhonxietyTheme(dynamicColor = false) {
        DashboardScreen(
            state = DashboardUiState(bridge = DashboardSamples.offline, paired = true),
            onOpenSetup = {},
            onOpenDoctor = {},
            onNewCall = {},
            onOpenLiveCall = {},
            onRunLinkTest = {},
            onOpenUpdates = {},
            onUnpair = {},
            nowEpochMs = DashboardSamples.now
        )
    }
}

object DashboardSamples {
    val now = 1_764_000_000_000L

    val direct = BridgeState(
        link = LinkState.Up(
            kind = LinkKind.IROH,
            path = PathKind.DIRECT,
            address = "6c3a1f9b",
            rttMs = 41,
            sinceEpochMs = now - TimeUnit.MINUTES.toMillis(12),
            reconnects = 0
        ),
        audio = AudioState(
            codec = "opus",
            bitrate = 32,
            rxLevelDbfs = -21f,
            txLevelDbfs = -18f,
            framesOut = 4_120,
            framesIn = 4_118,
            drops = 0
        ),
        device = DeviceState(
            batteryPercent = 78,
            charging = false,
            thermal = "LIGHT",
            network = "wifi",
            wifiRssi = -52,
            dozeExempt = true
        )
    )

    val relayInCall = direct.copy(
        link = (direct.link as LinkState.Up).copy(
            path = PathKind.RELAY,
            rttMs = 212,
            reconnects = 2
        ),
        call = CallState(
            callId = "preview",
            destinationMasked = "+998 ·· 42",
            phase = CallPhase.ACTIVE,
            startedAtEpochMs = now - TimeUnit.SECONDS.toMillis(74)
        ),
        audio = direct.audio.copy(rxLevelDbfs = -8f, txLevelDbfs = -34f)
    )

    val offline = BridgeState(
        link = LinkState.Offline,
        device = DeviceState(
            batteryPercent = 41,
            charging = true,
            thermal = "NONE",
            network = "cellular",
            dozeExempt = false,
            missingPermissions = listOf("android.permission.ACCESS_LOCAL_NETWORK")
        )
    )
}
