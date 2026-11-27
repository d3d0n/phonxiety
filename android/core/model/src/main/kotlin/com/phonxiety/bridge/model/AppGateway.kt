package com.phonxiety.bridge.model

import java.io.OutputStream
import kotlinx.coroutines.flow.StateFlow

/**
 * The engine side of the app API: one request/response exchange on the device
 * link and the bulk attachment stream.  Implemented by the engine and consumed
 * by the data layer; no caller ever touches the transport.
 */
interface AppGateway {
    /**
     * The app API credential this gateway was built for, as the daemon last handed it over.  A
     * request takes it from the same gateway that carries it, so a pairing change can never pair
     * one daemon's address with another daemon's token; a request refused with an old one waits on
     * the same flow for the daemon's replacement.  A retired engine's flow stops moving.
     */
    val appToken: StateFlow<String?>

    /** True when the live link can carry a phone-opened attachment stream. */
    val supportsAttachments: Boolean

    /** Sends one `api` message and waits for its `api_result`; [deadlineMs] bounds readiness and the answer. */
    suspend fun exchange(request: DeviceApiRequest, deadlineMs: Long): DeviceApiResult

    /** Streams one attachment into [sink]; [AttachmentOutcome.Unsupported] on a paged link. */
    suspend fun receiveAttachment(header: AttachmentHeader, sink: OutputStream): AttachmentOutcome
}

/** Process-wide lookup of the service-owned gateway, provided by the device layer. */
fun interface AppGatewaySource {
    fun gateway(): AppGateway?
}
