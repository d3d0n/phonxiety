package com.phonxiety.bridge.engine

import android.telecom.Call
import com.phonxiety.bridge.model.DisconnectCause

/**
 * Pure reads over a Telecom [Call]: identity, state, disconnect cause and the
 * extras marker the bridge stamps on the call it placed.
 */
internal object CallCorrelation {
    /** Extras the bridge puts on the Call it asks Telecom to place. */
    const val EXTRA_CALL_ID = "com.phonxiety.bridge.extra.CALL_ID"
    const val EXTRA_OWNER = "com.phonxiety.bridge.extra.OWNER"

    data class CallMarker(val callId: String, val owner: String?)

    fun detailsOf(call: Call): Call.Details? = try {
        call.details
    } catch (_: Exception) {
        null
    }

    fun stateOf(call: Call): Int = detailsOf(call)?.state ?: Call.STATE_NEW

    fun disconnectCause(call: Call): DisconnectCause? = disconnectCause(detailsOf(call))

    fun disconnectCause(details: Call.Details?): DisconnectCause? {
        val cause = details?.disconnectCause ?: return null
        return DisconnectCause(
            code = cause.code,
            label = cause.label?.toString() ?: "",
            reason = cause.reason ?: ""
        )
    }

    fun extraMarker(details: Call.Details): CallMarker? {
        val extras = try {
            details.intentExtras
        } catch (_: Exception) {
            null
        } ?: return null
        val callId = try {
            extras.getString(EXTRA_CALL_ID)
        } catch (_: Exception) {
            null
        }
        if (callId.isNullOrEmpty()) return null
        val owner = try {
            extras.getString(EXTRA_OWNER)
        } catch (_: Exception) {
            null
        }
        return CallMarker(callId, owner)
    }

    fun phoneNumbersMatch(left: String, right: String): Boolean {
        val a = left.filter { it.isDigit() }
        val b = right.filter { it.isDigit() }
        if (a.isNotEmpty() && b.isNotEmpty()) return a == b
        return left.trim() == right.trim()
    }

    fun mapState(state: Int): String = when (state) {
        Call.STATE_ACTIVE -> "active"

        Call.STATE_HOLDING -> "active"

        Call.STATE_DISCONNECTING -> "ending"

        Call.STATE_DISCONNECTED -> "ended"

        Call.STATE_NEW,
        Call.STATE_RINGING,
        Call.STATE_DIALING,
        Call.STATE_CONNECTING,
        Call.STATE_SELECT_PHONE_ACCOUNT -> "dialing"

        else -> "active"
    }
}
