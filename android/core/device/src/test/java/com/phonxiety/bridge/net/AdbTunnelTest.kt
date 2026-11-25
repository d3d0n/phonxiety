package com.phonxiety.bridge.net

import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adb tunnel's two testable edges: the first frame a tunnel stream must
 * carry, and the idempotent root command sequence that opens adbd on loopback.
 */
class AdbTunnelTest {
    private val adb = """{"type":"tunnel","service":"adb"}""".toByteArray(Charsets.UTF_8)

    private fun frame(kind: Int, payload: ByteArray): ByteArray = Framing.encode(kind, payload)!!

    private fun headerOf(frame: ByteArray): ByteArray = frame.copyOfRange(0, Framing.HEADER_BYTES)

    @Test
    fun adbTunnelRequestIsAccepted() {
        val wire = frame(Framing.KIND_TEXT, adb)
        assertTrue(AdbTunnel.isAdbRequest(adb))
        assertTrue(AdbTunnel.acceptsFirstFrame(headerOf(wire), adb))
    }

    @Test
    fun otherServiceIsRejected() {
        val rtsp = """{"type":"tunnel","service":"rtsp"}""".toByteArray(Charsets.UTF_8)
        assertFalse(AdbTunnel.isAdbRequest(rtsp))
        assertFalse(AdbTunnel.acceptsFirstFrame(headerOf(frame(Framing.KIND_TEXT, rtsp)), rtsp))
    }

    @Test
    fun otherTypeIsRejected() {
        val auth = """{"type":"auth","service":"adb"}""".toByteArray(Charsets.UTF_8)
        assertFalse(AdbTunnel.isAdbRequest(auth))
    }

    @Test
    fun malformedRequestIsRejected() {
        val garbage = "not json".toByteArray(Charsets.UTF_8)
        assertFalse(AdbTunnel.isAdbRequest(garbage))
        assertFalse(AdbTunnel.isAdbRequest(ByteArray(0)))
        assertFalse(AdbTunnel.acceptsFirstFrame(headerOf(frame(Framing.KIND_TEXT, garbage)), garbage))
    }

    @Test
    fun binaryFrameIsRejected() {
        assertFalse(AdbTunnel.acceptsFirstFrame(headerOf(frame(Framing.KIND_BINARY, adb)), adb))
    }

    @Test
    fun malformedHeaderIsRejected() {
        // Truncated header, and a header with an unknown frame kind.
        assertFalse(AdbTunnel.acceptsFirstFrame(headerOf(frame(Framing.KIND_TEXT, adb)).copyOf(3), adb))
        assertFalse(AdbTunnel.acceptsFirstFrame(byteArrayOf(0, 0, 0, 0, 29), adb))
    }

    @Test
    fun payloadShorterThanTheHeaderIsRejected() {
        val wire = frame(Framing.KIND_TEXT, ByteArray(64) { 'x'.code.toByte() })
        assertFalse(AdbTunnel.acceptsFirstFrame(headerOf(wire), adb))
    }

    @Test
    fun freshDeviceGetsFirewallThenPropertyThenRestart() {
        val host = FakeHost(port = "")
        assertTrue(AdbTunnelCommands(host).enable())

        val commands = host.commands
        val acceptLo = " -i lo -p tcp --dport 5555 -j ACCEPT"
        val dropOther = " ! -i lo -p tcp --dport 5555 -j DROP"
        for (table in listOf("iptables", "ip6tables")) {
            assertTrue("$table -w -C INPUT$acceptLo" in commands)
            assertTrue("$table -w -I INPUT 1$acceptLo" in commands)
            assertTrue("$table -w -C INPUT$dropOther" in commands)
            assertTrue("$table -w -I INPUT 1$dropOther" in commands)
        }
        // The port property is read exactly once, after the firewall is in place.
        assertEquals(1, commands.count { it.startsWith("getprop ") })
        assertTrue(commands.indexOf("getprop service.adb.tcp.port") > commands.indexOf("ip6tables -w -I INPUT 1$dropOther"))
        assertTrue(commands.indexOf("setprop service.adb.tcp.port 5555") > commands.indexOf("getprop service.adb.tcp.port"))
        assertEquals(1, commands.count { it == "stop adbd; start adbd" })
        assertTrue(commands.indexOf("stop adbd; start adbd") > commands.indexOf("setprop service.adb.tcp.port 5555"))
    }

    @Test
    fun existingRulesAreNotInsertedAgain() {
        val host = FakeHost(port = "", present = { it.contains("--dport 5555") })
        assertTrue(AdbTunnelCommands(host).enable())

        assertTrue(host.commands.none { it.contains(" -I ") })
        assertTrue(host.commands.any { it == "setprop service.adb.tcp.port 5555" })
    }

    @Test
    fun portAlreadyOpenSetsNothing() {
        val host = FakeHost(port = "5555\n", present = { true })
        assertTrue(AdbTunnelCommands(host).enable())

        assertTrue(host.commands.none { it.startsWith("setprop") })
        assertTrue(host.commands.none { it.startsWith("stop adbd") })
        assertTrue(host.commands.none { it.contains(" -I ") })
    }

    @Test
    fun missingRootFails() {
        val host = FakeHost(root = false)
        assertFalse(AdbTunnelCommands(host).enable())
        assertTrue(host.commands.isEmpty())
    }

    /**
     * A root shell that records every command; `-C` checks succeed for rules
     * [present] calls present.
     */
    private class FakeHost(
        private val port: String = "",
        private val root: Boolean = true,
        private val present: (String) -> Boolean = { false }
    ) : AdbHost {
        val commands = mutableListOf<String>()

        override fun su(command: String): AdbHost.Shell? {
            if (!root) return null
            commands += command
            if (command.startsWith("getprop")) return AdbHost.Shell(0, port)
            if (command.contains(" -C ")) {
                return AdbHost.Shell(if (present(command)) 0 else 1, "")
            }
            return AdbHost.Shell(0, "")
        }

        override fun connect(port: Int): Socket? = null
    }
}
