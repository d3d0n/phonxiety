package com.phonxiety.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.Call
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.phonxiety.bridge.device.R
import com.phonxiety.bridge.model.AppGateway
import com.phonxiety.bridge.model.AttachmentHeader
import com.phonxiety.bridge.model.AttachmentOutcome
import com.phonxiety.bridge.model.AudioDiagnostics
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.Capabilities
import com.phonxiety.bridge.model.ControlEnvelope
import com.phonxiety.bridge.model.CrashDiagnostics
import com.phonxiety.bridge.model.DaemonInfo
import com.phonxiety.bridge.model.DeviceActiveCall
import com.phonxiety.bridge.model.DeviceApiError
import com.phonxiety.bridge.model.DeviceApiRequest
import com.phonxiety.bridge.model.DeviceApiResult
import com.phonxiety.bridge.model.DeviceCapabilities
import com.phonxiety.bridge.model.DeviceCommand
import com.phonxiety.bridge.model.DeviceOutbound
import com.phonxiety.bridge.model.DeviceProtocol
import com.phonxiety.bridge.model.DeviceState
import com.phonxiety.bridge.model.DiagnosticsSnapshot
import com.phonxiety.bridge.model.DisconnectCause
import com.phonxiety.bridge.model.EngineDiagnostics
import com.phonxiety.bridge.model.LinkDiagnostics
import com.phonxiety.bridge.model.LinkError
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.net.CandidateLinkFactory
import com.phonxiety.bridge.net.CandidateStore
import com.phonxiety.bridge.net.ConnectionMode
import com.phonxiety.bridge.net.DeviceLink
import com.phonxiety.bridge.net.ExecutorLinkScheduler
import com.phonxiety.bridge.net.IrohSupport
import com.phonxiety.bridge.net.LinkSelector
import com.phonxiety.bridge.net.LinkSupervisor
import com.phonxiety.bridge.net.RelayModeSetting
import com.phonxiety.bridge.net.RelayOptions
import com.phonxiety.bridge.net.Tailnet
import com.phonxiety.bridge.net.WebSocketCandidateProbe
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * Protocol v3 device endpoint.
 *
 * Owns the single device link, the single bridge-owned Telecom call, its
 * audio path and the phone-side host-loss lease.  Every visible state change
 * is published on [state] by one serialized scope; [BridgeInCallService] only
 * forwards Telecom callbacks.
 *
 * The link is a TLS WebSocket to the paired daemon or an iroh stream; see
 * [LinkSupervisor] and [DeviceLink].  The engine keeps the protocol and call
 * control, the supervisor keeps the link lifecycle.  The foreground-service
 * notification follows [state].
 */
