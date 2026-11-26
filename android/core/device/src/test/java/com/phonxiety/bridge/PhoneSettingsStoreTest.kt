package com.phonxiety.bridge

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings live in device-protected storage and the credential-protected file of earlier
 * versions is applied once, whenever it first becomes readable: after a boot that happened before
 * the first unlock, the stored values still arrive and replace the defaults the boot ran on.
 */
class PhoneSettingsStoreTest {
    private val root: File = Files.createTempDirectory("phone-settings").toFile()
    private val scopes = mutableListOf<CoroutineScope>()

    private val deFile = File(root, "de/datastore/phone-settings.preferences_pb")
    private val ceFile = File(root, "ce/datastore/phone-settings.preferences_pb")

    /** Flipped by the tests: the credential-protected storage is readable only while it is true. */
    private var unlocked = false

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        root.deleteRecursively()
    }

    @Test
    fun `a store that starts unlocked takes the credential-protected values and keeps the file`() = runBlocking {
        writeCe(stored)
        unlocked = true
        val store = store()

        val loaded = store.read()

        assertEquals("+998901234567", loaded.testNumber)
        assertEquals(PhoneCodec.PCM, loaded.preferredCodec)
        assertEquals(24_000, loaded.maxBitrate)
        assertEquals("debug", loaded.logLevel)
        assertEquals("custom", loaded.relayMode)
        assertEquals(listOf("https://relay.example:443"), loaded.relayUrls)
        assertEquals(listOf("https://daemon.example:443"), loaded.daemonRelayUrls)
        assertTrue("the old copy stays for a rollback to an earlier version", ceFile.isFile)

        // The same values are on disk, not only in memory: a later process reads them back.
        val persisted = readFile(deFile)
        assertEquals("+998901234567", persisted[stringPreferencesKey("test_number")])
        assertEquals(24_000, persisted[intPreferencesKey("max_bitrate")])
    }

    @Test
    fun `a store that starts locked runs on defaults and picks the values up on unlock`() = runBlocking {
        writeCe(stored)
        unlocked = false
        val store = store()

        // The engine's read at boot: nothing readable, so defaults, and no file of its own.
        assertEquals(PhoneSettings(), store.read())
        assertFalse("a locked read writes nothing", deFile.exists())
        assertTrue("the old copy is untouched while storage is locked", ceFile.isFile)

        // Something wrote while the real values were unreadable; that write may not win.
        store.update { it.copy(testNumber = "111", maxBitrate = 12_000) }
        assertEquals("111", store.read().testNumber)

        val seen = Channel<PhoneSettings>(Channel.UNLIMITED)
        val collector = CoroutineScope(Dispatchers.IO).also { scopes += it }
            .launch { store.settings.collect { seen.send(it) } }

        unlocked = true
        store.onUserUnlocked()

        val migrated = withTimeout(5_000) { awaitNumber(seen, "+998901234567") }
        assertEquals(PhoneCodec.PCM, migrated.preferredCodec)
        assertEquals("the stored bitrate beats the pre-unlock write", 24_000, migrated.maxBitrate)
        collector.cancel()
        assertTrue("the old copy stays for a rollback to an earlier version", ceFile.isFile)

        // And the disk holds the migrated values, so the next process starts with them.
        assertEquals("+998901234567", readFile(deFile)[stringPreferencesKey("test_number")])
    }

    @Test
    fun `a write before migration does not drop the stored values`() = runBlocking {
        writeCe(stored)
        unlocked = false
        val store = store()

        // Every field the store writes is replaced by the defaults a locked boot would have to
        // work with; migration has to put the stored ones back over them.
        store.update { settings ->
            settings.copy(
                preferredCodec = PhoneCodec.OPUS,
                testNumber = "123",
                connectionMode = "lan",
                logLevel = "warn"
            )
        }

        unlocked = true

        val loaded = store.read()

        assertEquals(PhoneCodec.PCM, loaded.preferredCodec)
        assertEquals("+998901234567", loaded.testNumber)
        assertEquals("debug", loaded.logLevel)
        assertEquals("iroh", loaded.connectionMode)
        assertEquals("custom", loaded.relayMode)
        assertEquals(listOf("https://relay.example:443"), loaded.relayUrls)
    }

    @Test
    fun `the migration is marked on disk so the kept file is never applied twice`() = runBlocking {
        writeCe(stored)
        unlocked = true
        val first = store()
        first.read()

        val persisted = readFile(deFile)
        assertTrue(
            "the marker travels with the values, in the same write",
            persisted[booleanPreferencesKey("migrated_from_credential_protected")] == true
        )

        // The user changes a setting after the migration, then the next process starts while the
        // old file is still there: the file is a leftover now, and the newer choice stays.
        first.update { it.copy(preferredCodec = PhoneCodec.OPUS, testNumber = "+998935550101") }
        // One store per file: the first one closes before the next process's store opens it.
        scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() }
        val next = store()

        val loaded = next.read()
        assertEquals(PhoneCodec.OPUS, loaded.preferredCodec)
        assertEquals("+998935550101", loaded.testNumber)
        assertTrue("the old copy is still there for a rollback", ceFile.isFile)
    }

    @Test
    fun `a fresh install has nothing to migrate and writes nothing extra`() = runBlocking {
        unlocked = true
        val store = store()

        assertEquals(PhoneSettings(), store.read())
        assertFalse(deFile.exists())
    }

    // -- fixtures ---------------------------------------------------------

    private fun store(): PhoneSettingsStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return PhoneSettingsStore(
            dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { deFile }),
            legacy = LegacyPhoneSettingsFile(ceFile) { unlocked },
            scope = scope
        )
    }

    private suspend fun awaitNumber(from: Channel<PhoneSettings>, number: String): PhoneSettings {
        while (true) {
            val settings = from.receive()
            if (settings.testNumber == number) return settings
        }
    }

    /** Every key the earlier version stored, with none of the defaults. */
    private val stored: Preferences = mutablePreferencesOf(
        stringPreferencesKey("preferred_codec") to "pcm",
        intPreferencesKey("max_bitrate") to 24_000,
        booleanPreferencesKey("wifi_low_latency") to true,
        booleanPreferencesKey("thermal_awareness") to false,
        booleanPreferencesKey("battery_awareness") to false,
        stringPreferencesKey("log_level") to "debug",
        stringPreferencesKey("test_number") to "+998901234567",
        booleanPreferencesKey("auto_tune_enabled") to true,
        stringPreferencesKey("connection_mode") to "iroh",
        stringPreferencesKey("relay_mode") to "custom",
        stringPreferencesKey("relay_urls") to "https://relay.example:443",
        stringPreferencesKey("daemon_relay_urls") to "https://daemon.example:443"
    )

    /** Writes the file the way the previous version's DataStore wrote it. */
    private fun writeCe(preferences: Preferences) {
        ceFile.parentFile?.mkdirs()
        ceFile.outputStream().buffered().use { out ->
            runBlocking { PreferencesFileSerializer.writeTo(preferences, out) }
        }
    }

    private fun readFile(file: File): Preferences = runBlocking {
        file.inputStream().buffered().use { PreferencesFileSerializer.readFrom(it) }
    }
}
