package com.phonxiety.bridge.net

import android.util.Log
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.RecvStream
import computer.iroh.SendStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

private const val TAG = "PhonxietyBridge"

/**
 * The device side of the iroh adb tunnel.
 *
 * After auth the daemon may open extra bidirectional streams on the same
 * connection.  The first thing on such a stream is one [Framing] text frame
 * naming the service it wants; this class accepts those streams for the life of
 * the link, checks that first frame, and for `adb` pumps raw bytes between the
 * stream and adbd on loopback TCP 5555.  Unknown services and malformed frames
 * are reset and otherwise ignored: a bad tunnel never takes the link down.
 *
 * Root work and the loopback socket sit behind [AdbHost], so the protocol part
 * stays unit-testable without a device.
 */
internal class AdbTunnel(private val host: AdbHost = AndroidAdbHost()) {

    companion object {
        const val SERVICE = "adb"
        const val PORT = 5555

        /** Error code sent when a stream is not a tunnel this phone serves. */
        private const val REJECT_CODE = 0UL

        /** Raw pump buffer; also the cap on the tunnel request frame. */
        private const val CHUNK_BYTES = 16 * 1024
        private const val MAX_REQUEST_BYTES = 1024

        /** Loopback adbd is dialled a few times: a restarted adbd accepts late. */
        private const val CONNECT_ATTEMPTS = 10
        private const val CONNECT_RETRY_MS = 200L

        /**
         * Decodes the first frame of a tunnel stream: the [Framing] header must
         * be a complete text frame matching [payload], and [payload] must be a
         * `{"type":"tunnel","service":...}` request.
         */
        fun acceptsFirstFrame(header: ByteArray, payload: ByteArray): Boolean {
            val parsed = Framing.parse(header) ?: return false
            if (parsed.kind != Framing.KIND_TEXT || parsed.length != payload.size) return false
            return isAdbRequest(payload)
        }

        /** Whether [payload] is exactly the adb tunnel request. */
        fun isAdbRequest(payload: ByteArray): Boolean {
            if (payload.isEmpty() || payload.size > MAX_REQUEST_BYTES) return false
            val request = parseFlatObject(payload.toString(Charsets.UTF_8)) ?: return false
            return request["type"] == "tunnel" && request["service"] == SERVICE
        }

        /**
         * Decodes a flat JSON object of string values, the only shape a tunnel
         * request has (`{"type":"tunnel","service":"adb"}`).  Nesting, arrays,
         * numbers, escapes and duplicate keys return null, so a frame with
         * anything but the expected fields is not mistaken for a request.
         */
        private fun parseFlatObject(text: String): Map<String, String>? {
            var at = 0

            fun skipSpace() {
                while (at < text.length && text[at].isWhitespace()) at++
            }

            fun string(): String? {
                if (at >= text.length || text[at] != '"') return null
                at++
                val start = at
                while (at < text.length && text[at] != '"') {
                    if (text[at] == '\\') return null
                    at++
                }
                if (at >= text.length) return null
                val value = text.substring(start, at)
                at++ // closing quote
                return value
            }

            val fields = LinkedHashMap<String, String>()
            skipSpace()
            if (at >= text.length || text[at] != '{') return null
            at++
            skipSpace()
            if (at < text.length && text[at] == '}') {
                at++
                skipSpace()
                return if (at == text.length) fields else null
            }
            while (true) {
                skipSpace()
                val key = string() ?: return null
                skipSpace()
                if (at >= text.length || text[at] != ':') return null
                at++
                skipSpace()
                val value = string() ?: return null
                if (fields.put(key, value) != null) return null
                skipSpace()
                if (at >= text.length) return null
                when (text[at]) {
                    ',' -> at++
                    else -> {
                        if (text[at] != '}') return null
                        at++
                        skipSpace()
                        return if (at == text.length) fields else null
                    }
                }
            }
        }
    }

