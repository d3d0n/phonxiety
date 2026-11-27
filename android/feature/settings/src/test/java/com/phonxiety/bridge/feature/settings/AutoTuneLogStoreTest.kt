package com.phonxiety.bridge.feature.settings

import com.phonxiety.bridge.designsystem.UiText
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The auto-tune log keeps the reason template and its measurements; an entry without a known
 * template is skipped with the file it sits in.
 */
class AutoTuneLogStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store(limit: Int = 100) = AutoTuneLogStore(File(folder.root, "auto-tune.json"), limit)

    private fun entry(reasonId: TuneReason) = AutoTuneLogEntry(
        id = "1",
        atMs = 1_764_146_000_000,
        key = TuneKey.MAX_BITRATE.key,
        from = "32000",
        to = "16000",
        reasonId = reasonId,
        reasonArgs = listOf("512\u00A0ms", "4%", "16\u00A0kbit/s")
    )

    @Test
    fun aStoredTemplateAndItsArgumentsComeBack() = runBlocking {
        val stored = entry(TuneReason.BITRATE_LOWER_P95_LOSS)

        val loaded = store().append(stored).first()

        assertEquals(
            UiText.Res(R.string.settings_reason_bitrate_lower_p95_loss, stored.reasonArgs),
            loaded.reasonText()
        )
        assertEquals(UiText.Res(R.string.settings_change_bitrate, listOf("16\u00A0kbit/s")), loaded.title())
    }

    @Test
    fun aFileWithoutReasonTemplatesIsIgnored() = runBlocking {
        val file = File(folder.root, "auto-tune.json")
        file.writeText(
            """[{"id":"old","atMs":1,"key":"wifi_low_latency","from":"true","to":"false",""" +
                """"reason":"thermal SEVERE: released the Wi-Fi low-latency lock"}]"""
        )

        assertEquals(emptyList<AutoTuneLogEntry>(), AutoTuneLogStore(file).load())
    }

    @Test
    fun revertingMarksTheEntryAndKeepsTheReason() = runBlocking {
        val stored = entry(TuneReason.BITRATE_LOWER_P95_LOSS)
        val store = store()
        store.append(stored)

        val loaded = store.markReverted(setOf(stored.id)).single()

        assertEquals(true, loaded.reverted)
        assertEquals(stored.reasonArgs, loaded.reasonArgs)

        // Undo puts the record back without losing why it happened.
        val restored = store.markReverted(setOf(stored.id), reverted = false).single()
        assertEquals(false, restored.reverted)
        assertEquals(stored.reasonArgs, restored.reasonArgs)
    }

    @Test
    fun theLogKeepsItsNewestEntries() = runBlocking {
        val store = store(limit = 2)
        repeat(3) { index ->
            store.append(entry(TuneReason.BITRATE_LOWER_P95_LOSS).copy(id = index.toString(), atMs = index.toLong()))
        }

        assertEquals("newest first", listOf("2", "1"), store.load().map { it.id })
    }
}
