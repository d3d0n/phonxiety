package com.phonxiety.bridge.feature.doctor

import java.io.File

/**
 * The change log: one line per fix, kept in the app's files directory so the record survives a
 * restart. Doctor writes here only when it acted, never for a check it merely read.
 *
 * A line names a stable [DoctorLogEvent] plus its arguments, so the copy stays translatable and a
 * resource-id change never rewrites history. A line written before the events existed keeps its
 * text as a legacy entry.
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

    private fun format(entry: DoctorChangeEntry): String {
        val event = entry.event
        if (event == null) return "${entry.atEpochMs}|${escape(entry.legacyText.orEmpty())}"
        val args = entry.args.joinToString(ARG_SEPARATOR.toString()) { escape(it) }
        return "${entry.atEpochMs}|${event.name}|$args"
    }

    private fun parse(line: String): DoctorChangeEntry? {
        val first = line.indexOf(FIELD_SEPARATOR)
        if (first <= 0) return null
        val at = line.substring(0, first).toLongOrNull() ?: return null
        val rest = line.substring(first + 1)
        val second = rest.indexOf(FIELD_SEPARATOR)
        val head = if (second < 0) rest else rest.substring(0, second)
        val event = DoctorLogEvent.entries.firstOrNull { it.name == head }
            ?: return DoctorChangeEntry(atEpochMs = at, legacyText = unescape(rest))
        val args = if (second < 0) {
            emptyList()
        } else {
            rest.substring(second + 1).split(ARG_SEPARATOR).filter { it.isNotEmpty() }.map(::unescape)
        }
        return DoctorChangeEntry(atEpochMs = at, event = event, args = args)
    }

    private fun escape(text: String): String = buildString {
        text.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                FIELD_SEPARATOR -> append("\\f")
                ARG_SEPARATOR -> append("\\a")
                '\n', '\r', '\t' -> append(' ')
                else -> append(character)
            }
        }
    }

    private fun unescape(text: String): String = buildString {
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '\\' && index + 1 < text.length) {
                when (text[index + 1]) {
                    '\\' -> append('\\')
                    'f' -> append(FIELD_SEPARATOR)
                    'a' -> append(ARG_SEPARATOR)
                    else -> append(text[index + 1])
                }
                index += 2
            } else {
                append(character)
                index += 1
            }
        }
    }

    private companion object {
        const val FIELD_SEPARATOR = '|'
        const val ARG_SEPARATOR = '\u001F'
    }
}
