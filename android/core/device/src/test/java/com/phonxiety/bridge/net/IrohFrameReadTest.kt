package com.phonxiety.bridge.net

import com.phonxiety.bridge.model.LinkDownReason
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The iroh frame reader: kinds, truncation and the tap's size-only record. */
class IrohFrameReadTest {
    private class Listener : DeviceLink.Listener {
        val texts = mutableListOf<String>()
        val binaries = mutableListOf<ByteArray>()
        val downs = mutableListOf<LinkDownReason>()

        override fun onLinkOpened() = Unit

        override fun onLinkText(text: String) {
            texts += text
        }

        override fun onLinkBinary(bytes: ByteString) {
            binaries += bytes.toByteArray()
        }

        override fun onLinkDown(reason: LinkDownReason, detail: String?) {
            downs += reason
        }
    }

    private fun textFrame(payload: String): ByteArray = Framing.encode(Framing.KIND_TEXT, payload.toByteArray(Charsets.UTF_8))!!

    private fun binaryFrame(payload: ByteArray): ByteArray = Framing.encode(Framing.KIND_BINARY, payload)!!

    @Test
    fun textAndBinaryReachTheListener() = runBlocking {
        val text = Listener()
        readIrohFrames(
            source = FakeIrohStream(listOf(textFrame("""{"type":"command"}"""))),
            expectedKind = Framing.KIND_TEXT,
            stream = FrameStream.CONTROL,
            tap = FrameTapSink.None,
            listener = text,
            fail = { text.downs += it }
        )
        assertEquals(listOf("""{"type":"command"}"""), text.texts)
        assertEquals(listOf(LinkDownReason.CLOSED), text.downs)

        val audio = Listener()
        readIrohFrames(
            source = FakeIrohStream(listOf(binaryFrame(byteArrayOf(1, 2, 3)))),
            expectedKind = Framing.KIND_BINARY,
            stream = FrameStream.AUDIO,
            tap = FrameTapSink.None,
            listener = audio,
            fail = { audio.downs += it }
        )
        assertEquals(listOf(listOf<Byte>(1, 2, 3)), audio.binaries.map { it.toList() })
        assertEquals(emptyList<String>(), audio.texts)
    }

    @Test
    fun aWrongKindIsAProtocolError() = runBlocking {
        val listener = Listener()
        readIrohFrames(
            source = FakeIrohStream(listOf(binaryFrame(byteArrayOf(9)))),
            expectedKind = Framing.KIND_TEXT,
            stream = FrameStream.CONTROL,
            tap = FrameTapSink.None,
            listener = listener,
            fail = { listener.downs += it }
        )
        assertEquals(listOf(LinkDownReason.PROTOCOL), listener.downs)
        assertEquals(emptyList<ByteArray>(), listener.binaries)
    }

    @Test
    fun aTruncatedHeaderIsClosed() = runBlocking {
        val listener = Listener()
        readIrohFrames(
            source = FakeIrohStream(listOf(byteArrayOf(1, 0))),
            expectedKind = Framing.KIND_TEXT,
            stream = FrameStream.CONTROL,
            tap = FrameTapSink.None,
            listener = listener,
            fail = { listener.downs += it }
        )
        assertEquals(listOf(LinkDownReason.CLOSED), listener.downs)
    }

    @Test
    fun theTapSeesSizeAndKindOnly() = runBlocking {
        val payload = """{"type":"api","id":"x","app_token":"TOKEN-abcdefghij"}"""
        val dir: File = Files.createTempDirectory("frame-tap-token").toFile()
        val scope = CoroutineScope(Dispatchers.IO)
        val dump = FrameTapDump(dir = dir, clock = { 1L }, scope = scope)
        dump.noteSetting(true)
        val seen = mutableListOf<FrameRecord>()
        val sink = object : FrameTapSink {
            override val enabled: Boolean = true

            override fun frame(direction: FrameDirection, stream: FrameStream, kind: Int, bytes: Int) {
                seen += FrameRecord(1L, 1L, seen.size + 1L, direction, stream, kind, bytes)
                seen.last().let { dump.record(it) }
            }
        }
        val listener = Listener()
        try {
            readIrohFrames(
                source = FakeIrohStream(listOf(textFrame(payload))),
                expectedKind = Framing.KIND_TEXT,
                stream = FrameStream.CONTROL,
                tap = sink,
                listener = listener,
                fail = { listener.downs += it }
            )
        } finally {
            dump.close()
            dump.job.join()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }

        // The tap saw one frame: direction, stream, kind and the payload's byte size.
        assertEquals(1, seen.size)
        val only = seen.single()
        assertEquals(FrameDirection.IN, only.direction)
        assertEquals(FrameStream.CONTROL, only.stream)
        assertEquals(Framing.KIND_TEXT, only.kind)
        assertEquals(payload.toByteArray(Charsets.UTF_8).size, only.bytes)

        // Never the payload: neither the record nor the dump holds the token.
        assertFalse(only.encode().contains("TOKEN"))
        assertFalse(File(dir, FrameTapDump.CURRENT).readText().contains("TOKEN"))
        assertTrue(listener.texts.single().contains("TOKEN"))
        dir.deleteRecursively()
        Unit
    }
}

/**
 * A frame source over already encoded frames: [read] returns at most what is
 * left, exactly as a closed native stream does.
 */
internal class FakeIrohStream(private val frames: List<ByteArray>) : IrohFrameSource {
    private val bytes = frames.fold(ByteArray(0)) { all, frame -> all + frame }
    private var cursor = 0

    override suspend fun read(length: Int): ByteArray {
        val end = minOf(cursor + length, bytes.size)
        val chunk = bytes.copyOfRange(cursor, end)
        cursor = end
        return chunk
    }
}
