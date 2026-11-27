package com.phonxiety.bridge.net

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Relay URL validation and the mode that picks a list; and the candidate document on disk. */
class RelayOptionsTest {
    private val pin = "d".repeat(64)

    @Test
    fun onlyWellFormedRelayUrlsSurvive() {
        assertTrue(RelayOptions.isRelayUrl("https://relay.example"))
        assertTrue(RelayOptions.isRelayUrl("https://relay.example:8443/relay"))
        assertTrue(RelayOptions.isRelayUrl("http://10.0.0.5:3340"))
        assertTrue(RelayOptions.isRelayUrl("https://[fd00::1]:443"))
        assertFalse(RelayOptions.isRelayUrl("relay.example"))
        assertFalse(RelayOptions.isRelayUrl("https://"))
        assertFalse(RelayOptions.isRelayUrl("https://user@relay.example"))
        assertFalse(RelayOptions.isRelayUrl("https://relay.example/ path"))
        assertFalse(RelayOptions.isRelayUrl(""))
    }

    @Test
    fun theModePicksTheListItNames() {
        val custom = listOf("https://mine.example", "not a url")
        val daemon = listOf("https://daemon.example")
        assertEquals(emptyList<String>(), RelayOptions.urls(RelayModeSetting.DEFAULT, custom, daemon))
        assertEquals(emptyList<String>(), RelayOptions.urls(RelayModeSetting.DISABLED, custom, daemon))
        assertEquals(listOf("https://daemon.example"), RelayOptions.urls(RelayModeSetting.DAEMON, custom, daemon))
        assertEquals(listOf("https://mine.example"), RelayOptions.urls(RelayModeSetting.CUSTOM, custom, daemon))
        assertEquals(RelayModeSetting.DAEMON, RelayModeSetting.of(" DAEMON "))
        assertEquals(RelayModeSetting.DEFAULT, RelayModeSetting.of("whatever"))
    }

    @Test
    fun theUrlListRoundTripsThroughText() {
        val text = "https://a.example\nhttps://b.example\n"
        assertEquals(listOf("https://a.example", "https://b.example"), RelayOptions.split(text))
        assertEquals("https://a.example\nhttps://b.example", RelayOptions.join(listOf("https://a.example", "https://b.example")))
        assertEquals(emptyList<String>(), RelayOptions.split(""))
    }

    @Test
    fun theCandidateDocumentKeepsPinnedAddressesOnce() {
        val dir = createTempDirectory("candidates").toFile()
        val store = CandidateStore(File(dir, "link-candidates.json"))
        assertTrue(store.load().isEmpty())
        store.bind("cert:$pin")
        val stored = store.merge(
            "cert:$pin",
            listOf(
                StoredCandidate("wss://192.168.1.10:18763/device", pin, CandidateStore.Source.MDNS),
                StoredCandidate("wss://192.168.1.10:18763/device", pin, CandidateStore.Source.LEARNED),
                StoredCandidate("wss://100.64.0.9:18763/device", pin, CandidateStore.Source.LEARNED),
                StoredCandidate("wss://192.168.1.11:18763/device", null, CandidateStore.Source.MDNS),
                StoredCandidate("https://not-a-link.example", pin, CandidateStore.Source.LEARNED)
            )
        )!!
        assertEquals(2, stored.size)
        assertEquals(CandidateStore.Source.LEARNED, stored[0].source)
        assertEquals(listOf("wss://192.168.1.10:18763/device", "wss://100.64.0.9:18763/device"), store.load().map { it.url })
        dir.deleteRecursively()
    }
}
