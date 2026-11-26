package com.phonxiety.bridge

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** A Doctor row prints a clock time; it must follow the zone in force when the row is built. */
class CrashReportsTest {
    private val originalZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `summary formats in the zone default at call time, not the one at load`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        // First call loads the object and its formatter while UTC is the default.
        val report = CrashReports.Report(
            atEpochMs = 1_796_496_400_000L,
            kind = CrashReports.Kind.EXIT,
            reason = "reason_16",
            description = null,
            memoryLimiter = false,
            detail = null
        )
        assertEquals("exit at 2026-12-05 18:46:40 (reason_16)", report.summary())

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tashkent"))
        assertEquals("exit at 2026-12-05 23:46:40 (reason_16)", report.summary())
    }
}
