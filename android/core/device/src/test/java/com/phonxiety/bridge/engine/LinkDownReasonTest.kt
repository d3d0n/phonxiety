package com.phonxiety.bridge.engine

import com.phonxiety.bridge.EngineStateStore
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.LinkDownReason
import com.phonxiety.bridge.model.LinkError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The typed link-down at the wire and the words a user reads.
 *
 * The encoded error is the serialized carrier: `LinkError` is what
 * `BridgeState.lastError` stores, and the encoded field set and the spelling of
 * `code` are what surfaces, the Doctor and any stored snapshot read.  The two
 * typed fields are transient, so the encoding keeps exactly the three fields
 * it has always had.
 */
class LinkDownReasonTest {
    /** The production outbound shape: defaults encoded, nulls dropped. */
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private fun encode(error: LinkError): JsonObject = json.parseToJsonElement(json.encodeToString(LinkError.serializer(), error)).jsonObject

    /**
     * The spelling every value has always had.  Written out literally so the
     * test pins the wire, not the enum agreeing with itself: a renamed value
     * fails here before a daemon or a stored snapshot ever sees it.
     */
    private val historicalWire = mapOf(
        LinkDownReason.HOST_TIMEOUT to "host_timeout",
        LinkDownReason.CONNECT_FAILED to "connect_failed",
        LinkDownReason.CONNECT_TIMEOUT to "connect_timeout",
        LinkDownReason.HELLO_FAILED to "hello_failed",
        LinkDownReason.CLOSED to "closed",
        LinkDownReason.CLOSING to "closing",
        LinkDownReason.PROTOCOL to "protocol",
        LinkDownReason.WRITE_FAILED to "write_failed",
        LinkDownReason.RELAY_ONLY to "relay_only",
        LinkDownReason.CONTROL_BACKLOG to "control_backlog",
        LinkDownReason.NETWORK_CHANGED to "network_changed",
        LinkDownReason.NETWORK_LOST to "network_lost",
        LinkDownReason.CONNECTION_MODE_CHANGED to "connection_mode_changed",
        LinkDownReason.CANDIDATE_BETTER to "candidate_better",
        LinkDownReason.CAPABILITIES_CHANGED to "capabilities_changed",
        LinkDownReason.INVALID_URL to "invalid_url",
        LinkDownReason.FAILURE to "failure",
        LinkDownReason.CREATION_FAILED to "creation_failed",
        LinkDownReason.NO_CANDIDATE to "no_candidate",
        LinkDownReason.IROH_UNAVAILABLE to "iroh_unavailable",
        LinkDownReason.OTHER to "other"
    )

    @Test
    fun everyReasonKeepsItsHistoricalWireSpelling() {
        assertEquals("every value is pinned", LinkDownReason.entries.toSet(), historicalWire.keys)
        for (reason in LinkDownReason.entries) {
            assertEquals("wire of $reason", historicalWire.getValue(reason), reason.wire)
        }
    }

    @Test
    fun everyReasonKeepsItsWireSpellingInTheEncodedError() {
        for (reason in LinkDownReason.entries) {
            val error = LinkError(
                code = reason.wire,
                message = linkMessage(reason, null),
                atEpochMs = 7L,
                reason = reason
            )
            val encoded = encode(error)
            assertEquals(
                "encoded code of $reason",
                historicalWire.getValue(reason),
                encoded.getValue("code").jsonPrimitive.content
            )
            assertEquals(
                "the typed fields stay out of the encoding of $reason",
                setOf("code", "message", "atEpochMs"),
                encoded.keys
            )
        }
    }

    @Test
    fun theEncodedLinkErrorIsTheHistoricalThreeFields() {
        val wire = json.encodeToString(
            LinkError.serializer(),
            LinkError(
                code = LinkDownReason.HOST_TIMEOUT.wire,
                message = linkMessage(LinkDownReason.HOST_TIMEOUT, null),
                atEpochMs = 0,
                reason = LinkDownReason.HOST_TIMEOUT
            )
        )
        assertEquals(
            """{"code":"host_timeout","message":"the daemon stopped answering","atEpochMs":0}""",
            wire
        )
    }

