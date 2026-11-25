package com.phonxiety.bridge.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mDNS advertisement as the phone reads it: service name, host and TXT fields. */
class MdnsAdvertisementTest {
    private val pin = "c".repeat(64)

    @Test
    fun txtFieldsAreLowercasedAndTrimmed() {
        val fields = MdnsAdvertisement.fields(
            mapOf(
                "CERT" to " $pin ".toByteArray(Charsets.UTF_8),
                "proto" to "3".toByteArray(Charsets.UTF_8)
            )
        )
        assertEquals(pin, MdnsAdvertisement.certOf(fields))
        assertEquals(3, MdnsAdvertisement.protocolOf(fields))
        assertEquals("3", fields["proto"])
    }

    @Test
    fun aMalformedPinIsNotACertificate() {
        assertEquals(null, MdnsAdvertisement.certOf(MdnsAdvertisement.fields(mapOf("cert" to "abc".toByteArray()))))
        assertEquals(null, MdnsAdvertisement.certOf(MdnsAdvertisement.fields(mapOf("cert" to "z".repeat(64).toByteArray()))))
        assertEquals(null, MdnsAdvertisement.certOf(emptyMap()))
        assertNull(MdnsAdvertisement.protocolOf(MdnsAdvertisement.fields(mapOf("proto" to "two".toByteArray()))))
    }

    @Test
    fun aResolvedServiceBecomesADaemon() {
        val daemon = MdnsAdvertisement.daemonOf(
            serviceName = "phonxiety-daemon",
            host = "192.168.1.10.",
            port = 18763,
            fields = mapOf(MdnsAdvertisement.TXT_CERT to pin, MdnsAdvertisement.TXT_PROTOCOL to "3")
        )
        assertEquals("192.168.1.10", daemon?.host)
        assertEquals(pin, daemon?.certSha256)
        assertEquals("wss://192.168.1.10:18763/device", daemon?.url)
    }

    @Test
    fun anIpv6HostKeepsItsBracketsInTheUrl() {
        val daemon = MdnsAdvertisement.daemonOf("n", "fd7a:115c:a1e0::9", 18763, mapOf(MdnsAdvertisement.TXT_CERT to pin))
        assertEquals("wss://[fd7a:115c:a1e0::9]:18763/device", daemon?.url)
        assertTrue(Tailnet.isTailnetHost(daemon?.host))
    }

    @Test
    fun aServiceWithoutHostOrPortIsDropped() {
        assertNull(MdnsAdvertisement.daemonOf("n", null, 18763, mapOf(MdnsAdvertisement.TXT_CERT to pin)))
        assertNull(MdnsAdvertisement.daemonOf("n", "", 18763, mapOf(MdnsAdvertisement.TXT_CERT to pin)))
        assertNull(MdnsAdvertisement.daemonOf("n", "192.168.1.10", 0, mapOf(MdnsAdvertisement.TXT_CERT to pin)))
        assertNull(MdnsAdvertisement.daemonOf("n", "192.168.1.10", 70000, mapOf(MdnsAdvertisement.TXT_CERT to pin)))
    }

    @Test
    fun anAdvertisementWithoutAPinIsStillResolvedButNeverTrusted() {
        val daemon = MdnsAdvertisement.daemonOf("n", "192.168.1.10", 18763, emptyMap())
        assertNull(daemon?.certSha256)
        assertFalse(daemon == null)
    }
}