    /**
     * Accepts tunnel streams until the connection ends.  Each accepted stream
     * is served in its own child of [scope], which the link cancels on failure.
     */
    suspend fun acceptLoop(connection: Connection, scope: CoroutineScope) {
        while (true) {
            val stream = try {
                connection.acceptBi()
            } catch (_: CancellationException) {
                return
            } catch (_: Throwable) {
                // The connection is gone; the link's own watcher reports why.
                return
            }
            scope.launch { serve(stream) }
        }
    }

    private suspend fun serve(stream: BiStream) {
        val send = stream.send()
        val recv = stream.recv()
        try {
            val header = try {
                recv.readExact(Framing.HEADER_BYTES.toUInt())
            } catch (_: Throwable) {
                null
            }
            val payload = header?.let { bytes ->
                val parsed = Framing.parse(bytes)
                if (parsed == null || parsed.kind != Framing.KIND_TEXT) {
                    null
                } else {
                    try {
                        recv.readExact(parsed.length.toUInt())
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            if (header == null || payload == null || !acceptsFirstFrame(header, payload)) {
                reject(stream, send, recv)
                return
            }
            if (!AdbReady.ensure(host)) {
                Log.w(TAG, "adb_tunnel_unavailable")
                reject(stream, send, recv)
                return
            }
            val socket = openSocket()
            if (socket == null) {
                Log.w(TAG, "adb_tunnel_unavailable")
                reject(stream, send, recv)
                return
            }
            pump(send, recv, socket)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w(TAG, "adb tunnel failed: ${t.javaClass.simpleName}")
        } finally {
            runCatching { send.close() }
            runCatching { recv.close() }
            runCatching { stream.close() }
        }
    }

    /**
     * Opens loopback adbd, retrying briefly: a just-restarted adbd needs a
     * moment before it accepts connections.
     */
    private suspend fun openSocket(): Socket? {
        repeat(CONNECT_ATTEMPTS) { attempt ->
            withContext(Dispatchers.IO) { host.connect(PORT) }?.let { return it }
            if (attempt < CONNECT_ATTEMPTS - 1) delay(CONNECT_RETRY_MS)
        }
        return null
    }

    /**
     * Tells the daemon this stream is not served: reset both halves, then
     * release them.  Never reported as a link failure.
     */
    private suspend fun reject(stream: BiStream, send: SendStream, recv: RecvStream) {
        runCatching { send.reset(REJECT_CODE) }
        runCatching { recv.stop(REJECT_CODE) }
        runCatching { send.close() }
        runCatching { recv.close() }
        runCatching { stream.close() }
    }

    /**
     * Copies raw bytes both ways until either side ends.  A stream end closes
     * the socket; a socket EOF finishes the stream.  The first direction to
     * finish tears the other one down.
     */
    private suspend fun pump(send: SendStream, recv: RecvStream, socket: Socket) {
        try {
            coroutineScope {
                // Closing the socket is the only way to interrupt a blocked
                // read, so a cancelled link must close it from another job.
                val guard = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        runCatching { socket.close() }
                    }
                }
                val fromStream = launch(Dispatchers.IO) {
                    try {
                        val out = socket.getOutputStream()
                        while (isActive) {
                            val chunk = recv.read(CHUNK_BYTES.toUInt())
                            if (chunk.isEmpty()) break
                            out.write(chunk)
                            out.flush()
                        }
                    } catch (_: CancellationException) {
                        // Closed by the other direction.
                    } catch (_: Throwable) {
                        // Peer or socket is gone; the other direction ends below.
                    } finally {
                        runCatching { socket.close() }
                    }
                }
                try {
                    val input = socket.getInputStream()
                    val buffer = ByteArray(CHUNK_BYTES)
                    while (isActive) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            runCatching { send.finish() }
                            break
                        }
                        if (read > 0) {
                            send.writeAll(if (read == buffer.size) buffer else buffer.copyOf(read))
                        }
                    }
                } finally {
                    runCatching { recv.stop(REJECT_CODE) }
                    guard.cancel()
                    fromStream.cancel()
                    runCatching { socket.close() }
                }
            }
        } finally {
            runCatching { send.finish() }
        }
    }
}

/**
 * Root/system side of the tunnel: behind an interface so the command sequence
 * and the frame checks run on a plain JVM.
 */
