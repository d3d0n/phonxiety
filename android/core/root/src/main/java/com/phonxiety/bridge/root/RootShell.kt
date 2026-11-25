package com.phonxiety.bridge.root

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
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
    val timeoutMs: Long = 0L,
    /**
     * The capture dropped bytes from the middle of a stream: [stdout] and [stderr] then hold its
     * beginning and its end, not the whole of it.
     */
    val truncated: Boolean = false
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

/**
 * Real process runner. Both pipes are drained by their own threads from the moment the child starts,
 * so an installer that prints more than a pipe buffer never blocks on write while we wait for it.
 * [timeoutMs] bounds the whole run — the exit and the draining alike — and only a bounded head and
 * tail of each stream is kept ([captureLimitBytes] of each, 256 KiB by default). A pipe still held
 * after that (a backgrounded grandchild inheriting the handles) is closed and abandoned instead of
 * awaited, so the call always returns.
 */
class ProcessBuilderProcessRunner(
    private val captureLimitBytes: Int = DEFAULT_CAPTURE_LIMIT_BYTES,
    private val readerGraceMs: Long = READER_GRACE_MS
) : ProcessRunner {
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
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val stdout = BoundedStreamCapture(captureLimitBytes)
        val stderr = BoundedStreamCapture(captureLimitBytes)
        val readers = listOf(
            pump("root-shell-stdout", process.inputStream, stdout),
            pump("root-shell-stderr", process.errorStream, stderr)
        )
        var timedOut = false
        var exitCode = EXIT_TIMEOUT
        try {
            if (process.waitFor(millisUntil(deadline), TimeUnit.MILLISECONDS)) {
                exitCode = process.exitValue()
            } else {
                timedOut = true
                process.destroyForcibly()
                process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)
            }
            // The readers ran alongside the child; only the pipes' end is still owed.
            val drainUntil = if (timedOut) System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readerGraceMs) else deadline
            if (!awaitReaders(readers, drainUntil)) {
                closeQuietly(process.inputStream)
                closeQuietly(process.errorStream)
                readers.forEach { it.join(readerGraceMs) }
            }
        } finally {
            process.destroy()
            closeQuietly(process.inputStream)
            closeQuietly(process.errorStream)
        }
        ShellOutput(
            stdout = stdout.text(),
            stderr = stderr.text(),
            exitCode = exitCode,
            timedOut = timedOut,
            truncated = stdout.truncated || stderr.truncated
        )
    }

    /** Starts a daemon thread that copies [stream] into [capture] until the pipe ends or closes. */
    private fun pump(name: String, stream: InputStream, capture: BoundedStreamCapture): Thread = Thread(
        {
            val buffer = ByteArray(READ_BUFFER_BYTES)
            try {
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    capture.offer(buffer, read)
                }
            } catch (_: IOException) {
                // The pipe was closed under us during teardown; what was read is kept.
            } finally {
                closeQuietly(stream)
            }
        },
        name
    ).apply {
        isDaemon = true
        start()
    }

    /** Waits for every reader to end by [deadlineNanos]; false when one is still alive. */
    private fun awaitReaders(readers: List<Thread>, deadlineNanos: Long): Boolean {
        for (reader in readers) {
            val remaining = millisUntil(deadlineNanos)
            if (remaining == 0L) break
            reader.join(remaining)
        }
        return readers.none { it.isAlive }
    }

    private fun millisUntil(deadlineNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private fun closeQuietly(stream: Closeable) {
        runCatching { stream.close() }
    }

    companion object {
        const val EXIT_NOT_STARTED: Int = 126
        const val EXIT_TIMEOUT: Int = 124

        /** Bytes kept from the beginning and from the end of each stream. */
        const val DEFAULT_CAPTURE_LIMIT_BYTES: Int = 256 * 1024

        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val KILL_GRACE_MS = 500L
        private const val READER_GRACE_MS = 250L
    }
}

/**
 * Keeps the first [limitBytes] of a stream and its last [limitBytes], so a multi-megabyte install log
 * stays bounded without losing the command's opening line or its verdict. A stream of up to twice the
 * limit is kept whole; beyond that the middle is dropped and [truncated] says so.
 *
 * Chunks arrive from a pump thread while the run may already be rendering, so every entry point is
 * synchronized.
 */
internal class BoundedStreamCapture(private val limitBytes: Int) {
    private val head = ByteArrayOutputStream(limitBytes)
    private val tail = ByteArray(limitBytes)
    private var tailStart = 0
    private var tailSize = 0
    private var total = 0L

    init {
        require(limitBytes > 0) { "capture limit must be positive" }
    }

    @get:Synchronized
    val truncated: Boolean
        get() = total > 2L * limitBytes

    @Synchronized
    fun offer(buffer: ByteArray, length: Int) {
        val headRoom = limitBytes - head.size()
        if (headRoom > 0) head.write(buffer, 0, minOf(headRoom, length))
        for (offset in 0 until length) {
            if (tailSize < limitBytes) {
                tail[(tailStart + tailSize) % limitBytes] = buffer[offset]
                tailSize++
            } else {
                tail[tailStart] = buffer[offset]
                tailStart = (tailStart + 1) % limitBytes
            }
        }
        total += length
    }

    @Synchronized
    fun text(): String {
        val first = head.toByteArray()
        if (total <= limitBytes) return String(first, Charsets.UTF_8)
        val kept = tailBytes()
        // The head and the tail overlap while the stream is shorter than twice the limit.
        val uniqueTail = (total - first.size).coerceIn(0L, kept.size.toLong()).toInt()
        val bytes = ByteArray(first.size + uniqueTail)
        first.copyInto(bytes)
        kept.copyInto(bytes, first.size, kept.size - uniqueTail)
        return String(bytes, Charsets.UTF_8)
    }

    private fun tailBytes(): ByteArray {
        val bytes = ByteArray(tailSize)
        for (index in 0 until tailSize) bytes[index] = tail[(tailStart + index) % limitBytes]
        return bytes
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

    /** Manager version from `PackageManager`, so a denied `su` still names a real version. */
    fun packageVersion(packageName: String): String? = null
}

/** Package inventory from `PackageManager`; kept abstract so this module stays JVM-testable. */
fun interface PackageInventory {
    fun installed(): Set<String>

    /** `versionName (versionCode)` of an installed package, or null when it cannot be read. */
    fun version(packageName: String): String? = null
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

    override fun packageVersion(packageName: String): String? = packages.version(packageName)
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

    override fun packageVersion(packageName: String): String? = packages.version(packageName)
}
