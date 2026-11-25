package com.phonxiety.bridge

import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
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

/**
 * The address (`wss://`) link.  One OkHttp client lives as long as the factory,
 * so reconnects keep the connection pool and the dispatcher; each attempt is a
 * fresh [WebSocket].
 */
internal class WebSocketLinkFactory(
    private val config: BridgeConfig.Address,
) : DeviceLinkFactory {
    private val client = buildClient(config.certSha256)

    override fun create(): DeviceLink = WebSocketLink(client, config)

    override fun shutdown() {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    private companion object {
        /**
         * OkHttp 5.5.1 does not enable TCP_NODELAY itself: `ConnectPlan`
         * sets only `soTimeout` before `connect()`.  The listener below turns
         * it on as soon as the connection is acquired, before the WebSocket
         * request is written, so PCM frames are not coalesced by Nagle.
         */
        private val tcpNoDelay = object : EventListener() {
            override fun connectionAcquired(call: HttpCall, connection: Connection) {
                runCatching { connection.socket().tcpNoDelay = true }
            }
        }

        fun buildClient(certSha256: String?): OkHttpClient {
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
    }
}

/**
 * Accepts the TLS peer only when SHA-256 of its DER leaf certificate equals the
 * pinned fingerprint.  Chain and hostname validation are skipped deliberately:
 * the self-signed daemon certificate is the identity, per the device link
 * contract.
 */
private class PinnedTrustManager(pinHex: String) : X509TrustManager {
    private val expected: ByteArray = hexToBytes(pinHex)
    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(this@PinnedTrustManager), null)
    }

    val socketFactory: SSLSocketFactory get() = sslContext.socketFactory

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client_certificate_unexpected")
    }

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

private class WebSocketLink(
    private val client: OkHttpClient,
    private val config: BridgeConfig.Address,
) : DeviceLink {
    private val downOnce = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    @Volatile private var socket: WebSocket? = null

    override val backlogBytes: Long
        get() = socket?.queueSize() ?: 0L

    override fun route(): DeviceLink.Route? = null

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
                listener.onLinkText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
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
            client.newWebSocket(request, adapter)
        } catch (_: Exception) {
            down(listener, "creation_failed")
            return
        }
        socket = created
        if (cancelled.get()) created.cancel()
    }

    override fun sendText(message: String): Boolean {
        val webSocket = socket ?: return false
        return try {
            webSocket.send(message)
        } catch (_: Exception) {
            false
        }
    }

    override fun sendBinary(frame: ByteArray, length: Int) {
        val webSocket = socket ?: return
        val size = max(0, minOf(length, frame.size))
        if (size == 0) return
        try {
            // The caller checks the backlog; a refusal here means the socket is
            // already gone and the listener reports it.
            webSocket.send(frame.toByteString(0, size))
        } catch (_: Exception) {
            // Link loss is handled by the WebSocket listener.
        }
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