    @Test
    fun theStateReportCarriesTheSameCodeForEveryReason() {
        for (reason in LinkDownReason.entries) {
            val state = BridgeState(
                lastError = LinkError(
                    code = reason.wire,
                    message = linkMessage(reason, null),
                    atEpochMs = 1,
                    reason = reason
                )
            )
            val encoded = json.parseToJsonElement(json.encodeToString(BridgeState.serializer(), state)).jsonObject
            val error = encoded.getValue("lastError").jsonObject
            assertEquals("state code of $reason", reason.wire, error.getValue("code").jsonPrimitive.content)
            assertEquals("state shape of $reason", setOf("code", "message", "atEpochMs"), error.keys)
        }
    }

    @Test
    fun everyReasonHasItsOwnUserMessage() {
        assertEquals("the daemon stopped answering", linkMessage(LinkDownReason.HOST_TIMEOUT, null))
        assertEquals("cannot reach the daemon", linkMessage(LinkDownReason.CONNECT_FAILED, null))
        assertEquals("the connection attempt timed out", linkMessage(LinkDownReason.CONNECT_TIMEOUT, null))
        assertEquals("the link could not complete its handshake", linkMessage(LinkDownReason.HELLO_FAILED, null))
        assertEquals("the daemon closed the link", linkMessage(LinkDownReason.CLOSED, null))
        assertEquals("the daemon closed the link", linkMessage(LinkDownReason.CLOSING, null))
        assertEquals("the daemon spoke an unknown protocol", linkMessage(LinkDownReason.PROTOCOL, null))
        assertEquals("the link stopped accepting data", linkMessage(LinkDownReason.WRITE_FAILED, null))
        assertEquals("no direct path is available, only a relay", linkMessage(LinkDownReason.RELAY_ONLY, null))
        assertEquals("the link stopped taking messages", linkMessage(LinkDownReason.CONTROL_BACKLOG, null))
        assertEquals("the network changed", linkMessage(LinkDownReason.NETWORK_CHANGED, null))
        assertEquals("the network is gone", linkMessage(LinkDownReason.NETWORK_LOST, null))
        assertEquals("the connection mode changed", linkMessage(LinkDownReason.CONNECTION_MODE_CHANGED, null))
        assertEquals("a better path was measured", linkMessage(LinkDownReason.CANDIDATE_BETTER, null))
        assertEquals("the phone's call permissions changed", linkMessage(LinkDownReason.CAPABILITIES_CHANGED, null))
        assertEquals("the link address is not valid", linkMessage(LinkDownReason.INVALID_URL, null))
        assertEquals("the link failed", linkMessage(LinkDownReason.FAILURE, null))
        assertEquals("the link could not be created", linkMessage(LinkDownReason.CREATION_FAILED, null))
        assertEquals("no connection path is known", linkMessage(LinkDownReason.NO_CANDIDATE, null))
        assertEquals("this connection route is not available", linkMessage(LinkDownReason.IROH_UNAVAILABLE, null))
        assertEquals("the link closed for an unknown reason", linkMessage(LinkDownReason.OTHER, null))
        assertEquals("timed out by peer", linkMessage(LinkDownReason.OTHER, "timed out by peer"))
    }

    /**
     * An engine error code is not a link-down: it keeps its own code, its own
     * message and no typed reason, so `audio_error` survives unchanged.
     */
    @Test
    fun engineErrorCodesStillSurfaceUnchanged() {
        val store = EngineStateStore()
        store.error("audio_error", "the call audio failed: start_failed")
        val error = store.state.value.lastError!!
        assertEquals("audio_error", error.code)
        assertEquals("the call audio failed: start_failed", error.message)
        assertNull(error.reason)
        assertNull(error.detail)

        val encoded = encode(error)
        assertEquals("audio_error", encoded.getValue("code").jsonPrimitive.content)
        assertEquals(
            "the call audio failed: start_failed",
            encoded.getValue("message").jsonPrimitive.content
        )
        assertEquals(setOf("code", "message", "atEpochMs"), encoded.keys)
    }
}
