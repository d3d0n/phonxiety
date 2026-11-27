package com.phonxiety.bridge

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The frame-tap preference: off by default, false unless chosen, and persisted by key. */
class PhoneSettingsFrameTapTest {
    private val root: File = Files.createTempDirectory("frame-tap-settings").toFile()
    private val file = File(root, "de/datastore/phone-settings.preferences_pb")
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        root.deleteRecursively()
    }

    @Test
    fun theFrameTapDefaultsToOff() {
        assertFalse("a fresh phone never dumps frames", PhoneSettings().frameTap)
        assertFalse(PhoneSettings.sanitized(null, 32_000, false, true, true, null, null, false).frameTap)
    }

    @Test
    fun theChoiceRoundTripsAndIsPersistedUnderItsOwnKey() = runBlocking {
        val store = store()
        assertEquals("off until chosen", false, store.read().frameTap)

        val written = store.update { it.copy(frameTap = true) }
        assertTrue(written.frameTap)
        assertTrue("the next read sees the choice", store.read().frameTap)

        val persisted = runBlocking {
            file.inputStream().buffered().use { PreferencesFileSerializer.readFrom(it) }
        }
        assertEquals(true, persisted[booleanPreferencesKey("frame_tap")])

        store.update { it.copy(frameTap = false) }
        assertEquals(false, store.read().frameTap)
    }

    private fun store(): PhoneSettingsStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return PhoneSettingsStore(
            dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        )
    }
}
