package com.phonxiety.bridge.root

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Raw result of one external process. */
data class ShellOutput(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0,
    val timedOut: Boolean = false,
    /** The process could not be started at all — on Android, `su` is not installed. */
    val launchFailed: Boolean = false,
    /** The budget this run was given, recorded so a timeout can report it. */
    val timeoutMs: Long = 0L
) {
    val ok: Boolean get() = !timedOut && !launchFailed && exitCode == 0

    /** stdout and stderr joined the way the Doctor screen shows them. */
    val combined: String
        get() = buildString {
            if (stdout.isNotEmpty()) append(stdout.trimEnd())
            if (stderr.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(stderr.trimEnd())
            }
        }

    fun tail(lines: Int = 20): List<String> = combined.lineSequence().filter { it.isNotBlank() }.toList().takeLast(lines)
}

/**
 * Injectable executor. Production uses [ProcessBuilderProcessRunner]; tests substitute a fake so
 * every provider command, timeout and denial is asserted without a device.
 */
fun interface ProcessRunner {
    suspend fun run(command: List<String>, timeoutMs: Long): ShellOutput
}

/** Real process runner: kills the child after [timeoutMs] and preserves what it printed so far. */
class ProcessBuilderProcessRunner : ProcessRunner {
    override suspend fun run(command: List<String>, timeoutMs: Long): ShellOutput = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(false).start()
        } catch (error: IOException) {
            return@withContext ShellOutput(
                stderr = error.message ?: "could not start ${command.firstOrNull()}",
                exitCode = EXIT_NOT_STARTED,
                launchFailed = true
            )
        }
        try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)
                ShellOutput(
                    stdout = readStream(process.inputStream),
                    stderr = readStream(process.errorStream),
                    exitCode = EXIT_TIMEOUT,
                    timedOut = true
                )
            } else {
                ShellOutput(
                    stdout = readStream(process.inputStream),
                    stderr = readStream(process.errorStream),
                    exitCode = process.exitValue()
                )
            }
        } finally {
            process.destroy()
        }
    }

    private fun readStream(stream: java.io.InputStream): String = runCatching { stream.bufferedReader().use { it.readText() } }.getOrDefault("")

    companion object {
        const val EXIT_NOT_STARTED: Int = 126
        const val EXIT_TIMEOUT: Int = 124
        private const val KILL_GRACE_MS = 500L
    }
}

/**
 * The only caller of `su`. It accepts just a [RootCommand], waits at most [timeoutMs]
 * (20 s by default) and reports stdout, stderr and the exit status.
 */
class RootShell(
    private val runner: ProcessRunner = ProcessBuilderProcessRunner(),
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    /** Runs `su -c <command.script>`. Cancellation of the calling coroutine kills the child. */
    suspend fun exec(command: RootCommand): ShellOutput {
        val output = runner.run(listOf(SU_BINARY, "-c", command.script), timeoutMs)
        return if (output.timeoutMs == 0L) output.copy(timeoutMs = timeoutMs) else output
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 20_000L
        const val SU_BINARY: String = "su"

        /** The exact argv handed to the runner, exposed so tests can assert it. */
        fun argvFor(command: RootCommand): List<String> = listOf(SU_BINARY, "-c", command.script)
    }
}

/**
 * Read-only view of the device that the root code needs, injectable for tests.
 * Production is [ShellRootEnvironment] (`/data/adb` is root-only) plus an Android package inventory
 * supplied by the app layer.
 */
interface RootEnvironment {
    /** Entry names of [path], or null when the directory cannot be read. Not recursive. */
    suspend fun listDir(path: String): List<String>?

    /** File contents, or null when the file cannot be read. */
    suspend fun readFile(path: String): String?

    /** Every installed package name. */
    fun installedPackages(): Set<String>
}

/** Package inventory from `PackageManager`; kept abstract so this module stays JVM-testable. */
fun interface PackageInventory {
    fun installed(): Set<String>
}

/** Reads the module tree through [RootShell], since `/data/adb` is unreadable to the app itself. */
class ShellRootEnvironment(
    private val shell: RootShell,
    private val packages: PackageInventory = PackageInventory { emptySet() }
) : RootEnvironment {
    override suspend fun listDir(path: String): List<String>? {
        val entries = shell.exec(RootCommand.listDir(path))
        if (!entries.ok) return null
        return entries.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    override suspend fun readFile(path: String): String? {
        val output = shell.exec(RootCommand.readModuleFile(path))
        return if (output.ok) output.stdout else null
    }

    override fun installedPackages(): Set<String> = packages.installed()
}

/** A plain-filesystem environment: used by tests and by `adb shell`-style tooling. */
class FileRootEnvironment(
    private val root: File = File("/"),
    private val packages: PackageInventory = PackageInventory { emptySet() }
) : RootEnvironment {
    override suspend fun listDir(path: String): List<String>? {
        val dir = File(root, path.trimStart('/'))
        return if (dir.isDirectory) dir.list()?.sorted() else null
    }

    override suspend fun readFile(path: String): String? {
        val file = File(root, path.trimStart('/'))
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    override fun installedPackages(): Set<String> = packages.installed()
}
