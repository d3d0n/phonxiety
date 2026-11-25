package com.phonxiety.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The in-process ring the log viewer reads: bounded, level-filtered, oldest first. */
class BridgeLogTest {
    @Before
    fun setUp() {
        BridgeLog.minimumLevel = BridgeLog.INFO
        BridgeLog.clear()
    }

    @After
    fun tearDown() {
        BridgeLog.minimumLevel = BridgeLog.INFO
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
}
