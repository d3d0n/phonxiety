package com.phonxiety.bridge.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The attachment stream's only parser: data frames, then `end` or `error`. */
class IrohAttachmentChannelTest {
    private fun frame(kind: Int, payload: ByteArray): ByteArray = Framing.encode(kind, payload)!!

    private fun channel(frames: List<ByteArray>): IrohAttachmentChannel = IrohAttachmentChannel(FakeIrohStream(frames), FrameTapSink.None) {}

    @Test
    fun binaryFramesAreDataAndTheEndFrameCloses() = runBlocking {
        val channel = channel(
            listOf(
                frame(Framing.KIND_BINARY, byteArrayOf(1, 2, 3)),
                frame(Framing.KIND_TEXT, """{"type":"end","total":3,"sha256":"abc"}""".toByteArray()),
                frame(Framing.KIND_BINARY, byteArrayOf(4, 5, 6))
            )
        )

        val first = channel.next()
        assertTrue(first is AttachmentFrame.Data)
        assertEquals(listOf<Byte>(1, 2, 3), (first as AttachmentFrame.Data).bytes.toList())

        val end = channel.next()
        assertTrue("a text frame ends the stream", end is AttachmentFrame.End)
        assertEquals(3L, (end as AttachmentFrame.End).total)
        assertEquals("abc", end.sha256)

        // Nothing after the end is read, even though the source has more bytes.
        val after = channel.next()
        assertEquals(AttachmentFrame.Error("closed", "attachment stream is closed"), after)
    }

    @Test
    fun anErrorControlFrameCarriesItsCodeAndMessage() = runBlocking {
        val channel = channel(
            listOf(frame(Framing.KIND_TEXT, """{"type":"error","code":"too_large","message":"no"}""".toByteArray()))
        )
        assertEquals(AttachmentFrame.Error("too_large", "no"), channel.next())
        assertEquals(AttachmentFrame.Error("closed", "attachment stream is closed"), channel.next())
    }

    @Test
    fun aStreamEndingMidFrameReportsClosed() = runBlocking {
        val header = Framing.encode(Framing.KIND_BINARY, ByteArray(10))!!.copyOfRange(0, Framing.HEADER_BYTES)
        val channel = channel(listOf(header, byteArrayOf(1, 2, 3)))

        assertEquals(AttachmentFrame.Error("closed", "the attachment stream ended mid-frame"), channel.next())
        // The stream is done: a second read does not parse the remainder.
        assertEquals(AttachmentFrame.Error("closed", "attachment stream is closed"), channel.next())
    }

    @Test
    fun anUnexpectedTextFrameIsAProtocolError() = runBlocking {
        val channel = channel(listOf(frame(Framing.KIND_TEXT, """{"type":"other"}""".toByteArray())))
        assertEquals(AttachmentFrame.Error("protocol", "unexpected attachment control frame"), channel.next())
    }

    @Test
    fun cancelStopsTheStreamsOnce() {
        var cancels = 0
        val channel = IrohAttachmentChannel(FakeIrohStream(emptyList()), FrameTapSink.None) { cancels++ }
        channel.cancel()
        channel.cancel()
        assertEquals(1, cancels)
    }
}
