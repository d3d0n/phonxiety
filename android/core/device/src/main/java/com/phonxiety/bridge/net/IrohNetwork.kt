package com.phonxiety.bridge.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** How long a bind waits for an internet-capable network before going ahead anyway. */
private const val NETWORK_WAIT_MS = 8_000L

/**
 * An endpoint bound before Android has an internet-capable network never
 * recovers a direct path, so wait for one first.  Gives up after
 * [NETWORK_WAIT_MS] and binds anyway: the supervisor's attempt deadline and
 * backoff own the rest.
 */
internal suspend fun awaitNetwork(context: Context) {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
    if (hasInternet(manager)) return
    withTimeoutOrNull(NETWORK_WAIT_MS) {
        suspendCancellableCoroutine { continuation ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        return
                    }
                    // Unregister before resuming so the platform never keeps
                    // a callback whose continuation is already done.
                    unregisterNetwork(manager, this)
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            try {
                manager.registerDefaultNetworkCallback(callback)
            } catch (_: Exception) {
                // The callback table is full or the service is gone: bind now
                // instead of waiting out the deadline without any signal.
                if (continuation.isActive) continuation.resume(Unit)
                return@suspendCancellableCoroutine
            }
            // Installed only once registered: a cancellation that came first
            // runs this right away, so the callback never outlives the wait.
            continuation.invokeOnCancellation { unregisterNetwork(manager, callback) }
        }
    }
}

internal fun hasInternet(manager: ConnectivityManager): Boolean {
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

internal fun unregisterNetwork(manager: ConnectivityManager, callback: ConnectivityManager.NetworkCallback) {
    runCatching { manager.unregisterNetworkCallback(callback) }
}

/**
 * Calls [onChange] whenever Android's default network is lost or replaced by
 * another one, until the returned function unregisters the callback.  The
 * network current at registration does not count as a change.
 */
internal fun watchNetwork(context: Context, onChange: (String) -> Unit): () -> Unit {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return {}
    val callback = object : ConnectivityManager.NetworkCallback() {
        private var current: Network? = null

        override fun onAvailable(network: Network) {
            val previous = current
            current = network
            if (previous != null && previous != network) onChange("network_changed")
        }

        override fun onLost(network: Network) {
            if (network == current) {
                current = null
                onChange("network_lost")
            }
        }
    }
    return try {
        manager.registerDefaultNetworkCallback(callback)
        ({ unregisterNetwork(manager, callback) })
    } catch (_: Exception) {
        {}
    }
}
