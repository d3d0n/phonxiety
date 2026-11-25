package com.phonxiety.bridge.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which addresses the phone dials, in which order: the ticket and the
 * endpoint cache merged, subnet matches first, ports and IPv6 scopes intact,
 * and never more than four.
 */
class IrohAddressPlanTest {
    @Test
    fun aTicketAloneIsDialedInItsOwnOrder() {
        val plan = IrohAddressPlanner.plan(
            ticketAddresses = listOf("10.0.0.2:5000", "192.168.1.20:5000"),
            ticketRelay = "https://relay.example",
            cachedAddresses = null,
            cachedRelay = null,
            subnets = null
        )

        assertEquals("https://relay.example", plan.relayUrl)
        assertEquals(listOf("10.0.0.2:5000", "192.168.1.20:5000"), plan.addresses)
    }

    @Test
    fun duplicateTicketAndCacheAddressesCollapse() {
        val plan = IrohAddressPlanner.plan(
            ticketAddresses = listOf("10.0.0.2:5000"),
            ticketRelay = null,
            cachedAddresses = listOf("10.0.0.2:5000", "10.0.0.3:5000"),
            cachedRelay = null,
            subnets = null
        )

        assertEquals(listOf("10.0.0.2:5000", "10.0.0.3:5000"), plan.addresses)
    }

    @Test
    fun addressesOnTheActiveSubnetComeFirst() {
        val plan = IrohAddressPlanner.plan(
            ticketAddresses = listOf("203.0.113.9:5000", "192.168.1.20:5000"),
            ticketRelay = null,
            cachedAddresses = listOf("198.51.100.4:5000"),
            cachedRelay = null,
            subnets = listOf(IrohSubnet(byteArrayOf(192.toByte(), 168.toByte(), 1, 0), 24))
        )

        assertEquals(
            listOf("192.168.1.20:5000", "198.51.100.4:5000", "203.0.113.9:5000"),
            plan.addresses
        )
    }

    @Test
    fun unavailableSubnetsKeepPrivateAddresses() {
        val plan = IrohAddressPlanner.plan(
            ticketAddresses = listOf("10.0.0.2:5000"),
            ticketRelay = null,
            cachedAddresses = listOf("192.168.1.20:5000"),
            cachedRelay = null,
            subnets = null
        )

        assertEquals(listOf("192.168.1.20:5000", "10.0.0.2:5000"), plan.addresses)
    }

    @Test
    fun ipv6AddressesKeepTheirPortAndScopeAndDedupeCanonically() {
        val plan = IrohAddressPlanner.plan(
            ticketAddresses = listOf("[fe80::1%wlan0]:5000", "[2001:db8::2]:5000"),
            ticketRelay = null,
            cachedAddresses = listOf("[fe80:0:0:0:0:0:0:1%wlan0]:5000"),
            cachedRelay = null,
            subnets = null
        )

        // The cache is dialled first, so its spelling of the same endpoint wins;
        // the expanded and compressed forms of one address collapse.
        assertEquals(
            listOf("[fe80:0:0:0:0:0:0:1%wlan0]:5000", "[2001:db8::2]:5000"),
            plan.addresses
        )
    }

    @Test
    fun onlyFourAddressesAreDialed() {
        val ticket = (1..6).map { "10.0.0.$it:5000" }
        val plan = IrohAddressPlanner.plan(
            ticketAddresses = ticket,
            ticketRelay = null,
            cachedAddresses = listOf("192.168.1.20:5000"),
            cachedRelay = null,
            subnets = null
        )

        assertEquals(listOf("192.168.1.20:5000") + ticket.take(3), plan.addresses)
        assertEquals(IrohAddressPlanner.MAX_ADDRESSES, plan.addresses.size)
    }

    @Test
    fun theCachedRelayFillsInWhenTheTicketHasNone() {
        val plan = IrohAddressPlanner.plan(
            ticketAddresses = emptyList(),
            ticketRelay = null,
            cachedAddresses = emptyList(),
            cachedRelay = "https://cached.example",
            subnets = null
        )

        assertEquals("https://cached.example", plan.relayUrl)
        assertEquals(emptyList<String>(), plan.addresses)
    }

    @Test
    fun anAddressWithoutAHostIsNotDialed() {
        val plan = IrohAddressPlanner.plan(
            ticketAddresses = listOf(":5000", ""),
            ticketRelay = null,
            cachedAddresses = null,
            cachedRelay = null,
            subnets = null
        )

        assertNull(plan.relayUrl)
        assertEquals(emptyList<String>(), plan.addresses)
    }
}
