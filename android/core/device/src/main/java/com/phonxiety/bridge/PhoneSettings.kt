package com.phonxiety.bridge

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.phonxiety.bridge.net.ConnectionMode
import com.phonxiety.bridge.net.RelayModeSetting
import com.phonxiety.bridge.net.RelayOptions
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Codec values the phone can ask for: its own preference, not the daemon's own setting. */
object PhoneCodec {
    const val AUTO = "auto"
    const val OPUS = "opus"
    const val PCM = "pcm"

    val all = listOf(AUTO, OPUS, PCM)

    fun normalize(value: String?): String = value?.trim()?.lowercase(Locale.US)?.takeIf { it in all } ?: AUTO
}

/**
 * The phone's own audio and behaviour preferences.  [preferredCodec] and [maxBitrate] travel in
 * the device `hello` and in `set_prefs`, so they decide the next call's negotiated codec;
 * [wifiLowLatency] holds the Wi-Fi low-latency lock while the link is up; [thermalAwareness] and
 * [batteryAwareness] let auto-tune release that lock; [logLevel] is what the log ring keeps;
 * [testNumber] is the only number the test lab is allowed to dial.
 *
 * Connection values decide which transport the engine opens and which relays its iroh
 * endpoint uses: [connectionMode] pins one candidate or leaves the choice to selection,
 * [relayMode] picks between the preset relays, the daemon's list, the phone's own list and
 * no relays at all, and [relayUrls] is that own list.
 */
data class PhoneSettings(
    val preferredCodec: String = PhoneCodec.AUTO,
    val maxBitrate: Int = DEFAULT_MAX_BITRATE,
    val wifiLowLatency: Boolean = false,
    val thermalAwareness: Boolean = true,
    val batteryAwareness: Boolean = true,
    val logLevel: String = "info",
    val testNumber: String = "",
    val autoTuneEnabled: Boolean = false,
    val connectionMode: String = ConnectionMode.AUTO.wire,
    val relayMode: String = RelayModeSetting.DEFAULT.wire,
    val relayUrls: List<String> = emptyList(),
    /** The daemon's own `iroh_relays`, mirrored here so the daemon relay mode can use it. */
    val daemonRelayUrls: List<String> = emptyList()
) {
    companion object {
        const val MIN_BITRATE = 6_000
        const val MAX_BITRATE = 128_000
        const val DEFAULT_MAX_BITRATE = 32_000
        val LOG_LEVELS = listOf("debug", "info", "warn", "error")

        /** Values outside the ranges the daemon accepts are clamped rather than rejected. */
        fun sanitized(
            preferredCodec: String?,
            maxBitrate: Int,
            wifiLowLatency: Boolean,
            thermalAwareness: Boolean,
            batteryAwareness: Boolean,
            logLevel: String?,
            testNumber: String?,
            autoTuneEnabled: Boolean,
            connectionMode: String? = ConnectionMode.AUTO.wire,
            relayMode: String? = RelayModeSetting.DEFAULT.wire,
            relayUrls: List<String> = emptyList(),
            daemonRelayUrls: List<String> = emptyList()
        ): PhoneSettings = PhoneSettings(
            preferredCodec = PhoneCodec.normalize(preferredCodec),
            maxBitrate = maxBitrate.coerceIn(MIN_BITRATE, MAX_BITRATE),
            wifiLowLatency = wifiLowLatency,
            thermalAwareness = thermalAwareness,
            batteryAwareness = batteryAwareness,
            logLevel = logLevel?.trim()?.lowercase(Locale.US)?.takeIf { it in LOG_LEVELS } ?: "info",
            testNumber = sanitizeNumber(testNumber),
            autoTuneEnabled = autoTuneEnabled,
            connectionMode = ConnectionMode.of(connectionMode).wire,
            relayMode = RelayModeSetting.of(relayMode).wire,
            relayUrls = RelayOptions.sanitize(relayUrls),
            daemonRelayUrls = RelayOptions.sanitize(daemonRelayUrls)
        )

        /** Keeps digits and a single leading `+`; an empty string means "not configured". */
        fun sanitizeNumber(value: String?): String {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return ""
            val digits = text.filter { it.isDigit() }.take(15)
            return if (text.startsWith("+") && digits.isNotEmpty()) "+$digits" else digits
        }
    }
}

