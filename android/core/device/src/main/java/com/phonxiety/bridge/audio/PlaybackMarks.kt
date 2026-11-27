package com.phonxiety.bridge.audio

/**
 * One playback mark: a `PXMK` frame the daemon sends on the audio stream once
 * it has finished sending a batch, so the phone can report when the batch has
 * actually reached the line.
 *
 * The frame is the audio header without a payload, exactly [SIZE] bytes:
 *
 * ```
 * "PXMK" | epoch:u32 big-endian | mark id:u64 big-endian
 * ```
 *
 * The mark id uses the same big-endian index field (bytes 8..15) as a PX01 or
 * PX02 audio header.  Pure Kotlin, so parsing is unit-testable without Android.
 */
internal object MarkFrame {
    const val SIZE = 16

    /** A decoded mark frame. */
    data class Mark(val epoch: Long, val id: Long)

    /**
     * Parses one whole mark frame.  Returns null unless [bytes] is exactly
     * [SIZE] bytes and starts with the `PXMK` magic.
     */
    fun parse(bytes: ByteArray): Mark? {
        if (!isMark(bytes)) return null
        val epoch = ((bytes[4].toLong() and 0xFF) shl 24) or
            ((bytes[5].toLong() and 0xFF) shl 16) or
            ((bytes[6].toLong() and 0xFF) shl 8) or
            (bytes[7].toLong() and 0xFF)
        var id = 0L
        var index = 8
        while (index < SIZE) {
            id = (id shl 8) or (bytes[index].toLong() and 0xFF)
            index++
        }
        return Mark(epoch, id)
    }

    /** Whether [bytes] begins with the mark magic and has the mark length. */
    fun isMark(bytes: ByteArray): Boolean = bytes.size == SIZE &&
        bytes[0] == 0x50.toByte() && // P
        bytes[1] == 0x58.toByte() && // X
        bytes[2] == 0x4D.toByte() && // M
        bytes[3] == 0x4B.toByte() // K
}

/**
 * Mark bookkeeping for one call epoch, independent of audio and Android.
 *
 * The latest mark of the epoch waits as *pending* until the caller reports it
 * has played everything received before it ([observe] with a zero queue); the
 * latest drained mark stays readable for reporting.  A mark for another epoch
 * is ignored, a newer mark replaces an older pending one, and ids never go
 * backwards: a mark at or below the drained one adds nothing.
 */
internal class PlaybackMarks(private val epoch: Long) {
    private val lock = Any()
    private var pending: Long? = null
    private var drained: Long? = null

    /**
     * Records mark [id] received for [epoch].  Returns false when the mark
     * belongs to another epoch or is older than the last drained mark.
     */
    fun offer(epoch: Long, id: Long): Boolean {
        if (epoch != this.epoch) return false
        synchronized(lock) {
            val last = drained
            if (last != null && id <= last) return false
            val current = pending
            if (current == null || id > current) pending = id
            return true
        }
    }

    /**
     * Drains the pending mark once nothing received before it is left.
     * [queuedSamples] reads the audio path's queue and runs under the same lock
     * as [offer]: a mark offered after the snapshot waits for the next call, so
     * audio that arrived just before it can never be counted as played.
     * Returns the mark drained by this call.
     */
    fun observe(queuedSamples: () -> Long): Long? {
        synchronized(lock) {
            if (queuedSamples() > 0L) return null
            val id = pending ?: return null
            pending = null
            val last = drained
            if (last != null && id <= last) return null
            drained = id
            return id
        }
    }

    /** Latest drained mark of this epoch, or null until one has drained. */
    fun drainedMark(): Long? = synchronized(lock) { drained }
}
