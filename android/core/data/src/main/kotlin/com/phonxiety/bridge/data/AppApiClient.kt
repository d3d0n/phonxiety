package com.phonxiety.bridge.data

import com.phonxiety.bridge.data.di.IoDispatcher
import com.phonxiety.bridge.model.AppGateway
import com.phonxiety.bridge.model.AppGatewaySource
import com.phonxiety.bridge.model.AttachmentHeader
import com.phonxiety.bridge.model.AttachmentOutcome
import com.phonxiety.bridge.model.AttachmentPage
import com.phonxiety.bridge.model.DeviceApiRequest
import com.phonxiety.bridge.model.DeviceApiResult
import java.io.OutputStream
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One app API call over the live device link. */
@Singleton
class LinkAppApi @Inject constructor(
    gateways: AppGatewaySource,
    @IoDispatcher private val io: CoroutineDispatcher
) : AppApi {
    private val envelope = AppApiEnvelope(gateways = gateways)

    override suspend fun request(
        method: String,
        path: String,
        query: Map<String, String>,
        body: JsonObject?
    ): ApiResult = envelope.request(method, path, query, body)

    override fun operation(): ApiOperation = envelope.operation()

    override suspend fun download(
        path: String,
        query: Map<String, String>,
        sink: OutputStream
    ): ApiResult = withContext(io) {
        envelope.download(path, query, sink)
    }
}

/**
 * The request/response envelope: one id per request, the gateway's own app
 * token, and the daemon's `{code, message}` mapped into [ApiResult.Failure].
 * The engine owns each attempt's deadline (including a wait for the link), and the whole
 * authenticated operation — attempt, token wait, one retry — shares one budget; a typed timeout
 * an attempt answers with is mapped here, and a caller's cancellation propagates.
 * A daemon `401 unauthorized` waits, within that budget, for the gateway's rotated token and then
 * retries exactly once on the same gateway; nothing else is retried.
 * Bulk data uses the attachment stream when the live transport offers one and
 * paged base64 otherwise.
 *
 * The credential is read from the gateway that carries the request, never
 * from storage on its own: a pairing change replaces the gateway, so the new
 * daemon's token cannot be attached to the previous daemon's link.
 */
