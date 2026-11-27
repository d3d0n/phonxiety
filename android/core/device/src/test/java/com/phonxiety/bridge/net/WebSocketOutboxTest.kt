package com.phonxiety.bridge.net

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address link's outbox: one FIFO writer for text and binary, acceptance
 * without suspension, a bounded queue and a single write-failure report.
 */
class WebSocketOutboxTest {
    private val bytes = byteArrayOf(1, 2, 3).toByteString()

    @Test
    fun textAndBinaryKeepCallOrderOnOneWriter() = runBlocking {
        val outbox = WebSocketOutbox()
        val written = Collections.synchronizedList(mutableListOf<String>())
        val job = launch(Dispatchers.Default) {
            outbox.drain(
                send = { frame ->
                    written += when (frame) {
                        is SocketFrame.Text -> "text:${frame.text}"
                        is SocketFrame.Binary -> "binary:${frame.payload.size}"
                    }
                    true
                },
                onWriteFailed = { error("no send may fail") }
            )
        }
        assertTrue(outbox.offerText("hello"))
        assertTrue(outbox.offerBinary(bytes))
        assertTrue(outbox.offerText("after"))
        outbox.close()
        job.join()

        // Call order, not kind order: the one socket carries both in the order we accepted them.
        assertEquals(listOf("text:hello", "binary:3", "text:after"), written)
    }

    @Test
    fun sendTextAcceptsIntoOurQueueWhileTheWriterIsBlocked() = runBlocking {
        val outbox = WebSocketOutbox()
        val gate = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            outbox.drain(
                send = {
                    gate.await()
                    true
                },
                onWriteFailed = { error("no send may fail") }
            )
        }
        val hello = """{"type":"hello"}"""
        val api = """{"type":"api","id":"x"}"""
        assertTrue(outbox.offerText(hello))
        // The writer took the first frame and is blocked inside send().
        yield()

        // sendText is an offer: it returns at once, with the frame in OUR queue.
        assertTrue(outbox.offerText(api))
        assertEquals((utf8Length(hello) + utf8Length(api)).toLong(), outbox.pendingBytes)

        gate.complete(Unit)
        outbox.close()
        job.join()
        assertEquals(0L, outbox.pendingBytes)
    }

    @Test
    fun theQueueIsBoundedAndRefuses() {
        val outbox = WebSocketOutbox(capacity = 2)
        assertTrue(outbox.offerText("a"))
        assertTrue(outbox.offerText("b"))
        assertEquals(2L, outbox.pendingBytes)
        assertFalse("a full outbox refuses instead of suspending", outbox.offerBinary(bytes))
        assertEquals("a refused frame adds nothing", 2L, outbox.pendingBytes)
        outbox.close()
    }

    @Test
    fun aDeadWriterReportsWriteFailedOnce() = runBlocking {
        val outbox = WebSocketOutbox()
        val failures = AtomicInteger(0)
        var attempts = 0
        val job = launch(Dispatchers.Default) {
            outbox.drain(
                send = {
                    attempts++
                    false
                },
                onWriteFailed = { failures.incrementAndGet() }
            )
        }
        assertTrue(outbox.offerText("first"))
        job.join()

        assertEquals(1, attempts)
        assertEquals(1, failures.get())
        assertEquals("the frame is not left counted", 0L, outbox.pendingBytes)
        assertFalse("a closed outbox refuses later sends", outbox.offerText("second"))

        // A second writer finds a closed queue: no second report.
        outbox.drain(
            send = {
                attempts++
                true
            },
            onWriteFailed = { failures.incrementAndGet() }
        )
        assertEquals(1, failures.get())
    }

    @Test
    fun pendingBytesCountsBothKindsUntilWritten() = runBlocking {
        val outbox = WebSocketOutbox()
        assertTrue(outbox.offerText("ok")) // 2 bytes of UTF-8
        assertTrue(outbox.offerBinary(bytes)) // 3 bytes
        assertEquals(5L, outbox.pendingBytes)

        val job = launch(Dispatchers.Default) {
            outbox.drain(send = { true }, onWriteFailed = { error("no send may fail") })
        }
        outbox.close()
        job.join()
        assertEquals(0L, outbox.pendingBytes)
    }
}
