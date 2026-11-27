package com.phonxiety.bridge.net

import com.phonxiety.bridge.model.AttachmentControl
import com.phonxiety.bridge.model.ControlEnvelope
import com.phonxiety.bridge.model.LinkDownReason
import computer.iroh.Connection
import computer.iroh.RecvStream
import java.util.concurrent.atomic.AtomicBoolean
import okio.ByteString.Companion.toByteString

/** Reads exactly [length] bytes from one stream; the test seam under the frame readers. */
internal fun interface IrohFrameSource {
    suspend fun read(length: Int): ByteArray
}

internal fun RecvStream.asFrameSource(): IrohFrameSource = IrohFrameSource { length ->
    readExact(length.toUInt())
}

/**
 * Reads framed messages from one stream.  [expectedKind] is the only frame kind
 * the stream may carry: text on control, binary on an audio stream.  A frame of
 * the other kind is a protocol error.  [tap] records the frame's size and type,
 * never its bytes.
 *
 * [fail] reports the link down; it ignores a link that already ended, so the
 * reader simply returns after either a truncation or a throw.
 */
internal suspend fun readIrohFrames(
    source: IrohFrameSource,
    expectedKind: Int,
    stream: FrameStream,
    tap: FrameTapSink,
    listener: DeviceLink.Listener,
    fail: (LinkDownReason) -> Unit
) {
    try {
        while (true) {
            val header = source.read(Framing.HEADER_BYTES)
            if (header.size != Framing.HEADER_BYTES) {
                fail(LinkDownReason.CLOSED)
                return
            }
            val parsed = Framing.parse(header)
            if (parsed == null || parsed.kind != expectedKind) {
                fail(LinkDownReason.PROTOCOL)
                return
            }
            val payload = source.read(parsed.length)
            if (payload.size != parsed.length) {
                fail(LinkDownReason.CLOSED)
                return
            }
            if (tap.enabled) tap.frame(FrameDirection.IN, stream, parsed.kind, parsed.length)
            if (expectedKind == Framing.KIND_TEXT) {
                listener.onLinkText(payload.toString(Charsets.UTF_8))
            } else {
                listener.onLinkBinary(payload.toByteString())
            }
        }
    } catch (t: Throwable) {
        fail(LinkDownReason.CLOSED)
    }
}

/** Accepts the daemon→phone audio stream after auth, then reads its frames. */
internal suspend fun readIrohAudio(
    link: Connection,
    tap: FrameTapSink,
    listener: DeviceLink.Listener,
    fail: (LinkDownReason) -> Unit
) {
    val incoming = try {
        link.acceptUni()
    } catch (t: Throwable) {
        fail(LinkDownReason.CLOSED)
        return
    }
    readIrohFrames(incoming.asFrameSource(), Framing.KIND_BINARY, FrameStream.AUDIO, tap, listener, fail)
}

/**
 * Echoes the daemon's `PXLT` datagram probes, which measure the raw path
 * during a call, unchanged.  Other datagrams are ignored, as is a probe
 * that no longer fits the connection's current limit.
 */
internal suspend fun echoDatagrams(
    link: Connection,
    isCancelled: () -> Boolean,
    isDown: () -> Boolean
) {
    while (!isCancelled() && !isDown()) {
        val datagram = try {
            link.readDatagram()
        } catch (_: Throwable) {
            // The connection is gone; the closed watcher reports the reason.
            return
        }
        if (!isLinkProbe(datagram)) continue
        val limit = try {
            link.maxDatagramSize()?.toLong()
        } catch (_: Throwable) {
            null
        }
        if (limit != null && (limit <= 0L || datagram.size > limit)) continue
        runCatching { link.sendDatagram(datagram) }
    }
}

private fun isLinkProbe(bytes: ByteArray): Boolean = bytes.size >= 4 &&
    bytes[0] == 0x50.toByte() && // P
    bytes[1] == 0x58.toByte() && // X
    bytes[2] == 0x4C.toByte() && // L
    bytes[3] == 0x54.toByte() // T

/**
 * One phone-opened bulk stream: binary frames are data, a text frame ends the
 * stream (or reports the daemon's error).  The stream stays independent of the
 * control and audio streams, so a large recording never delays a command.
 *
 * [cancelStreams] finishes and stops the native streams without blocking the
 * caller; it is passed in so the parser itself is plain Kotlin.
 */
internal class IrohAttachmentChannel(
    private val incoming: IrohFrameSource,
    private val tap: FrameTapSink,
    private val cancelStreams: () -> Unit
) : AttachmentChannel {
    private val done = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    override suspend fun next(): AttachmentFrame {
        if (done.get()) return AttachmentFrame.Error("closed", "attachment stream is closed")
        return try {
            val header = incoming.read(Framing.HEADER_BYTES)
            if (header.size != Framing.HEADER_BYTES) {
                done.set(true)
                return AttachmentFrame.Error("closed", "the daemon closed the attachment stream")
            }
            val parsed = Framing.parse(header)
                ?: return AttachmentFrame.Error("protocol", "invalid attachment frame header")
            val payload = incoming.read(parsed.length)
            if (payload.size != parsed.length) {
                done.set(true)
                return AttachmentFrame.Error("closed", "the attachment stream ended mid-frame")
            }
            if (tap.enabled) tap.frame(FrameDirection.IN, FrameStream.ATTACHMENT, parsed.kind, parsed.length)
            if (parsed.kind == Framing.KIND_BINARY) {
                AttachmentFrame.Data(payload)
            } else {
                when (val control = ControlEnvelope.decodeAttachmentControl(payload.toString(Charsets.UTF_8))) {
                    is AttachmentControl.End -> {
                        done.set(true)
                        AttachmentFrame.End(control.total, control.sha256)
                    }

                    is AttachmentControl.Error -> {
                        done.set(true)
                        AttachmentFrame.Error(control.code, control.message)
                    }

                    null -> AttachmentFrame.Error("protocol", "unexpected attachment control frame")
                }
            }
        } catch (t: Throwable) {
            done.set(true)
            AttachmentFrame.Error("closed", "the attachment stream failed: ${t.javaClass.simpleName}")
        }
    }

    override fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        cancelStreams()
    }
}
