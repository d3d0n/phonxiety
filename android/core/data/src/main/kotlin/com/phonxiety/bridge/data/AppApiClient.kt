package com.phonxiety.bridge.data

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One app API call over the live device link. */
@Singleton
class LinkAppApi @Inject constructor(
    gateways: AppGatewaySource
) : AppApi {
    private val envelope = AppApiEnvelope(gateways = gateways)

    override suspend fun request(
        method: String,
        path: String,
        query: Map<String, String>,
        body: JsonObject?
    ): ApiResult = envelope.request(method, path, query, body)

    override suspend fun download(
        path: String,
        query: Map<String, String>,
        sink: OutputStream
    ): ApiResult = withContext(Dispatchers.IO) {
        envelope.download(path, query, sink)
    }
}

/**
 * The request/response envelope: one id per request, one 30 s deadline, the
 * gateway's own app token, and the daemon's `{code, message}` mapped into
 * [ApiResult.Failure].  Bulk data uses the attachment stream when the live
 * transport offers one and paged base64 otherwise.
 *
 * The credential is read from the gateway that carries the request, never
 * from storage on its own: a pairing change replaces the gateway, so the new
 * daemon's token cannot be attached to the previous daemon's link.
 */
internal class AppApiEnvelope(
    private val gateways: AppGatewaySource,
    private val timeoutMs: Long = 30_000L,
    private val pageBytes: Int = 192 * 1024,
    private val nextId: () -> String = { UUID.randomUUID().toString() }
) {
    private val json: Json = DaemonJson

    suspend fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null
    ): ApiResult {
        val gateway = gateways.gateway()
            ?: return ApiResult.Failure(503, "link_down", "the bridge service is not running")
        val token = gateway.appToken
            ?: return ApiResult.Failure(401, "app_token_missing", "pair again to receive an app token")
        return exchange(gateway, token, method, path, query, body)
    }

    private suspend fun exchange(
        gateway: AppGateway,
        token: String,
        method: String,
        path: String,
        query: Map<String, String>,
        body: JsonObject?
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
            withTimeout(timeoutMs) { gateway.exchange(request) }
        } catch (e: TimeoutCancellationException) {
            return ApiResult.Failure(504, "timeout", "the daemon did not answer in time")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ApiResult.Failure(500, "transport_error", e.javaClass.simpleName)
        }
        return map(result)
    }

    suspend fun download(
        path: String,
        query: Map<String, String>,
        sink: OutputStream
    ): ApiResult {
        val gateway = gateways.gateway()
            ?: return ApiResult.Failure(503, "link_down", "the bridge service is not running")
        val token = gateway.appToken
            ?: return ApiResult.Failure(401, "app_token_missing", "pair again to receive an app token")
        if (gateway.supportsAttachments) {
            val header = AttachmentHeader(
                id = nextId(),
                appToken = token,
                path = path,
                query = query
            )
            when (
                val outcome = runCatching { gateway.receiveAttachment(header, sink) }.getOrElse {
                    return ApiResult.Failure(500, "transport_error", it.javaClass.simpleName)
                }
            ) {
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
