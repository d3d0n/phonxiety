package com.phonxiety.bridge.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The iroh stream framing shared with the daemon must not drift by a byte. */
class FramingTest {
    @Test
    fun textFrameRoundTrips() {
        val payload = """{"type":"auth","token":"t"}""".toByteArray(Charsets.UTF_8)
        val frame = Framing.encode(Framing.KIND_TEXT, payload)!!

        assertEquals(Framing.HEADER_BYTES + payload.size, frame.size)
        val header = Framing.parse(frame)!!
        assertEquals(Framing.KIND_TEXT, header.kind)
        assertEquals(payload.size, header.length)
        assertArrayEquals(payload, frame.copyOfRange(Framing.HEADER_BYTES, frame.size))
    }

    @Test
    fun binaryFrameRoundTrips() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val frame = Framing.encode(Framing.KIND_BINARY, payload)!!

        val header = Framing.parse(frame)!!
        assertEquals(Framing.KIND_BINARY, header.kind)
        assertEquals(payload.size, header.length)
        assertArrayEquals(payload, frame.copyOfRange(Framing.HEADER_BYTES, frame.size))
    }

    @Test
    fun headerIsKindThenBigEndianLength() {
        // 300 = 0x0000012C, so the header must be 02 00 00 01 2C.
        val frame = Framing.encode(Framing.KIND_BINARY, ByteArray(300))!!

        assertArrayEquals(byteArrayOf(0x02, 0x00, 0x00, 0x01, 0x2C), frame.copyOfRange(0, 5))
    }

    @Test
    fun explicitLengthTruncatesThePayload() {
        val frame = Framing.encode(Framing.KIND_BINARY, ByteArray(10) { 9 }, length = 4)!!

        assertEquals(4, Framing.parse(frame)!!.length)
        assertArrayEquals(ByteArray(4) { 9 }, frame.copyOfRange(Framing.HEADER_BYTES, frame.size))
    }

    @Test
    fun unknownKindsAreRejected() {
        assertNull(Framing.encode(0, ByteArray(4)))
        assertNull(Framing.encode(3, ByteArray(4)))
        assertNull(Framing.parse(headerOf(0, 4)))
        assertNull(Framing.parse(headerOf(3, 4)))
    }

    @Test
    fun payloadLengthMustBeWithinTheProtocolRange() {
        assertNull(Framing.encode(Framing.KIND_TEXT, ByteArray(0)))
        assertNull(Framing.encode(Framing.KIND_BINARY, ByteArray(Framing.MAX_PAYLOAD + 1)))
        assertNull(Framing.encode(Framing.KIND_BINARY, ByteArray(10), length = 0))
        assertNull(Framing.encode(Framing.KIND_BINARY, ByteArray(10), length = 11))
        assertNull(Framing.parse(headerOf(Framing.KIND_TEXT, 0)))
        assertNull(Framing.parse(headerOf(Framing.KIND_BINARY, Framing.MAX_PAYLOAD + 1)))

        // Both ends of the accepted range are frames.
        assertEquals(Framing.MAX_PAYLOAD, Framing.parse(headerOf(Framing.KIND_BINARY, Framing.MAX_PAYLOAD))!!.length)
        assertEquals(1, Framing.parse(headerOf(Framing.KIND_TEXT, 1))!!.length)
    }

    @Test
    fun truncatedHeaderIsRejected() {
        assertNull(Framing.parse(byteArrayOf(1, 0, 0, 0)))
        assertNull(Framing.parse(ByteArray(0)))
    }

    private fun headerOf(kind: Int, length: Int) = byteArrayOf(
        kind.toByte(),
        (length ushr 24).toByte(),
        (length ushr 16).toByte(),
        (length ushr 8).toByte(),
        length.toByte(),
    )
}
