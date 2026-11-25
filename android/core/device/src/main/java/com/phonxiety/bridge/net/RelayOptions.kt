package com.phonxiety.bridge.net

import java.util.Locale

/**
 * Which iroh relays the phone's endpoint uses.
 *
 * [DEFAULT] is whatever the endpoint preset configures (n0's production map);
 * [DAEMON] follows the daemon's own `iroh_relays` setting, which the settings
 * screen keeps in sync; [CUSTOM] is the list typed on the phone; [DISABLED]
 * turns relays off entirely, leaving only direct paths.
 */
enum class RelayModeSetting(val wire: String, val label: String) {
    DEFAULT("default", "Default relays"),
    DAEMON("daemon", "The daemon's relays"),
    CUSTOM("custom", "Custom URLs"),
    DISABLED("disabled", "No relays");

    companion object {
        fun of(value: String?): RelayModeSetting = entries.firstOrNull { it.wire == value?.trim()?.lowercase(Locale.US) } ?: DEFAULT
    }
}

/**
 * The relay URLs a [RelayModeSetting] resolves to, and the checks applied to
 * them before they reach the iroh endpoint.
 *
 * A relay URL is `https://host[:port][/path]`; plain `http` is accepted because
 * a relay on the owner's own network may not have TLS, and a path suffix is
 * allowed for reverse-proxied relays.
 */
object RelayOptions {
    private const val MAX_URLS = 8
    private const val MAX_URL_CHARS = 512

    /** The URLs to hand to iroh; empty means "use the preset's map" or "no relays". */
    fun urls(setting: RelayModeSetting, custom: List<String>, daemon: List<String>): List<String> = when (setting) {
        RelayModeSetting.DEFAULT, RelayModeSetting.DISABLED -> emptyList()
        RelayModeSetting.DAEMON -> sanitize(daemon)
        RelayModeSetting.CUSTOM -> sanitize(custom)
    }

    /** Keeps well-formed URLs only, de-duplicated, in the order given. */
    fun sanitize(urls: List<String>): List<String> = urls.asSequence()
        .map { it.trim() }
        .filter { isRelayUrl(it) }
        .distinct()
        .take(MAX_URLS)
        .toList()

    fun isRelayUrl(url: String): Boolean {
        if (url.isEmpty() || url.length > MAX_URL_CHARS) return false
        if (url.any { it.isWhitespace() || it.isISOControl() }) return false
        val scheme = url.substringBefore("://", missingDelimiterValue = "")
        if (scheme.lowercase(Locale.US) !in setOf("https", "http")) return false
        val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || authority.contains('@')) return false
        val host = if (authority.startsWith("[")) {
            authority.takeIf { it.contains(']') }?.substringAfter('[')?.substringBefore(']')
        } else {
            authority.substringBefore(':')
        }
        return !host.isNullOrEmpty()
    }

    /** The relay list as one text block; the settings screen edits it this way. */
    fun join(urls: List<String>): String = urls.joinToString("\n")

    fun split(text: String): List<String> = text.split('\n', ',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
