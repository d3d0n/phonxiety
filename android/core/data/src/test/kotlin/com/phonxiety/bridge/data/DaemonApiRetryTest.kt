package com.phonxiety.bridge.data

import com.phonxiety.bridge.model.AppGateway
import com.phonxiety.bridge.model.AppGatewaySource
import com.phonxiety.bridge.model.AttachmentHeader
import com.phonxiety.bridge.model.AttachmentOutcome
import com.phonxiety.bridge.model.DeviceApiError
import com.phonxiety.bridge.model.DeviceApiRequest
import com.phonxiety.bridge.model.DeviceApiResult
import com.phonxiety.bridge.model.PlaceCallRequest
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
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
 * Retries belong to reads only, never outlive one operation budget, keep their
 * gateway, and never repeat a write, a download or the events long poll.
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

    @Test
    fun slowAttemptsEndAtTheOperationBudgetWithTheTypedTimeout() = runTest {
        // Each attempt alone spends 20 s, so three of them could never fit one read.
        val transport = ScriptedApi(ApiResult.Failure(503, "link_down", "not yet"), latencyMillis = 20_000)
        val api = DefaultDaemonApi(transport).apply {
            retryRandom = Random(1)
            retryClock = { currentTime }
        }

        val outcome = api.diagnostics()

        assertEquals(DefaultDaemonApi.OPERATION_BUDGET_MILLIS, currentTime)
        assertEquals("attempts: ${transport.requests.size}", 2, transport.requests.size)
        val failure = outcome as ApiOutcome.Failure
        assertEquals(504, failure.status)
        assertEquals("timeout", failure.code)
        assertEquals("the daemon did not answer in time", failure.message)
    }

    @Test
    fun aFast503IsRetriedInsideTheOperationBudget() = runTest {
        val transport = ScriptedApi(
            ApiResult.Failure(503, "link_down", "not yet"),
            ApiResult.Success(200, buildJsonObject { put("ok", true) })
        )
        val api = DefaultDaemonApi(transport).apply {
            retryRandom = Random(1)
            retryClock = { currentTime }
        }

        val outcome = api.diagnostics()

        assertTrue("expected success, got $outcome", outcome is ApiOutcome.Ok)
        assertEquals(2, transport.requests.size)
        assertTrue("elapsed $currentTime", currentTime < DefaultDaemonApi.OPERATION_BUDGET_MILLIS)
    }

    @Test
    fun aRetryThatCannotFitTheBudgetIsSkipped() = runTest {
        // One attempt leaves half a second: not enough for a pause and an attempt.
        val transport = ScriptedApi(ApiResult.Failure(503, "link_down", "not yet"), latencyMillis = 29_500)
        val api = DefaultDaemonApi(transport).apply {
            retryRandom = Random(1)
            retryClock = { currentTime }
        }

        val outcome = api.diagnostics()

        assertEquals(1, transport.requests.size)
        assertEquals(29_500L, currentTime)
        assertEquals(503, (outcome as ApiOutcome.Failure).status)
        assertEquals("link_down", outcome.code)
    }

    @Test
    fun everyRetryStaysOnTheGatewayTheFirstAttemptUsed() = runTest {
        val old = FakeGateway(status = 503)
        val fresh = FakeGateway(status = 200)
        var lookups = 0
        // The pairing moves right after the guard looked: the retry is already bound.
        val source = AppGatewaySource { if (++lookups <= 3) old else fresh }
        val api = DefaultDaemonApi(LinkAppApi(source)).apply {
            retryRandom = Random(1)
            retryClock = { currentTime }
        }

        val outcome = api.diagnostics()

        assertEquals("the old gateway carried the retry too", 2, old.requests.size)
        assertEquals("the new gateway must never be asked", 0, fresh.requests.size)
        assertEquals("pairing_changed", (outcome as ApiOutcome.Failure).code)
    }

    @Test
    fun aPairingChangeSeenByTheGuardStopsTheRetry() = runTest {
        val old = FakeGateway(status = 503)
        val fresh = FakeGateway(status = 200)
        var lookups = 0
        // The pairing has moved by the time the guard looks: it stops before any retry.
        val source = AppGatewaySource { if (++lookups <= 2) old else fresh }
        val api = DefaultDaemonApi(LinkAppApi(source)).apply {
            retryRandom = Random(1)
            retryClock = { currentTime }
        }

        val outcome = api.diagnostics()

        assertEquals("the new gateway must never be asked", 0, fresh.requests.size)
        assertEquals(1, old.requests.size)
        assertEquals("pairing_changed", (outcome as ApiOutcome.Failure).code)
        assertEquals(503, outcome.status)
    }

    @Test
    fun aPairingChangeDuringThePauseStopsTheRetry() = runTest {
        val old = FakeGateway(status = 503)
        val fresh = FakeGateway(status = 200)
        var current = old
        val api = DefaultDaemonApi(LinkAppApi(AppGatewaySource { current })).apply {
            retryRandom = Random(1)
            retryClock = { currentTime }
        }

        val operation = async { api.diagnostics() }
        runCurrent()
        assertEquals(1, old.requests.size)
        assertEquals("the pause has not elapsed", 0L, currentTime)

        current = fresh
        val outcome = operation.await()

        assertEquals("the new gateway must never be asked", 0, fresh.requests.size)
        assertEquals("pairing_changed", (outcome as ApiOutcome.Failure).code)
    }

    @Test
    fun anOperationOpenedWithNoGatewayAnswersLinkDownAndNeverReachesALaterOne() = runTest {
        val later = FakeGateway(status = 200)
        var lookups = 0
        // No gateway when the read opens; another pairing's comes up right after.
        val source = AppGatewaySource { if (++lookups <= 1) null else later }
        val api = DefaultDaemonApi(LinkAppApi(source)).apply {
            retryRandom = Random(1)
            retryClock = { currentTime }
        }

        val outcome = api.diagnostics()

        assertEquals("a gateway that came up later must never be asked", 0, later.requests.size)
        assertEquals(503, (outcome as ApiOutcome.Failure).status)
        assertEquals("link_down", outcome.code)
        assertEquals("no pause was spent", 0L, currentTime)
    }

    @Test
    fun aPairingChangeIsNeverRetried() = runTest {
        for (status in listOf(503, 500)) {
            val transport = ScriptedApi(ApiResult.Failure(status, "pairing_changed", "the pairing changed"))
            val api = DefaultDaemonApi(transport).apply { retryRandom = Random(1) }

            val outcome = api.diagnostics()

            assertEquals("$status: attempts", 1, transport.requests.size)
            assertEquals("$status: the answer", status, (outcome as ApiOutcome.Failure).status)
            assertEquals("$status: the code", "pairing_changed", outcome.code)
        }
    }

    @Test
    fun aCancellationFromTheTransportIsRethrownAndNotATimeout() = runTest {
        val transport = object : AppApi {
            override suspend fun request(
                method: String,
                path: String,
                query: Map<String, String>,
                body: JsonObject?
            ): ApiResult = throw CancellationException("the caller went away")

            override suspend fun download(
                path: String,
                query: Map<String, String>,
                sink: OutputStream
            ): ApiResult = error("a download is not this test")
        }
        val api = DefaultDaemonApi(transport).apply { retryClock = { currentTime } }

        val thrown = runCatching { api.diagnostics() }.exceptionOrNull()

        assertTrue("expected a cancellation, got $thrown", thrown is CancellationException)
    }

    @Test
    fun aCallerCancellationDuringAnAttemptStaysCancellation() = runTest {
        val transport = object : AppApi {
            override suspend fun request(
                method: String,
                path: String,
                query: Map<String, String>,
                body: JsonObject?
            ): ApiResult = awaitCancellation()

            override suspend fun download(
                path: String,
                query: Map<String, String>,
                sink: OutputStream
            ): ApiResult = error("a download is not this test")
        }
        val api = DefaultDaemonApi(transport).apply { retryClock = { currentTime } }

        val operation = async { api.diagnostics() }
        runCurrent()
        operation.cancel()

        val thrown = runCatching { operation.await() }.exceptionOrNull()
        assertTrue("expected a cancellation, got $thrown", thrown is CancellationException)
    }

    @Test
    fun aCallerCancellationDuringThePauseStaysCancellation() = runTest {
        val transport = ScriptedApi(ApiResult.Failure(503, "link_down", "not yet"))
        val api = DefaultDaemonApi(transport).apply {
            retryRandom = Random(1)
            retryClock = { currentTime }
        }

        val operation = async { api.diagnostics() }
        runCurrent()
        assertEquals("the first attempt ran and the pause began", 1, transport.requests.size)
        assertEquals(0L, currentTime)
        operation.cancel()

        val thrown = runCatching { operation.await() }.exceptionOrNull()
        assertTrue("expected a cancellation, got $thrown", thrown is CancellationException)
        assertEquals(1, transport.requests.size)
    }

    private suspend fun failingTransport(block: suspend (DefaultDaemonApi) -> Any?): ScriptedApi {
        val transport = ScriptedApi(ApiResult.Failure(503, "link_down", "no"))
        block(DefaultDaemonApi(transport).apply { retryRandom = Random(1) })
        return transport
    }

    /** Answers from its script in order, repeating the last answer; records every attempt. */
    private class ScriptedApi(vararg replies: ApiResult, private val latencyMillis: Long = 0) : AppApi {
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
            if (latencyMillis > 0) delay(latencyMillis)
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

    /** One daemon's gateway object: records every request it is asked to carry. */
    private class FakeGateway(
        private val status: Int,
        private val code: String = "link_down",
        private val message: String = "not yet"
    ) : AppGateway {
        override val appToken: String = "token"
        override val supportsAttachments: Boolean = false
        val requests = mutableListOf<DeviceApiRequest>()

        override suspend fun exchange(request: DeviceApiRequest): DeviceApiResult {
            requests += request
            return if (status in 200..299) {
                DeviceApiResult(request.id, status, result = buildJsonObject { put("ok", true) })
            } else {
                DeviceApiResult(request.id, status, error = DeviceApiError(code, message))
            }
        }

        override suspend fun receiveAttachment(header: AttachmentHeader, sink: OutputStream): AttachmentOutcome = error("a download is not this test")
    }
}
