package com.phonxiety.bridge.feature.dashboard

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.data.bitrateKbps
import com.phonxiety.bridge.designsystem.BrandLockup
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
import com.phonxiety.bridge.designsystem.formatDuration
import com.phonxiety.bridge.designsystem.formatElapsedTime
import com.phonxiety.bridge.designsystem.formatPhoneNumber
import com.phonxiety.bridge.model.AudioState
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import com.phonxiety.bridge.model.DeviceState
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/** Unit symbols are not translated; the designsystem joins them to the value with a no-break space. */
private const val UNIT_MILLISECONDS = "ms"
private const val UNIT_KILOBITS = "kbit/s"

/** A missing measurement is a dash, never a fabricated zero. */
internal const val MISSING_VALUE = "—"

/** The playback ring is 48 kHz mono, so one millisecond of downlink audio is 48 samples. */
internal const val AUDIO_SAMPLES_PER_MILLISECOND = 48L

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
 *
 * The call card comes first because it answers "what is happening now"; the actions follow the
 * answer. [nowEpochMs] fixes the clock for previews and screenshots; the screen ticks by itself.
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
    nowEpochMs: Long? = null
) {
    val ticked by tickingNow()
    val now = nowEpochMs ?: ticked
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Box(
            modifier = Modifier
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
                DashboardHeader(state.bridge, state.paired)
                state.bridge.lastError?.let { error ->
                    ErrorCard(
                        title = stringResource(R.string.dashboard_link_error_title),
                        message = error.message,
                        output = error.code,
                        actionLabel = stringResource(R.string.dashboard_open_doctor),
                        onAction = onOpenDoctor
                    )
                }
                if (!state.paired) {
                    EmptyState(
                        title = stringResource(R.string.dashboard_unpaired_title),
                        message = stringResource(R.string.dashboard_unpaired_message)
                    )
                    PrimaryActionButton(
                        label = stringResource(R.string.dashboard_set_up_phone),
                        onClick = onOpenSetup
                    )
                } else {
                    CallCard(state.bridge.call, now, onOpenLiveCall)
                    QuickActionsCard(
                        call = state.bridge.call,
                        showLinkTest = state.bridge.link is LinkState.Up,
                        onNewCall = onNewCall,
                        onRunLinkTest = onRunLinkTest,
                        onOpenDoctor = onOpenDoctor,
                        onOpenUpdates = onOpenUpdates
                    )
                    LinkCard(
                        link = state.bridge.link,
                        nowEpochMs = now,
                        onRunLinkTest = onRunLinkTest,
                        // The header wears the phase while a call runs, so this card carries the
                        // measured path instead; the two never show the same pill twice.
                        showPath = state.bridge.call.isLive()
                    )
                    AudioCard(state.bridge.audio)
                    DeviceCard(state.bridge.device, state.simOperator)
                    PermissionCard(state.bridge.device.missingPermissions, onOpenSetup)
                    PairingCard(
                        inCall = state.bridge.call.isLive(),
                        onOpenSetup = onOpenSetup,
                        onUnpair = onUnpair
                    )
                }
            }
        }
    }
}

