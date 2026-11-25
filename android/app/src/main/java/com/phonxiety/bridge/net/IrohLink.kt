package com.phonxiety.bridge.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.phonxiety.bridge.AppStorage
import com.phonxiety.bridge.BridgeConfig
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.EndpointTicket
import computer.iroh.IrohAndroid
import computer.iroh.RecvStream
import computer.iroh.SecretKey
import computer.iroh.SendStream
import computer.iroh.presetN0
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Process-wide scope for iroh calls that must never block their caller:
 * endpoint shutdown, endpoint recycle and connection close.  Callers include
 * the main thread when the service stops, so none of this may run there.
 */
private val irohIo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Process-wide iroh state: one endpoint, one persisted secret key.
 *
 * The endpoint is bound on first use and lives until the bridge service closes
 * the engine; it is never touched on the main thread.
 */
internal object IrohSupport {
    private const val TAG = "PhonxietyBridge"
    private const val SECRET_KEY_FILE = "iroh-secret.key"
    private const val SECRET_KEY_BYTES = 32
    private const val NETWORK_WAIT_MS = 30_000L
    private const val RECYCLE_MIN_INTERVAL_MS = 5 * 60_000L

    /** ALPN of the device protocol, per the iroh link contract. */
    val ALPN: ByteArray = "phonxiety/device/1".toByteArray(Charsets.UTF_8)

    private val contextInstalled = AtomicBoolean(false)
    private val endpointMutex = Mutex()
    private val recycleLock = Any()

    @Volatile private var endpointRef: Endpoint? = null

    /** `elapsedRealtime` of the last endpoint recycle; 0 until the first one. */
    private var lastRecycleElapsed = 0L

    /** Binds the endpoint on first use; safe to call from several links. */
    suspend fun endpoint(context: Context): Endpoint {
        endpointRef?.let { return it }
        return endpointMutex.withLock {
            endpointRef ?: bind(context.applicationContext).also { endpointRef = it }
        }
    }

    private suspend fun bind(context: Context): Endpoint {
        installAndroidContext(context)
        val key = secretKey(context)
        return withContext(Dispatchers.IO) {
            awaitNetwork(context)
            Endpoint.bind(
                EndpointOptions(
                    preset = presetN0(),
                    secretKey = key,
                )
            )
        }
    }

