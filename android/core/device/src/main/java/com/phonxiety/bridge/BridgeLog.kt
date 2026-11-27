package com.phonxiety.bridge

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Every log line the bridge writes, kept in a bounded in-process ring so the test lab can show
 * the app's own recent history without `logcat` or a file.
 *
 * Each line goes to the [sink] and, when its level is at least [minimumLevel], into the ring.
 * [sinkLevel] decides what the sink receives; by default it accepts every level, exactly as
 * `logcat` did, while [minimumLevel] keeps its old meaning for the ring. The lazy overloads take
 * a lambda and build the text only when the sink or the ring will accept the line, so a hot path
 * can describe a message it usually never writes.
 *
 * The ring is process-wide and survives activity restarts, not process death: it is a debugging
 * surface, not a durable log.
 */
object BridgeLog {
    const val DEBUG = 0
    const val INFO = 1
    const val WARN = 2
    const val ERROR = 3

    /** Severity of one line, for a sink that wants the level as a value rather than a code. */
    enum class Level {
        DEBUG,
        INFO,
        WARN,
        ERROR;

        companion object {
            fun of(code: Int): Level = when (code) {
                0 -> DEBUG
                2 -> WARN
                3 -> ERROR
                else -> INFO
            }
        }
    }

    /** Where the lines are written; `logcat` by default, replaceable in tests and tools. */
    fun interface Sink {
        fun write(level: Level, tag: String, message: String, error: Throwable?)
    }

    /** One captured line: when it happened, how severe it is, its tag and its text. */
    data class Line(
        val atEpochMs: Long,
        val level: Int,
        val tag: String,
        val message: String
    ) {
        fun format(): String {
            val at = TIME.format(Instant.ofEpochMilli(atEpochMs).atZone(ZoneId.systemDefault()))
            return "$at ${levelLetter(level)}/$tag: $message"
        }
    }

    private const val CAPACITY = 2_000
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<Line>(CAPACITY)
    private val _revision = MutableStateFlow(0)

    /** Bumped for every captured line; a viewer collects it and then reads [snapshot]. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Lines the ring keeps; a change also applies to the log level setting. */
    @Volatile
    var minimumLevel: Int = INFO
        set(value) {
            field = value.coerceIn(DEBUG, ERROR)
        }

    /** Lowest level the [sink] receives. Every level by default, so `logcat` sees what it always did. */
    @Volatile
    var sinkLevel: Int = DEBUG
        set(value) {
            field = value.coerceIn(DEBUG, ERROR)
        }

    /** Writes one line to `logcat`; the process default when nothing replaced the sink. */
    internal val defaultSink: Sink = Sink { level, tag, message, error ->
        when (level) {
            Level.DEBUG -> android.util.Log.d(tag, message)

            Level.INFO -> android.util.Log.i(tag, message)

            Level.WARN -> android.util.Log.w(tag, message)

            Level.ERROR ->
                if (error != null) {
                    android.util.Log.e(tag, message, error)
                } else {
                    android.util.Log.e(tag, message)
                }
        }
    }

    /** Where lines go besides the ring; the default is `android.util.Log`. */
    @Volatile
    var sink: Sink = defaultSink

    fun d(tag: String, message: String) = write(DEBUG, tag, message)

    fun i(tag: String, message: String) = write(INFO, tag, message)

    fun w(tag: String, message: String) = write(WARN, tag, message)

    fun e(tag: String, message: String) = write(ERROR, tag, message)

    /**
     * An error with its cause. The sink (logcat) receives the original message, exactly as before
     * the ring existed; only the ring line carries the throwable's class, because the ring has no
     * stack trace of its own to show.
     */
    fun e(tag: String, message: String, throwable: Throwable) = write(ERROR, tag, message, "$message: ${throwable.javaClass.simpleName}", throwable)

    /** Debug line whose text is built only when the sink or the ring accepts it. */
    fun d(tag: String, message: () -> String) = writeLazily(DEBUG, tag, message)

    /** Info line whose text is built only when the sink or the ring accepts it. */
    fun i(tag: String, message: () -> String) = writeLazily(INFO, tag, message)

    /** Warning whose text is built only when the sink or the ring accepts it. */
    fun w(tag: String, message: () -> String) = writeLazily(WARN, tag, message)

    /** Error line whose text is built only when the sink or the ring accepts it. */
    fun e(tag: String, message: () -> String) = writeLazily(ERROR, tag, message)

    /** Every retained line, oldest first. */
    fun snapshot(): List<Line> = synchronized(lines) { lines.toList() }

    /** The retained lines as the text the log viewer copies and shares. */
    fun text(minimumLevel: Int = DEBUG): String = snapshot().filter { it.level >= minimumLevel }.joinToString("\n") { it.format() }

    fun clear() {
        synchronized(lines) { lines.clear() }
        _revision.update { it + 1 }
    }

    /** A code the log level setting accepts, or null when the value is unknown. */
    fun levelOf(name: String?): Int? = when (name?.trim()?.lowercase(Locale.US)) {
        "debug" -> DEBUG
        "info" -> INFO
        "warn" -> WARN
        "error" -> ERROR
        else -> null
    }

    fun levelName(level: Int): String = when (level) {
        DEBUG -> "debug"
        WARN -> "warn"
        ERROR -> "error"
        else -> "info"
    }

    private inline fun writeLazily(level: Int, tag: String, message: () -> String) {
        if (!accepted(level)) return
        val text = message()
        write(level, tag, text, text, null)
    }

    /** True when either consumer takes a line of [level]; the only gate a lazy message pays. */
    private fun accepted(level: Int): Boolean = level >= minimumLevel || level >= sinkLevel

    private fun write(level: Int, tag: String, message: String, error: Throwable? = null) = write(level, tag, message, message, error)

    /** [message] goes to the sink; [ring] is what the bounded ring keeps. */
    private fun write(level: Int, tag: String, message: String, ring: String, error: Throwable? = null) {
        if (level >= sinkLevel) sink.write(Level.of(level), tag, message, error)
        capture(level, tag, ring)
    }

    private fun levelLetter(level: Int): String = when (level) {
        DEBUG -> "D"
        WARN -> "W"
        ERROR -> "E"
        else -> "I"
    }

    private fun capture(level: Int, tag: String, message: String) {
        if (level < minimumLevel) return
        val line = Line(System.currentTimeMillis(), level, tag, message)
        synchronized(lines) {
            if (lines.size == CAPACITY) lines.removeFirst()
            lines.addLast(line)
        }
        _revision.update { it + 1 }
    }
}
