package com.phonxiety.bridge.feature.widgets

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import com.phonxiety.bridge.BridgeRuntime
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.data.HangUpOutcome
import com.phonxiety.bridge.model.CallId
import com.phonxiety.bridge.model.CallSummary
import com.phonxiety.bridge.model.LinkState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlin.coroutines.cancellation.CancellationException
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
 * Ends the running call: the daemon's own `hangup` when the link is up and the daemon succeeds,
 * and the call the engine owns on this device otherwise. Either way the engine reports the end
 * through the state.
 *
 * The daemon is asked only over a live link. Without one, its request would wait for a reconnect
 * and keep the call up for the whole bound, so an explicit hang-up goes straight to the engine,
 * which ends only its own call and leaves any unrelated call alone.
 *
 * [callId], [linkUp], [daemon] and [local] are the seams the Hang up receiver's tests use to
 * prove that a throwing daemon still falls through to the engine; the app calls it with the live ones.
 */
object CallEnd {
    suspend fun hangUp(
        context: Context,
        callId: String? = BridgeRuntime.state.value.call?.callId,
        linkUp: Boolean = BridgeRuntime.state.value.link is LinkState.Up,
        daemon: suspend () -> HangUpOutcome? = { callId?.let { id -> daemonApiOrNull(context)?.hangup(CallId(id)) } },
        local: suspend (String?) -> Unit = { id -> BridgeRuntime.endOwnedCall(id) }
    ) {
        if (callId != null && linkUp) {
            val outcome = try {
                withTimeoutOrNull(HANGUP_TIMEOUT_MS) { daemon() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The daemon's end failed; the call the engine owns is still there to end.
                null
            }
            if (outcome is HangUpOutcome.HungUp) return
        }
        // The id captured on entry: a call that takes over the line while the
        // daemon request waits must not become the call this hang-up ends.
        local(callId)
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
