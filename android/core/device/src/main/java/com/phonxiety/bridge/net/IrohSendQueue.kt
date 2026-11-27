package com.phonxiety.bridge.net

import com.phonxiety.bridge.model.LinkDownReason
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
 * The tap is read once per accepted frame: with it off the queue holds the
 * caller's own array (see [queuedEntry]), so the hot path allocates nothing
 * beyond the channel node and no record is ever built.
 *
 * Frames are drained in FIFO order by exactly one coroutine.  A write that
 * throws reports [onWriteFailed] once and closes the queue: iroh has no write
 * deadline, so a wedged writer is the only signal the transport can give.  The
 * latch is defence in depth — the link's own one-time down report is the
 * publication the engine sees.
 */
internal class IrohSendQueue(
    private val capacity: Int,
    private val backlog: AtomicLong,
    private val tap: FrameTapSink = FrameTapSink.None
) {
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

    private val frames = Channel<Any>(capacity)
    private val failed = AtomicBoolean(false)

    /** Accepts [bytes] without suspending; false when the queue is full or closed. */
    fun offer(bytes: ByteArray, payload: Int = bytes.size): Boolean = offerResult(bytes, payload) == OfferResult.ACCEPTED

    /** Like [offer], but tells a full queue from a closed one: a full control queue must fail the link. */
    fun offerResult(bytes: ByteArray, payload: Int = bytes.size): OfferResult {
        backlog.addAndGet(bytes.size.toLong())
        val result = frames.trySend(queuedEntry(bytes, payload, tap))
        if (result.isSuccess) return OfferResult.ACCEPTED
        backlog.addAndGet(-bytes.size.toLong())
        return if (frames.isClosedForSend) OfferResult.CLOSED else OfferResult.FULL
    }

    /**
     * Writes every accepted frame in order until the queue closes or [write]
     * throws.  Exactly one coroutine calls this per queue.  A frame queued with
     * the tap on is recorded after its write returns, with the stream and kind
     * it used.
     */
    suspend fun drain(
        stream: FrameStream,
        kind: Int,
        write: suspend (ByteArray) -> Unit,
        onWriteFailed: () -> Unit
    ) {
        try {
            for (entry in frames) {
                val bytes = (entry as? QueuedFrame)?.bytes ?: entry as ByteArray
                try {
                    write(bytes)
                    if (entry is QueuedFrame && tap.enabled) {
                        tap.frame(FrameDirection.OUT, stream, kind, entry.payload)
                    }
                } finally {
                    backlog.addAndGet(-bytes.size.toLong())
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

/** Why an offer was refused, so a caller can tell a full queue from an ending link. */
internal enum class OfferResult { ACCEPTED, FULL, CLOSED }

/**
 * The control stream's send point: the bounded queue plus the one link failure
 * a full queue asks for.
 *
 * A full control queue means its writer is not draining and the link is
 * stalled.  The engine records its state before the send and ignores a refusal,
 * so a saturated queue can silently lose an "ended" state or a command reply,
 * and nothing re-syncs it while the link stays up.  The first full-queue
 * refusal therefore schedules [reportDown] with [BACKLOG_REASON], and the
 * engine's normal down → reconnect → hello path restores the truth.  The
 * report goes through [schedule] (the link's own I/O scope), never inline and
 * never under a lock, and only once per link; the refusal still returns false.
 *
 * A refusal from a closed queue is not a backlog: the link is already ending,
 * so nothing is scheduled.
 */
internal class IrohControlSender(
    private val queue: IrohSendQueue,
    private val schedule: (() -> Unit) -> Unit,
    private val reportDown: (LinkDownReason) -> Unit
) {
    internal companion object {
        /** The link-down reason a full control queue reports. */
        val BACKLOG_REASON = LinkDownReason.CONTROL_BACKLOG
    }

    private val scheduled = AtomicBoolean(false)

    fun offer(frame: ByteArray, payload: Int): Boolean = when (queue.offerResult(frame, payload)) {
        OfferResult.ACCEPTED -> true

        OfferResult.FULL -> {
            if (scheduled.compareAndSet(false, true)) schedule { reportDown(BACKLOG_REASON) }
            false
        }

        OfferResult.CLOSED -> false
    }
}

/**
 * What one queue entry holds: the caller's array itself while the tap is off,
 * so no per-frame wrapper is allocated, and a [QueuedFrame] with the payload
 * size only when a record may be written.
 */
internal fun queuedEntry(bytes: ByteArray, payload: Int, tap: FrameTapSink): Any = if (tap.enabled) QueuedFrame(bytes, payload) else bytes

/** One accepted frame: the bytes to write and the payload size the tap records. */
internal class QueuedFrame(val bytes: ByteArray, val payload: Int)
