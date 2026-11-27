package com.phonxiety.bridge.engine

import android.os.SystemClock
import android.telecom.Call
import com.phonxiety.bridge.AttachedAudio
import com.phonxiety.bridge.AudioStream
import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.CallAdmission
import com.phonxiety.bridge.IdleLinkAction
import com.phonxiety.bridge.audio.PhoneAudio
import com.phonxiety.bridge.telecom.CallLedgerException
import com.phonxiety.bridge.telecom.CallStore
import java.util.concurrent.atomic.AtomicLong

/**
 * The one bridge-owned call and the door that keeps a link-maintenance action
 * from overlapping its reservation.
 *
 * [lock] is [admission]'s monitor, never a second object: every read and write
 * of [owned], [recent] and [orphanCancels] happens under it, and an idle-only
 * link action admitted through [admission] can therefore never run beside a
 * reservation.
 */
internal class CallSession(
    private val dials: CallStore,
    private val ownerPackage: String,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) {
    companion object {
        private const val TAG = "Bridge:engine"
        private const val MAX_RECENT_CALLS = 32

        /** How long a cancelled dial may still surface as a Telecom Call and be dropped. */
        internal const val ORPHAN_CANCEL_TTL_MS = 10 * 60_000L

        /** How wide around the reservation a matching Telecom creation time may land. */
        internal const val DIAL_CORRELATION_WINDOW_MS = 10_000L

        /**
         * How long a dial waits for an already-admitted idle link action (a
         * network recycle).  Such an action only nulls the
         * endpoint and reports the link down, so it is milliseconds; the
         * deadline covers a wedged native shutdown, after which the dial is
         * refused as `link_down` rather than reserving a call about to be cut.
         */
        internal const val LINK_ACTION_WAIT_MS = 2_000L
    }

    val admission = CallAdmission()
    val lock: Any get() = admission.lock

    /** The owned call; guarded by [lock]. */
    internal var owned: OwnedCall? = null

    /** Recently finished or refused call ids; guarded by [lock]. */
    internal val recent = LinkedHashMap<String, RecentCall>()

    /** Cancelled dials whose Telecom Call has not surfaced yet; guarded by [lock]. */
    internal val orphanCancels = ArrayList<OrphanCancel>()

    /** `dialing`, `active` or `ending` while this engine owns a call. */
    fun activeCallState(): String? = synchronized(lock) {
        val current = owned ?: return null
        if (current.finished) null else current.state
    }

    /**
     * True while this engine owns a bridge call in any non-terminal state:
     * dialing, active or ending.  The iroh link uses it to keep a relayed call
     * alive instead of recycling the endpoint.
     */
    fun ownsCall(): Boolean = synchronized(lock) {
        val current = owned
        current != null && !current.finished
    }

    /**
     * Admits one idle-only link action — recycle the endpoint on a network
     * move — or refuses it while a call is owned or
     * being reserved.  The door stays shut until the action is released, so the
     * link can never close under a call placed a moment later.
     */
    fun tryBeginIdleLinkAction(): IdleLinkAction? = admission.tryBeginIdleLinkAction { ownsCall() }

    /**
     * Reserves one dial under the admission monitor: the duplicate and busy
     * checks, the durable ledger write and the in-memory ownership land in one
     * critical section, so a second dial can never reserve over the first and
     * a failed write leaves nothing owned.
     *
     * [linkUp] and [running] are read under [lock] and must not block;
     * [unrelatedCall] is read before the wait, outside the lock, because
     * Telecom's call list is an IPC.
     */
    fun beginDial(
        callId: String,
        destination: String,
        phoneAccount: String?,
        unrelatedCall: () -> Boolean,
        linkUp: () -> Boolean,
        running: () -> Boolean
    ): DialOutcome {
        // Read before the wait and the lock: Telecom's call list is an IPC and must not run under them.
        val unrelated = unrelatedCall()
        // A network recycle already admitted closes this
        // link as soon as it runs; wait it out instead of racing it.
        if (!admission.awaitIdle(LINK_ACTION_WAIT_MS)) throw DialRefused("link_down")
        synchronized(lock) {
            if (!running()) throw DialRefused("stopped")
            if (admission.actionInFlightLocked()) throw DialRefused("link_down")
            if (!linkUp()) throw DialRefused("link_down")
            val current = owned
            if (current != null) {
                if (current.callId != callId) throw DialRefused("busy")
                if (current.destination != destination) throw DialRefused("duplicate_conflict")
                return DialOutcome.Duplicate(current.state)
            }
            val previous = recent[callId]
            if (previous != null) {
                if (previous.destination != destination) throw DialRefused("duplicate_conflict")
                return DialOutcome.Duplicate(previous.state)
            }
            if (unrelated) throw DialRefused("busy")
            // Correlation is stored before the external action.  A failed
            // durable write aborts the dial instead of proceeding blind.
            val created = OwnedCall(
                callId = callId,
                destination = destination,
                dialStartedWall = System.currentTimeMillis(),
                dialStartedElapsed = clock()
            )
            try {
                dials.reserve(
                    callId = callId,
                    destination = destination,
                    phoneAccount = phoneAccount,
                    createdWall = created.dialStartedWall,
                    createdElapsed = created.dialStartedElapsed
                )
            } catch (_: CallLedgerException) {
                throw DialRefused("state_persist_failed")
            }
            owned = created
            return DialOutcome.Reserved(created)
        }
    }

    /**
     * Prepares a hang-up under [lock]: the tombstone (or the cancellation) is
     * written before the in-memory intent is dropped, and a failed write keeps
     * the owned call so the caller can refuse with `state_persist_failed` and
     * the dial remains recoverable.
     */
    fun planHangupLocked(callId: String): HangupPlan {
        val plan = HangupPlan()
        val current = owned
        if (current == null || current.callId != callId) {
            val previous = recent[callId]
            if (previous != null) {
                plan.terminalState = if (previous.state == "failed") "ended" else previous.state
            } else {
                throw DialRefused("not_owned")
            }
            return plan
        }
        if (current.call == null) {
            // Persist the tombstone before dropping the in-memory intent so a
            // late Call is still rejected after restart.
            try {
                dials.orphan(
                    callId = current.callId,
                    destination = current.destination,
                    dialStartedWall = current.dialStartedWall,
                    ttlMs = ORPHAN_CANCEL_TTL_MS
                )
            } catch (_: CallLedgerException) {
                throw DialRefused("state_persist_failed")
            }
        } else {
            try {
                dials.cancel(current.callId)
            } catch (e: CallLedgerException) {
                BridgeLog.e(TAG) { "cancellation not durable: ${e.message}" }
            }
        }
        current.cancelled = true
        current.generation.set(Long.MAX_VALUE)
        current.detachAudio()?.let {
            plan.audio = it.audio
            plan.stream = it.stream
        }
        current.epoch = -1
        plan.call = current.call
        plan.pendingOnly = plan.call == null
        plan.toneOwner = current
        if (plan.call == null) {
            // Telecom has not surfaced the Call yet.  Do not keep the bridge
            // busy: remember the cancelled intent so a late matching Call is
            // disconnected on arrival, and allow the host to start a different
            // request immediately.
            owned = null
            rememberLocked(current.callId, current.destination, "ended")
            addOrphanLocked(current)
        }
        return plan
    }

    /**
     * Moves the action generation forward for [callId], or returns null when
     * the id is not the owned call or [generation] is stale.  Called under
     * [lock] by `cancel_actions` and by the hang-up's teardown.
     */
    internal fun bumpGenerationLocked(callId: String, generation: Long): OwnedCall? {
        val current = owned ?: return null
        if (current.callId != callId) return null
        val previous = current.generation.get()
        if (generation < previous) return null
        current.generation.set(generation)
        return current
    }

    internal fun rememberLocked(callId: String, destination: String, state: String) {
        recent[callId] = RecentCall(destination, state)
        while (recent.size > MAX_RECENT_CALLS) {
            val oldest = recent.keys.firstOrNull() ?: break
            recent.remove(oldest)
        }
    }

    internal fun addOrphanLocked(call: OwnedCall) {
        val now = clock()
        orphanCancels.removeAll { now > it.expiresAt }
        while (orphanCancels.size >= 4) {
            orphanCancels.removeAt(0)
        }
        orphanCancels.add(
            OrphanCancel(
                callId = call.callId,
                destination = call.destination,
                dialStartedWall = call.dialStartedWall,
                expiresAt = now + ORPHAN_CANCEL_TTL_MS
            )
        )
    }

    internal fun takeOrphanLocked(call: Call): OrphanCancel? {
        if (orphanCancels.isEmpty()) return null
        val details = CallCorrelation.detailsOf(call) ?: return null
        if (details.callDirection != Call.Details.DIRECTION_OUTGOING) return null
        if (details.state == Call.STATE_DISCONNECTED) return null
        val marker = CallCorrelation.extraMarker(details)
        if (marker != null) {
            if (marker.owner != null && marker.owner != ownerPackage) return null
            val index = orphanCancels.indexOfFirst { it.callId == marker.callId }
            if (index < 0) return null
            val orphan = orphanCancels.removeAt(index)
            consumeOrphan(orphan)
            return orphan
        }
        val candidate = details.handle?.schemeSpecificPart ?: return null
        val created = details.creationTimeMillis
        val now = clock()
        var index = 0
        while (index < orphanCancels.size) {
            val orphan = orphanCancels[index]
            if (now > orphan.expiresAt) {
                orphanCancels.removeAt(index)
                continue
            }
            if (CallCorrelation.phoneNumbersMatch(orphan.destination, candidate) &&
                (
                    created <= 0 ||
                        (
                            created >= orphan.dialStartedWall - 5_000 &&
                                created <= orphan.dialStartedWall + DIAL_CORRELATION_WINDOW_MS
                            )
                    )
            ) {
                orphanCancels.removeAt(index)
                consumeOrphan(orphan)
                return orphan
            }
            index++
        }
        return null
    }

    private fun consumeOrphan(orphan: OrphanCancel) {
        try {
            dials.consumeOrphan(orphan.callId, orphan.dialStartedWall)
        } catch (e: CallLedgerException) {
            BridgeLog.e(TAG) { "orphan tombstone not cleared: ${e.message}" }
        }
    }

    /** The result of [beginDial]: a fresh reservation or a replayed duplicate. */
    internal sealed interface DialOutcome {
        class Reserved(val call: OwnedCall) : DialOutcome
        class Duplicate(val state: String) : DialOutcome
    }

    /** A dial or hang-up the session refused, with the daemon's error code. */
    internal class DialRefused(val code: String) : Exception(code)

    /** What [planHangupLocked] took out of the session, for the caller's replies. */
    internal class HangupPlan {
        var audio: PhoneAudio? = null
        var stream: AudioStream? = null
        var call: Call? = null
        var toneOwner: OwnedCall? = null

        /** Set when the id belongs to a finished call rather than the owned one. */
        var terminalState: String? = null
        var pendingOnly: Boolean = false
    }

    internal class OwnedCall(
        val callId: String,
        val destination: String,
        val dialStartedWall: Long = System.currentTimeMillis(),
        val dialStartedElapsed: Long = SystemClock.elapsedRealtime()
    ) {
        val generation = AtomicLong(0)
        var call: Call? = null
        var callback: Call.Callback? = null
        var state: String = "dialing"
        var telecomState: Int = -1

        /** The audio and the store stream it publishes into; the two never travel apart. */
        var attached: AttachedAudio? = null
        var epoch: Long = -1
        var lastEpoch: Long = -1
        var cancelled: Boolean = false
        var finished: Boolean = false
        var reportedState: String? = null
        var reportedCauseKey: String? = null

        /**
         * Takes the audio out of this call.  The caller resets exactly
         * [AttachedAudio.stream] and nothing else, so a cleanup that runs after
         * a successor attached cannot clear the successor's numbers.
         */
        fun detachAudio(): AttachedAudio? = attached.also { attached = null }

        /**
         * Takes the audio of the callback at [epoch], or returns null when that
         * callback is obsolete (another epoch, or nothing attached).  An
         * obsolete fatal callback must change nothing, including the store.
         */
        fun detachAudioAt(epoch: Long): AttachedAudio? {
            if (attached == null || this.epoch != epoch) return null
            this.epoch = -1
            return detachAudio()
        }

        /** The stream to publish from, or null once this call has finished. */
        fun audioToPublish(): AttachedAudio? = if (finished) null else attached
    }

    internal data class RecentCall(val destination: String, val state: String)

    internal data class OrphanCancel(
        val callId: String,
        val destination: String,
        val dialStartedWall: Long,
        val expiresAt: Long
    )
}
