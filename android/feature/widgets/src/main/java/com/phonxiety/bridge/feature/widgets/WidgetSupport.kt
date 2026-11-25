package com.phonxiety.bridge.feature.widgets

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import com.phonxiety.bridge.BridgeRuntime
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.model.CallSummary
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.withTimeoutOrNull

/** The Hilt graph the surfaces reach without an activity: the typed daemon client. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun daemonApi(): DaemonApi
}

/** The daemon client, or null when the app is not running under Hilt (previews, unit tests). */
fun daemonApiOrNull(context: Context): DaemonApi? = runCatching {
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).daemonApi()
}.getOrNull()

/**
 * The last finished call, kept in process memory. It is refetched on the next widget update after
 * a process restart, so the widget never needs a timer to stay current.
 */
object WidgetStateStore {
    @Volatile
    private var lastFinishedCall: CallSummary? = null

    fun lastCall(): CallSummary? = lastFinishedCall

    fun setLastCall(summary: CallSummary?) {
        lastFinishedCall = summary
    }
}

/**
 * Ends the running call: the daemon's own `hangup` when the app API answers, and the Telecom call
 * the engine supervises when it does not. Either way the engine reports the end through the state.
 */
object CallEnd {
    suspend fun hangUp(context: Context) {
        val callId = BridgeRuntime.state.value.call?.callId
        if (callId != null) {
            val outcome = withTimeoutOrNull(HANGUP_TIMEOUT_MS) { daemonApiOrNull(context)?.hangup(callId) }
            if (outcome is ApiOutcome.Ok) return
        }
        BridgeRuntime.currentCalls().forEach { call ->
            runCatching { call.disconnect() }
        }
    }

    private const val HANGUP_TIMEOUT_MS = 5_000L
}

/** The app's single activity: the dashboard, or the wizard while setup is unfinished. */
fun openAppIntent(): Intent = Intent().apply {
    setClassName(HOST_PACKAGE, HOST_ACTIVITY)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}

const val HOST_PACKAGE: String = "com.phonxiety.bridge"
const val HOST_ACTIVITY: String = "com.phonxiety.bridge.MainActivity"

/** Asks both tiles to re-read the state the next time quick settings shows them. */
fun requestTileRefresh(context: Context) {
    listOf(LinkTileService::class.java, HangupTileService::class.java).forEach { service ->
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, service))
        }
    }
}
