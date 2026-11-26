package com.phonxiety.bridge.feature.lab

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The lab is read on the phone: daemon timestamps are shown in the phone's zone. */
class LabLocalTimeTest {
    private lateinit var previous: TimeZone

    @Before
    fun setUp() {
        previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tashkent"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(previous)
    }

    @Test
    fun `a UTC daemon stamp is shown in the phone's zone`() {
        assertEquals("2026-11-26 15:24:05", localTime("2026-11-26T10:24:05+00:00"))
    }

    @Test
    fun `a Z suffix is converted too`() {
        assertEquals("2026-11-26 15:24:05", localTime("2026-11-26T10:24:05Z"))
    }

    @Test
    fun `a stamp without an offset is already local wall clock`() {
        assertEquals("2026-11-26 10:24:05", localTime("2026-11-26T10:24:05"))
    }

    @Test
    fun `an unreadable stamp is shown as it arrived`() {
        assertEquals("not a time", localTime("not a time"))
    }

    @Test
    fun `no stamp stays empty`() {
        assertNull(localTime(null))
    }
}
