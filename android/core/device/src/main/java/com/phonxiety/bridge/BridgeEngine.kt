package com.phonxiety.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager
import android.telecom.Call
import android.telecom.TelecomManager
import com.phonxiety.bridge.audio.AudioStatsPublisher
import com.phonxiety.bridge.engine.AudioSettings
import com.phonxiety.bridge.engine.CallSession
import com.phonxiety.bridge.engine.CommandService
import com.phonxiety.bridge.engine.DiagnosticsService
import com.phonxiety.bridge.engine.HelloState
import com.phonxiety.bridge.engine.LinkEvents
import com.phonxiety.bridge.engine.SettingsSync
import com.phonxiety.bridge.engine.StateReportPublisher
import com.phonxiety.bridge.model.AppGateway
import com.phonxiety.bridge.model.AttachmentHeader
import com.phonxiety.bridge.model.AttachmentOutcome
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.Capabilities
import com.phonxiety.bridge.model.DeviceApiRequest
import com.phonxiety.bridge.model.DeviceApiResult
import com.phonxiety.bridge.model.DeviceOutbound
import com.phonxiety.bridge.model.DeviceProtocol
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.model.PairingGate
import com.phonxiety.bridge.net.ApiExchange
import com.phonxiety.bridge.net.ApiRequestTable
import com.phonxiety.bridge.net.CandidateLinkFactory
import com.phonxiety.bridge.net.CandidateMerge
import com.phonxiety.bridge.net.CandidateStore
import com.phonxiety.bridge.net.ConnectionMode
import com.phonxiety.bridge.net.DeviceLink
import com.phonxiety.bridge.net.ExecutorLinkScheduler
import com.phonxiety.bridge.net.InboundRouter
import com.phonxiety.bridge.net.LinkReadiness
import com.phonxiety.bridge.net.LinkSelector
import com.phonxiety.bridge.net.LinkSupervisor
import com.phonxiety.bridge.net.RequestFailure
import com.phonxiety.bridge.net.StoredCandidate
import com.phonxiety.bridge.net.Tailnet
import com.phonxiety.bridge.net.WebSocketCandidateProbe
import com.phonxiety.bridge.telecom.CallLedger
import com.phonxiety.bridge.telecom.TelecomObserver
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Protocol v3 device endpoint.
 *
 * Owns the single device link, the single bridge-owned Telecom call, its
 * audio path and the phone-side host-loss lease.  Every visible state change
 * is published on [state] by one serialized scope; [com.phonxiety.bridge.telecom.BridgeInCallService]
 * only forwards Telecom callbacks.
 *
 * The link is a TLS WebSocket to the paired daemon or an iroh stream; see
 * [LinkSupervisor] and [DeviceLink].  The engine keeps the protocol and call
 * control, the supervisor keeps the link lifecycle.  The foreground-service
 * notification follows [state].
 */
