package com.phonxiety.bridge.net

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel

/**
 * One send stream's outgoing frames and its single writer.
 *
 * The control queue is bounded: a full queue makes [offer] return false, so the
 * caller refuses the frame instead of suspending under a lock.  The audio queue
 * keeps the unlimited channel and per-frame accounting it always had; the only
 * cap on audio stays [LinkSupervisor.MAX_AUDIO_BACKLOG_BYTES], checked by the
 * supervisor before it calls the transport.
 *
 * [backlog] is shared by both queues and counts every accepted, not yet written
 * byte: it grows at accept and shrinks in the writer's `finally`, so a refused
 * frame never adds and a failed write still subtracts.
 *
 * Frames are drained in FIFO order by exactly one coroutine.  A write that
 * throws reports [onWriteFailed] once and closes the queue: iroh has no write
 * deadline, so a wedged writer is the only signal the transport can give.  The
 * latch is defence in depth — the link's own one-time down report is the
 * publication the engine sees.
 */
internal class IrohSendQueue(private val capacity: Int, private val backlog: AtomicLong) {
    internal companion object {
        /**
         * Control frames accepted but not yet written.  The request table caps
         * in-flight API requests at 64, so 128 leaves room for call state,
         * playback, levels and route updates; a full queue refuses (the caller
         * reports `Refused`) instead of suspending under the supervisor lock.
         */
        const val CONTROL_CAPACITY = 128

        /**
         * Audio has no queue bound of its own.  The only audio cap stays
         * [LinkSupervisor.MAX_AUDIO_BACKLOG_BYTES], checked before the frame is
         * offered; making this bounded would drop live capture instead.
         */
        const val AUDIO_CAPACITY = Channel.UNLIMITED
    }

    private val frames = Channel<Frame>(capacity)
    private val failed = AtomicBoolean(false)

    /** One accepted frame: the bytes to write and the payload size the tap records. */
    private class Frame(val bytes: ByteArray, val payload: Int)

    /** Accepts [bytes] without suspending; false when the queue is full or closed. */
    fun offer(bytes: ByteArray, payload: Int = bytes.size): Boolean {
        backlog.addAndGet(bytes.size.toLong())
        if (frames.trySend(Frame(bytes, payload)).isFailure) {
            backlog.addAndGet(-bytes.size.toLong())
            return false
        }
        return true
    }

    /**
     * Writes every accepted frame in order until the queue closes or [write]
     * throws.  Exactly one coroutine calls this per queue.  [tap] records each
     * frame after its write returns, with the stream and kind it used.
     */
    suspend fun drain(
        stream: FrameStream,
        kind: Int,
        tap: FrameTapSink,
        write: suspend (ByteArray) -> Unit,
        onWriteFailed: () -> Unit
    ) {
        try {
            for (frame in frames) {
                try {
                    write(frame.bytes)
                    if (tap.enabled) tap.frame(FrameDirection.OUT, stream, kind, frame.payload)
                } finally {
                    backlog.addAndGet(-frame.bytes.size.toLong())
                }
            }
        } catch (t: Throwable) {
            frames.close()
            if (failed.compareAndSet(false, true)) onWriteFailed()
        }
    }

    /** Stops accepting frames; the writer returns once the queue is empty. */
    fun close() {
        frames.close()
    }
}