/** The phone-owned preferences the settings screens write and the engine reads. */
interface PhonePreferences {
    val settings: Flow<PhoneSettings>

    /** Applies [block] to the stored values, writes the result and returns it. */
    suspend fun update(block: (PhoneSettings) -> PhoneSettings): PhoneSettings
}

private val Context.phoneSettingsDataStore: DataStore<Preferences> by preferencesDataStore("phone-settings")

/**
 * DataStore-backed [PhonePreferences].  One instance per process: DataStore refuses two active
 * stores for one file, and the engine and the UI both need this one, so [of] hands out the
 * single instance instead of a new one per injection.
 */
class PhoneSettingsStore private constructor(private val dataStore: DataStore<Preferences>) : PhonePreferences {
    override val settings: Flow<PhoneSettings> = dataStore.data
        .map { preferences -> decode(preferences) }
        .distinctUntilChanged()

    override suspend fun update(block: (PhoneSettings) -> PhoneSettings): PhoneSettings {
        var written = PhoneSettings()
        dataStore.edit { preferences ->
            val next = block(decode(preferences))
            written = next
            preferences[CODEC] = next.preferredCodec
            preferences[BITRATE] = next.maxBitrate
            preferences[WIFI_LOW_LATENCY] = next.wifiLowLatency
            preferences[THERMAL_AWARENESS] = next.thermalAwareness
            preferences[BATTERY_AWARENESS] = next.batteryAwareness
            preferences[LOG_LEVEL] = next.logLevel
            preferences[TEST_NUMBER] = next.testNumber
            preferences[AUTO_TUNE] = next.autoTuneEnabled
            preferences[CONNECTION_MODE] = next.connectionMode
            preferences[RELAY_MODE] = next.relayMode
            preferences[RELAY_URLS] = RelayOptions.join(next.relayUrls)
            preferences[DAEMON_RELAY_URLS] = RelayOptions.join(next.daemonRelayUrls)
        }
        return written
    }

    /** The stored values, read once; used by the engine before its first `hello`. */
    suspend fun read(): PhoneSettings = decode(dataStore.data.first())

    private fun decode(preferences: Preferences): PhoneSettings = PhoneSettings.sanitized(
        preferredCodec = preferences[CODEC],
        maxBitrate = preferences[BITRATE] ?: PhoneSettings.DEFAULT_MAX_BITRATE,
        wifiLowLatency = preferences[WIFI_LOW_LATENCY] ?: false,
        thermalAwareness = preferences[THERMAL_AWARENESS] ?: true,
        batteryAwareness = preferences[BATTERY_AWARENESS] ?: true,
        logLevel = preferences[LOG_LEVEL],
        testNumber = preferences[TEST_NUMBER],
        autoTuneEnabled = preferences[AUTO_TUNE] ?: false,
        connectionMode = preferences[CONNECTION_MODE],
        relayMode = preferences[RELAY_MODE],
        relayUrls = RelayOptions.split(preferences[RELAY_URLS].orEmpty()),
        daemonRelayUrls = RelayOptions.split(preferences[DAEMON_RELAY_URLS].orEmpty())
    )

    companion object {
        private val CODEC = stringPreferencesKey("preferred_codec")
        private val BITRATE = intPreferencesKey("max_bitrate")
        private val WIFI_LOW_LATENCY = booleanPreferencesKey("wifi_low_latency")
        private val THERMAL_AWARENESS = booleanPreferencesKey("thermal_awareness")
        private val BATTERY_AWARENESS = booleanPreferencesKey("battery_awareness")
        private val LOG_LEVEL = stringPreferencesKey("log_level")
        private val TEST_NUMBER = stringPreferencesKey("test_number")
        private val AUTO_TUNE = booleanPreferencesKey("auto_tune_enabled")
        private val CONNECTION_MODE = stringPreferencesKey("connection_mode")
        private val RELAY_MODE = stringPreferencesKey("relay_mode")
        private val RELAY_URLS = stringPreferencesKey("relay_urls")
        private val DAEMON_RELAY_URLS = stringPreferencesKey("daemon_relay_urls")

        @Volatile private var instance: PhoneSettingsStore? = null

        fun of(context: Context): PhoneSettingsStore = instance ?: synchronized(this) {
            instance ?: PhoneSettingsStore(context.applicationContext.phoneSettingsDataStore).also { instance = it }
        }
    }
}
