package com.phonxiety.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phonxiety.bridge.net.ConnectionMode
import com.phonxiety.bridge.net.RelayModeSetting
import com.phonxiety.bridge.net.RelayOptions
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 *
 * [frameTap] turns on the transport frame dump: direction, stream, frame type, byte size
 * and the link's correlation id only — never payload bytes.  Off by default, and read
 * when a link attempt starts, so a change takes effect on the next dial.
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
    val daemonRelayUrls: List<String> = emptyList(),
    /** Whether the transport records frame metadata to the bounded dump; off by default. */
    val frameTap: Boolean = false
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
            daemonRelayUrls: List<String> = emptyList(),
            frameTap: Boolean = false
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
            daemonRelayUrls = RelayOptions.sanitize(daemonRelayUrls),
            frameTap = frameTap
        )

        /** Keeps digits and a single leading `+`; an empty string means "not configured". */
        fun sanitizeNumber(value: String?): String {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return ""
            val digits = text.filter { it.isDigit() }.take(15)
            return if (text.startsWith("+") && digits.isNotEmpty()) "+$digits" else digits
        }

        /**
         * What the test-number field shows while it is being typed: [sanitizeNumber], except that a
         * leading `+` survives on its own, so typing one before the digits no longer erases it.
         * What is stored and dialled is still [sanitizeNumber].
         */
        fun draftNumber(value: String?): String {
            val clean = sanitizeNumber(value)
            return if (clean.isEmpty() && value?.trim()?.startsWith("+") == true) "+" else clean
        }
    }
}

/** The phone-owned preferences the settings screens write and the engine reads. */
interface PhonePreferences {
    val settings: Flow<PhoneSettings>

    /** Applies [block] to the stored values, writes the result and returns it. */
    suspend fun update(block: (PhoneSettings) -> PhoneSettings): PhoneSettings
}

/**
 * The settings file earlier versions kept in credential-protected storage,
 * `files/datastore/phone-settings.preferences_pb`.  That storage is readable only after the user
 * unlocks the phone, so every access is gated on [unlocked]: a locked device shows the file as
 * absent and the next attempt happens after unlock, never a default written over it.  The file
 * stays after the migration: a rollback to an earlier version reads its settings from it.
 */
internal class LegacyPhoneSettingsFile(
    private val file: File,
    val unlocked: () -> Boolean
) {
    val exists: Boolean get() = file.isFile

    /** Every preference the earlier version stored, or null while the file cannot be read. */
    suspend fun read(): Preferences? = withContext(Dispatchers.IO) {
        try {
            file.inputStream().buffered().use { PreferencesFileSerializer.readFrom(it) }
        } catch (e: Exception) {
            BridgeLog.w(TAG, "phone settings: previous file unreadable: ${e.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        const val TAG = "PhonxietySettings"
    }
}

/**
 * DataStore-backed [PhonePreferences], kept in device-protected storage.
 *
 * The engine reads the codec, connection mode and relay URLs at boot, before anyone unlocks the
 * phone, and credential-protected storage is unreadable then: a store kept there would answer with
 * the defaults it read for the life of the process.  One instance per process: DataStore refuses
 * two active stores for one file, and the engine and the UI both need this one, so [of] hands out
 * the single instance instead of a new one per injection.
 *
 * Preferences written by earlier versions are migrated once, and only while they can be read.
 * Every access tries it, and [onUserUnlocked] tries again on the unlock broadcast, so a boot before
 * the first unlock ends with the stored values instead of the defaults.
 */
class PhoneSettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val legacy: LegacyPhoneSettingsFile,
    private val scope: CoroutineScope
) : PhonePreferences {
    /** Serialises the migration, so a read and a write never race it into two copies. */
    private val migrationLock = Mutex()

    /** Set once nothing is left to migrate, so later reads do not look at the old file. */
    @Volatile private var migrated = false

    override val settings: Flow<PhoneSettings> = flow {
        migrate()
        emitAll(dataStore.data.map { preferences -> decode(preferences) }.distinctUntilChanged())
    }

    override suspend fun update(block: (PhoneSettings) -> PhoneSettings): PhoneSettings {
        migrate()
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
            preferences[FRAME_TAP] = next.frameTap
        }
        return written
    }

    /** The stored values, read once; used by the engine before its first `hello`. */
    suspend fun read(): PhoneSettings {
        migrate()
        return decode(dataStore.data.first())
    }

    /** The phone was unlocked: values that were unreadable at boot can be read now. */
    fun onUserUnlocked() {
        scope.launch { migrate() }
    }

    /**
     * Applies the credential-protected file once.  A locked user leaves it alone and the next
     * access after unlock tries again; what it holds wins over everything this store may already
     * have written, because a default written while the real values were unreadable may not
     * replace them.  The migration marker is written in the same edit, and the old file is left in
     * place for a rollback, so it is never applied a second time over newer values.
     */
    private suspend fun migrate() {
        if (migrated) return
        withContext(Dispatchers.IO) {
            migrationLock.withLock {
                if (migrated) return@withLock
                if (!legacy.unlocked()) return@withLock
                if (!legacy.exists) {
                    migrated = true
                    return@withLock
                }
                // An earlier process already applied it; this file is a leftover, not a source.
                if (dataStore.data.first()[MIGRATED_FROM_CREDENTIAL] == true) {
                    migrated = true
                    return@withLock
                }
                val stored = legacy.read() ?: return@withLock
                dataStore.edit { preferences ->
                    // The old file is a full snapshot of what the user chose, so it replaces
                    // whatever this store holds; the defaults a locked boot answered with are not
                    // choices and may not outlive them.
                    preferences += stored
                    preferences[MIGRATED_FROM_CREDENTIAL] = true
                }
                migrated = true
            }
        }
    }

    /** Listens for the unlock so a boot before it stops running on defaults. */
    private fun watchUserUnlock(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_UNLOCKED) onUserUnlocked()
            }
        }
        runCatching {
            AppStorage.deviceProtected(context).registerReceiver(
                receiver,
                IntentFilter(Intent.ACTION_USER_UNLOCKED),
                Context.RECEIVER_NOT_EXPORTED
            )
        }.onFailure { BridgeLog.w(TAG, "phone settings: no unlock callback: ${it.javaClass.simpleName}") }
    }

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
        daemonRelayUrls = RelayOptions.split(preferences[DAEMON_RELAY_URLS].orEmpty()),
        frameTap = preferences[FRAME_TAP] ?: false
    )

    companion object {
        private const val TAG = "PhonxietySettings"
        private const val FILE_NAME = "phone-settings"

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
        private val FRAME_TAP = booleanPreferencesKey("frame_tap")

        /** Set by the one migration, so a leftover old file is never applied twice. */
        private val MIGRATED_FROM_CREDENTIAL = booleanPreferencesKey("migrated_from_credential_protected")

        @Volatile private var instance: PhoneSettingsStore? = null

        fun of(context: Context): PhoneSettingsStore = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(app: Context): PhoneSettingsStore {
            val scope = bridgeScope("phonxiety-settings", Dispatchers.IO)
            val file = AppStorage.dataStoreFile(AppStorage.deviceProtected(app).filesDir, FILE_NAME)
            val store = PhoneSettingsStore(
                dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
                legacy = LegacyPhoneSettingsFile(AppStorage.dataStoreFile(app.filesDir, FILE_NAME)) {
                    AppStorage.userUnlocked(app)
                },
                scope = scope
            )
            store.watchUserUnlock(app)
            return store
        }
    }
}
