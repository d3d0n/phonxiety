package com.phonxiety.bridge.feature.settings

import com.phonxiety.bridge.designsystem.UiText
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The auto-tune log keeps the reason template and its measurements, and a record written before
 * the templates existed still shows its prose instead of an empty line.
 */
class AutoTuneLogStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store(limit: Int = 100) = AutoTuneLogStore(File(folder.root, "auto-tune.json"), limit)

    private fun entry(reasonId: String?, reason: String = "") = AutoTuneLogEntry(
        id = "1",
        atMs = 1_764_146_000_000,
        key = TuneKey.MAX_BITRATE.key,
        from = "32000",
        to = "16000",
        reason = reason,
        reasonId = reasonId,
        reasonArgs = listOf("512\u00A0ms", "4%", "16\u00A0kbit/s")
    )

    @Test
    fun aStoredTemplateAndItsArgumentsComeBack() = runBlocking {
        val stored = entry(TuneReason.BITRATE_LOWER_P95_LOSS.name)

        val loaded = store().append(stored).first()

        assertEquals(
            UiText.Res(R.string.settings_reason_bitrate_lower_p95_loss, stored.reasonArgs),
            loaded.reasonText()
        )
        assertEquals(UiText.Res(R.string.settings_change_bitrate, listOf("16\u00A0kbit/s")), loaded.title())
    }

    @Test
    fun aRecordFromBeforeTheTemplatesKeepsItsProse() = runBlocking {
        val legacy = entry(reasonId = null, reason = "link test p95 512 ms, loss 4%: dropped to Opus 16 kbit/s")
        store().append(legacy)

        val loaded = store().load().single()

        assertEquals(UiText.Literal(legacy.reason), loaded.reasonText())
    }

    @Test
    fun aFileWrittenByAnOlderBuildIsReadable() = runBlocking {
        val file = File(folder.root, "auto-tune.json")
        file.writeText(
            Json.encodeToString(
                ListSerializer(AutoTuneLogEntry.serializer()),
                listOf(
                    AutoTuneLogEntry(
                        id = "old",
                        atMs = 1,
                        key = TuneKey.WIFI_LOW_LATENCY.key,
                        from = "true",
                        to = "false",
                        reason = "thermal SEVERE: released the Wi-Fi low-latency lock"
                    )
                )
            )
        )

        val loaded = AutoTuneLogStore(file).load().single()

        assertEquals(UiText.Res(R.string.settings_change_wifi_off), loaded.title())
        assertEquals(UiText.Literal("thermal SEVERE: released the Wi-Fi low-latency lock"), loaded.reasonText())
    }

    @Test
    fun revertingMarksTheEntryAndKeepsTheReason() = runBlocking {
        val stored = entry(TuneReason.BITRATE_LOWER_P95_LOSS.name)
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
            store.append(entry(TuneReason.BITRATE_LOWER_P95_LOSS.name).copy(id = index.toString(), atMs = index.toLong()))
        }

        assertEquals("newest first", listOf("2", "1"), store.load().map { it.id })
    }
}
