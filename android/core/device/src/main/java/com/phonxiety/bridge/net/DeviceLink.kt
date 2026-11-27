package com.phonxiety.bridge.net

import com.phonxiety.bridge.model.LinkDownReason
import com.phonxiety.bridge.model.LinkKind
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

        /**
         * The link ended for [reason]; [detail] keeps a native close text the
         * typed reason cannot name ([LinkDownReason.OTHER]) for diagnostics.
         */
        fun onLinkDown(reason: LinkDownReason, detail: String? = null)
    }

    /** Begins the connection attempt.  May call back before returning. */
    fun start(listener: Listener)

    /** Queues one JSON text message.  Never dropped by the transport. */
    fun sendText(message: String): Boolean

    /** Queues one binary frame of [length] bytes from [frame]. */
    fun sendBinary(frame: ByteArray, length: Int)

    /**
     * Queues one binary frame of [length] bytes from [frame] and reports whether
     * the transport took it: false means the frame is lost, so live capture
     * counts it as unsent instead of assuming delivery.  Default: accepted,
     * which is all a transport without its own refusal can say.
     */
    fun offerBinary(frame: ByteArray, length: Int): Boolean {
        sendBinary(frame, length)
        return true
    }

    /** Bytes accepted but not yet written to the peer. */
    val backlogBytes: Long

    /**
     * Stops the link without reporting down: the owner initiated the close.
     */
    fun cancel()

    /** Route for the UI; null for transports without one. */
    fun route(): Route?

    /**
     * The transport this attempt was built for, captured when it was created or
     * selected; null when no candidate was ever chosen.  Recovery accounting
     * reads this per attempt instead of a factory's mutable current candidate.
     */
    val kind: LinkKind? get() = null

    /** True when the transport can carry a phone-opened bulk stream. */
    val supportsAttachments: Boolean get() = false

    /**
     * Opens one attachment stream and writes its header frame; null when the
     * transport has no attachment streams or the link is not live.
     */
    suspend fun openAttachment(header: String): AttachmentChannel? = null
}

/** One readable attachment stream: data frames, then `end` or `error`. */
internal interface AttachmentChannel {
    suspend fun next(): AttachmentFrame

    fun cancel()
}

internal sealed interface AttachmentFrame {
    data class Data(val bytes: ByteArray) : AttachmentFrame

    data class End(val total: Long, val sha256: String) : AttachmentFrame

    data class Error(val code: String, val message: String) : AttachmentFrame
}

/** How one accepted link attempt ended, for the transport's recovery accounting. */
internal enum class AttemptOutcome {
    /** The attempt opened and the supervisor accepted it. */
    OPENED,

    /** The attempt ended without ever opening. */
    FAILED_BEFORE_OPEN
}

/**
 * Creates links for one pairing and releases whatever outlives a single link
 * (the OkHttp pool, the process-wide iroh endpoint).
 */
internal interface DeviceLinkFactory {
    fun create(): DeviceLink

    /** Called once when the engine closes. */
    fun shutdown()

    /**
     * One accepted attempt's outcome, exactly once per attempt.  [kind] is the
     * transport that attempt was built for, or null when it ended before one
     * was selected.  Default: no recovery accounting.
     */
    fun attemptEnded(outcome: AttemptOutcome, kind: LinkKind?) {}

    /**
     * The candidate the last created link was built for; null for factories
     * that have only one transport.
     */
    fun selected(): SelectedCandidate? = null

    /**
     * Runs one selection round over the current candidates and reports whether
     * the link should move to another one.  False for factories with one
     * transport, and false when the round only learned something.
     */
    fun considerSwitch(): Boolean = false
}
