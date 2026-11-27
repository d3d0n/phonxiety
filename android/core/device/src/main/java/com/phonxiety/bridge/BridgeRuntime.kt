package com.phonxiety.bridge

import android.telecom.Call
import com.phonxiety.bridge.model.AppGateway
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.PairingGate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide hand-off between the idle bridge service and the Telecom-bound
 * InCallService.  Both run in the same app process.
 *
 * The UI reads the link kind, the live state and the route from here; none of
 * it exposes the bearer token.
 */
object BridgeRuntime {
    enum class LinkState { NOT_PAIRED, CONNECTING, CONNECTED, CALL_IN_PROGRESS }

    /** Route of the live link, in a form the UI can render. */
    data class Route(val relay: Boolean, val rttMs: Long?)

    private val scope = bridgeScope("phonxiety-runtime")
    private val mutableState = MutableStateFlow(BridgeState())
    private var stateJob: Job? = null

    /** The one hot bridge state every surface collects, whether or not the service runs. */
    val state: StateFlow<BridgeState> = mutableState.asStateFlow()

    @Volatile internal var engine: BridgeEngine? = null
        private set

    @Volatile var inCallService: BridgeInCallService? = null

    @Volatile private var pairingKind: BridgeConfig.Kind? = null

    /** Link kind of the stored pairing, or null while unpaired. */
    val pairing: BridgeConfig.Kind?
        get() = pairingKind

    val linkState: LinkState
        get() {
            if (pairingKind == null) return LinkState.NOT_PAIRED
            val current = engine ?: return LinkState.CONNECTING
            if (current.activeCallState() != null) return LinkState.CALL_IN_PROGRESS
            return if (current.linkIsUp()) LinkState.CONNECTED else LinkState.CONNECTING
        }

    /** `dialing`, `active` or `ending` while the bridge owns a call. */
    fun currentCallState(): String? = engine?.activeCallState()

    /** Route of the live link, or null while it is down or unavailable. */
    fun currentRoute(): Route? = engine?.currentRoute()?.let { Route(relay = it.relay, rttMs = it.rttMs) }

    /** Installs the service's engine and mirrors its state process-wide. */
    internal fun attach(current: BridgeEngine, identity: String) {
        stateJob?.cancel()
        engine = current
        PairingGate.install(current, identity)
        stateJob = scope.launch {
            current.state.collect { mutableState.value = it }
        }
    }

    /** Detaches [current] if it is still the installed engine. */
    internal fun detach(current: BridgeEngine) {
        PairingGate.uninstall(current)
        if (engine !== current) return
        stateJob?.cancel()
        stateJob = null
        engine = null
        mutableState.value = BridgeState()
    }

    /**
     * Points every app-API caller at [identity]: a different pairing closes the
     * installed gateway in the same step, so no request can leave through the
     * previous daemon while the service is still being rebuilt.
     */
    fun expectPairing(identity: String?) = PairingGate.expect(identity)

    /** The current pairing generation, for an answer that must not outlive it. */
    fun pairingEpoch(): Long = PairingGate.epoch()

    /** The live app-API gateway for the expected pairing, or null while the service is not running. */
    fun appGateway(): AppGateway? = PairingGate.gateway()

    fun rememberPairing(kind: BridgeConfig.Kind) {
        pairingKind = kind
    }

    fun forgetPairing() {
        pairingKind = null
        PairingGate.expect(null)
    }

    fun currentCalls(): List<Call> {
        val service = inCallService ?: return emptyList()
        return try {
            service.calls
        } catch (_: Exception) {
            emptyList()
        }
    }
}
