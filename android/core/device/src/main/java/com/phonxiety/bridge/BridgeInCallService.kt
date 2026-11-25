package com.phonxiety.bridge

import android.app.Service
import android.content.pm.ServiceInfo
import android.telecom.Call
import android.telecom.InCallService

/**
 * Non-UI InCallService.  Telecom binds this when a call starts; it is not a
 * dialer replacement and declares no IN_CALL_SERVICE_UI metadata.  The binding
 * is legitimate because the app is allowlisted for CONTROL_INCALL_EXPERIENCE
 * as a privileged app (Magisk allowlist).
 *
 * It starts a microphone-type foreground service as required for capture while
 * in the background.  It does not use the microphone as an audio path: RX is
 * AudioRecord(VOICE_DOWNLINK) and TX is an AudioTrack to the telephony sink.
 */
class BridgeInCallService : InCallService() {
    companion object {
        private const val TAG = "PhonxietyBridge"
        private const val NOTIFICATION_ID = 0x5049
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { Notifications.ensureChannels(this) }
        try {
            startForeground(
                NOTIFICATION_ID,
                Notifications.call(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            // Missing RECORD_AUDIO or a pre-unlock restriction: callbacks still
            // arrive, and the explicit audio_error path will report failures.
            BridgeLog.e(TAG, "call foreground unavailable: ${e.javaClass.simpleName}")
        }
        BridgeRuntime.inCallService = this
    }

    override fun onDestroy() {
        if (BridgeRuntime.inCallService === this) {
            BridgeRuntime.inCallService = null
        }
        runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        BridgeRuntime.engine?.onTelecomCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        BridgeRuntime.engine?.onTelecomCallRemoved(call)
        super.onCallRemoved(call)
    }
}
