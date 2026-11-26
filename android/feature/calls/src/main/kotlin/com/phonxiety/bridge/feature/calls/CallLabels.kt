package com.phonxiety.bridge.feature.calls

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.phonxiety.bridge.designsystem.UiText

/**
 * A failure as the screen shows it: the sentence a person reads, and the daemon's code kept apart
 * so it can sit behind "Show details" in monospace instead of inside the sentence.
 */
data class CallsFailure(val message: UiText, val code: String?) {
    companion object {
        fun of(message: String, code: String?): CallsFailure = CallsFailure(UiText.Literal(message), code)
    }
}

/**
 * The daemon's call outcome in words. The stored value stays the filter key; only the label is a
 * resource. An outcome this build does not know is still readable: underscores become spaces.
 */
@Composable
internal fun outcomeLabel(outcome: String?): String? = when (outcome?.lowercase()) {
    null -> null
    "answered" -> stringResource(R.string.calls_outcome_answered)
    "no_answer" -> stringResource(R.string.calls_outcome_no_answer)
    "busy" -> stringResource(R.string.calls_outcome_busy)
    "declined" -> stringResource(R.string.calls_outcome_declined)
    "disconnected" -> stringResource(R.string.calls_outcome_disconnected)
    "cancelled", "canceled" -> stringResource(R.string.calls_outcome_canceled)
    "failed" -> stringResource(R.string.calls_outcome_failed)
    else -> humanize(outcome)
}

/** The call phase in words; "active" is the live phase and reads "In progress". */
@Composable
internal fun stateLabel(state: String?): String? = when (state?.lowercase()) {
    null -> null
    "preparing" -> stringResource(R.string.calls_state_preparing)
    "dialing" -> stringResource(R.string.calls_state_dialing)
    "ringing" -> stringResource(R.string.calls_state_ringing)
    "active", "in_progress" -> stringResource(R.string.calls_state_in_progress)
    "ending" -> stringResource(R.string.calls_state_ending)
    "ended" -> stringResource(R.string.calls_state_ended)
    "cancelled", "canceled" -> stringResource(R.string.calls_state_canceled)
    "failed" -> stringResource(R.string.calls_state_failed)
    else -> humanize(state)
}

/** What the brief's answers are: "ready" alone says nothing, "Summary ready" does. */
@Composable
internal fun resultLabel(status: String?): String? = when (status?.lowercase()) {
    null -> null
    "ready" -> stringResource(R.string.call_detail_result_ready)
    "pending" -> stringResource(R.string.call_detail_result_pending)
    "failed" -> stringResource(R.string.call_detail_result_failed)
    else -> humanize(status)
}

/** Who ended the call: the daemon's cause code as a person would say it. */
@Composable
internal fun causeLabel(cause: String?): String = when (cause?.uppercase()) {
    "LOCAL", "PHONE" -> stringResource(R.string.call_detail_cause_this_phone)
    "REMOTE", "FAR_END", "FAREND" -> stringResource(R.string.call_detail_cause_far_end)
    "TIMEOUT", "RING_TIMEOUT" -> stringResource(R.string.call_detail_cause_ring_timeout)
    null -> stringResource(R.string.calls_status_unknown)
    else -> humanize(cause)
}

/** What answered at the far end: a person, a menu or a mailbox. */
@Composable
internal fun farEndLabel(farEnd: String): String = when (farEnd.lowercase()) {
    "person" -> stringResource(R.string.calls_far_end_person)
    "ivr" -> stringResource(R.string.calls_far_end_ivr)
    "voicemail" -> stringResource(R.string.calls_far_end_voicemail)
    else -> humanize(farEnd)
}

/**
 * The recording card's subtitle follows the download: it offers the download while only the bridge
 * has the file, reports the transfer while it runs, and claims the file on this phone only once one
 * is here.
 */
@StringRes
internal fun recordingSubtitle(audio: CallAudioUiState): Int = when {
    audio.filePath != null -> R.string.call_detail_recording_subtitle_saved
    audio.downloading -> R.string.call_detail_recording_downloading
    else -> R.string.call_detail_recording_subtitle_remote
}

/** A mark's kind in words; the stored key stays "follow-up". */
@Composable
internal fun markLabel(mark: String): String = when (mark.lowercase()) {
    "agreed" -> stringResource(R.string.call_detail_mark_agreed)
    "refused" -> stringResource(R.string.call_detail_mark_refused)
    "follow-up", "follow_up" -> stringResource(R.string.call_detail_mark_follow_up)
    else -> humanize(mark)
}

/**
 * A daemon value this build has no words for: still readable, never an underscore in the UI. This
 * is the last resort for a value the daemon may add; known values always get a resource.
 */
internal fun humanize(value: String): String {
    val words = value.replace('_', ' ').replace('-', ' ').trim()
    if (words.isEmpty()) return value
    return words.replaceFirstChar { it.uppercase() }
}