class BridgeEngine(
    context: Context,
    private val config: BridgeConfig
) : AppGateway {
    companion object {
        private const val TAG = "PhonxietyBridge"
        private const val THREAD_NAME = "phonxiety-scheduler"
        private const val PENDING_DIAL_TIMEOUT_MS = 120_000L
        private const val MAX_RECENT_CALLS = 32
        private const val ORPHAN_CANCEL_TTL_MS = 10 * 60_000L
        private const val DIAL_CORRELATION_WINDOW_MS = 10_000L
        private const val MAX_TEXT_CHARS = 256 * 1024

        /** One preferences read on the link thread, so the first hello is not sent blind. */
        private const val SETTINGS_READ_TIMEOUT_MS = 500L
        private const val MAX_BINARY_BYTES = 64 * 1024
        private const val EXTRA_CALL_ID = "com.phonxiety.bridge.extra.CALL_ID"
        private const val EXTRA_OWNER = "com.phonxiety.bridge.extra.OWNER"
        private const val APP_REQUEST_TIMEOUT_MS = 30_000L
        private const val TELEMETRY_INTERVAL_MS = 5_000L
        private const val AUDIO_LEVEL_INTERVAL_MS = 100L

        /**
         * How long a dial waits for an already-admitted idle link action (a
         * relay escalation or network recycle).  Such an action only nulls the
         * endpoint and reports the link down, so it is milliseconds; the
         * deadline covers a wedged native shutdown, after which the dial is
         * refused as `link_down` rather than reserving a call about to be cut.
         */
        private const val LINK_ACTION_WAIT_MS = 2_000L

        /** How often an idle link re-probes its candidates; the selector's hysteresis decides. */
        private const val SELECTION_INTERVAL_S = 900L

        /**
         * How often the link re-checks the call permissions its hello announced.
         * Permission reads only: a change the daemon cannot see without a new
         * handshake is picked up within this window while the link is up.
         */
        private const val CAPABILITY_WATCH_INTERVAL_S = 10L
        private val DESTINATION = Regex("^[+*#0-9]{1,32}$")
        private val LINK_TEST_MAGIC = "PXLT".encodeUtf8()
    }

    private val appContext = context.applicationContext
    private val ownerPackage = appContext.packageName
    private val deviceBootId: String = bootId()
    private val ledger = CallLedger(appContext, deviceBootId)

    /** Serializes call reservation with the link's idle-only actions; its monitor is [lock]. */
    private val admission = CallAdmission()
    private val lock = admission.lock
    private val dtmfMonitor = java.lang.Object()

    /** Routine cancellation stops only action audio; teardown also stops the Lab tone. */
    private val audioCancellation = CallAudioCancellation(
        stopActionAudio = { call ->
            synchronized(dtmfMonitor) { dtmfMonitor.notifyAll() }
            val telecomCall = synchronized(lock) { call.call }
            runCatching { telecomCall?.stopDtmfTone() }
        },
        stopLabTone = { call -> call.attached?.audio?.stopTone() }
    )
    private val closeOnce = AtomicBoolean(false)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val stateStore = EngineStateStore()

    /**
     * What the last queued `hello` told the daemon.  Kept apart from the UI's
     * [Capabilities], which [refreshCapabilities] also writes with the daemon's
     * voice-control support.
     */
    private val capabilityWatch = CapabilityWatch()
    private val capabilityRefresh = CapabilityRefresh(
        watch = capabilityWatch,
        admission = admission,
        callOwned = { ownsCall() },
        sample = { currentCapabilities() },
        reconnect = { links.reconnect(it) }
    )

    /** Everything the UI, tiles and widget observe. */
    val state: StateFlow<BridgeState> get() = stateStore.state

    private val engineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phonxiety-state").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val engineScope = CoroutineScope(SupervisorJob() + engineDispatcher + CoroutineName("phonxiety-engine"))

    /** The app API credential; the daemon re-sends it on every connect, so it can change at runtime. */
    @Volatile
    private var appToken: String? = config.appToken
    private val startedElapsed = SystemClock.elapsedRealtime()
    private var telemetryJob: Job? = null

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }
    private val commandExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phonxiety-commands").apply { isDaemon = true }
    }
    private val dtmfExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phonxiety-dtmf").apply { isDaemon = true }
    }
    private val callbackThread = HandlerThread("phonxiety-callbacks").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    /**
     * Which candidate the link uses, kept across attempts so a reconnect prefers the
     * path that was working.  Reads the phone's mode and call state through the
     * lambdas because both change while the engine runs.
     */
    private val selector = LinkSelector()

    private val links = LinkSupervisor(
        factory = CandidateLinkFactory(
            context = appContext,
            config = config,
            selector = selector,
            prober = WebSocketCandidateProbe(config.token),
            store = CandidateStore.of(appContext),
            mode = { ConnectionMode.of(phoneSettings.connectionMode) },
            ownsCall = { ownsCall() },
            duringCall = { ownsCall() },
            beginIdleLinkAction = { tryBeginIdleLinkAction() }
        ),
        callbacks = LinkCallbacks(),
        scheduler = ExecutorLinkScheduler(scheduler),
        clock = { SystemClock.elapsedRealtime() }
    )

    private val telecom: TelecomManager? = appContext.getSystemService(TelecomManager::class.java)
    private val wakeLock: PowerManager.WakeLock? =
        appContext.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonxiety:bridge")
            ?.apply { setReferenceCounted(false) }

    @Volatile private var running = false

    private var owned: OwnedCall? = null
    private val recent = LinkedHashMap<String, RecentCall>()
    private val pendingAudioClose = ConcurrentLinkedQueue<PhoneAudio>()
    private val orphanCancels = ArrayList<OrphanCancel>()
    private val apiPending = ConcurrentHashMap<String, CompletableDeferred<DeviceApiResult>>()

    /** Format the daemon asks for on the next call unless `start_audio` overrides it. */
    @Volatile private var preferredCodec = PhoneCodec.AUTO

    @Volatile private var maxBitrate = PhoneSettings.DEFAULT_MAX_BITRATE

    /** Phone-owned preferences, kept current from DataStore for hello, set_prefs and the lock. */
    @Volatile private var phoneSettings = PhoneSettings()

    /** True once this link has answered the handshake, so `set_prefs` never precedes `hello`. */
    @Volatile private var helloDelivered = false

    @Volatile private var settingsLoaded = false

    private val preferences = PhoneSettingsStore.of(appContext)

    // WIFI_MODE_FULL_LOW_LATENCY is what keeps audio latency stable on a busy Wi-Fi network;
    // it is held only while the link is up and the setting is on.
    private val wifiLock: WifiManager.WifiLock? = appContext.getSystemService(WifiManager::class.java)
        ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "phonxiety:low-latency")
        ?.apply { setReferenceCounted(false) }

    private var lastRouteKey: String? = null

    /**
     * The transport the current (or last) attempt uses.  A WebSocket target inside the
     * tailnet ranges is a Tailscale-app link; selection updates this whenever it picks
     * another candidate.
     */
    private var linkKind: LinkKind = when (config) {
        is BridgeConfig.Address ->
            if (Tailnet.isTailnetHost(Tailnet.hostOf(config.url))) LinkKind.TAILSCALE else LinkKind.WEBSOCKET

        is BridgeConfig.Iroh -> LinkKind.IROH
    }

    // ACCESS_NETWORK_STATE is a normal permission declared by this module; the
    // wake lock is held for the lifetime of the foreground service, which is
    // exactly the long-lived case the detector cannot express.
    @SuppressLint("MissingPermission", "WakelockTimeout")
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            restoreLedgerLocked()
        }
        stateStore.linkConnecting(linkKind)
        runCatching { wakeLock?.acquire() }
        telemetryJob = engineScope.launch {
            while (isActive && running) {
                runCatching { sampleDeviceState() }.getOrNull()?.let { stateStore.device(it) }
                delay(TELEMETRY_INTERVAL_MS)
            }
        }
        engineScope.launch {
            runCatching {
                preferences.settings.collect {
                    settingsLoaded = true
                    applySettings(it)
                }
            }
        }
        try {
            scheduler.schedule({
                safe { adoptExistingCalls() }
                safe { restoreAbandonedMuteAtStartup() }
                safe { links.start() }
            }, 0, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            return
        }
        scheduler.scheduleWithFixedDelay({ safe { links.tick() } }, 1, 1, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ safe { publishRoute() } }, 1, 1, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ safe { considerSwitch() } }, SELECTION_INTERVAL_S, SELECTION_INTERVAL_S, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay(
            { safe { refreshCapabilitiesOnChange() } },
            CAPABILITY_WATCH_INTERVAL_S,
            CAPABILITY_WATCH_INTERVAL_S,
            TimeUnit.SECONDS
        )
        scheduler.scheduleWithFixedDelay({ safe { emitPlayback() } }, 500, 500, TimeUnit.MILLISECONDS)
        scheduler.scheduleWithFixedDelay({ safe { emitAudioLevels() } }, AUDIO_LEVEL_INTERVAL_MS, AUDIO_LEVEL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        scheduler.scheduleWithFixedDelay({ safe { expirePendingDial() } }, 5, 5, TimeUnit.SECONDS)
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
                    safe { considerSwitch() }
                }
            } catch (_: RejectedExecutionException) {
                // Closing.
            }
        }
    }

    fun close() {
        if (!closeOnce.compareAndSet(false, true)) return
        synchronized(lock) { running = false }
        engineScope.cancel()
        runCatching {
            appContext.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(networkCallback)
        }
        links.cancel()
        failPendingApi("link_down", "the bridge is shutting down")
        scheduler.shutdownNow()
        abortOwnedOnShutdown()
        commandExecutor.shutdownNow()
        drainPendingAudioClose()
        dtmfExecutor.shutdownNow()
        callbackThread.quitSafely()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        links.shutdown()
        stateStore.reset()
        runCatching { engineDispatcher.close() }
    }

    /** `dialing`, `active` or `ending` while this engine owns a call. */
    fun activeCallState(): String? = synchronized(lock) {
        val current = owned ?: return null
        if (current.finished) null else current.state
    }

    fun linkIsUp(): Boolean = links.isUp()

    /** Route of the live link for the UI, or null while down. */
    internal fun currentRoute(): DeviceLink.Route? = links.route()

    // ------------------------------------------------------------------
    // Device link
    // ------------------------------------------------------------------

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            BridgeLog.e(TAG, "background task failed: ${t.javaClass.simpleName}")
        }
    }

    private fun send(message: DeviceOutbound): Boolean = links.sendText(DeviceProtocol.encode(message))

    /**
     * One phone preference change: remember it for the next hello, apply the local effects, and
     * tell a live daemon so the next call uses it without a reconnect.
     */
    private fun applySettings(settings: PhoneSettings, push: Boolean = true) {
        val codecChanged = settings.preferredCodec != preferredCodec
        val bitrateChanged = settings.maxBitrate != maxBitrate
        val modeChanged = settings.connectionMode != phoneSettings.connectionMode
        phoneSettings = settings
        preferredCodec = settings.preferredCodec
        maxBitrate = settings.maxBitrate
        BridgeLog.minimumLevel = BridgeLog.levelOf(settings.logLevel) ?: BridgeLog.INFO
        applyRelays(settings)
        applyWifiLock()
        // A new mode applies to the next attempt: the current link was chosen under the old one.
        if (modeChanged && !ownsCall()) {
            runCatching { scheduler.execute { safe { links.reconnect("connection_mode_changed") } } }
        }
        if (push && helloDelivered && links.isUp() && (codecChanged || bitrateChanged)) {
            send(
                DeviceOutbound.SetPrefs(
                    preferredCodec = settings.preferredCodec,
                    maxBitrate = settings.maxBitrate
                )
            )
        }
    }

    /**
     * Points the iroh endpoint at the relays the phone chose.  A bound endpoint keeps its
     * relays, so a change recycles it — never during a call, where the link must stay put;
     * the choice then applies to the next bind.
     */
    private fun applyRelays(settings: PhoneSettings) {
        val mode = RelayModeSetting.of(settings.relayMode)
        val urls = RelayOptions.urls(mode, settings.relayUrls, settings.daemonRelayUrls)
        val changed = IrohSupport.configureRelays(mode, urls)
        if (changed && !ownsCall()) IrohSupport.recycleEndpoint()
    }

    /**
     * The first `hello` must carry what the phone stored, not the defaults: a call can start
     * seconds after the link comes up.  The link's own thread waits for one small disk read
     * instead of racing a coroutine.
     */
    private fun loadSettingsBeforeHello() {
        if (settingsLoaded) return
        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(SETTINGS_READ_TIMEOUT_MS) { preferences.read() }
            }?.let { loaded ->
                settingsLoaded = true
                applySettings(loaded, push = false)
            }
        }
    }

    /** Holds the Wi-Fi low-latency lock exactly while the link is up and the setting is on. */
    private fun applyWifiLock() {
        val wanted = phoneSettings.wifiLowLatency && links.isUp()
        runCatching {
            if (wanted) {
                if (wifiLock?.isHeld != true) wifiLock?.acquire()
            } else if (wifiLock?.isHeld == true) {
                wifiLock.release()
            }
        }
    }

    /**
     * One idle re-probe: with automatic selection and no call owned, a better measured
     * path takes the link over.  The selector's hysteresis decides, so a single lucky
     * probe never moves it.
     */
    private fun considerSwitch() {
        if (!running || !links.isUp() || ownsCall()) return
        if (ConnectionMode.of(phoneSettings.connectionMode) != ConnectionMode.AUTO) return
        if (links.considerSwitch()) links.reconnect("candidate_better")
    }

    private fun publishRoute() {
        // A frame refused by the hello gate must not be remembered as sent:
        // the existing one-second tick retries it once the link can carry it.
        if (!helloDelivered) return
        val route = links.route() ?: return
        val key = "${route.relay}|${route.rttMs}"
        if (key == lastRouteKey) return
        val sent = send(
            DeviceOutbound.LinkRoute(
                path = if (route.relay) "relay" else "direct",
                rttMs = route.rttMs
            )
        )
        if (!sent) return
        lastRouteKey = key
        stateStore.route(RouteSnapshot(route.relay, route.rttMs))
    }

    /**
     * True while this engine owns a bridge call in any non-terminal state:
     * dialing, active or ending.  The iroh link uses it to keep a relayed call
     * alive instead of recycling the endpoint.
     */
    private fun ownsCall(): Boolean = synchronized(lock) {
        val current = owned
        current != null && !current.finished
    }

    /**
     * Admits one idle-only link action — escalate a relay-only link, recycle
     * the endpoint on a network move — or refuses it while a call is owned or
     * being reserved.  The door stays shut until the action is released, so the
     * link can never close under a call placed a moment later.
     */
    private fun tryBeginIdleLinkAction(): IdleLinkAction? = admission.tryBeginIdleLinkAction { ownsCall() }

    /**
     * The engine side of the link: sends `hello` once a link is live, turns
     * received JSON into commands and binary into the audio/link-test path.
     * Owner checks, activity bookkeeping and reconnects live in
     * [LinkSupervisor]; every callback here is for the current link only.
     */
    private inner class LinkCallbacks : LinkSupervisor.Callbacks {
        override fun onLinkOpened(): Boolean {
            val chosen = links.selectedCandidate()
            if (chosen != null) linkKind = chosen.kind
            stateStore.linkUp(
                kind = linkKind,
                route = links.route()?.let { RouteSnapshot(it.relay, it.rttMs) },
                sinceEpochMs = System.currentTimeMillis(),
                reconnects = links.reconnects(),
                address = chosen?.host ?: when (config) {
                    is BridgeConfig.Address -> config.host
                    is BridgeConfig.Iroh -> IrohSupport.shortEndpointId(config.ticket)
                }
            )
            // Nothing else may reach this link before its hello: the daemon
            // reads the first control frame as the handshake.
            val hello = sendHello()
            if (hello) engineScope.launch { refreshCapabilities() }
            applyWifiLock()
            return hello
        }

        override fun onLinkText(text: String) {
            this@BridgeEngine.onText(text)
        }

        override fun onLinkBinary(bytes: ByteString) {
            this@BridgeEngine.onBinary(bytes)
        }

        override fun onLinkDown(reason: String) {
            lastRouteKey = null
            helloDelivered = false
            capabilityWatch.helloGone()
            applyWifiLock()
            stateStore.linkDown(LinkError(code = reason, message = linkMessage(reason), atEpochMs = System.currentTimeMillis()))
            failPendingApi("link_down", "the device link went down")
            abortOwnedForLinkLoss()
        }
    }

    private fun linkMessage(reason: String): String = when (reason) {
        "host_timeout" -> "the daemon stopped answering"
        "connect_failed" -> "cannot reach the daemon"
        "network_changed" -> "the network changed"
        "network_lost" -> "the network is gone"
        "connection_mode_changed" -> "the connection mode changed"
        "candidate_better" -> "a better path was measured"
        "capabilities_changed" -> "the phone's call permissions changed"
        "closed", "closing" -> "the daemon closed the link"
        "protocol" -> "the daemon spoke an unknown protocol"
        "write_failed" -> "the link stopped accepting data"
        else -> reason
    }

    private fun onText(text: String) {
        if (text.length > MAX_TEXT_CHARS) {
            BridgeLog.w(TAG, "ignoring oversized control message")
            return
        }
        when (ControlEnvelope.type(text)) {
            "command" -> {
                val id = ControlEnvelope.id(text)
                if (id.isNullOrEmpty()) return
                try {
                    commandExecutor.execute { handleCommand(id, text) }
                } catch (_: RejectedExecutionException) {
                    // Shutting down.
                }
            }

            "api_result" -> {
                val result = runCatching { ControlEnvelope.decodeApiResult(text) }.getOrNull() ?: return
                apiPending.remove(result.id)?.complete(result)
            }

            "app_access" -> {
                val token = ControlEnvelope.appAccessToken(text) ?: return
                engineScope.launch { acceptAppToken(token) }
            }

            else -> Unit
        }
    }

    private fun onBinary(bytes: ByteString) {
        if (bytes.size < 16 || bytes.size > MAX_BINARY_BYTES) return
        val audio = synchronized(lock) { owned?.attached?.audio }
        if (audio != null) {
            audio.offerFrame(bytes)
        } else if (bytes.startsWith(LINK_TEST_MAGIC)) {
            // The daemon times an idle link under call load: return its frame.
            links.sendFrame(bytes.toByteArray(), bytes.size)
        }
    }

    private fun sendHello(): Boolean {
        loadSettingsBeforeHello()
        val capabilities = currentCapabilities()
        val busy = hasUnrelatedLiveCall()
        val active: DeviceActiveCall? = synchronized(lock) {
            val current = owned
            if (current == null || current.finished) null else DeviceActiveCall(current.callId, current.state)
        }
        val hello = helloFrame(
            capabilities = capabilities,
            device = android.os.Build.DEVICE ?: "unknown",
            bootId = deviceBootId,
            busy = busy,
            activeCall = active,
            preferredCodec = preferredCodec,
            maxBitrate = maxBitrate,
            adbTunnel = config is BridgeConfig.Iroh
        )
        // Through the supervisor's hello gate: the first control frame of the
        // link, and the only one allowed before it is queued.
        val sent = links.sendHello(DeviceProtocol.encode(hello))
        helloDelivered = sent
        if (sent) {
            capabilityWatch.helloSent(capabilities)
            applyWifiLock()
        }
        stateStore.capabilities(
            stateStore.state.value.capabilities.copy(
                frameworkAudio = capabilities.frameworkAudio,
                callControl = capabilities.callControl,
                appApi = if (appToken != null) stateStore.state.value.capabilities.appApi else null
            )
        )
        return sent
    }

    /**
     * The call permissions this hello must announce.  `checkSelfPermission`
     * only: no I/O, cheap enough for the 10 s capability watch.
     */
    private fun currentCapabilities(): PhoneCapabilities = PhoneCapabilities(
        callControl = Permissions.granted(appContext, Permissions.CALL_PHONE) &&
            Permissions.granted(appContext, Permissions.CONTROL_INCALL_EXPERIENCE),
        frameworkAudio = Permissions.granted(appContext, Permissions.CAPTURE_AUDIO_OUTPUT) &&
            Permissions.granted(appContext, Permissions.MODIFY_PHONE_STATE) &&
            Permissions.granted(appContext, Permissions.RECORD_AUDIO)
    )

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

    private fun emitPlayback() {
        val state: Triple<PhoneAudio, String, Long> = synchronized(lock) {
            val current = owned
            val audio = current?.attached?.audio
            if (current == null || audio == null) return
            Triple(audio, current.callId, current.epoch)
        }
        val stats = state.first.stats()
        send(
            DeviceOutbound.Playback(
                callId = state.second,
                epoch = state.third,
                playedSamples = stats.first,
                queuedSamples = stats.second,
                drainedMark = state.first.drainedMark()
            )
        )
    }

    private fun emitAudioLevels() {
        val target: AudioLevelTarget = synchronized(lock) {
            val current = owned ?: return
            val attached = current.audioToPublish() ?: return
            AudioLevelTarget(attached, current.callId)
        }
        // Both calls below can block behind the playback write and the send, so
        // the stream may be torn down by the time they return.  The store
        // accepts numbers only from the stream it still considers live, so this
        // publish can never land after that teardown's reset.
        val levels = target.attached.audio.levels()
        send(
            DeviceOutbound.AudioLevels(
                callId = target.callId,
                rxDbfs = levels.rxDbfs,
                txDbfs = levels.txDbfs,
                queued = levels.queuedSamples / 960
            )
        )
        val diagnostics = target.attached.audio.diagnostics()
        stateStore.audioStats(
            target.attached.stream,
            AudioStats(
                codec = diagnostics.codec,
                bitrate = diagnostics.bitrate,
                rxDbfs = levels.rxDbfs,
                txDbfs = levels.txDbfs,
                framesOut = diagnostics.capturedFrames,
                framesIn = diagnostics.hostFrames,
                drops = diagnostics.overflowSamples
            )
        )
    }

    /** One 100 ms publish, captured under [lock] together with the stream that owns it. */
    private class AudioLevelTarget(
        val attached: AttachedAudio,
        val callId: String
    )

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private fun handleCommand(envelopeId: String, text: String) {
        if (!running) return
        val command = try {
            DeviceProtocol.decodeCommand(text)
        } catch (_: SerializationException) {
            replyError(envelopeId, "unknown_command")
            return
        } catch (_: IllegalArgumentException) {
            replyError(envelopeId, "unknown_command")
            return
        }
        val id = command.id.ifEmpty { envelopeId }
        try {
            when (command) {
                is DeviceCommand.Dial -> handleDial(id, command)
                is DeviceCommand.Hangup -> handleHangup(id, command)
                is DeviceCommand.StartAudio -> handleStartAudio(id, command)
                is DeviceCommand.Flush -> handleFlush(id, command)
                is DeviceCommand.Dtmf -> handleDtmf(id, command)
                is DeviceCommand.CancelActions -> handleCancelActions(id, command)
                is DeviceCommand.Ping -> replyOk(id, buildJsonObject { put("pong", true) })
                is DeviceCommand.SetPrefs -> handleSetPrefs(id, command)
                is DeviceCommand.LoopbackStart -> handleLoopback(id, command, enabled = true)
                is DeviceCommand.LoopbackStop -> handleLoopback(id, command, enabled = false)
                is DeviceCommand.ToneStart -> handleToneStart(id, command)
                is DeviceCommand.ToneStop -> handleToneStop(id, command)
                is DeviceCommand.Diagnostics -> handleDiagnostics(id)
            }
        } catch (e: CommandException) {
            replyError(id, e.code)
        } catch (e: Exception) {
            BridgeLog.e(TAG, "command failed: ${e.javaClass.simpleName}")
            replyError(id, "internal_error")
        }
    }

    private fun handleSetPrefs(id: String, command: DeviceCommand.SetPrefs) {
        val codec = command.codec?.let { value ->
            if (value !in PhoneCodec.all) throw CommandException("codec_unsupported")
            PhoneCodec.normalize(value)
        } ?: preferredCodec
        val bitrate = command.bitrate?.also { value ->
            if (value !in 6_000..128_000) throw CommandException("bitrate_invalid")
        } ?: maxBitrate
        preferredCodec = codec
        maxBitrate = bitrate
        replyOk(
            id,
            buildJsonObject {
                put("codec", preferredCodec)
                put("bitrate", maxBitrate)
            }
        )
    }

    private fun handleLoopback(id: String, command: DeviceCommand, enabled: Boolean) {
        val callId = when (command) {
            is DeviceCommand.LoopbackStart -> command.callId
            is DeviceCommand.LoopbackStop -> command.callId
            else -> null
        }
        val (audio, ownerCallId) = synchronized(lock) {
            val current = owned
            if (current == null || current.finished) throw CommandException("not_owned")
            if (callId != null && current.callId != callId) throw CommandException("not_owned")
            val active = current.attached?.audio ?: throw CommandException("audio_not_started")
            active to current.callId
        }
        val applied = audio.setLoopback(enabled)
        replyOk(
            id,
            buildJsonObject {
                put("call_id", ownerCallId)
                put("enabled", applied)
            }
        )
    }

    private fun handleToneStart(id: String, command: DeviceCommand.ToneStart) {
        val (audio, ownerCallId) = synchronized(lock) {
            val current = owned
            if (current == null || current.finished) throw CommandException("not_owned")
            if (command.callId != null && current.callId != command.callId) throw CommandException("not_owned")
            val active = current.attached?.audio ?: throw CommandException("audio_not_started")
            active to current.callId
        }
        if (command.hz !in 100..4_000) throw CommandException("hz_invalid")
        if (command.dbfs !in -60.0..0.0) throw CommandException("dbfs_invalid")
        if (!audio.startTone(command.hz, command.dbfs)) throw CommandException("audio_not_started")
        replyOk(
            id,
            buildJsonObject {
                put("call_id", ownerCallId)
                put("hz", command.hz)
                put("dbfs", command.dbfs)
                put("started", true)
            }
        )
    }

    private fun handleToneStop(id: String, command: DeviceCommand.ToneStop) {
        val (audio, ownerCallId) = synchronized(lock) {
            val current = owned
            if (current == null || current.finished) throw CommandException("not_owned")
            if (command.callId != null && current.callId != command.callId) throw CommandException("not_owned")
            val active = current.attached?.audio ?: throw CommandException("audio_not_started")
            active to current.callId
        }
        audio.stopTone()
        replyOk(
            id,
            buildJsonObject {
                put("call_id", ownerCallId)
                put("started", false)
            }
        )
    }

    private fun handleDiagnostics(id: String) {
        var callId: String? = null
        var callState: String? = null
        var epoch = -1L
        var audioState: PhoneAudio? = null
        synchronized(lock) {
            val current = owned
            if (current != null && !current.finished) {
                callId = current.callId
                callState = current.state
                epoch = current.epoch
                audioState = current.attached?.audio
            }
        }
        val diagnostics = DiagnosticsSnapshot(
            engine = EngineDiagnostics(
                running = running,
                callId = callId,
                state = callState,
                epoch = epoch,
                uptimeSeconds = (SystemClock.elapsedRealtime() - startedElapsed) / 1_000,
                activeCalls = if (ownsCall()) 1 else 0
            ),
            audio = audioState?.diagnostics() ?: AudioDiagnostics(),
            link = LinkDiagnostics(
                up = links.isUp(),
                path = links.route()?.let { if (it.relay) "relay" else "direct" },
                rttMs = links.route()?.rttMs,
                backlogBytes = links.backlogBytes(),
                reconnects = links.reconnects()
            ),
            // What killed the last processes: the daemon puts this next to its own state in the
            // diagnostics bundle, so a crash or an ANR arrives with the report instead of being
            // inferred from a gap in `logcat`.
            crashes = CrashReports.recent(appContext, limit = 5).map { report ->
                CrashDiagnostics(
                    atEpochMs = report.atEpochMs,
                    kind = report.kind.name.lowercase(Locale.US),
                    reason = report.reason,
                    description = report.description,
                    memoryLimiter = report.memoryLimiter,
                    trace = report.detail
                )
            }
        )
        replyOk(id, json.encodeToJsonElement(DiagnosticsSnapshot.serializer(), diagnostics).jsonObject)
    }

    // CALL_PHONE and READ_PHONE_STATE are checked by the command handler before
    // this runs, and a SecurityException is translated to call_permission_denied.
    @SuppressLint("MissingPermission")
    private fun handleDial(id: String, command: DeviceCommand.Dial) {
        val callId = command.callId.ifEmpty { throw CommandException("call_id_required") }
        val destination = command.destination.trim()
        if (!DESTINATION.matches(destination)) throw CommandException("destination_invalid")
        // The bridge never dials emergency services.  The SIM and network define
        // which numbers those are, so ask telephony; refuse if it cannot answer.
        val emergency = try {
            appContext.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(destination)
        } catch (_: Exception) {
            null
        }
        if (emergency != false) {
            throw CommandException(if (emergency == true) "emergency_number" else "emergency_check_unavailable")
        }
        val requestedAccount = command.phoneAccount?.trim()?.ifEmpty { null }
        val unrelatedCall = hasUnrelatedLiveCall()

        // A relay-only escalation or network recycle already admitted closes
        // this link as soon as it runs, so a call reserved under it would be
        // aborted on arrival.  The action is milliseconds long: wait it out and
        // refuse only if it is still in flight, never race it.
        if (!admission.awaitIdle(LINK_ACTION_WAIT_MS)) throw CommandException("link_down")

        var duplicateState: String? = null
        var reserved: OwnedCall? = null
        synchronized(lock) {
            if (!running) throw CommandException("stopped")
            if (admission.actionInFlightLocked()) throw CommandException("link_down")
            if (!links.isUp()) throw CommandException("link_down")
            val current = owned
            if (current != null) {
                if (current.callId != callId) throw CommandException("busy")
                if (current.destination != destination) throw CommandException("duplicate_conflict")
                duplicateState = current.state
            } else {
                val previous = recent[callId]
                if (previous != null) {
                    if (previous.destination != destination) throw CommandException("duplicate_conflict")
                    duplicateState = previous.state
                }
            }
            if (duplicateState == null) {
                if (unrelatedCall) throw CommandException("busy")
                // Correlation is stored before the external action.  A failed
                // durable write aborts the dial instead of proceeding blind.
                val created = OwnedCall(callId, destination)
                try {
                    ledger.reserve(
                        callId = callId,
                        destination = destination,
                        phoneAccount = requestedAccount,
                        createdWall = created.dialStartedWall,
                        createdElapsed = created.dialStartedElapsed
                    )
                } catch (_: CallLedgerException) {
                    throw CommandException("state_persist_failed")
                }
                owned = created
                reserved = created
            }
        }
        val duplicate = duplicateState
        if (duplicate != null) {
            stateStore.callStarted(callId, maskDestination(destination), System.currentTimeMillis())
            stateStore.callPhase(callPhaseOf(duplicate))
            if (duplicate == "failed") {
                replyError(id, "dial_failed_previous")
            } else {
                replyOk(
                    id,
                    buildJsonObject {
                        put("call_id", callId)
                        put("state", duplicate)
                        put("duplicate", true)
                    }
                )
            }
            return
        }

        val handle = try {
            resolvePhoneAccount(requestedAccount)
        } catch (e: CommandException) {
            clearFailedDial(callId)
            throw e
        }
        val manager = telecom
        if (manager == null) {
            clearFailedDial(callId)
            throw CommandException("telecom_unavailable")
        }
        if (!Permissions.granted(appContext, Permissions.CALL_PHONE)) {
            clearFailedDial(callId)
            throw CommandException("call_permission_denied")
        }
        val extras = Bundle()
        if (handle != null) {
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        }
        // Best-effort identity for the Call that Telecom creates.  Adoption
        // still falls back to destination/time when Telecom does not surface
        // these extras in Call.Details.
        extras.putString(EXTRA_CALL_ID, callId)
        extras.putString(EXTRA_OWNER, ownerPackage)
        val reservedCall = reserved
        val stillOwned = synchronized(lock) {
            running && links.isUp() && reservedCall != null && owned === reservedCall
        }
        if (!stillOwned) {
            clearFailedDial(callId)
            throw CommandException("link_down")
        }
        try {
            manager.placeCall(Uri.fromParts("tel", destination, null), extras)
        } catch (_: SecurityException) {
            clearFailedDial(callId)
            BridgeLog.e(TAG, "placeCall denied")
            throw CommandException("call_permission_denied")
        } catch (e: Exception) {
            clearFailedDial(callId)
            BridgeLog.e(TAG, "placeCall failed: ${e.javaClass.simpleName}")
            throw CommandException("dial_failed")
        }

        stateStore.callStarted(callId, maskDestination(destination), System.currentTimeMillis())
        val state = synchronized(lock) {
            owned?.takeIf { it.callId == callId }?.state ?: "dialing"
        }
        stateStore.callPhase(callPhaseOf(state))
        replyOk(
            id,
            buildJsonObject {
                put("call_id", callId)
                put("state", state)
            }
        )
    }

    private fun handleHangup(id: String, command: DeviceCommand.Hangup) {
        val callId = command.callId.ifEmpty { throw CommandException("call_id_required") }
        var audio: PhoneAudio? = null
        var stream: AudioStream? = null
        var call: Call? = null
        var toneOwner: OwnedCall? = null
        var terminalState: String? = null
        var pendingOnly = false

        synchronized(lock) {
            val current = owned
            if (current == null || current.callId != callId) {
                val previous = recent[callId]
                if (previous != null) {
                    terminalState = if (previous.state == "failed") "ended" else previous.state
                } else {
                    throw CommandException("not_owned")
                }
            } else {
                if (current.call == null) {
                    // Persist the tombstone before dropping the in-memory
                    // intent so a late Call is still rejected after restart.
                    try {
                        ledger.orphan(
                            callId = current.callId,
                            destination = current.destination,
                            dialStartedWall = current.dialStartedWall,
                            ttlMs = ORPHAN_CANCEL_TTL_MS
                        )
                    } catch (_: CallLedgerException) {
                        throw CommandException("state_persist_failed")
                    }
                } else {
                    try {
                        ledger.cancel(current.callId)
                    } catch (e: CallLedgerException) {
                        BridgeLog.e(TAG, "cancellation not durable: ${e.message}")
                    }
                }
                current.cancelled = true
                current.generation.set(Long.MAX_VALUE)
                current.detachAudio()?.let {
                    audio = it.audio
                    stream = it.stream
                }
                current.epoch = -1
                call = current.call
                pendingOnly = call == null
                toneOwner = current
                if (call == null) {
                    // Telecom has not surfaced the Call yet.  Do not keep the
                    // bridge busy: remember the cancelled intent so a late
                    // matching Call is disconnected on arrival, and allow the
                    // host to start a different request immediately.
                    owned = null
                    rememberLocked(current.callId, current.destination, "ended")
                    addOrphanLocked(current)
                }
            }
        }
        if (terminalState != null) {
            val terminal = terminalState ?: "ended"
            stateStore.callPhase(callPhaseOf(terminal))
            replyOk(
                id,
                buildJsonObject {
                    put("call_id", callId)
                    put("state", terminal)
                }
            )
            return
        }
        toneOwner?.let { cancelToneFor(it) }
        closeAudioAsync(audio)
        // The hang-up clears the stream's numbers itself.  Telecom's terminal
        // callback may never arrive (or this hang-up may reach a call Telecom
        // already dropped), and the last stream's drops must not outlive it.
        stream?.let { stateStore.audioReset(it) }
        call?.let { telecomCall ->
            try {
                telecomCall.disconnect()
            } catch (_: Exception) {
                val terminal = synchronized(lock) {
                    val current = owned
                    if (current != null && current.callId == callId) {
                        null
                    } else {
                        recent[callId]?.state ?: "ended"
                    }
                }
                if (terminal != null) {
                    stateStore.callPhase(callPhaseOf(terminal))
                    replyOk(
                        id,
                        buildJsonObject {
                            put("call_id", callId)
                            put("state", terminal)
                        }
                    )
                } else {
                    replyError(id, "disconnect_failed")
                }
                return
            }
        }
        if (pendingOnly) {
            BridgeLog.w(TAG, "hangup requested before the telecom call appeared; retained")
        }
        stateStore.callPhase(CallPhase.ENDING)
        replyOk(
            id,
            buildJsonObject {
                put("call_id", callId)
                put("state", "ending")
            }
        )
    }

    private fun handleStartAudio(id: String, command: DeviceCommand.StartAudio) {
        val callId = command.callId.ifEmpty { throw CommandException("call_id_required") }
        val epoch = command.epoch
        if (epoch < 0 || epoch > 0xFFFFFFFFL) throw CommandException("epoch_invalid")
        val codec = command.codec ?: if (preferredCodec == PhoneCodec.AUTO) PhoneCodec.OPUS else preferredCodec
        val opusBitrate = when (codec) {
            "pcm" -> null

            "opus" -> (command.bitrate ?: maxBitrate).also {
                if (it !in 6_000..128_000) throw CommandException("bitrate_invalid")
            }

            else -> throw CommandException("codec_unsupported")
        }
        var target: OwnedCall? = null
        var previous: AttachedAudio? = null

        synchronized(lock) {
            val current = owned
            if (current == null || current.callId != callId) throw CommandException("not_owned")
            if (!links.isUp()) throw CommandException("link_down")
            if (current.call == null || current.telecomState != Call.STATE_ACTIVE) throw CommandException("not_active")
            if (epoch < current.lastEpoch) throw CommandException("stale_epoch")
            val existing = current.attached
            if (existing != null && current.epoch == epoch) {
                replyOk(
                    id,
                    buildJsonObject {
                        put("call_id", callId)
                        put("epoch", epoch)
                        put("state", "started")
                        put("codec", existing.audio.codec)
                    }
                )
                return
            }
            if (existing != null) {
                previous = existing
                current.attached = null
                current.epoch = -1
            }
            target = current
        }
        // A new epoch supersedes the old session.  Close it and clear its
        // numbers before the new stream starts so leftover RX frames and queued
        // TX audio from the previous epoch cannot overlap the new one, and a
        // start that fails cannot leave the dead epoch's stats on the dashboard.
        previous?.audio?.close()
        previous?.let { stateStore.audioReset(it.stream) }

        val current = target ?: throw CommandException("not_owned")
        val audio = PhoneAudio(
            appContext,
            callId,
            epoch,
            opusBitrate,
            { frame, length -> links.sendFrame(frame, length) },
            { reason -> postAudioFatal(callId, epoch, reason) }
        )
        try {
            audio.start()
        } catch (e: AudioStartException) {
            audio.close()
            throw CommandException(e.code)
        } catch (e: Exception) {
            audio.close()
            BridgeLog.e(TAG, "audio start failed: ${e.javaClass.simpleName}")
            throw CommandException("audio_start_failed")
        }

        var started: AttachedAudio? = null
        synchronized(lock) {
            if (running && links.isUp() && owned === current && !current.finished &&
                current.telecomState == Call.STATE_ACTIVE && current.attached == null
            ) {
                val session = AttachedAudio(audio, stateStore.audioStreamStart())
                current.attached = session
                current.epoch = epoch
                current.lastEpoch = epoch
                started = session
            }
        }
        val live = started
        if (live == null) {
            audio.close()
            throw CommandException("not_active")
        }
        // Codec first, but only for the stream that just attached: a teardown
        // that beat this line has already reset the block and must not see the
        // codec reappear.
        stateStore.audio(live.stream, live.audio.codec, opusBitrate)
        replyOk(
            id,
            buildJsonObject {
                put("call_id", callId)
                put("epoch", epoch)
                put("state", "started")
                put("codec", audio.codec)
            }
        )
    }

    private fun handleFlush(id: String, command: DeviceCommand.Flush) {
        val callId = command.callId.ifEmpty { throw CommandException("call_id_required") }
        val epoch = command.epoch
        if (epoch < 0 || epoch > 0xFFFFFFFFL) throw CommandException("epoch_invalid")
        val audio = synchronized(lock) {
            val current = owned
            if (current == null || current.callId != callId) throw CommandException("not_owned")
            if (current.telecomState != Call.STATE_ACTIVE) throw CommandException("not_active")
            val activeAudio = current.attached?.audio ?: throw CommandException("audio_not_started")
            if (epoch < current.epoch) {
                replyOk(
                    id,
                    buildJsonObject {
                        put("call_id", callId)
                        put("epoch", epoch)
                        put("flushed", false)
                        put("obsolete", true)
                    }
                )
                return
            }
            if (epoch != current.epoch) throw CommandException("epoch_mismatch")
            activeAudio
        }
        if (!audio.flush()) throw CommandException("flush_failed")
        replyOk(
            id,
            buildJsonObject {
                put("call_id", callId)
                put("epoch", epoch)
                put("flushed", true)
            }
        )
    }

    private fun handleCancelActions(id: String, command: DeviceCommand.CancelActions) {
        val callId = command.callId.ifEmpty { throw CommandException("call_id_required") }
        val generation = command.actionGeneration
        if (generation < 0) throw CommandException("action_generation_invalid")
        var target: OwnedCall? = null
        var known = false
        synchronized(lock) {
            val current = owned
            if (current != null && current.callId == callId) {
                known = true
                val previous = current.generation.get()
                if (generation >= previous) {
                    current.generation.set(generation)
                    target = current
                }
            } else if (recent.containsKey(callId)) {
                known = true
            }
        }
        if (!known) throw CommandException("not_owned")
        val cancelledCall = target
        if (cancelledCall != null) {
            // Routine supervision cancels the DTMF tone an action started; the Lab test
            // tone is owned by its own tone_start/tone_stop window and every teardown.
            audioCancellation.cancelActions(cancelledCall)
        }
        replyOk(
            id,
            buildJsonObject {
                put("call_id", callId)
                put("action_generation", generation)
                put("cancelled", cancelledCall != null)
            }
        )
    }

    private fun handleDtmf(id: String, command: DeviceCommand.Dtmf) {
        val callId = command.callId.ifEmpty { throw CommandException("call_id_required") }
        val generation = command.actionGeneration
        if (generation < 0) throw CommandException("action_generation_invalid")
        val digits = command.digits.uppercase(Locale.ROOT)
        if (digits.isEmpty() || digits.length > 64 ||
            digits.any { it !in "0123456789*#ABCD" }
        ) {
            throw CommandException("digits_invalid")
        }
        val toneMs = command.toneMs.coerceIn(40, 1000)
        val gapMs = command.gapMs.coerceIn(0, 1000)

        var bumped = false
        val target = synchronized(lock) {
            val current = owned
            if (current == null || current.callId != callId) {
                throw CommandException(if (recent.containsKey(callId)) "call_ended" else "not_owned")
            }
            if (current.call == null || current.telecomState != Call.STATE_ACTIVE) {
                throw CommandException("not_active")
            }
            val previous = current.generation.get()
            if (generation < previous) throw CommandException("stale_generation")
            if (generation > previous) {
                current.generation.set(generation)
                bumped = true
            }
            current
        }
        if (bumped) {
            synchronized(dtmfMonitor) { dtmfMonitor.notifyAll() }
        }
        try {
            dtmfExecutor.execute {
                runDtmf(id, target, generation, digits, toneMs, gapMs)
            }
        } catch (_: RejectedExecutionException) {
            throw CommandException("stopping")
        }
    }

    private fun runDtmf(
        id: String,
        call: OwnedCall,
        generation: Long,
        digits: String,
        toneMs: Int,
        gapMs: Int
    ) {
        var failure: String? = null
        var index = 0
        while (index < digits.length) {
            if (generation < call.generation.get()) {
                failure = "cancelled"
                break
            }
            val telecomCall = synchronized(lock) { if (call.finished) null else call.call }
            if (telecomCall == null) {
                failure = "call_ended"
                break
            }
            val active = synchronized(lock) {
                !call.finished && call.telecomState == Call.STATE_ACTIVE
            }
            if (!active) {
                failure = "not_active"
                break
            }
            val digit = digits[index]
            try {
                telecomCall.playDtmfTone(digit)
            } catch (_: Exception) {
                failure = "dtmf_failed"
                break
            }
            val interrupted = waitDtmf(toneMs, call, generation)
            runCatching { telecomCall.stopDtmfTone() }
            if (interrupted || generation < call.generation.get()) {
                failure = "cancelled"
                break
            }
            val activeAfter = synchronized(lock) {
                !call.finished && call.telecomState == Call.STATE_ACTIVE
            }
            if (!activeAfter) {
                failure = "not_active"
                break
            }
            if (index < digits.lastIndex && gapMs > 0) {
                if (waitDtmf(gapMs, call, generation) || generation < call.generation.get()) {
                    failure = "cancelled"
                    break
                }
            }
            index++
        }
        val reason = failure
        if (reason != null) {
            replyError(id, reason)
        } else {
            replyOk(
                id,
                buildJsonObject {
                    put("call_id", call.callId)
                    put("action_generation", generation)
                    put("digits", digits)
                    put("completed", true)
                }
            )
        }
    }

    private fun waitDtmf(ms: Int, call: OwnedCall, generation: Long): Boolean {
        if (ms <= 0) return false
        val deadline = SystemClock.elapsedRealtime() + ms
        synchronized(dtmfMonitor) {
            while (true) {
                if (generation < call.generation.get()) return true
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) return false
                try {
                    dtmfMonitor.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return true
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return false
    }

    private fun postAudioFatal(callId: String, epoch: Long, reason: String) {
        try {
            commandExecutor.execute { onAudioFatal(callId, epoch, reason) }
        } catch (_: RejectedExecutionException) {
            // Shutting down.
        }
    }

    private fun onAudioFatal(callId: String, epoch: Long, reason: String) {
        var detached: AttachedAudio? = null
        synchronized(lock) {
            val current = owned
            if (current != null && current.callId == callId) {
                detached = current.detachAudioAt(epoch)
            }
        }
        val attached = detached ?: return
        send(DeviceOutbound.AudioError(callId = callId, error = reason))
        stateStore.error("audio_error", "the call audio failed: $reason")
        attached.audio.close()
        // Only the stream this callback owned: a fatal callback queued behind a
        // replacement `start_audio` is obsolete and must leave the replacement
        // publishing.
        stateStore.audioReset(attached.stream)
    }

    // ------------------------------------------------------------------
    // Telecom calls
    // ------------------------------------------------------------------

    fun onTelecomCallAdded(call: Call) {
        if (!running) return
        var adopted: OwnedCall? = null
        var cancelledDial = false
        var registered = false
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                this@BridgeEngine.onCallStateChanged(call, state)
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                this@BridgeEngine.onCallDetailsChanged(call, details)
            }

            override fun onCallDestroyed(call: Call) {
                this@BridgeEngine.onCallDestroyed(call)
            }
        }

        var orphanCancellation: OrphanCancel? = null
        synchronized(lock) {
            val current = owned
            if (current != null && current.call === call) {
                // Duplicate delivery for the already-adopted Call; never let a
                // stale tombstone act on it.
                return
            }
            if (current != null && !current.finished && current.call == null &&
                matchesPending(current, call)
            ) {
                current.call = call
                current.callback = callback
                adopted = current
                cancelledDial = current.cancelled
                current.telecomState = stateOf(call)
            } else if (current == null || current.call == null) {
                orphanCancellation = takeOrphanLocked(call)
                if (orphanCancellation == null) {
                    BridgeLog.i(TAG, "ignoring telecom call not owned by the bridge")
                    return
                }
            } else {
                BridgeLog.i(TAG, "ignoring telecom call while an owned call is attached")
                return
            }
        }

        val orphan = orphanCancellation
        if (orphan != null) {
            // A dial the host already cancelled has finally surfaced.  Drop it
            // without touching any current bridge-owned call, and restore the
            // persisted microphone snapshot only once it is disconnected.
            val restored = AtomicBoolean(false)
            val cleanup: (Call) -> Unit = { target ->
                if (restored.compareAndSet(false, true)) {
                    restoreMuteForCall(orphan.callId, target)
                }
            }
            val orphanCallback = object : Call.Callback() {
                override fun onStateChanged(call: Call, state: Int) {
                    if (state == Call.STATE_DISCONNECTED) {
                        runCatching { call.unregisterCallback(this) }
                        cleanup(call)
                    }
                }

                override fun onCallDestroyed(call: Call) {
                    runCatching { call.unregisterCallback(this) }
                    cleanup(call)
                }
            }
            val registeredOrphan = try {
                call.registerCallback(orphanCallback, callbackHandler)
                true
            } catch (_: Exception) {
                false
            }
            runCatching { call.disconnect() }
            if (!registeredOrphan || stateOf(call) == Call.STATE_DISCONNECTED) {
                // No callback will arrive for a Call that is already gone.
                cleanup(call)
            }
            return
        }

        val target = adopted ?: return
        try {
            call.registerCallback(callback, callbackHandler)
            registered = true
        } catch (e: Exception) {
            BridgeLog.e(TAG, "call callback registration failed: ${e.javaClass.simpleName}")
        }
        reportState(target, mapState(stateOf(call)), disconnectCause(call))
        if (cancelledDial) {
            try {
                call.disconnect()
            } catch (_: Exception) {
                BridgeLog.w(TAG, "could not disconnect cancelled dial")
            }
        }
        if (!registered) {
            BridgeLog.w(TAG, "owned call has no state callbacks")
        }
    }

    fun onTelecomCallRemoved(call: Call) {
        finishOwned(call, disconnectCause(call))
    }

    private fun onCallStateChanged(call: Call, state: Int) {
        if (!running) return
        var detached: AttachedAudio? = null
        var toneOwner: OwnedCall? = null
        var finish = false
        var cause: DisconnectCause? = null
        synchronized(lock) {
            val current = owned
            if (current == null || current.call !== call || current.finished) return
            current.telecomState = state
            cause = disconnectCause(call)
            val mapped = mapState(state)
            if (mapped == "ending" || mapped == "ended") {
                current.generation.set(Long.MAX_VALUE)
                detached = current.detachAudio()
                current.epoch = -1
                toneOwner = current
            }
            reportState(current, mapped, cause)
            finish = mapped == "ended"
        }
        releaseDetachedAudio(stateStore, detached, ::closeAudioAsync)
        toneOwner?.let { cancelToneFor(it) }
        if (finish) finishOwned(call, cause)
    }

    private fun onCallDetailsChanged(call: Call, details: Call.Details) {
        if (!running) return
        var detached: AttachedAudio? = null
        var toneOwner: OwnedCall? = null
        var finish = false
        var cause: DisconnectCause? = null
        synchronized(lock) {
            val current = owned
            if (current == null || current.call !== call || current.finished) return
            current.telecomState = details.state
            cause = disconnectCause(details)
            val mapped = mapState(details.state)
            if (mapped == "ending" || mapped == "ended") {
                current.generation.set(Long.MAX_VALUE)
                detached = current.detachAudio()
                current.epoch = -1
                toneOwner = current
            }
            reportState(current, mapped, cause)
            finish = mapped == "ended"
        }
        releaseDetachedAudio(stateStore, detached, ::closeAudioAsync)
        toneOwner?.let { cancelToneFor(it) }
        if (finish) finishOwned(call, cause)
    }

    private fun onCallDestroyed(call: Call) {
        finishOwned(call, disconnectCause(call))
    }

    private fun finishOwned(call: Call, cause: DisconnectCause?) {
        var audio: PhoneAudio? = null
        var stream: AudioStream? = null
        var callback: Call.Callback? = null
        var finished: OwnedCall? = null
        synchronized(lock) {
            val current = owned
            if (current == null || current.call !== call || current.finished) return
            current.finished = true
            current.state = "ended"
            current.detachAudio()?.let {
                audio = it.audio
                stream = it.stream
            }
            current.epoch = -1
            callback = current.callback
            current.callback = null
            finished = current
            owned = null
            rememberLocked(current.callId, current.destination, "ended")
            try {
                ledger.finish(current.callId, current.destination, "ended")
            } catch (e: CallLedgerException) {
                BridgeLog.e(TAG, "terminal state not durable: ${e.message}")
            }
        }
        val target = finished ?: return
        reportState(target, "ended", cause)
        closeAudioAsync(audio)
        callback?.let { cb -> runCatching { call.unregisterCallback(cb) } }
        cancelToneFor(target)
        restoreMuteForCall(target.callId, call)
        // Stream-scoped, and only when this call still had audio: a hang-up
        // already detached and reset it, and a call that started while this one
        // was finishing keeps the stats its own stream is already publishing.
        stream?.let { stateStore.audioReset(it) }
    }

    private fun matchesPending(current: OwnedCall, call: Call): Boolean {
        val details = detailsOf(call) ?: return false
        if (details.callDirection != Call.Details.DIRECTION_OUTGOING) return false
        if (details.state == Call.STATE_DISCONNECTED) return false
        val marker = extraMarker(details)
        if (marker != null) {
            // An explicit marker is authoritative: never adopt a Call tagged
            // for another bridge intent, even when the number matches.
            if (marker.owner != null && marker.owner != ownerPackage) return false
            return marker.callId == current.callId
        }
        val handle = details.handle ?: return false
        val candidate = handle.schemeSpecificPart ?: return false
        if (!phoneNumbersMatch(current.destination, candidate)) return false
        val created = details.creationTimeMillis
        if (created > 0 &&
            (
                created < current.dialStartedWall - 5_000 ||
                    created > current.dialStartedWall + DIAL_CORRELATION_WINDOW_MS
                )
        ) {
            return false
        }
        return true
    }

    private data class CallMarker(val callId: String, val owner: String?)

    private fun extraMarker(details: Call.Details): CallMarker? {
        val extras = try {
            details.intentExtras
        } catch (_: Exception) {
            null
        } ?: return null
        val callId = try {
            extras.getString(EXTRA_CALL_ID)
        } catch (_: Exception) {
            null
        }
        if (callId.isNullOrEmpty()) return null
        val owner = try {
            extras.getString(EXTRA_OWNER)
        } catch (_: Exception) {
            null
        }
        return CallMarker(callId, owner)
    }

    private fun phoneNumbersMatch(left: String, right: String): Boolean {
        val a = left.filter { it.isDigit() }
        val b = right.filter { it.isDigit() }
        if (a.isNotEmpty() && b.isNotEmpty()) return a == b
        return left.trim() == right.trim()
    }

    private fun mapState(state: Int): String = when (state) {
        Call.STATE_ACTIVE -> "active"

        Call.STATE_HOLDING -> "active"

        Call.STATE_DISCONNECTING -> "ending"

        Call.STATE_DISCONNECTED -> "ended"

        Call.STATE_NEW,
        Call.STATE_RINGING,
        Call.STATE_DIALING,
        Call.STATE_CONNECTING,
        Call.STATE_SELECT_PHONE_ACCOUNT -> "dialing"

        else -> "active"
    }

    private fun detailsOf(call: Call): Call.Details? = try {
        call.details
    } catch (_: Exception) {
        null
    }

    private fun stateOf(call: Call): Int = detailsOf(call)?.state ?: Call.STATE_NEW

    private fun disconnectCause(call: Call): DisconnectCause? = disconnectCause(detailsOf(call))

    private fun disconnectCause(details: Call.Details?): DisconnectCause? {
        val cause = details?.disconnectCause ?: return null
        return DisconnectCause(
            code = cause.code,
            label = cause.label?.toString() ?: "",
            reason = cause.reason ?: ""
        )
    }

    private fun abortOwnedForLinkLoss() {
        var attached: AttachedAudio? = null
        var call: Call? = null
        var toneOwner: OwnedCall? = null
        var pendingOnly = false
        synchronized(lock) {
            val current = owned ?: return
            val lateCall = current.call == null
            try {
                if (lateCall) {
                    ledger.orphan(
                        callId = current.callId,
                        destination = current.destination,
                        dialStartedWall = current.dialStartedWall,
                        ttlMs = ORPHAN_CANCEL_TTL_MS
                    )
                } else {
                    ledger.cancel(current.callId)
                }
            } catch (e: CallLedgerException) {
                BridgeLog.e(TAG, "link-loss cancellation not durable: ${e.message}")
            }
            current.cancelled = true
            current.generation.set(Long.MAX_VALUE)
            attached = current.detachAudio()
            current.epoch = -1
            call = current.call
            toneOwner = current
            pendingOnly = call == null
            if (pendingOnly) {
                owned = null
                rememberLocked(current.callId, current.destination, "ended")
                addOrphanLocked(current)
            }
        }
        closeAudioAsync(attached?.audio)
        toneOwner?.let { cancelToneFor(it) }
        call?.let { runCatching { it.disconnect() } }
        if (pendingOnly) {
            BridgeLog.w(TAG, "link lost before the telecom call appeared; cancellation retained as an orphan")
        }
        // Scoped to the stream this abort detached: the successor of a link-loss
        // abort must be able to publish the moment it attaches.
        attached?.let { stateStore.audioReset(it.stream) }
    }

    private fun abortOwnedOnShutdown() {
        var audio: PhoneAudio? = null
        var call: Call? = null
        var callback: Call.Callback? = null
        var toneOwner: OwnedCall? = null
        synchronized(lock) {
            val current = owned ?: return
            try {
                ledger.orphan(
                    callId = current.callId,
                    destination = current.destination,
                    dialStartedWall = current.dialStartedWall,
                    ttlMs = ORPHAN_CANCEL_TTL_MS
                )
            } catch (e: CallLedgerException) {
                BridgeLog.e(TAG, "shutdown cancellation not durable: ${e.message}")
            }
            current.cancelled = true
            current.generation.set(Long.MAX_VALUE)
            current.finished = true
            current.state = "ended"
            audio = current.detachAudio()?.audio
            current.epoch = -1
            call = current.call
            callback = current.callback
            current.callback = null
            toneOwner = current
            owned = null
            rememberLocked(current.callId, current.destination, "ended")
            addOrphanLocked(current)
        }
        audio?.close()
        callback?.let { cb -> runCatching { call?.unregisterCallback(cb) } }
        call?.let { runCatching { it.disconnect() } }
        toneOwner?.let { cancelToneFor(it) }
        stateStore.callEnded()
        // Whole-engine shutdown: nothing may publish after this.
        stateStore.resetAllAudio()
    }

    private fun expirePendingDial() {
        runCatching { ledger.pruneOrphans() }
        var expired: OwnedCall? = null
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            orphanCancels.removeAll { now > it.expiresAt }
            val current = owned ?: return
            if (current.call != null || current.finished) return
            if (now - current.dialStartedElapsed < PENDING_DIAL_TIMEOUT_MS) return
            current.finished = true
            current.state = "ended"
            owned = null
            rememberLocked(current.callId, current.destination, "ended")
            try {
                ledger.finish(current.callId, current.destination, "ended")
            } catch (e: CallLedgerException) {
                BridgeLog.e(TAG, "pending expiry not durable: ${e.message}")
            }
            expired = current
        }
        val target = expired ?: return
        BridgeLog.w(TAG, "pending dial expired without a telecom call")
        reportState(target, "ended", null)
        restoreMuteForCall(target.callId)
    }

    // ------------------------------------------------------------------
    // Durable recovery
    // ------------------------------------------------------------------

    private fun restoreLedgerLocked() {
        val snapshot = ledger.current()
        recent.clear()
        for (entry in snapshot.recent) {
            recent[entry.callId] = RecentCall(entry.destination, entry.state)
        }
        orphanCancels.clear()
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        for (orphan in snapshot.orphans) {
            val remaining = orphan.expiresWall - nowWall
            if (remaining <= 0L) continue
            orphanCancels.add(
                OrphanCancel(
                    callId = orphan.callId,
                    destination = orphan.destination,
                    dialStartedWall = orphan.dialStartedWall,
                    expiresAt = nowElapsed + remaining
                )
            )
        }
        val pending = snapshot.pending ?: return
        if (pending.cancelled) {
            // The previous process died while terminating this dial.  Keep it
            // only as a late-call tombstone; the intent is never re-dialled.
            try {
                ledger.orphan(
                    callId = pending.callId,
                    destination = pending.destination,
                    dialStartedWall = pending.createdWall,
                    ttlMs = ORPHAN_CANCEL_TTL_MS
                )
            } catch (e: CallLedgerException) {
                BridgeLog.e(TAG, "recovered cancellation not durable: ${e.message}")
            }
            val synthetic = OwnedCall(
                callId = pending.callId,
                destination = pending.destination,
                dialStartedWall = pending.createdWall,
                dialStartedElapsed = pending.createdElapsed
            )
            synthetic.cancelled = true
            synthetic.finished = true
            rememberLocked(pending.callId, pending.destination, "ended")
            addOrphanLocked(synthetic)
            return
        }
        if (pending.state == "ended") {
            // The Call already reached a terminal state; keep only the ID so a
            // repeated call_id is still refused.
            rememberLocked(pending.callId, pending.destination, "ended")
            try {
                ledger.finish(pending.callId, pending.destination, "ended")
            } catch (e: CallLedgerException) {
                BridgeLog.e(TAG, "recovered terminal state not durable: ${e.message}")
            }
            return
        }
        // Ownership survived the restart and is advertised in hello.  No
        // placeCall is issued; the Call is adopted when Telecom surfaces it.
        val restored = OwnedCall(
            callId = pending.callId,
            destination = pending.destination,
            dialStartedWall = pending.createdWall,
            dialStartedElapsed = pending.createdElapsed
        )
        restored.state = pending.state
        restored.reportedState = pending.state
        owned = restored
        BridgeLog.i(TAG, "recovered bridge call intent from durable state")
        stateStore.callStarted(pending.callId, maskDestination(pending.destination), System.currentTimeMillis())
        stateStore.callPhase(callPhaseOf(pending.state))
    }

    private fun adoptExistingCalls() {
        val relevant = synchronized(lock) {
            val pending = owned?.let { if (!it.finished && it.call == null) it else null }
            pending != null || orphanCancels.isNotEmpty()
        }
        if (!relevant) return
        for (call in BridgeRuntime.currentCalls()) {
            onTelecomCallAdded(call)
        }
    }

    // READ_PHONE_STATE is requested at pairing; a SecurityException is treated
    // as "a call may be in progress", so the detector's concern is handled.
    @SuppressLint("MissingPermission")
    private fun restoreAbandonedMuteAtStartup() {
        val manager = telecom ?: return
        val inCall = try {
            manager.isInCall
        } catch (_: Exception) {
            true
        }
        if (inCall) return
        val idle = synchronized(lock) {
            owned?.let { !it.finished && it.call != null } != true
        }
        if (!idle) return
        try {
            PhoneAudio.restoreAbandonedMute(appContext)
        } catch (e: Exception) {
            BridgeLog.e(TAG, "microphone restore failed: ${e.javaClass.simpleName}")
        }
    }

    private fun restoreMuteForCall(callId: String, exclude: Call? = null) {
        if (hasUnrelatedLiveCall(exclude)) return
        try {
            PhoneAudio.restoreAbandonedMute(appContext, callId)
        } catch (e: Exception) {
            BridgeLog.e(TAG, "microphone restore failed: ${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------
    // App API gateway
    // ------------------------------------------------------------------

    override val supportsAttachments: Boolean get() = links.supportsAttachments()

    override suspend fun exchange(request: DeviceApiRequest): DeviceApiResult {
        if (!links.isUp()) {
            return DeviceApiResult(
                id = request.id,
                status = 503,
                error = DeviceApiError("link_down", "the device link is down")
            )
        }
        val wire = json.encodeToString(DeviceApiRequest.serializer(), request)
        val deferred = CompletableDeferred<DeviceApiResult>()
        apiPending[request.id] = deferred
        try {
            if (!links.sendText(wire)) {
                return DeviceApiResult(
                    id = request.id,
                    status = 503,
                    error = DeviceApiError("link_down", "the device link refused the message")
                )
            }
            return withTimeout(APP_REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            return DeviceApiResult(
                id = request.id,
                status = 504,
                error = DeviceApiError("timeout", "the daemon did not answer in time")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DeviceApiResult(
                id = request.id,
                status = 500,
                error = DeviceApiError("transport_error", e.javaClass.simpleName)
            )
        } finally {
            apiPending.remove(request.id)
        }
    }

    override suspend fun receiveAttachment(header: AttachmentHeader, sink: OutputStream): AttachmentOutcome {
        if (!links.supportsAttachments()) return AttachmentOutcome.Unsupported
        val wire = json.encodeToString(AttachmentHeader.serializer(), header)
        val channel = links.openAttachment(wire)
            ?: return AttachmentOutcome.Failed("link_down", "the device link cannot open an attachment stream")
        return AttachmentReceiver(channel, sink).receive()
    }

    private fun failPendingApi(code: String, message: String) {
        for ((id, deferred) in apiPending) {
            deferred.complete(DeviceApiResult(id = id, status = 503, error = DeviceApiError(code, message)))
        }
        apiPending.clear()
    }

    /** Stores the token the daemon handed over the authenticated link, then refreshes capabilities. */
    private suspend fun acceptAppToken(token: String) {
        if (token == appToken) return
        val stored = withContext(Dispatchers.IO) {
            runCatching {
                val current = BridgeConfigLoader.loadOrNull(appContext) ?: return@runCatching false
                val updated = when (current) {
                    is BridgeConfig.Address -> current.copy(appToken = token)
                    is BridgeConfig.Iroh -> current.copy(appToken = token)
                }
                BridgeConfigLoader.write(appContext, updated)
                true
            }.getOrDefault(false)
        }
        if (!stored) {
            BridgeLog.w(TAG, "could not store the app token from the daemon")
            return
        }
        appToken = token
        BridgeLog.i(TAG, "app API access granted by the daemon")
        refreshCapabilities()
    }

    private suspend fun refreshCapabilities() {
        val token = appToken ?: return
        val request = DeviceApiRequest(
            id = UUID.randomUUID().toString(),
            method = "GET",
            path = "/app/v1/info",
            appToken = token
        )
        val result = withTimeoutOrNull(APP_REQUEST_TIMEOUT_MS) { exchange(request) } ?: return
        val element = result.result ?: return
        if (result.status !in 200..299) return
        val info = runCatching {
            json.decodeFromJsonElement(DaemonInfo.serializer(), element)
        }.getOrNull() ?: return
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

    // ------------------------------------------------------------------
    // Telemetry
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission", "DEPRECATION")
    private fun sampleDeviceState(): DeviceState {
        val battery = appContext.getSystemService(BatteryManager::class.java)
        val batteryPercent = runCatching {
            battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        }.getOrNull()
        val charging = runCatching { battery?.isCharging }.getOrNull()
        val power = appContext.getSystemService(PowerManager::class.java)
        val thermal = runCatching {
            when (power?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> null
            }
        }.getOrNull()
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = runCatching {
            connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        }.getOrNull()
        val network = when {
            capabilities == null -> null
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val rssi = runCatching {
            @Suppress("DEPRECATION")
            appContext.getSystemService(WifiManager::class.java)
                ?.connectionInfo
                ?.rssi
                ?.takeIf { it != -127 && it != 0 }
        }.getOrNull()
        val dozeExempt = runCatching {
            power?.isIgnoringBatteryOptimizations(ownerPackage)
        }.getOrNull()
        val missing = Permissions.runtimeMissing(appContext) + Permissions.privilegedMissing(appContext)
        return DeviceState(
            batteryPercent = batteryPercent,
            charging = charging,
            thermal = thermal,
            network = network,
            wifiRssi = rssi,
            dozeExempt = dozeExempt,
            missingPermissions = missing
        )
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private fun closeAudioAsync(audio: PhoneAudio?) {
        if (audio == null) return
        if (!running || commandExecutor.isShutdown) {
            audio.close()
            return
        }
        pendingAudioClose.add(audio)
        try {
            commandExecutor.execute {
                try {
                    audio.close()
                } finally {
                    pendingAudioClose.remove(audio)
                }
            }
        } catch (_: RejectedExecutionException) {
            pendingAudioClose.remove(audio)
            audio.close()
        }
    }

    private fun drainPendingAudioClose() {
        while (true) {
            val audio = pendingAudioClose.poll() ?: return
            audio.close()
        }
    }

    private fun cancelToneFor(call: OwnedCall) {
        audioCancellation.stopAll(call)
    }

    private fun reportState(call: OwnedCall, state: String, cause: DisconnectCause?) {
        val causeKey = cause?.let { "${it.code}|${it.label}|${it.reason}" }
        synchronized(lock) {
            if (call.finished && state != "ended") return
            call.state = state
            if (call.reportedState == state) {
                if (state != "ended") return
                if (causeKey == null) return
                if (call.reportedCauseKey == causeKey) return
            }
            call.reportedState = state
            call.reportedCauseKey = causeKey
            try {
                ledger.markState(call.callId, state)
            } catch (e: CallLedgerException) {
                BridgeLog.e(TAG, "call state not durable: ${e.message}")
            }
        }
        send(DeviceOutbound.CallState(call.callId, state, cause))
        stateStore.callPhase(callPhaseOf(state))
    }

    private fun replyOk(id: String, data: JsonObject? = null) {
        send(DeviceOutbound.Response(id = id, ok = true, data = data))
    }

    private fun replyError(id: String, error: String) {
        send(DeviceOutbound.Response(id = id, ok = false, error = error))
    }

    private fun addOrphanLocked(call: OwnedCall) {
        val now = SystemClock.elapsedRealtime()
        orphanCancels.removeAll { now > it.expiresAt }
        while (orphanCancels.size >= 4) {
            orphanCancels.removeAt(0)
        }
        orphanCancels.add(
            OrphanCancel(
                callId = call.callId,
                destination = call.destination,
                dialStartedWall = call.dialStartedWall,
                expiresAt = now + ORPHAN_CANCEL_TTL_MS
            )
        )
    }

    private fun takeOrphanLocked(call: Call): OrphanCancel? {
        if (orphanCancels.isEmpty()) return null
        val details = detailsOf(call) ?: return null
        if (details.callDirection != Call.Details.DIRECTION_OUTGOING) return null
        if (details.state == Call.STATE_DISCONNECTED) return null
        val marker = extraMarker(details)
        if (marker != null) {
            if (marker.owner != null && marker.owner != ownerPackage) return null
            val index = orphanCancels.indexOfFirst { it.callId == marker.callId }
            if (index < 0) return null
            val orphan = orphanCancels.removeAt(index)
            try {
                ledger.consumeOrphan(orphan.callId, orphan.dialStartedWall)
            } catch (e: CallLedgerException) {
                BridgeLog.e(TAG, "orphan tombstone not cleared: ${e.message}")
            }
            return orphan
        }
        val candidate = details.handle?.schemeSpecificPart ?: return null
        val created = details.creationTimeMillis
        val now = SystemClock.elapsedRealtime()
        var index = 0
        while (index < orphanCancels.size) {
            val orphan = orphanCancels[index]
            if (now > orphan.expiresAt) {
                orphanCancels.removeAt(index)
                continue
            }
            if (phoneNumbersMatch(orphan.destination, candidate) &&
                (
                    created <= 0 ||
                        (
                            created >= orphan.dialStartedWall - 5_000 &&
                                created <= orphan.dialStartedWall + DIAL_CORRELATION_WINDOW_MS
                            )
                    )
            ) {
                orphanCancels.removeAt(index)
                try {
                    ledger.consumeOrphan(orphan.callId, orphan.dialStartedWall)
                } catch (e: CallLedgerException) {
                    BridgeLog.e(TAG, "orphan tombstone not cleared: ${e.message}")
                }
                return orphan
            }
            index++
        }
        return null
    }

    private fun clearFailedDial(callId: String) {
        var cleared = false
        var attached: AttachedAudio? = null
        synchronized(lock) {
            val current = owned
            if (current != null && current.callId == callId &&
                current.call == null && !current.finished
            ) {
                attached = current.detachAudio()
                owned = null
                rememberLocked(callId, current.destination, "failed")
                try {
                    ledger.finish(callId, current.destination, "failed")
                } catch (e: CallLedgerException) {
                    BridgeLog.e(TAG, "dial failure not durable: ${e.message}")
                }
                cleared = true
            }
        }
        if (!cleared) return
        stateStore.callPhase(CallPhase.ENDED)
        // A dial that never got its Call cannot have audio of its own; any
        // numbers still live belong to no call, so they are cleared wholesale
        // only after this failure took the owned call away.
        val detached = attached
        if (detached != null) {
            stateStore.audioReset(detached.stream)
        } else {
            stateStore.resetAllAudio()
        }
    }

    private fun rememberLocked(callId: String, destination: String, state: String) {
        recent[callId] = RecentCall(destination, state)
        while (recent.size > MAX_RECENT_CALLS) {
            val oldest = recent.keys.firstOrNull() ?: break
            recent.remove(oldest)
        }
    }

    private fun hasUnrelatedLiveCall(exclude: Call? = null): Boolean {
        val ownCall = synchronized(lock) { owned?.call }
        return BridgeRuntime.currentCalls().any { call ->
            if (call === ownCall || (exclude != null && call === exclude)) {
                false
            } else {
                try {
                    call.details?.state != Call.STATE_DISCONNECTED
                } catch (_: Exception) {
                    true
                }
            }
        }
    }

    // The READ_PHONE_STATE grant is checked above and a SecurityException is
    // translated to phone_account_unavailable.
    @SuppressLint("MissingPermission")
    private fun resolvePhoneAccount(requested: String?): PhoneAccountHandle? {
        if (requested == null) return null
        if (!Permissions.granted(appContext, Permissions.READ_PHONE_STATE)) {
            throw CommandException("phone_state_permission_denied")
        }
        val manager = telecom ?: throw CommandException("telecom_unavailable")
        val accounts = try {
            manager.callCapablePhoneAccounts
        } catch (_: SecurityException) {
            throw CommandException("phone_account_unavailable")
        } catch (_: Exception) {
            throw CommandException("phone_account_unavailable")
        }
        val byId = accounts.firstOrNull { it.id == requested }
        if (byId != null) return byId
        val byComponent = accounts.filter { handle ->
            val component = handle.componentName
            requested == component.flattenToShortString() ||
                requested == component.flattenToString() ||
                requested == "${component.flattenToShortString()}/${handle.id}" ||
                requested == "${component.flattenToString()}/${handle.id}"
        }
        if (byComponent.size == 1) return byComponent.first()
        if (byComponent.size > 1) throw CommandException("phone_account_ambiguous")
        throw CommandException("phone_account_unavailable")
    }

    internal class OwnedCall(
        val callId: String,
        val destination: String,
        val dialStartedWall: Long = System.currentTimeMillis(),
        val dialStartedElapsed: Long = SystemClock.elapsedRealtime()
    ) {
        val generation = AtomicLong(0)
        var call: Call? = null
        var callback: Call.Callback? = null
        var state: String = "dialing"
        var telecomState: Int = -1

        /** The audio and the store stream it publishes into; the two never travel apart. */
        var attached: AttachedAudio? = null
        var epoch: Long = -1
        var lastEpoch: Long = -1
        var cancelled: Boolean = false
        var finished: Boolean = false
        var reportedState: String? = null
        var reportedCauseKey: String? = null

        /**
         * Takes the audio out of this call.  The caller resets exactly
         * [AttachedAudio.stream] and nothing else, so a cleanup that runs after
         * a successor attached cannot clear the successor's numbers.
         */
        fun detachAudio(): AttachedAudio? = attached.also { attached = null }

        /**
         * Takes the audio of the callback at [epoch], or returns null when that
         * callback is obsolete (another epoch, or nothing attached).  An
         * obsolete fatal callback must change nothing, including the store.
         */
        fun detachAudioAt(epoch: Long): AttachedAudio? {
            if (attached == null || this.epoch != epoch) return null
            this.epoch = -1
            return detachAudio()
        }

        /** The stream to publish from, or null once this call has finished. */
        fun audioToPublish(): AttachedAudio? = if (finished) null else attached
    }

    private data class RecentCall(val destination: String, val state: String)

    private data class OrphanCancel(
        val callId: String,
        val destination: String,
        val dialStartedWall: Long,
        val expiresAt: Long
    )

    private class CommandException(val code: String) : Exception(code)
}

/**
 * The audio of the owned call together with the store stream its stats belong
 * to.  They are one value so every teardown can name the stream it actually
 * detached: a cleanup that finds nothing has nothing to reset.
 */
internal class AttachedAudio(val audio: PhoneAudio, val stream: AudioStream)

/**
 * Which audio a command may stop.
 *
 * `cancel_actions` exists to interrupt what an action started — the DTMF tone of
 * a keypress.  The Lab test tone is started and stopped by its own
 * `tone_start`/`tone_stop` pair, so routine supervision must leave it running
 * until its scheduled stop; an explicit stop or any teardown still stops both.
 */
internal class CallAudioCancellation(
    private val stopActionAudio: (BridgeEngine.OwnedCall) -> Unit,
    private val stopLabTone: (BridgeEngine.OwnedCall) -> Unit
) {
    /** A routine `cancel_actions`: only the tone the action itself started. */
    fun cancelActions(call: BridgeEngine.OwnedCall) {
        stopActionAudio(call)
    }

    /** An explicit stop or a teardown: the action's tone and the Lab tone. */
    fun stopAll(call: BridgeEngine.OwnedCall) {
        stopActionAudio(call)
        stopLabTone(call)
    }
}

/**
 * Closes a detached stream's audio and clears its numbers, after the engine lock is released.
 * The stream is gone for good: a later cleanup of the same call finds nothing to reset, and a
 * publish still in flight for it is refused by the store.
 */
internal fun releaseDetachedAudio(
    store: EngineStateStore,
    detached: AttachedAudio?,
    close: (PhoneAudio?) -> Unit
) {
    close(detached?.audio)
    detached?.let { store.audioReset(it.stream) }
}

/**
 * One admitted idle-only link action: the iroh link holds it while it recycles
 * the endpoint or reports the link down for a relay-only escalation, and the
 * engine refuses to reserve a call until it is released.  The action is short
 * (null the endpoint, report down); anything long must not hold it.
 */
internal class IdleLinkAction internal constructor(private val onRelease: () -> Unit) : AutoCloseable {
    private val released = AtomicBoolean(false)

    /** Ends the action; safe to call more than once and from any thread. */
    fun release() {
        if (released.compareAndSet(false, true)) onRelease()
    }

    override fun close() = release()
}

/**
 * The door between idle-only link maintenance and call reservation.
 *
 * [lock] is the engine's call-ownership monitor: every read and write of the
 * owned call already happens under it, so an action admitted here can never
 * overlap a reservation, and a dial can never reserve while an action is in
 * flight.  One action runs at a time; [awaitIdle] lets a dial wait the action
 * out instead of failing a command that arrives in the same millisecond.
 */
internal class CallAdmission {
    /** The engine's call-ownership lock: one monitor for the owned call and this door. */
    internal val lock: Any = Any()

    private var actionInFlight = false

    /**
     * Admits one idle-only action, or null while a call is owned (including
     * the reservation window) or another action still runs.  [callOwned] is
     * read under [lock] and must not block.
     */
    fun tryBeginIdleLinkAction(callOwned: () -> Boolean): IdleLinkAction? = synchronized(lock) {
        if (actionInFlight || callOwned()) return null
        actionInFlight = true
        IdleLinkAction {
            synchronized(lock) {
                actionInFlight = false
                (lock as Object).notifyAll()
            }
        }
    }

    /**
     * Waits up to [timeoutMs] for an in-flight action to end; true when the
     * caller may reserve.  The monitor is released while waiting, so the action
     * can finish and a link-down abort can proceed meanwhile.
     */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(lock) {
            while (actionInFlight) {
                val leftMs = (deadline - System.nanoTime()) / 1_000_000L
                if (leftMs <= 0L) return false
                try {
                    (lock as Object).wait(leftMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return true
        }
    }

    /** True while an action holds the door; the caller holds [lock] to reserve. */
    fun actionInFlightLocked(): Boolean = actionInFlight
}

/**
 * What the phone can do inside a bridge call: exactly the two bits the daemon
 * learns from `hello`.  Read from permissions, never from stored state.
 */
internal data class PhoneCapabilities(
    val callControl: Boolean,
    val frameworkAudio: Boolean
)

/**
 * Remembers the capabilities the last queued `hello` announced.
 *
 * `callControl` and `frameworkAudio` reach the daemon only through `hello`, so
 * a permission granted or revoked while the link is up stays invisible to it
 * until the next handshake.  This watch compares permission samples against the
 * last hello — never against the UI [Capabilities], which [BridgeEngine] also
 * writes with the daemon's voice-control support — and only reports a change
 * that two consecutive samples agree on.
 */
internal class CapabilityWatch {
    private var announced: PhoneCapabilities? = null

    /** The last sample that differed from [announced], waiting for the next one. */
    private var candidate: PhoneCapabilities? = null

    /** The transport accepted a hello carrying [capabilities]. */
    @Synchronized
    fun helloSent(capabilities: PhoneCapabilities) {
        announced = capabilities
        candidate = null
    }

    /** The link went down: the next hello announces whatever it finds then. */
    @Synchronized
    fun helloGone() {
        announced = null
        candidate = null
    }

    /**
     * Records one permission sample and says whether the change is stable:
     * true only when this sample and the one before it agree and both differ
     * from the last hello.  A sample matching the last hello clears the
     * candidate, so permissions alternating around the hello never admit a
     * refresh.  False before the first hello.
     */
    @Synchronized
    fun stableChange(current: PhoneCapabilities): Boolean {
        if (announced == null || current == announced) {
            candidate = null
            return false
        }
        val previous = candidate
        candidate = current
        return previous == current
    }
}

/**
 * Turns a stable capability change into one link refresh.
 *
 * Two consecutive samples must agree and differ from the last hello before a
 * refresh is admitted, so a permission flapping on and off never reconnects.
 * The idle door is taken before the decision and held through the synchronous
 * link-down publication, so a call reserved a moment earlier is never cut by a
 * refresh decided on an older sample; the re-sample under the door re-checks
 * the change has not reverted meanwhile.  A refused door defers: the candidate
 * stays, and the next stable sample decides again.
 */
internal class CapabilityRefresh(
    private val watch: CapabilityWatch,
    private val admission: CallAdmission,
    private val callOwned: () -> Boolean,
    private val sample: () -> PhoneCapabilities,
    private val reconnect: (reason: String) -> Boolean
) {
    /**
     * One sample; true when the link was dropped so the next attempt sends a
     * hello carrying what the phone can do now.
     */
    fun run(): Boolean {
        if (!watch.stableChange(sample())) return false
        val action = admission.tryBeginIdleLinkAction(callOwned) ?: return false
        try {
            if (!watch.stableChange(sample())) return false
            return reconnect(CAPABILITIES_CHANGED)
        } finally {
            action.close()
        }
    }

    companion object {
        /** Link-down reason: only a fresh hello can tell the daemon otherwise. */
        const val CAPABILITIES_CHANGED = "capabilities_changed"
    }
}

/** The `hello` a link sends first: the phone's identity, call capabilities and preferences. */
internal fun helloFrame(
    capabilities: PhoneCapabilities,
    device: String,
    bootId: String,
    busy: Boolean,
    activeCall: DeviceActiveCall?,
    preferredCodec: String,
    maxBitrate: Int,
    adbTunnel: Boolean
): DeviceOutbound.Hello = DeviceOutbound.Hello(
    device = device,
    bootId = bootId,
    capabilities = DeviceCapabilities(
        callControl = capabilities.callControl,
        frameworkAudio = capabilities.frameworkAudio,
        busy = busy,
        linkTest = true,
        adbTunnel = adbTunnel,
        audioCodecs = listOf("pcm", "opus")
    ),
    activeCall = activeCall,
    preferredCodec = preferredCodec,
    maxBitrate = maxBitrate
)
