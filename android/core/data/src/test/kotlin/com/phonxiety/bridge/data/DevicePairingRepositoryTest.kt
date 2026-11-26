package com.phonxiety.bridge.data

import android.content.Context
import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.BridgeConfigLoader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The pairing flag follows the stored config, not the moment the repository was constructed: the
 * file is also written by `mise run phone:pair` and by the bridge service while the app runs.
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

    @Test
    fun aConfigWrittenWhileTheAppRunsIsPairedWithoutARestart() {
        BridgeConfigLoader.delete(context)
        val repository = DevicePairingRepository(context)
        assertFalse(repository.paired.value)

        BridgeConfigLoader.write(context, storedConfig())

        assertTrue(repository.paired.value)
        assertTrue(repository.paired.replayCache.single())
    }

    @Test
    fun deletingTheConfigUnpairsTheSameInstance() {
        BridgeConfigLoader.write(context, storedConfig())
        val repository = DevicePairingRepository(context)
        assertTrue(repository.paired.value)

        BridgeConfigLoader.delete(context)

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
