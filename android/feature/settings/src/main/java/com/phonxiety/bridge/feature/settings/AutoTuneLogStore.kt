package com.phonxiety.bridge.feature.settings

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One applied (or reverted) auto-tune change, as the change log shows it. */
@Serializable
data class AutoTuneLogEntry(
    val id: String,
    val atMs: Long,
    val key: String,
    val from: String,
    val to: String,
    val reason: String,
    val reverted: Boolean = false
) {
    val title: String
        get() = when (key) {
            TuneKey.CODEC.key -> "Codec → $to"
            TuneKey.MAX_BITRATE.key -> "Max bitrate → ${to.toIntOrNull()?.div(1_000)} kbit/s"
            TuneKey.WIFI_LOW_LATENCY.key -> "Wi-Fi low-latency lock → ${if (to == "true") "on" else "off"}"
            else -> "$key → $to"
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

    suspend fun markReverted(ids: Set<String>): List<AutoTuneLogEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = read().map { entry -> if (entry.id in ids) entry.copy(reverted = true) else entry }
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
