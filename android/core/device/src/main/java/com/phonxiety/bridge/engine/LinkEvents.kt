package com.phonxiety.bridge.engine

import android.content.Context
import com.phonxiety.bridge.AppInfoRefresh
import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.CapabilityWatch
import com.phonxiety.bridge.EngineStateStore
import com.phonxiety.bridge.PhoneCapabilities
import com.phonxiety.bridge.RouteSnapshot
import com.phonxiety.bridge.helloFrame
import com.phonxiety.bridge.model.ControlEnvelope
import com.phonxiety.bridge.model.DeviceActiveCall
import com.phonxiety.bridge.model.DeviceOutbound
import com.phonxiety.bridge.model.DeviceProtocol
import com.phonxiety.bridge.model.LinkDownReason
import com.phonxiety.bridge.model.LinkError
import com.phonxiety.bridge.net.ApiExchange
import com.phonxiety.bridge.net.IrohSupport
import com.phonxiety.bridge.net.LinkSupervisor
import com.phonxiety.bridge.os.Permissions
import java.util.concurrent.atomic.AtomicLong
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * The engine's side of one link: the hello, the inbound frame router, the route
 * publisher and the link-down transition.
 *
 * `hello` is the only frame the transport accepts before its gate opens; every
 * other control frame goes through the supervisor's own gate as the second
 * line of defence.  The supervisor is read through [links] because its
 * constructor takes these callbacks.
 */
internal class LinkEvents(
    private val context: Context,
    private val session: CallSession,
    private val store: EngineStateStore,
    private val publisher: StateReportPublisher,
    private val wire: (DeviceOutbound) -> Boolean,
    private val links: () -> LinkSupervisor,
    private val settings: SettingsSync,
    private val hello: HelloState,
    private val appInfo: AppInfoRefresh,
    private val requests: ApiExchange,
    private val routeText: (String) -> Unit,
    private val capabilityWatch: CapabilityWatch,
    private val linkGeneration: AtomicLong,
    private val deviceBootId: String,
    private val config: BridgeConfig,
    private val audioSettings: AudioSettings,
    private val appTokenPresent: () -> Boolean,
    private val unrelatedCall: () -> Boolean,
    private val onCommand: (String, String) -> Unit,
    private val onAppAccess: (String, Long) -> Unit,
    private val onLinkDown: () -> Unit
) : LinkSupervisor.Callbacks {
    companion object {
        private const val TAG = "Bridge:engine"
        private const val MAX_TEXT_CHARS = 256 * 1024
        private const val MAX_BINARY_BYTES = 64 * 1024
        private val LINK_TEST_MAGIC = "PXLT".encodeUtf8()
    }

    override fun onLinkOpened(): Boolean {
        val chosen = links().selectedCandidate()
        if (chosen != null) hello.kind = chosen.kind
        store.linkUp(
            kind = hello.kind,
            route = links().route()?.let { RouteSnapshot(it.relay, it.rttMs) },
            sinceEpochMs = System.currentTimeMillis(),
            reconnects = links().reconnects(),
            address = chosen?.host ?: when (config) {
                is BridgeConfig.Address -> config.host
                is BridgeConfig.Iroh -> IrohSupport.endpointId(config.ticket)
            }
        )
        linkGeneration.incrementAndGet()
        appInfo.linkOpened()
        requests.transportOpened()
        // Nothing else may reach this link before its hello: the daemon
        // reads the first control frame as the handshake.
        val sent = sendHello()
        settings.applyWifiLock()
        return sent
    }

    override fun onLinkText(text: String) {
        onText(text)
    }

    override fun onLinkBinary(bytes: ByteString) {
        onBinary(bytes)
    }

    override fun onLinkDown(reason: LinkDownReason, detail: String?) {
        hello.lastRouteKey = null
        hello.delivered = false
        linkGeneration.incrementAndGet()
        appInfo.linkDown()
        requests.linkDown()
        capabilityWatch.helloGone()
        settings.applyWifiLock()
        store.linkDown(
            LinkError(
                code = reason.text(detail),
                message = linkMessage(reason, detail),
                atEpochMs = System.currentTimeMillis(),
                reason = reason,
                detail = detail
            )
        )
        onLinkDown()
    }

    private fun onText(text: String) {
        if (text.length > MAX_TEXT_CHARS) {
            BridgeLog.w(TAG, "ignoring oversized control message")
            return
        }
        routeText(text)
    }

    /** Everything the router dispatches after it has completed a pending request for the frame. */
    internal fun dispatch(type: String, text: String) {
        when (type) {
            "command" -> {
                val id = ControlEnvelope.id(text)
                if (id.isNullOrEmpty()) return
                onCommand(id, text)
            }

            "app_access" -> {
                val token = ControlEnvelope.appAccessToken(text) ?: return
                // The delivery belongs to the connection it arrived on: a
                // reconnect while the token is persisted must not let the old
                // connection admit the new connection's info request.
                onAppAccess(token, linkGeneration.get())
            }

            else -> Unit
        }
    }

    private fun onBinary(bytes: ByteString) {
        if (bytes.size < 16 || bytes.size > MAX_BINARY_BYTES) return
        val audio = synchronized(session.lock) { session.owned?.attached?.audio }
        if (audio != null) {
            audio.offerFrame(bytes)
        } else if (bytes.startsWith(LINK_TEST_MAGIC)) {
            // The daemon times an idle link under call load: return its frame.
            links().sendFrame(bytes.toByteArray(), bytes.size)
        }
    }

    private fun sendHello(): Boolean {
        settings.loadBeforeHello()
        val capabilities = currentCapabilities()
        val busy = unrelatedCall()
        val active: DeviceActiveCall? = synchronized(session.lock) {
            val current = session.owned
            if (current == null || current.finished) null else DeviceActiveCall(current.callId, current.state)
        }
        val helloMessage = helloFrame(
            capabilities = capabilities,
            device = android.os.Build.DEVICE ?: "unknown",
            bootId = deviceBootId,
            busy = busy,
            activeCall = active,
            preferredCodec = audioSettings.preferredCodec,
            maxBitrate = audioSettings.maxBitrate,
            adbTunnel = config is BridgeConfig.Iroh
        )
        // Through the supervisor's hello gate: the first control frame of the
        // link, and the only one allowed before it is queued.
        val sent = links().sendHello(DeviceProtocol.encode(helloMessage))
        hello.delivered = sent
        if (sent) {
            // A terminal report the previous link refused goes out first, on this thread,
            // before the hello releases the app requests that were waiting for a usable link.
            publisher.replayRetained()
            requests.helloQueued()
            capabilityWatch.helloSent(capabilities)
            settings.applyWifiLock()
        }
        store.capabilities(
            store.state.value.capabilities.copy(
                frameworkAudio = capabilities.frameworkAudio,
                callControl = capabilities.callControl,
                appApi = if (appTokenPresent()) store.state.value.capabilities.appApi else null
            )
        )
        return sent
    }

    /**
     * The call permissions this hello must announce.  `checkSelfPermission`
     * only: no I/O, cheap enough for the 10 s capability watch.
     */
    fun currentCapabilities(): PhoneCapabilities = PhoneCapabilities(
        callControl = Permissions.granted(context, Permissions.CALL_PHONE) &&
            Permissions.granted(context, Permissions.CONTROL_INCALL_EXPERIENCE),
        frameworkAudio = Permissions.granted(context, Permissions.CAPTURE_AUDIO_OUTPUT) &&
            Permissions.granted(context, Permissions.MODIFY_PHONE_STATE) &&
            Permissions.granted(context, Permissions.RECORD_AUDIO)
    )

    /**
     * One route publish: a frame refused by the hello gate must not be
     * remembered as sent, so the one-second tick retries it once the link can
     * carry it.
     */
    fun publishRoute() {
        if (!hello.delivered) return
        val route = links().route() ?: return
        val key = "${route.relay}|${route.rttMs}"
        if (key == hello.lastRouteKey) return
        val sent = wire(
            DeviceOutbound.LinkRoute(
                path = if (route.relay) "relay" else "direct",
                rttMs = route.rttMs
            )
        )
        if (!sent) return
        hello.lastRouteKey = key
        store.route(RouteSnapshot(route.relay, route.rttMs))
    }
}

