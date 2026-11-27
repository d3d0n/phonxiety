package com.phonxiety.bridge

import com.phonxiety.bridge.model.DaemonInfo
import com.phonxiety.bridge.model.DeviceApiRequest
import com.phonxiety.bridge.model.DeviceApiResult
import com.phonxiety.bridge.net.CandidateMerge
import com.phonxiety.bridge.net.CandidateStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The one `/app/v1/info` request of a connection.
 *
 * The daemon sends the app credential (rotated or not) over the authenticated
 * link, and only then is an info request admitted: asking earlier answers
 * nothing for a pairing without a token, or burns the attempt on a 401 for a
 * rotated one.  The request waits until no call is owned, so it never competes
 * with a call for the link, and it is tied to the link generation and to the
 * pairing that built this engine: an answer that arrives after either moved is
 * dropped instead of being applied to the new pairing.
 */
internal class AppInfoRefresh(
    private val store: CandidateStore,
    private val identity: () -> String,
    private val admission: CallAdmission,
    private val callOwned: () -> Boolean,
    private val linkUp: () -> Boolean,
    private val token: () -> String?,
    private val scope: CoroutineScope,
    private val exchange: suspend (DeviceApiRequest) -> DeviceApiResult,
    private val apply: (DaemonInfo) -> Unit,
    private val nextId: () -> String = { UUID.randomUUID().toString() }
) {
    /** Bumped for every connection that opens; an older connection's answer is never applied. */
    private val linkEpoch = AtomicLong(0L)

    /** The link generation this connection already asked for, or -1. */
    private val refreshed = AtomicLong(-1L)

    /** A request was admitted by the daemon's app access and waits for an idle link. */
    private val pending = AtomicBoolean(false)

    private val json = Json { ignoreUnknownKeys = true }

    /** A new connection may ask once; nothing carries over from the previous one. */
    fun linkOpened() {
        linkEpoch.incrementAndGet()
        pending.set(false)
    }

    /** The link went down before an admitted request ran: the next connection asks again. */
    fun linkDown() {
        pending.set(false)
    }

    /**
     * The daemon delivered its app credential, rotated or unchanged.  Both
     * cases ask for info: an unchanged token still needs the capability and
     * address answer this connection has not read yet.
     */
    fun delivered() {
        pending.set(true)
        run()
    }

    /** Retries a request deferred by an owned call; cheap while none is pending. */
    fun tick() {
        if (pending.get()) run()
    }

    private fun run() {
        if (!pending.get() || !linkUp()) return
        val appToken = token() ?: return
        val current = linkEpoch.get()
        if (refreshed.get() == current) {
            pending.set(false)
            return
        }
        // The idle door is taken only for the decision; the request itself is a
        // small control exchange and must not hold call reservation for its duration.
        val action = admission.tryBeginIdleLinkAction(callOwned) ?: return
        try {
            if (!pending.compareAndSet(true, false)) return
            scope.launch { perform(current, appToken) }
        } finally {
            action.close()
        }
    }

    private suspend fun perform(epoch: Long, appToken: String) {
        val request = DeviceApiRequest(id = nextId(), method = "GET", path = INFO_PATH, appToken = appToken)
        val result = try {
            exchange(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BridgeLog.w(TAG) { "info refresh failed: ${e.javaClass.simpleName}" }
            return
        }
        // One attempt per connection whatever the answer: a refusal is retried
        // by the daemon's next app access, not by asking again on this link.
        refreshed.set(epoch)
        if (result.status !in 200..299) {
            BridgeLog.w(TAG) { "info refresh refused: ${result.status} ${result.error?.code.orEmpty()}" }
            return
        }
        val info = try {
            result.result?.let { json.decodeFromJsonElement(DaemonInfo.serializer(), it) }
        } catch (e: Exception) {
            BridgeLog.w(TAG) { "info refresh returned no usable document: ${e.javaClass.simpleName}" }
            null
        } ?: return
        // The link may have reconnected while the answer was in flight.
        if (linkEpoch.get() != epoch) return
        apply(info)
        if (CandidateMerge.store(store, info.connection, identity()) == null) {
            BridgeLog.w(TAG, "info answered for another pairing; its addresses were not stored")
        }
    }

    companion object {
        private const val TAG = "Bridge:api"
        const val INFO_PATH = "/app/v1/info"
    }
}
