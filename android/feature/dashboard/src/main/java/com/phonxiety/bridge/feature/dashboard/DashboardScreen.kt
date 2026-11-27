package com.phonxiety.bridge.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.RegisterScrollToTop
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.feature.dashboard.cards.AudioCard
import com.phonxiety.bridge.feature.dashboard.cards.CallCard
import com.phonxiety.bridge.feature.dashboard.cards.DashboardHeader
import com.phonxiety.bridge.feature.dashboard.cards.DeviceCard
import com.phonxiety.bridge.feature.dashboard.cards.LinkCard
import com.phonxiety.bridge.feature.dashboard.cards.PairingCard
import com.phonxiety.bridge.feature.dashboard.cards.PermissionCard
import com.phonxiety.bridge.feature.dashboard.cards.QuickActionsCard
import com.phonxiety.bridge.feature.dashboard.cards.isLive
import com.phonxiety.bridge.model.AudioState
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import com.phonxiety.bridge.model.DeviceState
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The key the navigation host dispatches when the user taps the active Home tab again. It is the
 * same string the host's `Section.DASHBOARD` holds; both sides pin it.
 */
internal const val DASHBOARD_SCROLL_TO_TOP_KEY = "dashboard"

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
        onRequestUnpair = viewModel::requestUnpair,
        onDismissUnpair = viewModel::dismissUnpair,
        onConfirmUnpair = viewModel::unpair,
        modifier = modifier
    )
}

/**
 * Link, call, audio and device cards plus the permissions the bridge is missing. Every "connected"
 * claim on this screen is a measurement from [DashboardUiState.bridge]; nothing here probes.
 *
 * The call card comes first because it answers "what is happening now"; the actions follow the
 * answer. [nowEpochMs] fixes the clock for previews and screenshots; the screen ticks by itself.
 *
 * The unpair question is a value of [state], not of this composition: a rotation re-composes the
 * screen with the same pending confirmation and the dialog stays open. The screen registers its
 * scroll-to-top action under [DASHBOARD_SCROLL_TO_TOP_KEY], so a re-tap of the active Home tab in
 * the host brings the header back into view.
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
    onRequestUnpair: () -> Unit,
    onDismissUnpair: () -> Unit,
    onConfirmUnpair: () -> Unit,
    modifier: Modifier = Modifier,
    nowEpochMs: Long? = null
) {
    val ticked by tickingNow()
    val now = nowEpochMs ?: ticked
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    RegisterScrollToTop(DASHBOARD_SCROLL_TO_TOP_KEY) {
        scope.launch { scrollState.animateScrollTo(0) }
    }
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
                    .verticalScroll(scrollState)
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
                        confirming = state.confirmingUnpair,
                        onOpenSetup = onOpenSetup,
                        onRequestUnpair = onRequestUnpair,
                        onDismissUnpair = onDismissUnpair,
                        onConfirmUnpair = onConfirmUnpair
                    )
                }
            }
        }
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
            onRequestUnpair = {},
            onDismissUnpair = {},
            onConfirmUnpair = {},
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
            onRequestUnpair = {},
            onDismissUnpair = {},
            onConfirmUnpair = {},
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
            onRequestUnpair = {},
            onDismissUnpair = {},
            onConfirmUnpair = {},
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
