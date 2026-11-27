package com.phonxiety.bridge.net

import android.annotation.SuppressLint
import com.phonxiety.bridge.BridgeConfig
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.max
import okhttp3.Call as HttpCall
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * The address (`wss://`) link.  One OkHttp client lives as long as the factory,
 * so reconnects keep the connection pool and the dispatcher; each attempt is a
 * fresh [WebSocket].
 */
internal class WebSocketLinkFactory(
    private val config: BridgeConfig.Address,
    private val tap: () -> FrameTapSink = { FrameTapSink.None }
) : DeviceLinkFactory {
    private val client = pinnedOkHttpClient(config.certSha256)

    override fun create(): DeviceLink = WebSocketLink(client, config, tap())

    override fun shutdown() {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }
}

/**
 * OkHttp 5.5.1 does not enable TCP_NODELAY itself: `ConnectPlan` sets only
 * `soTimeout` before `connect()`.  The listener turns it on as soon as the
 * connection is acquired, before the WebSocket request is written, so PCM
 * frames are not coalesced by Nagle.
 */
private val tcpNoDelay = object : EventListener() {
    override fun connectionAcquired(call: HttpCall, connection: Connection) {
        runCatching { connection.socket().tcpNoDelay = true }
    }
}

/** The shared WebSocket client; [certSha256] pins the daemon certificate when it is known. */
internal fun pinnedOkHttpClient(certSha256: String?): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .eventListener(tcpNoDelay)
    if (certSha256 != null) {
        val pinned = PinnedTrustManager(certSha256)
        builder
            .sslSocketFactory(pinned.socketFactory, pinned)
            .hostnameVerifier { _, _ -> true }
    }
    return builder.build()
}

/**
 * Probes one address candidate the way the link contract measures a path:
 * three consecutive TLS handshakes with the pinned certificate and the bearer
 * token in the upgrade request, each within
 * [CandidateScore.PROBE_BUDGET_MS].  The daemon authorizes the upgrade before
 * any frame arrives, so an open socket proves the pin and the token; the probe
 * closes at once and never sends a device `hello`, so it cannot replace the
 * live link.
 */
internal class WebSocketCandidateProbe(
    private val token: String,
    private val budgetMs: Long = CandidateScore.PROBE_BUDGET_MS
) : CandidateProbe {
    private val clients = java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>()

    override fun probe(candidate: Candidate): ProbeReport {
        val pin = candidate.certSha256?.takeIf { it.isNotBlank() } ?: return ProbeReport(candidate.id, 0, null)
        val url = candidate.url ?: return ProbeReport(candidate.id, 0, null)
        val client = clients.getOrPut(pin) { pinnedOkHttpClient(pin) }
        val samples = ArrayList<Long>(CandidateScore.MIN_PASSES)
        repeat(CandidateScore.MIN_PASSES) {
            val openMs = openOnce(client, url) ?: return ProbeReport(candidate.id, 0, null)
            samples += openMs
        }
        samples.sort()
        return ProbeReport(candidate.id, samples.size, samples[samples.size / 2])
    }

    private fun openOnce(client: OkHttpClient, url: String): Long? {
        val request = try {
            Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        } catch (_: Exception) {
            return null
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val started = System.nanoTime()
        var openMs = 0L
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    openMs = (System.nanoTime() - started) / 1_000_000
                    runCatching { webSocket.close(1000, "probe") }
                    latch.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    runCatching { webSocket.cancel() }
                    latch.countDown()
                }
            }
        )
        if (!latch.await(budgetMs, TimeUnit.MILLISECONDS)) {
            runCatching { socket.cancel() }
            return null
        }
        return openMs.takeIf { it in 1..budgetMs }
    }
}

/**
 * Accepts the TLS peer only when SHA-256 of its DER leaf certificate equals the
 * pinned fingerprint.  Chain and hostname validation are skipped deliberately:
 * the self-signed daemon certificate is the identity, per the device link
 * contract.
 */
