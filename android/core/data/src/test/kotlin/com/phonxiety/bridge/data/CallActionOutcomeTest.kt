package com.phonxiety.bridge.data

import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.DeleteCallResult
import com.phonxiety.bridge.model.MarksResponse
import com.phonxiety.bridge.model.PauseResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The write verbs are translated once, here: each daemon code becomes its arm and anything this
 * build does not know keeps the daemon's code and message instead of disappearing.
 */
class CallActionOutcomeTest {

    @Test
    fun deleteMapsItsCodesAndKeepsAnUnknownOne() {
        assertEquals(DeleteOutcome.Deleted(200), ApiOutcome.Ok(200, DeleteCallResult()).asDeleteOutcome())
        assertEquals(
            DeleteOutcome.NotFound(404, "call_not_found", "no archived call"),
            ApiOutcome.Failure(404, "call_not_found", "no archived call").asDeleteOutcome()
        )
        assertEquals(
            DeleteOutcome.CallActive(409, "call_active", "hang up first"),
            ApiOutcome.Failure(409, "call_active", "hang up first").asDeleteOutcome()
        )
        assertEquals(
            DeleteOutcome.Unknown(500, "archive_locked", "the archive is busy"),
            ApiOutcome.Failure(500, "archive_locked", "the archive is busy").asDeleteOutcome()
        )
    }

    @Test
    fun markMapsItsCodesAndKeepsAnUnknownOne() {
        assertEquals(
            MarkOutcome.Marked(200, listOf(com.phonxiety.bridge.model.CallMark("agreed"))),
            ApiOutcome.Ok(200, MarksResponse(marks = listOf(com.phonxiety.bridge.model.CallMark("agreed")))).asMarkOutcome()
        )
        assertEquals(
            MarkOutcome.CallNotFound(404, "call_not_found", "no call"),
            ApiOutcome.Failure(404, "call_not_found", "no call").asMarkOutcome()
        )
        assertEquals(
            MarkOutcome.Refused(400, "invalid_argument", "bad mark"),
            ApiOutcome.Failure(400, "invalid_argument", "bad mark").asMarkOutcome()
        )
        assertEquals(
            MarkOutcome.Unknown(500, "archive_write_failed", "the mark could not be stored"),
            ApiOutcome.Failure(500, "archive_write_failed", "the mark could not be stored").asMarkOutcome()
        )
    }

    @Test
    fun hangUpKeepsTheRecordOnSuccessAndTheCodeOnAnUnknownFailure() {
        val snapshot = CallSnapshot(callId = "call-1")
        assertEquals(
            HangUpOutcome.HungUp(200, snapshot),
            ApiOutcome.Ok(200, snapshot).asHangUpOutcome()
        )
        assertEquals(
            HangUpOutcome.Unknown(500, "hangup_failed", "no line"),
            ApiOutcome.Failure(500, "hangup_failed", "no line").asHangUpOutcome()
        )
    }

    @Test
    fun pauseAndResumeHaveTheirOwnArms() {
        assertEquals(
            PauseOutcome.Paused(200, PauseResult(muted = true)),
            ApiOutcome.Ok(200, PauseResult(muted = true)).asPauseOutcome()
        )
        assertEquals(
            PauseOutcome.NotActive(409, "call_not_active", "the call is over"),
            ApiOutcome.Failure(409, "call_not_active", "the call is over").asPauseOutcome()
        )
        assertEquals(
            PauseOutcome.Unknown(500, "audio_failed", "no audio path"),
            ApiOutcome.Failure(500, "audio_failed", "no audio path").asPauseOutcome()
        )

        assertEquals(
            ResumeOutcome.Resumed(200, PauseResult(muted = false)),
            ApiOutcome.Ok(200, PauseResult(muted = false)).asResumeOutcome()
        )
        assertEquals(
            ResumeOutcome.NotActive(409, "call_not_active", "the call is over"),
            ApiOutcome.Failure(409, "call_not_active", "the call is over").asResumeOutcome()
        )
        assertEquals(
            ResumeOutcome.Unknown(500, "audio_failed", "no audio path"),
            ApiOutcome.Failure(500, "audio_failed", "no audio path").asResumeOutcome()
        )
    }
}
