package com.phonxiety.bridge.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's own LAN measurement: the prober refuses to guess when it cannot authenticate. */
class LanPathProberTest {
    @Test
    fun `no address means nothing to probe`() {
        assertTrue(LanPathProber.probe(emptyList(), "cc".repeat(32), "token").isEmpty())
    }

    @Test
    fun `a missing pin reports unreachable without opening a socket`() {
        val results = LanPathProber.probe(listOf("wss://192.168.1.10:18763/device"), null, "token")
        assertEquals(1, results.size)
        assertFalse(results.single().reachable)
        assertNull(results.single().rttMs)
    }

    @Test
    fun `at most four addresses are tried`() {
        val urls = (1..6).map { "wss://192.168.1.$it:18763/device" }
        val results = LanPathProber.probe(urls, null, "token")
        assertEquals(4, results.size)
        assertEquals(urls.take(4), results.map { it.address })
    }
}
