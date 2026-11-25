package com.phonxiety.bridge.root

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [ProcessBuilderProcessRunner] against real `/bin/sh` children, so what a fake runner cannot
 * show is exercised: both pipes drained while the child runs, a bounded head+tail capture, a budget
 * that also covers draining, and a pipe a grandchild keeps open.
 */
class RootShellProcessTest {
    private val runner = ProcessBuilderProcessRunner()

    @Test
    fun returnsBothStreamsAndTheExitStatus() = runTest {
        val output = runner.run(listOf("/bin/sh", "-c", "printf out; printf err >&2; exit 3"), timeoutMs = 20_000)

        assertEquals("out", output.stdout)
        assertEquals("err", output.stderr)
        assertEquals(3, output.exitCode)
        assertFalse(output.timedOut)
        assertFalse(output.truncated)
    }

    @Test
    fun drainsMoreThanAPipeBufferOnStdoutWhileTheChildRuns() = runTest {
        val started = System.nanoTime()
        val output = runner.run(listOf("/bin/sh", "-c", flood("")), timeoutMs = 20_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(0, output.exitCode)
        assertFalse(output.timedOut)
        assertTrue(output.truncated)
        assertTrue(output.stdout.startsWith("HEAD-MARKER"))
        assertTrue(output.stdout.endsWith("TAIL-MARKER"))
        assertEquals(2 * ProcessBuilderProcessRunner.DEFAULT_CAPTURE_LIMIT_BYTES, output.stdout.length)
        assertTrue(output.stderr.isEmpty())
        assertTrue("draining took ${elapsedMs}ms", elapsedMs < FAST_RUN_MS)
    }

    @Test
    fun drainsMoreThanAPipeBufferOnStderrWhileTheChildRuns() = runTest {
        val started = System.nanoTime()
        val output = runner.run(listOf("/bin/sh", "-c", flood(" >&2")), timeoutMs = 20_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(0, output.exitCode)
        assertFalse(output.timedOut)
        assertTrue(output.truncated)
        assertTrue(output.stderr.startsWith("HEAD-MARKER"))
        assertTrue(output.stderr.endsWith("TAIL-MARKER"))
        assertEquals(2 * ProcessBuilderProcessRunner.DEFAULT_CAPTURE_LIMIT_BYTES, output.stderr.length)
        assertTrue(output.stdout.isEmpty())
        assertTrue("draining took ${elapsedMs}ms", elapsedMs < FAST_RUN_MS)
    }

    @Test
    fun keepsAStreamOfUpToTwiceTheLimitWhole() = runTest {
        val length = ProcessBuilderProcessRunner.DEFAULT_CAPTURE_LIMIT_BYTES + 40_000
        val script = "printf HEAD-MARKER; head -c ${length - MARKERS_LENGTH} /dev/zero | tr '\\0' a; printf TAIL-MARKER"

        val output = runner.run(listOf("/bin/sh", "-c", script), timeoutMs = 20_000)

        assertFalse(output.timedOut)
        assertFalse(output.truncated)
        assertTrue(output.stdout.startsWith("HEAD-MARKER"))
        assertTrue(output.stdout.endsWith("TAIL-MARKER"))
        assertEquals(length, output.stdout.length)
    }

    @Test
    fun givesUpOnAPipeHeldByABackgroundedGrandchildInsteadOfHanging() = runTest {
        val started = System.nanoTime()
        val output = runner.run(listOf("/bin/sh", "-c", "sleep 3 & echo started"), timeoutMs = HOLDING_BUDGET_MS)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(0, output.exitCode)
        assertFalse(output.timedOut)
        assertFalse(output.truncated)
        assertEquals("started\n", output.stdout)
        assertTrue("took ${elapsedMs}ms", elapsedMs < HOLDING_BUDGET_MS + ABANDON_GRACE_MS)
    }

    @Test
    fun timesOutASleepingChildAndKeepsWhatItPrinted() = runTest {
        val started = System.nanoTime()
        val output = runner.run(listOf("/bin/sh", "-c", "printf before-sleep; sleep 5"), timeoutMs = HOLDING_BUDGET_MS)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(output.timedOut)
        assertEquals(ProcessBuilderProcessRunner.EXIT_TIMEOUT, output.exitCode)
        assertEquals("before-sleep", output.stdout)
        assertTrue("took ${elapsedMs}ms", elapsedMs < HOLDING_BUDGET_MS + ABANDON_GRACE_MS)
    }

    @Test
    fun reportsAChildThatCannotBeStarted() = runTest {
        val output = runner.run(listOf("/nonexistent/phantom-shell"), timeoutMs = 20_000)

        assertTrue(output.launchFailed)
        assertEquals(ProcessBuilderProcessRunner.EXIT_NOT_STARTED, output.exitCode)
        assertFalse(output.ok)
    }

    /** A child that writes [FLOOD_BYTES] between two markers, optionally redirected as a whole. */
    private fun flood(redirect: String): String = "{ printf HEAD-MARKER; head -c $FLOOD_BYTES /dev/zero | tr '\\0' a; printf TAIL-MARKER; }$redirect"

    private companion object {
        /** Comfortably past one pipe buffer and past 1 MiB. */
        const val FLOOD_BYTES = 1_200_000
        const val MARKERS_LENGTH = 22
        const val FAST_RUN_MS = 10_000
        const val HOLDING_BUDGET_MS = 500L
        const val ABANDON_GRACE_MS = 3_000
    }
}
