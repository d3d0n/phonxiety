package com.phonxiety.bridge

/**
 * Which audio a command may stop.
 *
 * `cancel_actions` exists to interrupt what an action started — the DTMF tone of
 * a keypress.  The Lab test tone is started and stopped by its own
 * `tone_start`/`tone_stop` pair, so routine supervision must leave it running
 * until its scheduled stop; an explicit stop or any teardown still stops both.
 */
internal class CallAudioCancellation(
    private val stopActionAudio: (BridgeEngine.OwnedCall) -> Unit,
    private val stopLabTone: (BridgeEngine.OwnedCall) -> Unit
) {
    /** A routine `cancel_actions`: only the tone the action itself started. */
    fun cancelActions(call: BridgeEngine.OwnedCall) {
        stopActionAudio(call)
    }

    /** An explicit stop or a teardown: the action's tone and the Lab tone. */
    fun stopAll(call: BridgeEngine.OwnedCall) {
        stopActionAudio(call)
        stopLabTone(call)
    }
}
