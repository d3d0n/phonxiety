package com.phonxiety.bridge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.Call
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Protocol v1 device endpoint.
 *
 * Owns the single device link, the single bridge-owned Telecom call, its
 * audio path and the phone-side host-loss lease.  All externally visible state
 * changes are serialized here; [BridgeInCallService] only forwards Telecom
 * callbacks.
 *
 * The link is a TLS WebSocket to the paired daemon or an iroh stream; see
 * [DeviceLink].  The engine keeps the protocol rules and reconnect policy, the
 * transport only moves messages.  [onLinkState] receives a string resource id
 * for the foreground-service notification.
 */
class BridgeEngine(
    context: Context,
    private val config: BridgeConfig,
    private val onLinkState: (Int) -> Unit,
) {
    companion object {
        private const val TAG = "PhonxietyBridge"
        private const val PROTOCOL_VERSION = 1
        private const val HOST_TIMEOUT_MS = 10_000L
        private const val RECONNECT_MAX_MS = 8_000L
        private const val PENDING_DIAL_TIMEOUT_MS = 120_000L
        private const val MAX_RECENT_CALLS = 32
        private const val ORPHAN_CANCEL_TTL_MS = 10 * 60_000L
        private const val DIAL_CORRELATION_WINDOW_MS = 10_000L
        private const val MAX_CONTROL_CHARS = 64 * 1024
        // 500 ms of 48 kHz mono PCM16: above this backlog a live capture frame
        // is dropped instead of turning a network stall into growing latency.
        private const val MAX_AUDIO_BACKLOG_BYTES = 48_000L
        private const val EXTRA_CALL_ID = "com.phonxiety.bridge.extra.CALL_ID"
        private const val EXTRA_OWNER = "com.phonxiety.bridge.extra.OWNER"
        private val DESTINATION = Regex("^[+*#0-9]{1,32}$")
        private val LINK_TEST_MAGIC = "PXLT".encodeUtf8()
    }

    private val appContext = context.applicationContext
    private val ownerPackage = appContext.packageName
    private val deviceBootId: String = bootId()
    private val ledger = CallLedger(appContext, deviceBootId)
    private val lock = Any()
    private val dtmfMonitor = java.lang.Object()
    private val closeOnce = AtomicBoolean(false)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "phonxiety-scheduler").apply { isDaemon = true }
    }
    private val commandExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phonxiety-commands").apply { isDaemon = true }
    }
    private val dtmfExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phonxiety-dtmf").apply { isDaemon = true }
    }
    private val callbackThread = HandlerThread("phonxiety-callbacks").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    private val transport: DeviceLinkFactory = when (config) {
        is BridgeConfig.Address -> WebSocketLinkFactory(config)
        is BridgeConfig.Iroh -> IrohLinkFactory(appContext, config) { ownsCall() }
    }

    private val telecom: TelecomManager? = appContext.getSystemService(TelecomManager::class.java)
    private val wakeLock: PowerManager.WakeLock? =
        appContext.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonxiety:bridge")
            ?.apply { setReferenceCounted(false) }

    @Volatile private var running = false
    @Volatile private var linkUp = false

    private var link: DeviceLink? = null
    private var reconnectScheduled = false
    private var reconnectDelayMs = 1_000L
    private var lastHostActivity = 0L

    private var owned: OwnedCall? = null
    private val recent = LinkedHashMap<String, RecentCall>()
    private val pendingAudioClose = ConcurrentLinkedQueue<PhoneAudio>()
    private val orphanCancels = ArrayList<OrphanCancel>()

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            restoreLedgerLocked()
        }
        runCatching { wakeLock?.acquire() }
        onLinkState(R.string.notification_link_waiting)
        try {
            scheduler.schedule({
                safe { adoptExistingCalls() }
                safe { restoreAbandonedMuteAtStartup() }
                safe { connect() }
            }, 0, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            return
        }
        scheduler.scheduleWithFixedDelay({ safe { watchdog() } }, 1, 1, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ safe { emitPlayback() } }, 500, 500, TimeUnit.MILLISECONDS)
        scheduler.scheduleWithFixedDelay({ safe { expirePendingDial() } }, 5, 5, TimeUnit.SECONDS)
    }

    fun close() {
        if (!closeOnce.compareAndSet(false, true)) return
        synchronized(lock) {
            running = false
            linkUp = false
            lastHostActivity = 0L
            val current = link
            link = null
            if (current != null) runCatching { current.cancel() }
        }
        scheduler.shutdownNow()
        abortOwnedOnShutdown()
        commandExecutor.shutdownNow()
        drainPendingAudioClose()
        dtmfExecutor.shutdownNow()
        callbackThread.quitSafely()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { transport.shutdown() }
    }

    /** `dialing`, `active` or `ending` while this engine owns a call. */
    fun activeCallState(): String? = synchronized(lock) {
        val current = owned ?: return null
        if (current.finished) null else current.state
    }

    fun linkIsUp(): Boolean = synchronized(lock) { linkUp }

    /** Route of the live link for the UI, or null while down. */
    internal fun currentRoute(): DeviceLink.Route? = synchronized(lock) {
        if (!linkUp) null else link?.route()
    }

    // ------------------------------------------------------------------
    // Device link
    // ------------------------------------------------------------------

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "background task failed: ${t.javaClass.simpleName}")
        }
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

    private fun connect() {
        synchronized(lock) {
            if (!running || link != null) return
        }
        val attempt = transport.create()
        val installed = synchronized(lock) {
            if (!running) {
                false
            } else if (link == null) {
                link = attempt
                true
            } else {
                false
            }
        }
        if (!installed) {
            runCatching { attempt.cancel() }
            return
        }
        attempt.start(LinkListener(attempt))
    }

    /** Binds one transport attempt to its engine-side callbacks. */
    private inner class LinkListener(private val owner: DeviceLink) : DeviceLink.Listener {
        override fun onLinkOpened() {
            this@BridgeEngine.onOpen(owner)
        }

        override fun onLinkText(text: String) {
            this@BridgeEngine.onText(owner, text)
        }

        override fun onLinkBinary(bytes: ByteString) {
            this@BridgeEngine.onBinary(owner, bytes)
        }

        override fun onLinkDown(reason: String) {
            this@BridgeEngine.onLinkDown(owner, reason)
        }
    }

    private fun scheduleReconnect() {
        val delay = synchronized(lock) {
            if (!running || reconnectScheduled) return
            reconnectScheduled = true
            val next = reconnectDelayMs
            reconnectDelayMs = minOf(reconnectDelayMs * 2, RECONNECT_MAX_MS)
            next
        }
        try {
            scheduler.schedule({
                synchronized(lock) { reconnectScheduled = false }
                safe { connect() }
            }, delay, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { reconnectScheduled = false }
        }
    }

    private fun onOpen(owner: DeviceLink) {
        synchronized(lock) {
            if (!running) {
                runCatching { owner.cancel() }
                return
            }
            if (link != null && link !== owner) {
                runCatching { owner.cancel() }
                return
            }
            link = owner
            linkUp = true
            reconnectScheduled = false
            reconnectDelayMs = 1_000L
            lastHostActivity = SystemClock.elapsedRealtime()
        }
        onLinkState(R.string.notification_link_connected)
        sendHello(owner)
    }

    private fun onLinkDown(owner: DeviceLink, reason: String) {
        val shouldHandle = synchronized(lock) {
            if (link === owner) {
                link = null
            } else if (link != null) {
                return
            }
            linkUp = false
            lastHostActivity = 0L
            running
        }
        if (!shouldHandle) return
        Log.w(TAG, "device link down: $reason")
        onLinkState(R.string.notification_link_waiting)
        abortOwnedForLinkLoss()
        scheduleReconnect()
    }

    private fun onText(owner: DeviceLink, text: String) {
        synchronized(lock) {
            if (link !== owner) return
            lastHostActivity = SystemClock.elapsedRealtime()
        }
        if (text.length > MAX_CONTROL_CHARS) {
            Log.w(TAG, "ignoring oversized control message")
            return
        }
        val message = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        if (message.optString("type") != "command") return
        val id = message.optString("id", "")
        val command = message.optString("command", "")
        if (id.isEmpty() || command.isEmpty()) return
        try {
            commandExecutor.execute { handleCommand(id, command, message) }
        } catch (_: RejectedExecutionException) {
            // Shutting down.
        }
    }

    private fun onBinary(owner: DeviceLink, bytes: ByteString) {
        synchronized(lock) {
            if (link !== owner) return
            lastHostActivity = SystemClock.elapsedRealtime()
        }
        if (bytes.size < 16 || bytes.size > MAX_CONTROL_CHARS) return
        val audio = synchronized(lock) { owned?.audio }
        if (audio != null) {
            audio.offerFrame(bytes)
        } else if (bytes.startsWith(LINK_TEST_MAGIC)) {
            // The daemon times an idle link under call load: return its frame.
            sendFrame(bytes.toByteArray(), bytes.size)
        }
    }

    private fun sendText(message: JSONObject) {
        val target = synchronized(lock) { if (linkUp) link else null } ?: return
        target.sendText(message.toString())
    }

    /** False when the frame was discarded: no link, or a backlog above the cap. */
    private fun sendFrame(frame: ByteArray, length: Int): Boolean {
        val target = synchronized(lock) { if (linkUp) link else null } ?: return false
        // Live capture: never let a stalled network turn into growing latency.
        // Control messages use sendText and are never dropped.
        if (target.backlogBytes > MAX_AUDIO_BACKLOG_BYTES) return false
        target.sendBinary(frame, length)
        return true
    }

    private fun sendHello(owner: DeviceLink) {
        val busy = hasUnrelatedLiveCall()
        val callControl = Permissions.granted(appContext, Permissions.CALL_PHONE) &&
            Permissions.granted(appContext, Permissions.CONTROL_INCALL_EXPERIENCE)
        val frameworkAudio = Permissions.granted(appContext, Permissions.CAPTURE_AUDIO_OUTPUT) &&
            Permissions.granted(appContext, Permissions.MODIFY_PHONE_STATE) &&
            Permissions.granted(appContext, Permissions.RECORD_AUDIO)
        val activePair: Pair<String, String>? = synchronized(lock) {
            val current = owned
            if (current == null || current.finished) null else current.callId to current.state
        }
        val active: Any = if (activePair == null) {
            JSONObject.NULL
        } else {
            JSONObject()
                .put("call_id", activePair.first)
                .put("state", activePair.second)
        }
        val hello = JSONObject()
            .put("type", "hello")
            .put("version", PROTOCOL_VERSION)
            .put("device", android.os.Build.DEVICE ?: "unknown")
            .put("boot_id", deviceBootId)
            .put("capabilities", JSONObject()
                .put("call_control", callControl)
                .put("framework_audio", frameworkAudio)
                .put("busy", busy)
                .put("link_test", true)
                .put("audio_codecs", org.json.JSONArray().put("pcm").put("opus")))
            .put("active_call", active)
        if (!owner.sendText(hello.toString())) onLinkDown(owner, "hello_failed")
    }

    private fun bootId(): String {
        try {
            val count = android.provider.Settings.Global.getInt(
                appContext.contentResolver, android.provider.Settings.Global.BOOT_COUNT
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

    private fun watchdog() {
        val now = SystemClock.elapsedRealtime()
        val stale = synchronized(lock) {
            if (!running || !linkUp) return
            if (now - lastHostActivity <= HOST_TIMEOUT_MS) return
            link
        }
        val owner = stale ?: return
        Log.w(TAG, "host activity deadline expired")
        onLinkDown(owner, "host_timeout")
        runCatching { owner.cancel() }
    }

    private fun emitPlayback() {
        val state: Triple<PhoneAudio, String, Long> = synchronized(lock) {
            val current = owned
            val audio = current?.audio
            if (current == null || audio == null) return
            Triple(audio, current.callId, current.epoch)
        }
        val stats = state.first.stats()
        sendText(JSONObject()
            .put("type", "playback")
            .put("call_id", state.second)
            .put("epoch", state.third)
            .put("played_samples", stats.first)
            .put("queued_samples", stats.second))
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private fun handleCommand(id: String, command: String, message: JSONObject) {
        if (!running) return
        try {
            when (command) {
                "dial" -> handleDial(id, message)
                "hangup" -> handleHangup(id, message)
                "start_audio" -> handleStartAudio(id, message)
                "flush" -> handleFlush(id, message)
                "dtmf" -> handleDtmf(id, message)
                "cancel_actions" -> handleCancelActions(id, message)
                "ping" -> replyOk(id, JSONObject().put("pong", true))
                else -> replyError(id, "unknown_command")
            }
        } catch (e: CommandException) {
            replyError(id, e.code)
        } catch (e: Exception) {
            Log.e(TAG, "command failed: ${e.javaClass.simpleName}")
            replyError(id, "internal_error")
        }
    }

    private fun handleDial(id: String, message: JSONObject) {
        val callId = callIdOf(message)
        val destination = message.optString("destination", "").trim()
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
        val requestedAccount = message.optString("phone_account", "").trim().ifEmpty { null }
        val unrelatedCall = hasUnrelatedLiveCall()

        var duplicateState: String? = null
        var reserved: OwnedCall? = null
        synchronized(lock) {
            if (!running) throw CommandException("stopped")
            if (!linkUp) throw CommandException("link_down")
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
                        createdElapsed = created.dialStartedElapsed,
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
            if (duplicate == "failed") {
                replyError(id, "dial_failed_previous")
            } else {
                replyOk(id, JSONObject()
                    .put("call_id", callId)
                    .put("state", duplicate)
                    .put("duplicate", true))
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
            running && linkUp && reservedCall != null && owned === reservedCall
        }
        if (!stillOwned) {
            clearFailedDial(callId)
            throw CommandException("link_down")
        }
        try {
            manager.placeCall(Uri.fromParts("tel", destination, null), extras)
        } catch (_: SecurityException) {
            clearFailedDial(callId)
            Log.e(TAG, "placeCall denied")
            throw CommandException("call_permission_denied")
        } catch (e: Exception) {
            clearFailedDial(callId)
            Log.e(TAG, "placeCall failed: ${e.javaClass.simpleName}")
            throw CommandException("dial_failed")
        }

        val state = synchronized(lock) {
            owned?.takeIf { it.callId == callId }?.state ?: "dialing"
        }
        replyOk(id, JSONObject()
            .put("call_id", callId)
            .put("state", state))
    }

    private fun handleHangup(id: String, message: JSONObject) {
        val callId = callIdOf(message)
        var audio: PhoneAudio? = null
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
                            ttlMs = ORPHAN_CANCEL_TTL_MS,
                        )
                    } catch (_: CallLedgerException) {
                        throw CommandException("state_persist_failed")
                    }
                } else {
                    try {
                        ledger.cancel(current.callId)
                    } catch (e: CallLedgerException) {
                        Log.e(TAG, "cancellation not durable: ${e.message}")
                    }
                }
                current.cancelled = true
                current.generation.set(Long.MAX_VALUE)
                audio = current.audio
                current.audio = null
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
            val terminal = terminalState
            replyOk(id, JSONObject().put("call_id", callId).put("state", terminal))
            return
        }
        toneOwner?.let { cancelToneFor(it) }
        closeAudioAsync(audio)
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
                    replyOk(id, JSONObject().put("call_id", callId).put("state", terminal))
                } else {
                    replyError(id, "disconnect_failed")
                }
                return
            }
        }
        if (pendingOnly) {
            Log.w(TAG, "hangup requested before the telecom call appeared; retained")
        }
        replyOk(id, JSONObject().put("call_id", callId).put("state", "ending"))
    }

    private fun handleStartAudio(id: String, message: JSONObject) {
        val callId = callIdOf(message)
        val epoch = epochOf(message)
        val opusBitrate = when (message.optString("codec", "pcm")) {
            "pcm" -> null
            "opus" -> message.optInt("bitrate", 32_000).also {
                if (it !in 6_000..128_000) throw CommandException("bitrate_invalid")
            }
            else -> throw CommandException("codec_unsupported")
        }
        var target: OwnedCall? = null
        var previousAudio: PhoneAudio? = null

        synchronized(lock) {
            val current = owned
            if (current == null || current.callId != callId) throw CommandException("not_owned")
            if (!linkUp) throw CommandException("link_down")
            if (current.call == null || current.telecomState != Call.STATE_ACTIVE) throw CommandException("not_active")
            if (epoch < current.lastEpoch) throw CommandException("stale_epoch")
            if (current.audio != null && current.epoch == epoch) {
                replyOk(id, JSONObject()
                    .put("call_id", callId)
                    .put("epoch", epoch)
                    .put("state", "started")
                    .put("codec", current.audio!!.codec))
                return
            }
            if (current.audio != null) {
                previousAudio = current.audio
                current.audio = null
                current.epoch = -1
            }
            target = current
        }
        // A new epoch supersedes the old session.  Close it before the new
        // stream starts so leftover RX frames and queued TX audio from the
        // previous epoch cannot overlap the new one.
        previousAudio?.close()

        val current = target ?: throw CommandException("not_owned")
        val audio = PhoneAudio(
            appContext,
            callId,
            epoch,
            opusBitrate,
            { frame, length -> sendFrame(frame, length) },
            { reason -> postAudioFatal(callId, epoch, reason) },
        )
        try {
            audio.start()
        } catch (e: AudioStartException) {
            audio.close()
            throw CommandException(e.code)
        } catch (e: Exception) {
            audio.close()
            Log.e(TAG, "audio start failed: ${e.javaClass.simpleName}")
            throw CommandException("audio_start_failed")
        }

        var attached = false
        synchronized(lock) {
            if (running && linkUp && owned === current && !current.finished &&
                current.telecomState == Call.STATE_ACTIVE && current.audio == null
            ) {
                current.audio = audio
                current.epoch = epoch
                current.lastEpoch = epoch
                attached = true
            }
        }
        if (!attached) {
            audio.close()
            throw CommandException("not_active")
        }
        replyOk(id, JSONObject()
            .put("call_id", callId)
            .put("epoch", epoch)
            .put("state", "started")
            .put("codec", audio.codec))
    }

    private fun handleFlush(id: String, message: JSONObject) {
        val callId = callIdOf(message)
        val epoch = epochOf(message)
        val audio = synchronized(lock) {
            val current = owned
            if (current == null || current.callId != callId) throw CommandException("not_owned")
            if (current.telecomState != Call.STATE_ACTIVE) throw CommandException("not_active")
            val activeAudio = current.audio ?: throw CommandException("audio_not_started")
            if (epoch < current.epoch) {
                replyOk(id, JSONObject()
                    .put("call_id", callId)
                    .put("epoch", epoch)
                    .put("flushed", false)
                    .put("obsolete", true))
                return
            }
            if (epoch != current.epoch) throw CommandException("epoch_mismatch")
            activeAudio
        }
        if (!audio.flush()) throw CommandException("flush_failed")
        replyOk(id, JSONObject()
            .put("call_id", callId)
            .put("epoch", epoch)
            .put("flushed", true))
    }

    private fun handleCancelActions(id: String, message: JSONObject) {
        val callId = callIdOf(message)
        val generation = generationOf(message)
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
            synchronized(dtmfMonitor) { dtmfMonitor.notifyAll() }
            cancelToneFor(cancelledCall)
        }
        replyOk(id, JSONObject()
            .put("call_id", callId)
            .put("action_generation", generation)
            .put("cancelled", cancelledCall != null))
    }

    private fun handleDtmf(id: String, message: JSONObject) {
        val callId = callIdOf(message)
        val generation = generationOf(message)
        val rawDigits = message.optString("digits", "")
        val digits = rawDigits.uppercase(Locale.ROOT)
        if (digits.isEmpty() || digits.length > 64 ||
            digits.any { it !in "0123456789*#ABCD" }
        ) {
            throw CommandException("digits_invalid")
        }
        val toneMs = message.optInt("tone_ms", 120).coerceIn(40, 1000)
        val gapMs = message.optInt("gap_ms", 80).coerceIn(0, 1000)

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
        gapMs: Int,
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
            replyOk(id, JSONObject()
                .put("call_id", call.callId)
                .put("action_generation", generation)
                .put("digits", digits)
                .put("completed", true))
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
        var audio: PhoneAudio? = null
        var report = false
        synchronized(lock) {
            val current = owned
            if (current != null && current.callId == callId &&
                current.audio != null && current.epoch == epoch
            ) {
                audio = current.audio
                current.audio = null
                current.epoch = -1
                report = true
            }
        }
        if (report) {
            sendText(JSONObject()
                .put("type", "audio_error")
                .put("call_id", callId)
                .put("error", reason))
        }
        audio?.close()
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
                    Log.i(TAG, "ignoring telecom call not owned by the bridge")
                    return
                }
            } else {
                Log.i(TAG, "ignoring telecom call while an owned call is attached")
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
            val registered = try {
                call.registerCallback(orphanCallback, callbackHandler)
                true
            } catch (_: Exception) {
                false
            }
            runCatching { call.disconnect() }
            if (!registered || stateOf(call) == Call.STATE_DISCONNECTED) {
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
            Log.e(TAG, "call callback registration failed: ${e.javaClass.simpleName}")
        }
        reportState(target, mapState(stateOf(call)), disconnectCause(call))
        if (cancelledDial) {
            try {
                call.disconnect()
            } catch (_: Exception) {
                Log.w(TAG, "could not disconnect cancelled dial")
            }
        }
        if (!registered) {
            Log.w(TAG, "owned call has no state callbacks")
        }
    }

    fun onTelecomCallRemoved(call: Call) {
        finishOwned(call, disconnectCause(call))
    }

    private fun onCallStateChanged(call: Call, state: Int) {
        if (!running) return
        var audio: PhoneAudio? = null
        var toneOwner: OwnedCall? = null
        var finish = false
        var cause: JSONObject? = null
        synchronized(lock) {
            val current = owned
            if (current == null || current.call !== call || current.finished) return
            current.telecomState = state
            cause = disconnectCause(call)
            val mapped = mapState(state)
            if (mapped == "ending" || mapped == "ended") {
                current.generation.set(Long.MAX_VALUE)
                audio = current.audio
                current.audio = null
                current.epoch = -1
                toneOwner = current
            }
            reportState(current, mapped, cause)
            finish = mapped == "ended"
        }
        closeAudioAsync(audio)
        toneOwner?.let { cancelToneFor(it) }
        if (finish) finishOwned(call, cause)
    }

    private fun onCallDetailsChanged(call: Call, details: Call.Details) {
        if (!running) return
        var audio: PhoneAudio? = null
        var toneOwner: OwnedCall? = null
        var finish = false
        var cause: JSONObject? = null
        synchronized(lock) {
            val current = owned
            if (current == null || current.call !== call || current.finished) return
            current.telecomState = details.state
            cause = disconnectCause(details)
            val mapped = mapState(details.state)
            if (mapped == "ending" || mapped == "ended") {
                current.generation.set(Long.MAX_VALUE)
                audio = current.audio
                current.audio = null
                current.epoch = -1
                toneOwner = current
            }
            reportState(current, mapped, cause)
            finish = mapped == "ended"
        }
        closeAudioAsync(audio)
        toneOwner?.let { cancelToneFor(it) }
        if (finish) finishOwned(call, cause)
    }

    private fun onCallDestroyed(call: Call) {
        finishOwned(call, disconnectCause(call))
    }

    private fun finishOwned(call: Call, cause: JSONObject?) {
        var audio: PhoneAudio? = null
        var callback: Call.Callback? = null
        var finished: OwnedCall? = null
        synchronized(lock) {
            val current = owned
            if (current == null || current.call !== call || current.finished) return
            current.finished = true
            current.state = "ended"
            audio = current.audio
            current.audio = null
            current.epoch = -1
            callback = current.callback
            current.callback = null
            finished = current
            owned = null
            rememberLocked(current.callId, current.destination, "ended")
            try {
                ledger.finish(current.callId, current.destination, "ended")
            } catch (e: CallLedgerException) {
                Log.e(TAG, "terminal state not durable: ${e.message}")
            }
        }
        val target = finished ?: return
        reportState(target, "ended", cause)
        closeAudioAsync(audio)
        callback?.let { cb -> runCatching { call.unregisterCallback(cb) } }
        cancelToneFor(target)
        restoreMuteForCall(target.callId, call)
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
            (created < current.dialStartedWall - 5_000 ||
                created > current.dialStartedWall + DIAL_CORRELATION_WINDOW_MS)
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

    private fun detailsOf(call: Call): Call.Details? =
        try {
            call.details
        } catch (_: Exception) {
            null
        }

    private fun stateOf(call: Call): Int = detailsOf(call)?.state ?: Call.STATE_NEW

    private fun disconnectCause(call: Call): JSONObject? = disconnectCause(detailsOf(call))

    private fun disconnectCause(details: Call.Details?): JSONObject? {
        val cause = details?.disconnectCause ?: return null
        return JSONObject()
            .put("code", cause.code)
            .put("label", cause.label?.toString() ?: "")
            .put("reason", cause.reason ?: "")
    }

    private fun abortOwnedForLinkLoss() {
        var audio: PhoneAudio? = null
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
                        ttlMs = ORPHAN_CANCEL_TTL_MS,
                    )
                } else {
                    ledger.cancel(current.callId)
                }
            } catch (e: CallLedgerException) {
                Log.e(TAG, "link-loss cancellation not durable: ${e.message}")
            }
            current.cancelled = true
            current.generation.set(Long.MAX_VALUE)
            audio = current.audio
            current.audio = null
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
        closeAudioAsync(audio)
        toneOwner?.let { cancelToneFor(it) }
        call?.let { runCatching { it.disconnect() } }
        if (pendingOnly) {
            Log.w(TAG, "link lost before the telecom call appeared; cancellation retained as an orphan")
        }
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
                    ttlMs = ORPHAN_CANCEL_TTL_MS,
                )
            } catch (e: CallLedgerException) {
                Log.e(TAG, "shutdown cancellation not durable: ${e.message}")
            }
            current.cancelled = true
            current.generation.set(Long.MAX_VALUE)
            current.finished = true
            current.state = "ended"
            audio = current.audio
            current.audio = null
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
                Log.e(TAG, "pending expiry not durable: ${e.message}")
            }
            expired = current
        }
        val target = expired ?: return
        Log.w(TAG, "pending dial expired without a telecom call")
        sendText(JSONObject()
            .put("type", "call_state")
            .put("call_id", target.callId)
            .put("state", "ended")
            .put("disconnect_cause", JSONObject.NULL))
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
                    expiresAt = nowElapsed + remaining,
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
                    ttlMs = ORPHAN_CANCEL_TTL_MS,
                )
            } catch (e: CallLedgerException) {
                Log.e(TAG, "recovered cancellation not durable: ${e.message}")
            }
            val synthetic = OwnedCall(
                callId = pending.callId,
                destination = pending.destination,
                dialStartedWall = pending.createdWall,
                dialStartedElapsed = pending.createdElapsed,
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
                Log.e(TAG, "recovered terminal state not durable: ${e.message}")
            }
            return
        }
        // Ownership survived the restart and is advertised in hello.  No
        // placeCall is issued; the Call is adopted when Telecom surfaces it.
        val restored = OwnedCall(
            callId = pending.callId,
            destination = pending.destination,
            dialStartedWall = pending.createdWall,
            dialStartedElapsed = pending.createdElapsed,
        )
        restored.state = pending.state
        restored.reportedState = pending.state
        owned = restored
        Log.i(TAG, "recovered bridge call intent from durable state")
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
            Log.e(TAG, "microphone restore failed: ${e.javaClass.simpleName}")
        }
    }

    private fun restoreMuteForCall(callId: String, exclude: Call? = null) {
        if (hasUnrelatedLiveCall(exclude)) return
        try {
            PhoneAudio.restoreAbandonedMute(appContext, callId)
        } catch (e: Exception) {
            Log.e(TAG, "microphone restore failed: ${e.javaClass.simpleName}")
        }
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
        synchronized(dtmfMonitor) { dtmfMonitor.notifyAll() }
        val telecomCall = synchronized(lock) { call.call }
        runCatching { telecomCall?.stopDtmfTone() }
    }

    private fun reportState(call: OwnedCall, state: String, cause: JSONObject?) {
        val causeKey = cause?.let {
            val code = it.optInt("code")
            val label = it.optString("label")
            val reason = it.optString("reason")
            "$code|$label|$reason"
        }
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
                Log.e(TAG, "call state not durable: ${e.message}")
            }
        }
        sendText(JSONObject()
            .put("type", "call_state")
            .put("call_id", call.callId)
            .put("state", state)
            .put("disconnect_cause", cause ?: JSONObject.NULL))
    }

    private fun replyOk(id: String, data: JSONObject) {
        sendText(JSONObject()
            .put("type", "response")
            .put("id", id)
            .put("ok", true)
            .put("data", data))
    }

    private fun replyError(id: String, error: String) {
        sendText(JSONObject()
            .put("type", "response")
            .put("id", id)
            .put("ok", false)
            .put("error", error))
    }

    private fun callIdOf(message: JSONObject): String {
        val value = message.optString("call_id", "")
        if (value.isEmpty()) throw CommandException("call_id_required")
        return value
    }

    private fun epochOf(message: JSONObject): Long {
        if (!message.has("epoch")) throw CommandException("epoch_required")
        val value = message.optLong("epoch", -1)
        if (value < 0 || value > 0xFFFFFFFFL) throw CommandException("epoch_invalid")
        return value
    }

    private fun generationOf(message: JSONObject): Long {
        if (!message.has("action_generation")) throw CommandException("action_generation_required")
        val value = message.optLong("action_generation", -1)
        if (value < 0) throw CommandException("action_generation_invalid")
        return value
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
                expiresAt = now + ORPHAN_CANCEL_TTL_MS,
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
                Log.e(TAG, "orphan tombstone not cleared: ${e.message}")
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
                (created <= 0 ||
                    (created >= orphan.dialStartedWall - 5_000 &&
                        created <= orphan.dialStartedWall + DIAL_CORRELATION_WINDOW_MS))
            ) {
                orphanCancels.removeAt(index)
                try {
                    ledger.consumeOrphan(orphan.callId, orphan.dialStartedWall)
                } catch (e: CallLedgerException) {
                    Log.e(TAG, "orphan tombstone not cleared: ${e.message}")
                }
                return orphan
            }
            index++
        }
        return null
    }

    private fun clearFailedDial(callId: String) {
        synchronized(lock) {
            val current = owned
            if (current != null && current.callId == callId &&
                current.call == null && !current.finished
            ) {
                owned = null
                rememberLocked(callId, current.destination, "failed")
                try {
                    ledger.finish(callId, current.destination, "failed")
                } catch (e: CallLedgerException) {
                    Log.e(TAG, "dial failure not durable: ${e.message}")
                }
            }
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

    private class OwnedCall(
        val callId: String,
        val destination: String,
        val dialStartedWall: Long = System.currentTimeMillis(),
        val dialStartedElapsed: Long = SystemClock.elapsedRealtime(),
    ) {
        val generation = AtomicLong(0)
        var call: Call? = null
        var callback: Call.Callback? = null
        var state: String = "dialing"
        var telecomState: Int = -1
        var audio: PhoneAudio? = null
        var epoch: Long = -1
        var lastEpoch: Long = -1
        var cancelled: Boolean = false
        var finished: Boolean = false
        var reportedState: String? = null
        var reportedCauseKey: String? = null
    }

    private data class RecentCall(val destination: String, val state: String)

    private data class OrphanCancel(
        val callId: String,
        val destination: String,
        val dialStartedWall: Long,
        val expiresAt: Long,
    )

    private class CommandException(val code: String) : Exception(code)
}