internal class AppApiEnvelope(
    private val gateways: AppGatewaySource,
    private val pageBytes: Int = 192 * 1024,
    private val nextId: () -> String = { UUID.randomUUID().toString() },
    /** Monotonic milliseconds for the operation budget; a test drives it with virtual time. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val json: Json = DaemonJson

    /**
     * Binds one operation to the gateway resolved now: every send on the returned
     * channel uses that object, never a fresh lookup, so a retry cannot reach the
     * daemon that replaced it.  With no gateway installed the operation answers
     * [NO_SERVICE] at once, as a single request always did, and is never retried:
     * a gateway that comes up during a pause may already belong to another pairing.
     */
    fun operation(): ApiOperation = gateways.gateway()?.let(::PinnedOperation) ?: NoGatewayOperation

    suspend fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null
    ): ApiResult {
        val gateway = gateways.gateway() ?: return NO_SERVICE
        val token = gateway.appToken.value
            ?: return ApiResult.Failure(401, "app_token_missing", "pair again to receive an app token")
        return exchange(gateway, token, method, path, query, body)
    }

    /** One operation on [gateway]; [isCurrent] says whether the pairing still resolves it. */
    private inner class PinnedOperation(private val gateway: AppGateway) : ApiOperation {
        override fun isCurrent(): Boolean = gateways.gateway() === gateway

        override suspend fun request(
            method: String,
            path: String,
            query: Map<String, String>,
            body: JsonObject?
        ): ApiResult {
            val token = gateway.appToken.value
                ?: return ApiResult.Failure(401, "app_token_missing", "pair again to receive an app token")
            return exchange(gateway, token, method, path, query, body)
        }
    }

    /** No gateway when the operation opened: its one answer is final. */
    private object NoGatewayOperation : ApiOperation {
        override val canRetry: Boolean = false

        override fun isCurrent(): Boolean = false

        override suspend fun request(
            method: String,
            path: String,
            query: Map<String, String>,
            body: JsonObject?
        ): ApiResult = NO_SERVICE
    }

    /**
     * One authenticated operation inside ONE budget, whatever the method: the first attempt, the
     * wait for a rotated token and the one retry each take only what is left of it, so a late
     * refusal can never buy a second full deadline.  An attempt that runs out answers with the
     * engine's own typed timeout; a wait that runs out leaves the original refusal standing.
     */
    private suspend fun exchange(
        gateway: AppGateway,
        token: String,
        method: String,
        path: String,
        query: Map<String, String>,
        body: JsonObject?
    ): ApiResult {
        val deadline = clock() + OPERATION_BUDGET_MILLIS
        val first = send(gateway, token, method, path, query, body, remaining(deadline))
        if (gateways.gateway() !== gateway) return PAIRING_CHANGED
        // The daemon rotates the app token on every connect, and the app can be handed the new
        // one after a request already left with the old one.  It rejects at its auth check before
        // running the handler, so one retry with the new token is safe for writes too.
        if (!first.isUnauthorized()) return first
        val rotated = awaitNewToken(gateway, token, remaining(deadline)) ?: return first
        // The wait may have outlived the pairing: the retry belongs to the same gateway or nowhere.
        if (gateways.gateway() !== gateway) return first
        val left = remaining(deadline)
        // An attempt with less than this left would only spend the caller's wait on its timeout.
        if (left < MIN_ATTEMPT_MILLIS) return first
        val second = send(gateway, rotated, method, path, query, body, left)
        if (gateways.gateway() !== gateway) return PAIRING_CHANGED
        return second
    }

    /** What is left of the operation's one budget; never negative. */
    private fun remaining(deadline: Long): Long = (deadline - clock()).coerceAtLeast(0L)

    /** One send with [token] on [gateway] inside [deadlineMs] of the operation; exceptions are answers, cancellation is not. */
    private suspend fun send(
        gateway: AppGateway,
        token: String,
        method: String,
        path: String,
        query: Map<String, String>,
        body: JsonObject?,
        deadlineMs: Long
    ): ApiResult {
        val request = DeviceApiRequest(
            id = nextId(),
            method = method,
            path = path,
            query = query,
            body = body,
            appToken = token
        )
        val result = try {
            // The engine owns the attempt's deadline; a caller's cancellation — a timeout
            // included — is the caller's, never a daemon answer, so it propagates.
            gateway.exchange(request, deadlineMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ApiResult.Failure(500, "transport_error", e.javaClass.simpleName)
        }
        return map(result)
    }

    /**
     * Waits for [gateway] to hand over a credential other than [sent], bounded by
     * [TOKEN_ROTATION_WAIT_MILLIS] and by what is left of the operation's budget.  Null means no
     * rotation arrived in time and the refusal stands.
     */
    private suspend fun awaitNewToken(gateway: AppGateway, sent: String, budgetMs: Long): String? {
        val bound = minOf(TOKEN_ROTATION_WAIT_MILLIS, budgetMs)
        if (bound <= 0L) return null
        return withTimeoutOrNull(bound) { gateway.appToken.first { it != null && it != sent } }
    }

    private fun ApiResult.isUnauthorized(): Boolean = this is ApiResult.Failure && status == 401 && code == "unauthorized"

    suspend fun download(
        path: String,
        query: Map<String, String>,
        sink: OutputStream
    ): ApiResult {
        val gateway = gateways.gateway() ?: return NO_SERVICE
        val token = gateway.appToken.value
            ?: return ApiResult.Failure(401, "app_token_missing", "pair again to receive an app token")
        if (gateway.supportsAttachments) {
            val header = AttachmentHeader(
                id = nextId(),
                appToken = token,
                path = path,
                query = query
            )
            val streamed = runCatching { gateway.receiveAttachment(header, sink) }.getOrElse {
                return ApiResult.Failure(500, "transport_error", it.javaClass.simpleName)
            }
            // A download finished under a pairing that has since been replaced is
            // as stale as the pages of a paged one.
            if (gateways.gateway() !== gateway) {
                return PAIRING_CHANGED
            }
            when (val outcome = streamed) {
                is AttachmentOutcome.Complete -> return ApiResult.Success(
                    200,
                    buildJsonObject {
                        put("total", outcome.total)
                        put("sha256", outcome.sha256)
                    }
                )

                is AttachmentOutcome.Failed ->
                    return ApiResult.Failure(502, outcome.code, outcome.message)

                AttachmentOutcome.Unsupported -> Unit
            }
        }
        return pagedDownload(gateway, token, path, query, sink)
    }

    private suspend fun pagedDownload(
        gateway: AppGateway,
        token: String,
        path: String,
        query: Map<String, String>,
        sink: OutputStream
    ): ApiResult {
        var offset = 0L
        var total = -1L
        while (true) {
            val pageQuery = query + mapOf(
                "offset" to offset.toString(),
                "limit" to pageBytes.toString()
            )
            val response = exchange(gateway, token, "GET", path, pageQuery, null)
            if (response is ApiResult.Failure) return response
            val success = response as ApiResult.Success
            val page = try {
                json.decodeFromJsonElement(AttachmentPage.serializer(), success.result)
            } catch (e: Exception) {
                return ApiResult.Failure(502, "decode_failed", "the attachment page is not valid")
            }
            val chunk = try {
                Base64.getDecoder().decode(page.data)
            } catch (e: IllegalArgumentException) {
                return ApiResult.Failure(502, "decode_failed", "the attachment page is not valid base64")
            }
            sink.write(chunk)
            total = page.total
            offset += chunk.size
            if (offset >= total || chunk.isEmpty()) break
        }
        sink.flush()
        if (total < 0 || offset != total) {
            return ApiResult.Failure(502, "size_mismatch", "the attachment ended after $offset of $total bytes")
        }
        return ApiResult.Success(200, buildJsonObject { put("total", total) })
    }

    private fun map(result: DeviceApiResult): ApiResult {
        if (result.status in 200..299) {
            return ApiResult.Success(result.status, result.result ?: JsonObject(emptyMap()))
        }
        val error = result.error
        return ApiResult.Failure(
            status = result.status,
            code = error?.code ?: "http_${result.status}",
            message = error?.message ?: "the daemon refused the request"
        )
    }
}

/** The answer while no bridge gateway is installed: the service is not running. */
private val NO_SERVICE = ApiResult.Failure(503, "link_down", "the bridge service is not running")

/**
 * How long a refused request waits for the gateway to name a new app token.  Only a cap: the
 * operation's one budget ([DefaultDaemonApi.OPERATION_BUDGET_MILLIS]) can shorten it further.
 */
private const val TOKEN_ROTATION_WAIT_MILLIS = 5_000L

/** The one budget of an authenticated operation; the same one a retried read gets. */
private const val OPERATION_BUDGET_MILLIS = DefaultDaemonApi.OPERATION_BUDGET_MILLIS

/** No attempt starts with less than this left for its own answer. */
private const val MIN_ATTEMPT_MILLIS = DefaultDaemonApi.MIN_ATTEMPT_MILLIS
