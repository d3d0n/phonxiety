package com.phonxiety.bridge

import com.phonxiety.bridge.model.AudioState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audio stats belong to the stream that produced them.  The engine publishes
 * them from the 100 ms scheduler outside its engine lock, while teardown runs
 * elsewhere, so these tests pin the store's stream gate: a publish that was in
 * flight when the stream died must never leave the last call's drops on the
 * dashboard with no call running and nothing left to overwrite them.
 */
class AudioStreamStatsTest {
    private fun stats(drops: Long, codec: String? = "opus"): AudioStats = AudioStats(
        codec = codec,
        bitrate = 24_000,
        rxDbfs = -20.5f,
        txDbfs = -31.0f,
        framesOut = 96_000,
        framesIn = 144_000,
        drops = drops
    )

    @Test
    fun aPublishHeldAcrossTeardownNeverLandsAfterTheReset() {
        val store = EngineStateStore()
        val stream = store.audioStreamStart()
        assertTrue(store.audioStats(stream, stats(drops = 255_329)))
        assertEquals(255_329L, store.state.value.audio.drops)

        // The publisher has captured the stream, then blocks between capture
        // and publish — exactly where `emitAudioLevels` waits on the playback
        // write.  Teardown runs to completion in that window.
        val captured = stream
        val releasePublish = CountDownLatch(1)
        val landed = AtomicReference<Boolean>()
        val publisher = thread(name = "audio-publish") {
            releasePublish.await()
            landed.set(store.audioStats(captured, stats(drops = 300_000)))
        }

        // What `finishOwned` does once it has detached the audio: reset.
        store.audioReset(captured)

        releasePublish.countDown()
        publisher.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse("the held publish reported success", landed.get() == true)
        assertEquals(AudioState(), store.state.value.audio)
        assertEquals(0L, store.state.value.audio.drops)
        assertNull(store.state.value.audio.codec)
    }

    @Test
    fun hangUpClearsTheStatsWithoutATerminalTelecomCallback() {
        val store = EngineStateStore()
        val stream = store.audioStreamStart()
        store.audio("opus", 24_000)
        assertTrue(store.audioStats(stream, stats(drops = 255_329)))

        // `handleHangup` detaches the audio, queues its close and resets the
        // stream it detached.  Nothing else arrives: no DISCONNECTED, no
        // DESTROYED, no `finishOwned`.
        store.audioReset(stream)

        assertEquals(AudioState(), store.state.value.audio)
        // And the detached stream can never publish its numbers back.
        assertFalse(store.audioStats(stream, stats(drops = 999_999)))
        assertEquals(0L, store.state.value.audio.drops)
    }

    @Test
    fun aLiveStreamKeepsPublishingUntilItsTeardown() {
        val store = EngineStateStore()
        val stream = store.audioStreamStart()

        // One publish per 100 ms scheduler tick: each lands while the stream is live.
        for (tick in 1L..5L) {
            assertTrue(store.audioStats(stream, stats(drops = tick * 480)))
            assertEquals(tick * 480, store.state.value.audio.drops)
            assertEquals(144_000L, store.state.value.audio.framesIn)
        }

        store.audioReset(stream)
        assertEquals(AudioState(), store.state.value.audio)
    }

    @Test
    fun aNewStreamSupersedesTheEarlierStreamsNumbers() {
        val store = EngineStateStore()
        val first = store.audioStreamStart()
        assertTrue(store.audioStats(first, stats(drops = 255_329)))

        // A fresh start_audio epoch attaches a new PhoneAudio and opens a new stream.
        val second = store.audioStreamStart()
        assertFalse(store.audioStats(first, stats(drops = 300_000)))
        assertTrue(store.audioStats(second, stats(drops = 480)))
        assertEquals(480L, store.state.value.audio.drops)

        // The earlier stream's teardown must not wipe the stream that replaced it.
        store.audioReset(first)
        assertEquals(480L, store.state.value.audio.drops)
        assertEquals("opus", store.state.value.audio.codec)

        store.audioReset(second)
        assertEquals(AudioState(), store.state.value.audio)
    }
}