/** Tiles reflow instead of squeezing: three across at 100 %, as many rows as the font needs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        content()
    }
}

/** Action buttons wrap at their own width so no label is ever split or clipped. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionFlow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

/** The title and the one pill that answers "what is happening now". */
@Composable
private fun DashboardHeader(bridge: BridgeState, paired: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BrandLockup(height = 22.dp)
            Text(
                text = stringResource(R.string.dashboard_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HeaderStatus(bridge, paired)
    }
}

@Composable
private fun HeaderStatus(bridge: BridgeState, paired: Boolean) {
    val call = bridge.call.takeIfLive()
    when {
        call != null -> StatusPill(
            label = phaseLabel(call.phase),
            tone = phaseTone(call.phase),
            pulse = call.phase == CallPhase.DIALING
        )

        !paired -> StatusPill(
            label = stringResource(R.string.dashboard_status_not_paired),
            tone = StatusTone.NEUTRAL
        )

        else -> when (val link = bridge.link) {
            is LinkState.Up -> LinkPathPill(link.path.toDesignPath())

            is LinkState.Connecting -> StatusPill(
                label = stringResource(R.string.dashboard_status_connecting),
                tone = StatusTone.WARNING,
                pulse = true
            )

            LinkState.Offline -> StatusPill(
                label = stringResource(R.string.dashboard_status_offline),
                tone = StatusTone.NEUTRAL
            )
        }
    }
}

/**
 * The call in progress or the last one, then one primary action: open it. An ended call keeps its
 * numbers for review but carries no filled button.
 */
@Composable
private fun CallCard(call: CallState?, nowEpochMs: Long, onOpenLiveCall: (String) -> Unit) {
    val context = LocalContext.current
    // While a call is live the header pill already names its phase; the card does not repeat it.
    val status: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = when {
        call == null -> {
            {
                StatusPill(
                    label = stringResource(R.string.dashboard_call_idle),
                    tone = StatusTone.NEUTRAL
                )
            }
        }

        !call.isLive() -> {
            {
                StatusPill(
                    label = phaseLabel(call.phase),
                    tone = phaseTone(call.phase)
                )
            }
        }

        else -> null
    }
    SectionCard(
        title = stringResource(R.string.dashboard_call_title),
        status = status
    ) {
        if (call == null) {
            Text(
                text = stringResource(R.string.dashboard_call_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val timing = callTiming(call)
            TileRow {
                StatTile(
                    label = stringResource(R.string.dashboard_destination),
                    value = formatPhoneNumber(call.destinationMasked),
                    mono = true
                )
                StatTile(
                    label = stringResource(timing.labelRes),
                    value = timing.elapsedStartEpochMs
                        ?.let { formatElapsedTime(secondsBetween(it, timing.elapsedEndEpochMs ?: nowEpochMs)) }
                        ?: MISSING_VALUE,
                    mono = true
                )
                StatTile(
                    label = stringResource(R.string.dashboard_connected_at),
                    value = timing.connectedAtEpochMs?.let { timeOf(context, it) } ?: MISSING_VALUE,
                    mono = true
                )
            }
            if (call.isLive()) {
                PrimaryActionButton(
                    label = stringResource(R.string.dashboard_open_live_call),
                    onClick = { onOpenLiveCall(call.callId) }
                )
            }
        }
    }
}

/**
 * The actions that are not the call: prove the link, repair it, update it. A call in progress
 * explains why a second one is not offered instead of disabling a button without a reason.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickActionsCard(
    call: CallState?,
    showLinkTest: Boolean,
    onNewCall: () -> Unit,
    onRunLinkTest: () -> Unit,
    onOpenDoctor: () -> Unit,
    onOpenUpdates: () -> Unit
) {
    SectionCard(title = stringResource(R.string.dashboard_actions_title)) {
        if (call.isLive()) {
            Text(
                text = stringResource(R.string.dashboard_actions_call_running),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ActionFlow {
                if (showLinkTest) {
                    OutlinedButton(onClick = onRunLinkTest) {
                        Text(stringResource(R.string.dashboard_link_test))
                    }
                }
                OutlinedButton(onClick = onOpenDoctor) {
                    Text(stringResource(R.string.dashboard_doctor))
                }
                OutlinedButton(onClick = onOpenUpdates) {
                    Text(stringResource(R.string.dashboard_updates))
                }
            }
        } else {
            ActionFlow {
                Button(onClick = onNewCall) {
                    Text(stringResource(R.string.dashboard_new_call))
                }
                if (showLinkTest) {
                    OutlinedButton(onClick = onRunLinkTest) {
                        Text(stringResource(R.string.dashboard_link_test))
                    }
                }
                OutlinedButton(onClick = onOpenDoctor) {
                    Text(stringResource(R.string.dashboard_doctor))
                }
                OutlinedButton(onClick = onOpenUpdates) {
                    Text(stringResource(R.string.dashboard_updates))
                }
            }
        }
    }
}

/** An ended call stays on the card for review; only a call still in progress blocks the actions. */
private fun CallState?.isLive(): Boolean = this != null && phase != CallPhase.ENDED

private fun CallState?.takeIfLive(): CallState? = takeIf { it.isLive() }

@Composable
private fun LinkCard(
    link: LinkState,
    nowEpochMs: Long,
    onRunLinkTest: () -> Unit,
    showPath: Boolean
) {
    val pathPill: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? =
        if (showPath && link is LinkState.Up) {
            { LinkPathPill(link.path.toDesignPath()) }
        } else {
            null
        }
    SectionCard(
        title = stringResource(R.string.dashboard_link_title),
        subtitle = stringResource(R.string.dashboard_link_subtitle),
        status = pathPill
    ) {
        when (link) {
            is LinkState.Up -> {
                TileRow {
                    StatTile(
                        label = stringResource(R.string.dashboard_transport),
                        value = linkKindLabel(link.kind)
                    )
                    StatTile(
                        label = stringResource(R.string.dashboard_latency_median),
                        value = link.rttMs?.toString() ?: MISSING_VALUE,
                        mono = true,
                        tone = rttTone(link.rttMs),
                        unit = if (link.rttMs != null) UNIT_MILLISECONDS else null
                    )
                    StatTile(
                        label = stringResource(R.string.dashboard_up_for),
                        value = formatDuration(secondsBetween(link.sinceEpochMs, nowEpochMs)),
                        mono = true
                    )
                }
                KeyValueRow(
                    label = stringResource(R.string.dashboard_reconnects),
                    value = link.reconnects.toString(),
                    mono = true
                )
                link.address?.let { address ->
                    KeyValueRow(
                        label = stringResource(R.string.dashboard_endpoint),
                        value = shortEndpoint(address),
                        mono = true,
                        valueMaxLines = 2
                    )
                    EndpointDisclosure(address)
                }
            }

            is LinkState.Connecting -> Column {
                Text(
                    text = stringResource(
                        R.string.dashboard_connecting_trying,
                        linkKindLabel(link.candidate)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onRunLinkTest) {
                    Text(stringResource(R.string.dashboard_link_test))
                }
            }

            LinkState.Offline -> Column {
                Text(
                    text = stringResource(R.string.dashboard_link_offline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onRunLinkTest) {
                    Text(stringResource(R.string.dashboard_link_test))
                }
            }
        }
    }
}

/**
 * The whole endpoint id on request. The card keeps the short form so the row stays a row; a reader
 * who needs the key — to pair, or to compare with the computer — opens it here, selectable and
 * wrapped, in monospace.
 */
@Composable
private fun EndpointDisclosure(address: String) {
    var shown by rememberSaveable(address) { mutableStateOf(false) }
    TextButton(onClick = { shown = !shown }) {
        Text(stringResource(if (shown) R.string.dashboard_endpoint_hide else R.string.dashboard_endpoint_show))
    }
    if (shown) {
        SelectionContainer {
            Text(
                text = address,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

/** With no stream there are no measurements: the card says when one starts instead of showing zeros. */
@Composable
private fun AudioCard(audio: AudioState) {
    val codec = audio.codec
    SectionCard(
        title = stringResource(R.string.dashboard_audio_title),
        status = {
            if (codec == null) {
                StatusPill(
                    label = stringResource(R.string.dashboard_no_stream),
                    tone = StatusTone.NEUTRAL
                )
            } else {
                StatusPill(label = humanizeToken(codec), tone = StatusTone.ACCENT)
            }
        }
    ) {
        if (codec == null) {
            Text(
                text = stringResource(R.string.dashboard_no_audio_stream),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        TileRow {
            StatTile(
                label = stringResource(R.string.dashboard_bitrate),
                value = bitrateKbps(audio.bitrate)?.toString() ?: MISSING_VALUE,
                mono = true,
                unit = if (audio.bitrate != null) UNIT_KILOBITS else null
            )
            StatTile(
                label = stringResource(R.string.dashboard_frames_out),
                value = audio.framesOut.toString(),
                mono = true
            )
            StatTile(
                label = stringResource(R.string.dashboard_playback_drops),
                value = playbackDropMillis(audio.drops).toString(),
                mono = true,
                tone = if (audio.drops > 0) StatTone.NEGATIVE else StatTone.NEUTRAL,
                unit = UNIT_MILLISECONDS
            )
        }
        Text(
            text = stringResource(R.string.dashboard_playback_drops_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LevelMeter(
            label = stringResource(R.string.dashboard_microphone),
            levelDbfs = audio.txLevelDbfs,
            modifier = Modifier.fillMaxWidth()
        )
        LevelMeter(
            label = stringResource(R.string.dashboard_far_end),
            levelDbfs = audio.rxLevelDbfs,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DeviceCard(device: DeviceState, simOperator: String?) {
    SectionCard(title = stringResource(R.string.dashboard_phone_title)) {
        val thermal = device.thermal
        TileRow {
            StatTile(
                label = stringResource(R.string.dashboard_battery),
                value = device.batteryPercent?.toString() ?: MISSING_VALUE,
                mono = true,
                caption = when (device.charging) {
                    true -> stringResource(R.string.dashboard_charging)
                    false -> stringResource(R.string.dashboard_on_battery)
                    null -> null
                },
                unit = if (device.batteryPercent != null) "%" else null
            )
            StatTile(
                label = stringResource(R.string.dashboard_thermal),
                value = thermal?.let { thermalLabel(it) } ?: MISSING_VALUE,
                tone = when (thermalState(thermal)) {
                    ThermalState.NONE, ThermalState.UNKNOWN -> StatTone.NEUTRAL
                    ThermalState.WARM, ThermalState.HOT -> StatTone.WARNING
                }
            )
            StatTile(
                label = stringResource(R.string.dashboard_doze),
                value = when (device.dozeExempt) {
                    true -> stringResource(R.string.dashboard_doze_exempt)
                    false -> stringResource(R.string.dashboard_doze_limited)
                    null -> MISSING_VALUE
                },
                mono = device.dozeExempt != null,
                tone = if (device.dozeExempt == true) StatTone.POSITIVE else StatTone.NEUTRAL
            )
        }
        val network = device.network
        if (network != null || device.wifiRssi != null) {
            KeyValueRow(
                label = stringResource(R.string.dashboard_network),
                value = listOfNotNull(
                    network?.let { networkLabel(it) },
                    device.wifiRssi?.let { formatDbm(it) }
                ).joinToString(" · ")
            )
        }
        if (simOperator != null) {
            KeyValueRow(label = stringResource(R.string.dashboard_sim), value = simOperator)
        }
    }
}

@Composable
private fun PermissionCard(missing: List<String>, onOpenSetup: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.dashboard_permissions_title),
        status = {
            if (missing.isEmpty()) {
                StatusPill(
                    label = stringResource(R.string.dashboard_permissions_all_granted),
                    tone = StatusTone.POSITIVE
                )
            } else {
                StatusPill(
                    label = pluralStringResource(
                        R.plurals.dashboard_permissions_missing,
                        missing.size,
                        missing.size
                    ),
                    tone = StatusTone.NEGATIVE
                )
            }
        }
    ) {
        if (missing.isEmpty()) {
            Text(
                text = stringResource(R.string.dashboard_permissions_all_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            missing.forEach { permission ->
                val known = knownPermission(permission)
                if (known != null) {
                    KeyValueRow(
                        label = stringResource(known),
                        value = stringResource(R.string.dashboard_permission_not_granted)
                    )
                } else {
                    Column {
                        KeyValueRow(
                            label = stringResource(R.string.dashboard_permission_other),
                            value = stringResource(R.string.dashboard_permission_not_granted)
                        )
                        Text(
                            text = permission,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            TextButton(onClick = onOpenSetup) {
                Text(stringResource(R.string.dashboard_fix_in_setup))
            }
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
    SectionCard(title = stringResource(R.string.dashboard_pairing_title)) {
        Text(
            text = stringResource(R.string.dashboard_pairing_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenSetup) {
                Text(stringResource(R.string.dashboard_pair_another))
            }
            TextButton(
                onClick = { confirming = true },
                enabled = !inCall,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.dashboard_unpair))
            }
        }
        if (inCall) {
            Text(
                text = stringResource(R.string.dashboard_unpair_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.dashboard_unpair_title)) },
            text = { Text(stringResource(R.string.dashboard_unpair_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onUnpair()
                    }
                ) {
                    Text(stringResource(R.string.dashboard_unpair))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.dashboard_cancel))
                }
            }
        )
    }
}

/**
 * One clock read per second, so "Up for" and the call duration move while the screen is open.
 * [clock] is injectable so a test can prove the value is re-read rather than frozen at composition.
 */
@Composable
internal fun tickingNow(
    periodMs: Long = 1_000L,
    clock: () -> Long = System::currentTimeMillis
): State<Long> = produceState(initialValue = clock()) {
    while (true) {
        delay(periodMs)
        value = clock()
    }
}

/** The link path as the pill names it; an unmeasured path stays "Unknown". */
internal fun PathKind.toDesignPath(): LinkPath = when (this) {
    PathKind.DIRECT -> LinkPath.DIRECT
    PathKind.RELAY -> LinkPath.RELAY
    PathKind.UNKNOWN -> LinkPath.UNKNOWN
}

/**
 * What the call card's timing tile counts and how it names itself. The ring before the line
 * connects counts from placement and is labelled Dialing, the live screen's own word, so it never
 * reads as a call duration; the system's call chip also starts at the answer. From the connect the
 * tile is the conversation's Duration and counts from the bridge's connect stamp, and "Connected
 * at" takes the same stamp, not the placement time. Once the call has ended the count stops at the
 * bridge's end stamp, so the last call keeps the duration it ended with.
 */
internal data class CallTiming(
    val labelRes: Int,
    val elapsedStartEpochMs: Long?,
    /** Where the count stops; null while the call is still running and the card uses its clock. */
    val elapsedEndEpochMs: Long?,
    val connectedAtEpochMs: Long?
)

internal fun callTiming(call: CallState): CallTiming {
    val connected = call.connectedAtEpochMs
    return when {
        connected != null -> CallTiming(
            labelRes = R.string.dashboard_duration,
            elapsedStartEpochMs = connected,
            elapsedEndEpochMs = call.endedAtEpochMs.takeIf { call.phase == CallPhase.ENDED },
            connectedAtEpochMs = connected
        )

        call.phase == CallPhase.DIALING -> CallTiming(
            labelRes = R.string.dashboard_phase_dialing,
            elapsedStartEpochMs = call.startedAtEpochMs,
            elapsedEndEpochMs = null,
            connectedAtEpochMs = null
        )

        // Ending or ended without a connect: the line never carried a conversation, so there is
        // nothing to time and no connect to report.
        else -> CallTiming(
            labelRes = R.string.dashboard_duration,
            elapsedStartEpochMs = null,
            elapsedEndEpochMs = null,
            connectedAtEpochMs = null
        )
    }
}

@Composable
internal fun phaseLabel(phase: CallPhase): String = stringResource(
    when (phase) {
        CallPhase.DIALING -> R.string.dashboard_phase_dialing
        CallPhase.ACTIVE -> R.string.dashboard_phase_live
        CallPhase.ENDING -> R.string.dashboard_phase_ending
        CallPhase.ENDED -> R.string.dashboard_phase_ended
    }
)

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

/** How warm the phone reports itself: the daemon's HAL names, in words a person reads. */
internal enum class ThermalState { NONE, WARM, HOT, UNKNOWN }

internal fun thermalState(raw: String?): ThermalState = when (raw?.lowercase()) {
    null -> ThermalState.UNKNOWN
    "none" -> ThermalState.NONE
    "light" -> ThermalState.WARM
    "moderate", "severe", "critical", "emergency", "shutdown" -> ThermalState.HOT
    else -> ThermalState.UNKNOWN
}

@Composable
internal fun thermalLabel(raw: String): String = stringResource(
    when (thermalState(raw)) {
        ThermalState.NONE -> R.string.dashboard_thermal_normal
        ThermalState.WARM -> R.string.dashboard_thermal_warm
        ThermalState.HOT -> R.string.dashboard_thermal_hot
        ThermalState.UNKNOWN -> R.string.dashboard_thermal_unknown
    }
)

/** The transport the daemon reports, in its own name. */
@Composable
internal fun linkKindLabel(kind: LinkKind): String = stringResource(
    when (kind) {
        LinkKind.IROH -> R.string.dashboard_link_kind_iroh
        LinkKind.WEBSOCKET -> R.string.dashboard_link_kind_websocket
        LinkKind.TAILSCALE -> R.string.dashboard_link_kind_tailscale
    }
)

/** The network a person sees in the status bar, not the HAL's lower-case name. */
internal enum class NetworkKind { WIFI, MOBILE, ETHERNET, UNKNOWN }

internal fun networkKind(raw: String): NetworkKind = when (raw.lowercase()) {
    "wifi", "wi-fi", "wlan" -> NetworkKind.WIFI
    "cellular", "mobile", "lte", "5g", "nr" -> NetworkKind.MOBILE
    "ethernet" -> NetworkKind.ETHERNET
    else -> NetworkKind.UNKNOWN
}

@Composable
internal fun networkLabel(raw: String): String = stringResource(
    when (networkKind(raw)) {
        NetworkKind.WIFI -> R.string.dashboard_network_wifi
        NetworkKind.MOBILE -> R.string.dashboard_network_mobile
        NetworkKind.ETHERNET -> R.string.dashboard_network_ethernet
        NetworkKind.UNKNOWN -> R.string.dashboard_network_unknown
    }
)

/** `-52` → `−52 dBm`: Unicode minus and a no-break space, so the value lines up in monospace. */
internal fun formatDbm(rssi: Int): String {
    val magnitude = if (rssi < 0) "−${-rssi}" else rssi.toString()
    return "$magnitude\u00A0dBm"
}

/** Playback overflows are counted in samples; the tile reports the audio time they cost. */
internal fun playbackDropMillis(samples: Long): Long = samples / AUDIO_SAMPLES_PER_MILLISECOND

internal fun secondsBetween(fromEpochMs: Long, toEpochMs: Long): Long = TimeUnit.MILLISECONDS.toSeconds((toEpochMs - fromEpochMs).coerceAtLeast(0))

/** The device's own clock format, so 12- and 24-hour phones both read correctly. */
private fun timeOf(context: Context, epochMs: Long): String = DateFormat.getTimeFormat(context).format(Date(epochMs))

/** A daemon token the screen shows as words: `reservation_time` → `Reservation time`. */
internal fun humanizeToken(token: String): String = token.replace('_', ' ').replaceFirstChar { it.uppercase() }

internal fun knownPermission(permission: String): Int? = when (permission) {
    "android.permission.RECORD_AUDIO" -> R.string.dashboard_permission_microphone
    "android.permission.CALL_PHONE" -> R.string.dashboard_permission_calls
    "android.permission.READ_PHONE_STATE" -> R.string.dashboard_permission_phone_state
    "android.permission.MODIFY_AUDIO_SETTINGS" -> R.string.dashboard_permission_audio_settings
    "android.permission.POST_NOTIFICATIONS" -> R.string.dashboard_permission_notifications
    "android.permission.ACCESS_LOCAL_NETWORK" -> R.string.dashboard_permission_local_network
    else -> null
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

    /** A public endpoint id as the engine publishes it: the whole key, never a shortened one. */
    const val ENDPOINT_ID = "864b90b7d1f2a5c6e93b47f80a1c2d3e4f5061728394a5b6c7d8e9f0a1b2cc62"

    val direct = BridgeState(
        link = LinkState.Up(
            kind = LinkKind.IROH,
            path = PathKind.DIRECT,
            address = ENDPOINT_ID,
            rttMs = 41,
            sinceEpochMs = now - TimeUnit.MINUTES.toMillis(12),
            reconnects = 0
        ),
        audio = AudioState(
            codec = "opus",
            // The engine publishes bits per second; the tile converts to kbit/s.
            bitrate = 32_000,
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
            startedAtEpochMs = now - TimeUnit.SECONDS.toMillis(74),
            // The answer came 34 s after the ring; the card counts the conversation from here.
            connectedAtEpochMs = now - TimeUnit.SECONDS.toMillis(40)
        ),
        audio = direct.audio.copy(rxLevelDbfs = -8f, txLevelDbfs = -34f, drops = 43_200)
    )

    /** The ring, before the answer: the card counts it but calls it Dialing, not a duration. */
    val dialing = relayInCall.copy(
        call = relayInCall.call?.copy(
            phase = CallPhase.DIALING,
            startedAtEpochMs = now - TimeUnit.SECONDS.toMillis(9),
            connectedAtEpochMs = null
        )
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