internal class BridgeEngine(
    context: Context,
    private val config: BridgeConfig,
    /** The pairing's other addresses, read together with the config that built this engine. */
    private val pairingCandidates: List<StoredCandidate> = emptyList()
) : AppGateway {
    companion object {
        private const val TAG = "Bridge:engine"
        private const val THREAD_NAME = "phonxiety-scheduler"
        private const val APP_REQUEST_TIMEOUT_MS = 30_000L
        private const val AUDIO_LEVEL_INTERVAL_MS = 100L

        /** How often an idle link re-probes its candidates; the selector's hysteresis decides. */
        private const val SELECTION_INTERVAL_S = 900L

        /**
         * How often the link re-checks the call permissions its hello announced.
         * Permission reads only: a change the daemon cannot see without a new
         * handshake is picked up within this window while the link is up.
         */
        private const val CAPABILITY_WATCH_INTERVAL_S = 10L
    }

    private val appContext = context.applicationContext
    private val ownerPackage = appContext.packageName
    private val deviceBootId: String = bootId()
    private val ledger = CallLedger(appContext, deviceBootId)

    private val closeOnce = AtomicBoolean(false)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val stateStore = EngineStateStore()

    /**
     * What the last queued `hello` told the daemon.  Kept apart from the UI's
     * [Capabilities], which the app-info answer also writes with the daemon's
     * voice-control support.
     */
    private val capabilityWatch = CapabilityWatch()

    /** Everything the UI, tiles and widget observe. */
    val state: StateFlow<BridgeState> get() = stateStore.state

    private val engineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phonxiety-state").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val engineScope = bridgeScope("phonxiety-engine", engineDispatcher)

    /**
     * The app API credential; the daemon re-sends it on every connect, so it can change at runtime.
     */
    private val mutableAppToken = MutableStateFlow(config.appToken)

    override val appToken: StateFlow<String?> = mutableAppToken.asStateFlow()

    /** The pairing this engine was built for; a service start compares it before reusing the engine. */
    internal val pairingIdentity: String = CandidateMerge.identity(config)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }
    private val commandExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phonxiety-commands").apply { isDaemon = true }
    }

    /**
     * Which candidate the link uses, kept across attempts so a reconnect prefers the
     * path that was working.  Reads the phone's mode and call state through the
     * lambdas because both change while the engine runs.
     */
    private val selector = LinkSelector()

    private val candidateStore = CandidateStore.of(appContext)

    private val telecom: TelecomManager? = appContext.getSystemService(TelecomManager::class.java)

    private val wakeLock: PowerManager.WakeLock? =
        appContext.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonxiety:bridge")
            ?.apply { setReferenceCounted(false) }

    @Volatile private var running = false

    private val session = CallSession(ledger, ownerPackage)
    private val audioSettings = AudioSettings()

    private val hello = HelloState(
        when (config) {
            is BridgeConfig.Address ->
                if (Tailnet.isTailnetHost(Tailnet.hostOf(config.url))) LinkKind.TAILSCALE else LinkKind.WEBSOCKET

            is BridgeConfig.Iroh -> LinkKind.IROH
        }
    )

    private val settings = SettingsSync(
        context = appContext,
        links = { links },
        scheduler = scheduler,
        audioSettings = audioSettings,
        hello = hello,
        wire = ::send,
        ownsCall = { session.ownsCall() },
        isRunning = { running }
    )

    private val report = StateReportPublisher(session, ledger, stateStore, ::send)

    private val audio: AudioStatsPublisher = AudioStatsPublisher(
        context = appContext,
        session = session,
        store = stateStore,
        wire = ::send,
        executor = commandExecutor,
        isRunning = { running },
        hasUnrelatedCall = { exclude -> observer.hasUnrelatedLiveCall(exclude) }
    )

    private val diagnostics: DiagnosticsService = DiagnosticsService(
        context = appContext,
        session = session,
        links = { links },
        store = stateStore,
        wire = ::send,
        json = json,
        isRunning = { running },
        ownerPackage = ownerPackage
    )

    private val commands: CommandService = CommandService(
        context = appContext,
        session = session,
        dials = ledger,
        telecom = telecom,
        store = stateStore,
        audio = audio,
        wire = ::send,
        executor = commandExecutor,
        linkUp = { links.isUp() },
        sendFrame = { frame, length -> links.sendFrame(frame, length) },
        unrelatedCall = { observer.hasUnrelatedLiveCall() },
        isRunning = { running },
        audioSettings = audioSettings,
        diagnostics = diagnostics,
        ownerPackage = ownerPackage
    )

    private val observer: TelecomObserver = TelecomObserver(
        session = session,
        dials = ledger,
        store = stateStore,
        report = report,
        audio = audio,
        ownerPackage = ownerPackage,
        isRunning = { running },
        cancelTone = { call -> commands.cancelToneFor(call) }
    )

    private val linkGeneration = AtomicLong(0L)

    private val requestTable = ApiRequestTable()

    private val requests = ApiExchange(
        table = requestTable,
        readiness = LinkReadiness(),
        pairingEpoch = { PairingGate.epoch() },
        send = { wire -> links.sendText(wire) }
    )

    private lateinit var links: LinkSupervisor

    /**
     * The one info request of a connection: asked after the daemon delivered
     * app access, only while idle, and applied only to the pairing that built
     * this engine.
     */
    private val appInfo = AppInfoRefresh(
        store = candidateStore,
        identity = { pairingIdentity },
        admission = session.admission,
        callOwned = { session.ownsCall() },
        linkUp = { links.isUp() && hello.delivered },
        token = { appToken.value },
        pairingEpoch = { PairingGate.epoch() },
        scope = engineScope,
        exchange = { request, onEnqueued -> exchangeBounded(request, APP_REQUEST_TIMEOUT_MS, onEnqueued) },
        apply = { info ->
            val current = stateStore.state.value.capabilities
            stateStore.capabilities(
                Capabilities(
                    callControl = info.voiceControls.hangup || info.voiceControls.instruction,
                    frameworkAudio = current.frameworkAudio,
                    appApi = info.appApi,
                    daemonVersion = info.daemonVersion
                )
            )
        }
    )

    private val events = LinkEvents(
        context = appContext,
        session = session,
        store = stateStore,
        publisher = report,
        wire = ::send,
        links = { links },
        settings = settings,
        hello = hello,
        appInfo = appInfo,
        requests = requests,
        routeText = { text -> inbound.route(text) },
        capabilityWatch = capabilityWatch,
        linkGeneration = linkGeneration,
        deviceBootId = deviceBootId,
        config = config,
        audioSettings = audioSettings,
        appTokenPresent = { appToken.value != null },
        unrelatedCall = { observer.hasUnrelatedLiveCall() },
        onCommand = { id, text -> commands.dispatch(id, text) },
        onAppAccess = { token, generation ->
            engineScope.launch { acceptAppToken(token, generation) }
        },
        onLinkDown = { observer.abortOwnedForLinkLoss() }
    )

    private val capabilityRefresh = CapabilityRefresh(
        watch = capabilityWatch,
        admission = session.admission,
        callOwned = { session.ownsCall() },
        sample = { events.currentCapabilities() },
        reconnect = { links.reconnect(it) }
    )

    private lateinit var inbound: InboundRouter

    /**
     * The inbound gate: an `api_result` completes its pending request before
     * anything else looks at the frame; an unknown id is logged and dropped.
     */
    private fun createInbound(): InboundRouter = InboundRouter(
        requests = requestTable,
        dispatch = { type, text -> events.dispatch(type, text) },
        log = { message -> BridgeLog.w(TAG) { message } }
    )

    init {
        links = LinkSupervisor(
            factory = CandidateLinkFactory(
                context = appContext,
                config = config,
                selector = selector,
                prober = WebSocketCandidateProbe(config.token),
                store = candidateStore,
                mode = { ConnectionMode.of(settings.phoneSettings.connectionMode) },
                ownsCall = { session.ownsCall() },
                duringCall = { session.ownsCall() },
                beginIdleLinkAction = { session.tryBeginIdleLinkAction() }
            ),
            callbacks = events,
            scheduler = ExecutorLinkScheduler(scheduler),
            clock = { android.os.SystemClock.elapsedRealtime() }
        )
        inbound = createInbound()
    }

    // ACCESS_NETWORK_STATE is a normal permission declared by this module; the
    // wake lock is held for the lifetime of the foreground service, which is
    // exactly the long-lived case the detector cannot express.
    @SuppressLint("MissingPermission", "WakelockTimeout")
    fun start() {
        // Read before the lock: Telecom's call list is an IPC, and the recovery below runs under it.
        val liveCalls = BridgeRuntime.currentCalls()
        synchronized(session.lock) {
            if (running) return
            running = true
            observer.restoreLedgerLocked(liveCalls)
        }
        stateStore.linkConnecting(hello.kind)
        runCatching { wakeLock?.acquire() }
        diagnostics.start(engineScope)
        settings.collectInto(engineScope)
        try {
            scheduler.schedule({
                // A config written from outside the app (the host pairing task) must
                // not leave another daemon's addresses behind this token.  The
                // addresses that file carries enter through the same binding the
                // QR path uses, and a failed binding leaves none usable.
                safe {
                    val bound = candidateStore.rebind(pairingIdentity, pairingCandidates)
                    if (bound == null) BridgeLog.w(TAG, "could not bind learned addresses to this pairing")
                }
                safe { observer.adoptExistingCalls() }
                safe { audio.restoreAbandonedAtStartup() }
                safe { links.start() }
            }, 0, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            return
        }
        scheduler.scheduleWithFixedDelay({ safe { links.tick() } }, 1, 1, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ safe { events.publishRoute() } }, 1, 1, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ safe { settings.considerSwitch() } }, SELECTION_INTERVAL_S, SELECTION_INTERVAL_S, TimeUnit.SECONDS)
        // An info request deferred by a call runs as soon as the link is idle.
        scheduler.scheduleWithFixedDelay({ safe { appInfo.tick() } }, 2, 2, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay(
            { safe { refreshCapabilitiesOnChange() } },
            CAPABILITY_WATCH_INTERVAL_S,
            CAPABILITY_WATCH_INTERVAL_S,
            TimeUnit.SECONDS
        )
        scheduler.scheduleWithFixedDelay({ safe { audio.emitPlayback() } }, 500, 500, TimeUnit.MILLISECONDS)
        scheduler.scheduleWithFixedDelay({ safe { audio.emitAudioLevels() } }, AUDIO_LEVEL_INTERVAL_MS, AUDIO_LEVEL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        scheduler.scheduleWithFixedDelay({ safe { observer.expirePendingDial() } }, 5, 5, TimeUnit.SECONDS)
        runCatching {
            appContext.getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(networkCallback)
        }
    }

    /** A link waiting out its backoff retries as soon as a network is back. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            try {
                scheduler.execute {
                    safe { links.networkAvailable() }
                    // A new network may make a better candidate reachable: one selection round.
                    safe { settings.considerSwitch() }
                }
            } catch (_: RejectedExecutionException) {
                // Closing.
            }
        }
    }

    fun close() {
        if (!closeOnce.compareAndSet(false, true)) return
        synchronized(session.lock) { running = false }
        engineScope.cancel()
        runCatching {
            appContext.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(networkCallback)
        }
        links.cancel()
        requests.stop(closeFailure())
        scheduler.shutdownNow()
        observer.abortOwnedOnShutdown()
        commandExecutor.shutdownNow()
        audio.drain()
        commands.shutdown()
        observer.close()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        settings.releaseWifiLock()
        links.shutdown()
        stateStore.reset()
        runCatching { engineDispatcher.close() }
    }

    /** `dialing`, `active` or `ending` while this engine owns a call. */
    fun activeCallState(): String? = session.activeCallState()

    fun linkIsUp(): Boolean = links.isUp()

    /**
     * Ends the call this engine owns on this device: the same core the daemon's
     * `hangup` runs, queued on the command executor.  True when a request was
     * queued for the owned or pending call, false when there is none.
     */
    fun endOwnedCall(callId: String?): Boolean = commands.endOwnedLocally(callId)

    /** Route of the live link for the UI, or null while down. */
    internal fun currentRoute(): DeviceLink.Route? = links.route()

    // ------------------------------------------------------------------
    // Device link
    // ------------------------------------------------------------------

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            BridgeLog.e(TAG) { "background task failed: ${t.javaClass.simpleName}" }
        }
    }

    private fun send(message: DeviceOutbound): Boolean = links.sendText(DeviceProtocol.encode(message))

    /**
     * Admits one idle-only link action — recycle the endpoint on a network
     * move — or refuses it while a call is owned or
     * being reserved.  The door stays shut until the action is released, so the
     * link can never close under a call placed a moment later.
     */
    internal fun tryBeginIdleLinkAction(): IdleLinkAction? = session.tryBeginIdleLinkAction()

    /**
     * One capability sample while the link is up.  A difference from the last
     * hello drops the link as `capabilities_changed`; the normal reconnect then
     * sends a hello carrying what the phone can do now.
     */
    private fun refreshCapabilitiesOnChange() {
        if (!running || !links.isUp()) return
        capabilityRefresh.run()
    }

    private fun bootId(): String {
        try {
            val count = android.provider.Settings.Global.getInt(
                appContext.contentResolver,
                android.provider.Settings.Global.BOOT_COUNT
            )
            return "android-boot-$count"
        } catch (_: Exception) {
            // Older/custom systems can still expose a stable kernel boot ID.
        }
        try {
            val file = File("/proc/sys/kernel/random/boot_id")
            if (file.isFile) {
                val value = file.readText().trim()
                if (value.isNotEmpty()) return value
            }
        } catch (_: Exception) {
            // Fall through to /proc/stat.
        }
        try {
            File("/proc/stat").useLines { lines ->
                val line = lines.firstOrNull { it.startsWith("btime ") }
                if (line != null) return line.trim().replace(' ', '-')
            }
        } catch (_: Exception) {
            // No stable identity is available from this process.
        }
        throw IllegalStateException("boot_identity_unavailable")
    }

    // ------------------------------------------------------------------
    // Telecom calls
    // ------------------------------------------------------------------

    fun onTelecomCallAdded(call: Call) {
        observer.onCallAdded(call)
    }

    fun onTelecomCallRemoved(call: Call) {
        observer.onCallRemoved(call)
    }

    // ------------------------------------------------------------------
    // App API gateway
    // ------------------------------------------------------------------

    override val supportsAttachments: Boolean get() = links.supportsAttachments()

    override suspend fun exchange(request: DeviceApiRequest, deadlineMs: Long): DeviceApiResult = exchangeBounded(request, deadlineMs)

    /**
     * The same exchange with the caller's bound.  [deadlineMs] covers the wait
     * for a usable link and the answer together; [onEnqueued] runs the moment
     * the frame is handed to the transport, before the answer is awaited.
     */
    internal suspend fun exchangeBounded(
        request: DeviceApiRequest,
        deadlineMs: Long,
        onEnqueued: () -> Unit = {}
    ): DeviceApiResult {
        val wire = json.encodeToString(DeviceApiRequest.serializer(), request)
        return requests.exchange(request.id, wire, deadlineMs, onEnqueued)
    }

    /**
     * The failure a teardown answers its pending requests with: a pairing that
     * moved away from this engine is named as such, so a request waiting on it
     * is never retried through the daemon that replaced it.
     */
    private fun closeFailure(): RequestFailure = if (PairingGate.expectedIdentity() != pairingIdentity) {
        RequestFailure.PairingChanged
    } else {
        RequestFailure.ShuttingDown
    }

    override suspend fun receiveAttachment(header: AttachmentHeader, sink: OutputStream): AttachmentOutcome {
        if (!links.supportsAttachments()) return AttachmentOutcome.Unsupported
        val wire = json.encodeToString(AttachmentHeader.serializer(), header)
        val channel = links.openAttachment(wire)
            ?: return AttachmentOutcome.Failed("link_down", "the device link cannot open an attachment stream")
        return AttachmentReceiver(channel, sink).receive()
    }

    /**
     * Stores the token the daemon handed over the authenticated link, then asks
     * for info once on this connection.  A token that did not change still asks:
     * the answer carries the capability and address set this connection has not
     * read yet.
     */
    private suspend fun acceptAppToken(token: String, generation: Long) {
        if (generation != linkGeneration.get()) {
            BridgeLog.w(TAG, "dropped an app token from a replaced connection")
            return
        }
        if (token != appToken.value) {
            val stored = withContext(Dispatchers.IO) {
                runCatching { storeAppToken(token) }.getOrDefault(false)
            }
            if (!stored) {
                BridgeLog.w(TAG, "could not store the app token from the daemon")
                return
            }
            mutableAppToken.value = token
            BridgeLog.i(TAG, "app API access granted by the daemon")
        }
        // The connection may have been replaced while the token was persisted.
        if (generation != linkGeneration.get()) return
        appInfo.delivered()
    }

    /**
     * Writes [token] only while the stored config still belongs to this
     * engine's pairing.  A late `app_access` from the previous daemon may not
     * overwrite the pairing that replaced it.
     */
    private fun storeAppToken(token: String): Boolean {
        val stored = BridgeConfigLoader.updateAppToken(appContext, pairingIdentity, token)
        if (!stored) BridgeLog.w(TAG, "the pairing moved; the app token was not stored")
        return stored
    }
}
