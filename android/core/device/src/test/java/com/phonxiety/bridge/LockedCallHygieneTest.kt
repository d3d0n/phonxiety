package com.phonxiety.bridge

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every test call to a production `*Locked` method holds the session monitor.
 *
 * Those methods document that the caller already owns the lock, and a call
 * without it is a race the compiler cannot see.  The check reads the test
 * sources; a call is accepted when a `synchronized(...lock)` block encloses it,
 * found by walking back to the enclosing braces.
 */
class LockedCallHygieneTest {
    private val callPattern = Regex("""\b\w+Locked\s*\(""")

    @Test
    fun everyLockedCallInTestsHoldsTheMonitor() {
        val files = testSources().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("device test sources were not found", files.isNotEmpty())
        var checked = 0
        val offenders = mutableListOf<String>()
        for (file in files) {
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
                if (!callPattern.containsMatchIn(line)) return@forEachIndexed
                checked++
                if (!holdsMonitor(lines, index)) offenders += "${file.name}:${index + 1}: ${line.trim()}"
            }
        }
        assertTrue("no *Locked call was found in the tests", checked > 0)
        assertTrue("these calls do not hold the monitor:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /**
     * True when a `synchronized(...lock)` block encloses [index].  The block
     * also opens on the call's own line; a block that closes above the call's
     * indentation ends the search, so an earlier test's lock is never borrowed.
     */
    private fun holdsMonitor(lines: List<String>, index: Int): Boolean {
        val indent = lines[index].takeWhile { it == ' ' }.length
        for (lineIndex in index downTo 0) {
            val line = lines[lineIndex]
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
            if (lineIndex < index) {
                val lineIndent = line.length - trimmed.length
                if (trimmed.startsWith("}") && lineIndent < indent) return false
            }
            if (line.contains("synchronized(") && line.contains(".lock)")) return true
        }
        return false
    }

    private fun testSources(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            for (relative in listOf(
                "src/test/java/com/phonxiety/bridge",
                "android/core/device/src/test/java/com/phonxiety/bridge"
            )) {
                val candidate = File(directory, relative)
                if (candidate.isDirectory) return candidate
            }
            directory = directory.parentFile
        }
        error("device test sources were not found from ${File("").absolutePath}")
    }
}
