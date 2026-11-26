package com.phonxiety.bridge.net

import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.model.DaemonInfo
import com.phonxiety.bridge.model.DeviceApiRequest
import com.phonxiety.bridge.model.DeviceApiResult
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Asks the daemon for its other addresses once a link's `hello` is answered.
 *
 * Before this the phone learned them only from the settings screen, so an
 * iroh pairing had no pin for links or mDNS until someone opened it.  The
 * refresh asks `/app/v1/info` over the link that is already up, at most once
 * per connection, and only ever logs a failure: the link and any call keep
 * running whatever the daemon answers.
 */
internal class LinkCandidateRefresh(
    private val store: CandidateStore,
    private val appToken: () -> String?,
    private val exchange: suspend (DeviceApiRequest) -> DeviceApiResult,
    private val nextId: () -> String = { UUID.randomUUID().toString() }
) {
    /** Set by the first refresh of one connection; cleared when the link goes down. */
    private val asked = AtomicBoolean(false)

    private val json = Json { ignoreUnknownKeys = true }

    /** A new connection may ask again; the previous answer stays until it does. */
    fun linkDown() {
        asked.set(false)
    }

    /** One `/app/v1/info` per connection, merged into [store] with the daemon's pin. */
    suspend fun refresh() {
        val token = appToken() ?: return
        if (!asked.compareAndSet(false, true)) return
        val request = DeviceApiRequest(
            id = nextId(),
            method = "GET",
            path = INFO_PATH,
            appToken = token
        )
        val result = try {
            exchange(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BridgeLog.w(TAG, "info refresh failed: ${e.javaClass.simpleName}")
            return
        }
        if (result.status !in 200..299) {
            BridgeLog.w(TAG, "info refresh refused: ${result.status} ${result.error?.code.orEmpty()}")
            return
        }
        val info = try {
            result.result?.let { json.decodeFromJsonElement(DaemonInfo.serializer(), it) }
        } catch (e: Exception) {
            BridgeLog.w(TAG, "info refresh returned no usable document: ${e.javaClass.simpleName}")
            null
        } ?: return
        CandidateMerge.store(store, info.connection)
    }

    companion object {
        private const val TAG = "PhonxietyBridge"
        const val INFO_PATH = "/app/v1/info"
    }
}
