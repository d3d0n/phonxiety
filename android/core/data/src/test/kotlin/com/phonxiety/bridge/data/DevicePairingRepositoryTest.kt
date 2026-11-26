package com.phonxiety.bridge.data

import android.content.Context
import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.BridgeConfigLoader
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The pairing flag follows the stored config, not the moment the repository was constructed: the
 * file is also written by `mise run phone:pair` and by the bridge service while the app runs. The
 * flow itself stays a cache, so a reader on the main thread never waits for storage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DevicePairingRepositoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun storedConfig() = BridgeConfig.Address(
        url = "wss://192.168.1.10:18763/device",
        token = "device-token",
        certSha256 = null
    )

    /** The repository over the real storage, counting how often it is read. */
    private fun countingRepository(reads: AtomicInteger) = DevicePairingRepository(context) {
        reads.incrementAndGet()
        BridgeConfigLoader.loadOrNull(context) != null
    }

    @Test
    fun readingTheCachedValueNeverTouchesStorageAndRefreshPicksUpTheNewConfig() = runBlocking {
        BridgeConfigLoader.delete(context)
        val reads = AtomicInteger()
        val repository = countingRepository(reads)
        assertEquals(1, reads.get())
        assertFalse(repository.paired.value)
        assertFalse(repository.paired.replayCache.single())
        assertEquals("a cached read must not load the config", 1, reads.get())

        BridgeConfigLoader.write(context, storedConfig())
        assertFalse(repository.paired.value)
        assertEquals(1, reads.get())

        repository.refresh()

        assertTrue(repository.paired.value)
        assertTrue(repository.paired.replayCache.single())
        assertEquals(2, reads.get())
    }

    @Test
    fun deletingTheConfigUnpairsAfterARefresh() = runBlocking {
        BridgeConfigLoader.write(context, storedConfig())
        val repository = DevicePairingRepository(context)
        assertTrue(repository.paired.value)

        BridgeConfigLoader.delete(context)
        assertTrue("the cached value is served until the cache is refreshed", repository.paired.value)

        repository.refresh()

        assertFalse(repository.paired.value)
    }

    @Test
    fun aPairingLinkPairsImmediately() = runTest {
        BridgeConfigLoader.delete(context)
        val repository = DevicePairingRepository(context)

        val result = repository.pair(
            "phonxiety://pair?url=wss%3A%2F%2F192.168.1.10%3A18763%2Fdevice&token=device-token"
        )

        assertTrue(result is PairResult.Paired)
        assertTrue(repository.paired.value)
        assertTrue(repository.paired.replayCache.single())
    }
}
