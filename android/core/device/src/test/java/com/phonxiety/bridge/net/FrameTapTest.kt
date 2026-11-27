package com.phonxiety.bridge.net

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The opt-in frame dump: off by default, bounded, rotating, and never a payload byte. */
class FrameTapTest {
    private val dir: File = Files.createTempDirectory("frame-tap").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(name: String) = File(dir, name)

    private fun record(seq: Long, bytes: Int = 32) = FrameRecord(
        atMs = 1_000L + seq,
        linkId = 7L,
        seq = seq,
        direction = FrameDirection.OUT,
        stream = FrameStream.CONTROL,
        kind = Framing.KIND_TEXT,
        bytes = bytes
    )

    @Test
    fun offWritesNothing() = runTest {
        val dump = FrameTapDump(
            isEnabled = { false },
            dir = dir,
            clock = { 1L },
            scope = this
        )
        repeat(10) { dump.record(record(it.toLong())) }
        dump.close()
        dump.job.join()

        assertFalse("the default is off and the dump stays absent", file(FrameTapDump.CURRENT).exists())
        assertFalse(file(FrameTapDump.PREVIOUS).exists())
        assertEquals(false, FrameTapSink.None.enabled)
    }

    @Test
    fun recordsAreBoundedAndRotate() = runTest {
        val dump = FrameTapDump(
            isEnabled = { true },
            dir = dir,
            clock = { 1L },
            scope = this,
            maxBytes = 240
        )
        repeat(30) { dump.record(record(it.toLong())) }
        dump.close()
        dump.job.join()

        val current = file(FrameTapDump.CURRENT)
        val previous = file(FrameTapDump.PREVIOUS)
        assertTrue("the current file is written", current.isFile)
        assertTrue("the older file was rotated in", previous.isFile)
        assertTrue("current stays within the bound: ${current.length()}", current.length() <= 240L)
        assertTrue("previous stays within the bound: ${previous.length()}", previous.length() <= 240L)
        assertTrue("two files at most", current.length() + previous.length() <= 480L)
    }

    @Test
    fun aFullTapDropsAndCounts() = runTest {
        val dump = FrameTapDump(
            isEnabled = { true },
            dir = dir,
            clock = { 1L },
            scope = this,
            capacity = 2
        )
        // Nothing has consumed the queue yet: two fit, the rest are dropped and counted.
        repeat(5) { dump.record(record(it.toLong())) }
        dump.close()
        dump.job.join()

        val text = file(FrameTapDump.CURRENT).readText()
        assertTrue("the drop is reported: $text", text.contains("dropped=3"))
        assertEquals("only the accepted records were written", 2, text.lines().count { it.matches(Regex("\\d+,\\d+,\\d+,.+")) })
    }

    @Test
    fun eachRecordHasDirectionStreamKindSizeLinkAndSeq() = runTest {
        val dump = FrameTapDump(
            isEnabled = { true },
            dir = dir,
            clock = { 5_000L },
            scope = this
        )
        dump.record(
            FrameRecord(
                atMs = 5_000L,
                linkId = 7L,
                seq = 3L,
                direction = FrameDirection.OUT,
                stream = FrameStream.AUDIO,
                kind = Framing.KIND_BINARY,
                bytes = 960
            )
        )
        dump.close()
        dump.job.join()

        assertEquals("5000,7,3,OUT,AUDIO,2,960", file(FrameTapDump.CURRENT).readText().trim())
    }
}
