package com.phonxiety.bridge

import com.phonxiety.bridge.engine.CallSession

/**
 * Which audio a command may stop.
 *
 * `cancel_actions` exists to interrupt what an action started — the DTMF tone of
 * a keypress.  The Lab test tone is started and stopped by its own
 * `tone_start`/`tone_stop` pair, so routine supervision must leave it running
 * until its scheduled stop; an explicit stop or any teardown still stops both.
 */
internal class CallAudioCancellation(
    private val stopActionAudio: (CallSession.OwnedCall) -> Unit,
    private val stopLabTone: (CallSession.OwnedCall) -> Unit
) {
    /** A routine `cancel_actions`: only the tone the action itself started. */
    fun cancelActions(call: CallSession.OwnedCall) {
        stopActionAudio(call)
    }

    /** An explicit stop or a teardown: the action's tone and the Lab tone. */
    fun stopAll(call: CallSession.OwnedCall) {
        stopActionAudio(call)
        stopLabTone(call)
    }
}
