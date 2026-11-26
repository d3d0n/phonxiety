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
 * Identity of one live audio stream: one `start_audio` epoch and its
 * [PhoneAudio].  Audio stats belong to the stream that produced them, so the
 * store accepts a publish only while its stream is the live one and a stream
 * that has been torn down can never show its numbers as current.
 */
internal class AudioStream internal constructor(private val id: Long) {
    override fun toString(): String = "audio-stream#$id"
}

/**
 * One publish of a live audio stream's stats.  Everything goes in together so
 * a teardown cannot interleave between the counters and the level meters.
 *
 * [drops] counts playback-ring overflow only: samples the phone's 500 ms
 * playback buffer discarded because the sink fell behind.  A barge-in flush
 * (`PhoneAudio.flush()`) drops the queued tail without counting it, so
 * barge-in loss is deliberately excluded — the tile says "Playback drops", not
 * "all audio lost".
 */
internal data class AudioStats(
    val codec: String?,
    val bitrate: Int?,
    val rxDbfs: Float?,
    val txDbfs: Float?,
    val framesOut: Long,
    val framesIn: Long,
    val drops: Long
)

/**
 * The engine's one state machine.  Everything that changes [BridgeState] goes
 * through here, so surfaces see a single ordered stream regardless of which
 * callback or thread produced the event.
 */
class EngineStateStore {
    private val mutable = MutableStateFlow(BridgeState())

    val state: StateFlow<BridgeState> = mutable.asStateFlow()

    /** Guards [liveAudioStream] with the writes it gates; never held across foreign calls. */
    private val audioLock = Any()
    private var liveAudioStream: AudioStream? = null
    private var audioStreamIds = 0L

    fun reset() {
        synchronized(audioLock) { liveAudioStream = null }
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

    /**
     * Moves the call to [phase], recording its connect time on the first [CallPhase.ACTIVE].
     * Telecom's own [connectTimeMillis] is preferred when it is positive, because the system's
     * call chip counts from it; otherwise the engine's clock at this transition is used. The stamp
     * belongs to the call and is never replaced by a later ACTIVE, so a screen opened mid-call
     * counts from the answer rather than from the moment it opened.
     */
    fun callPhase(phase: CallPhase, connectTimeMillis: Long? = null) {
        mutable.update { current ->
            val call = current.call ?: return@update current
            val connectedAt = when {
                call.connectedAtEpochMs != null -> call.connectedAtEpochMs
                phase != CallPhase.ACTIVE -> null
                connectTimeMillis != null && connectTimeMillis > 0 -> connectTimeMillis
                else -> System.currentTimeMillis()
            }
            current.copy(call = call.copy(phase = phase, connectedAtEpochMs = connectedAt))
        }
    }

    fun callEnded() {
        mutable.update { current ->
            val call = current.call ?: return@update current
            current.copy(call = call.copy(phase = CallPhase.ENDED))
        }
    }

    /** Sets the live stream's codec before its first 100 ms publish; ignored once the stream is gone. */
    internal fun audio(stream: AudioStream, codec: String?, bitrate: Int?) {
        synchronized(audioLock) {
            if (liveAudioStream !== stream) return@synchronized
            mutable.update { it.copy(audio = it.audio.copy(codec = codec, bitrate = bitrate)) }
        }
    }

    /** Starts a new live audio stream; the previous stream's numbers stop being publishable. */
    internal fun audioStreamStart(): AudioStream = synchronized(audioLock) {
        AudioStream(++audioStreamIds).also { liveAudioStream = it }
    }

    /**
     * Publishes a live stream's stats in one step.  Returns false, writing
     * nothing, when [stream] is no longer the live one: a publish that was in
     * flight while the stream was torn down must never land after
     * [audioReset], or the last call's drops would sit on the dashboard with
     * no call running and nothing left to overwrite them.
     */
    internal fun audioStats(stream: AudioStream, stats: AudioStats): Boolean = synchronized(audioLock) {
        if (liveAudioStream !== stream) return@synchronized false
        mutable.update {
            it.copy(
                audio = it.audio.copy(
                    codec = stats.codec,
                    bitrate = stats.bitrate,
                    rxLevelDbfs = stats.rxDbfs,
                    txLevelDbfs = stats.txDbfs,
                    framesOut = stats.framesOut,
                    framesIn = stats.framesIn,
                    drops = stats.drops
                )
            )
        }
        true
    }

    /**
     * Clears the audio block of exactly [stream].  A stream that has already
     * been replaced is left alone, so a teardown that arrives late cannot wipe
     * the numbers of the call that started meanwhile.
     *
     * There is deliberately no parameterless form: a teardown that captured no
     * stream has nothing to clear, and resetting "whatever is live" is how a
     * delayed cleanup invalidates a successor's stream for its whole lifetime.
     * Whole-engine clears go through [resetAllAudio].
     */
    internal fun audioReset(stream: AudioStream) {
        synchronized(audioLock) {
            if (liveAudioStream !== stream) return@synchronized
            liveAudioStream = null
            mutable.update { it.copy(audio = AudioState()) }
        }
    }

    /**
     * Clears the audio block regardless of which stream owns it.  Only for the
     * engine's own reset and shutdown, where nothing may keep publishing.
     */
    internal fun resetAllAudio() {
        synchronized(audioLock) {
            liveAudioStream = null
            mutable.update { it.copy(audio = AudioState()) }
        }
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
