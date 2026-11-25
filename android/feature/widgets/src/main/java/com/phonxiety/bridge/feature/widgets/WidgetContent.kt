package com.phonxiety.bridge.feature.widgets

import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallSummary
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import java.util.Locale

/** Semantic colour of the widget's dot; the resource decides the actual colour. */
enum class WidgetTone { GOOD, WARN, BAD, IDLE }

/** The call line: who, and how long it has been up. */
data class WidgetCall(val title: String, val elapsed: String)

/** Everything the widget draws, derived from one state sample. */
data class WidgetContent(
    val linkLabel: String,
    val pathLabel: String,
    val call: WidgetCall?,
    val footer: String,
    val tone: WidgetTone
)

/**
 * Maps the bridge state onto the four lines a 2×1 widget can hold. Pure and deterministic, so the
 * widget's behaviour is unit-tested without a launcher; [nowEpochMs] only feeds the call timer.
 */
object WidgetContentMapper {
    fun map(
        bridge: BridgeState,
        paired: Boolean,
        lastCall: CallSummary?,
        nowEpochMs: Long
    ): WidgetContent {
        if (!paired) {
            return WidgetContent(
                linkLabel = "Not paired",
                pathLabel = "",
                call = null,
                footer = "Pair with your computer to link this phone",
                tone = WidgetTone.IDLE
            )
        }
        val link = bridge.link
        val (linkLabel, pathLabel, tone) = when (link) {
            is LinkState.Up -> Triple(
                "Linked",
                buildString {
                    append(
                        when (link.path) {
                            PathKind.DIRECT -> "direct"
                            PathKind.RELAY -> "relay"
                            PathKind.UNKNOWN -> "path unknown"
                        }
                    )
                    link.rttMs?.let { append(" · $it ms") }
                },
                if (link.path == PathKind.DIRECT) WidgetTone.GOOD else WidgetTone.WARN
            )

            is LinkState.Connecting -> Triple("Connecting", link.candidate.name.lowercase(Locale.ROOT), WidgetTone.WARN)

            LinkState.Offline -> Triple(
                "Offline",
                bridge.lastError?.code ?: "no path",
                WidgetTone.BAD
            )
        }
        val call = bridge.call?.takeIf { it.phase != CallPhase.ENDED }?.let { current ->
            WidgetCall(
                title = current.destinationMasked,
                elapsed = elapsed(current.startedAtEpochMs, nowEpochMs)
            )
        }
        val footer = when {
            call != null -> "Tap to open · hang up from the button"
            lastCall != null -> lastCallText(lastCall)
            else -> "No calls yet"
        }
        return WidgetContent(linkLabel, pathLabel, call, footer, tone)
    }

    /** `MM:SS`, or `H:MM:SS` once a call passes the hour. */
    fun elapsed(startedAtEpochMs: Long?, nowEpochMs: Long): String {
        val start = startedAtEpochMs ?: return "00:00"
        val seconds = ((nowEpochMs - start) / 1000).coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val rest = seconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, rest)
        }
    }

    private fun lastCallText(summary: CallSummary): String {
        val outcome = summary.callOutcome ?: summary.taskOutcome ?: summary.state ?: "ended"
        val duration = summary.durationSeconds?.let { seconds ->
            elapsed(startedAtEpochMs = 0L, nowEpochMs = (seconds * 1000).toLong())
        }
        return "last: $outcome" + if (duration != null) " · $duration" else ""
    }
}
