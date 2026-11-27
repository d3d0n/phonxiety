package com.phonxiety.bridge.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.phonxiety.bridge.BridgeLog
import java.util.Locale

/**
 * One `_phonxiety._tcp` advertisement on the local network.
 *
 * The daemon publishes its address link under this service with a TXT record
 * carrying the certificate pin and the protocol version.  mDNS is
 * unauthenticated, so [certSha256] is the only part that may be trusted, and
 * only after it matches the pin the pairing already holds.
 */
internal data class DiscoveredDaemon(
    val host: String,
    val port: Int,
    val certSha256: String?,
    val protocol: Int?,
    val serviceName: String
) {
    /** The `wss://` URL of this daemon's address link; IPv6 hosts keep their brackets. */
    val url: String
        get() {
            val authority = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            return "wss://$authority:$port${MdnsAdvertisement.DEVICE_PATH}"
        }
}

/** The mDNS service name and the TXT fields the daemon publishes. */
internal object MdnsAdvertisement {
    const val SERVICE_TYPE = "_phonxiety._tcp"
    const val DEVICE_PATH = "/device"
    const val TXT_CERT = "cert"
    const val TXT_PROTOCOL = "proto"

    private val PIN = Regex("^[0-9a-fA-F]{64}$")

    /** The TXT record as strings; an entry that is not UTF-8 is dropped. */
    fun fields(attributes: Map<String, ByteArray>?): Map<String, String> {
        if (attributes == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((key, value) in attributes) {
            val text = runCatching { String(value, Charsets.UTF_8).trim() }.getOrNull() ?: continue
            if (key.isNotEmpty()) out[key.trim().lowercase(Locale.US)] = text
        }
        return out
    }

    /** A certificate pin from the TXT record, or null when it is missing or malformed. */
    fun certOf(fields: Map<String, String>): String? = fields[TXT_CERT]?.takeIf { PIN.matches(it) }?.lowercase(Locale.US)

    fun protocolOf(fields: Map<String, String>): Int? = fields[TXT_PROTOCOL]?.toIntOrNull()

    /** A resolved service as a daemon, or null when it carries no host, port or valid pin. */
    fun daemonOf(serviceName: String?, host: String?, port: Int, fields: Map<String, String>): DiscoveredDaemon? {
        val address = host?.trim()?.removeSuffix(".")?.takeIf { it.isNotEmpty() } ?: return null
        if (port !in 1..65535) return null
        return DiscoveredDaemon(
            host = address,
            port = port,
            certSha256 = certOf(fields),
            protocol = protocolOf(fields),
            serviceName = serviceName.orEmpty()
        )
    }
}

/**
 * Discovery of the daemon's address link on the same network, through
 * [NsdManager].  Resolved services are handed to [onService] on the NSD
 * callback thread; the caller filters them by its pairing pin.
 *
 * Discovery never blocks and never throws: a network without mDNS, a missing
 * `ACCESS_LOCAL_NETWORK` grant or a busy NSD stack only means no addresses are
 * learned, and the paired transport stays first.
 */
internal class MdnsDiscovery(
    context: Context,
    private val onService: (DiscoveredDaemon) -> Unit
) {
    private val manager: NsdManager? = context.applicationContext.getSystemService(NsdManager::class.java)

    @Volatile private var running = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (running) return
        val service = manager ?: return
        running = true
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!running) return
                if (!info.serviceType.startsWith(MdnsAdvertisement.SERVICE_TYPE, ignoreCase = true)) return
                resolve(service, info)
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                BridgeLog.w(TAG, "mDNS discovery unavailable ($errorCode)")
                stop()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                BridgeLog.w(TAG, "mDNS discovery could not stop ($errorCode)")
            }
        }
        listener = discovery
        try {
            service.discoverServices(MdnsAdvertisement.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: Exception) {
            BridgeLog.w(TAG) { "mDNS discovery failed to start: ${e.javaClass.simpleName}" }
            running = false
            listener = null
        }
    }

    /** Stops discovery; safe to call more than once and from any thread. */
    fun stop() {
        val discovery = listener ?: return
        listener = null
        running = false
        runCatching { manager?.stopServiceDiscovery(discovery) }
    }

    private fun resolve(service: NsdManager, info: NsdServiceInfo) {
        try {
            service.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        BridgeLog.d(TAG, "mDNS resolve failed ($errorCode)")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (!running) return
                        val daemon = MdnsAdvertisement.daemonOf(
                            serviceName = serviceInfo.serviceName,
                            host = serviceInfo.host?.hostAddress,
                            port = serviceInfo.port,
                            fields = MdnsAdvertisement.fields(serviceInfo.attributes)
                        ) ?: return
                        onService(daemon)
                    }
                }
            )
        } catch (e: Exception) {
            // NsdManager allows one resolve at a time; a busy stack is not an error worth retrying.
            BridgeLog.d(TAG) { "mDNS resolve skipped: ${e.javaClass.simpleName}" }
        }
    }

    private companion object {
        const val TAG = "Bridge:mdns"
    }
}
