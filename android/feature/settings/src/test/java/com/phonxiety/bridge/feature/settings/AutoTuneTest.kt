package com.phonxiety.bridge.feature.settings

import com.phonxiety.bridge.PhoneCodec
import com.phonxiety.bridge.PhoneSettings
import com.phonxiety.bridge.model.LinkTestFrames
import com.phonxiety.bridge.model.LinkTestResult
import com.phonxiety.bridge.model.PathKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The measured, reversible rules: each one fires on its own evidence and on nothing else. */
class AutoTuneTest {
    private fun poor(p95: Long = 512, loss: Double = 0.04) = LinkTestResult(
        codec = "opus",
        p50 = 210,
        p95 = p95,
        max = p95 + 100,
        loss = loss,
        frames = LinkTestFrames(sent = 150, echoed = (150 * (1 - loss)).toInt()),
        throughputBps = 24_000,
        healthy = false,
        verdict = "poor"
    )

    private fun good(p95: Long = 60, throughput: Long = 800_000) = LinkTestResult(
        codec = "opus",
        p50 = 30,
        p95 = p95,
        max = p95 + 20,
        loss = 0.0,
        frames = LinkTestFrames(sent = 150, echoed = 150),
        throughputBps = throughput,
        healthy = true,
        verdict = "good"
    )

    @Test
    fun `a poor measurement drops both the codec and the bitrate`() {
        val action = AutoTune.evaluate(
            TuneObservation(lastLinkTest = poor()),
            PhoneSettings(preferredCodec = PhoneCodec.PCM, maxBitrate = 64_000)
        )
        assertEquals(2, action.changes.size)
        assertEquals(TuneKey.CODEC, action.changes[0].key)
        assertEquals(PhoneCodec.OPUS, action.changes[0].to)
        assertEquals(TuneKey.MAX_BITRATE, action.changes[1].key)
        assertEquals("16000", action.changes[1].to)
        assertTrue(action.changes[1].reason.contains("p95 512 ms"))
        assertTrue(action.suggestions.isEmpty())
    }

    @Test
    fun `a poor measurement changes nothing it already applied`() {
        val action = AutoTune.evaluate(
            TuneObservation(lastLinkTest = poor()),
            PhoneSettings(preferredCodec = PhoneCodec.OPUS, maxBitrate = 16_000)
        )
        assertTrue(action.changes.isEmpty())
    }

    @Test
    fun `a fast path allows a richer opus profile`() {
        val action = AutoTune.evaluate(
            TuneObservation(lastLinkTest = good()),
            PhoneSettings(preferredCodec = PhoneCodec.OPUS, maxBitrate = 32_000)
        )
        assertEquals(1, action.changes.size)
        assertEquals(TuneKey.MAX_BITRATE, action.changes[0].key)
        assertEquals("64000", action.changes[0].to)
    }

    @Test
    fun `a slow path does not raise the bitrate`() {
        val action = AutoTune.evaluate(
            TuneObservation(lastLinkTest = good(p95 = 180)),
            PhoneSettings(preferredCodec = PhoneCodec.OPUS, maxBitrate = 32_000)
        )
        assertTrue(action.changes.isEmpty())
    }

    @Test
    fun `cellular with loss forces opus but never pcm`() {
        val action = AutoTune.evaluate(
            TuneObservation(network = "cellular", lastLinkTest = good(throughput = 100_000).copy(loss = 0.01)),
            PhoneSettings(preferredCodec = PhoneCodec.PCM)
        )
        assertEquals(1, action.changes.size)
        assertEquals(PhoneCodec.OPUS, action.changes[0].to)
    }

    @Test
    fun `severe heat releases the wifi lock when thermal awareness is on`() {
        val action = AutoTune.evaluate(
            TuneObservation(thermal = "SEVERE"),
            PhoneSettings(wifiLowLatency = true, thermalAwareness = true)
        )
        assertEquals(1, action.changes.size)
        assertEquals(TuneKey.WIFI_LOW_LATENCY, action.changes[0].key)
        assertEquals("false", action.changes[0].to)
        assertTrue(action.changes[0].reason.contains("SEVERE"))
    }

    @Test
    fun `thermal awareness off leaves the lock alone`() {
        val action = AutoTune.evaluate(
            TuneObservation(thermal = "CRITICAL"),
            PhoneSettings(wifiLowLatency = true, thermalAwareness = false)
        )
        assertTrue(action.changes.isEmpty())
    }

    @Test
    fun `a low battery on battery power releases the lock`() {
        val action = AutoTune.evaluate(
            TuneObservation(batteryPercent = 9, charging = false),
            PhoneSettings(wifiLowLatency = true, batteryAwareness = true)
        )
        assertEquals("false", action.changes.single().to)
    }

    @Test
    fun `a low battery while charging keeps the lock`() {
        val action = AutoTune.evaluate(
            TuneObservation(batteryPercent = 9, charging = true),
            PhoneSettings(wifiLowLatency = true, batteryAwareness = true)
        )
        assertTrue(action.changes.isEmpty())
    }

    @Test
    fun `relay-only and a missing local-network permission are suggestions, not changes`() {
        val action = AutoTune.evaluate(
            TuneObservation(
                path = PathKind.RELAY,
                relayForMs = 40_000,
                missingPermissions = listOf("ACCESS_LOCAL_NETWORK")
            ),
            PhoneSettings()
        )
        assertTrue(action.changes.isEmpty())
        assertEquals(2, action.suggestions.size)
        assertTrue(action.suggestions[0].contains("Relay-only for 40 s"))
        assertTrue(action.suggestions[1].contains("permission"))
    }

    @Test
    fun `a short relay spell stays quiet`() {
        val action = AutoTune.evaluate(
            TuneObservation(path = PathKind.RELAY, relayForMs = 5_000),
            PhoneSettings()
        )
        assertTrue(action.suggestions.isEmpty())
    }
}
