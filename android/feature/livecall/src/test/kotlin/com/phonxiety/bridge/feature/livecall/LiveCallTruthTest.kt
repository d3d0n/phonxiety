package com.phonxiety.bridge.feature.livecall

import com.phonxiety.bridge.model.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the live call screen says: the phase the daemon uses, the speaker labels it has to
 * translate, and a timer that stops with the call.
 */
class LiveCallTruthTest {
    private val live = LiveCallSamples.live
    private val ended = LiveCallSamples.ended

    @Test
    fun theTimerRunsWhileTheCallDoes() {
        // Started at 1_764_000_005_000; 39 s later the screen still counts.
        assertEquals("0:39", formatTimer(live, nowEpochMs = 1_764_000_044_000L))
    }

    @Test
    fun theTimerStopsWhenTheCallEnded() {
        // The call ended at 1_764_000_052_000; a tick minutes later must not move the timer.
        assertEquals("0:47", formatTimer(ended, nowEpochMs = 1_764_000_200_000L))
    }

    @Test
    fun theDaemonsRoleWordsBecomeSpeakerLabels() {
        assertEquals("Agent", speakerLabel("assistant"))
        assertEquals("Far end", speakerLabel("user"))
        // Records written before the role vocabulary settled still read correctly.
        assertEquals("Agent", speakerLabel("agent"))
        assertEquals("Far end", speakerLabel("far_end"))
        assertEquals("Supervisor", speakerLabel("supervisor"))
        assertEquals("Someone", speakerLabel(null))
    }

    @Test
    fun theHeaderSaysThePhaseNotTheAgentActivity() {
        // The daemon reports "idle" whenever no review is running; that is not the call's phase.
        val idle = live.copy(state = "active", activity = "idle")
        assertEquals("Live", phaseLabel(idle))
        assertEquals("Dialing", phaseLabel(live.copy(state = "dialing", activity = "idle")))
        assertEquals("Ended", phaseLabel(ended.copy(activity = "idle")))
    }

    @Test
    fun thePhonePhaseFillsInUntilTheDaemonReports() {
        val local = live.copy(state = null, localPhase = CallPhase.DIALING)
        assertEquals("dialing", local.phase)
        assertEquals("Dialing", phaseLabel(local))

        val remote = live.copy(state = "active", localPhase = CallPhase.ENDING)
        assertEquals("active", remote.phase)
    }
}
