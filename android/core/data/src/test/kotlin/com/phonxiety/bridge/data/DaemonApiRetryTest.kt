package com.phonxiety.bridge.data

import com.phonxiety.bridge.model.PlaceCallRequest
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.random.Random
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retries belong to reads only, never outlive the caller's deadline, and never
 * repeat a write, a download or the events long poll.
 */
class DaemonApiRetryTest {

    @Test
    fun aReadIsRetriedOnEachRetryableStatus() = runTest {
        for (status in listOf(502, 503, 504)) {
            val transport = ScriptedApi(ApiResult.Failure(status, "not_now", "try later"))
            val api = DefaultDaemonApi(transport).apply { retryRandom = Random(1) }

            val outcome = api.diagnostics()

            assertEquals("$status: attempts", 3, transport.requests.size)
            assertEquals("$status: the last answer", status, (outcome as ApiOutcome.Failure).status)
        }
    }

    @Test
    fun aReadIsRetriedAfterATransportFailure() = runTest {
        val transport = ScriptedApi(ApiResult.Failure(500, "transport_error", "SocketTimeout"))
        val api = DefaultDaemonApi(transport).apply { retryRandom = Random(1) }

        api.diagnostics()

        assertEquals(3, transport.requests.size)
        assertEquals(listOf("GET", "GET", "GET"), transport.requests.map { it.first })
    }

    @Test
    fun aReadSucceedsAfterTwoTransientFailures() = runTest {
        val transport = ScriptedApi(
            ApiResult.Failure(502, "bad_gateway", "not yet"),
            ApiResult.Failure(503, "link_down", "not yet"),
            ApiResult.Success(200, buildJsonObject { put("ok", true) })
        )
        val api = DefaultDaemonApi(transport).apply { retryRandom = Random(1) }

        val outcome = api.diagnostics()

        assertTrue("expected success, got $outcome", outcome is ApiOutcome.Ok)
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun postIsNeverRetried() = runTest {
        val transport = failingTransport { it.placeCall(PlaceCallRequest(destination = "+15550100", goal = "book a table")) }

        assertEquals(listOf("POST"), transport.requests.map { it.first })
    }

    @Test
    fun putIsNeverRetried() = runTest {
        val transport = failingTransport { it.putSettings(JsonObject(emptyMap())) }

        assertEquals(listOf("PUT"), transport.requests.map { it.first })
    }

    @Test
    fun deleteIsNeverRetried() = runTest {
        val transport = failingTransport { it.deleteCall("call-1") }

        assertEquals(listOf("DELETE"), transport.requests.map { it.first })
    }

    @Test
    fun theEventsLongPollIsNeverRetried() = runTest {
        val transport = failingTransport { it.events("call-1", since = 4, waitSeconds = 20.0) }

        assertEquals(listOf("GET"), transport.requests.map { it.first })
        assertEquals("/app/v1/calls/call-1/events", transport.requests.single().second)
    }

    @Test
    fun aDownloadIsNeverRetried() = runTest {
        val transport = ScriptedApi(ApiResult.Failure(503, "link_down", "no"))

        val result = DefaultDaemonApi(transport).downloadAudio("call-1", "m4a", ByteArrayOutputStream())

        assertTrue(result is ApiResult.Failure)
        assertEquals(1, transport.downloads)
    }

    @Test
    fun aClientErrorIsNeverRetried() = runTest {
        val transport = ScriptedApi(ApiResult.Failure(404, "not_found", "no such record"))
        val api = DefaultDaemonApi(transport).apply { retryRandom = Random(1) }

        api.diagnostics()

        assertEquals(1, transport.requests.size)
    }

    @Test
    fun theRetriesNeverOutliveTheCallersDeadline() = runTest {
        val transport = ScriptedApi(ApiResult.Failure(503, "link_down", "no"))
        val api = DefaultDaemonApi(transport).apply { retryRandom = Random(4) }
        val deadline = 300L

        val outcome = withTimeoutOrNull(deadline) { api.diagnostics() }

        assertNull("the deadline must end the operation", outcome)
        assertTrue("elapsed $currentTime is past the $deadline ms deadline", currentTime <= deadline)
        assertTrue("attempts past the deadline: ${transport.requests.size}", transport.requests.size in 1..3)
    }

    @Test
    fun aStuckAttemptStopsAtTheCallersDeadline() = runTest {
        val transport = object : AppApi {
            var requests = 0

            override suspend fun request(
                method: String,
                path: String,
                query: Map<String, String>,
                body: JsonObject?
            ): ApiResult {
                requests++
                awaitCancellation()
            }

            override suspend fun download(
                path: String,
                query: Map<String, String>,
                sink: OutputStream
            ): ApiResult = error("a download is not this test")
        }
        val deadline = 500L

        val outcome = withTimeoutOrNull(deadline) { DefaultDaemonApi(transport).diagnostics() }

        assertNull(outcome)
        assertEquals(deadline, currentTime)
        assertEquals(1, transport.requests)
    }

    private suspend fun failingTransport(block: suspend (DefaultDaemonApi) -> Any?): ScriptedApi {
        val transport = ScriptedApi(ApiResult.Failure(503, "link_down", "no"))
        block(DefaultDaemonApi(transport).apply { retryRandom = Random(1) })
        return transport
    }

    /** Answers from its script in order, repeating the last answer; records every attempt. */
    private class ScriptedApi(vararg replies: ApiResult) : AppApi {
        private val script = replies.toList()
        val requests = mutableListOf<Pair<String, String>>()
        var downloads = 0

        override suspend fun request(
            method: String,
            path: String,
            query: Map<String, String>,
            body: JsonObject?
        ): ApiResult {
            requests += method to path
            return if (requests.size <= script.size) script[requests.size - 1] else script.last()
        }

        override suspend fun download(
            path: String,
            query: Map<String, String>,
            sink: OutputStream
        ): ApiResult {
            downloads++
            return ApiResult.Failure(503, "link_down", "no")
        }
    }
}
