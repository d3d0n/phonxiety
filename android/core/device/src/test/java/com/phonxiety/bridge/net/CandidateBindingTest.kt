package com.phonxiety.bridge.net

import android.content.Context
import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.BridgeConfigLoader
import com.phonxiety.bridge.model.ConnectionInfo
import java.io.File
import kotlin.io.path.createTempDirectory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The send boundary of link selection: what the credential-bearing probe is
 * asked to open.  A late answer or a failed binding must leave the previous
 * daemon's address out of that set, or the new pairing's device token would be
 * sent to the previous daemon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CandidateBindingTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val pinA = "a".repeat(64)
    private val pinB = "b".repeat(64)
    private val lanA = "wss://192.168.1.10:18763/device"
    private val lanB = "wss://192.168.1.20:18763/device"

    private fun storeFile(): File = File(createTempDirectory("candidates").toFile(), "link-candidates.json")

    /**
     * Runs one selection round with [token] in the probe and returns every
     * `(address, token)` the probe was asked to handshake.
     */
    private fun probed(config: BridgeConfig, store: CandidateStore, token: String): List<Pair<String, String>> {
        val seen = mutableListOf<Pair<String, String>>()
        val prober = CandidateProbe { candidate ->
            val url = candidate.url ?: candidate.id
            seen += url to token
            ProbeReport(candidate.id, passes = 0, p50RttMs = null)
        }
        val factory = CandidateLinkFactory(
            context = context,
            config = config,
            selector = LinkSelector(),
            prober = prober,
            store = store,
            mode = { ConnectionMode.AUTO },
            ownsCall = { false },
            duringCall = { false },
            beginIdleLinkAction = { null }
        )
        try {
            factory.considerSwitch()
        } finally {
            factory.shutdown()
        }
        return seen
    }

    @Test
    fun aLateAnswerFromThePreviousDaemonIsNeverProbedWithTheNewPairing() {
        val store = CandidateStore(storeFile())
        val configA = BridgeConfig.validateIroh("ticket-a", "token-a", certSha256 = pinA)
        store.rebind(
            CandidateMerge.identity(configA),
            listOf(StoredCandidate(lanA, pinA, CandidateStore.Source.PAIRED))
        )
        val configB = BridgeConfig.validateIroh("ticket-b", "token-b")
        store.rebind(CandidateMerge.identity(configB), emptyList())

        // A's refresh lands after the re-pair; it carries A's identity and pin.
        assertNull(
            CandidateMerge.store(
                store,
                ConnectionInfo(lan = listOf(lanA), certificate = pinA),
                CandidateMerge.identity(configA)
            )
        )

        assertEquals(emptyList<Pair<String, String>>(), probed(configB, store, "token-b"))
    }

    @Test
    fun aRotatedCertificateLeavesThePreviousCertificateAddressOutOfTheProbe() {
        val store = CandidateStore(storeFile())
        val before = BridgeConfig.validateIroh("ticket-a", "token-a", certSha256 = pinA)
        store.rebind(
            CandidateMerge.identity(before),
            listOf(StoredCandidate(lanA, pinA, CandidateStore.Source.PAIRED))
        )
        val rotated = BridgeConfig.validateIroh("ticket-a", "token-a", certSha256 = pinB)
        store.rebind(CandidateMerge.identity(rotated), emptyList())

        assertEquals(emptyList<Pair<String, String>>(), probed(rotated, store, "token-a"))
    }

    @Test
    fun theHostWrittenPairingAddressesEnterThroughTheSameBinding() {
        val configFile = File(context.createDeviceProtectedStorageContext().filesDir, "config.json")
        configFile.parentFile?.mkdirs()
        configFile.writeText(
            JSONObject()
                .put("iroh", "ticket-b")
                .put("token", "token-b")
                .put("cert_sha256", pinB)
                .put("lan", JSONArray(listOf(lanB)))
                .put("tailscale", JSONArray(listOf("wss://100.64.0.2:18763/device")))
                .toString()
        )
        try {
            val loaded = BridgeConfigLoader.read(context)
            val store = CandidateStore(storeFile())
            val bound = store.rebind(
                CandidateMerge.identity(loaded.config),
                BridgeConfigLoader.candidates(loaded.config, loaded.addresses)
            )

            assertTrue(bound != null)
            val seen = probed(loaded.config, store, "token-b")
            assertTrue(seen.contains(lanB to "token-b"))
            // Nothing from an earlier pairing is in the file or the store.
            assertEquals("cert:$pinB", store.pairingIdentity())
        } finally {
            configFile.delete()
        }
    }
}
