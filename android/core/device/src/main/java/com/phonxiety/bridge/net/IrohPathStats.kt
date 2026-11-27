package com.phonxiety.bridge.net

import com.phonxiety.bridge.BridgeLog
import computer.iroh.Connection
import computer.iroh.PathChangeCallback
import computer.iroh.PathSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The link's path evidence: the connection's own push events, the 1 s fallback
 * sample that keeps the UI's route fresh and feeds the relay-only monitor, and
 * the dump logged at link down.
 *
 * [link] reads the current connection, [isLive] is false once the link is
 * cancelled, and [onRoute] publishes the sampled route (null clears it).
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

    /** Subscribes the monitor to a connection's own path-change events. */
    fun source(connection: Connection): IrohPathSource = IrohPathSource { onPath ->
        val handle = connection.watchPaths(object : PathChangeCallback {
            override suspend fun onChange(paths: List<PathSnapshot>) = onPath(kindOf(paths))
        })
        IrohPathWatch { runCatching { handle.close() } }
    }

    fun kindOf(paths: List<PathSnapshot>): IrohPathKind = when (paths.firstOrNull { it.isSelected }?.isRelay) {
        null -> IrohPathKind.UNKNOWN
        true -> IrohPathKind.RELAY
        false -> IrohPathKind.DIRECT
    }

    /**
     * The fallback the push events cannot replace: one snapshot per second that
     * keeps the UI's route fresh and feeds the same path kind to the relay-only
     * monitor, so a lost watcher event never hides a relay residence.
     */
    suspend fun sample(connection: Connection, monitor: IrohPathMonitor) {
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
                monitor.observe(kindOf(snapshots))
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
