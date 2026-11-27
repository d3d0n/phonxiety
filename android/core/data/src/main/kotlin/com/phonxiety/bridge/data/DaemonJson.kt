package com.phonxiety.bridge.data

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration for every document the daemon sends or accepts:
 * unknown members are ignored so a newer daemon does not break an older app,
 * nulls are never written unless a field says so, and an input value outside
 * an enum's range falls back to the property's default instead of failing the
 * whole document.
 */
internal val DaemonJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}
