package com.phonxiety.bridge.net

import okio.ByteString

/**
 * One transport carrying the device protocol.
 *
 * [LinkSupervisor] owns the single live link; a [DeviceLink] only moves whole
 * messages.  Each implementation reports every terminal event at most once
 * through [Listener.onLinkDown] and stops sending after that.
 */
internal interface DeviceLink {
    /** Route the link currently uses, or null when the transport has no route. */
    data class Route(val relay: Boolean, val rttMs: Long?)

    interface Listener {
        fun onLinkOpened()
        fun onLinkText(text: String)
        fun onLinkBinary(bytes: ByteString)
        fun onLinkDown(reason: String)
    }

    /** Begins the connection attempt.  May call back before returning. */
    fun start(listener: Listener)

    /** Queues one JSON text message.  Never dropped by the transport. */
    fun sendText(message: String): Boolean

    /** Queues one binary frame of [length] bytes from [frame]. */
    fun sendBinary(frame: ByteArray, length: Int)

    /** Bytes accepted but not yet written to the peer. */
    val backlogBytes: Long

    /**
     * Stops the link without reporting down: the owner initiated the close.
     */
    fun cancel()

    /** Route for the UI; null for transports without one. */
    fun route(): Route?
}

/**
 * Creates links for one pairing and releases whatever outlives a single link
 * (the OkHttp pool, the process-wide iroh endpoint).
 */
internal interface DeviceLinkFactory {
    fun create(): DeviceLink

    /** Called once when the engine closes. */
    fun shutdown()
}
