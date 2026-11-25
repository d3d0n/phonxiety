package com.phonxiety.bridge.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.phonxiety.bridge.AppStorage
import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.model.AttachmentControl
import com.phonxiety.bridge.model.ControlEnvelope
import com.phonxiety.bridge.model.DeviceAuth
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.EndpointTicket
import computer.iroh.IrohAndroid
import computer.iroh.RecvStream
import computer.iroh.SecretKey
import computer.iroh.SendStream
import computer.iroh.presetN0
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
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
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString

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
object IrohSupport {
    private const val TAG = "PhonxietyBridge"
    private const val SECRET_KEY_FILE = "iroh-secret.key"
    private const val SECRET_KEY_BYTES = 32
    private const val NETWORK_WAIT_MS = 30_000L

    /** ALPN of the device protocol, per the iroh link contract. */
    val ALPN: ByteArray = "phonxiety/device/2".toByteArray(Charsets.UTF_8)

    private val contextInstalled = AtomicBoolean(false)
    private val endpointMutex = Mutex()

    @Volatile private var endpointRef: Endpoint? = null

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
                    secretKey = key
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
                        capabilities: NetworkCapabilities
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
                continuation.invokeOnCancellation { unregister(manager, callback) }
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
            BridgeLog.w(TAG, "could not persist the iroh secret key: ${e.javaClass.simpleName}")
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
     * Clears the process-wide endpoint so the next link binds a fresh one, and
     * shuts the old one down without blocking the caller.  Done when Android's
     * default network changes: an endpoint bound on the old network can stay on
     * the relay or wedged in reconnects (iroh#4588, iroh#4476), a fresh one finds
     * the paths of the new network.
     */
    fun recycleEndpoint() {
        val endpoint = endpointRef ?: return
        endpointRef = null
        stopAsync(endpoint, "phonxiety-iroh-recycle")
    }

    /**
     * Calls [onChange] whenever Android's default network is lost or replaced by
     * another one, until the returned function unregisters the callback.  The
     * network current at registration does not count as a change.
     */
    fun watchNetwork(context: Context, onChange: (String) -> Unit): () -> Unit {
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
            ({ unregister(manager, callback) })
        } catch (_: Exception) {
            {}
        }
    }

