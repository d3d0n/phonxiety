package com.phonxiety.bridge.net

import com.phonxiety.bridge.BridgeLog
import computer.iroh.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The link's path evidence: the 1 s sample that keeps the UI's route fresh and
 * the dump logged at link down.
 *
 * [link] reads the current connection, [isLive] is false once the link is
 * cancelled, and [onRoute] publishes the sampled route (null clears it) for the
 * UI and link selection.
 */
internal class IrohPathStats(
    private val scope: CoroutineScope,
    private val link: () -> Connection?,
    private val isLive: () -> Boolean,
    private val onRoute: (DeviceLink.Route?) -> Unit
) {
    private companion object {
        const val TAG = "Bridge:iroh"
        const val ROUTE_INTERVAL_MS = 1_000L
    }

    /**
     * One snapshot per second: the selected path for the UI and link selection.
     * A lost snapshot ends the loop.
     */
    suspend fun sample(connection: Connection) {
        var lastPath: String? = null
        while (scope.isActive && isLive()) {
            try {
                val snapshots = connection.paths()
                val selected = snapshots.firstOrNull { it.isSelected }
                val path = selected?.let { (if (it.isRelay) "relay " else "direct ") + it.remoteAddr }
                if (path != lastPath) {
                    lastPath = path
                    BridgeLog.i(TAG) { "iroh path: ${path ?: "none"}" }
                }
                onRoute(
                    selected?.let {
                        val rtt = it.rttMs.toLong()
                        DeviceLink.Route(relay = it.isRelay, rttMs = if (rtt > 0L) rtt else null)
                    }
                )
            } catch (_: Throwable) {
                return
            }
            delay(ROUTE_INTERVAL_MS)
        }
    }

    /** Every open path, the selected one starred, with its transport counters. */
    fun describe(): String = runCatching {
        link()?.paths()?.joinToString("; ") { path ->
            (if (path.isSelected) "*" else "") + (if (path.isRelay) "relay " else "direct ") + path.remoteAddr +
                " rtt ${path.rttMs} ms, sent ${path.stats.udpTxDatagrams}, received ${path.stats.udpRxDatagrams}, " +
                "lost ${path.stats.lostPackets}, cwnd ${path.stats.cwnd}, mtu ${path.stats.currentMtu}"
        }
    }.getOrNull() ?: "unavailable"
}
