package com.phonxiety.bridge.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.phonxiety.bridge.AppStorage
import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.IdleLinkAction
import com.phonxiety.bridge.model.AttachmentControl
import com.phonxiety.bridge.model.ControlEnvelope
import com.phonxiety.bridge.model.DeviceAuth
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointOptions
import computer.iroh.EndpointTicket
import computer.iroh.IrohAndroid
import computer.iroh.PathChangeCallback
import computer.iroh.PathSnapshot
import computer.iroh.RecvStream
import computer.iroh.RelayMode
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
 * One bound endpoint and the generation that identifies it.  A link captures
 * the generation so a late callback can recycle only the endpoint it dialled,
 * never one a newer link bound.
 */
internal class IrohEndpoint internal constructor(
    val endpoint: Endpoint,
    val generation: Long
)

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
    private const val NETWORK_WAIT_MS = 8_000L

    /** ALPN of the device protocol, per the iroh link contract. */
    val ALPN: ByteArray = "phonxiety/device/2".toByteArray(Charsets.UTF_8)

    private val contextInstalled = AtomicBoolean(false)
    private val endpointMutex = Mutex()

    /** Guards [current] and [generationSeq]: one endpoint, one generation at a time. */
    private val endpointState = Any()

    /** Relays the phone's endpoint uses; [RelayModeSetting.DEFAULT] leaves the preset in charge. */
    @Volatile private var relayMode: RelayModeSetting = RelayModeSetting.DEFAULT

    @Volatile private var relayUrls: List<String> = emptyList()

    @Volatile private var current: IrohEndpoint? = null

    private var generationSeq = 0L

    /**
     * The endpoint and the generation it was bound under.  A link remembers
     * its generation so it can recycle only the endpoint it dialled through,
     * never the one a newer link bound after a replacement.
     */
    internal suspend fun binding(context: Context): IrohEndpoint {
        current?.let { return it }
        return endpointMutex.withLock {
            current ?: bind(context.applicationContext).also { bound ->
                synchronized(endpointState) { current = bound }
            }
        }
    }

    private suspend fun bind(context: Context): IrohEndpoint {
        installAndroidContext(context)
        val key = secretKey(context)
        val endpoint = withContext(Dispatchers.IO) {
            awaitNetwork(context)
            Endpoint.bind(
                EndpointOptions(
                    preset = presetN0(),
                    secretKey = key,
                    relayMode = relayOption()
                )
            )
        }
        return IrohEndpoint(endpoint, synchronized(endpointState) { ++generationSeq })
    }

    /**
     * An endpoint bound before Android has an internet-capable network never
     * recovers a direct path, so wait for one first.  Gives up after
     * [NETWORK_WAIT_MS] and binds anyway: the supervisor's attempt deadline and
     * backoff own the rest.
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

    /**
     * Sets the relay choice the next endpoint bind uses and reports whether it
     * differs from the one in force.  A bound endpoint keeps its relays until
     * it is recycled: the engine does that right away when no call is owned,
     * so a change from the settings screen takes effect on the next bind
     * without cutting a call mid-sentence.
     */
    fun configureRelays(mode: RelayModeSetting, urls: List<String>): Boolean {
        val clean = RelayOptions.sanitize(urls)
        synchronized(this) {
            if (mode == relayMode && clean == relayUrls) return false
            relayMode = mode
            relayUrls = clean
            return true
        }
    }

    /** The iroh relay option for the current settings; null means the preset's own map. */
    private fun relayOption(): RelayMode? = try {
        when (relayMode) {
            RelayModeSetting.DEFAULT -> null

            RelayModeSetting.DISABLED -> RelayMode.disabled()

            RelayModeSetting.DAEMON, RelayModeSetting.CUSTOM ->
                relayUrls.takeIf { it.isNotEmpty() }?.let { RelayMode.customFromUrls(it) }
        }
    } catch (t: Throwable) {
        // A malformed URL is refused by the bindings; the preset map still works.
        BridgeLog.w(TAG, "iroh relay settings refused: ${t.javaClass.simpleName}")
        null
    }

    /** Shuts the endpoint down without blocking the caller. */
    fun shutdownAsync() {
        val bound = synchronized(endpointState) {
            current.also { current = null }
        } ?: return
        stopAsync(bound.endpoint, "phonxiety-iroh-close")
    }

    /**
     * Clears the process-wide endpoint so the next link binds a fresh one, and
     * shuts the old one down without blocking the caller.  Done when Android's
     * default network changes: an endpoint bound on the old network can stay on
     * the relay or wedged in reconnects (iroh#4588, iroh#4476), a fresh one finds
     * the paths of the new network.
     */
    fun recycleEndpoint() {
        val bound = synchronized(endpointState) {
            current.also { current = null }
        } ?: return
        stopAsync(bound.endpoint, "phonxiety-iroh-recycle")
    }

    /**
     * Recycles the endpoint only while [generation] is still the bound one: a
     * link callback that lost the race to its own cancellation and replacement
     * must never close the endpoint a newer link dialled through.
     */
    fun recycleEndpoint(generation: Long): Boolean {
        val bound = synchronized(endpointState) {
            val live = current ?: return false
            if (live.generation != generation) return false
            current = null
            live
        }
        stopAsync(bound.endpoint, "phonxiety-iroh-recycle")
        return true
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

/** What came of one destructive link action. */
internal enum class IrohActionOutcome {
    /** The action ran: the endpoint may be recycled and the link reported down. */
    EXECUTED,

    /** The link was already finished (cancelled or down): nothing was touched. */
    LINK_CLOSED
}

/**
 * Runs a link's destructive decisions — recycle the endpoint, report down —
 * at most once, serialized with the link's own cancellation for the recycle,
 * which is applied only to the generation this link bound.
 *
 * Cancellation sets the link's closed flag, so a callback that arrives after
 * [close] sees [IrohActionOutcome.LINK_CLOSED] and never touches the endpoint
 * a newer link bound.  The monitor covers only the check-and-recycle: reporting
 * down reaches the supervisor, which may call back into the link's cancellation
 * under its own lock, so it must never run while this monitor is held.
 */
internal class IrohLinkLifecycle(
    private val recycleEndpoint: (generation: Long) -> Boolean,
    private val isClosed: () -> Boolean
) {
    private companion object {
        const val NO_GENERATION = -1L
    }

    private val monitor = Any()
    private var generation = NO_GENERATION
    private var closed = false

    /** Records the endpoint generation this link dialled through. */
    fun bind(generation: Long) {
        synchronized(monitor) {
            if (!closed) this.generation = generation
        }
    }

    /** The link is finished; a later action must not touch the endpoint. */
    fun close() {
        synchronized(monitor) { closed = true }
    }

    /**
     * Recycles this link's endpoint generation when [recycle] is true and then
     * runs [reportDown].
     */
    fun run(recycle: Boolean, reportDown: () -> Unit): IrohActionOutcome {
        synchronized(monitor) {
            if (closed || isClosed()) return IrohActionOutcome.LINK_CLOSED
            if (recycle && generation != NO_GENERATION) recycleEndpoint(generation)
        }
        reportDown()
        return IrohActionOutcome.EXECUTED
    }
}

/**
 * The admission token an escalation took, carried across the link's one
 * terminal transition.
 *
 * A relay-only escalation or a network recycle admits itself before it acts,
 * and the transition it triggers may not be its own: the endpoint shutdown it
 * starts can make a reader or the closed watcher win `downOnce` first.  If the
 * escalation released the token when its own `fail()` returned, the door would
 * open while the winning failure has not yet reached the supervisor and the
 * engine — a dial reserving a call in that window is aborted by the very
 * link-down still being published.  So the token is handed to the transition
 * instead: whichever failure claims it releases the token after
 * [DeviceLink.Listener.onLinkDown] has returned, i.e. once the supervisor has
 * dropped the link and the engine has seen it down.
 *
 * A link that is already finished has no transition left to carry the token;
 * [attach] then refuses and its caller releases it at once.  [finish] is also
 * what a cancelled link calls: the supervisor cancels a link only after it
 * dropped it, so no publication is left to release the token.
 */
internal class IrohLinkAdmission {
    private val lock = Any()
    private var finished = false
    private var held: IdleLinkAction? = null

    /** Hands [action] to the terminal transition; false means the caller must release it itself. */
    fun attach(action: IdleLinkAction): Boolean = synchronized(lock) {
        if (finished) return false
        check(held == null) { "one escalation token at a time" }
        held = action
        true
    }

    /**
     * The link's end is final: release the token, if one is attached, outside
     * the registry lock so a dial waiting on the admission can reserve.
     */
    fun finish() {
        val action = synchronized(lock) {
            if (finished) return
            finished = true
            held.also { held = null }
        }
        action?.release()
    }
}

/**
 * The link's late-bound subscriptions: the relay-only monitor and Android's
 * default-network watch.
 *
 * Their registration finishes after readers may already have failed the link,
 * so both sides share one lock and publication checks the link's terminal state
 * (cancellation *and* the one-time down flag): a handle offered after the end
 * is refused and closed by its caller at once, and one attached before the end
 * is released by [detachAll].  Nothing outlives the link through its own
 * native lifetime.
 */
internal class IrohLinkWatchers(private val isClosed: () -> Boolean) {
    private val lock = Any()
    private var stopped = false
    private var monitor: IrohPathMonitor? = null
    private var networkWatch: (() -> Unit)? = null

    /** The monitor while it is attached; null once the link ended. */
    fun monitor(): IrohPathMonitor? = synchronized(lock) { if (stopped) null else monitor }

    /** Attaches the relay-only monitor; false means the caller cancels it now. */
    fun attachMonitor(pathMonitor: IrohPathMonitor): Boolean = attach { monitor = pathMonitor }

    /** Attaches Android's network watch; false means the caller unregisters it now. */
    fun attachNetworkWatch(release: () -> Unit): Boolean = attach { networkWatch = release }

    /** Stops accepting handles and returns every release to run outside the lock. */
    fun detachAll(): List<() -> Unit> = synchronized(lock) {
        stopped = true
        val releases = ArrayList<() -> Unit>(2)
        networkWatch?.let { releases += it }
        monitor?.let { attached -> releases += { attached.cancel() } }
        networkWatch = null
        monitor = null
        releases
    }

    private fun attach(store: () -> Unit): Boolean = synchronized(lock) {
        if (stopped || isClosed()) {
            false
        } else {
            store()
            true
        }
    }
}

internal class IrohLinkFactory(
    private val context: Context,
    private val config: BridgeConfig.Iroh,
    private val ownsCall: () -> Boolean,
    private val beginIdleLinkAction: () -> IdleLinkAction?
) : DeviceLinkFactory {
    /**
     * Escalation history for the paired endpoint: one instance outlives every
     * link this factory creates and every endpoint recycle, so the recycle
     * window and the cooldown are measured across reconnects.
     */
    private val policy = IrohEscalationPolicy(clock = { System.nanoTime() / 1_000_000L })

    override fun create(): DeviceLink = IrohLink(context, config, ownsCall, beginIdleLinkAction, policy)

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
    private val ownsCall: () -> Boolean,
    private val beginIdleLinkAction: () -> IdleLinkAction?,
    private val policy: IrohEscalationPolicy
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

    /** Late-bound subscriptions; publication is terminal-state aware. */
    private val watchers = IrohLinkWatchers { cancelled.get() || downOnce.get() }

    /** Carries an escalation's admission token to whichever failure ends this link. */
    private val admission = IrohLinkAdmission()

    /** Serializes a destructive escalation with cancellation; recycles this link's endpoint only. */
    private val lifecycle = IrohLinkLifecycle(
        recycleEndpoint = { generation -> IrohSupport.recycleEndpoint(generation) },
        isClosed = { cancelled.get() || downOnce.get() }
    )

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
            val bound = IrohSupport.binding(context)
            lifecycle.bind(bound.generation)
            val endpoint = bound.endpoint
            val link = EndpointTicket.fromString(config.ticket).use { ticket ->
                ticket.endpointAddr().use { addr ->
                    endpoint.connect(dialAddress(endpoint, addr), IrohSupport.ALPN)
                }
            }
            connection = link
            // Control: one bidirectional stream, the auth frame first.
            val control = link.openBi()
            val controlSend = control.send()
            val auth = Framing.encode(Framing.KIND_TEXT, authMessage())
                ?: return fail(listener, "connect_failed")
            controlSend.writeAll(auth)
            if (cancelled.get() || downOnce.get()) {
                runCatching { link.close(0, "cancelled".toByteArray()) }
                return
            }
            // Audio: the phone→daemon stream opens right after auth, the
            // daemon→phone one is accepted by audioReadLoop.  Control keeps the
            // higher send priority so a queued audio burst cannot delay commands.
            val audioSend = link.openUni()
            runCatching { controlSend.setPriority(CONTROL_PRIORITY) }
            runCatching { audioSend.setPriority(AUDIO_PRIORITY) }
            if (cancelled.get() || downOnce.get()) {
                // An endpoint recycle or a reader ended the attempt while it
                // was opening: never publish it as live.
                runCatching { link.close(0, "closed".toByteArray(Charsets.UTF_8)) }
                return
            }
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
            scope.launch { datagramLoop(link) }
            // The relay-only watchdog: push updates from watchPaths plus the 1 s sample.
            val pathMonitor = IrohPathMonitor(
                policy = policy,
                scheduler = CoroutineLinkScheduler(scope),
                ownsCall = ownsCall,
                onEscalate = { escalation -> onRelayOnly(escalation, listener) }
            )
            // A reader may have failed while this registration ran: publication
            // is terminal-state aware, so a handle is closed at once instead of
            // outliving the link through its own native lifetime.
            if (watchers.attachMonitor(pathMonitor)) {
                pathMonitor.start(pathSource(link))
                scope.launch { samplePaths(link, pathMonitor) }
            } else {
                pathMonitor.cancel()
            }
            // Android's default-network callback is the only network-change source.
            // iroh's own netmon (`Endpoint.watchNetworkChange`) is not subscribed: on
            // Android 17 it reported changes continuously, thousands per second, and
            // recycled the endpoint at every cooldown expiry while the network stayed
            // the same.  iroh still migrates the connection internally on address
            // changes; only a replaced or lost default network recycles the endpoint.
            val unwatch = IrohSupport.watchNetwork(context) { reason -> onNetworkChange(reason, listener) }
            if (!watchers.attachNetworkWatch(unwatch)) unwatch()
        } catch (t: Throwable) {
            if (!cancelled.get()) {
                BridgeLog.w(TAG, "iroh connect failed: ${t.javaClass.simpleName}")
                fail(listener, "connect_failed")
            }
        }
    }

    /**
     * The address the phone dials: the ticket's endpoint id, its relay, and the
     * direct addresses merged from the ticket and the endpoint's cache (see
     * [IrohAddressPlanner]).  A cache miss is not an error: the ticket alone
     * still reaches the daemon over the relay.
     */
    private suspend fun dialAddress(endpoint: Endpoint, ticketAddr: EndpointAddr): EndpointAddr {
        val id = ticketAddr.id()
        val cached = runCatching { endpoint.remoteAddr(id) }.getOrNull()
        val cachedAddresses = cached?.let { runCatching { it.directAddresses() }.getOrNull() }
        val cachedRelay = cached?.let { runCatching { it.relayUrl() }.getOrNull() }
        cached?.close()
        val plan = IrohAddressPlanner.plan(
            ticketAddresses = ticketAddr.directAddresses(),
            ticketRelay = ticketAddr.relayUrl(),
            cachedAddresses = cachedAddresses,
            cachedRelay = cachedRelay,
            subnets = activeSubnets()
        )
        return EndpointAddr(id, plan.relayUrl, plan.addresses)
    }

    /** Prefixes of the active network, or null when the platform cannot say. */
    private fun activeSubnets(): List<IrohSubnet>? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val properties = manager.getLinkProperties(network) ?: return null
        return properties.linkAddresses.map { linkAddress ->
            IrohSubnet(linkAddress.address.address, linkAddress.prefixLength)
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
     * The default network changed under a live link.  The connection's relay
     * evidence is stale either way; the endpoint is recycled and the link
     * dropped only when the policy admits it — no call owned and no recent
     * escalation or network recycle inside the cooldown.  During a call iroh
     * keeps migrating the connection; inside the cooldown this link keeps
     * running rather than churning a third endpoint.
     */
    private fun onNetworkChange(reason: String, listener: DeviceLink.Listener) {
        if (cancelled.get() || downOnce.get()) return
        val pathMonitor = watchers.monitor()
        pathMonitor?.noteNetworkChange()
        val action = beginIdleLinkAction() ?: run {
            BridgeLog.i(TAG, "$reason: a call or another link action owns the link, keeping the iroh endpoint")
            return
        }
        when (pathMonitor?.admitNetworkRecycle() ?: IrohNetworkDecision.HELD) {
            IrohNetworkDecision.RECYCLE -> withAdmission(action) {
                lifecycle.run(recycle = true) {
                    BridgeLog.i(TAG, "$reason: recreating the iroh endpoint")
                    fail(listener, reason)
                }
            }

            IrohNetworkDecision.DURING_CALL -> {
                BridgeLog.i(TAG, "$reason during a call: keeping the iroh endpoint")
                action.release()
            }

            IrohNetworkDecision.HELD -> {
                BridgeLog.i(TAG, "$reason inside the escalation cooldown: keeping the iroh link")
                action.release()
            }
        }
    }

    /**
     * Runs [act] with [action] handed to the link's terminal transition: the
     * winning failure releases it after the engine has seen the link down.
     * A finished link has no transition left, so the token comes back here and
     * null tells the caller nothing was escalated.
     */
    private fun <T> withAdmission(action: IdleLinkAction, act: () -> T): T? {
        if (!admission.attach(action)) {
            action.release()
            return null
        }
        try {
            return act()
        } catch (t: Throwable) {
            // Nothing was published: give the door back instead of leaving it shut.
            admission.finish()
            throw t
        }
    }

    /** Subscribes the monitor to the connection's own path-change events. */
    private fun pathSource(link: Connection): IrohPathSource = IrohPathSource { onPath ->
        val handle = link.watchPaths(object : PathChangeCallback {
            override suspend fun onChange(paths: List<PathSnapshot>) = onPath(kindOf(paths))
        })
        IrohPathWatch { runCatching { handle.close() } }
    }

    private fun kindOf(paths: List<PathSnapshot>): IrohPathKind = when (paths.firstOrNull { it.isSelected }?.isRelay) {
        null -> IrohPathKind.UNKNOWN
        true -> IrohPathKind.RELAY
        false -> IrohPathKind.DIRECT
    }

    /**
     * The fallback the push events cannot replace: one snapshot per second that
     * keeps [route] for the UI fresh and feeds the same path kind to the
     * relay-only monitor, so a lost watcher event never hides a relay residence.
     */
    private suspend fun samplePaths(link: Connection, monitor: IrohPathMonitor) {
        var lastPath: String? = null
        while (scope.isActive && !cancelled.get()) {
            try {
                val snapshots = link.paths()
                val selected = snapshots.firstOrNull { it.isSelected }
                val path = selected?.let { (if (it.isRelay) "relay " else "direct ") + it.remoteAddr }
                if (path != lastPath) {
                    lastPath = path
                    BridgeLog.i(TAG, "iroh path: ${path ?: "none"}")
                }
                route = selected?.let {
                    val rtt = it.rttMs.toLong()
                    DeviceLink.Route(relay = it.isRelay, rttMs = if (rtt > 0L) rtt else null)
                }
                monitor.observe(kindOf(snapshots))
            } catch (_: Throwable) {
                return
            }
            delay(ROUTE_INTERVAL_MS)
        }
    }

    /**
     * The policy says the relay only would no longer do: recycle this link's
     * endpoint when this is a repeated escalation, then report down so
     * LinkSupervisor re-dials through the usual backoff.  The engine admission
     * makes the check-and-act atomic: a call reserved a moment earlier is never
     * aborted by this callback, and the run is serialized with cancellation so
     * a stale callback cannot close a replacement endpoint.  The admission is
     * not released here: the failure that actually ends the link releases it,
     * and only once the supervisor and the engine have seen the link down.
     *
     * Returns false only when the action was refused and may be retried (a call
     * owns the link); a finished link counts as handled.
     */
    private fun onRelayOnly(escalation: IrohEscalation, listener: DeviceLink.Listener): Boolean {
        val action = beginIdleLinkAction() ?: run {
            BridgeLog.i(TAG, "relay only: a call owns the link, keeping the iroh link")
            return false
        }
        // The admission outlives this callback: whichever failure wins the
        // link's end — this one, or the closed watcher the endpoint shutdown
        // wakes first — releases it only after the engine has seen the link
        // down, so a dial arriving meanwhile waits instead of reserving a call
        // that very failure is about to abort.
        val handled = withAdmission(action) {
            lifecycle.run(escalation.recycleEndpoint) {
                BridgeLog.w(
                    TAG,
                    if (escalation.recycleEndpoint) {
                        "relay only again: recycling the iroh endpoint before reconnecting"
                    } else {
                        "relay only: reconnecting the iroh link"
                    }
                )
                fail(listener, "relay_only")
            } == IrohActionOutcome.EXECUTED
        }
        return handled ?: false
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

    /**
     * Reports the link down exactly once.  The winner of [downOnce] also owns
     * any escalation's admission token: it releases it in a `finally`, after
     * [DeviceLink.Listener.onLinkDown] has returned — the supervisor has
     * dropped this link and the engine has seen it down — never before, and a
     * losing failure releases nothing.
     */
    private fun fail(listener: DeviceLink.Listener, reason: String) {
        if (cancelled.get()) return
        if (!downOnce.compareAndSet(false, true)) return
        lifecycle.close()
        try {
            releaseWatchers()
            BridgeLog.w(TAG, "iroh paths at link down ($reason): ${describePaths()}")
            route = null
            runCatching { connection?.close(0, reason.toByteArray(Charsets.UTF_8)) }
            controlOutbound.close()
            audioOutbound.close()
            listener.onLinkDown(reason)
        } finally {
            admission.finish()
            scope.cancel()
        }
    }

    /** Releases every attached subscription outside the registry lock. */
    private fun releaseWatchers() {
        watchers.detachAll().forEach { release -> release() }
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
        lifecycle.close()
        releaseWatchers()
        // The supervisor drops a link before it cancels it, so no link-down
        // publication is left; a token an escalation still holds comes back now
        // instead of leaving the admission shut for good.
        admission.finish()
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
