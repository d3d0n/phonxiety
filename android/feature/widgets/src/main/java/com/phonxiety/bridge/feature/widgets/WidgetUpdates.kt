package com.phonxiety.bridge.feature.widgets

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.phonxiety.bridge.BridgeConfigLoader
import com.phonxiety.bridge.BridgeRuntime
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.model.BridgeState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one observer behind the widget and both tiles. It reacts to state changes (the rendered
 * content, so audio levels and other invisible churn cost nothing), asks Glance to re-render and
 * requests a listening update from the tiles. No timer polls the device.
 *
 * The app calls [start] once from its `Application.onCreate`; the widget receiver does the same, so
 * a launcher that updates us before the app ever started still gets a live widget.
 */
object WidgetUpdates {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    fun start(context: Context) {
        val appContext = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        scope.launch { observe(appContext) }
    }

    private suspend fun observe(context: Context) {
        val widget = PhonxietyWidget()
        var lastCallFetchAt = 0L
        BridgeRuntime.state
            .map { state -> state.call?.callId to render(context, state) }
            .distinctUntilChanged()
            .collect { (_, content) ->
                val now = System.currentTimeMillis()
                val needsCall = content.call == null &&
                    (WidgetStateStore.lastCall() == null || now - lastCallFetchAt > LAST_CALL_REFRESH_MS)
                if (needsCall && refreshLastCall(context, widget)) {
                    lastCallFetchAt = now
                }
                runCatching { widget.updateAll(context) }
                requestTileRefresh(context)
            }
    }

    /** Re-renders the widget once the last-call lookup lands; the daemon answered, so the caller throttles. */
    private suspend fun refreshLastCall(context: Context, widget: PhonxietyWidget): Boolean {
        val api = daemonApiOrNull(context) ?: return false
        val outcome = withTimeoutOrNull(LAST_CALL_TIMEOUT_MS) { api.calls(limit = 1) }
        val next = (outcome as? ApiOutcome.Ok)?.value?.calls?.firstOrNull()?.takeIf { !it.live }
        if (next != null || outcome is ApiOutcome.Ok) {
            WidgetStateStore.setLastCall(next)
            runCatching { widget.updateAll(context) }
        }
        return outcome != null
    }

    private fun render(context: Context, state: BridgeState): WidgetContent {
        val paired = runCatching { BridgeConfigLoader.loadOrNull(context) != null }.getOrDefault(false)
        return WidgetContentMapper.map(
            bridge = state,
            paired = paired,
            lastCall = WidgetStateStore.lastCall(),
            nowEpochMs = System.currentTimeMillis()
        )
    }

    private const val LAST_CALL_REFRESH_MS = 60_000L
    private const val LAST_CALL_TIMEOUT_MS = 5_000L
}
