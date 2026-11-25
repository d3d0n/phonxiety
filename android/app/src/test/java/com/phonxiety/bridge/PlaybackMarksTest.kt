package com.phonxiety.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Playback marks carry the daemon's audio batch boundary; ids and timing must be exact. */
class PlaybackMarksTest {
    @Test
    fun markDrainsOnlyAfterTheQueuedSamplesReachZero() {
        val marks = PlaybackMarks(epoch = 7)

        assertTrue(marks.offer(7, 3))
        // Samples still queued: the mark stays pending.
        assertNull(marks.observe { 480L })
        assertNull(marks.observe { 1L })
        assertNull(marks.drainedMark())

        assertEquals(3L, marks.observe { 0L })
        assertEquals(3L, marks.drainedMark())
        // Already drained once: nothing to report again.
        assertNull(marks.observe { 0L })
    }

    @Test
    fun markForAnotherEpochIsIgnored() {
        val marks = PlaybackMarks(epoch = 7)

        assertFalse(marks.offer(8, 5))
        assertNull(marks.observe { 0L })
        assertNull(marks.drainedMark())

        // The foreign mark did not replace a later mark of the own epoch.
        assertTrue(marks.offer(7, 5))
        assertEquals(5L, marks.observe { 0L })
    }

    @Test
    fun laterMarkReplacesPendingAndDrainedIdsNeverGoBackwards() {
        val marks = PlaybackMarks(epoch = 7)

        assertTrue(marks.offer(7, 3))
        assertTrue(marks.offer(7, 9))
        assertEquals(9L, marks.observe { 0L })
        assertEquals(9L, marks.drainedMark())

        // A stale mark neither becomes pending nor moves the drained id back.
        assertFalse(marks.offer(7, 5))
        assertNull(marks.observe { 0L })
        assertEquals(9L, marks.drainedMark())

        // Bigger ids keep flowing in the same direction.
        assertTrue(marks.offer(7, 12))
        assertEquals(12L, marks.observe { 0L })
        assertEquals(12L, marks.drainedMark())
    }

    @Test
    fun pendingMarkIsNotDrainedTwice() {
        val marks = PlaybackMarks(epoch = 1)

        assertTrue(marks.offer(1, 4))
        assertEquals(4L, marks.observe { 0L })
        assertNull(marks.observe { 0L })
    }

    @Test
    fun parsesSixteenByteMarkFrame() {
        // "PXMK" | epoch 0x01020304 | mark id 0x0000000100000002
        val frame = byteArrayOf(
            0x50, 0x58, 0x4D, 0x4B,
            0x01, 0x02, 0x03, 0x04,
            0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x02,
        )

        val mark = MarkFrame.parse(frame)!!
        assertEquals(0x01020304L, mark.epoch)
        assertEquals(0x0000000100000002L, mark.id)
        assertEquals(16, MarkFrame.SIZE)
    }

    @Test
    fun rejectsMalformedMarkFrames() {
        val valid = byteArrayOf(
            0x50, 0x58, 0x4D, 0x4B,
            0x00, 0x00, 0x00, 0x07,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x03,
        )

        // Right length, wrong magic: it is an audio header, not a mark.
        assertNull(MarkFrame.parse(valid.copyOf().also { it[2] = 0x30.toByte() }))
        // Not exactly 16 bytes, either way.
        assertNull(MarkFrame.parse(valid.copyOf(15)))
        assertNull(MarkFrame.parse(valid.copyOf(17)))
        assertNull(MarkFrame.parse(ByteArray(0)))
    }

    @Test
    fun aMarkArrivingWhileTheQueueIsReadWaitsForTheNextReport() {
        // The audio before the mark lands just after the queue was read as empty;
        // the mark itself must not be drained by that stale reading.
        val marks = PlaybackMarks(epoch = 7)
        val reading = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var drained: Long? = -1
        val reporter = Thread {
            drained = marks.observe {
                reading.countDown()
                release.await()
                0L
            }
        }
        reporter.start()
        reading.await()
        val offered = java.util.concurrent.atomic.AtomicBoolean(false)
        val receiver = Thread { offered.set(marks.offer(7, 1)) }
        receiver.start()
        receiver.join(100) // it must wait for the report in progress
        assertFalse(offered.get())
        release.countDown()
        reporter.join()
        receiver.join()
        assertNull(drained)
        assertTrue(offered.get())
        assertEquals(1L, marks.observe { 0L })
    }
}
