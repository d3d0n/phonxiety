package com.phonxiety.bridge.feature.doctor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The log stores events, not rendered sentences, so a later build can still read an old line and
 * a line written before the events existed keeps its text.
 */
class FileDoctorChangeLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store(file: File = File(folder.root, "doctor.log")) = FileDoctorChangeLog(file)

    @Test
    fun anEntryRoundTripsWithItsArguments() {
        val log = store()
        log.record(
            DoctorChangeEntry(
                atEpochMs = 1_796_500_000_000,
                event = DoctorLogEvent.PERMISSION_ASKED,
                args = listOf("android.permission.RECORD_AUDIO")
            )
        )
        log.record(DoctorChangeEntry(atEpochMs = 1_796_500_001_000, event = DoctorLogEvent.BRIDGE_RESTARTED))

        val entries = log.entries()
        assertEquals(listOf(DoctorLogEvent.PERMISSION_ASKED, DoctorLogEvent.BRIDGE_RESTARTED), entries.map { it.event })
        assertEquals(listOf("android.permission.RECORD_AUDIO"), entries.first().args)
    }

    @Test
    fun anArgumentWithSeparatorsSurvivesTheFile() {
        val log = store()
        log.record(
            DoctorChangeEntry(
                atEpochMs = 1,
                event = DoctorLogEvent.FIX_FAILED,
                args = listOf("magisk --install-module | exit 1\nsecond line")
            )
        )

        assertEquals(
            listOf("magisk --install-module | exit 1 second line"),
            log.entries().single().args
        )
    }

    @Test
    fun aLineFromBeforeTheEventsKeepsItsText() {
        val file = File(folder.root, "doctor.log")
        file.writeText("123|Restarted the bridge service to reopen the link\n")

        val entry = store(file).entries().single()
        assertEquals(null, entry.event)
        assertEquals("Restarted the bridge service to reopen the link", entry.legacyText)
    }

    @Test
    fun anEmptyArgumentKeepsItsPosition() {
        val log = store()
        log.record(DoctorChangeEntry(atEpochMs = 1, event = DoctorLogEvent.REBOOT_FAILED, args = listOf("")))
        log.record(DoctorChangeEntry(atEpochMs = 2, event = DoctorLogEvent.REBOOTED))

        val entries = log.entries()
        assertEquals(listOf(""), entries.first().args)
        assertEquals(emptyList<String>(), entries.last().args)
    }

    @Test
    fun aLegacyLineIsReadAndWrittenVerbatim() {
        val file = File(folder.root, "doctor.log")
        val oldText = "The daemon printed C:\\temp | exit \\f and \\a here"
        file.writeText("123|The daemon printed C:\\temp | exit \\f and \\a here\n")

        val log = store(file)
        assertEquals(oldText, log.entries().single().legacyText)

        // Recording a new event rewrites the file; the old payload must come back byte for byte.
        log.record(DoctorChangeEntry(atEpochMs = 456, event = DoctorLogEvent.BRIDGE_RESTARTED))
        val reloaded = store(file).entries()
        assertEquals(oldText, reloaded.first { it.event == null }.legacyText)
        assertEquals(DoctorLogEvent.BRIDGE_RESTARTED, reloaded.last().event)
    }

    @Test
    fun theLogKeepsItsNewestEntries() {
        val log = FileDoctorChangeLog(File(folder.root, "doctor.log"), limit = 2)
        repeat(3) { index ->
            log.record(DoctorChangeEntry(atEpochMs = index.toLong(), event = DoctorLogEvent.REBOOTED))
        }

        assertEquals(listOf(1L, 2L), log.entries().map { it.atEpochMs })
    }
}
