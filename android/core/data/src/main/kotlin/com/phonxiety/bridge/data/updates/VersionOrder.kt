package com.phonxiety.bridge.data.updates

/**
 * Orders dotted version strings the way the update document does: numeric segments compare as
 * numbers, a pre-release suffix (`-rc1`) sorts below the release it belongs to, and a missing
 * segment counts as zero (`0.3` == `0.3.0`).
 */
object VersionOrder {
    fun compare(left: String, right: String): Int {
        val a = parse(left)
        val b = parse(right)
        for (index in 0 until maxOf(a.segments.size, b.segments.size)) {
            val l = a.segments.getOrElse(index) { 0L }
            val r = b.segments.getOrElse(index) { 0L }
            if (l != r) return if (l < r) -1 else 1
        }
        return when {
            a.preRelease == null && b.preRelease == null -> 0
            a.preRelease == null -> 1
            b.preRelease == null -> -1
            else -> a.preRelease.compareTo(b.preRelease)
        }
    }

    private data class Parsed(val segments: List<Long>, val preRelease: String?)

    private fun parse(version: String): Parsed {
        val trimmed = version.trim().removePrefix("v").removePrefix("V")
        val core = trimmed.substringBefore('-').substringBefore('+')
        val preRelease = trimmed.substringAfter('-', missingDelimiterValue = "")
            .substringBefore('+')
            .takeIf { it.isNotEmpty() }
        val segments = core.split('.').map { part ->
            part.takeWhile(Char::isDigit).toLongOrNull() ?: 0L
        }
        return Parsed(segments.ifEmpty { listOf(0L) }, preRelease)
    }
}