    private fun stopAsync(endpoint: Endpoint, name: String) {
        irohIo.launch {
            try {
                endpoint.shutdown()
            } catch (t: Throwable) {
                BridgeLog.w(TAG, "iroh endpoint close failed ($name): ${t.javaClass.simpleName}")
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
    private val ownsCall: () -> Boolean
) : DeviceLinkFactory {
    override fun create(): DeviceLink = IrohLink(context, config, ownsCall)

    override fun shutdown() {
        IrohSupport.shutdownAsync()
    }
}

/**
 * One iroh link, protocol v2: dial the daemon endpoint from the stored ticket,
 * open the bidirectional control stream, authenticate with the first frame on
 * it, then open the phone→daemon audio stream and accept the daemon→phone one.
 * Text frames travel on control, binary frames (audio, marks, link test) on the
 * audio streams; a frame on the wrong stream is a protocol error.
 *
 * Reader coroutines per stream, a writer coroutine per send stream, a route
 * sampler and the datagram probe echo run for the life of the connection; any
 * failure reports down once.  Extra bidirectional streams the daemon opens
 * after auth are tunnel requests and go to [AdbTunnel].  The wire framing is
 * [Framing]; this class only decides what to send where.
 */
private class IrohLink(
    private val context: Context,
    private val config: BridgeConfig.Iroh,
    private val ownsCall: () -> Boolean
) : DeviceLink {
    private companion object {
        const val TAG = "PhonxietyBridge"
        const val ROUTE_INTERVAL_MS = 1_000L

        /** Send priority of the control stream, above the audio stream. */
        const val CONTROL_PRIORITY = 1

        /** Send priority of the phone→daemon audio stream. */
        const val AUDIO_PRIORITY = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Extra bidirectional streams the daemon may open on this connection. */
    private val tunnels = AdbTunnel()
    private val controlOutbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val audioOutbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val backlog = AtomicLong(0)
    private val downOnce = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    @Volatile private var connection: Connection? = null

    @Volatile private var route: DeviceLink.Route? = null

    @Volatile private var unwatchNetwork: () -> Unit = {}

    override val backlogBytes: Long get() = backlog.get()

    override val supportsAttachments: Boolean get() = true

    override fun route(): DeviceLink.Route? = route

    override suspend fun openAttachment(header: String): AttachmentChannel? {
        if (cancelled.get() || downOnce.get()) return null
        val link = connection ?: return null
        return try {
            val stream = link.openBi()
            val send = stream.send()
            val frame = Framing.encode(Framing.KIND_TEXT, header.toByteArray(Charsets.UTF_8)) ?: return null
            runCatching { send.setPriority(CONTROL_PRIORITY) }
            send.writeAll(frame)
            IrohAttachmentChannel(stream.recv(), send)
        } catch (t: Throwable) {
            if (!cancelled.get()) BridgeLog.w(TAG, "attachment open failed: ${t.javaClass.simpleName}")
            null
        }
    }

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
            // Control: one bidirectional stream, the auth frame first.
            val control = link.openBi()
            val controlSend = control.send()
            val auth = Framing.encode(Framing.KIND_TEXT, authMessage())
                ?: return fail(listener, "connect_failed")
            controlSend.writeAll(auth)
            if (cancelled.get()) {
                runCatching { link.close(0, "cancelled".toByteArray()) }
                return
            }
            // Audio: the phone→daemon stream opens right after auth, the
            // daemon→phone one is accepted by audioReadLoop.  Control keeps the
            // higher send priority so a queued audio burst cannot delay commands.
            val audioSend = link.openUni()
            runCatching { controlSend.setPriority(CONTROL_PRIORITY) }
            runCatching { audioSend.setPriority(AUDIO_PRIORITY) }
            listener.onLinkOpened()
            scope.launch { readLoop(control.recv(), Framing.KIND_TEXT, listener) }
            scope.launch { audioReadLoop(link, listener) }
            scope.launch { tunnels.acceptLoop(link, scope) }
            scope.launch { writeLoop(controlSend, controlOutbound, listener) }
            scope.launch { writeLoop(audioSend, audioOutbound, listener) }
            scope.launch {
                val reason = runCatching { link.closed() }.getOrDefault("closed")
                fail(listener, reason.ifEmpty { "closed" })
            }
            scope.launch { sampleRoute(link) }
            scope.launch { datagramLoop(link) }
            unwatchNetwork = IrohSupport.watchNetwork(context) { reason -> onNetworkChange(reason, listener) }
            if (cancelled.get()) unwatchNetwork()
        } catch (t: Throwable) {
            if (!cancelled.get()) {
                BridgeLog.w(TAG, "iroh connect failed: ${t.javaClass.simpleName}")
                fail(listener, "connect_failed")
            }
        }
    }

    private fun authMessage(): ByteArray = Json
        .encodeToString(DeviceAuth.serializer(), DeviceAuth(token = config.token))
        .toByteArray(Charsets.UTF_8)

    /**
     * Reads framed messages from one stream.  [kind] is the only frame kind the
     * stream may carry: text on control, binary on an audio stream.  A frame of
     * the other kind is a protocol error.
     */
    private suspend fun readLoop(incoming: RecvStream, kind: Int, listener: DeviceLink.Listener) {
        try {
            while (true) {
                val header = incoming.readExact(Framing.HEADER_BYTES.toUInt())
                if (header.size != Framing.HEADER_BYTES) {
                    fail(listener, "closed")
                    return
                }
                val parsed = Framing.parse(header)
                if (parsed == null || parsed.kind != kind) {
                    fail(listener, "protocol")
                    return
                }
                val payload = incoming.readExact(parsed.length.toUInt())
                if (payload.size != parsed.length) {
                    fail(listener, "closed")
                    return
                }
                if (kind == Framing.KIND_TEXT) {
                    listener.onLinkText(payload.toString(Charsets.UTF_8))
                } else {
                    listener.onLinkBinary(payload.toByteString())
                }
            }
        } catch (t: Throwable) {
            if (!cancelled.get()) fail(listener, "closed")
        }
    }

    /** Accepts the daemon→phone audio stream after auth, then reads its frames. */
    private suspend fun audioReadLoop(link: Connection, listener: DeviceLink.Listener) {
        val incoming = try {
            link.acceptUni()
        } catch (t: Throwable) {
            if (!cancelled.get()) fail(listener, "closed")
            return
        }
        readLoop(incoming, Framing.KIND_BINARY, listener)
    }

    private suspend fun writeLoop(
        outgoing: SendStream,
        queue: Channel<ByteArray>,
        listener: DeviceLink.Listener
    ) {
        try {
            for (bytes in queue) {
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

    /**
     * Echoes the daemon's `PXLT` datagram probes, which measure the raw path
     * during a call, unchanged.  Other datagrams are ignored, as is a probe
     * that no longer fits the connection's current limit.
     */
    private suspend fun datagramLoop(link: Connection) {
        while (!cancelled.get() && !downOnce.get()) {
            val datagram = try {
                link.readDatagram()
            } catch (_: Throwable) {
                // The connection is gone; the closed watcher reports the reason.
                return
            }
            if (!isLinkProbe(datagram)) continue
            val limit = try {
                link.maxDatagramSize()?.toLong()
            } catch (_: Throwable) {
                null
            }
            if (limit != null && (limit <= 0L || datagram.size > limit)) continue
            runCatching { link.sendDatagram(datagram) }
        }
    }

    private fun isLinkProbe(bytes: ByteArray): Boolean = bytes.size >= 4 &&
        bytes[0] == 0x50.toByte() && // P
        bytes[1] == 0x58.toByte() && // X
        bytes[2] == 0x4C.toByte() && // L
        bytes[3] == 0x54.toByte() // T

    /**
     * The default network changed under a live link: drop the link and the
     * endpoint so the reconnect binds afresh on the new network.  During a call
     * iroh keeps migrating the connection instead; dropping it would end the call.
     */
    private fun onNetworkChange(reason: String, listener: DeviceLink.Listener) {
        if (cancelled.get() || downOnce.get()) return
        if (ownsCall()) {
            BridgeLog.i(TAG, "$reason during a call: keeping the iroh endpoint")
            return
        }
        BridgeLog.i(TAG, "$reason: recreating the iroh endpoint")
        // Before reporting down, so the reconnect cannot pick up the old endpoint.
        IrohSupport.recycleEndpoint()
        fail(listener, reason)
    }

    private suspend fun sampleRoute(link: Connection) {
        var lastPath: String? = null
        while (scope.isActive && !cancelled.get()) {
            try {
                val selected = link.paths().firstOrNull { it.isSelected }
                val path = selected?.let { (if (it.isRelay) "relay " else "direct ") + it.remoteAddr }
                if (path != lastPath) {
                    lastPath = path
                    BridgeLog.i(TAG, "iroh path: ${path ?: "none"}")
                }
                route = selected?.let {
                    val rtt = it.rttMs.toLong()
                    DeviceLink.Route(relay = it.isRelay, rttMs = if (rtt > 0L) rtt else null)
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
        return enqueue(controlOutbound, frame)
    }

    override fun sendBinary(frame: ByteArray, length: Int) {
        val size = length.coerceIn(0, frame.size)
        if (size == 0) return
        val encoded = Framing.encode(Framing.KIND_BINARY, frame, size) ?: return
        enqueue(audioOutbound, encoded)
    }

    private fun enqueue(queue: Channel<ByteArray>, bytes: ByteArray): Boolean {
        if (cancelled.get() || downOnce.get()) return false
        backlog.addAndGet(bytes.size.toLong())
        if (queue.trySend(bytes).isFailure) {
            backlog.addAndGet(-bytes.size.toLong())
            return false
        }
        return true
    }

    private fun fail(listener: DeviceLink.Listener, reason: String) {
        if (cancelled.get()) return
        if (!downOnce.compareAndSet(false, true)) return
        unwatchNetwork()
        BridgeLog.w(TAG, "iroh paths at link down ($reason): ${describePaths()}")
        route = null
        runCatching { connection?.close(0, reason.toByteArray(Charsets.UTF_8)) }
        controlOutbound.close()
        audioOutbound.close()
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
        unwatchNetwork()
        route = null
        controlOutbound.close()
        audioOutbound.close()
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

/**
 * One phone-opened bulk stream: binary frames are data, a text frame ends the
 * stream (or reports the daemon's error).  The stream stays independent of the
 * control and audio streams, so a large recording never delays a command.
 */
private class IrohAttachmentChannel(
    private val incoming: RecvStream,
    private val outgoing: SendStream
) : AttachmentChannel {
    private val done = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    override suspend fun next(): AttachmentFrame {
        if (done.get()) return AttachmentFrame.Error("closed", "attachment stream is closed")
        return try {
            val header = incoming.readExact(Framing.HEADER_BYTES.toUInt())
            if (header.size != Framing.HEADER_BYTES) {
                done.set(true)
                return AttachmentFrame.Error("closed", "the daemon closed the attachment stream")
            }
            val parsed = Framing.parse(header)
                ?: return AttachmentFrame.Error("protocol", "invalid attachment frame header")
            val payload = incoming.readExact(parsed.length.toUInt())
            if (payload.size != parsed.length) {
                done.set(true)
                return AttachmentFrame.Error("closed", "the attachment stream ended mid-frame")
            }
            if (parsed.kind == Framing.KIND_BINARY) {
                AttachmentFrame.Data(payload)
            } else {
                when (val control = ControlEnvelope.decodeAttachmentControl(payload.toString(Charsets.UTF_8))) {
                    is AttachmentControl.End -> {
                        done.set(true)
                        AttachmentFrame.End(control.total, control.sha256)
                    }

                    is AttachmentControl.Error -> {
                        done.set(true)
                        AttachmentFrame.Error(control.code, control.message)
                    }

                    null -> AttachmentFrame.Error("protocol", "unexpected attachment control frame")
                }
            }
        } catch (t: Throwable) {
            done.set(true)
            AttachmentFrame.Error("closed", "the attachment stream failed: ${t.javaClass.simpleName}")
        }
    }

    override fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        irohIo.launch {
            runCatching { outgoing.finish() }
            runCatching { incoming.stop(0uL) }
        }
    }
}
