package com.phonxiety.bridge.net

import java.util.Locale

/**
 * Where a host address lives, as far as link selection is concerned.
 *
 * [TAILNET] is the range a tailnet hands out: `100.64.0.0/10`, the Tailscale
 * IPv6 prefix `fd7a:115c:a1e0::/48`, or a MagicDNS name under `.ts.net`.
 * [LOCAL] is a home or office LAN address.  Everything else is [PUBLIC].
 */
internal enum class HostScope { TAILNET, LOCAL, PUBLIC, UNKNOWN }

/**
 * Address classification for the daemon's reachable endpoints.
 *
 * A WebSocket link whose host is [HostScope.TAILNET] is a Tailscale-app link:
 * the Tailscale Android app provides the VPN interface and the daemon's
 * tailnet address is dialled over the same pinned WebSocket transport.
 */
internal object Tailnet {
    private const val MAGIC_DNS_SUFFIX = ".ts.net"
    private const val V6_PREFIX = "fd7a:115c:a1e0:"
    private const val CGNAT_FIRST_OCTET = 100
    private val CGNAT_SECOND_OCTETS = 64..127

    /** True when [host] (an IP literal or a name) is in the tailnet. */
    fun isTailnetHost(host: String?): Boolean = scopeOf(host) == HostScope.TAILNET

    /** Classifies an IP literal or a host name; unknown text is [HostScope.UNKNOWN]. */
    fun scopeOf(host: String?): HostScope {
        val name = normalizeHost(host) ?: return HostScope.UNKNOWN
        if (name.endsWith(MAGIC_DNS_SUFFIX)) return HostScope.TAILNET
        val v4 = parseIpv4(name)
        if (v4 != null) {
            return when {
                v4[0] == CGNAT_FIRST_OCTET && v4[1] in CGNAT_SECOND_OCTETS -> HostScope.TAILNET
                v4[0] == 10 || (v4[0] == 192 && v4[1] == 168) || (v4[0] == 172 && v4[1] in 16..31) -> HostScope.LOCAL
                v4[0] == 127 -> HostScope.LOCAL
                v4[0] == 169 && v4[1] == 254 -> HostScope.LOCAL
                else -> HostScope.PUBLIC
            }
        }
        if (name.contains(':')) {
            return when {
                name.startsWith(V6_PREFIX) -> HostScope.TAILNET
                name.startsWith("fd") || name.startsWith("fc") -> HostScope.LOCAL
                name.startsWith("fe8") || name.startsWith("fe9") || name.startsWith("fea") || name.startsWith("feb") -> HostScope.LOCAL
                name == "::1" || name.startsWith("::ffff:127.") -> HostScope.LOCAL
                else -> HostScope.PUBLIC
            }
        }
        // A plain name that is not a tailnet MagicDNS name; it resolves or it does not.
        return if (name.contains('.')) HostScope.PUBLIC else HostScope.UNKNOWN
    }

    /**
     * The host part of a `wss://` URL: userinfo, port and IPv6 brackets are
     * removed.  Null for anything that is not an absolute URL with an authority.
     */
    fun hostOf(url: String): String? {
        val scheme = url.indexOf("://")
        if (scheme <= 0) return null
        val rest = url.substring(scheme + 3)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return null
        return authority.substringAfterLast('@').let { hostPort ->
            if (hostPort.startsWith("[")) {
                val end = hostPort.indexOf(']')
                if (end < 0) null else hostPort.substring(1, end)
            } else {
                hostPort.substringBefore(':').ifEmpty { null }
            }
        }
    }

    /** Lowercase, brackets and trailing dot removed; null when empty. */
    fun normalizeHost(host: String?): String? {
        val text = host?.trim()?.removeSurrounding("[", "]")?.trimEnd('.') ?: return null
        if (text.isEmpty()) return null
        return text.lowercase(Locale.US)
    }

    private fun parseIpv4(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            val value = part.toIntOrNull() ?: return null
            if (value > 255) return null
            octets[index] = value
        }
        return octets
    }
}
