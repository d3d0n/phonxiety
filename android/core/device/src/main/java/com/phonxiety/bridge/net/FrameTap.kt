package com.phonxiety.bridge.net

import android.content.Context
import com.phonxiety.bridge.AppStorage
import com.phonxiety.bridge.PhoneSettingsStore
import com.phonxiety.bridge.bridgeScope
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Which way a frame travelled. */
internal enum class FrameDirection { IN, OUT }

/** Which stream a frame used. */
internal enum class FrameStream { CONTROL, AUDIO, ATTACHMENT, SOCKET }

/**
 * One frame record.  It holds numbers only: the tap is at the transport
 * boundary where tokens and transcripts travel, so the payload is never read,
 * copied or written.
 *
 * [linkId] names the attempt and [seq] counts frames on that attempt, so the
 * pair correlates the two directions of one link without touching the protocol.
 */
internal data class FrameRecord(
    val atMs: Long,
    val linkId: Long,
    val seq: Long,
    val direction: FrameDirection,
    val stream: FrameStream,
    val kind: Int,
    val bytes: Int
) {
    /** `atMs,linkId,seq,direction,stream,kind,bytes`, one line per frame. */
    fun encode(): String = "$atMs,$linkId,$seq,$direction,$stream,$kind,$bytes"
}

/** Where the transport reports one frame.  [enabled] false means the caller skips the record. */
internal interface FrameTapSink {
    val enabled: Boolean

    fun frame(direction: FrameDirection, stream: FrameStream, kind: Int, bytes: Int)

    /** The tap is off: nothing is measured and nothing is written. */
    object None : FrameTapSink {
        override val enabled: Boolean = false

        override fun frame(direction: FrameDirection, stream: FrameStream, kind: Int, bytes: Int) = Unit
    }
}

/**
 * One link's view of the process-wide dump: its own [linkId] and frame
 * sequence.  [enabled] reads the live setting, so a toggle reaches links that
 * are already running.
 */
internal class FrameTapSession(private val dump: FrameTapDump, private val linkId: Long) : FrameTapSink {
    private val seq = AtomicLong(0)

    override val enabled: Boolean get() = dump.enabled

    override fun frame(direction: FrameDirection, stream: FrameStream, kind: Int, bytes: Int) {
        if (!dump.enabled) return
        dump.record(FrameRecord(dump.clockMs(), linkId, seq.incrementAndGet(), direction, stream, kind, bytes))
    }
}

/**
 * The process-wide frame tap: one dump and one setting watcher, and a fresh
 * [FrameTapSession] per link attempt so each attempt gets its own record
 * sequence.
 *
 * The toggle applies to links that are already running, not only to the next
 * dial: every queued record is tagged with the enable generation it was queued
 * in, and the appender writes only records from the live generation.  Turning
 * the tap off therefore discards what is still queued, and turning it back on
 * never writes those older records.
 *
 * Off unless the phone's `frame_tap` preference is on.  The dump lives in
 * device-protected storage beside the iroh key, rotates at
 * [FrameTapDump.MAX_BYTES] over two files and drops (counting the drops) when
 * its queue is full, so the transport never blocks or grows on it.
 */
internal object FrameTap {
    private val lock = Any()
    private val links = AtomicLong(0)

    @Volatile private var dump: FrameTapDump? = null

    /** One attempt's tap: off while the preference is off, dumping to the shared files otherwise. */
    fun of(context: Context): FrameTapSink = FrameTapSession(shared(context), links.incrementAndGet())

    fun enabled(): Boolean = dump?.enabled ?: false

    private fun shared(context: Context): FrameTapDump {
        synchronized(lock) {
            dump?.let { return it }
            val app = context.applicationContext
            val scope = bridgeScope("phonxiety-frame-tap", Dispatchers.IO)
            val created = FrameTapDump(
                dir = AppStorage.deviceProtected(app).filesDir,
                clock = { System.currentTimeMillis() },
                scope = scope
            )
            scope.launch {
                PhoneSettingsStore.of(app).settings.collect { settings ->
                    created.noteSetting(settings.frameTap)
                }
            }
            dump = created
            return created
        }
    }
}

/**
 * The bounded, rotating record dump behind [FrameTap].
 *
 * Records arrive from transport threads through one bounded channel and are
 * written by one appender coroutine; a full channel drops the record and counts
 * it, so a slow disk never back-pressures a call.  Each record carries the
 * enable generation it was queued in: the appender writes only records from the
 * live generation, so a toggle discards whatever was queued across it.
 *
 * Two files of [MAX_BYTES], `frame-tap.log` and `frame-tap.1.log`, keep the
 * total bounded: the current file becomes the previous one and the oldest is
 * replaced.  If that rotation fails, the size bound wins: the record is
 * dropped instead of letting the current file grow past [MAX_BYTES].
 */
