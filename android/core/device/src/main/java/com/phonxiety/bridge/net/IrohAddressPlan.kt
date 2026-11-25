package com.phonxiety.bridge.net

/**
 * One prefix of the active network, as address bytes plus the prefix length.
 * Keeping it as plain bytes lets the planner stay free of Android types.
 */
internal class IrohSubnet(private val network: ByteArray, private val prefix: Int) {
    /** True when [address] of the same family lies inside this prefix. */
    fun contains(address: ByteArray): Boolean {
        if (address.size != network.size) return false
        val bits = prefix.coerceIn(0, network.size * 8)
        var remaining = bits
        var index = 0
        while (remaining >= 8) {
            if (network[index] != address[index]) return false
            index++
            remaining -= 8
        }
        if (remaining == 0) return true
        val mask = (0xFF shl (8 - remaining)) and 0xFF
        return (network[index].toInt() and mask) == (address[index].toInt() and mask)
    }
}

/** The address the phone dials: the relay, and at most [IrohAddressPlanner.MAX_ADDRESSES] direct ones. */
internal data class IrohDialAddress(val relayUrl: String?, val addresses: List<String>)

/**
 * Picks the direct addresses a phone dials, cheapest first.
 *
 * The ticket a daemon issued carries what it knew at pairing time; the
 * endpoint's cache carries what recent connections learned.  Both are merged
 * and deduplicated (ports and IPv6 scopes kept verbatim), and the first four
 * surviving addresses are ordered:
 *
 * 1. addresses on a prefix of the active network, so a phone on the same
 *    network dials the local address before anything else;
 * 2. the remaining cached addresses, which were reachable recently;
 * 3. the remaining ticket addresses.
 *
 * [subnets] is null when the platform cannot name the active prefixes; then no
 * subnet information is invented and no address is dropped for being private.
 */
internal object IrohAddressPlanner {
    const val MAX_ADDRESSES = 4

    fun plan(
        ticketAddresses: List<String>,
        ticketRelay: String?,
        cachedAddresses: List<String>?,
        cachedRelay: String?,
        subnets: List<IrohSubnet>?
    ): IrohDialAddress {
        val ticket = ticketAddresses.mapNotNull(::parse)
        val cached = cachedAddresses.orEmpty().mapNotNull(::parse)
        val seen = HashSet<String>(ticket.size + cached.size)
        val ordered = ArrayList<DialEntry>(ticket.size + cached.size)
        if (subnets != null) {
            for (entry in cached + ticket) {
                val host = entry.host ?: continue
                if (seen.contains(entry.key)) continue
                if (subnets.none { it.contains(host) }) continue
                seen += entry.key
                ordered += entry
            }
        }
        for (entry in cached) {
            if (seen.add(entry.key)) ordered += entry
        }
        for (entry in ticket) {
            if (seen.add(entry.key)) ordered += entry
        }
        return IrohDialAddress(
            relayUrl = ticketRelay ?: cachedRelay,
            addresses = ordered.take(MAX_ADDRESSES).map { it.raw }
        )
    }

    private class DialEntry(val raw: String, val host: ByteArray?, val key: String)

    private fun parse(text: String): DialEntry? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        var literal = raw
        var port = ""
        if (raw.startsWith("[")) {
            // [v6]:port or [v6]
            val close = raw.indexOf(']')
            if (close <= 1) return null
            literal = raw.substring(1, close)
            val rest = raw.substring(close + 1)
            if (rest.isNotEmpty()) {
                if (!rest.startsWith(":")) return null
                port = rest
            }
        } else {
            val colon = raw.lastIndexOf(':')
            if (colon > 0 && raw.indexOf(':') == colon) {
                // v4 or hostname with a port
                literal = raw.substring(0, colon)
                port = raw.substring(colon)
            } else if (colon == 0 && raw.count { it == ':' } == 1) {
                // A port with no host is not a dial address.
                return null
            }
        }
        var zone = ""
        val scope = literal.indexOf('%')
        if (scope >= 0) {
            zone = literal.substring(scope + 1).lowercase()
            literal = literal.substring(0, scope)
        }
        if (literal.isEmpty()) return null
        val host = if (literal.contains(':')) parseIpv6(literal) else parseIpv4(literal)
        val hostKey = if (host != null) host.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } else literal.lowercase()
        return DialEntry(raw, host, "$hostKey|$zone|$port")
    }

    private fun parseIpv4(literal: String): ByteArray? {
        val parts = literal.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[index] = value.toByte()
        }
        return bytes
    }

    private fun parseIpv6(literal: String): ByteArray? {
        val halves = literal.split("::")
        if (halves.size > 2) return null
        val head = if (halves[0].isEmpty()) emptyList() else halves[0].split(":")
        val tail = if (halves.size == 2) {
            if (halves[1].isEmpty()) emptyList() else halves[1].split(":")
        } else {
            emptyList()
        }
        val groups = ArrayList<Int>(8)
        for (part in head + tail) {
            if (part.isEmpty()) return null
            val value = part.toIntOrNull(16) ?: return null
            if (value !in 0..0xFFFF) return null
            groups += value
        }
        val missing = 8 - groups.size
        if (halves.size == 1) {
            if (missing != 0) return null
        } else if (missing < 1) {
            return null
        }
        val values = ArrayList<Int>(8)
        values += groups.subList(0, head.size)
        repeat(missing) { values += 0 }
        values += groups.subList(head.size, groups.size)
        val bytes = ByteArray(16)
        for ((index, value) in values.withIndex()) {
            bytes[2 * index] = (value ushr 8).toByte()
            bytes[2 * index + 1] = value.toByte()
        }
        return bytes
    }
}
