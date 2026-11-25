package com.phonxiety.bridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AudioStartException(val code: String) : Exception(code)

/**
 * One call's audio path.
 *
 * RX: AudioRecord(VOICE_DOWNLINK), 48 kHz mono PCM16, framed as 20 ms PX01
 * PCM packets, or as one PX02 Opus packet per 20 ms when [opusBitrate] is
 * set.  This is the framework's digital downlink source, not the microphone.
 *
 * TX: AudioTrack, 48 kHz stereo PCM16, USAGE_MEDIA, explicitly routed to the
 * AudioDeviceInfo.TYPE_TELEPHONY output.  Mono server PCM is duplicated into
 * both channels.  Nothing is ever recorded from the microphone and nothing is
 * routed to the speaker as a substitute.
 *
 * The local handset microphone is muted for the lifetime of this session with
 * the framework AudioManager flag, saving the pre-mute state first and
 * restoring it on every teardown path.  After process death the engine must
 * call restoreAbandonedMute, because no teardown code survives a kill.  A
 * framework capture mute is not evidence that the modem uplink TX is
 * suppressed on a gs101 handset; that requires a real call measurement.  No
 * tinyalsa/AoC control is written here.
 */
class PhoneAudio(
    context: Context,
    val callId: String,
    val epoch: Long,
    /** Opus bitrate in bit/s for both directions, or null for raw PCM. */
    private val opusBitrate: Int?,
    private val sendFrame: (ByteArray, Int) -> Boolean,
    private val onFatal: (String) -> Unit,
) {
    companion object {
        private const val TAG = "PhonxietyAudio"
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 960 // 20 ms
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val PRE_ROLL_FRAMES = FRAME_SAMPLES
        private const val PCM_VERSION: Byte = 0x31 // PX01
        private const val OPUS_VERSION: Byte = 0x32 // PX02
        private const val MAX_OPUS_PACKET = 1275 // RFC 6716 frame limit

        /**
         * Restores microphone state left behind by a dead bridge process.
         *
         * Pass [callId] for the owned call being cleaned up; without it the
         * snapshot is only consumed when Telecom reports no call at all.
         * Never throws.
         */
        fun restoreAbandonedMute(context: Context, callId: String? = null): Boolean =
            HandsetMute.restore(context.applicationContext, callId)

        private const val RING_CAPACITY_SAMPLES = 24_000 // 500 ms
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val rxRunning = AtomicBoolean(false)
    private val txRunning = AtomicBoolean(false)
    private val fatalReported = AtomicBoolean(false)
    private val closeOnce = AtomicBoolean(false)
    private val txLock = ReentrantLock()
    private val rxLock = ReentrantLock()
    private val ring = SampleRing(RING_CAPACITY_SAMPLES)
    private val marks = PlaybackMarks(epoch)

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var rxThread: Thread? = null
    private var txThread: Thread? = null
    private var encoder: OpusCodec.Encoder? = null
    private var decoder: OpusCodec.Decoder? = null
    private val decodeLock = Any()

    /** Wire format of this session, as reported to the daemon. */
    val codec: String get() = if (opusBitrate != null) "opus" else "pcm"

    private var sequence: Long = 0
    private var writtenFrames: Long = 0
    private var playbackBase: Long = 0
    private var captureFrames: Long = 0
    private var unsentFrames: Long = 0
    private val hostFrames = AtomicLong(0)
    private val overflowSamples = AtomicLong(0)

    @Volatile private var closed = false

    @Synchronized
    fun start() {
        if (closed) throw AudioStartException("stopped")
        val manager = audioManager ?: throw AudioStartException("audio_service_missing")
        if (!Permissions.granted(appContext, Permissions.CAPTURE_AUDIO_OUTPUT)) {
            throw AudioStartException("capture_permission_denied")
        }
        if (!Permissions.granted(appContext, Permissions.MODIFY_PHONE_STATE)) {
            throw AudioStartException("phone_state_permission_denied")
        }
        if (!Permissions.granted(appContext, Permissions.RECORD_AUDIO)) {
            throw AudioStartException("record_permission_denied")
        }

        val minRecord = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minRecord <= 0) throw AudioStartException("capture_buffer_unavailable")

        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minRecord, FRAME_BYTES * 4))
                .build()
        } catch (_: Exception) {
            throw AudioStartException("capture_open_failed")
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            throw AudioStartException("capture_open_failed")
        }
        if (closed) {
            runCatching { recorder.release() }
            throw AudioStartException("stopped")
        }

        val telephony = try {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        } catch (_: Exception) {
            null
        }
        if (telephony == null) {
            runCatching { recorder.release() }
            throw AudioStartException("telephony_output_missing")
        }

        val minTrack = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val player = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(if (minTrack > 0) minTrack else 0, 19_200))
                .build()
        } catch (_: Exception) {
            runCatching { recorder.release() }
            throw AudioStartException("playback_open_failed")
        }
        if (player.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { player.release() }
            runCatching { recorder.release() }
            throw AudioStartException("playback_open_failed")
        }

        val routeAccepted = try {
            player.setPreferredDevice(telephony)
        } catch (_: Exception) {
            false
        }
        if (!routeAccepted) {
            runCatching { player.release() }
            runCatching { recorder.release() }
            throw AudioStartException("telephony_route_rejected")
        }

        // The pre-mute state is committed before the flag is changed, so a
        // kill from here on is recoverable.  Any later failure in this method
        // reaches close(), which restores it.
        try {
            HandsetMute.acquire(appContext, callId)
        } catch (e: AudioStartException) {
            runCatching { player.release() }
            runCatching { recorder.release() }
            HandsetMute.restore(appContext, callId)
            throw e
        }

        record = recorder
        track = player

        try {
            recorder.startRecording()
        } catch (_: Exception) {
            close()
            throw AudioStartException("capture_start_failed")
        }
        try {
            player.play()
        } catch (_: Exception) {
            close()
            throw AudioStartException("playback_start_failed")
        }

        // Keep the agent's real speech off a speaker route.  Write 20 ms of
        // silence, wait (bounded) until the track reports the telephony sink,
        // and only then let the TX thread accept host PCM.
        try {
            val silence = ShortArray(FRAME_SAMPLES * 2)
            writeAll(player, silence, 0, silence.size)
            writtenFrames += PRE_ROLL_FRAMES
        } catch (_: Exception) {
            close()
            throw AudioStartException("playback_write_failed")
        }

        try {
            val deadline = SystemClock.elapsedRealtime() + 250
            var routed = try { player.routedDevice } catch (_: Exception) { null }
            while (routed?.type != AudioDeviceInfo.TYPE_TELEPHONY &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                Thread.sleep(10)
                routed = try { player.routedDevice } catch (_: Exception) { null }
            }
            if (routed?.type != AudioDeviceInfo.TYPE_TELEPHONY) {
                close()
                throw AudioStartException("telephony_route_not_selected")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            close()
            throw AudioStartException("stopped")
        }

        if (closed) {
            close()
            throw AudioStartException("stopped")
        }

        if (opusBitrate != null) {
            try {
                encoder = OpusCodec.Encoder(opusBitrate)
                decoder = OpusCodec.Decoder()
            } catch (_: Exception) {
                close()
                throw AudioStartException("opus_codec_unavailable")
            }
        }

        rxRunning.set(true)
        txRunning.set(true)
        rxThread = Thread({ rxLoop(recorder) }, "phonxiety-rx").also { it.start() }
        txThread = Thread({ txLoop(player) }, "phonxiety-tx").also { it.start() }
    }

    /** Accepts one host binary frame or playback mark.  Header/epoch validation happens here. */
    fun offerFrame(bytes: ByteString) {
        if (closed || !txRunning.get() || bytes.size < 16) return
        if (bytes.size == MarkFrame.SIZE) {
            // A mark drains once everything received before it has played; the
            // epoch filter and ordering live in PlaybackMarks.
            val mark = MarkFrame.parse(bytes.toByteArray())
            if (mark != null) {
                marks.offer(mark.epoch, mark.id)
                return
            }
        }
        val expected = if (opusBitrate != null) OPUS_VERSION else PCM_VERSION
        if (bytes[0] != 0x50.toByte() || bytes[1] != 0x58.toByte() ||
            bytes[2] != 0x30.toByte() || bytes[3] != expected
        ) {
            return
        }
        val frameEpoch = ((bytes[4].toLong() and 0xFF) shl 24) or
            ((bytes[5].toLong() and 0xFF) shl 16) or
            ((bytes[6].toLong() and 0xFF) shl 8) or
            (bytes[7].toLong() and 0xFF)
        if (frameEpoch != epoch) return
        val payload = bytes.size - 16
        if (payload <= 0) return
        if (opusBitrate == null) {
            if (payload % 2 != 0) return
            hostFrames.incrementAndGet()
            val dropped = ring.offer(bytes, 16, payload / 2)
            if (dropped > 0) overflowSamples.addAndGet(dropped.toLong())
            return
        }
        val pcm = synchronized(decodeLock) {
            val codec = decoder ?: return
            try {
                codec.decode(bytes.toByteArray(), 16, payload)
            } catch (_: Exception) {
                reportFatal("opus_decode_failed")
                return
            }
        }
        if (pcm.isEmpty() || pcm.size % 2 != 0) return
        hostFrames.incrementAndGet()
        val dropped = ring.offer(pcm, 0, pcm.size / 2)
        if (dropped > 0) overflowSamples.addAndGet(dropped.toLong())
    }

    /**
     * Drops queued playback and resumes only while the engine still has this
     * audio attached.  Serialized against the blocking AudioTrack write.
     */
    fun flush(): Boolean = txLock.withLock {
        val player = track
        if (player == null || closed || !txRunning.get()) return@withLock false
        try {
            player.pause()
            val before = headFrames(player)
            player.flush()
            val after = headFrames(player)
            if (after < before) playbackBase += before
            writtenFrames = playbackBase + after
            ring.clear()
            player.play()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** played_samples / queued_samples for the current epoch. */
    fun stats(): Pair<Long, Long> = txLock.withLock {
        val player = track
        if (player == null) return@withLock Pair(0L, 0L)
        val head = headFrames(player)
        val played = playbackBase + head
        var queued = 0L
        marks.observe {
            queued = (writtenFrames - played).coerceAtLeast(0L) + ring.size()
            queued
        }
        played to queued
    }

    /** Latest playback mark drained for this epoch, or null before the first. */
    fun drainedMark(): Long? = marks.drainedMark()

    @Synchronized
    fun close() {
        if (!closeOnce.compareAndSet(false, true)) return
        closed = true
        rxRunning.set(false)
        txRunning.set(false)

        val recorder = record
        record = null
        rxLock.withLock {
            if (recorder != null) runCatching { recorder.stop() }
        }
        val rx = rxThread
        rxThread = null
        if (rx != null && rx !== Thread.currentThread()) runCatching { rx.join(1000) }
        rxLock.withLock {
            if (recorder != null) runCatching { recorder.release() }
        }
        // The RX thread is joined, so the encoder is idle; the decoder is
        // released under the lock offerFrame holds while it decodes.
        encoder?.let { runCatching { it.close() } }
        encoder = null
        synchronized(decodeLock) {
            decoder?.let { runCatching { it.close() } }
            decoder = null
        }

        val player = track
        txLock.withLock {
            track = null
            if (player != null) {
                runCatching { player.pause() }
                runCatching { player.flush() }
                runCatching { player.release() }
            }
        }

        val tx = txThread
        txThread = null
        if (tx != null && tx !== Thread.currentThread()) runCatching { tx.join(1000) }

        // One line per call: which direction carried how much.  Read after
        // both threads are joined.  "not sent" counts frames the engine
        // refused (link down or backlog); the link can still discard an
        // accepted frame while it closes, so it is a lower bound.
        Log.i(
            TAG,
            "audio closed: $captureFrames frames captured (${sequence / 48} ms), $unsentFrames not sent, " +
                "${hostFrames.get()} frames from host, ${writtenFrames / 48} ms written to the line, " +
                "${overflowSamples.get() / 48} ms dropped on overflow",
        )

        // Last step: the capture and playback paths are gone, so the saved
        // microphone state can be applied without our own capture racing it.
        // restore() never throws, including during engine teardown.
        HandsetMute.restore(appContext, callId)
    }

    private fun rxLoop(recorder: AudioRecord) {
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        }
        val frame = ByteArray(16 + FRAME_BYTES)
        val opus = encoder
        // Opus encodes whole 20 ms frames; PCM goes out as soon as it is read.
        val wanted = if (opus != null) FRAME_BYTES else 0
        val packetFrame = if (opus != null) ByteArray(16 + MAX_OPUS_PACKET) else frame
        var filled = 0
        while (rxRunning.get() && !closed) {
            val read = try {
                rxLock.withLock {
                    if (closed || !rxRunning.get()) return
                    recorder.read(frame, 16 + filled, FRAME_BYTES - filled, AudioRecord.READ_NON_BLOCKING)
                }
            } catch (_: Exception) {
                if (rxRunning.get() && !closed) reportFatal("capture_read_failed")
                return
            }
            if (read < 0) {
                if (rxRunning.get() && !closed) reportFatal("capture_read_failed")
                return
            }
            if (read == 0) {
                try {
                    Thread.sleep(1)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                continue
            }
            if (read % 2 != 0) {
                reportFatal("capture_frame_alignment")
                return
            }
            if (opus == null) {
                writeHeader(frame, PCM_VERSION, epoch, sequence)
                sequence += read / 2
                captureFrames++
                if (!sendFrame(frame, 16 + read)) unsentFrames++
                continue
            }
            filled += read
            if (filled < wanted) continue
            filled = 0
            val packets = try {
                opus.encode(frame, 16)
            } catch (_: Exception) {
                if (rxRunning.get() && !closed) reportFatal("opus_encode_failed")
                return
            }
            for (packet in packets) {
                if (packet.size > MAX_OPUS_PACKET) continue
                writeHeader(packetFrame, OPUS_VERSION, epoch, sequence)
                System.arraycopy(packet, 0, packetFrame, 16, packet.size)
                captureFrames++
                if (!sendFrame(packetFrame, 16 + packet.size)) unsentFrames++
            }
            sequence += FRAME_SAMPLES
        }
    }

    private fun txLoop(player: AudioTrack) {
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        }
        val mono = ShortArray(FRAME_SAMPLES)
        val stereo = ShortArray(FRAME_SAMPLES * 2)
        while (txRunning.get() && !closed) {
            var samples = 0
            var failure: String? = null
            txLock.withLock {
                if (!txRunning.get() || closed) return@withLock
                samples = ring.drain(mono, mono.size)
                if (samples > 0) {
                    var i = 0
                    while (i < samples) {
                        val value = mono[i]
                        stereo[i * 2] = value
                        stereo[i * 2 + 1] = value
                        i++
                    }
                    try {
                        writeAll(player, stereo, 0, samples * 2)
                        writtenFrames += samples.toLong()
                    } catch (_: Exception) {
                        failure = "playback_write_failed"
                    }
                }
            }
            val failed = failure
            if (failed != null) {
                if (!closed && txRunning.get()) reportFatal(failed)
                return
            }
            if (samples == 0) {
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private fun writeAll(player: AudioTrack, data: ShortArray, offset: Int, count: Int) {
        var written = 0
        while (written < count) {
            if (closed) throw IllegalStateException("closed")
            val n = player.write(data, offset + written, count - written, AudioTrack.WRITE_BLOCKING)
            if (n < 0) throw IllegalStateException("write failed")
            if (n == 0) {
                try {
                    Thread.sleep(1)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("interrupted")
                }
            } else {
                written += n
            }
        }
    }

    private fun headFrames(player: AudioTrack): Long =
        try {
            player.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        } catch (_: Exception) {
            0L
        }

    private fun writeHeader(frame: ByteArray, version: Byte, epoch: Long, seq: Long) {
        frame[0] = 0x50 // P
        frame[1] = 0x58 // X
        frame[2] = 0x30 // 0
        frame[3] = version // 1 PCM, 2 Opus
        frame[4] = ((epoch ushr 24) and 0xFF).toByte()
        frame[5] = ((epoch ushr 16) and 0xFF).toByte()
        frame[6] = ((epoch ushr 8) and 0xFF).toByte()
        frame[7] = (epoch and 0xFF).toByte()
        var shift = 56
        var i = 8
        while (i < 16) {
            frame[i] = ((seq ushr shift) and 0xFF).toByte()
            shift -= 8
            i++
        }
    }

    private fun reportFatal(code: String) {
        if (closed || !fatalReported.compareAndSet(false, true)) return
        Log.w(TAG, "audio failure: $code")
        onFatal(code)
    }

    private class SampleRing(private val capacity: Int) {
        private val data = ShortArray(capacity)
        private var head = 0
        private var count = 0

        @Synchronized
        fun offer(source: ByteString, offset: Int, samples: Int): Int {
            var dropped = 0
            var i = 0
            while (i < samples) {
                val position = offset + i * 2
                if (push(((source[position].toInt() and 0xFF) or (source[position + 1].toInt() shl 8)).toShort())) dropped++
                i++
            }
            return dropped
        }

        @Synchronized
        fun offer(source: ByteArray, offset: Int, samples: Int): Int {
            var dropped = 0
            var i = 0
            while (i < samples) {
                val position = offset + i * 2
                if (push(((source[position].toInt() and 0xFF) or (source[position + 1].toInt() shl 8)).toShort())) dropped++
                i++
            }
            return dropped
        }

        /** Appends one sample; true when the oldest one was dropped to make room. */
        private fun push(sample: Short): Boolean {
            var dropped = false
            if (count == capacity) {
                head = (head + 1) % capacity
                count--
                dropped = true
            }
            data[(head + count) % capacity] = sample
            count++
            return dropped
        }

        @Synchronized
        fun drain(destination: ShortArray, max: Int): Int {
            val n = minOf(max, count)
            var i = 0
            while (i < n) {
                destination[i] = data[(head + i) % capacity]
                i++
            }
            head = (head + n) % capacity
            count -= n
            return n
        }

        @Synchronized
        fun clear() {
            head = 0
            count = 0
        }

        @Synchronized
        fun size(): Int = count
    }
}
