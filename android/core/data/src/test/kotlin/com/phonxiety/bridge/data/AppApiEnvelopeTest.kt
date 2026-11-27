package com.phonxiety.bridge.data

import com.phonxiety.bridge.model.AppGateway
import com.phonxiety.bridge.model.AppGatewaySource
import com.phonxiety.bridge.model.AttachmentHeader
import com.phonxiety.bridge.model.AttachmentOutcome
import com.phonxiety.bridge.model.DeviceApiError
import com.phonxiety.bridge.model.DeviceApiRequest
import com.phonxiety.bridge.model.DeviceApiResult
import com.phonxiety.bridge.model.PairingGate
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Base64
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppApiEnvelopeTest {
    @Test
    fun requestCarriesAnIdTheAppTokenAndTheEnvelope() = runTest {
        val gateway = FakeGateway()
        gateway.reply = DeviceApiResult(
            id = "id-1",
            status = 200,
            result = buildJsonObject { put("daemon_version", "0.4.0") }
        )
        val envelope = AppApiEnvelope(source(gateway), nextId = { "id-1" })

        val result = envelope.request("GET", "/app/v1/info")

        assertTrue(result is ApiResult.Success)
        val request = gateway.last!!
        assertEquals("id-1", request.id)
        assertEquals("api", request.type)
        assertEquals("app-token", request.appToken)
        assertEquals("GET", request.method)
        assertEquals("/app/v1/info", request.path)
        assertEquals("0.4.0", (result as ApiResult.Success).result.jsonObject["daemon_version"]?.jsonPrimitive?.content)
    }

    @Test
    fun daemonErrorsKeepTheirStatusAndCode() = runTest {
        val gateway = FakeGateway()
        gateway.reply = DeviceApiResult(
            id = "x",
            status = 403,
            error = DeviceApiError("hourly_call_cap", "at most 12 calls per hour")
        )
        val envelope = AppApiEnvelope(source(gateway))

        val result = envelope.request("POST", "/app/v1/calls")

        val failure = result as ApiResult.Failure
        assertEquals(403, failure.status)
        assertEquals("hourly_call_cap", failure.code)
        assertEquals("at most 12 calls per hour", failure.message)
    }

    @Test
    fun aServiceThatIsNotRunningFailsWithoutAGateway() = runTest {
        val envelope = AppApiEnvelope(AppGatewaySource { null })
        val failure = envelope.request("GET", "/app/v1/info") as ApiResult.Failure
        assertEquals(503, failure.status)
        assertEquals("link_down", failure.code)
    }

    @Test
    fun aPairingWithoutAnAppTokenCannotCallTheApi() = runTest {
        val envelope = AppApiEnvelope(source(FakeGateway().apply { setToken(null) }))
        val failure = envelope.request("GET", "/app/v1/info") as ApiResult.Failure
        assertEquals(401, failure.status)
        assertEquals("app_token_missing", failure.code)
    }

    @Test
    fun theEnginesTypedTimeoutIsMappedToAFailure() = runTest {
        val gateway = FakeGateway()
        gateway.reply = DeviceApiResult(
            id = "x",
            status = 504,
            error = DeviceApiError("timeout", "the daemon did not answer in time")
        )
        val envelope = AppApiEnvelope(source(gateway))

        val failure = envelope.request("GET", "/app/v1/info") as ApiResult.Failure

        assertEquals(504, failure.status)
        assertEquals("timeout", failure.code)
    }

    @Test
    fun aCallersTimeoutCancellationPropagatesInsteadOfBecomingAnAnswer() = runTest {
        val gateway = FakeGateway()
        gateway.hang = true
        val envelope = AppApiEnvelope(source(gateway))

        // The envelope has no timeout of its own. The block records whether the envelope ever
        // returned: a swallowed cancellation would answer here, a propagated one cannot.
        var returned: ApiResult? = null
        val cancelled = runCatching {
            withTimeout(50) { returned = envelope.request("GET", "/app/v1/info") }
        }.exceptionOrNull()

        assertNull("the envelope must not answer the caller's cancellation", returned)
        assertTrue(cancelled is TimeoutCancellationException)
    }

    @Test
    fun anAnswerThatOutlivesItsPairingIsRefused() = runTest {
        PairingGate.resetForTest()
        try {
            val old = FakeGateway().apply { setToken("token-a") }
            old.afterAnswer = { PairingGate.expect("cert:b") }
            PairingGate.expect("cert:a")
            PairingGate.install(old, "cert:a")
            val envelope = AppApiEnvelope(AppGatewaySource { PairingGate.gateway() })

            val failure = envelope.request("GET", "/app/v1/info") as ApiResult.Failure

            assertEquals(503, failure.status)
            assertEquals("pairing_changed", failure.code)
        } finally {
            PairingGate.resetForTest()
        }
    }

    @Test
    fun anAttachmentStreamGoesStraightToTheSink() = runTest {
        val gateway = FakeGateway()
        gateway.supportsAttachments = true
        gateway.attachment = AttachmentOutcome.Complete(5, "abcde")
        gateway.attachmentBytes = byteArrayOf(1, 2, 3, 4, 5)
        val envelope = AppApiEnvelope(source(gateway), nextId = { "attachment-1" })
        val sink = ByteArrayOutputStream()

        val result = envelope.download("/app/v1/calls/c1/audio", mapOf("format" to "wav"), sink)

        assertTrue(result is ApiResult.Success)
        val header = gateway.attachmentHeader!!
        assertEquals("attachment-1", header.id)
        assertEquals("app-token", header.appToken)
        assertEquals("/app/v1/calls/c1/audio", header.path)
        assertEquals("wav", header.query["format"])
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), sink.toByteArray())
    }

    @Test
    fun aPagedLinkReadsBase64PagesUntilTheTotal() = runTest {
        val gateway = PageGateway(byteArrayOf(9, 8, 7, 6, 5))
        val envelope = AppApiEnvelope(source(gateway))
        val sink = ByteArrayOutputStream()

        val result = envelope.download("/app/v1/calls/c1/audio", emptyMap(), sink)

        assertTrue(result is ApiResult.Success)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6, 5), sink.toByteArray())
        assertEquals(listOf("0", "3"), gateway.offsets)
    }

    @Test
    fun aPagedReadThatStopsShortFails() = runTest {
        val gateway = PageGateway(byteArrayOf(9, 8, 7, 6, 5), stopAfterFirstPage = true)
        val envelope = AppApiEnvelope(source(gateway))
        val failure = envelope.download("/app/v1/calls/c1/audio", emptyMap(), ByteArrayOutputStream()) as ApiResult.Failure
        assertEquals("size_mismatch", failure.code)
    }

    // -- pairing generation ----------------------------------------------

    @Test
    fun aRePairClosesTheOldGatewayBeforeAnyRequestCarriesTheNewCredentials() = runTest {
        PairingGate.resetForTest()
        try {
            val old = FakeGateway().apply { setToken("token-a") }
            val envelope = AppApiEnvelope(AppGatewaySource { PairingGate.gateway() })
            PairingGate.expect("cert:a")
            PairingGate.install(old, "cert:a")
            assertTrue(envelope.request("GET", "/app/v1/info") is ApiResult.Success)
            assertEquals("token-a", old.last!!.appToken)

            // The repository writes the new pairing and advances the gate at once;
            // the service has not rebuilt yet.
            PairingGate.expect("cert:b")
            val refused = envelope.request("GET", "/app/v1/info") as ApiResult.Failure
            assertEquals(503, refused.status)
            assertEquals("token-a", old.last!!.appToken)

            // The rebuilt engine serves the new pairing, and only it.
            val rebuilt = FakeGateway().apply { setToken("token-b") }
            PairingGate.install(rebuilt, "cert:b")
            assertTrue(envelope.request("GET", "/app/v1/info") is ApiResult.Success)
            assertEquals("token-b", rebuilt.last!!.appToken)
            assertEquals("token-a", old.last!!.appToken)
        } finally {
            PairingGate.resetForTest()
        }
    }

    @Test
    fun aLateTeardownOfThePreviousGatewayCannotRemoveTheNewOne() {
        PairingGate.resetForTest()
        try {
            val first = FakeGateway()
            val rebuilt = FakeGateway()
            PairingGate.expect("cert:a")
            PairingGate.install(first, "cert:a")
            PairingGate.expect("cert:b")
            PairingGate.install(rebuilt, "cert:b")

            PairingGate.uninstall(first)

            assertTrue(PairingGate.gateway() === rebuilt)
        } finally {
            PairingGate.resetForTest()
        }
    }

    @Test
    fun aRePairDuringTheFirstPageStopsTheDownloadAndNeverTouchesTheNewGateway() = runTest {
        PairingGate.resetForTest()
        try {
            val rebuilt = FakeGateway().apply { setToken("token-b") }
            val old = PageGateway(byteArrayOf(9, 8, 7, 6, 5, 4)) { page ->
                // The pairing moves while the very first page is being answered,
                // and the new gateway is installed before the next lookup.
                if (page == 1) {
                    PairingGate.expect("cert:b")
                    PairingGate.install(rebuilt, "cert:b")
                }
            }
            old.setToken("token-a")
            PairingGate.expect("cert:a")
            PairingGate.install(old, "cert:a")
            val envelope = AppApiEnvelope(AppGatewaySource { PairingGate.gateway() })
            val sink = ByteArrayOutputStream()

            val result = envelope.download("/app/v1/calls/c1/audio", emptyMap(), sink)

            val failure = result as ApiResult.Failure
            assertEquals("pairing_changed", failure.code)
            // The one page the old link answered carried the old credential; the
            // new daemon was never asked to finish the old download and the page
            // never reached the sink.
            assertEquals(listOf("token-a"), old.tokens)
            assertTrue(rebuilt.last == null)
            assertEquals(0, sink.size())
        } finally {
            PairingGate.resetForTest()
        }
    }

    // -- app-token rotation ----------------------------------------------

    @Test
    fun aRotationDuringTheRequestRetriesOnceWithTheNewToken() = runTest {
        val gateway = TokenGateway("token-a")
        gateway.onSend = { if (it == 1) gateway.rotate("token-b") }
        val envelope = AppApiEnvelope(source(gateway))

        // A write too: the daemon rejects at its auth check before the handler runs,
        // so the one retry cannot apply the write twice.
        val result = envelope.request("PUT", "/app/v1/calls", body = buildJsonObject { })

        assertTrue("the retried write succeeded", result is ApiResult.Success)
        assertEquals("the one retry carried the rotated token", listOf("token-a", "token-b"), gateway.sent)
    }

    @Test
    fun noRotatedTokenWithinTheBoundKeepsTheOriginal401() = runTest {
        val gateway = TokenGateway("token-a", refuseAnyToken = true)
        val envelope = AppApiEnvelope(source(gateway))

        val result = envelope.request("GET", "/app/v1/info") as ApiResult.Failure

        assertEquals(401, result.status)
        assertEquals("unauthorized", result.code)
        assertEquals("only the one refused attempt was sent", listOf("token-a"), gateway.sent)
        assertEquals("the wait is bounded at five seconds", 5_000L, currentTime)
    }

    @Test
    fun a401OnTheRetryIsFinal() = runTest {
        val gateway = TokenGateway("token-a", refuseAnyToken = true)
        gateway.onSend = { if (it == 1) gateway.rotate("token-b") }
        val envelope = AppApiEnvelope(source(gateway))

        val result = envelope.request("GET", "/app/v1/info") as ApiResult.Failure

        assertEquals(401, result.status)
        assertEquals("exactly one retry, never a third attempt", listOf("token-a", "token-b"), gateway.sent)
        assertEquals("the retry is not followed by another wait", 0L, currentTime)
    }

    /**
     * One budget for the whole authenticated write: a late refusal leaves only what is left, and
     * the retry is sent with that, never with a fresh deadline of its own.
     */
    @Test
    fun aLateRefusalNeverBuysTheWriteASecondFullBudget() = runTest {
        val gateway = TokenGateway("token-a", firstAnswerDelayMs = 28_000)
        gateway.onSend = { if (it == 1) gateway.rotate("token-b") }
        val envelope = AppApiEnvelope(source(gateway), clock = { testScheduler.currentTime })

        val result = envelope.request("POST", "/app/v1/calls", body = buildJsonObject { })

        assertTrue("the retried write succeeded", result is ApiResult.Success)
        assertEquals("the retry carried the rotated token", listOf("token-a", "token-b"), gateway.sent)
        assertEquals("the first attempt got the whole budget", listOf(30_000L, 2_000L), gateway.deadlines)
        assertTrue("one operation never outran its one budget", currentTime <= 30_000L)
    }

    /**
     * The wait is capped by what is left of the budget, so a token that never arrives cannot keep
     * a write open for another five seconds: the original refusal answers at the deadline.
     */
    @Test
    fun aTokenThatNeverArrivesAnswersTheOriginal401AtTheDeadline() = runTest {
        val gateway = TokenGateway("token-a", refuseAnyToken = true, firstAnswerDelayMs = 29_500)
        val envelope = AppApiEnvelope(source(gateway), clock = { testScheduler.currentTime })

        val result = envelope.request("POST", "/app/v1/calls", body = buildJsonObject { }) as ApiResult.Failure

        assertEquals(401, result.status)
        assertEquals("no token, no retry", listOf("token-a"), gateway.sent)
        assertEquals("answered at the one deadline, not five seconds later", 30_000L, currentTime)
    }

    @Test
    fun aGatewayThatChangedDuringTheWaitIsNotRetried() = runTest {
        val gateway = TokenGateway("token-a", refuseAnyToken = true)
        var current: AppGateway? = gateway
        val envelope = AppApiEnvelope(AppGatewaySource { current }, clock = { testScheduler.currentTime })
        var result: ApiResult? = null

        val job = launch { result = envelope.request("GET", "/app/v1/info") }
        runCurrent()
        assertEquals(listOf("token-a"), gateway.sent)

        // The wait is real: three seconds in, the refusal is not the answer yet.
        advanceTimeBy(3_000)
        runCurrent()
        assertNull("the request is still waiting for a rotated token", result)
        assertEquals("no retry while the wait runs", listOf("token-a"), gateway.sent)

        // The pairing is rebuilt while the request waits for a token: only the
        // original answer may reach the caller, and the new gateway is never asked.
        val rebuilt = TokenGateway("token-b")
        current = rebuilt
        gateway.rotate("token-b")
        runCurrent()
        job.join()

        assertEquals(401, (result as ApiResult.Failure).status)
        assertEquals("the wake-up after the change answers at once, not at the five-second bound", 3_000L, currentTime)
        assertEquals(listOf("token-a"), gateway.sent)
        assertTrue("the replaced gateway was never asked", rebuilt.sent.isEmpty())
    }

    private fun source(gateway: AppGateway?) = AppGatewaySource { gateway }

    private class FakeGateway : AppGateway {
        private val tokenState = MutableStateFlow<String?>("app-token")
        override val appToken: StateFlow<String?> = tokenState.asStateFlow()

        fun setToken(value: String?) {
            tokenState.value = value
        }

        override var supportsAttachments: Boolean = false
        var reply = DeviceApiResult(id = "x", status = 200, result = buildJsonObject { })
        var last: DeviceApiRequest? = null
        var hang = false
        var afterAnswer: () -> Unit = {}
        var attachment: AttachmentOutcome = AttachmentOutcome.Unsupported
        var attachmentHeader: AttachmentHeader? = null
        var attachmentBytes: ByteArray? = null

        override suspend fun exchange(request: DeviceApiRequest, deadlineMs: Long): DeviceApiResult {
            last = request
            if (hang) awaitCancellation()
            afterAnswer()
            return reply
        }

        override suspend fun receiveAttachment(header: AttachmentHeader, sink: OutputStream): AttachmentOutcome {
            attachmentHeader = header
            attachmentBytes?.let { sink.write(it) }
            return attachment
        }
    }

    /**
     * A gateway that accepts exactly the token it currently holds. [onSend] runs after the request
     * was built with its token and before the answer, so a test can rotate the credential while a
     * request is in flight. [firstAnswerDelayMs] holds the first answer back, as a slow daemon does.
     * Every send records the deadline it was given, so a test can see what is left of the budget.
     */
    private class TokenGateway(
        initial: String,
        private val refuseAnyToken: Boolean = false,
        private val firstAnswerDelayMs: Long = 0
    ) : AppGateway {
        private val tokenState = MutableStateFlow<String?>(initial)
        override val appToken: StateFlow<String?> = tokenState.asStateFlow()
        override val supportsAttachments: Boolean = false
        val sent = mutableListOf<String?>()
        val deadlines = mutableListOf<Long>()
        var onSend: (Int) -> Unit = {}

        fun rotate(token: String) {
            tokenState.value = token
        }

        override suspend fun exchange(request: DeviceApiRequest, deadlineMs: Long): DeviceApiResult {
            sent += request.appToken
            deadlines += deadlineMs
            if (sent.size == 1 && firstAnswerDelayMs > 0) delay(firstAnswerDelayMs)
            onSend(sent.size)
            val accepted = !refuseAnyToken && request.appToken == tokenState.value
            return if (accepted) {
                DeviceApiResult(request.id, 200, result = buildJsonObject { put("ok", true) })
            } else {
                DeviceApiResult(
                    request.id,
                    401,
                    error = DeviceApiError("unauthorized", "the app token is missing or no longer valid")
                )
            }
        }

        override suspend fun receiveAttachment(header: AttachmentHeader, sink: OutputStream): AttachmentOutcome = AttachmentOutcome.Unsupported
    }

    /** Answers a paged download with the base64 pages the daemon returns on WebSocket. */
    private class PageGateway(
        private val payload: ByteArray,
        private val stopAfterFirstPage: Boolean = false,
        private val onPage: (Int) -> Unit = {}
    ) : AppGateway {
        private val tokenState = MutableStateFlow<String?>("app-token")
        override val appToken: StateFlow<String?> = tokenState.asStateFlow()

        fun setToken(value: String?) {
            tokenState.value = value
        }

        override val supportsAttachments: Boolean = false
        val offsets = mutableListOf<String>()
        val tokens = mutableListOf<String>()

        override suspend fun exchange(request: DeviceApiRequest, deadlineMs: Long): DeviceApiResult {
            val offset = request.query["offset"] ?: "0"
            onPage(offsets.size + 1)
            offsets += offset
            request.appToken?.let { tokens += it }
            val start = offset.toInt()
            val end = if (stopAfterFirstPage && start > 0) start else minOf(payload.size, start + 3)
            val chunk = payload.copyOfRange(minOf(start, payload.size), end)
            val page = buildJsonObject {
                put("total", payload.size)
                put("offset", start)
                put("limit", 3)
                put("data", Base64.getEncoder().encodeToString(chunk))
            }
            return DeviceApiResult(id = request.id, status = 200, result = page)
        }

        override suspend fun receiveAttachment(header: AttachmentHeader, sink: OutputStream): AttachmentOutcome = AttachmentOutcome.Unsupported
    }
}
