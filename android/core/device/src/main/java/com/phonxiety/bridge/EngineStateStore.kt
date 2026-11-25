package com.phonxiety.bridge

import com.phonxiety.bridge.model.AudioState
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import com.phonxiety.bridge.model.Capabilities
import com.phonxiety.bridge.model.DeviceState
import com.phonxiety.bridge.model.LinkError
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A selected path as the engine sees it, independent of the transport's type. */
data class RouteSnapshot(val relay: Boolean, val rttMs: Long?)

/**
 * The engine's one state machine.  Everything that changes [BridgeState] goes
 * through here, so surfaces see a single ordered stream regardless of which
 * callback or thread produced the event.
 */
class EngineStateStore {
    private val mutable = MutableStateFlow(BridgeState())

    val state: StateFlow<BridgeState> = mutable.asStateFlow()

    fun reset() {
        mutable.value = BridgeState()
    }

    fun linkConnecting(kind: LinkKind) {
        mutable.update { it.copy(link = LinkState.Connecting(kind), lastError = null) }
    }

    fun linkUp(
        kind: LinkKind,
        route: RouteSnapshot?,
        sinceEpochMs: Long,
        reconnects: Int,
        address: String? = null
    ) {
        mutable.update {
            it.copy(
                link = LinkState.Up(
                    kind = kind,
                    path = route?.let { selected -> if (selected.relay) PathKind.RELAY else PathKind.DIRECT } ?: PathKind.UNKNOWN,
                    address = address,
                    rttMs = route?.rttMs,
                    sinceEpochMs = sinceEpochMs,
                    reconnects = reconnects
                ),
                lastError = null
            )
        }
    }

    fun route(route: RouteSnapshot?) {
        mutable.update { current ->
            val up = current.link as? LinkState.Up ?: return@update current
            current.copy(
                link = up.copy(
                    path = if (route == null) {
                        PathKind.UNKNOWN
                    } else if (route.relay) {
                        PathKind.RELAY
                    } else {
                        PathKind.DIRECT
                    },
                    rttMs = route?.rttMs
                )
            )
        }
    }

    fun linkDown(error: LinkError?) {
        mutable.update {
            it.copy(
                link = LinkState.Offline,
                call = it.call?.takeIf { call -> call.phase == CallPhase.ENDED },
                lastError = error
            )
        }
    }

    fun callStarted(callId: String, destinationMasked: String, startedAtEpochMs: Long) {
        mutable.update {
            it.copy(
                call = CallState(
                    callId = callId,
                    destinationMasked = destinationMasked,
                    phase = CallPhase.DIALING,
                    startedAtEpochMs = startedAtEpochMs
                )
            )
        }
    }

    fun callPhase(phase: CallPhase) {
        mutable.update { current ->
            val call = current.call ?: return@update current
            current.copy(call = call.copy(phase = phase))
        }
    }

    fun callEnded() {
        mutable.update { current ->
            val call = current.call ?: return@update current
            current.copy(call = call.copy(phase = CallPhase.ENDED))
        }
    }

    fun audio(codec: String?, bitrate: Int?) {
        mutable.update { it.copy(audio = it.audio.copy(codec = codec, bitrate = bitrate)) }
    }

    fun audioLevels(rxDbfs: Float?, txDbfs: Float?) {
        mutable.update { it.copy(audio = it.audio.copy(rxLevelDbfs = rxDbfs, txLevelDbfs = txDbfs)) }
    }

    fun audioFrames(framesOut: Long, framesIn: Long, drops: Long) {
        mutable.update {
            it.copy(audio = it.audio.copy(framesOut = framesOut, framesIn = framesIn, drops = drops))
        }
    }

    fun audioReset() {
        mutable.update { it.copy(audio = AudioState()) }
    }

    fun device(state: DeviceState) {
        mutable.update { it.copy(device = state) }
    }

    fun capabilities(capabilities: Capabilities) {
        mutable.update { it.copy(capabilities = capabilities) }
    }

    fun error(code: String, message: String, atEpochMs: Long = System.currentTimeMillis()) {
        mutable.update { it.copy(lastError = LinkError(code, message, atEpochMs)) }
    }
}

/** "dialing" / "active" / "ending" / "ended" as the protocol reports them. */
fun callPhaseOf(state: String): CallPhase = when (state) {
    "dialing" -> CallPhase.DIALING
    "active" -> CallPhase.ACTIVE
    "ending" -> CallPhase.ENDING
    else -> CallPhase.ENDED
}

/** Keeps the leading + and the last four digits; never exposes the whole number. */
fun maskDestination(destination: String): String {
    val digits = destination.filter { it.isDigit() }
    if (digits.length <= 4) return destination
    val visible = destination.takeLast(4)
    val prefix = if (destination.startsWith("+")) "+" else ""
    return prefix + "*".repeat(destination.length - visible.length - prefix.length) + visible
}
