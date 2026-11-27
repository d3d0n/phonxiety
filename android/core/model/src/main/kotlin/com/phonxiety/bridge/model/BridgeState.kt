package com.phonxiety.bridge.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Everything the UI, tiles and the widget show about the bridge, produced by the engine on
 * one scope and exposed as a `StateFlow`. Surfaces never talk to the transport.
 */
@Serializable
data class BridgeState(
    val link: LinkState = LinkState.Offline,
    val call: CallState? = null,
    val audio: AudioState = AudioState(),
    val device: DeviceState = DeviceState(),
    val capabilities: Capabilities = Capabilities(),
    val lastError: LinkError? = null
)

@Serializable
sealed interface LinkState {
    @Serializable
    data object Offline : LinkState

    @Serializable
    data class Connecting(val candidate: LinkKind) : LinkState

    @Serializable
    data class Up(
        val kind: LinkKind,
        val path: PathKind,
        val address: String?,
        val rttMs: Long?,
        val sinceEpochMs: Long,
        val reconnects: Int
    ) : LinkState
}

/** How the phone reaches the daemon. */
@Serializable
enum class LinkKind { IROH, WEBSOCKET, TAILSCALE }

/** Which network path the selected transport uses right now. */
@Serializable
enum class PathKind { DIRECT, RELAY, UNKNOWN }

@Serializable
data class CallState(
    val callId: String,
    val destinationMasked: String,
    val phase: CallPhase,
    val startedAtEpochMs: Long?,
    /**
     * When the line connected, in wall-clock milliseconds. The engine records it once, from
     * Telecom's own connect time where it exists, so the screen counts the conversation even when
     * it opens long after the answer. Null until the call reaches [CallPhase.ACTIVE].
     */
    val connectedAtEpochMs: Long? = null,
    /**
     * When the line ended, in wall-clock milliseconds. The engine records it once, from its own
     * clock when the phase becomes [CallPhase.ENDED], so an ended call's duration is end minus
     * connect and stops growing. Null before the call ends; a replayed end keeps the first stamp.
     */
    val endedAtEpochMs: Long? = null
)

@Serializable
enum class CallPhase { DIALING, ACTIVE, ENDING, ENDED }

@Serializable
data class AudioState(
    val codec: String? = null,
    val bitrate: Int? = null,
    val rxLevelDbfs: Float? = null,
    val txLevelDbfs: Float? = null,
    val framesOut: Long = 0,
    val framesIn: Long = 0,
    val drops: Long = 0
)

@Serializable
data class DeviceState(
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val thermal: String? = null,
    val network: String? = null,
    val wifiRssi: Int? = null,
    val dozeExempt: Boolean? = null,
    val missingPermissions: List<String> = emptyList()
)

@Serializable
data class Capabilities(
    val callControl: Boolean = false,
    val frameworkAudio: Boolean = false,
    val appApi: Int? = null,
    val daemonVersion: String? = null
)

/**
 * Why one transport link went down, typed.
 *
 * [wire] is the exact string [LinkError.code] has always carried for a named
 * reason, and an unnamed native close keeps its own text there (see [text]):
 * surfaces, logs and the Doctor show it, so its spelling must never change.  Only a
 * transport's own link-down belongs here; engine error codes such as
 * `audio_error` stay plain [LinkError.code] values with no [LinkError.reason].
 */
enum class LinkDownReason(val wire: String) {
    HOST_TIMEOUT("host_timeout"),
    CONNECT_FAILED("connect_failed"),
    CONNECT_TIMEOUT("connect_timeout"),
    HELLO_FAILED("hello_failed"),
    CLOSED("closed"),
    CLOSING("closing"),
    PROTOCOL("protocol"),
    WRITE_FAILED("write_failed"),
    RELAY_ONLY("relay_only"),
    CONTROL_BACKLOG("control_backlog"),
    NETWORK_CHANGED("network_changed"),
    NETWORK_LOST("network_lost"),
    CONNECTION_MODE_CHANGED("connection_mode_changed"),
    CANDIDATE_BETTER("candidate_better"),
    CAPABILITIES_CHANGED("capabilities_changed"),
    INVALID_URL("invalid_url"),
    FAILURE("failure"),
    CREATION_FAILED("creation_failed"),
    NO_CANDIDATE("no_candidate"),
    IROH_UNAVAILABLE("iroh_unavailable"),

    /** A native close this build cannot name; [LinkError.detail] keeps its text. */
    OTHER("other");

    /**
     * The string this link-down has always been known by in [LinkError.code]
     * and the link-down log lines: the [wire] spelling, or for [OTHER] the
     * native close text itself, so an unnamed close still reads as what the
     * transport said.
     */
    fun text(detail: String?): String = if (this == OTHER) detail ?: wire else wire

    companion object {
        /**
         * The typed reason for a transport's own close text.  Empty text is
         * [CLOSED], the way the transports have always substituted it; a text
         * that names one of the values above keeps it, and anything else is
         * [OTHER] so the caller can keep the original text as detail.
         */
        fun fromNativeClose(text: String): LinkDownReason = if (text.isEmpty()) {
            CLOSED
        } else {
            entries.firstOrNull { it != OTHER && it.wire == text } ?: OTHER
        }
    }
}

/**
 * [code] is the string surfaces and the Doctor show; [message] is the one
 * sentence a user reads; [reason] types the transport's own link-down and is
 * null for an engine error code, and [detail] keeps the native close text
 * behind [LinkDownReason.OTHER].  The two typed fields stay out of the
 * serialized shape: the encoded error keeps exactly `code`, `message` and
 * `atEpochMs`.
 */
@Serializable
data class LinkError(
    val code: String,
    val message: String,
    val atEpochMs: Long,
    @Transient val reason: LinkDownReason? = null,
    @Transient val detail: String? = null
)
