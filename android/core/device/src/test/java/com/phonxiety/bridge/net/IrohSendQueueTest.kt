package com.phonxiety.bridge.net

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-stream send queue: bounded control, unlimited audio, one FIFO writer
 * per queue and the shared backlog counter.
 */
class IrohSendQueueTest {
    private val backlog = AtomicLong(0)

    private fun frame(size: Int = 4, filler: Byte = 0): ByteArray = ByteArray(size) { filler }

    private suspend fun drainTo(
        queue: IrohSendQueue,
        write: suspend (ByteArray) -> Unit,
        onWriteFailed: () -> Unit
    ) = queue.drain(FrameStream.CONTROL, Framing.KIND_TEXT, FrameTapSink.None, write, onWriteFailed)

    @Test
    fun helloIsWrittenFirstOnControl() = runBlocking {
        val queue = IrohSendQueue(IrohSendQueue.CONTROL_CAPACITY, backlog)
        val written = Collections.synchronizedList(mutableListOf<ByteArray>())
        val job = launch(Dispatchers.Default) {
            drainTo(queue, write = { written += it }, onWriteFailed = { error("no write may fail") })
        }

        // The supervisor queues hello before it releases the gate, then
        // producers enqueue behind it.
        val hello = frame(8, 1)
        assertTrue(queue.offer(hello))
        val producers = CountDownLatch(1)
        val threads = (0 until 4).map { index ->
            Thread {
                producers.await()
                repeat(25) { queue.offer(frame(4, (index + it).toByte())) }
            }.also { it.start() }
        }
        producers.countDown()
        threads.forEach { it.join(5_000) }

        // Drain everything that was accepted.
        val drained = CountDownLatch(1)
        launch(Dispatchers.Default) {
            while (backlog.get() > 0) yield()
            drained.countDown()
        }
        assertTrue("the writer drained the accepted frames", drained.await(5, TimeUnit.SECONDS))
        queue.close()
        job.join()

        assertEquals("hello is the control stream's first frame", hello, written.first())
        assertEquals(101, written.size)
        assertEquals(0L, backlog.get())
    }

    @Test
    fun controlQueueIsBoundedAndRefusesInsteadOfSuspending() = runBlocking {
        assertEquals("the control queue the link uses is 128 frames", 128, IrohSendQueue.CONTROL_CAPACITY)
        val capacity = IrohSendQueue.CONTROL_CAPACITY
        val queue = IrohSendQueue(capacity, backlog)
        val gate = CompletableDeferred<Unit>()
        var offered = 0
        val job = launch(Dispatchers.Default) {
            drainTo(queue, write = { gate.await() }, onWriteFailed = { error("no write may fail") })
        }
        // Let the writer take the first frame and block inside write().
        yield()

        repeat(capacity) {
            assertTrue("frame ${it + 1} fits the bounded queue", queue.offer(frame()))
            offered++
        }
        val acceptedBytes = capacity.toLong() * 4L
        assertEquals(acceptedBytes, backlog.get())

        // The 129th offer is refused at once: no suspension, no backlog change.
        assertFalse("the queue is full", queue.offer(frame()))
        assertEquals(acceptedBytes, backlog.get())

        gate.complete(Unit)
        queue.close()
        job.join()
        assertEquals(0L, backlog.get())
        assertEquals(capacity, offered)
    }

    @Test
    fun audioQueueKeepsUnboundedAcceptance() {
        assertEquals("audio keeps the unlimited channel", Int.MAX_VALUE, IrohSendQueue.AUDIO_CAPACITY)
        val queue = IrohSendQueue(IrohSendQueue.AUDIO_CAPACITY, backlog)
        repeat(50_000) {
            assertTrue("audio frame $it accepted with no writer running", queue.offer(frame(1)))
        }
        assertEquals(50_000L, backlog.get())
        queue.close()
    }

    @Test
    fun backlogCountsAcceptedBytesAndRestoresOnRefusal() = runBlocking {
        val queue = IrohSendQueue(2, backlog)
        assertTrue(queue.offer(frame(10)))
        assertTrue(queue.offer(frame(20)))
        assertEquals(30L, backlog.get())
        // Full: the refused frame neither enters the queue nor stays counted.
        assertFalse(queue.offer(frame(40)))
        assertEquals(30L, backlog.get())

        val job = launch(Dispatchers.Default) {
            drainTo(queue, write = { }, onWriteFailed = { error("no write may fail") })
        }
        queue.close()
        job.join()
        assertEquals(0L, backlog.get())
    }

    @Test
    fun aThrownWriteStillDecrements() = runBlocking {
        val queue = IrohSendQueue(4, backlog)
        val failures = AtomicInteger(0)
        val job = launch(Dispatchers.Default) {
            drainTo(queue, write = { error("wedged") }, onWriteFailed = { failures.incrementAndGet() })
        }
        assertTrue(queue.offer(frame(12)))
        job.join()

        assertEquals(1, failures.get())
        assertEquals("a failed frame is not left counted", 0L, backlog.get())
    }

    @Test
    fun aWedgedWriterReportsWriteFailedOnce() = runBlocking {
        val queue = IrohSendQueue(4, backlog)
        val failures = AtomicInteger(0)
        var attempts = 0
        val job = launch(Dispatchers.Default) {
            drainTo(
                queue,
                write = {
                    attempts++
                    error("wedged")
                },
                onWriteFailed = { failures.incrementAndGet() }
            )
        }
        assertTrue(queue.offer(frame()))
        job.join()

        assertEquals(1, attempts)
        assertEquals(1, failures.get())
        assertFalse("a closed queue refuses later offers", queue.offer(frame()))
        assertEquals(0L, backlog.get())

        // A second writer finds a closed, empty queue: no second report.
        drainTo(queue, write = { attempts++ }, onWriteFailed = { failures.incrementAndGet() })
        assertEquals(1, failures.get())
    }
}
