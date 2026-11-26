package com.phonxiety.bridge.data

import android.content.Context
import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.BridgeConfigLoader
import com.phonxiety.bridge.BridgeRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    /**
     * The repository over the real storage, counting how often it is read. A read counts only once
     * the file has been loaded, so a test that waits for a count never races the read it waits for.
     */
    private fun countingRepository(reads: AtomicInteger) = DevicePairingRepository(context) {
        val stored = BridgeConfigLoader.loadOrNull(context) != null
        reads.incrementAndGet()
        stored
    }

    /**
     * The link collector refreshes the cache the first time it observes a link state, so the first
     * read after the constructor's one happens on another thread. Wait for it instead of assuming
     * it has either finished or not.
     */
    private fun awaitReads(reads: AtomicInteger, expected: Int) {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        while (reads.get() < expected) {
            if (System.currentTimeMillis() > deadline) {
                fail("expected $expected storage reads, saw ${reads.get()}")
            }
            Thread.sleep(5)
        }
    }

    @Test
    fun theCachedValueNeverReadsStorageAndTheFirstLinkStateRefreshesIt() = runBlocking {
        BridgeConfigLoader.delete(context)
        val reads = AtomicInteger()
        val repository = countingRepository(reads)

        awaitReads(reads, 2)
        val baseline = reads.get()
        assertEquals("one constructor read plus the first observed link state", 2, baseline)

        assertFalse(repository.paired.value)
        assertFalse(repository.paired.replayCache.single())
        assertEquals("a cached read must not load the config", baseline, reads.get())

        BridgeConfigLoader.write(context, storedConfig())
        assertFalse(repository.paired.value)
        assertEquals(baseline, reads.get())

        repository.refresh()

        assertTrue(repository.paired.value)
        assertTrue(repository.paired.replayCache.single())
        assertEquals(baseline + 1, reads.get())
    }

    @Test
    fun deletingTheConfigUnpairsAfterARefresh() = runBlocking {
        BridgeConfigLoader.write(context, storedConfig())
        val reads = AtomicInteger()
        val repository = countingRepository(reads)
        assertTrue(repository.paired.value)
        awaitReads(reads, 2)

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

    /**
     * A refresh that read the old value must not publish it after a newer refresh has published the
     * new one: the reads and the cache writes are serialized, so the newer value wins.
     */
    @Test
    fun anOlderRefreshCannotOverwriteANewerOne() = runBlocking {
        BridgeConfigLoader.delete(context)
        val stored = AtomicBoolean(false)
        val reads = AtomicInteger()
        val olderInside = CountDownLatch(1)
        val releaseOlder = CountDownLatch(1)
        val newerInside = CountDownLatch(1)
        val repository = DevicePairingRepository(context) {
            val seen = stored.get()
            when (reads.incrementAndGet()) {
                // The constructor read first; the second read is the first observed link state.
                3 -> {
                    olderInside.countDown()
                    releaseOlder.await()
                }

                4 -> newerInside.countDown()
            }
            seen
        }
        awaitReads(reads, 2)

        val older = launch(Dispatchers.IO) { repository.refresh() }
        assertTrue("the older refresh must reach storage", olderInside.await(SETTLE_TIMEOUT_MS, TimeUnit.MILLISECONDS))

        stored.set(true)
        val newer = launch(Dispatchers.IO) { repository.refresh() }
        assertFalse(
            "the newer refresh must wait for the older one to publish before it reads",
            newerInside.await(300, TimeUnit.MILLISECONDS)
        )

        releaseOlder.countDown()
        older.join()
        newer.join()

        assertEquals(4, reads.get())
        assertTrue("the newer value survives the older refresh", repository.paired.value)
    }

    /** A pairing write cannot slip under a refresh that already read the old value. */
    @Test
    fun aPairSurvivesAnInFlightRefresh() = runBlocking {
        BridgeConfigLoader.delete(context)
        val reads = AtomicInteger()
        val refreshInside = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val repository = DevicePairingRepository(context) {
            val seen = BridgeConfigLoader.loadOrNull(context) != null
            if (reads.incrementAndGet() == 3) {
                refreshInside.countDown()
                releaseRefresh.await()
            }
            seen
        }
        awaitReads(reads, 2)

        val stale = launch(Dispatchers.IO) { repository.refresh() }
        assertTrue("the refresh must reach storage", refreshInside.await(SETTLE_TIMEOUT_MS, TimeUnit.MILLISECONDS))

        val pair = async(Dispatchers.IO) {
            repository.pair(
                "phonxiety://pair?url=wss%3A%2F%2F192.168.1.10%3A18763%2Fdevice&token=device-token"
            )
        }
        releaseRefresh.countDown()
        stale.join()

        assertTrue(pair.await() is PairResult.Paired)
        assertTrue("the pairing write survives the in-flight refresh", repository.paired.value)
    }

    /**
     * The store writes the config and then starts the service, and the start can fail (a background
     * foreground-service start is a `SecurityException`). The attempt is reported as rejected, so
     * what it wrote must be gone again: the previous pairing, byte for byte, a cache that never said
     * the rejected pairing was stored, and a runtime that still reports the previous link kind.
     */
    @Test
    fun aRejectedAttemptPutsThePreviousConfigBackByteForByte() = runTest {
        val previous = storedIrohConfig()
        BridgeConfigLoader.write(context, previous)
        // The runtime mirrors the stored pairing's kind; this is what a running app would hold.
        BridgeRuntime.rememberPairing(previous.kind)
        val before = BridgeConfigLoader.snapshot(context)
        assertTrue(before != null)
        val repository = rejectingStartRepository()
        assertTrue("the previous pairing is still the stored one", repository.paired.value)

        // The attempt pairs over the address transport: the kind changes while it runs.
        val result = repository.pair(REPLACEMENT_LINK)

        assertEquals(PairResult.Invalid("pairing_store_failed"), result)
        assertArrayEquals(before, BridgeConfigLoader.snapshot(context))
        assertEquals(previous, BridgeConfigLoader.load(context))
        assertEquals("the runtime goes back to the previous link kind", previous.kind, BridgeRuntime.pairing)
        assertTrue("a rejected attempt leaves the cache alone", repository.paired.value)
    }

    @Test
    fun aRejectedAttemptLeavesNoConfigWhenThereWasNone() = runTest {
        BridgeConfigLoader.delete(context)
        BridgeRuntime.forgetPairing()
        val repository = rejectingStartRepository()
        assertFalse(repository.paired.value)

        val result = repository.pair(REPLACEMENT_LINK)

        assertEquals(PairResult.Invalid("pairing_store_failed"), result)
        assertNull(BridgeConfigLoader.snapshot(context))
        assertNull(BridgeConfigLoader.loadOrNull(context))
        assertNull("the app is not left believing it is paired", BridgeRuntime.pairing)
        assertFalse("a rejected attempt leaves the cache alone", repository.paired.value)
    }

    /** A config of the other transport, so a rollback has a link kind to restore as well. */
    private fun storedIrohConfig() = BridgeConfig.Iroh(
        ticket = "iroh-endpoint-ticket",
        token = "device-token",
        appToken = null
    )

    /** A store whose write succeeds and whose service start throws, as a background start can. */
    private fun rejectingStartRepository() = DevicePairingRepository(
        context = context,
        read = { BridgeConfigLoader.loadOrNull(context) != null },
        startService = { throw SecurityException("startForegroundService is not allowed") }
    )

    private companion object {
        const val SETTLE_TIMEOUT_MS = 5_000L

        const val REPLACEMENT_LINK =
            "phonxiety://pair?url=wss%3A%2F%2F192.168.1.20%3A18763%2Fdevice&token=other-token"
    }
}
