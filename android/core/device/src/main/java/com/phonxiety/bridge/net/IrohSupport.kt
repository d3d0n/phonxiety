package com.phonxiety.bridge.net

import android.content.Context
import com.phonxiety.bridge.AppStorage
import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.bridgeScope
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.EndpointTicket
import computer.iroh.IrohAndroid
import computer.iroh.RelayMode
import computer.iroh.SecretKey
import computer.iroh.presetN0
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide scope for iroh calls that must never block their caller:
 * endpoint shutdown, endpoint recycle and connection close.  Callers include
 * the main thread when the service stops, so none of this may run there.
 */
internal val irohIo = bridgeScope("phonxiety-iroh-io", Dispatchers.IO)

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
    private const val TAG = "Bridge:iroh"
    private const val SECRET_KEY_FILE = "iroh-secret.key"
    private const val SECRET_KEY_BYTES = 32

    /** ALPN of the device protocol, per the iroh link contract. */
    val ALPN: ByteArray = "phonxiety/device/2".toByteArray(Charsets.UTF_8)

    private val contextInstalled = AtomicBoolean(false)

    /** The one cached endpoint: its mutex serializes binds, its state guards the handle. */
    private val endpoints = IrohEndpointCache<IrohEndpoint> { bound, name ->
        stopAsync(bound.endpoint, name)
    }

    /** Relays the phone's endpoint uses; [RelayModeSetting.DEFAULT] leaves the preset in charge. */
    @Volatile private var relayMode: RelayModeSetting = RelayModeSetting.DEFAULT

    @Volatile private var relayUrls: List<String> = emptyList()

    /**
     * The endpoint and the generation it was bound under.  A link remembers
     * its generation so it can recycle only the endpoint it dialled through,
     * never the one a newer link bound after a replacement.
     *
     * [fresh] is the attempt's view of a pending fresh-bind request: while the
     * request is pending and this attempt is still live, the cached endpoint is
     * detached and the attempt binds a new one, both decided inside the bind
     * mutex.
     */
    internal suspend fun binding(context: Context, fresh: FreshBind? = null): IrohEndpoint {
        val appContext = context.applicationContext
        return if (fresh == null) {
            endpoints.acquire { generation -> bind(appContext, generation) }
        } else {
            endpoints.acquire(fresh) { generation -> bind(appContext, generation) }
        }
    }

    private suspend fun bind(context: Context, generation: Long): IrohEndpoint {
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
        return IrohEndpoint(endpoint, generation)
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
            BridgeLog.w(TAG) { "could not persist the iroh secret key: ${e.javaClass.simpleName}" }
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
        BridgeLog.w(TAG) { "iroh relay settings refused: ${t.javaClass.simpleName}" }
        null
    }

    /** Shuts the endpoint down without blocking the caller. */
    fun shutdownAsync() {
        val bound = endpoints.detach() ?: return
        stopAsync(bound.endpoint, "phonxiety-iroh-close")
    }

    /**
     * Clears the process-wide endpoint so the next link binds a fresh one, and
     * shuts the old one down without blocking the caller.  Done when Android's
     * default network changes: an endpoint bound on the old network can stay on
     * the relay or wedged in reconnects (iroh#4588, iroh#4476), a fresh one finds
     * the paths of the new network.
     *
     * Detaches under the cache's state guard, not its bind mutex: like the
     * generation-scoped variant, this is not serialized with a bind.
     */
    fun recycleEndpoint() {
        val bound = endpoints.detach() ?: return
        stopAsync(bound.endpoint, "phonxiety-iroh-recycle")
    }

    /**
     * Recycles the endpoint only while [generation] is still the bound one: a
     * link callback that lost the race to its own cancellation and replacement
     * must never close the endpoint a newer link dialled through.
     */
    fun recycleEndpoint(generation: Long): Boolean {
        val bound = endpoints.detach(generation) { it.generation } ?: return false
        stopAsync(bound.endpoint, "phonxiety-iroh-recycle")
        return true
    }

    private fun stopAsync(endpoint: Endpoint, name: String) {
        irohIo.launch {
            try {
                endpoint.shutdown()
            } catch (t: Throwable) {
                BridgeLog.w(TAG) { "iroh endpoint close failed ($name): ${t.javaClass.simpleName}" }
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
}
