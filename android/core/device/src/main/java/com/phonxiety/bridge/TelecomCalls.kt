package com.phonxiety.bridge

import android.telecom.Call

/**
 * What Telecom's own call list says about calls other than the one this phone placed.
 *
 * The live screen may only replace an in-call cover that belongs to its own call. The bridge owns at
 * most one call at a time, and a call placed here is outgoing, so a live incoming call — or any
 * second live call — is somebody else's screen: returning over it would open the live screen on top
 * of another call.
 *
 * Read-only: it never changes call state and returns false while Telecom has this process unbound.
 */
object TelecomCalls {
    /** True while Telecom is showing at least one live call that is not the bridge's own. */
    fun anotherCallVisible(): Boolean {
        var live = 0
        for (call in BridgeRuntime.currentCalls()) {
            if (!isLive(call)) continue
            live++
            if (direction(call) == Call.Details.DIRECTION_INCOMING) return true
        }
        return live > 1
    }

    private fun isLive(call: Call): Boolean = when (state(call)) {
        Call.STATE_DISCONNECTED,
        Call.STATE_DISCONNECTING -> false

        else -> true
    }

    private fun state(call: Call): Int = try {
        call.state
    } catch (_: Exception) {
        Call.STATE_DISCONNECTED
    }

    private fun direction(call: Call): Int = try {
        call.details?.callDirection ?: Call.Details.DIRECTION_UNKNOWN
    } catch (_: Exception) {
        Call.Details.DIRECTION_UNKNOWN
    }
}
