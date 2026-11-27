package com.phonxiety.bridge.data

import com.phonxiety.bridge.model.CallMark
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.DeleteCallResult
import com.phonxiety.bridge.model.MarksResponse
import com.phonxiety.bridge.model.PauseResult

/*
 * The write verbs of `/app/v1`, each with one sealed answer.
 *
 * The daemon's status and code are read exactly once, here, and every consumer matches the arm
 * instead of re-deriving meaning from a `code` string. Every verb keeps an [Unknown] arm that
 * carries the daemon's own code and message, so a code this build has never seen still reaches the
 * person with its evidence intact instead of being flattened into a generic failure.
 */

/** Deleting one call's record and its archived files. */
sealed interface DeleteOutcome {
    val status: Int

    /** The daemon removed the call. */
    data class Deleted(override val status: Int) : DeleteOutcome

    /** The daemon has no such archived call. */
    data class NotFound(override val status: Int, val code: String, val message: String) : DeleteOutcome

    /** The call is still live; the daemon asks for a hang-up first. */
    data class CallActive(override val status: Int, val code: String, val message: String) : DeleteOutcome

    /** Any other daemon answer, kept word for word. */
    data class Unknown(override val status: Int, val code: String, val message: String) : DeleteOutcome
}

/** Adding one mark to a call. */
sealed interface MarkOutcome {
    val status: Int

    /** The daemon stored the mark; [marks] is the call's marks afterwards. */
    data class Marked(override val status: Int, val marks: List<CallMark>) : MarkOutcome

    /** The call the mark was meant for does not exist. */
    data class CallNotFound(override val status: Int, val code: String, val message: String) : MarkOutcome

    /** The daemon refused the mark's kind or note. */
    data class Refused(override val status: Int, val code: String, val message: String) : MarkOutcome

    /** Any other daemon answer, kept word for word. */
    data class Unknown(override val status: Int, val code: String, val message: String) : MarkOutcome
}

/** Ending one call through the daemon. */
sealed interface HangUpOutcome {
    val status: Int

    /** The daemon took the hang-up; [snapshot] is the record as it answered. */
    data class HungUp(override val status: Int, val snapshot: CallSnapshot) : HangUpOutcome

    /** Any other daemon answer, kept word for word. */
    data class Unknown(override val status: Int, val code: String, val message: String) : HangUpOutcome
}

/** Withholding the agent's speech from the call. */
sealed interface PauseOutcome {
    val status: Int

    data class Paused(override val status: Int, val result: PauseResult) : PauseOutcome

    /** The call is no longer live, so there is no speech to withhold. */
    data class NotActive(override val status: Int, val code: String, val message: String) : PauseOutcome

    /** Any other daemon answer, kept word for word. */
    data class Unknown(override val status: Int, val code: String, val message: String) : PauseOutcome
}

/** Letting the agent's speech reach the call again. */
sealed interface ResumeOutcome {
    val status: Int

    data class Resumed(override val status: Int, val result: PauseResult) : ResumeOutcome

    /** The call is no longer live, so there is no speech to resume. */
    data class NotActive(override val status: Int, val code: String, val message: String) : ResumeOutcome

    /** Any other daemon answer, kept word for word. */
    data class Unknown(override val status: Int, val code: String, val message: String) : ResumeOutcome
}

fun ApiOutcome<DeleteCallResult>.asDeleteOutcome(): DeleteOutcome = when (this) {
    is ApiOutcome.Ok -> DeleteOutcome.Deleted(status)

    is ApiOutcome.Failure -> when (code) {
        "call_not_found" -> DeleteOutcome.NotFound(status, code, message)
        "call_active" -> DeleteOutcome.CallActive(status, code, message)
        else -> DeleteOutcome.Unknown(status, code, message)
    }
}

fun ApiOutcome<MarksResponse>.asMarkOutcome(): MarkOutcome = when (this) {
    is ApiOutcome.Ok -> MarkOutcome.Marked(status, value.marks)

    is ApiOutcome.Failure -> when (code) {
        "call_not_found" -> MarkOutcome.CallNotFound(status, code, message)
        "invalid_argument" -> MarkOutcome.Refused(status, code, message)
        else -> MarkOutcome.Unknown(status, code, message)
    }
}

fun ApiOutcome<CallSnapshot>.asHangUpOutcome(): HangUpOutcome = when (this) {
    is ApiOutcome.Ok -> HangUpOutcome.HungUp(status, value)
    is ApiOutcome.Failure -> HangUpOutcome.Unknown(status, code, message)
}

fun ApiOutcome<PauseResult>.asPauseOutcome(): PauseOutcome = when (this) {
    is ApiOutcome.Ok -> PauseOutcome.Paused(status, value)

    is ApiOutcome.Failure -> when (code) {
        "call_not_active", "call_not_found" -> PauseOutcome.NotActive(status, code, message)
        else -> PauseOutcome.Unknown(status, code, message)
    }
}

fun ApiOutcome<PauseResult>.asResumeOutcome(): ResumeOutcome = when (this) {
    is ApiOutcome.Ok -> ResumeOutcome.Resumed(status, value)

    is ApiOutcome.Failure -> when (code) {
        "call_not_active", "call_not_found" -> ResumeOutcome.NotActive(status, code, message)
        else -> ResumeOutcome.Unknown(status, code, message)
    }
}
