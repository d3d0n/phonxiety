package com.phonxiety.bridge.feature.doctor

import java.io.File

/**
 * The change log: one line per fix, kept in the app's files directory so the record survives a
 * restart. Doctor writes here only when it acted, never for a check it merely read.
 */
class FileDoctorChangeLog(
    private val file: File,
    private val limit: Int = 40
) : DoctorChangeLog {
    override fun entries(): List<DoctorChangeEntry> = runCatching {
        if (!file.isFile) return emptyList()
        file.readLines()
            .mapNotNull(::parse)
            .takeLast(limit)
    }.getOrDefault(emptyList())

    override fun record(entry: DoctorChangeEntry) {
        runCatching {
            file.parentFile?.mkdirs()
            val kept = (entries() + entry).takeLast(limit)
            val temporary = File(file.path + ".tmp")
            temporary.writeText(kept.joinToString("\n", postfix = "\n") { format(it) })
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        }
    }

    private fun format(entry: DoctorChangeEntry): String = "${entry.atEpochMs}|${entry.text.replace('\n', ' ')}"

    private fun parse(line: String): DoctorChangeEntry? {
        val separator = line.indexOf('|')
        if (separator <= 0) return null
        val at = line.substring(0, separator).toLongOrNull() ?: return null
        return DoctorChangeEntry(at, line.substring(separator + 1))
    }
}
