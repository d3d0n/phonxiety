package com.phonxiety.bridge.net

import android.content.Context
import android.net.ConnectivityManager
import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.IdleLinkAction
import com.phonxiety.bridge.bridgeScope
import com.phonxiety.bridge.model.DeviceAuth
import com.phonxiety.bridge.model.LinkKind
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointTicket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

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
internal class IrohLink(
    private val context: Context,
    private val config: BridgeConfig.Iroh,
    private val ownsCall: () -> Boolean,
    private val beginIdleLinkAction: () -> IdleLinkAction?,
    private val policy: IrohEscalationPolicy,
    private val budget: IrohFailureBudget,
    private val tap: FrameTapSink
) : DeviceLink {
    private companion object {
        const val TAG = "Bridge:iroh"

        /** Send priority of the control stream, above the audio stream. */
        const val CONTROL_PRIORITY = 1

        /** Send priority of the phone→daemon audio stream. */
        const val AUDIO_PRIORITY = 0
    }

    private val scope = bridgeScope("phonxiety-iroh-link", Dispatchers.IO)

    /** Extra bidirectional streams the daemon may open on this connection. */
    private val tunnels = AdbTunnel()

    /** Bytes accepted by either send queue and not yet written; shared by both, as before. */
    private val backlog = AtomicLong(0)

    /** Control frames behind their one writer; bounded so a stall refuses instead of growing. */
    private val controlOutbound = IrohSendQueue(IrohSendQueue.CONTROL_CAPACITY, backlog, tap)

    /** Audio frames behind their one writer; unlimited and capped only by the supervisor's backlog. */
    private val audioOutbound = IrohSendQueue(IrohSendQueue.AUDIO_CAPACITY, backlog, tap)
    private val downOnce = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /** The engine listener, kept so a stalled control queue can fail the link like a reader would. */
    @Volatile private var listener: DeviceLink.Listener? = null

    /** Control sends: the bounded queue plus the one backlog failure a full queue asks for. */
    private val controlSender = IrohControlSender(
        queue = controlOutbound,
        schedule = { task -> scope.launch { task() } },
        reportDown = { reason -> listener?.let { fail(it, reason) } }
    )

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

    /** This attempt's view of the factory's shared failure budget, read at bind. */
    private val fresh: FreshBind = BudgetFreshBind(budget, lifecycle)

    /** Path evidence: push events, the 1 s sample and the dump logged at link down. */
    private val paths = IrohPathStats(
        scope = scope,
        link = { connection },
        isLive = { !cancelled.get() },
        onRoute = { route = it }
    )

    override val kind: LinkKind = LinkKind.IROH

    override val backlogBytes: Long get() = backlog.get()

    override val supportsAttachments: Boolean get() = true

    override fun route(): DeviceLink.Route? = route

    override suspend fun openAttachment(header: String): AttachmentChannel? {
        if (cancelled.get() || downOnce.get()) return null
        val link = connection ?: return null
        return try {
            val stream = link.openBi()
            val send = stream.send()
            val headerBytes = header.toByteArray(Charsets.UTF_8)
            val frame = Framing.encode(Framing.KIND_TEXT, headerBytes) ?: return null
            runCatching { send.setPriority(CONTROL_PRIORITY) }
            send.writeAll(frame)
            if (tap.enabled) tap.frame(FrameDirection.OUT, FrameStream.ATTACHMENT, Framing.KIND_TEXT, headerBytes.size)
            // The header goes out first, as it always has; only then do we take
            // the stream's read half.
            val incoming = stream.recv()
            IrohAttachmentChannel(incoming.asFrameSource(), tap) {
                irohIo.launch {
                    runCatching { send.finish() }
                    runCatching { incoming.stop(0uL) }
                }
            }
        } catch (t: Throwable) {
            if (!cancelled.get()) BridgeLog.w(TAG, "attachment open failed: ${t.javaClass.simpleName}")
            null
        }
    }

    override fun start(listener: DeviceLink.Listener) {
        this.listener = listener
        scope.launch { connect(listener) }
    }

    private suspend fun connect(listener: DeviceLink.Listener) {
        try {
            if (cancelled.get()) return
            val bound = IrohSupport.binding(context, fresh)
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
            val authPayload = authMessage()
            val auth = Framing.encode(Framing.KIND_TEXT, authPayload)
                ?: return fail(listener, "connect_failed")
            controlSend.writeAll(auth)
            if (tap.enabled) tap.frame(FrameDirection.OUT, FrameStream.CONTROL, Framing.KIND_TEXT, authPayload.size)
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
            scope.launch {
                readIrohFrames(control.recv().asFrameSource(), Framing.KIND_TEXT, FrameStream.CONTROL, tap, listener) { reason ->
                    fail(listener, reason)
                }
            }
            scope.launch { readIrohAudio(link, tap, listener) { reason -> fail(listener, reason) } }
            scope.launch { tunnels.acceptLoop(link, scope) }
            scope.launch {
                controlOutbound.drain(
                    stream = FrameStream.CONTROL,
                    kind = Framing.KIND_TEXT,
                    write = { controlSend.writeAll(it) },
                    onWriteFailed = { if (!cancelled.get()) fail(listener, "write_failed") }
                )
            }
            scope.launch {
                audioOutbound.drain(
                    stream = FrameStream.AUDIO,
                    kind = Framing.KIND_BINARY,
                    write = { audioSend.writeAll(it) },
                    onWriteFailed = { if (!cancelled.get()) fail(listener, "write_failed") }
                )
            }
            scope.launch {
                val reason = runCatching { link.closed() }.getOrDefault("closed")
                fail(listener, reason.ifEmpty { "closed" })
            }
            scope.launch { echoDatagrams(link, { cancelled.get() }, { downOnce.get() }) }
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
                pathMonitor.start(paths.source(link))
                scope.launch { paths.sample(link, pathMonitor) }
            } else {
                pathMonitor.cancel()
            }
            // Android's default-network callback is the only network-change source.
            // The iroh-ffi `Endpoint.watchNetworkChange` wrapper is not used: it loops on
            // iroh's `Endpoint::network_change()`, which *notifies* iroh of a change instead
            // of waiting for one, so it fired thousands of times per second and recycled
            // the endpoint at every cooldown expiry while the network stayed the same.
            // iroh still migrates the connection internally on address changes; only a
            // replaced or lost default network recycles the endpoint.
            val unwatch = watchNetwork(context) { reason -> onNetworkChange(reason, listener) }
            if (!watchers.attachNetworkWatch(unwatch)) unwatch()
        } catch (t: Throwable) {
            if (!cancelled.get()) {
                BridgeLog.w(TAG) { "iroh connect failed: ${t.javaClass.simpleName}" }
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
        val payload = message.toByteArray(Charsets.UTF_8)
        val frame = Framing.encode(Framing.KIND_TEXT, payload) ?: return false
        if (cancelled.get() || downOnce.get()) return false
        return controlSender.offer(frame, payload.size)
    }

    override fun sendBinary(frame: ByteArray, length: Int) {
        offerBinary(frame, length)
    }

    override fun offerBinary(frame: ByteArray, length: Int): Boolean {
        val size = length.coerceIn(0, frame.size)
        if (size == 0) return false
        val encoded = Framing.encode(Framing.KIND_BINARY, frame, size) ?: return false
        if (cancelled.get() || downOnce.get()) return false
        return audioOutbound.offer(encoded, size)
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
            BridgeLog.w(TAG) { "iroh paths at link down ($reason): ${paths.describe()}" }
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
