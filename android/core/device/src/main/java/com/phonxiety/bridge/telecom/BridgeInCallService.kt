package com.phonxiety.bridge.telecom

import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.telecom.Call
import android.telecom.InCallService
import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.BridgeRuntime
import com.phonxiety.bridge.bridgeScope
import com.phonxiety.bridge.model.CallState
import com.phonxiety.bridge.os.NotificationSlot
import com.phonxiety.bridge.os.Notifications
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map

/**
 * Non-UI InCallService.  Telecom binds this when a call starts; it is not a
 * dialer replacement and declares no IN_CALL_SERVICE_UI metadata.  The binding
 * is legitimate because the app is allowlisted for CONTROL_INCALL_EXPERIENCE
 * as a privileged app (Magisk allowlist).
 *
 * It starts a microphone-type foreground service as required for capture while
 * in the background.  It does not use the microphone as an audio path: RX is
 * AudioRecord(VOICE_DOWNLINK) and TX is an AudioTrack to the telephony sink.
 *
 * The foreground card follows the bridge state: the far end, the phase and a
 * running clock, plus Hang up.  Telecom unbinds the service when the last call
 * ends, and [onDestroy] is the one place the card is removed.
 */
class BridgeInCallService : InCallService() {
    companion object {
        private const val TAG = "PhonxietyBridge"
    }

    private val scope = bridgeScope("phonxiety-incall")
    private var card: Job? = null

    private val slot by lazy {
        NotificationSlot<CallState?>(getSystemService(NotificationManager::class.java), Notifications.CALL_ID)
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { Notifications.ensureChannels(this) }
        enterForeground(BridgeRuntime.state.value.call)
        BridgeRuntime.inCallService = this
        card = slot.follow(BridgeRuntime.state.map { it.call }, scope) { call ->
            Notifications.call(this, call)
        }
    }

    override fun onDestroy() {
        if (BridgeRuntime.inCallService === this) {
            BridgeRuntime.inCallService = null
        }
        slot.close()
        card?.cancel()
        card = null
        scope.cancel()
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

    private fun enterForeground(call: CallState?) {
        try {
            startForeground(
                Notifications.CALL_ID,
                Notifications.call(this, call),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            // Missing RECORD_AUDIO or a pre-unlock restriction: callbacks still
            // arrive, and the explicit audio_error path will report failures.
            BridgeLog.e(TAG, "call foreground unavailable: ${e.javaClass.simpleName}")
        }
    }
}
