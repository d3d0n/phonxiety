package com.phonxiety.bridge.telecom

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.telecom.Call
import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.BridgeRuntime
import com.phonxiety.bridge.EngineStateStore
import com.phonxiety.bridge.audio.AudioStatsPublisher
import com.phonxiety.bridge.callPhaseOf
import com.phonxiety.bridge.engine.CallCorrelation
import com.phonxiety.bridge.engine.CallSession
import com.phonxiety.bridge.engine.StateReportPublisher
import com.phonxiety.bridge.maskDestination
import com.phonxiety.bridge.model.DisconnectCause
import com.phonxiety.bridge.releaseDetachedAudio
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns Telecom's call callbacks into the bridge's owned-call transitions.
 *
 * The tombstone is written inside the session's monitor and before the
 * in-memory intent is dropped, so a dial that Telecom has not surfaced yet is
 * still refused after a restart and a late Call is disconnected on arrival.
 * Exactly one [finishOwned] runs per Call: every terminal path checks the
 * identity of the owned Call first.
 */
internal class TelecomObserver(
    private val session: CallSession,
    private val dials: CallStore,
    private val store: EngineStateStore,
    private val report: StateReportPublisher,
    private val audio: AudioStatsPublisher,
    private val ownerPackage: String,
    private val isRunning: () -> Boolean,
    private val cancelTone: (CallSession.OwnedCall) -> Unit
) {
    companion object {
        private const val TAG = "Bridge:engine"
        private const val PENDING_DIAL_TIMEOUT_MS = 120_000L
    }

    private val callbackThread = HandlerThread("phonxiety-callbacks").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    fun close() {
        callbackThread.quitSafely()
    }

    fun onCallAdded(call: Call) {
        if (!isRunning()) return
        var adopted: CallSession.OwnedCall? = null
        var cancelledDial = false
        var registered = false
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                this@TelecomObserver.onCallStateChanged(call, state)
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                this@TelecomObserver.onCallDetailsChanged(call, details)
            }

            override fun onCallDestroyed(call: Call) {
                this@TelecomObserver.onCallDestroyed(call)
            }
        }

        var orphanCancellation: CallSession.OrphanCancel? = null
        synchronized(session.lock) {
            val current = session.owned
            if (current != null && current.call === call) {
                // Duplicate delivery for the already-adopted Call; never let a
                // stale tombstone act on it.
                return
            }
            if (current != null && !current.finished && current.call == null &&
                matchesPending(current, call)
            ) {
                current.call = call
                current.callback = callback
                adopted = current
                cancelledDial = current.cancelled
                current.telecomState = CallCorrelation.stateOf(call)
            } else if (current == null || current.call == null) {
                orphanCancellation = session.takeOrphanLocked(call)
                if (orphanCancellation == null) {
                    BridgeLog.i(TAG, "ignoring telecom call not owned by the bridge")
                    return
                }
            } else {
                BridgeLog.i(TAG, "ignoring telecom call while an owned call is attached")
                return
            }
        }

        val orphan = orphanCancellation
        if (orphan != null) {
            // A dial the host already cancelled has finally surfaced.  Drop it
            // without touching any current bridge-owned call, and restore the
            // persisted microphone snapshot only once it is disconnected.
            val restored = AtomicBoolean(false)
            val cleanup: (Call) -> Unit = { target ->
                if (restored.compareAndSet(false, true)) {
                    audio.restoreForCall(orphan.callId, target)
                }
            }
            val orphanCallback = object : Call.Callback() {
                override fun onStateChanged(call: Call, state: Int) {
                    if (state == Call.STATE_DISCONNECTED) {
                        runCatching { call.unregisterCallback(this) }
                        cleanup(call)
                    }
                }

                override fun onCallDestroyed(call: Call) {
                    runCatching { call.unregisterCallback(this) }
                    cleanup(call)
                }
            }
            val registeredOrphan = try {
                call.registerCallback(orphanCallback, callbackHandler)
                true
            } catch (_: Exception) {
                false
            }
            runCatching { call.disconnect() }
            if (!registeredOrphan || CallCorrelation.stateOf(call) == Call.STATE_DISCONNECTED) {
                // No callback will arrive for a Call that is already gone.
                cleanup(call)
            }
            return
        }

        val target = adopted ?: return
        try {
            call.registerCallback(callback, callbackHandler)
            registered = true
        } catch (e: Exception) {
            BridgeLog.e(TAG) { "call callback registration failed: ${e.javaClass.simpleName}" }
        }
        report.report(target, CallCorrelation.mapState(CallCorrelation.stateOf(call)), CallCorrelation.disconnectCause(call))
        if (cancelledDial) {
            try {
                call.disconnect()
            } catch (_: Exception) {
                BridgeLog.w(TAG, "could not disconnect cancelled dial")
            }
        }
        if (!registered) {
            BridgeLog.w(TAG, "owned call has no state callbacks")
        }
    }

    fun onCallRemoved(call: Call) {
        finishOwned(call, CallCorrelation.disconnectCause(call))
    }

    private fun onCallStateChanged(call: Call, state: Int) {
        if (!isRunning()) return
        var detached: com.phonxiety.bridge.AttachedAudio? = null
        var toneOwner: CallSession.OwnedCall? = null
        var mapped = "dialing"
        var finish = false
        var cause: DisconnectCause? = null
        synchronized(session.lock) {
            val current = session.owned
            if (current == null || current.call !== call || current.finished) return
            current.telecomState = state
            cause = CallCorrelation.disconnectCause(call)
            mapped = CallCorrelation.mapState(state)
            if (mapped == "ending" || mapped == "ended") {
                current.generation.set(Long.MAX_VALUE)
                detached = current.detachAudio()
                current.epoch = -1
                toneOwner = current
            }
            // Inside the monitor, as before the split: the plan and its record are one
            // critical section with Telecom's state, and the frame follows it here.
            report.report(current, mapped, cause)
            finish = mapped == "ended"
        }
        releaseDetachedAudio(store, detached) { audio.closeAsync(it) }
        toneOwner?.let { cancelTone(it) }
        if (finish) finishOwned(call, cause)
    }

    private fun onCallDetailsChanged(call: Call, details: Call.Details) {
        if (!isRunning()) return
        var detached: com.phonxiety.bridge.AttachedAudio? = null
        var toneOwner: CallSession.OwnedCall? = null
        var mapped = "dialing"
        var finish = false
        var cause: DisconnectCause? = null
        synchronized(session.lock) {
            val current = session.owned
            if (current == null || current.call !== call || current.finished) return
            current.telecomState = details.state
            cause = CallCorrelation.disconnectCause(details)
            mapped = CallCorrelation.mapState(details.state)
            if (mapped == "ending" || mapped == "ended") {
                current.generation.set(Long.MAX_VALUE)
                detached = current.detachAudio()
                current.epoch = -1
                toneOwner = current
            }
            // Inside the monitor, as before the split: the plan and its record are one
            // critical section with Telecom's state, and the frame follows it here.
            report.report(current, mapped, cause)
            finish = mapped == "ended"
        }
        releaseDetachedAudio(store, detached) { audio.closeAsync(it) }
        toneOwner?.let { cancelTone(it) }
        if (finish) finishOwned(call, cause)
    }

    private fun onCallDestroyed(call: Call) {
        finishOwned(call, CallCorrelation.disconnectCause(call))
    }

    private fun finishOwned(call: Call, cause: DisconnectCause?) {
        var audioSession: com.phonxiety.bridge.audio.PhoneAudio? = null
        var stream: com.phonxiety.bridge.AudioStream? = null
        var callback: Call.Callback? = null
        var finished: CallSession.OwnedCall? = null
        synchronized(session.lock) {
            val current = session.owned
            if (current == null || current.call !== call || current.finished) return
            current.finished = true
            current.state = "ended"
            current.detachAudio()?.let {
                audioSession = it.audio
                stream = it.stream
            }
            current.epoch = -1
            callback = current.callback
            current.callback = null
            finished = current
            session.owned = null
            session.rememberLocked(current.callId, current.destination, "ended")
            try {
                dials.finish(current.callId, current.destination, "ended")
            } catch (e: CallLedgerException) {
                BridgeLog.e(TAG) { "terminal state not durable: ${e.message}" }
            }
        }
        val target = finished ?: return
        report.report(target, "ended", cause)
        audio.closeAsync(audioSession)
        callback?.let { cb -> runCatching { call.unregisterCallback(cb) } }
        cancelTone(target)
        audio.restoreForCall(target.callId, call)
        // Stream-scoped, and only when this call still had audio: a hang-up
        // already detached and reset it, and a call that started while this one
        // was finishing keeps the stats its own stream is already publishing.
        stream?.let { store.audioReset(it) }
    }

    private fun matchesPending(current: CallSession.OwnedCall, call: Call): Boolean {
        val details = CallCorrelation.detailsOf(call) ?: return false
        if (details.callDirection != Call.Details.DIRECTION_OUTGOING) return false
        if (details.state == Call.STATE_DISCONNECTED) return false
        val marker = CallCorrelation.extraMarker(details)
        if (marker != null) {
            // An explicit marker is authoritative: never adopt a Call tagged
            // for another bridge intent, even when the number matches.
            if (marker.owner != null && marker.owner != ownerPackage) return false
            return marker.callId == current.callId
        }
        val handle = details.handle ?: return false
        val candidate = handle.schemeSpecificPart ?: return false
        if (!CallCorrelation.phoneNumbersMatch(current.destination, candidate)) return false
        val created = details.creationTimeMillis
        if (created > 0 &&
            (
                created < current.dialStartedWall - 5_000 ||
                    created > current.dialStartedWall + CallSession.DIAL_CORRELATION_WINDOW_MS
                )
        ) {
            return false
        }
        return true
    }

    fun abortOwnedForLinkLoss() {
        var attached: com.phonxiety.bridge.AttachedAudio? = null
        var call: Call? = null
        var toneOwner: CallSession.OwnedCall? = null
        var pendingOnly = false
        synchronized(session.lock) {
            val current = session.owned ?: return
            val lateCall = current.call == null
            try {
                if (lateCall) {
                    dials.orphan(
                        callId = current.callId,
                        destination = current.destination,
                        dialStartedWall = current.dialStartedWall,
                        ttlMs = CallSession.ORPHAN_CANCEL_TTL_MS
                    )
                } else {
                    dials.cancel(current.callId)
                }
            } catch (e: CallLedgerException) {
                BridgeLog.e(TAG) { "link-loss cancellation not durable: ${e.message}" }
            }
            current.cancelled = true
            current.generation.set(Long.MAX_VALUE)
            attached = current.detachAudio()
            current.epoch = -1
            call = current.call
            toneOwner = current
            pendingOnly = call == null
            if (pendingOnly) {
                session.owned = null
                session.rememberLocked(current.callId, current.destination, "ended")
                session.addOrphanLocked(current)
            }
        }
        audio.closeAsync(attached?.audio)
        toneOwner?.let { cancelTone(it) }
        call?.let { runCatching { it.disconnect() } }
        if (pendingOnly) {
            BridgeLog.w(TAG, "link lost before the telecom call appeared; cancellation retained as an orphan")
        }
        // Scoped to the stream this abort detached: the successor of a link-loss
        // abort must be able to publish the moment it attaches.
        attached?.let { store.audioReset(it.stream) }
    }

    fun abortOwnedOnShutdown() {
        var audioSession: com.phonxiety.bridge.audio.PhoneAudio? = null
        var call: Call? = null
        var callback: Call.Callback? = null
        var toneOwner: CallSession.OwnedCall? = null
        synchronized(session.lock) {
            val current = session.owned ?: return
            try {
                dials.orphan(
                    callId = current.callId,
                    destination = current.destination,
                    dialStartedWall = current.dialStartedWall,
                    ttlMs = CallSession.ORPHAN_CANCEL_TTL_MS
                )
            } catch (e: CallLedgerException) {
                BridgeLog.e(TAG) { "shutdown cancellation not durable: ${e.message}" }
            }
            current.cancelled = true
            current.generation.set(Long.MAX_VALUE)
            current.finished = true
            current.state = "ended"
            audioSession = current.detachAudio()?.audio
            current.epoch = -1
            call = current.call
            callback = current.callback
            current.callback = null
            toneOwner = current
            session.owned = null
            session.rememberLocked(current.callId, current.destination, "ended")
            session.addOrphanLocked(current)
        }
        audioSession?.close()
        callback?.let { cb -> runCatching { call?.unregisterCallback(cb) } }
        call?.let { runCatching { it.disconnect() } }
        toneOwner?.let { cancelTone(it) }
        store.callEnded()
        // Whole-engine shutdown: nothing may publish after this.
        store.resetAllAudio()
    }

    fun expirePendingDial() {
        runCatching { dials.pruneOrphans() }
        var expired: CallSession.OwnedCall? = null
        synchronized(session.lock) {
            val now = SystemClock.elapsedRealtime()
            session.orphanCancels.removeAll { now > it.expiresAt }
            val current = session.owned ?: return
            if (current.call != null || current.finished) return
            if (now - current.dialStartedElapsed < PENDING_DIAL_TIMEOUT_MS) return
            current.finished = true
            current.state = "ended"
            session.owned = null
            session.rememberLocked(current.callId, current.destination, "ended")
            try {
                dials.finish(current.callId, current.destination, "ended")
            } catch (e: CallLedgerException) {
                BridgeLog.e(TAG) { "pending expiry not durable: ${e.message}" }
            }
            expired = current
        }
        val target = expired ?: return
        BridgeLog.w(TAG, "pending dial expired without a telecom call")
        report.report(target, "ended", null)
        audio.restoreForCall(target.callId)
    }

    /**
     * Restores the ledger snapshot into the session.  The caller holds
     * [CallSession.lock]: the engine's start takes it around this restore,
     * after reading the live calls outside it, since Telecom's call list is an
     * IPC.
     */
    fun restoreLedgerLocked(liveCalls: List<Call>) {
        val snapshot = dials.current()
        session.recent.clear()
        for (entry in snapshot.recent) {
            session.recent[entry.callId] = CallSession.RecentCall(entry.destination, entry.state)
        }
        session.orphanCancels.clear()
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        for (orphan in snapshot.orphans) {
            val remaining = orphan.expiresWall - nowWall
            if (remaining <= 0L) continue
            session.orphanCancels.add(
                CallSession.OrphanCancel(
                    callId = orphan.callId,
                    destination = orphan.destination,
                    dialStartedWall = orphan.dialStartedWall,
                    expiresAt = nowElapsed + remaining
                )
            )
        }
        val pending = snapshot.pending ?: return
        if (pending.cancelled) {
            // The previous process died while terminating this dial.  Keep it
            // only as a late-call tombstone; the intent is never re-dialled.
            try {
                dials.orphan(
                    callId = pending.callId,
                    destination = pending.destination,
                    dialStartedWall = pending.createdWall,
                    ttlMs = CallSession.ORPHAN_CANCEL_TTL_MS
                )
            } catch (e: CallLedgerException) {
                BridgeLog.e(TAG) { "recovered cancellation not durable: ${e.message}" }
            }
            val synthetic = CallSession.OwnedCall(
                callId = pending.callId,
                destination = pending.destination,
                dialStartedWall = pending.createdWall,
                dialStartedElapsed = pending.createdElapsed
            )
            synthetic.cancelled = true
            synthetic.finished = true
            session.rememberLocked(pending.callId, pending.destination, "ended")
            session.addOrphanLocked(synthetic)
            return
        }
        if (pending.state == "ended") {
            // The Call already reached a terminal state; keep only the ID so a
            // repeated call_id is still refused.
            session.rememberLocked(pending.callId, pending.destination, "ended")
            try {
                dials.finish(pending.callId, pending.destination, "ended")
            } catch (e: CallLedgerException) {
                BridgeLog.e(TAG) { "recovered terminal state not durable: ${e.message}" }
            }
            return
        }
        // Ownership survived the restart and is advertised in hello.  No
        // placeCall is issued; the Call is adopted when Telecom surfaces it.
        val restored = CallSession.OwnedCall(
            callId = pending.callId,
            destination = pending.destination,
            dialStartedWall = pending.createdWall,
            dialStartedElapsed = pending.createdElapsed
        )
        restored.state = pending.state
        restored.reportedState = pending.state
        session.owned = restored
        BridgeLog.i(TAG, "recovered bridge call intent from durable state")
        // The placement is the durable one, and the phase is only replayed: the engine did not see
        // this call answer, so the restore's own clock may not stamp one. Telecom's connect time is
        // the one fact about that answer, and it exists only once the live Call does.
        store.callStarted(pending.callId, maskDestination(pending.destination), pending.createdWall)
        store.callPhaseReplayed(callPhaseOf(pending.state), connectTimeMillis = telecomConnectTime(restored, liveCalls))
    }

    /** Telecom's own connect time for the live call [restored] adopts, when it exists and reports one. */
    private fun telecomConnectTime(restored: CallSession.OwnedCall, calls: List<Call>): Long? {
        for (call in calls) {
            if (!matchesPending(restored, call)) continue
            return CallCorrelation.detailsOf(call)?.connectTimeMillis?.takeIf { it > 0 }
        }
        return null
    }

    fun adoptExistingCalls() {
        val relevant = synchronized(session.lock) {
            val pending = session.owned?.let { if (!it.finished && it.call == null) it else null }
            pending != null || session.orphanCancels.isNotEmpty()
        }
        if (!relevant) return
        for (call in BridgeRuntime.currentCalls()) {
            onCallAdded(call)
        }
    }

    fun hasUnrelatedLiveCall(exclude: Call? = null): Boolean {
        val ownCall = synchronized(session.lock) { session.owned?.call }
        return BridgeRuntime.currentCalls().any { call ->
            if (call === ownCall || (exclude != null && call === exclude)) {
                false
            } else {
                try {
                    call.details?.state != Call.STATE_DISCONNECTED
                } catch (_: Exception) {
                    true
                }
            }
        }
    }
}
