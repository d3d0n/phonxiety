package com.phonxiety.bridge.data

import com.phonxiety.bridge.model.CallPhase

/**
 * Who spoke one transcript turn, in the app's own vocabulary.
 *
 * The voice backends report `assistant` and `user` (`gemini.py`, `deepgram.py`), the controller
 * stores those words unchanged (`controller._transcript`) and the events long-poll forwards them
 * (`app_api._sync`). Older archives still carry `agent` and `far_end`, so both spellings mean the
 * same two speakers.
 */
enum class Speaker { AGENT, FAR_END, OTHER }

/** Maps a stored or streamed `role` field to the speaker the screen draws. */
fun speakerOf(role: String?): Speaker = when (role?.trim()?.lowercase()) {
    "assistant", "agent" -> Speaker.AGENT
    "user", "far_end", "far-end" -> Speaker.FAR_END
    else -> Speaker.OTHER
}

/**
 * The daemon's call-state word (`preparing`, `dialing`, `active`, `ending`, `ended`) for the
 * phone's own [CallPhase]. The engine publishes its phase immediately when Telecom dials, so the
 * screen has a phase before the first call snapshot or event arrives.
 */
fun daemonCallState(phase: CallPhase?): String? = when (phase) {
    CallPhase.DIALING -> "dialing"
    CallPhase.ACTIVE -> "active"
    CallPhase.ENDING -> "ending"
    CallPhase.ENDED -> "ended"
    null -> null
}

/** [com.phonxiety.bridge.model.AudioState.bitrate] is bits per second; the screen says kbit/s. */
fun bitrateKbps(bitrateBps: Int?): Int? = bitrateBps?.let { it / 1_000 }
