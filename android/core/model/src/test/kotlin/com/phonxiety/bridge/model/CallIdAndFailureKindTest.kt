package com.phonxiety.bridge.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The value class and the failure classification are model invariants: table-test both. */
class CallIdAndFailureKindTest {

    @Test
    fun aCallIdIsTheSameStringOnTheWire() {
        val json = Json
        assertEquals("\"call-1\"", json.encodeToString(CallId.serializer(), CallId("call-1")))
        assertEquals(CallId("call-1"), json.decodeFromString(CallId.serializer(), "\"call-1\""))
    }

    @Test
    fun failureKindsAreDecidedOnceFromStatusAndCode() {
        data class Case(val status: Int, val code: String?, val expected: FailureKind)

        val cases = listOf(
            // A code that names a transport cause wins over a status that says otherwise.
            Case(500, "transport_error", FailureKind.TRANSPORT),
            Case(400, "link_down", FailureKind.TRANSPORT),
            Case(418, "timeout", FailureKind.TRANSPORT),
            Case(200, "pairing_changed", FailureKind.TRANSPORT),
            // A daemon's own 5xx-shaped codes stay daemon failures even on a 502.
            Case(502, "decode_failed", FailureKind.DAEMON),
            Case(500, "internal_error", FailureKind.DAEMON),
            // Status-only transport failures.
            Case(408, "request_timeout", FailureKind.TRANSPORT),
            Case(502, "bad_gateway", FailureKind.TRANSPORT),
            Case(503, "unavailable", FailureKind.TRANSPORT),
            Case(504, "gateway_timeout", FailureKind.TRANSPORT),
            // A 4xx is the caller's answer.
            Case(400, "invalid_argument", FailureKind.REFUSED),
            Case(401, "unauthorized", FailureKind.REFUSED),
            Case(404, "call_not_found", FailureKind.REFUSED),
            Case(409, "call_active", FailureKind.REFUSED),
            Case(429, "hourly_call_cap", FailureKind.REFUSED),
            // Unknown codes fall back to the status, then to the daemon.
            Case(400, "a_code_this_build_never_saw", FailureKind.REFUSED),
            Case(500, "a_code_this_build_never_saw", FailureKind.DAEMON),
            Case(0, "a_code_this_build_never_saw", FailureKind.DAEMON),
            Case(0, null, FailureKind.DAEMON),
            Case(200, null, FailureKind.DAEMON)
        )

        for (case in cases) {
            assertEquals(
                "status ${case.status}, code ${case.code}",
                case.expected,
                FailureKind.of(case.status, case.code)
            )
        }
    }

    @Test
    fun theValueClassKeepsTheDaemonsSpelling() {
        val id = CallId("00000000-0000-4000-8000-000000000001")
        assertEquals("00000000-0000-4000-8000-000000000001", id.value)
        assertTrue(id == CallId("00000000-0000-4000-8000-000000000001"))
    }
}
