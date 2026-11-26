package com.phonxiety.bridge.net

import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.model.LinkKind
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Addresses are bound to the pairing that learned them.  Pairing to another
 * daemon replaces them atomically, so no address or pin of the previous daemon
 * can reach the candidate plan that links are built from.
 */
class CandidateStoreTest {
    private val pinA = "a".repeat(64)
    private val pinB = "b".repeat(64)
    private val lanA = "wss://192.168.1.10:18763/device"
    private val lanB = "wss://192.168.1.20:18763/device"

    private fun store(): CandidateStore = CandidateStore(File(createTempDirectory("candidates").toFile(), "link-candidates.json"))

    private fun config(ticket: String, pin: String) = BridgeConfig.validateIroh(ticket, "device-token", certSha256 = pin)

    @Test
    fun rePairingToAnotherDaemonDropsEveryAddressOfThePreviousOne() {
        val store = store()
        store.rebind(
            CandidateMerge.identity(config("ticket-a", pinA)),
            listOf(StoredCandidate(lanA, pinA, CandidateStore.Source.PAIRED))
        )

        val configB = config("ticket-b", pinB)
        store.rebind(
            CandidateMerge.identity(configB),
            listOf(StoredCandidate(lanB, pinB, CandidateStore.Source.PAIRED))
        )

        assertEquals(listOf(lanB), store.load().map { it.url })

        // The plan a link factory builds from: nothing of A survives, not even
        // an mDNS advertisement carrying A's certificate.
        val mdnsA = DiscoveredDaemon("192.168.1.10", 18763, pinA, 3, "phonxiety-a")
        val plan = CandidatePlanner.plan(
            configB,
            store.load(),
            listOf(mdnsA),
            store.pairingIdentity()
        )
        assertEquals(listOf(LinkKind.IROH, LinkKind.WEBSOCKET), plan.map { it.kind })
        assertEquals(lanB, plan[1].url)
        assertFalse(plan.any { it.certSha256 == pinA || it.url == lanA })
    }

    @Test
    fun aStoreBoundToAnotherPairingContributesNothingBeforeItIsRebound() {
        val store = store()
        store.rebind(
            "cert:$pinA",
            listOf(StoredCandidate(lanA, pinA, CandidateStore.Source.PAIRED))
        )
        val configB = config("ticket-b", pinB)

        val plan = CandidatePlanner.plan(configB, store.load(), emptyList(), store.pairingIdentity())

        assertEquals(listOf(LinkKind.IROH), plan.map { it.kind })
    }

    @Test
    fun rePairingTheSameDaemonKeepsItsAddresses() {
        val store = store()
        val before = config("ticket-a", pinA)
        store.rebind(
            CandidateMerge.identity(before),
            listOf(StoredCandidate(lanA, pinA, CandidateStore.Source.PAIRED))
        )

        // A token rotation writes a new config for the same certificate, and an
        // older QR for it may carry no addresses at all.
        val rotated = BridgeConfig.validateIroh("ticket-a", "new-device-token", certSha256 = pinA)
        store.rebind(CandidateMerge.identity(rotated), emptyList())

        assertEquals(listOf(lanA), store.load().map { it.url })
        assertEquals(CandidateMerge.identity(before), store.pairingIdentity())
    }

    @Test
    fun aDocumentWrittenBeforeIdentitiesIsReplacedByTheFirstBinding() {
        val store = store()
        store.merge(listOf(StoredCandidate(lanA, pinA, CandidateStore.Source.LEARNED)))
        assertEquals(null, store.pairingIdentity())

        store.bind(CandidateMerge.identity(config("ticket-b", pinB)))

        assertTrue(store.load().isEmpty())
        assertEquals("cert:$pinB", store.pairingIdentity())
    }
}