internal class FrameTapDump(
    private val dir: File,
    private val clock: () -> Long,
    scope: CoroutineScope,
    private val capacity: Int = 1024,
    private val maxBytes: Int = MAX_BYTES,
    /** Test seam: runs under no lock after a record's enable snapshot and before its encode. */
    private val beforeEncode: (() -> Unit)? = null,
    /** Test seam: runs under [admission] after a record's generation check and before its write. */
    private val beforeWrite: (() -> Unit)? = null
) {
    internal companion object {
        /** Bytes kept per file; two files in total. */
        const val MAX_BYTES = 64 * 1024

        const val CURRENT = "frame-tap.log"
        const val PREVIOUS = "frame-tap.1.log"
    }

    /** The toggle as one immutable value, so a reader can never see `enabled` and its generation split. */
    private class Snapshot(val enabled: Boolean, val generation: Long)

    private val records = Channel<Queued>(capacity)
    private val dropped = AtomicLong(0)

    /** The live enable state and its generation, read and replaced as one unit. */
    private val state = AtomicReference(Snapshot(enabled = false, generation = 0))

    /**
     * Serializes the appender's admission of a record (its generation check and
     * its write, as one critical section) with [noteSetting].  A setting change
     * therefore either lands before a record's check, dropping that record, or
     * waits for the write in flight; it never lands between the check and the
     * write.
     */
    private val admission = Any()

    /** The one appender; completes once [close] is called and the queue is drained. */
    internal val job: Job = scope.launch { append() }

    val enabled: Boolean get() = state.get().enabled

    fun clockMs(): Long = clock()

    /** One queued record and the generation it was accepted in. */
    private class Queued(val line: String, val generation: Long)

    /**
     * The setting changed: take the new state under [admission], so the
     * generation advances in the same critical section the appender checks it
     * in.  [FrameTap]'s watcher calls this for each emission, so a disable
     * discards what is queued and a re-enable never resurrects it.
     */
    fun noteSetting(on: Boolean) {
        synchronized(admission) {
            val current = state.get()
            if (current.enabled != on) state.set(Snapshot(on, current.generation + 1))
        }
    }

    fun record(record: FrameRecord) {
        // The snapshot is read before the record is built: the generation the
        // record is tagged with always matches the enable state it was taken
        // under, never a half-applied toggle.
        val snapshot = state.get()
        if (!snapshot.enabled) return
        beforeEncode?.invoke()
        val queued = Queued(record.encode(), snapshot.generation)
        if (records.trySend(queued).isFailure) dropped.incrementAndGet()
    }

    /** Stops accepting records; the appender writes what it holds and returns. */
    fun close() {
        records.close()
    }

    private suspend fun append() {
        val current = File(dir, CURRENT)
        val previous = File(dir, PREVIOUS)
        var size = current.length()
        for (queued in records) {
            // The generation check and the write share one lock: a toggle
            // either runs before this record is admitted, dropping it, or
            // waits here for the write to finish.  Nothing from a disabled
            // window is written, and re-enabling does not resurrect what an
            // earlier enable queued.
            synchronized(admission) {
                val snapshot = state.get()
                if (!snapshot.enabled || queued.generation != snapshot.generation) return@synchronized
                beforeWrite?.invoke()
                val lost = dropped.getAndSet(0)
                val text = (if (lost > 0) "dropped=$lost\n" else "") + queued.line + "\n"
                val bytes = text.toByteArray(Charsets.UTF_8)
                if (size + bytes.size > maxBytes) {
                    runCatching { previous.delete() }
                    if (!runCatching { current.renameTo(previous) }.getOrDefault(false)) {
                        // Without a fresh file this record would pass the bound:
                        // the bound wins, and `size` keeps the real file length.
                        dropped.incrementAndGet()
                        return@synchronized
                    }
                    size = 0
                }
                if (runCatching {
                        FileOutputStream(current, true).use { it.write(bytes) }
                    }.isSuccess
                ) {
                    size += bytes.size
                }
            }
        }
    }
}

/** Exact UTF-8 size of [text] without encoding a copy, for the dump's byte count. */
internal fun utf8Length(text: String): Int {
    var bytes = 0
    var index = 0
    while (index < text.length) {
        val code = text[index].code
        val pair = code in 0xD800..0xDBFF && index + 1 < text.length && text[index + 1].code in 0xDC00..0xDFFF
        bytes += when {
            code < 0x80 -> 1
            code < 0x800 -> 2
            pair -> 4
            else -> 3
        }
        index += if (pair) 2 else 1
    }
    return bytes
}