// The daemon's self-signed certificate is the identity: the SHA-256 pin is the
// only check, so chain and hostname validation are skipped by contract.
@SuppressLint("CustomX509TrustManager")
private class PinnedTrustManager(pinHex: String) : X509TrustManager {
    private val expected: ByteArray = hexToBytes(pinHex)
    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(this@PinnedTrustManager), null)
    }

    val socketFactory: SSLSocketFactory get() = sslContext.socketFactory

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit = throw CertificateException("client_certificate_unexpected")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("peer_certificate_missing")
        val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        if (!MessageDigest.isEqual(digest, expected)) {
            throw CertificateException("peer_certificate_pin_mismatch")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private companion object {
        fun hexToBytes(hex: String): ByteArray {
            require(hex.length == 64) { "pin_length" }
            return ByteArray(hex.length / 2) { index ->
                val high = Character.digit(hex[index * 2], 16)
                val low = Character.digit(hex[index * 2 + 1], 16)
                require(high >= 0 && low >= 0) { "pin_hex" }
                ((high shl 4) or low).toByte()
            }
        }
    }
}

internal class WebSocketLink(
    private val client: OkHttpClient,
    private val config: BridgeConfig.Address,
    private val tap: FrameTapSink,
    private val connect: (Request, WebSocketListener) -> WebSocket = { request, listener ->
        client.newWebSocket(request, listener)
    }
) : DeviceLink {
    private val downOnce = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    @Volatile private var socket: WebSocket? = null

    /**
     * Frames go straight to OkHttp's queue.  On this socket the audio freeze
     * overrides a queue of our own: nothing stands between capture and `send`,
     * and the backlog is the socket's `queueSize` alone.  A refused send never
     * reports down from here: OkHttp's listener does, on its own thread.
     */
    private val sender = SocketSender(socket = { socket }, tap = tap)

    override val backlogBytes: Long get() = sender.backlogBytes

    // One pinned TCP stream to a known address: there is no relay hop to report, and no
    // transport-level round trip until the daemon's heartbeat answers.
    override fun route(): DeviceLink.Route = DeviceLink.Route(relay = false, rttMs = null)

    override fun start(listener: DeviceLink.Listener) {
        val request = try {
            Request.Builder()
                .url(config.url)
                .header("Authorization", "Bearer ${config.token}")
                .build()
        } catch (_: Exception) {
            down(listener, "invalid_url")
            return
        }
        val adapter = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socket == null) socket = webSocket
                if (cancelled.get()) {
                    runCatching { webSocket.close(1000, "cancelled") }
                    return
                }
                listener.onLinkOpened()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (tap.enabled) tap.frame(FrameDirection.IN, FrameStream.SOCKET, Framing.KIND_TEXT, utf8Length(text))
                listener.onLinkText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (tap.enabled) tap.frame(FrameDirection.IN, FrameStream.SOCKET, Framing.KIND_BINARY, bytes.size)
                listener.onLinkBinary(bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                runCatching { webSocket.close(1000, null) }
                down(listener, "closing")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                down(listener, "closed")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                down(listener, "failure")
            }
        }
        val created = try {
            connect(request, adapter)
        } catch (_: Exception) {
            down(listener, "creation_failed")
            return
        }
        socket = created
        if (cancelled.get()) created.cancel()
    }

    override fun sendText(message: String): Boolean = sender.sendText(message)

    override fun sendBinary(frame: ByteArray, length: Int) {
        offerBinary(frame, length)
    }

    override fun offerBinary(frame: ByteArray, length: Int): Boolean {
        val size = max(0, minOf(length, frame.size))
        if (size == 0) return false
        return sender.sendBinary(frame.toByteString(0, size))
    }

    override fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        downOnce.set(true)
        socket?.cancel()
        socket = null
    }

    private fun down(listener: DeviceLink.Listener, reason: String) {
        if (!downOnce.compareAndSet(false, true)) return
        listener.onLinkDown(reason)
    }
}
