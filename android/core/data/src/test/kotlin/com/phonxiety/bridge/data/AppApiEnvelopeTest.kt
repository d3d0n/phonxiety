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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        val envelope = AppApiEnvelope(source(FakeGateway().apply { appToken = null }))
        val failure = envelope.request("GET", "/app/v1/info") as ApiResult.Failure
        assertEquals(401, failure.status)
        assertEquals("app_token_missing", failure.code)
    }

    @Test
    fun aRequestThatNeverAnswersTimesOut() = runTest {
        val gateway = FakeGateway()
        gateway.hang = true
        val envelope = AppApiEnvelope(source(gateway), timeoutMs = 50)

        val failure = envelope.request("GET", "/app/v1/info") as ApiResult.Failure

        assertEquals(504, failure.status)
        assertEquals("timeout", failure.code)
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
            val old = FakeGateway().apply { appToken = "token-a" }
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
            val rebuilt = FakeGateway().apply { appToken = "token-b" }
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
    fun aRePairDuringAPagedDownloadNeverPutsTheNewTokenOnTheOldLink() = runTest {
        PairingGate.resetForTest()
        try {
            val old = PageGateway(byteArrayOf(9, 8, 7, 6, 5, 4)) { page ->
                // The new pairing is stored after the first page; the download in
                // flight must keep the credential it started with.
                if (page == 2) PairingGate.expect("cert:b")
            }
            old.appToken = "token-a"
            PairingGate.expect("cert:a")
            PairingGate.install(old, "cert:a")
            val envelope = AppApiEnvelope(AppGatewaySource { PairingGate.gateway() })
            val sink = ByteArrayOutputStream()

            val result = envelope.download("/app/v1/calls/c1/audio", emptyMap(), sink)

            assertTrue(result is ApiResult.Success)
            assertArrayEquals(byteArrayOf(9, 8, 7, 6, 5, 4), sink.toByteArray())
            assertEquals(listOf("token-a", "token-a"), old.tokens)
        } finally {
            PairingGate.resetForTest()
        }
    }

    private fun source(gateway: AppGateway?) = AppGatewaySource { gateway }

    private class FakeGateway : AppGateway {
        override var appToken: String? = "app-token"
        override var supportsAttachments: Boolean = false
        var reply = DeviceApiResult(id = "x", status = 200, result = buildJsonObject { })
        var last: DeviceApiRequest? = null
        var hang = false
        var attachment: AttachmentOutcome = AttachmentOutcome.Unsupported
        var attachmentHeader: AttachmentHeader? = null
        var attachmentBytes: ByteArray? = null

        override suspend fun exchange(request: DeviceApiRequest): DeviceApiResult {
            last = request
            if (hang) awaitCancellation()
            return reply
        }

        override suspend fun receiveAttachment(header: AttachmentHeader, sink: OutputStream): AttachmentOutcome {
            attachmentHeader = header
            attachmentBytes?.let { sink.write(it) }
            return attachment
        }
    }

    /** Answers a paged download with the base64 pages the daemon returns on WebSocket. */
    private class PageGateway(
        private val payload: ByteArray,
        private val stopAfterFirstPage: Boolean = false,
        private val onPage: (Int) -> Unit = {}
    ) : AppGateway {
        override var appToken: String? = "app-token"
        override val supportsAttachments: Boolean = false
        val offsets = mutableListOf<String>()
        val tokens = mutableListOf<String>()

        override suspend fun exchange(request: DeviceApiRequest): DeviceApiResult {
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
