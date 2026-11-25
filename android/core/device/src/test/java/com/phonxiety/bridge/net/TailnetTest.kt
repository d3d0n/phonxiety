package com.phonxiety.bridge.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Address classification: a WebSocket target inside the tailnet ranges is a
 * Tailscale-app link, a private address is the LAN, and a name under
 * `.ts.net` is the tailnet's MagicDNS.
 */
class TailnetTest {
    @Test
    fun tailnetIpv4RangeIsCgnat() {
        assertTrue(Tailnet.isTailnetHost("100.64.0.1"))
        assertTrue(Tailnet.isTailnetHost("100.100.100.5"))
        assertTrue(Tailnet.isTailnetHost("100.127.255.254"))
        assertFalse(Tailnet.isTailnetHost("100.63.255.255"))
        assertFalse(Tailnet.isTailnetHost("100.128.0.1"))
        assertFalse(Tailnet.isTailnetHost("10.0.0.1"))
        assertFalse(Tailnet.isTailnetHost("192.168.1.10"))
    }

    @Test
    fun tailnetIpv6PrefixIsRecognized() {
        assertTrue(Tailnet.isTailnetHost("fd7a:115c:a1e0::1"))
        assertTrue(Tailnet.isTailnetHost("[fd7a:115c:a1e0:ab12::7]"))
        assertTrue(Tailnet.isTailnetHost("FD7A:115C:A1E0::9"))
        assertEquals(HostScope.LOCAL, Tailnet.scopeOf("fd00::1"))
        assertEquals(HostScope.LOCAL, Tailnet.scopeOf("fe80::1"))
        assertEquals(HostScope.PUBLIC, Tailnet.scopeOf("2606:4700:4700::1111"))
    }

    @Test
    fun magicDnsNamesAreTailnet() {
        assertTrue(Tailnet.isTailnetHost("daemon.tail1a2b3.ts.net"))
        assertTrue(Tailnet.isTailnetHost("DAEMON.TS.NET"))
        assertEquals(HostScope.PUBLIC, Tailnet.scopeOf("daemon.example.com"))
        assertEquals(HostScope.UNKNOWN, Tailnet.scopeOf("localhost"))
        assertEquals(HostScope.LOCAL, Tailnet.scopeOf("127.0.0.1"))
    }

    @Test
    fun localRangesAreLocal() {
        assertFalse(Tailnet.isTailnetHost("172.16.0.1"))
        assertEquals(HostScope.LOCAL, Tailnet.scopeOf("172.31.255.1"))
        assertEquals(HostScope.PUBLIC, Tailnet.scopeOf("172.32.0.1"))
        assertEquals(HostScope.LOCAL, Tailnet.scopeOf("169.254.10.10"))
        assertEquals(HostScope.PUBLIC, Tailnet.scopeOf("8.8.8.8"))
    }

    @Test
    fun hostsAreExtractedFromUrls() {
        assertEquals("100.64.1.2", Tailnet.hostOf("wss://100.64.1.2:18763/device"))
        assertEquals("daemon.tail1.ts.net", Tailnet.hostOf("wss://daemon.tail1.ts.net/device"))
        assertEquals("fd7a:115c:a1e0::1", Tailnet.hostOf("wss://[fd7a:115c:a1e0::1]:18763/device"))
        assertEquals("host", Tailnet.hostOf("wss://user@host:1234/device?x=1"))
        assertNull(Tailnet.hostOf("not a url"))
        assertNull(Tailnet.hostOf("wss://"))
    }
}
