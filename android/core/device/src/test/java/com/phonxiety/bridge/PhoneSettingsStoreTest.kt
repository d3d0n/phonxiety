package com.phonxiety.bridge

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The settings live in device-protected storage and survive a process restart. */
class PhoneSettingsStoreTest {
    private val root: File = Files.createTempDirectory("phone-settings").toFile()
    private val scopes = mutableListOf<CoroutineScope>()
    private val file = File(root, "datastore/phone-settings.preferences_pb")

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        root.deleteRecursively()
    }

    @Test
    fun `a value written by one store is read by the next process`() = runBlocking {
        val first = store()
        first.update { it.copy(preferredCodec = PhoneCodec.PCM, testNumber = "+998901234567") }
        // One store per file: the first one closes before the next process's store opens it.
        scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() }

        val loaded = store().read()

        assertEquals(PhoneCodec.PCM, loaded.preferredCodec)
        assertEquals("+998901234567", loaded.testNumber)
    }

    private fun store(): PhoneSettingsStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return PhoneSettingsStore(
            dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        )
    }
}
