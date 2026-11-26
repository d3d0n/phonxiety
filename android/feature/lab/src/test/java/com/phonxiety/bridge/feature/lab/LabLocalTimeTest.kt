package com.phonxiety.bridge.feature.lab

import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The lab is read on the phone: a run from today shows its time, an older one shows its date. */
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
    fun `a run from today shows the local time and no date`() {
        val shown = localTime("2026-11-26T10:24:05+00:00", NOW)!!
        assertEquals(
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(Instant.parse("2026-11-26T10:24:05Z").toEpochMilli())),
            shown
        )
        assertFalse(shown, shown.contains("2026"))
    }

    @Test
    fun `a Z suffix is converted too`() {
        assertEquals(localTime("2026-11-26T10:24:05+00:00", NOW), localTime("2026-11-26T10:24:05Z", NOW))
    }

    @Test
    fun `an older run shows its date as well`() {
        val shown = localTime("2026-11-26T10:24:05Z", NOW + 2 * DAY_MS)!!
        assertEquals(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(Instant.parse("2026-11-26T10:24:05Z").toEpochMilli())),
            shown
        )
        assertTrue(shown, shown.contains("2026"))
    }

    @Test
    fun `a stamp without an offset is already local wall clock`() {
        val shown = localTime("2026-11-26T10:24:05", NOW)!!
        assertEquals(
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(Instant.parse("2026-11-26T05:24:05Z").toEpochMilli())),
            shown
        )
    }

    @Test
    fun `an unreadable stamp is shown as it arrived`() {
        assertEquals("not a time", localTime("not a time", NOW))
    }

    @Test
    fun `no stamp stays empty`() {
        assertNull(localTime(null, NOW))
    }

    private companion object {
        /** Midday UTC on the same local day as the stamps above. */
        val NOW = Instant.parse("2026-11-26T12:00:00Z").toEpochMilli()
        const val DAY_MS = 86_400_000L
    }
}
