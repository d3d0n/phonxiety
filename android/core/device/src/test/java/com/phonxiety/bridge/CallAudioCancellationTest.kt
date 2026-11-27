package com.phonxiety.bridge

import com.phonxiety.bridge.engine.CallSession.OwnedCall
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone side of audio cancellation.  Routine supervision (`cancel_actions`,
 * sent by a review, a delegation or a keypress) exists to interrupt the DTMF
 * tone an action started; the Lab test tone belongs to its own scheduled
 * `tone_stop`, so a running probe must survive the supervision.  Teardown still
 * stops everything.
 */
class CallAudioCancellationTest {
    private class Counter {
        var calls = 0
    }

    private fun cancellation(action: Counter, lab: Counter) = CallAudioCancellation({ action.calls++ }, { lab.calls++ })

    private fun call() = OwnedCall("call-1", "+998900000000")

    @Test
    fun routineCancelActionsStopsOnlyTheActionTone() {
        val action = Counter()
        val lab = Counter()

        cancellation(action, lab).cancelActions(call())

        assertEquals(1, action.calls)
        assertEquals(0, lab.calls)
    }

    @Test
    fun teardownStopsTheLabToneToo() {
        val action = Counter()
        val lab = Counter()

        cancellation(action, lab).stopAll(call())

        assertEquals(1, action.calls)
        assertEquals(1, lab.calls)
    }
}
