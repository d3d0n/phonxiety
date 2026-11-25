package com.phonxiety.bridge.net

/**
 * Wire framing of the iroh device streams and the WebSocket transport, shared
 * with the daemon:
 *
 * ```
 * kind:u8 | payload length:u32 big-endian | payload
 * ```
 *
 * `kind` is [KIND_TEXT] for UTF-8 JSON control messages and [KIND_BINARY] for
 * binary payloads (audio, playback marks, link test).  On iroh the control
 * stream carries only text and the audio streams only binary; the WebSocket
 * carries both kinds on its one socket.  A payload is 1..[MAX_PAYLOAD] bytes;
 * a header with any other kind or length is a protocol error.  Pure Kotlin, so
 * the codec is unit-testable without Android.
 */
internal object Framing {
    const val HEADER_BYTES = 5
    const val KIND_TEXT = 1
    const val KIND_BINARY = 2
    const val MAX_PAYLOAD = 131_072

    /** A decoded frame header. */
    data class Header(val kind: Int, val length: Int)

    /**
     * Encodes one frame: the header above followed by the first [length] bytes
     * of [payload].  Returns null when the kind is unknown or the payload is
     * outside 1..[MAX_PAYLOAD], so a rejected frame is never queued.
     */
    fun encode(kind: Int, payload: ByteArray, length: Int = payload.size): ByteArray? {
        if (length > payload.size || !accepts(kind, length.toLong())) return null
        val bytes = ByteArray(HEADER_BYTES + length)
        bytes[0] = kind.toByte()
        bytes[1] = (length ushr 24).toByte()
        bytes[2] = (length ushr 16).toByte()
        bytes[3] = (length ushr 8).toByte()
        bytes[4] = length.toByte()
        payload.copyInto(bytes, HEADER_BYTES, 0, length)
        return bytes
    }

    /**
     * Parses and validates a header, reading the first [HEADER_BYTES] bytes of
     * [header].  Returns null for a truncated header, an unknown kind or a
     * payload length outside 1..[MAX_PAYLOAD].
     */
    fun parse(header: ByteArray): Header? {
        if (header.size < HEADER_BYTES) return null
        val kind = header[0].toInt() and 0xFF
        val length = ((header[1].toLong() and 0xFF) shl 24) or
            ((header[2].toLong() and 0xFF) shl 16) or
            ((header[3].toLong() and 0xFF) shl 8) or
            (header[4].toLong() and 0xFF)
        if (!accepts(kind, length)) return null
        return Header(kind, length.toInt())
    }

    /** Whether [kind] and [length] describe a frame this protocol allows. */
    private fun accepts(kind: Int, length: Long): Boolean = (kind == KIND_TEXT || kind == KIND_BINARY) && length >= 1L && length <= MAX_PAYLOAD
}
