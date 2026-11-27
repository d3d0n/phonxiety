package com.phonxiety.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The in-process ring the log viewer reads: bounded, level-filtered, oldest first. */
class BridgeLogTest {
    @Before
    fun setUp() {
        BridgeLog.minimumLevel = BridgeLog.INFO
        BridgeLog.sinkLevel = BridgeLog.DEBUG
        BridgeLog.sink = BridgeLog.Sink { _, _, _, _ -> }
        BridgeLog.clear()
    }

    @After
    fun tearDown() {
        BridgeLog.minimumLevel = BridgeLog.INFO
        BridgeLog.sinkLevel = BridgeLog.DEBUG
        BridgeLog.sink = BridgeLog.defaultSink
        BridgeLog.clear()
    }

    @Test
    fun `lines below the level are not kept but still appear once the level drops`() {
        BridgeLog.minimumLevel = BridgeLog.WARN
        BridgeLog.d("T", "hidden debug")
        BridgeLog.i("T", "hidden info")
        BridgeLog.w("T", "kept warn")
        assertEquals(listOf("kept warn"), BridgeLog.snapshot().map { it.message })

        BridgeLog.minimumLevel = BridgeLog.DEBUG
        BridgeLog.d("T", "now kept")
        assertEquals(listOf("kept warn", "now kept"), BridgeLog.snapshot().map { it.message })
    }

    @Test
    fun `the ring keeps the newest lines and stays ordered`() {
        repeat(2_050) { index -> BridgeLog.i("T", "line $index") }
        val lines = BridgeLog.snapshot()
        assertEquals(2_000, lines.size)
        assertEquals("line 50", lines.first().message)
        assertEquals("line 2049", lines.last().message)
    }

    @Test
    fun `formatted text carries level, tag and message`() {
        BridgeLog.e("PhonxietyBridge", "audio failed")
        val line = BridgeLog.text().lines().single()
        assertTrue(line.contains("E/PhonxietyBridge: audio failed"))
    }

    @Test
    fun `the revision moves for every captured line`() {
        val before = BridgeLog.revision.value
        BridgeLog.i("T", "one")
        BridgeLog.w("T", "two")
        assertEquals(before + 2, BridgeLog.revision.value)
    }

    @Test
    fun `the sink receives every level by default while the ring keeps its own filter`() {
        val received = mutableListOf<String>()
        BridgeLog.sink = BridgeLog.Sink { level, tag, _, _ -> received += "${level.name}:$tag" }
        BridgeLog.minimumLevel = BridgeLog.WARN

        BridgeLog.d("D", "debug")
        BridgeLog.i("I", "info")
        BridgeLog.w("W", "warn")

        assertEquals(listOf("DEBUG:D", "INFO:I", "WARN:W"), received)
        assertEquals(listOf("warn"), BridgeLog.snapshot().map { it.message })
    }

    @Test
    fun `the sink threshold narrows what it receives without touching the ring`() {
        val received = mutableListOf<String>()
        BridgeLog.sink = BridgeLog.Sink { _, _, message, _ -> received += message }
        BridgeLog.sinkLevel = BridgeLog.ERROR
        BridgeLog.minimumLevel = BridgeLog.DEBUG

        BridgeLog.d("T", "kept by the ring only")
        BridgeLog.e("T", "reaches both")

        assertEquals(listOf("reaches both"), received)
        assertEquals(listOf("kept by the ring only", "reaches both"), BridgeLog.snapshot().map { it.message })
    }

    @Test
    fun `a lazy message below both thresholds is never built`() {
        BridgeLog.minimumLevel = BridgeLog.ERROR
        BridgeLog.sinkLevel = BridgeLog.ERROR
        var built = false

        BridgeLog.i("T") {
            built = true
            "hidden"
        }

        assertFalse("an info line accepts neither consumer", built)
        BridgeLog.e("T") {
            built = true
            "kept"
        }
        assertTrue("an error line is accepted", built)
        assertEquals(listOf("kept"), BridgeLog.snapshot().map { it.message })
    }

    @Test
    fun `a lazy message is built when only the ring accepts it`() {
        BridgeLog.minimumLevel = BridgeLog.DEBUG
        BridgeLog.sinkLevel = BridgeLog.ERROR
        val received = mutableListOf<String>()
        BridgeLog.sink = BridgeLog.Sink { _, _, message, _ -> received += message }

        BridgeLog.i("T") { "for the ring" }

        assertEquals(listOf("for the ring"), BridgeLog.snapshot().map { it.message })
        assertTrue("the sink level still refuses it", received.isEmpty())
    }
}
