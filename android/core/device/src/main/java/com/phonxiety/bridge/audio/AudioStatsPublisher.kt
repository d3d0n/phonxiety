package com.phonxiety.bridge.audio

import android.annotation.SuppressLint
import android.content.Context
import android.telecom.Call
import android.telecom.TelecomManager
import com.phonxiety.bridge.AttachedAudio
import com.phonxiety.bridge.AudioStats
import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.EngineStateStore
import com.phonxiety.bridge.engine.CallSession
import com.phonxiety.bridge.model.DeviceOutbound
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/**
 * Publishes the live stream's playback marks and level meters, reports a fatal
 * audio callback, and owns the asynchronously closed audio sessions and the
 * handset's microphone restore.
 *
 * The 500 ms and 100 ms schedules stay in the engine; this class never starts
 * its own timer, so the publish cadence and the frame send path are unchanged.
 * Every capture of the stream happens under [CallSession.lock], and a reset
 * always names exactly the stream that was detached.
 */
internal class AudioStatsPublisher(
    private val context: Context,
    private val session: CallSession,
    private val store: EngineStateStore,
    private val wire: (DeviceOutbound) -> Boolean,
    private val executor: ExecutorService,
    private val isRunning: () -> Boolean,
    private val hasUnrelatedCall: (Call?) -> Boolean
) {
    companion object {
        private const val TAG = "Bridge:engine"
    }

    private val pendingAudioClose = ConcurrentLinkedQueue<PhoneAudio>()

    fun emitPlayback() {
        val state: Triple<PhoneAudio, String, Long> = synchronized(session.lock) {
            val current = session.owned
            val audio = current?.attached?.audio
            if (current == null || audio == null) return
            Triple(audio, current.callId, current.epoch)
        }
        val stats = state.first.stats()
        wire(
            DeviceOutbound.Playback(
                callId = state.second,
                epoch = state.third,
                playedSamples = stats.first,
                queuedSamples = stats.second,
                drainedMark = state.first.drainedMark()
            )
        )
    }

    fun emitAudioLevels() {
        val target: AudioLevelTarget = synchronized(session.lock) {
            val current = session.owned ?: return
            val attached = current.audioToPublish() ?: return
            AudioLevelTarget(attached, current.callId)
        }
        // Both calls below can block behind the playback write and the send, so
        // the stream may be torn down by the time they return.  The store
        // accepts numbers only from the stream it still considers live, so this
        // publish can never land after that teardown's reset.
        val levels = target.attached.audio.levels()
        wire(
            DeviceOutbound.AudioLevels(
                callId = target.callId,
                rxDbfs = levels.rxDbfs,
                txDbfs = levels.txDbfs,
                queued = levels.queuedSamples / 960
            )
        )
        val diagnostics = target.attached.audio.diagnostics()
        store.audioStats(
            target.attached.stream,
            AudioStats(
                codec = diagnostics.codec,
                bitrate = diagnostics.bitrate,
                rxDbfs = levels.rxDbfs,
                txDbfs = levels.txDbfs,
                framesOut = diagnostics.capturedFrames,
                framesIn = diagnostics.hostFrames,
                drops = diagnostics.overflowSamples
            )
        )
    }

    /** The audio callback of a new session, funneled through the command executor. */
    fun postFatal(callId: String, epoch: Long, reason: String) {
        try {
            executor.execute { onAudioFatal(callId, epoch, reason) }
        } catch (_: RejectedExecutionException) {
            // Shutting down.
        }
    }

    private fun onAudioFatal(callId: String, epoch: Long, reason: String) {
        var detached: AttachedAudio? = null
        synchronized(session.lock) {
            val current = session.owned
            if (current != null && current.callId == callId) {
                detached = current.detachAudioAt(epoch)
            }
        }
        val attached = detached ?: return
        wire(DeviceOutbound.AudioError(callId = callId, error = reason))
        store.error("audio_error", "the call audio failed: $reason")
        attached.audio.close()
        // Only the stream this callback owned: a fatal callback queued behind a
        // replacement `start_audio` is obsolete and must leave the replacement
        // publishing.
        store.audioReset(attached.stream)
    }

    /** One 100 ms publish, captured under the session lock together with its stream. */
    private class AudioLevelTarget(
        val attached: AttachedAudio,
        val callId: String
    )

    /**
     * Closes audio off the calling thread, after the command that detached it.
     * A shutdown drains the queue synchronously; a refused enqueue closes now.
     */
    fun closeAsync(audio: PhoneAudio?) {
        if (audio == null) return
        if (!isRunning() || executor.isShutdown) {
            audio.close()
            return
        }
        pendingAudioClose.add(audio)
        try {
            executor.execute {
                try {
                    audio.close()
                } finally {
                    pendingAudioClose.remove(audio)
                }
            }
        } catch (_: RejectedExecutionException) {
            pendingAudioClose.remove(audio)
            audio.close()
        }
    }

    fun drain() {
        while (true) {
            val audio = pendingAudioClose.poll() ?: return
            audio.close()
        }
    }

    /**
     * Restores a microphone snapshot left behind by a dead process.  Only when
     * Telecom shows no call at all and no bridge call is attached.
     */
    // READ_PHONE_STATE is requested at pairing; a SecurityException is treated
    // as "a call may be in progress", so the detector's concern is handled.
    @SuppressLint("MissingPermission")
    fun restoreAbandonedAtStartup() {
        val manager = context.getSystemService(TelecomManager::class.java) ?: return
        val inCall = try {
            manager.isInCall
        } catch (_: Exception) {
            true
        }
        if (inCall) return
        val idle = synchronized(session.lock) {
            session.owned?.let { !it.finished && it.call != null } != true
        }
        if (!idle) return
        try {
            PhoneAudio.restoreAbandonedMute(context)
        } catch (e: Exception) {
            BridgeLog.e(TAG) { "microphone restore failed: ${e.javaClass.simpleName}" }
        }
    }

    /** Restores the microphone for [callId] unless somebody else's call is live. */
    fun restoreForCall(callId: String, exclude: Call? = null) {
        if (hasUnrelatedCall(exclude)) return
        try {
            PhoneAudio.restoreAbandonedMute(context, callId)
        } catch (e: Exception) {
            BridgeLog.e(TAG) { "microphone restore failed: ${e.javaClass.simpleName}" }
        }
    }
}
