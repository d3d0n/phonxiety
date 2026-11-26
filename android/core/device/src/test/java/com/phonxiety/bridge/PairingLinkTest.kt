package com.phonxiety.bridge

import com.phonxiety.bridge.net.CandidateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingLinkTest {
    private val pin = "AB".repeat(32)

    /** The bindings cannot load on the JVM, so parsing runs with the ticket accepted. */
    private fun parseIroh(link: String): PairingResult = PairingLink.parse(link) { true }

    @Test
    fun addressPairingCarriesBothTokens() {
        val link = "phonxiety://pair?url=wss%3A%2F%2F192.168.1.10%3A18763%2Fdevice" +
            "&token=device-token&app_token=app-token&cert=${"ab".repeat(32)}"
        val result = PairingLink.parse(link)
        assertTrue(result is PairingResult.Valid)
        val config = (result as PairingResult.Valid).config as BridgeConfig.Address
        assertEquals("wss://192.168.1.10:18763/device", config.url)
        assertEquals("device-token", config.token)
        assertEquals("app-token", config.appToken)
        assertEquals("ab".repeat(32), config.certSha256)
    }

    @Test
    fun anIrohPairingCarriesThePinAndTheOtherAddresses() {
        val result = parseIroh(
            "phonxiety://pair?iroh=endpoint-ticket&token=device-token&app_token=app-token&cert=$pin" +
                "&lan=wss%3A%2F%2F192.168.1.10%3A18763%2Fdevice" +
                "&tailscale=wss%3A%2F%2F100.64.0.2%3A18763%2Fdevice" +
                "&tailscale=wss%3A%2F%2F100.64.0.3%3A18763%2Fdevice"
        )

        assertTrue(result is PairingResult.Valid)
        val valid = result as PairingResult.Valid
        val config = valid.config as BridgeConfig.Iroh
        assertEquals(pin.lowercase(), config.certSha256)
        assertEquals(
            listOf(
                "wss://192.168.1.10:18763/device",
                "wss://100.64.0.2:18763/device",
                "wss://100.64.0.3:18763/device"
            ),
            valid.candidates.map { it.url }
        )
        assertTrue(valid.candidates.all { it.certSha256 == pin.lowercase() })
        assertTrue(valid.candidates.all { it.source == CandidateStore.Source.PAIRED })
    }

    @Test
    fun anOlderIrohQrStillPairsWithoutAddressesOrAPin() {
        val result = parseIroh("phonxiety://pair?iroh=endpoint-ticket&token=device-token")

        assertTrue(result is PairingResult.Valid)
        val valid = result as PairingResult.Valid
        assertNull((valid.config as BridgeConfig.Iroh).certSha256)
        assertTrue(valid.candidates.isEmpty())
    }

    @Test
    fun appTokenIsOptionalForAnOlderEntry() {
        val result = PairingLink.parse("phonxiety://pair?url=wss%3A%2F%2Flocalhost%3A1%2Fdevice&token=t")
        val config = (result as PairingResult.Valid).config as BridgeConfig.Address
        assertNull(config.appToken)
    }

    @Test
    fun aWhitespaceAppTokenIsRefused() {
        val result = PairingLink.parse(
            "phonxiety://pair?url=wss%3A%2F%2Flocalhost%3A1%2Fdevice&token=t&app_token=a%20b"
        )
        assertEquals(BridgeConfigError.TOKEN_INVALID, (result as PairingResult.Invalid).error)
    }

    @Test
    fun aForeignSchemeIsRefused() {
        val result = PairingLink.parse("https://example.com/pair?url=wss%3A%2F%2Flocalhost%3A1&token=t")
        assertEquals(BridgeConfigError.PAIR_LINK_INVALID, (result as PairingResult.Invalid).error)
    }
}