internal interface AdbHost {
    /** One finished root command. */
    data class Shell(val exit: Int, val output: String)

    /** Runs [command] through `su -c`; null when root itself is unavailable. */
    fun su(command: String): Shell?

    /** Opens the device-local adb port; null when it is closed. */
    fun connect(port: Int): Socket?
}

/**
 * Makes adbd listen on loopback TCP 5555, idempotently.
 *
 * Firewall first (both iptables and ip6tables, each rule inserted only when the
 * `-C` check says it is missing): accept 5555 on `lo`, drop it everywhere else.
 * Only when `service.adb.tcp.port` is not already 5555: set the property and
 * restart adbd.  Every mutating command is logged; nothing here carries a
 * secret.
 */
internal class AdbTunnelCommands(private val host: AdbHost) {
    companion object {
        private const val PORT = AdbTunnel.PORT
        private const val ACCEPT_LO = "-i lo -p tcp --dport $PORT -j ACCEPT"
        private const val DROP_OTHER = "! -i lo -p tcp --dport $PORT -j DROP"
        private val TABLES = listOf("iptables", "ip6tables")
    }

    /** True when adbd is reachable on loopback 5555 after this call. */
    fun enable(): Boolean {
        for (table in TABLES) {
            if (!ensureRule(table, ACCEPT_LO)) return false
            if (!ensureRule(table, DROP_OTHER)) return false
        }
        val current = host.su("getprop service.adb.tcp.port") ?: return false
        if (current.exit != 0) return false
        if (current.output.trim() == PORT.toString()) return true
        if (host.su("setprop service.adb.tcp.port $PORT")?.exit != 0) return false
        Log.i(TAG, "adb tunnel: setprop service.adb.tcp.port $PORT")
        if (host.su("stop adbd; start adbd")?.exit != 0) return false
        Log.i(TAG, "adb tunnel: restarted adbd on port $PORT")
        return true
    }

    private fun ensureRule(table: String, rule: String): Boolean {
        val check = host.su("$table -w -C INPUT $rule") ?: return false
        if (check.exit == 0) return true
        val add = host.su("$table -w -I INPUT 1 $rule") ?: return false
        if (add.exit != 0) return false
        Log.i(TAG, "adb tunnel: $table -I INPUT 1 $rule")
        return true
    }
}

/** The real host: `su -c` for root, a loopback socket for adbd. */
internal class AndroidAdbHost : AdbHost {
    companion object {
        private const val ROOT_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val LOOPBACK = "127.0.0.1"
    }

    override fun su(command: String): AdbHost.Shell? {
        var process: Process? = null
        return try {
            val started = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = started
            if (!started.waitFor(ROOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                runCatching { started.destroy() }
                return null
            }
            val output = started.inputStream.bufferedReader().use { it.readText() }
            AdbHost.Shell(started.exitValue(), output)
        } catch (_: Throwable) {
            runCatching { process?.destroy() }
            null
        }
    }

    override fun connect(port: Int): Socket? {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(InetAddress.getByName(LOOPBACK), port), CONNECT_TIMEOUT_MS)
            socket
        } catch (_: Throwable) {
            runCatching { socket.close() }
            null
        }
    }
}

/**
 * Process-wide "adbd is already listening" flag: the root work runs once per
 * app process, serialized across tunnels, and a failure is never cached.
 */
private object AdbReady {
    private val mutex = Mutex()

    @Volatile private var ready = false

    suspend fun ensure(host: AdbHost): Boolean {
        if (ready) return true
        return mutex.withLock {
            if (ready) return@withLock true
            // An installer (the Mac over adb, or root granted to this app) may already have
            // opened adbd on loopback; then no root is needed here at all.
            val open = withContext(Dispatchers.IO) { host.connect(AdbTunnel.PORT)?.use { true } ?: false }
            val enabled = open || withContext(Dispatchers.IO) { AdbTunnelCommands(host).enable() }
            if (enabled) {
                Log.i(TAG, "adb_tunnel_enabled")
                ready = true
            }
            enabled
        }
    }
}
