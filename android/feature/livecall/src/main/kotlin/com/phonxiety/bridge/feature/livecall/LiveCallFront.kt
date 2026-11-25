package com.phonxiety.bridge.feature.livecall

/**
 * The id of the call this phone placed itself. A call the computer places also runs through
 * Telecom, but nobody on the phone asked to watch that one; only a call placed from the composer
 * should pull the live screen back over the system's in-call UI.
 */
object LiveCallFront {
    @Volatile
    var placedCallId: String? = null
        private set

    fun remember(callId: String) {
        if (callId.isNotBlank()) placedCallId = callId
    }

    fun forget(callId: String) {
        if (placedCallId == callId) placedCallId = null
    }
}
