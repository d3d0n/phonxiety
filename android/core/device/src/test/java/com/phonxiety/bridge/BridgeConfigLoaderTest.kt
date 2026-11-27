package com.phonxiety.bridge

import android.content.Context
import com.phonxiety.bridge.net.CandidateMerge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The pairing file is written by the host and by the app; the app's own
 * token update must belong to the pairing that is stored and must not drop the
 * addresses the host put there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BridgeConfigLoaderTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val pinA = "a".repeat(64)
    private val pinB = "b".repeat(64)

    private val configFile: File
        get() = File(context.createDeviceProtectedStorageContext().filesDir, "config.json")

    @Before
    fun setUp() {
        BridgeConfigLoader.delete(context)
    }

    @Test
    fun anAppTokenIsStoredOnlyForThePairingThatDeliveredIt() {
        BridgeConfigLoader.write(context, BridgeConfig.Iroh("ticket-a", "token-a", certSha256 = pinA))

        assertEquals(false, BridgeConfigLoader.updateAppToken(context, "cert:$pinB", "token-b"))
        assertNull((BridgeConfigLoader.load(context) as BridgeConfig.Iroh).appToken)

        assertTrue(BridgeConfigLoader.updateAppToken(context, "cert:$pinA", "app-token-a"))
        assertEquals("app-token-a", (BridgeConfigLoader.load(context) as BridgeConfig.Iroh).appToken)
    }

    @Test
    fun aTokenUpdateKeepsTheAddressesTheHostWrote() {
        configFile.parentFile?.mkdirs()
        configFile.writeText(
            JSONObject()
                .put("iroh", "ticket-a")
                .put("token", "token-a")
                .put("cert_sha256", pinA)
                .put("lan", JSONArray(listOf("wss://192.168.1.10:18763/device")))
                .put("tailscale", JSONArray(listOf("wss://100.64.0.2:18763/device")))
                .toString()
        )

        assertTrue(BridgeConfigLoader.updateAppToken(context, "cert:$pinA", "app-token-a"))

        val stored = BridgeConfigLoader.read(context)
        assertEquals("app-token-a", (stored.config as BridgeConfig.Iroh).appToken)
        assertEquals(
            listOf("wss://192.168.1.10:18763/device", "wss://100.64.0.2:18763/device"),
            BridgeConfigLoader.candidates(stored.config, stored.addresses).map { it.url }
        )
    }

    /**
     * The config and the addresses are written back from one parse, so the documents cannot differ:
     * the identity a token was stored for is still the identity and the address set of the file.
     */
    @Test
    fun aTokenUpdateKeepsTheIdentityAndTheAddressesOfTheSameDocument() {
        BridgeConfigLoader.write(
            context,
            BridgeConfig.Iroh("ticket-a", "token-a", certSha256 = pinA),
            PairingAddresses(
                lan = listOf("wss://192.168.1.10:18763/device"),
                tailscale = listOf("wss://100.64.0.2:18763/device")
            )
        )
        // The host rewrites the file to B between the update's read and its write.  An
        // implementation that reads the addresses after this point would write A's identity with
        // B's addresses; the one parse keeps them the same document.
        BridgeConfigLoader.afterReadForTest = { rewriteAsB() }
        try {
            assertTrue(BridgeConfigLoader.updateAppToken(context, "cert:$pinA", "app-token-a"))
        } finally {
            BridgeConfigLoader.afterReadForTest = null
        }

        val stored = BridgeConfigLoader.read(context)
        assertEquals("cert:$pinA", CandidateMerge.identity(stored.config))
        assertEquals("app-token-a", (stored.config as BridgeConfig.Iroh).appToken)
        assertEquals(
            listOf("wss://192.168.1.10:18763/device", "wss://100.64.0.2:18763/device"),
            BridgeConfigLoader.candidates(stored.config, stored.addresses).map { it.url }
        )
    }

    private fun rewriteAsB() {
        configFile.writeText(
            JSONObject()
                .put("iroh", "ticket-b")
                .put("token", "token-b")
                .put("cert_sha256", pinB)
                .put("lan", JSONArray(listOf("wss://192.168.1.20:18763/device")))
                .toString()
        )
    }

    /**
     * A host rewrite between the update's read and its write must not mix two pairings: the token
     * write uses the identity and the addresses of the one parse it validated.
     */
    @Test
    fun aRewriteBetweenTheReadAndTheWriteCannotMixTwoPairings() {
        BridgeConfigLoader.write(
            context,
            BridgeConfig.Iroh("ticket-a", "token-a", certSha256 = pinA),
            PairingAddresses(lan = listOf("wss://192.168.1.10:18763/device"))
        )
        val rewrote = AtomicBoolean(false)
        BridgeConfigLoader.afterReadForTest = {
            rewrote.set(true)
            rewriteAsB()
        }
        try {
            assertTrue(BridgeConfigLoader.updateAppToken(context, "cert:$pinA", "app-token-a"))
        } finally {
            BridgeConfigLoader.afterReadForTest = null
        }
        assertTrue("the rewrite between the read and the write ran", rewrote.get())

        val stored = BridgeConfigLoader.read(context)
        assertEquals("the identity validated is the one written", "cert:$pinA", CandidateMerge.identity(stored.config))
        assertEquals("app-token-a", (stored.config as BridgeConfig.Iroh).appToken)
        assertEquals(
            "the addresses come from the same parse as the identity",
            listOf("wss://192.168.1.10:18763/device"),
            stored.addresses.lan
        )
    }
}
