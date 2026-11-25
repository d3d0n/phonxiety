package com.phonxiety.bridge.root

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RootShellTest {
    @Test
    fun runsTheCommandThroughSuWithTheTwentySecondBudget() = runTest {
        val runner = RecordingRunner { ShellOutput(stdout = "uid=0(root) gid=0(root)") }
        val shell = RootShell(runner)

        val output = shell.exec(RootCommand.id())

        assertEquals("uid=0(root) gid=0(root)", output.stdout)
        assertEquals(listOf("su", "-c", "id"), runner.invocations.single().argv)
        assertEquals(RootShell.DEFAULT_TIMEOUT_MS, runner.invocations.single().timeoutMs)
    }

    @Test
    fun recordsTheBudgetOnATimeout() = runTest {
        val runner = RecordingRunner { ShellOutput(timedOut = true, stdout = "partial") }
        val shell = RootShell(runner, timeoutMs = 5_000)

        val output = shell.exec(RootCommand.reboot())

        assertEquals(true, output.timedOut)
        assertEquals(5_000L, output.timeoutMs)
        assertEquals(false, output.ok)
    }

    @Test
    fun combinedOutputKeepsStderr() = runTest {
        val runner = RecordingRunner { ShellOutput(stdout = "out", stderr = "err", exitCode = 1) }
        val shell = RootShell(runner)

        val output = shell.exec(RootCommand.id())

        assertEquals("out\nerr", output.combined)
    }
}
