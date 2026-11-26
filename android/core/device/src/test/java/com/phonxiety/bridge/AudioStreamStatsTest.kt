package com.phonxiety.bridge

import com.phonxiety.bridge.BridgeEngine.OwnedCall
import com.phonxiety.bridge.model.AudioState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Audio stats belong to the stream that produced them.  The engine publishes
 * from the 100 ms scheduler outside its lock while teardown runs elsewhere, and
 * several teardowns can touch one call (hang-up, Telecom's terminal callback,
 * an audio-fatal callback).  These tests drive the engine's own ownership
 * decisions — [BridgeEngine.OwnedCall.detachAudio], `detachAudioAt` and
 * `audioToPublish` — against the store's stream gate, so a cleanup that finds
 * nothing leaves nothing to reset and a stale callback cannot touch a
 * successor's stream.
 *
 * Telecom ([android.telecom.Call]) and [android.media.AudioTrack] paths are not
 * reachable on the JVM; those call sites only choose which of these methods to
 * call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioStreamStatsTest {
    private fun audio(callId: String = "call-1", epoch: Long = 1) = PhoneAudio(
        RuntimeEnvironment.getApplication(),
        callId,
        epoch,
        null,
        { _, _ -> true },
        {}
    )

    private fun stats(drops: Long, codec: String? = "opus"): AudioStats = AudioStats(
        codec = codec,
        bitrate = 24_000,
        rxDbfs = -20.5f,
        txDbfs = -31.0f,
        framesOut = 96_000,
        framesIn = 144_000,
        drops = drops
    )

    /** What `handleStartAudio` does when it attaches a new stream. */
    private fun OwnedCall.attach(store: EngineStateStore, epoch: Long): AttachedAudio {
        val attached = AttachedAudio(audio(callId, epoch), store.audioStreamStart())
        this.attached = attached
        this.epoch = epoch
        return attached
    }

    @Test
    fun aPublishCapturedBeforeTeardownNeverLandsAfterTheReset() {
        val store = EngineStateStore()
        val owned = BridgeEngine.OwnedCall("call-1", "+998901234567")
        val attached = owned.attach(store, epoch = 1)
        assertTrue(store.audioStats(attached.stream, stats(drops = 255_329)))
        assertEquals(255_329L, store.state.value.audio.drops)

        // `emitAudioLevels` captures the stream under the engine lock, then
        // blocks on the playback write and the send.
        val captured = owned.audioToPublish()
        assertNotNull(captured)

        // `finishOwned` detaches and resets to completion in that window.
        val detached = owned.detachAudio()
        assertSame(attached, detached)
        store.audioReset(detached!!.stream)

        // The captured publish lands afterwards.  It must not resurrect the numbers.
        assertFalse(store.audioStats(captured!!.stream, stats(drops = 300_000)))
        assertEquals(AudioState(), store.state.value.audio)
    }

    @Test
    fun hangUpResetsWithoutATerminalTelecomCallbackAndTheLateCleanupFindsNothing() {
        val store = EngineStateStore()
        val owned = BridgeEngine.OwnedCall("call-1", "+998901234567")
        val attached = owned.attach(store, epoch = 4)
        store.audio(attached.stream, "opus", 24_000)
        assertTrue(store.audioStats(attached.stream, stats(drops = 255_329)))

        // `handleHangup` detaches the audio and resets the stream it detached.
        // No DISCONNECTED, no DESTROYED, no `finishOwned` arrives.
        val hungUp = owned.detachAudio()
        assertSame(attached, hungUp)
        store.audioReset(hungUp!!.stream)

        assertEquals(AudioState(), store.state.value.audio)
        // The detached stream can never publish its numbers back.
        assertFalse(store.audioStats(attached.stream, stats(drops = 999_999)))

        // A late `finishOwned` for the same call detaches nothing, so it has no
        // stream to reset — and therefore cannot wipe anything that is live.
        assertNull(owned.detachAudio())
    }

    @Test
    fun aDelayedCleanupAfterHangUpNeverResetsTheSuccessor() {
        val store = EngineStateStore()
        val first = BridgeEngine.OwnedCall("call-1", "+998901234567")
        val firstAudio = first.attach(store, epoch = 1)
        assertTrue(store.audioStats(firstAudio.stream, stats(drops = 255_329)))

        // Hang-up: detach and reset the first stream.
        val hungUp = first.detachAudio()
        store.audioReset(hungUp!!.stream)

        // A successor call attaches while the first call's terminal cleanup is
        // still queued.
        val successor = BridgeEngine.OwnedCall("call-2", "+998901234568")
        val secondAudio = successor.attach(store, epoch = 1)
        assertTrue(store.audioStats(secondAudio.stream, stats(drops = 480)))
        assertEquals(480L, store.state.value.audio.drops)

        // The delayed cleanup: the first call has no attachment left, so it
        // resets nothing; even a stale repeat of its captured token is ignored.
        assertNull(first.detachAudio())
        store.audioReset(firstAudio.stream)

        // The successor keeps publishing for its whole lifetime.
        for (tick in 2L..5L) {
            assertTrue(store.audioStats(secondAudio.stream, stats(drops = tick * 480)))
            assertEquals(tick * 480, store.state.value.audio.drops)
        }
        assertEquals(144_000L, store.state.value.audio.framesIn)
        assertEquals("opus", store.state.value.audio.codec)
    }

    @Test
    fun anObsoleteFatalCallbackLeavesTheReplacementPublishing() {
        val store = EngineStateStore()
        val owned = BridgeEngine.OwnedCall("call-1", "+998901234567")
        val old = owned.attach(store, epoch = 1)
        assertTrue(store.audioStats(old.stream, stats(drops = 255_329)))

        // The daemon's `start_audio` for a new epoch queued its replacement, and
        // the old epoch's fatal callback was queued behind it: the replacement
        // attaches first.
        owned.detachAudio()
        store.audioReset(old.stream)
        val replacement = owned.attach(store, epoch = 2)
        assertTrue(store.audioStats(replacement.stream, stats(drops = 480)))

        // `onAudioFatal` for the dead epoch: the ownership check rejects it and
        // detaches nothing, so the engine resets nothing.
        val stale = owned.detachAudioAt(1)
        assertNull(stale)
        // Even the dead epoch's own stream token cannot clear the replacement.
        store.audioReset(old.stream)

        assertTrue(store.audioStats(replacement.stream, stats(drops = 960)))
        assertEquals(960L, store.state.value.audio.drops)
        assertNotNull(owned.attached)
        assertEquals(2L, owned.epoch)
    }

    @Test
    fun aMatchingFatalCallbackResetsOnlyItsOwnStream() {
        val store = EngineStateStore()
        val owned = BridgeEngine.OwnedCall("call-1", "+998901234567")
        val attached = owned.attach(store, epoch = 7)
        assertTrue(store.audioStats(attached.stream, stats(drops = 255_329)))

        // A callback for an older epoch owns nothing here and must change nothing.
        assertNull(owned.detachAudioAt(6))
        assertSame(attached, owned.attached)
        assertTrue(store.audioStats(attached.stream, stats(drops = 300_000)))

        val matched = owned.detachAudioAt(7)
        assertSame(attached, matched)
        assertEquals(-1L, owned.epoch)
        assertNull(owned.attached)

        store.audioReset(matched!!.stream)
        assertEquals(AudioState(), store.state.value.audio)
        assertFalse(store.audioStats(attached.stream, stats(drops = 200)))
    }

    @Test
    fun aFinishedCallPublishesNothingEvenWithAudioAttached() {
        val store = EngineStateStore()
        val owned = BridgeEngine.OwnedCall("call-1", "+998901234567")
        owned.attach(store, epoch = 1)
        owned.finished = true
        assertNull(owned.audioToPublish())
    }

    @Test
    fun aLiveStreamKeepsPublishingEveryTickUntilItsTeardown() {
        val store = EngineStateStore()
        val owned = BridgeEngine.OwnedCall("call-1", "+998901234567")
        val attached = owned.attach(store, epoch = 1)

        // One publish per 100 ms scheduler tick: each lands while the stream is live.
        for (tick in 1L..5L) {
            val target = owned.audioToPublish()
            assertNotNull(target)
            assertTrue(store.audioStats(target!!.stream, stats(drops = tick * 480)))
            assertEquals(tick * 480, store.state.value.audio.drops)
            assertEquals(144_000L, store.state.value.audio.framesIn)
        }

        val detached = owned.detachAudio()
        store.audioReset(detached!!.stream)
        assertEquals(AudioState(), store.state.value.audio)
    }

    @Test
    fun aPublishBlockedBetweenCaptureAndStoreIsOrderedByTheReset() {
        val store = EngineStateStore()
        val owned = BridgeEngine.OwnedCall("call-1", "+998901234567")
        val attached = owned.attach(store, epoch = 1)
        assertTrue(store.audioStats(attached.stream, stats(drops = 255_329)))

        val captured = owned.audioToPublish()!!
        val releasePublish = CountDownLatch(1)
        val landed = AtomicReference<Boolean>()
        val publisher = thread(name = "audio-publish") {
            releasePublish.await()
            landed.set(store.audioStats(captured.stream, stats(drops = 300_000)))
        }

        val detached = owned.detachAudio()!!
        store.audioReset(detached.stream)

        releasePublish.countDown()
        publisher.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse("the held publish reported success", landed.get() == true)
        assertEquals(AudioState(), store.state.value.audio)
    }

    /**
     * Telecom reports the call ending: the callback detaches under the engine lock and releases
     * the stream right after it, through the same [releaseDetachedAudio] the engine calls. The
     * terminal cleanup then finds nothing, and a publish captured before the ending is refused.
     */
    @Test
    fun aTelecomEndingReleasesItsStreamAndTheTerminalCleanupFindsNothing() {
        val store = EngineStateStore()
        val owned = BridgeEngine.OwnedCall("call-1", "+998901234567")
        val attached = owned.attach(store, epoch = 1)
        assertTrue(store.audioStats(attached.stream, stats(drops = 255_329)))
        val captured = owned.audioToPublish()
        assertNotNull(captured)

        val detached = owned.detachAudio()
        val closed = mutableListOf<PhoneAudio?>()
        releaseDetachedAudio(store, detached) { closed += it }

        assertEquals(listOf(attached.audio), closed)
        assertEquals(AudioState(), store.state.value.audio)
        assertNull("the terminal cleanup must find nothing to reset", owned.detachAudio())
        assertFalse(store.audioStats(attached.stream, stats(drops = 300_000)))
        assertEquals(AudioState(), store.state.value.audio)
    }
}
