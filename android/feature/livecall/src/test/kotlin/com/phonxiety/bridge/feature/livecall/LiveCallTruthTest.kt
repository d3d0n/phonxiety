package com.phonxiety.bridge.feature.livecall

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.model.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the live call screen says: the phase the daemon uses, the words a person reads, the timer
 * that stops with the call, and a phase that never claims a live call while the link is down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveCallTruthTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val live = LiveCallSamples.live
    private val ended = LiveCallSamples.ended

    @Test
    fun theTimerRunsWhileTheCallDoes() {
        // Started at 1_764_000_005_000; 39 s later the screen still counts, in MM:SS.
        assertEquals("00:39", formatTimer(live, nowEpochMs = 1_764_000_044_000L))
    }

    @Test
    fun theTimerStopsWhenTheCallEnded() {
        // The call ended at 1_764_000_052_000; a tick minutes later must not move the timer.
        assertEquals("00:47", formatTimer(ended, nowEpochMs = 1_764_000_200_000L))
    }

    @Test
    fun aLongCallReadsHoursInThePlatformFormat() {
        val hourAndNine = live.copy(connectedAtMs = 0L)
        assertEquals("1:01:09", formatTimer(hourAndNine, nowEpochMs = 3_669_000L))
    }

    @Test
    fun theDaemonsRoleWordsBecomeSpeakerLabels() {
        assertEquals("Agent", speakerLabel("assistant", resources))
        assertEquals("Far end", speakerLabel("user", resources))
        // Records written before the role vocabulary settled still read correctly.
        assertEquals("Agent", speakerLabel("agent", resources))
        assertEquals("Far end", speakerLabel("far_end", resources))
        assertEquals("Supervisor", speakerLabel("supervisor", resources))
        assertEquals("Someone", speakerLabel(null, resources))
    }

    @Test
    fun theHeaderSaysThePhaseNotTheAgentActivity() {
        // The daemon reports "idle" whenever no review is running; that is not the call's phase.
        val idle = live.copy(state = "active", activity = "idle")
        assertEquals("Live", phaseLabel(idle, resources))
        assertEquals("Dialing", phaseLabel(live.copy(state = "dialing", activity = "idle"), resources))
        assertEquals("Ended", phaseLabel(ended.copy(activity = "idle"), resources))
    }

    @Test
    fun aDroppedLinkNeverClaimsALiveCall() {
        val reconnecting = live.copy(state = "active", reconnecting = true)
        assertEquals("Reconnecting", statusLabel(reconnecting, resources))
        assertNotEquals("Live", statusLabel(reconnecting, resources))

        // Once the link is back the phase word returns.
        assertEquals("Live", statusLabel(live, resources))
    }

    @Test
    fun thePhonePhaseFillsInUntilTheDaemonReports() {
        val local = live.copy(state = null, localPhase = CallPhase.DIALING)
        assertEquals("dialing", local.phase)
        assertEquals("Dialing", phaseLabel(local, resources))

        val remote = live.copy(state = "active", localPhase = CallPhase.ENDING)
        assertEquals("active", remote.phase)
    }

    @Test
    fun thePhoneAdvancesAStalePreparingRecord() {
        // Telecom dials before the daemon's snapshot makes the round trip: a record still saying
        // "preparing" must not keep "Preparing" on screen while the phone already dials.
        assertEquals("dialing", LiveCallUiState(state = "preparing", localPhase = CallPhase.DIALING).phase)
        assertEquals("active", LiveCallUiState(state = "preparing", localPhase = CallPhase.ACTIVE).phase)
        // A terminal daemon state is the call's own end: the phone cannot advance past it.
        assertEquals("ended", LiveCallUiState(state = "ended", localPhase = CallPhase.ACTIVE).phase)
        // A daemon phase the phone cannot contradict stays as recorded.
        assertEquals("ending", LiveCallUiState(state = "ending", localPhase = CallPhase.ACTIVE).phase)
        assertEquals("dialing", LiveCallUiState(state = "dialing", localPhase = CallPhase.DIALING).phase)
        // A phone that already ended the call is not still "Preparing" it either.
        assertEquals("ended", LiveCallUiState(state = "preparing", localPhase = CallPhase.ENDED).phase)
    }

    @Test
    fun anUnconfirmedEndIsNotCalledEnded() {
        val unknown = LiveCallUiState(state = "unknown", ended = true, turns = ended.turns)
        assertEquals("Unknown", phaseLabel(unknown, resources))
        assertEquals("The call's end was not confirmed · 5 turns", transcriptSubtitle(unknown, resources))
        assertNotEquals(transcriptSubtitle(ended, resources), transcriptSubtitle(unknown, resources))
        assertEquals("active", LiveCallUiState(state = "active", localPhase = CallPhase.ENDING).phase)
    }

    @Test
    fun theSpokenDurationIsWordsNotDigits() {
        assertEquals(
            "Call duration 1 minute 9 seconds",
            spokenDuration(69, resources)
        )
        assertEquals("Call duration 45 seconds", spokenDuration(45, resources))
        assertEquals("Call duration 0 seconds", spokenDuration(0, resources))
        assertEquals(
            "Call duration 1 hour 1 minute 1 second",
            spokenDuration(3_661, resources)
        )
    }

    @Test
    fun playbackDropsAreReportedAsAudioTime() {
        assertEquals(0L, playbackDropMillis(0))
        assertEquals(1_000L, playbackDropMillis(48_000))
        assertEquals(5_319L, playbackDropMillis(255_329))
    }

    @Test
    fun daemonTokensReadAsWords() {
        assertEquals("Reservation time", humanizeToken("reservation_time"))
        assertEquals("Follow up", humanizeToken("follow-up"))
        assertEquals("Ready", humanizeToken("ready"))
    }
}
