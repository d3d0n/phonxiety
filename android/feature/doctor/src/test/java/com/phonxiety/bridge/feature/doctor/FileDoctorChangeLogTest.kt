package com.phonxiety.bridge.feature.doctor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The log stores events, not rendered sentences, so a line means the same in every build; a line
 * without a known event is skipped.
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
    fun aLineWithoutAKnownEventIsSkipped() {
        val file = File(folder.root, "doctor.log")
        file.writeText("123|Restarted the bridge service to reopen the link\n")

        assertEquals(emptyList<DoctorChangeEntry>(), store(file).entries())
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
    fun theLogKeepsItsNewestEntries() {
        val log = FileDoctorChangeLog(File(folder.root, "doctor.log"), limit = 2)
        repeat(3) { index ->
            log.record(DoctorChangeEntry(atEpochMs = index.toLong(), event = DoctorLogEvent.REBOOTED))
        }

        assertEquals(listOf(1L, 2L), log.entries().map { it.atEpochMs })
    }
}
