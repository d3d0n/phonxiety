package com.phonxiety.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.phonxiety.bridge.feature.widgets.CallEnd
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Hang up button on the live-call card.
 *
 * It runs the one hang-up implementation the tile and the widget already use: the daemon's own
 * `hangup` when the app API answers, and the Telecom call the engine supervises when it does not.
 * There is no second way to end a call from the app.
 */
class NotificationActions(
    private val endCall: suspend (Context) -> Unit = CallEnd::hangUp
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifications.ACTION_HANG_UP) return
        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                endCall(appContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the hang-up action failed: ${e.javaClass.simpleName}")
            } finally {
                // Non-null when the system dispatched the broadcast; a direct call in a test has
                // no pending result, and there is nothing to finish there either.
                pending?.finish()
            }
        }
    }

    private companion object {
        const val TAG = "PhonxietyNotify"

        /** The work outlives [onReceive]; [goAsync] gives it the broadcast's grace window. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
