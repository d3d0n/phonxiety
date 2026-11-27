package com.phonxiety.bridge.data

import com.phonxiety.bridge.model.CallId
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The call id is a value class in Kotlin and a plain string on the wire. These tests pin both
 * halves: the production encoder writes the id into the path and the delete body unchanged, and
 * the path rule refuses the same characters it always refused.
 */
class CallIdWireTest {

    @Test
    fun theEncoderWritesTheCallIdIntoTheSamePathAndDeleteBody() = runTest {
        val transport = Recording()
        val api = DefaultDaemonApi(transport)

        api.call(CallId("call-1"))
        api.deleteCall(CallId("call-1"))

        assertEquals("/app/v1/calls/call-1", transport.requests[0].path)
        assertEquals("/app/v1/calls/call-1", transport.requests[1].path)
        assertEquals("call-1", transport.requests[1].body!!["confirm"]!!.jsonPrimitive.content)
    }

    @Test
    fun anUnsafeCallIdNeverReachesTheTransport() = runTest {
        val transport = Recording()
        val api = DefaultDaemonApi(transport)

        for (unsafe in listOf("", "a/b", "a b", "a?b", "a#b", "a\u0000b")) {
            try {
                api.call(CallId(unsafe))
                fail("crossed the path with '$unsafe'")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(emptyList<Pair<String, String>>(), transport.requests)
    }

    @Test
    fun anUnsafeBundleIdNeverReachesTheTransport() = runTest {
        val transport = Recording()
        val api = DefaultDaemonApi(transport)
        val sink = ByteArrayOutputStream()

        for (unsafe in listOf("", "a/b", "a b")) {
            try {
                api.downloadBundle(unsafe, sink)
                fail("crossed the path with '$unsafe'")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        assertTrue(transport.downloads.isEmpty())
    }

    private class Recording : AppApi {
        data class Request(val method: String, val path: String, val body: JsonObject?)

        val requests = mutableListOf<Request>()
        val downloads = mutableListOf<String>()

        override suspend fun request(
            method: String,
            path: String,
            query: Map<String, String>,
            body: JsonObject?
        ): ApiResult {
            requests += Request(method, path, body)
            return ApiResult.Success(200, buildJsonObject { put("call_id", "call-1") })
        }

        override suspend fun download(path: String, query: Map<String, String>, sink: OutputStream): ApiResult {
            downloads += path
            return ApiResult.Success(200, JsonObject(emptyMap()))
        }
    }
}
