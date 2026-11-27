package com.phonxiety.bridge.feature.livecall

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.phonxiety.bridge.data.Speaker
import com.phonxiety.bridge.data.speakerOf
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.designsystem.formatElapsedTime

/** The playback ring is 48 kHz mono, so one millisecond of downlink audio is 48 samples. */
private const val AUDIO_SAMPLES_PER_MILLISECOND = 48L
internal const val UNIT_MILLISECONDS = "ms"
internal const val UNIT_KILOBITS = "kbit/s"
internal const val MISSING_VALUE = "—"
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L

/** The seconds since the line connected, or the connected duration the call ended at. */
internal fun elapsedSeconds(state: LiveCallUiState, nowEpochMs: Long): Long? {
    // Before the line connects the header still counts from placement, so the ring is visible;
    // once it is up the count is the conversation, which is what the system's call chip shows.
    val start = state.timerStartMs ?: return null
    val end = (if (state.ended) state.endedAtMs else null) ?: nowEpochMs
    return ((end - start).coerceAtLeast(0)) / 1000
}

/** `MM:SS`, or `H:MM:SS` past an hour, from the platform formatter. */
internal fun formatTimer(state: LiveCallUiState, nowEpochMs: Long): String = elapsedSeconds(state, nowEpochMs)?.let(::formatElapsedTime) ?: MISSING_VALUE

/** "Call duration 1 minute 9 seconds": what TalkBack says instead of reading "1:09" as digits. */
internal fun spokenDuration(
    seconds: Long,
    resources: Resources
): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val rest = total % SECONDS_PER_MINUTE
    val words = buildList {
        if (hours > 0) {
            add(resources.getQuantityString(R.plurals.live_call_duration_hours, hours.toInt(), hours.toInt()))
        }
        if (minutes > 0) {
            add(
                resources.getQuantityString(
                    R.plurals.live_call_duration_minutes,
                    minutes.toInt(),
                    minutes.toInt()
                )
            )
        }
        if (rest > 0 || isEmpty()) {
            add(resources.getQuantityString(R.plurals.live_call_duration_seconds, rest.toInt(), rest.toInt()))
        }
    }
    return resources.getString(R.string.live_call_duration_description, words.joinToString(" "))
}

@Composable
internal fun spokenDuration(seconds: Long): String = spokenDuration(seconds, LocalResources.current)

/** `assistant`/`user` are the daemon's words for the two speakers; older records say `agent`/`far_end`. */
@Composable
internal fun speakerLabel(role: String?): String = speakerLabel(role, LocalResources.current)

internal fun speakerLabel(role: String?, resources: Resources): String = when (speakerOf(role)) {
    Speaker.AGENT -> resources.getString(R.string.live_call_role_agent)

    Speaker.FAR_END -> resources.getString(R.string.live_call_role_far_end)

    Speaker.OTHER -> role?.trim()?.takeIf { it.isNotEmpty() }
        ?.replace('_', ' ')
        ?.replaceFirstChar { it.uppercase() }
        ?: resources.getString(R.string.live_call_role_someone)
}

/** The pill never says a call is live while the link it reports through is down. */
internal fun statusLabel(state: LiveCallUiState, resources: Resources): String = if (state.reconnecting) {
    resources.getString(R.string.live_call_phase_reconnecting)
} else {
    phaseLabel(state, resources)
}

internal fun phaseLabel(state: LiveCallUiState, resources: Resources): String = when (state.phase) {
    "preparing" -> resources.getString(R.string.live_call_phase_preparing)

    "dialing" -> resources.getString(R.string.live_call_phase_dialing)

    "active" -> resources.getString(R.string.live_call_phase_live)

    "ending" -> resources.getString(R.string.live_call_phase_ending)

    "ended" -> resources.getString(R.string.live_call_phase_ended)

    "cancelled" -> resources.getString(R.string.live_call_phase_canceled)

    "failed" -> resources.getString(R.string.live_call_phase_failed)

    "unknown" -> resources.getString(R.string.live_call_phase_unknown)

    else -> resources.getString(
        if (state.loaded) R.string.live_call_phase_idle else R.string.live_call_phase_loading
    )
}

@Composable
internal fun statusLabel(state: LiveCallUiState): String = statusLabel(state, LocalResources.current)

internal fun phaseTone(state: LiveCallUiState): StatusTone = when (state.phase) {
    "active" -> StatusTone.POSITIVE
    "dialing", "preparing", "ending" -> StatusTone.WARNING
    "failed", "cancelled" -> StatusTone.NEGATIVE
    else -> StatusTone.NEUTRAL
}

/** Playback overflows are counted in samples; the tile reports the audio time they cost. */
internal fun playbackDropMillis(samples: Long): Long = samples.coerceAtLeast(0) / AUDIO_SAMPLES_PER_MILLISECOND

/** A daemon token the screen shows as words: `reservation_time` → `Reservation time`. */
internal fun humanizeToken(token: String): String = token.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }
