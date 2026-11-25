package com.phonxiety.bridge

import android.telecom.Call
import com.phonxiety.bridge.net.DeviceLink

/**
 * Process-wide hand-off between the idle bridge service and the Telecom-bound
 * InCallService.  Both run in the same app process.
 *
 * The UI reads the link kind, the live state and the route from here; none of
 * it exposes the bearer token.
 */
object BridgeRuntime {
    enum class LinkState { NOT_PAIRED, CONNECTING, CONNECTED, CALL_IN_PROGRESS }

    @Volatile var engine: BridgeEngine? = null
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
    internal fun currentRoute(): DeviceLink.Route? = engine?.currentRoute()

    fun rememberPairing(kind: BridgeConfig.Kind) {
        pairingKind = kind
    }

    fun forgetPairing() {
        pairingKind = null
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
