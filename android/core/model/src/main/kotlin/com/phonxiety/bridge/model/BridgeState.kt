package com.phonxiety.bridge.model

import kotlinx.serialization.Serializable

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
    val startedAtEpochMs: Long?
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

@Serializable
data class LinkError(val code: String, val message: String, val atEpochMs: Long)
