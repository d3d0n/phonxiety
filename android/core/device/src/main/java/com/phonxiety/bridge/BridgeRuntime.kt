package com.phonxiety.bridge

import android.telecom.Call
import com.phonxiety.bridge.model.AppGateway
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.PairingGate
import com.phonxiety.bridge.telecom.BridgeInCallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    /**
     * The app-API credential of the engine installed now, null while none is. One stable flow:
     * [attach] follows the new engine's own flow and [detach] drops it, so a caller holding this
     * flow never keeps reading a retired engine's token.
     */
    private val mutableAppToken = MutableStateFlow<String?>(null)

    val appToken: StateFlow<String?> = mutableAppToken.asStateFlow()

    private val tokenScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var tokenJob: Job? = null

    /**
     * Follows [source] for the installed engine. Unconfined is enough: mirroring one state flow
     * needs no thread of its own, and each emission lands before the next call returns.
     */
    internal fun followAppToken(source: StateFlow<String?>?) {
        tokenJob?.cancel()
        tokenJob = null
        mutableAppToken.value = source?.value
        if (source == null) return
        tokenJob = tokenScope.launch {
            source.collect { mutableAppToken.value = it }
        }
    }

    @Volatile private var pairingKind: BridgeConfig.Kind? = null

    /** Serializes pairing publication with the service's adoption of the same file. */
    private val transaction = Any()

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

    /**
     * Ends the engine-owned call on this device; false when the service has no
     * engine to ask, so nothing was queued.
     */
    fun endOwnedCall(callId: String?): Boolean = engine?.endOwnedCall(callId) ?: false

    /** Route of the live link, or null while it is down or unavailable. */
    fun currentRoute(): Route? = engine?.currentRoute()?.let { Route(relay = it.relay, rttMs = it.rttMs) }

    /** Installs the service's engine and mirrors its state process-wide. */
    internal fun attach(current: BridgeEngine, identity: String) {
        stateJob?.cancel()
        engine = current
        PairingGate.install(current, identity)
        followAppToken(current.appToken)
        stateJob = scope.launch {
            current.state.collect { mutableState.value = it }
        }
    }

    /** Detaches [current] if it is still the installed engine. */
    internal fun detach(current: BridgeEngine) {
        PairingGate.uninstall(current)
        if (engine !== current) return
        followAppToken(null)
        stateJob?.cancel()
        stateJob = null
        engine = null
        mutableState.value = BridgeState()
    }

    /**
     * Runs [block] while no other pairing publication or service adoption runs.
     *
     * The pairing file and the expectation of it must move together: a start
     * that read the previous file must not re-expose the previous gateway after
     * a re-pair published the new one.  Both sides enter this transaction, so a
     * stale start either runs before the write (and is then replaced) or after
     * it (and adopts the new pairing).
     */
    fun <T> pairingTransaction(block: () -> T): T = synchronized(transaction) { block() }

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
