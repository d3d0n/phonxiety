package com.phonxiety.bridge

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Why the process died, kept on disk so the next launch and the diagnostics
 * bundle can see it.
 *
 * Two sources feed this:
 *
 *  * [collectExitReasons] reads the system's own record
 *    ([ActivityManager.getHistoricalProcessExitReasons]) and persists every entry
 *    it has not stored yet — the reason, the description (Android names the
 *    memory limiter there as `MemoryLimiter:AnonSwap`), and the ANR trace when the
 *    system kept one;
 *  * [install] wraps the default uncaught-exception handler so a Kotlin/Java
 *    crash is written before the process stops.
 *
 * Files live in the app's device-protected storage, are one per record, are
 * sorted by their timestamp prefix, and the newest [MAX_ENTRIES] are kept.  Only
 * [latest] and [recent] are public API; the formats are private because nothing
 * outside this file reads them.
 */
object CrashReports {
    private const val TAG = "PhonxietyCrash"
    private const val DIRECTORY = "crashes"
    private const val MAX_ENTRIES = 20
    private const val MAX_DETAIL_CHARS = 16 * 1024
    private const val HEADER_END = "----record----"

    /** What kind of death the record describes. */
    enum class Kind { CRASH, ANR, LOW_MEMORY, EXIT }

    /**
     * One persisted record.  [detail] is the ANR trace or the exception stack
     * trace when the system or the handler captured one.
     */
    data class Report(
        val atEpochMs: Long,
        val kind: Kind,
        val reason: String,
        val description: String?,
        val memoryLimiter: Boolean,
        val detail: String?
    ) {
        /** One line for a Doctor row or a log message. */
        fun summary(): String {
            val what = when (kind) {
                Kind.CRASH -> "crash"
                Kind.ANR -> "ANR"
                Kind.LOW_MEMORY -> "low memory"
                Kind.EXIT -> "exit"
            }
            val memory = if (memoryLimiter) ", memory limiter (${description.orEmpty()})" else ""
            return "$what at ${TIME.format(Date(atEpochMs))} ($reason$memory)"
        }
    }

    private val TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val installed = AtomicBoolean(false)
    private val lock = Any()