/**
 * The one sentence a user reads for a transport link-down.  Exhaustive over
 * [LinkDownReason] with no `else`, so a new reason cannot ship without its
 * words.  [detail] is the native close text behind [LinkDownReason.OTHER] and
 * is what the old free-string fallback showed.
 */
internal fun linkMessage(reason: LinkDownReason, detail: String?): String = when (reason) {
    LinkDownReason.HOST_TIMEOUT -> "the daemon stopped answering"
    LinkDownReason.CONNECT_FAILED -> "cannot reach the daemon"
    LinkDownReason.CONNECT_TIMEOUT -> "the connection attempt timed out"
    LinkDownReason.HELLO_FAILED -> "the link could not complete its handshake"
    LinkDownReason.CLOSED, LinkDownReason.CLOSING -> "the daemon closed the link"
    LinkDownReason.PROTOCOL -> "the daemon spoke an unknown protocol"
    LinkDownReason.WRITE_FAILED -> "the link stopped accepting data"
    LinkDownReason.RELAY_ONLY -> "no direct path is available, only a relay"
    LinkDownReason.CONTROL_BACKLOG -> "the link stopped taking messages"
    LinkDownReason.NETWORK_CHANGED -> "the network changed"
    LinkDownReason.NETWORK_LOST -> "the network is gone"
    LinkDownReason.CONNECTION_MODE_CHANGED -> "the connection mode changed"
    LinkDownReason.CANDIDATE_BETTER -> "a better path was measured"
    LinkDownReason.CAPABILITIES_CHANGED -> "the phone's call permissions changed"
    LinkDownReason.INVALID_URL -> "the link address is not valid"
    LinkDownReason.FAILURE -> "the link failed"
    LinkDownReason.CREATION_FAILED -> "the link could not be created"
    LinkDownReason.NO_CANDIDATE -> "no connection path is known"
    LinkDownReason.IROH_UNAVAILABLE -> "this connection route is not available"
    LinkDownReason.OTHER -> detail?.takeIf { it.isNotBlank() } ?: "the link closed for an unknown reason"
}
