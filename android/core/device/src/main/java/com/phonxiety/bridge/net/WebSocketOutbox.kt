package com.phonxiety.bridge.net

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import okio.ByteString

/** One accepted frame waiting for the address link's single socket writer. */
internal sealed interface SocketFrame {
    /** Bytes this frame adds to the backlog. */
    val bytes: Long

    class Text(val text: String) : SocketFrame {
        override val bytes: Long = utf8Length(text).toLong()
    }

    class Binary(val payload: ByteString) : SocketFrame {
        override val bytes: Long = payload.size.toLong()
    }
}

/**
 * The address link's outbound queue and its single writer.
 *
 * Both kinds share one FIFO because they share one socket: two queues would
 * reorder a command against the audio around it.  [offerText] and
 * [offerBinary] only `trySend`, so `sendText` never suspends under the
 * supervisor lock and reports acceptance into this queue instead of OkHttp's.
 *
 * [pendingBytes] counts what we accepted and OkHttp has not taken; the link
 * adds the socket's own `queueSize`, so the supervisor's audio backlog cap
 * keeps seeing every byte it saw before.  A frame the socket refuses reports
 * [onWriteFailed] once and closes the queue.
 */
internal class WebSocketOutbox(private val capacity: Int = CAPACITY) {
    internal companion object {
        /** Frames accepted before the socket takes them; a stalled socket refuses beyond this. */
        const val CAPACITY = 256
    }

    private val frames = Channel<SocketFrame>(capacity)
    private val pending = AtomicLong(0)
    private val failed = AtomicBoolean(false)

    val pendingBytes: Long get() = pending.get()

    fun offerText(message: String): Boolean = offer(SocketFrame.Text(message))

    fun offerBinary(payload: ByteString): Boolean = offer(SocketFrame.Binary(payload))

    private fun offer(frame: SocketFrame): Boolean {
        pending.addAndGet(frame.bytes)
        if (frames.trySend(frame).isFailure) {
            pending.addAndGet(-frame.bytes)
            return false
        }
        return true
    }

    /**
     * Sends every accepted frame in order until the queue closes or [send]
     * reports the socket is gone.  Exactly one coroutine calls this.
     */
    suspend fun drain(send: suspend (SocketFrame) -> Boolean, onWriteFailed: () -> Unit) {
        for (frame in frames) {
            val sent = try {
                send(frame)
            } catch (_: Throwable) {
                false
            }
            pending.addAndGet(-frame.bytes)
            if (!sent) {
                frames.close()
                if (failed.compareAndSet(false, true)) onWriteFailed()
                return
            }
        }
    }

    /** Stops accepting frames; the writer returns once the queue is empty. */
    fun close() {
        frames.close()
    }
}
