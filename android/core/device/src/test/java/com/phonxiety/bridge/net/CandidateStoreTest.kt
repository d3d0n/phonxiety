package com.phonxiety.bridge.net

import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.model.ConnectionInfo
import com.phonxiety.bridge.model.LinkKind
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val file = File(createTempDirectory("candidates").toFile(), "link-candidates.json")
        file.writeText(
            """{"candidates":[{"url":"$lanA","cert_sha256":"$pinA","source":"daemon"}]}"""
        )
        val store = CandidateStore(file)
        assertNull(store.pairingIdentity())

        val bound = store.bind(CandidateMerge.identity(config("ticket-b", pinB)))

        assertNotNull(bound)
        assertTrue(store.load().isEmpty())
        assertEquals("cert:$pinB", store.pairingIdentity())
    }

    @Test
    fun aLateAnswerFromThePreviousPairingIsNeverStoredUnderTheNewOne() {
        val store = store()
        val configA = config("ticket-a", pinA)
        store.rebind(
            CandidateMerge.identity(configA),
            listOf(StoredCandidate(lanA, pinA, CandidateStore.Source.PAIRED))
        )
        val configB = BridgeConfig.validateIroh("ticket-b", "device-token-b")
        store.rebind(CandidateMerge.identity(configB), emptyList())

        val stored = store.merge(
            CandidateMerge.identity(configA),
            CandidateMerge.learned(ConnectionInfo(lan = listOf(lanA), certificate = pinA))
        )

        assertNull(stored)
        assertTrue(store.load().isEmpty())
        assertEquals(CandidateMerge.identity(configB), store.pairingIdentity())
        assertEquals(
            listOf(LinkKind.IROH),
            CandidatePlanner.plan(configB, store.load(), emptyList(), store.pairingIdentity()).map { it.kind }
        )
    }

    @Test
    fun aCertificateRotationCannotReuseThePreviousDaemonsAddresses() {
        val store = store()
        val before = config("ticket-a", pinA)
        store.rebind(
            CandidateMerge.identity(before),
            listOf(StoredCandidate(lanA, pinA, CandidateStore.Source.PAIRED))
        )
        val rotated = config("ticket-a", pinB)
        store.rebind(CandidateMerge.identity(rotated), emptyList())

        assertNull(
            store.merge(
                CandidateMerge.identity(before),
                CandidateMerge.learned(ConnectionInfo(lan = listOf(lanA), certificate = pinA))
            )
        )
        val plan = CandidatePlanner.plan(rotated, store.load(), emptyList(), store.pairingIdentity())
        assertEquals(listOf(LinkKind.IROH), plan.map { it.kind })
        assertFalse(plan.any { it.url == lanA })
    }

    @Test
    fun aFailedBindingLeavesNoCandidateUsableForAPinlessPairing() {
        val file = File(createTempDirectory("candidates").toFile(), "link-candidates.json")
        file.writeText(
            """{"candidates":[{"url":"$lanA","cert_sha256":"$pinA","source":"daemon"}]}"""
        )
        val store = CandidateStore(file)
        // The write cannot land: the staging path is occupied by a directory.
        assertTrue(File(file.parentFile, file.name + ".tmp").mkdirs())

        val pinlessB = BridgeConfig.validateIroh("ticket-b", "device-token-b")
        assertNull(store.bind(CandidateMerge.identity(pinlessB)))

        // The identity-less document survives, but a pinless pairing never
        // infers its single pin from it: A's address is not in the plan.
        assertNull(store.pairingIdentity())
        val plan = CandidatePlanner.plan(pinlessB, store.load(), emptyList(), store.pairingIdentity())
        assertEquals(listOf(LinkKind.IROH), plan.map { it.kind })
    }

    @Test
    fun concurrentMergesCannotBringThePreviousPairingsAddressesBack() {
        val store = store()
        val identityA = CandidateMerge.identity(config("ticket-a", pinA))
        val identityB = CandidateMerge.identity(config("ticket-b", pinB))
        store.rebind(identityA, listOf(StoredCandidate(lanA, pinA, CandidateStore.Source.PAIRED)))
        val start = CountDownLatch(1)
        val writers = (1..8).map { index ->
            thread {
                start.await()
                repeat(20) {
                    store.merge(
                        identityA,
                        listOf(StoredCandidate("wss://192.168.9.$index:18763/device", pinA, CandidateStore.Source.LEARNED))
                    )
                }
            }
        }
        start.countDown()
        repeat(20) { store.rebind(identityB, emptyList()) }
        writers.forEach { it.join() }

        assertEquals(identityB, store.pairingIdentity())
        assertTrue(store.load().none { it.certSha256 == pinA })
    }
}
