package com.phonxiety.bridge

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 48 kHz mono Opus through the platform codecs, one 20 ms packet per frame.
 *
 * Both sides run MediaCodec synchronously: the call audio threads feed one
 * frame and collect what it produced.  Not thread-safe; each instance belongs
 * to one audio thread.
 */
object OpusCodec {
    const val SAMPLE_RATE = 48_000
    const val FRAME_SAMPLES = 960
    private const val TIMEOUT_US = 20_000L

    class Encoder(bitrate: Int) : AutoCloseable {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        private val info = MediaCodec.BufferInfo()
        private var pts = 0L

        init {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2)
            }
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            } catch (e: Exception) {
                codec.release()
                throw e
            }
        }

        /** Encodes one 20 ms PCM16 frame; returns the packets that came out (normally one). */
        fun encode(pcm: ByteArray, offset: Int): List<ByteArray> {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) throw IllegalStateException("opus encoder input stalled")
            val input = codec.getInputBuffer(index) ?: throw IllegalStateException("opus encoder input missing")
            input.clear()
            input.put(pcm, offset, FRAME_SAMPLES * 2)
            codec.queueInputBuffer(index, 0, FRAME_SAMPLES * 2, pts, 0)
            pts += 20_000L
            return drain(codec, info, wait = true)
        }

        override fun close() {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    class Decoder : AutoCloseable {
        private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        private val info = MediaCodec.BufferInfo()
        private var pts = 0L

        init {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
                setByteBuffer("csd-0", opusHead())
                // No pre-skip: every packet yields its full 20 ms, so played
                // samples match what the daemon submitted.
                setByteBuffer("csd-1", nanos(0L))
                setByteBuffer("csd-2", nanos(80_000_000L))
            }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                codec.release()
                throw e
            }
        }

        /** Decodes one packet to little-endian PCM16 bytes. */
        fun decode(packet: ByteArray, offset: Int, length: Int): ByteArray {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) throw IllegalStateException("opus decoder input stalled")
            val input = codec.getInputBuffer(index) ?: throw IllegalStateException("opus decoder input missing")
            input.clear()
            input.put(packet, offset, length)
            codec.queueInputBuffer(index, 0, length, pts, 0)
            pts += 20_000L
            val chunks = drain(codec, info, wait = true)
            if (chunks.size == 1) return chunks[0]
            val out = ByteArray(chunks.sumOf { it.size })
            var at = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, out, at, chunk.size)
                at += chunk.size
            }
            return out
        }

        override fun close() {
            runCatching { codec.stop() }
            codec.release()
        }

        private fun opusHead(): ByteBuffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII))
            put(1) // version
            put(1) // channels
            putShort(0) // pre-skip
            putInt(SAMPLE_RATE)
            putShort(0) // output gain
            put(0) // mapping family
            flip()
        }

        private fun nanos(value: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).flip() as ByteBuffer
    }

    /** Output buffers ready now; waits for the first one when [wait]. */
    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, wait: Boolean): List<ByteArray> {
        val out = ArrayList<ByteArray>(1)
        var timeout = if (wait) TIMEOUT_US else 0L
        while (true) {
            val index = codec.dequeueOutputBuffer(info, timeout)
            when {
                index >= 0 -> {
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            out.add(ByteArray(info.size).also { buffer.get(it) })
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    // Once real output arrived, collect only what is already there.
                    if (!config) timeout = 0L
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit

                else -> return out
            }
        }
    }
}