    /**
     * An endpoint bound before Android has an internet-capable network never
     * recovers a direct path, so wait for one first.  Gives up after 30 s and
     * binds anyway.
     */
    private suspend fun awaitNetwork(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        if (hasInternet(manager)) return
        withTimeoutOrNull(NETWORK_WAIT_MS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                            return
                        }
                        // Unregister before resuming so the platform never keeps
                        // a callback whose continuation is already done.
                        unregister(manager, this)
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                continuation.invokeOnCancellation { unregister(manager, callback) }
                try {
                    manager.registerDefaultNetworkCallback(callback)
                } catch (_: Exception) {
                    // The callback table is full or the service is gone: bind now
                    // instead of waiting out the deadline without any signal.
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    private fun hasInternet(manager: ConnectivityManager): Boolean {
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun unregister(manager: ConnectivityManager, callback: ConnectivityManager.NetworkCallback) {
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    /** Installs the JNI application context iroh's DNS resolver needs. */
    fun installAndroidContext(context: Context) {
        if (!contextInstalled.compareAndSet(false, true)) return
        try {
            IrohAndroid.installAndroidContext(context.applicationContext)
        } catch (t: Throwable) {
            contextInstalled.set(false)
            throw t
        }
    }

    /** Reads the persisted 32-byte secret key, or creates and persists one. */
    private fun secretKey(context: Context): ByteArray {
        val file = File(AppStorage.deviceProtected(context).filesDir, SECRET_KEY_FILE)
        if (file.isFile) {
            val stored = runCatching { file.readBytes() }.getOrNull()
            if (stored != null && stored.size == SECRET_KEY_BYTES) return stored
        }
        val generated = SecretKey.generate().toBytes()
        try {
            AppStorage.writeAtomically(file, generated)
        } catch (e: Exception) {
            // A new identity per service start is worse than no persistence,
            // but the link must still work; never log the key itself.
            Log.w(TAG, "could not persist the iroh secret key: ${e.javaClass.simpleName}")
        }
        return generated
    }

    /** Shuts the endpoint down without blocking the caller. */
    fun shutdownAsync() {
        val endpoint = endpointRef ?: return
        endpointRef = null
        stopAsync(endpoint, "phonxiety-iroh-close")
    }

    /**
     * Whether a relay-only link may recycle the endpoint now.  Marks the
     * recycle time when it returns true, so at most one happens per five
     * minutes.  Call [recycleEndpoint] after reporting the link down.
     */
    fun reserveRecycle(): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(recycleLock) {
            if (lastRecycleElapsed != 0L && now - lastRecycleElapsed < RECYCLE_MIN_INTERVAL_MS) {
                return false
            }
            lastRecycleElapsed = now
        }
        return true
    }

    /**
     * Clears the process-wide endpoint so the next link binds a fresh one, and
     * shuts the old one down without blocking the caller.
     */
    fun recycleEndpoint() {
        val endpoint = endpointRef ?: return
        endpointRef = null
        stopAsync(endpoint, "phonxiety-iroh-recycle")
    }

    private fun stopAsync(endpoint: Endpoint, name: String) {
        irohIo.launch {
            try {
                endpoint.shutdown()
            } catch (t: Throwable) {
                Log.w(TAG, "iroh endpoint close failed ($name): ${t.javaClass.simpleName}")
            }
        }
    }

    /**
     * Parses [ticket] with the bindings and returns the daemon endpoint id, or
     * null when the ticket is not valid.  Loads the native library and must run
     * off the main thread.
     */
    fun endpointId(ticket: String): String? = try {
        EndpointTicket.fromString(ticket).use { parsed ->
            parsed.endpointAddr().use { addr ->
                addr.id().use { id -> id.toString() }
            }
        }
    } catch (_: Throwable) {
        null
    }

    fun ticketIsValid(ticket: String): Boolean = endpointId(ticket) != null

    /** First 8 and last 4 characters of the endpoint id, for the UI. */
    fun shortEndpointId(ticket: String): String? = endpointId(ticket)?.let { id ->
        if (id.length <= 12) id else id.take(8) + "…" + id.takeLast(4)
    }
}

internal class IrohLinkFactory(
    private val context: Context,
    private val config: BridgeConfig.Iroh,
    private val ownsCall: () -> Boolean,
) : DeviceLinkFactory {
    override fun create(): DeviceLink = IrohLink(context, config, ownsCall)

    override fun shutdown() {
        IrohSupport.shutdownAsync()
    }
}

/**
 * One iroh link: dial the daemon endpoint from the stored ticket, open one
 * bidirectional stream, authenticate with the first frame, then move framed
 * messages.  A reader coroutine, a writer coroutine fed by a queue and a
 * route sampler run for the life of the stream; any failure reports down once.
 *
 * The wire framing is [Framing]; this class only decides what to send.
 */
private class IrohLink(
    private val context: Context,
    private val config: BridgeConfig.Iroh,
    private val ownsCall: () -> Boolean,
) : DeviceLink {
    private companion object {
        const val TAG = "PhonxietyBridge"
        const val ROUTE_INTERVAL_MS = 1_000L
        const val RELAY_ONLY_RECYCLE_MS = 20_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val backlog = AtomicLong(0)
    private val downOnce = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    @Volatile private var connection: Connection? = null
    @Volatile private var route: DeviceLink.Route? = null

    override val backlogBytes: Long get() = backlog.get()

    override fun route(): DeviceLink.Route? = route

    override fun start(listener: DeviceLink.Listener) {
        scope.launch { connect(listener) }
    }

    private suspend fun connect(listener: DeviceLink.Listener) {
        try {
            if (cancelled.get()) return
            val endpoint = IrohSupport.endpoint(context)
            val link = EndpointTicket.fromString(config.ticket).use { ticket ->
                ticket.endpointAddr().use { addr ->
                    endpoint.connect(addr, IrohSupport.ALPN)
                }
            }
            connection = link
            val stream = link.openBi()
            val outgoing = stream.send()
            val auth = Framing.encode(Framing.KIND_TEXT, authMessage())
                ?: return fail(listener, "connect_failed")
            outgoing.writeAll(auth)
            if (cancelled.get()) {
                runCatching { link.close(0, "cancelled".toByteArray()) }
                return
            }
            listener.onLinkOpened()
            scope.launch { readLoop(stream.recv(), listener) }
            scope.launch { writeLoop(outgoing, listener) }
            scope.launch {
                val reason = runCatching { link.closed() }.getOrDefault("closed")
                fail(listener, reason.ifEmpty { "closed" })
            }
            scope.launch { sampleRoute(link, listener) }
        } catch (t: Throwable) {
            if (!cancelled.get()) {
                Log.w(TAG, "iroh connect failed: ${t.javaClass.simpleName}")
                fail(listener, "connect_failed")
            }
        }
    }

    private fun authMessage(): ByteArray = JSONObject()
        .put("type", "auth")
        .put("token", config.token)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private suspend fun readLoop(incoming: RecvStream, listener: DeviceLink.Listener) {
        try {
            while (true) {
                val header = incoming.readExact(Framing.HEADER_BYTES.toUInt())
                if (header.size != Framing.HEADER_BYTES) {
                    fail(listener, "closed")
                    return
                }
                val parsed = Framing.parse(header)
                if (parsed == null) {
                    fail(listener, "protocol")
                    return
                }
                val payload = incoming.readExact(parsed.length.toUInt())
                if (payload.size != parsed.length) {
                    fail(listener, "closed")
                    return
                }
                if (parsed.kind == Framing.KIND_TEXT) {
                    listener.onLinkText(payload.toString(Charsets.UTF_8))
                } else {
                    listener.onLinkBinary(payload.toByteString())
                }
            }
        } catch (t: Throwable) {
            if (!cancelled.get()) fail(listener, "closed")
        }
    }

    private suspend fun writeLoop(outgoing: SendStream, listener: DeviceLink.Listener) {
        try {
            for (bytes in outbound) {
                try {
                    outgoing.writeAll(bytes)
                } finally {
                    backlog.addAndGet(-bytes.size.toLong())
                }
            }
        } catch (t: Throwable) {
            if (!cancelled.get()) fail(listener, "write_failed")
        }
    }

    private suspend fun sampleRoute(link: Connection, listener: DeviceLink.Listener) {
        // An endpoint bound before the network settles can stay on the relay
        // forever; a fresh endpoint finds the direct path again.  Measure from
        // the open so only a continuous relay-only path triggers a recycle.
        var relaySince = 0L
        var lastPath: String? = null
        while (scope.isActive && !cancelled.get()) {
            val now = SystemClock.elapsedRealtime()
            try {
                val selected = link.paths().firstOrNull { it.isSelected }
                val path = selected?.let { (if (it.isRelay) "relay " else "direct ") + it.remoteAddr }
                if (path != lastPath) {
                    lastPath = path
                    Log.i(TAG, "iroh path: ${path ?: "none"}")
                }
                if (selected == null) {
                    route = null
                    relaySince = 0L
                } else {
                    val rtt = selected.rttMs.toLong()
                    route = DeviceLink.Route(
                        relay = selected.isRelay,
                        rttMs = if (rtt > 0L) rtt else null,
                    )
                    if (selected.isRelay) {
                        if (relaySince == 0L) relaySince = now
                        if (now - relaySince >= RELAY_ONLY_RECYCLE_MS && !ownsCall()) {
                            if (IrohSupport.reserveRecycle()) {
                                fail(listener, "relay_only")
                                IrohSupport.recycleEndpoint()
                                return
                            }
                        }
                    } else {
                        relaySince = 0L
                    }
                }
            } catch (_: Throwable) {
                return
            }
            delay(ROUTE_INTERVAL_MS)
        }
    }

    override fun sendText(message: String): Boolean {
        val frame = Framing.encode(Framing.KIND_TEXT, message.toByteArray(Charsets.UTF_8))
            ?: return false
        return enqueue(frame)
    }

    override fun sendBinary(frame: ByteArray, length: Int) {
        val size = length.coerceIn(0, frame.size)
        if (size == 0) return
        val encoded = Framing.encode(Framing.KIND_BINARY, frame, size) ?: return
        enqueue(encoded)
    }

    private fun enqueue(bytes: ByteArray): Boolean {
        if (cancelled.get() || downOnce.get()) return false
        backlog.addAndGet(bytes.size.toLong())
        if (outbound.trySend(bytes).isFailure) {
            backlog.addAndGet(-bytes.size.toLong())
            return false
        }
        return true
    }

    private fun fail(listener: DeviceLink.Listener, reason: String) {
        if (cancelled.get()) return
        if (!downOnce.compareAndSet(false, true)) return
        Log.w(TAG, "iroh paths at link down ($reason): ${describePaths()}")
        route = null
        runCatching { connection?.close(0, reason.toByteArray(Charsets.UTF_8)) }
        outbound.close()
        listener.onLinkDown(reason)
        scope.cancel()
    }

    /** Every open path, the selected one starred, with its transport counters. */
    private fun describePaths(): String = runCatching {
        connection?.paths()?.joinToString("; ") { path ->
            (if (path.isSelected) "*" else "") + (if (path.isRelay) "relay " else "direct ") + path.remoteAddr +
                " rtt ${path.rttMs} ms, sent ${path.stats.udpTxDatagrams}, received ${path.stats.udpRxDatagrams}, " +
                "lost ${path.stats.lostPackets}, cwnd ${path.stats.cwnd}, mtu ${path.stats.currentMtu}"
        }
    }.getOrNull() ?: "unavailable"

    override fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        downOnce.set(true)
        route = null
        outbound.close()
        val link = connection
        scope.cancel()
        if (link != null) {
            // Cancel can run on the main thread when the service stops; keep
            // every iroh call off it.
            irohIo.launch {
                runCatching { link.close(0, "cancelled".toByteArray(Charsets.UTF_8)) }
            }
        }
    }
}
