package com.phonxiety.bridge.net

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address link's send path: synchronous hand-off in call order, refusal
 * reported up, the socket's own queue as the backlog, and outbound metadata in
 * the dump.
 */
class SocketSenderTest {
    /** The socket as OkHttp presents it: `send` returns whether it took the frame. */
    private class FakeSocket(var accepts: Boolean = true, var queued: Long = 0L) : WebSocket {
        val calls = mutableListOf<String>()

        override fun request(): Request = error("no request in this test")

        override fun queueSize(): Long = queued

        override fun send(text: String): Boolean {
            calls += "text:$text"
            return accepts
        }

        override fun send(bytes: ByteString): Boolean {
            calls += "binary:${bytes.size}"
            return accepts
        }

        override fun close(code: Int, reason: String?): Boolean = true

        override fun cancel() = Unit
    }

    private val bytes = byteArrayOf(1, 2, 3).toByteString()

    @Test
    fun framesReachTheSocketSynchronouslyInCallOrder() {
        val socket = FakeSocket()
        val sender = SocketSender({ socket })

        assertTrue(sender.sendText("hello"))
        // The frame is already in the socket's hands: no coroutine hop.
        assertEquals(listOf("text:hello"), socket.calls)

        assertTrue(sender.sendBinary(bytes))
        assertTrue(sender.sendText("after"))

        // Call order, not kind order: one socket carries both as they come.
        assertEquals(listOf("text:hello", "binary:3", "text:after"), socket.calls)
    }

    @Test
    fun aRefusedBinaryFrameReportsFalse() {
        val socket = FakeSocket(accepts = false)
        var refusals = 0
        val sender = SocketSender({ socket }, onRefused = { refusals++ })

        assertFalse("the socket refused the frame", sender.sendBinary(bytes))
        assertEquals(listOf("binary:3"), socket.calls)
        assertEquals("the refusal is reported once", 1, refusals)

        assertFalse("text is refused the same way", sender.sendText("hello"))
        assertEquals(2, refusals)
    }

    @Test
    fun noSocketIsARefusalWithoutATerminalReport() {
        var refusals = 0
        val sender = SocketSender({ null }, onRefused = { refusals++ })

        assertFalse(sender.sendText("hello"))
        assertFalse(sender.sendBinary(bytes))
        assertEquals("nothing was live to report down", 0, refusals)
    }

    @Test
    fun theBacklogIsTheSocketsOwnQueue() {
        val socket = FakeSocket(queued = 42L)
        val sender = SocketSender({ socket })
        assertEquals("no queue of our own is added", 42L, sender.backlogBytes)

        // Accepted frames change nothing: the socket's queue is the whole count.
        assertTrue(sender.sendText("hello"))
        assertTrue(sender.sendBinary(bytes))
        assertEquals(42L, sender.backlogBytes)

        socket.queued = 7L
        assertEquals(7L, sender.backlogBytes)
        assertEquals(0L, SocketSender({ null }).backlogBytes)
    }

    @Test
    fun outboundFramesReachTheDumpWithoutPayload() = runTest {
        val dir: File = Files.createTempDirectory("socket-sender-tap").toFile()
        val dump = FrameTapDump(isEnabled = { true }, dir = dir, clock = { 1L }, scope = this)
        val session = FrameTapSession(dump, linkId = 4L)
        val socket = FakeSocket()
        val sender = SocketSender({ socket }, tap = session)
        val message = """{"type":"api","app_token":"SECRET-token"}"""
        try {
            assertTrue(sender.sendText(message))
            assertTrue(sender.sendBinary(bytes))
        } finally {
            dump.close()
            dump.job.join()
        }

        val lines = File(dir, FrameTapDump.CURRENT).readLines().filter { it.isNotBlank() }
        assertEquals("one record per handed-off frame", 2, lines.size)
        assertTrue("the text frame is outbound socket metadata: $lines", lines.any { it.contains(",OUT,SOCKET,1,${utf8Length(message)}") })
        assertTrue("the binary frame is outbound socket metadata: $lines", lines.any { it.contains(",OUT,SOCKET,2,3") })
        assertFalse("never a payload byte", lines.any { it.contains("SECRET") })
        dir.deleteRecursively()
    }

    @Test
    fun aRefusedFrameIsNotDumped() = runTest {
        val dir: File = Files.createTempDirectory("socket-sender-refused").toFile()
        val dump = FrameTapDump(isEnabled = { true }, dir = dir, clock = { 1L }, scope = this)
        val session = FrameTapSession(dump, linkId = 4L)
        val sender = SocketSender({ FakeSocket(accepts = false) }, tap = session)
        try {
            assertFalse(sender.sendBinary(bytes))
        } finally {
            dump.close()
            dump.job.join()
        }

        assertFalse("nothing left the phone, so nothing is recorded", File(dir, FrameTapDump.CURRENT).exists())
        dir.deleteRecursively()
    }
}
