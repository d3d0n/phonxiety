package com.phonxiety.bridge.feature.settings

import com.phonxiety.bridge.PhoneCodec
import com.phonxiety.bridge.designsystem.UiText
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One applied (or reverted) auto-tune change, as the change log shows it. [reasonId] and
 * [reasonArgs] carry the template and its measurements; [reason] keeps the prose written by a build
 * that predates them, so an upgrade never loses the record.
 */
@Serializable
data class AutoTuneLogEntry(
    val id: String,
    val atMs: Long,
    val key: String,
    val from: String,
    val to: String,
    val reason: String = "",
    val reasonId: String? = null,
    val reasonArgs: List<String> = emptyList(),
    val reverted: Boolean = false
) {
    /** The change as a short title: "Codec → Opus", "Max bitrate → 16 kbit/s". */
    fun title(): UiText = when (key) {
        TuneKey.CODEC.key -> when (PhoneCodec.normalize(to)) {
            PhoneCodec.OPUS -> UiText.Res(R.string.settings_change_codec_opus)
            PhoneCodec.PCM -> UiText.Res(R.string.settings_change_codec_pcm)
            PhoneCodec.AUTO -> UiText.Res(R.string.settings_change_codec_auto)
            else -> UiText.Res(R.string.settings_change_codec_other, listOf(to))
        }

        TuneKey.MAX_BITRATE.key -> {
            val kbit = to.toIntOrNull()?.div(1_000)
            UiText.Res(
                R.string.settings_change_bitrate,
                listOf(
                    if (kbit == null) {
                        to
                    } else {
                        "${NumberFormat.getIntegerInstance(Locale.getDefault()).format(kbit)}\u00A0kbit/s"
                    }
                )
            )
        }

        TuneKey.WIFI_LOW_LATENCY.key -> UiText.Res(
            if (to == "true") R.string.settings_change_wifi_on else R.string.settings_change_wifi_off
        )

        else -> UiText.Literal("$key → $to")
    }

    /** The measurement behind the change, resolved from its template when the entry has one. */
    fun reasonText(): UiText {
        val reason = TuneReason.of(reasonId)
        return if (reason == null) {
            UiText.Literal(this.reason)
        } else {
            UiText.Res(reason.textRes, reasonArgs)
        }
    }
}

/**
 * The reversal record for everything auto-tune did, newest first, in one JSON file so a reboot
 * does not erase the reason a setting changed.
 */
class AutoTuneLogStore(
    private val file: File,
    private val limit: Int = 100
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val mutex = Mutex()

    suspend fun load(): List<AutoTuneLogEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { read() }
    }

    suspend fun append(entry: AutoTuneLogEntry): List<AutoTuneLogEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = (listOf(entry) + read()).take(limit)
            write(next)
            next
        }
    }

    suspend fun markReverted(ids: Set<String>, reverted: Boolean = true): List<AutoTuneLogEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = read().map { entry -> if (entry.id in ids) entry.copy(reverted = reverted) else entry }
            write(next)
            next
        }
    }

    private fun read(): List<AutoTuneLogEntry> = try {
        if (!file.isFile) {
            emptyList()
        } else {
            json.decodeFromString(ListSerializer(AutoTuneLogEntry.serializer()), file.readText())
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun write(entries: List<AutoTuneLogEntry>) {
        val parent = file.parentFile
        parent?.mkdirs()
        val temporary = File(parent, file.name + ".tmp")
        temporary.writeText(json.encodeToString(ListSerializer(AutoTuneLogEntry.serializer()), entries))
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }
}
