package com.phonxiety.bridge

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Every log line the bridge writes, kept in a bounded in-process ring so the test lab can show
 * the app's own recent history without `logcat` or a file.  Lines still reach `android.util.Log`
 * unchanged; [minimumLevel] only decides what the ring keeps, which is what the log level setting
 * asks for.
 *
 * The ring is process-wide and survives activity restarts, not process death: it is a debugging
 * surface, not a durable log.
 */
object BridgeLog {
    const val DEBUG = 0
    const val INFO = 1
    const val WARN = 2
    const val ERROR = 3

    /** One captured line: when it happened, how severe it is, its tag and its text. */
    data class Line(
        val atEpochMs: Long,
        val level: Int,
        val tag: String,
        val message: String
    ) {
        fun format(): String = "${TIME.format(Date(atEpochMs))} ${levelLetter(level)}/$tag: $message"
    }

    private const val CAPACITY = 2_000
    private val TIME = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
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

    fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
        capture(DEBUG, tag, message)
    }

    fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
        capture(INFO, tag, message)
    }

    fun w(tag: String, message: String) {
        android.util.Log.w(tag, message)
        capture(WARN, tag, message)
    }

    fun e(tag: String, message: String) {
        android.util.Log.e(tag, message)
        capture(ERROR, tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        android.util.Log.e(tag, message, throwable)
        capture(ERROR, tag, "$message: ${throwable.javaClass.simpleName}")
    }

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
