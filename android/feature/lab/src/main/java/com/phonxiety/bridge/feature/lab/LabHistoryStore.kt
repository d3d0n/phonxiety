package com.phonxiety.bridge.feature.lab

import com.phonxiety.bridge.model.LinkTestResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The phone's own history of link-test runs, newest first, in one JSON file.
 *
 * The daemon persists its runs too, but the lab must show numbers when the link is down,
 * which is exactly when they matter. A corrupt or unreadable file reads as empty rather
 * than breaking the screen.
 */
class LabHistoryStore(
    private val file: File,
    private val limit: Int = 50
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val mutex = Mutex()

    suspend fun load(): List<LinkTestResult> = withContext(Dispatchers.IO) {
        mutex.withLock { read() }
    }

    suspend fun append(result: LinkTestResult): List<LinkTestResult> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = (listOf(result) + read()).take(limit)
            write(next)
            next
        }
    }

    private fun read(): List<LinkTestResult> = try {
        if (!file.isFile) {
            emptyList()
        } else {
            json.decodeFromString(ListSerializer(LinkTestResult.serializer()), file.readText())
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun write(results: List<LinkTestResult>) {
        val parent = file.parentFile
        parent?.mkdirs()
        val temporary = File(parent, file.name + ".tmp")
        temporary.writeText(json.encodeToString(ListSerializer(LinkTestResult.serializer()), results))
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }
}