    /**
     * Installs the uncaught-exception recorder and stores the exit reasons the
     * system accumulated since the last launch.  Idempotent; the activity, the
     * service and the widget all start the same process, so the application
     * object calls this exactly once per process.
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            recordCrash(app, thread, error)
            previous?.uncaughtException(thread, error)
        }
        Thread {
            try {
                collectExitReasons(app)
            } catch (error: Exception) {
                BridgeLog.w(TAG, "exit reasons unreadable: ${error.javaClass.simpleName}")
            }
        }.apply { name = "phonxiety-exit-reasons" }.start()
    }

    /**
     * Reads the system's exit history and writes every record not already on
     * disk.  Safe to call repeatedly; a timestamp already present is skipped.
     */
    fun collectExitReasons(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_ENTRIES)
        }.getOrNull() ?: return
        val directory = directory(context)
        val known = synchronized(lock) { knownTimestamps(directory) }.toMutableSet()
        for (exit in exits) {
            val at = exit.timestamp
            if (at <= 0L || at in known) continue
            val record = reportOf(exit)
            if (write(directory, record)) known += at
        }
        trim(directory)
    }

    /** The newest stored record, or null when the app has never died. */
    fun latest(context: Context): Report? = recent(context, 1).firstOrNull()

    /** Up to [limit] records, newest first. */
    fun recent(context: Context, limit: Int = 5): List<Report> {
        val directory = directory(context)
        val files = synchronized(lock) {
            directory.listFiles { file -> file.isFile && file.name.endsWith(".txt") }
                ?.sortedByDescending { it.name }
                .orEmpty()
        }
        return files.asSequence().take(limit.coerceAtLeast(1)).mapNotNull { read(it) }.toList()
    }

    // -- writing ---------------------------------------------------------

    private fun reportOf(exit: ApplicationExitInfo): Report {
        val description = exit.description?.takeIf { it.isNotBlank() }
        val memoryLimiter = description != null &&
            (
                description.contains("MemoryLimiter", ignoreCase = true) ||
                    description.contains("AnonSwap", ignoreCase = true)
                )
        val reason = reasonName(exit.reason)
        val kind = when {
            exit.reason == ApplicationExitInfo.REASON_ANR -> Kind.ANR

            memoryLimiter || exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY -> Kind.LOW_MEMORY

            exit.reason == ApplicationExitInfo.REASON_CRASH ||
                exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                exit.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> Kind.CRASH

            else -> Kind.EXIT
        }
        return Report(
            atEpochMs = exit.timestamp,
            kind = kind,
            reason = reason,
            description = description,
            memoryLimiter = memoryLimiter,
            detail = traceOf(exit)
        )
    }

    private fun traceOf(exit: ApplicationExitInfo): String? {
        val stream = runCatching { exit.traceInputStream }.getOrNull() ?: return null
        return runCatching {
            stream.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.takeIf { it.isNotBlank() }?.take(MAX_DETAIL_CHARS)
    }

    private fun recordCrash(context: Context, thread: Thread, error: Throwable) {
        runCatching {
            val stack = StringWriter().also { buffer ->
                PrintWriter(buffer).use { writer -> error.printStackTrace(writer) }
            }.toString()
            val detail = "thread=${thread.name}\n$stack".take(MAX_DETAIL_CHARS)
            val directory = directory(context)
            write(
                directory,
                Report(
                    atEpochMs = System.currentTimeMillis(),
                    kind = Kind.CRASH,
                    reason = error.javaClass.name,
                    description = error.message,
                    memoryLimiter = false,
                    detail = detail
                )
            )
            // A crash loop must not fill the phone's storage.
            trim(directory)
        }.onFailure {
            android.util.Log.e(TAG, "crash record not written", it)
        }
    }

    private fun write(directory: File, report: Report): Boolean = synchronized(lock) {
        val target = File(directory, "${stamp(report.atEpochMs)}-${report.kind.name.lowercase(Locale.US)}.txt")
        when {
            target.exists() -> true

            else -> runCatching {
                AppStorage.writeAtomically(target, render(report).toByteArray(Charsets.UTF_8))
                true
            }.getOrElse {
                BridgeLog.w(TAG, "exit record not written: ${it.javaClass.simpleName}")
                false
            }
        }
    }

    private fun render(report: Report): String = buildString {
        appendLine("at=${report.atEpochMs}")
        appendLine("kind=${report.kind.name.lowercase(Locale.US)}")
        appendLine("reason=${report.reason}")
        appendLine("description=${report.description.orEmpty().replace('\n', ' ').replace('\r', ' ').take(500)}")
        appendLine("memory_limiter=${report.memoryLimiter}")
        appendLine(HEADER_END)
        report.detail?.let { append(it) }
    }

    // -- reading ---------------------------------------------------------

    private fun read(file: File): Report? = runCatching {
        val text = file.readText(Charsets.UTF_8)
        val split = text.indexOf("$HEADER_END\n")
        val header = if (split >= 0) text.substring(0, split) else text
        val detail = if (split >= 0) text.substring(split + HEADER_END.length + 1) else ""
        val fields = header.lineSequence()
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
            }
            .toMap()
        val at = fields["at"]?.toLongOrNull() ?: return null
        Report(
            atEpochMs = at,
            kind = Kind.entries.firstOrNull { it.name.equals(fields["kind"], ignoreCase = true) } ?: Kind.EXIT,
            reason = fields["reason"].orEmpty(),
            description = fields["description"]?.takeIf { it.isNotBlank() },
            memoryLimiter = fields["memory_limiter"] == "true",
            detail = detail.takeIf { it.isNotBlank() }
        )
    }.getOrNull()

    private fun knownTimestamps(directory: File): Set<Long> = directory
        .listFiles()
        .orEmpty()
        .mapNotNull { it.name.substringBefore('-').toLongOrNull() }
        .toSet()

    private fun trim(directory: File) {
        synchronized(lock) {
            directory.listFiles { file -> file.isFile && file.name.endsWith(".txt") }
                ?.sortedByDescending { it.name }
                ?.drop(MAX_ENTRIES)
                ?.forEach { it.delete() }
        }
    }

    private fun directory(context: Context): File {
        val base = AppStorage.deviceProtected(context)
        return File(base.filesDir, DIRECTORY).apply { mkdirs() }
    }

    private fun stamp(epochMs: Long): String = epochMs.toString().padStart(13, '0')

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        else -> "reason_$reason"
    }
}
