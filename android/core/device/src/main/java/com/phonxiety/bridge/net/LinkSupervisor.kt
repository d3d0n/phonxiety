package com.phonxiety.bridge.net

import com.phonxiety.bridge.BridgeLog
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import okio.ByteString

/** A scheduled task that has not run yet; cancelling it keeps it from running. */
internal fun interface LinkTask {
    fun cancel()
}

/**
 * Where [LinkSupervisor] gets time and timers.  Injected so the supervisor runs
 * on a plain JVM: production uses [ExecutorLinkScheduler] on the engine's
 * single scheduler thread.
 */
internal interface LinkScheduler {
    /** Runs [task] once after [delayMs] milliseconds; may throw when shut down. */
    fun after(delayMs: Long, task: () -> Unit): LinkTask
}

/** Runs supervisor work on the engine's single-threaded scheduler. */
internal class ExecutorLinkScheduler(
    private val executor: ScheduledExecutorService
) : LinkScheduler {
    override fun after(delayMs: Long, task: () -> Unit): LinkTask {
        val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        return LinkTask { future.cancel(false) }
    }
}

/**
 * Owns the single live [DeviceLink]: connecting, the per-attempt listener with
 * owner checks, reconnect backoff, the host-activity deadline, the gate that
 * keeps `hello` the first post-authentication control frame and the two send
 * paths with the audio backlog cap.
 *
 * The protocol and call control stay in
 * [com.phonxiety.bridge.BridgeEngine], which talks to the link only through
 * [Callbacks].  Callbacks always run outside the supervisor lock and in the
 * order the transport produced the events.
 */
internal class LinkSupervisor(
    private val factory: DeviceLinkFactory,
    private val callbacks: Callbacks,
    private val scheduler: LinkScheduler,
    private val clock: () -> Long
) {
    /** What the engine does with link events. */
    interface Callbacks {
        /**
         * Called once the link is current and counted as live.  The engine
         * queues `hello` here through [sendHello] and returns whether that send
         * was accepted; false reports the link down as `hello_failed`.
         */
        fun onLinkOpened(): Boolean

        /** One JSON control message; already counted as host activity. */
        fun onLinkText(text: String)

        /** One binary message; already counted as host activity. */
        fun onLinkBinary(bytes: ByteString)

        /**
         * The link is down and no longer current.  The supervisor schedules
         * the reconnect after this returns.
         */
        fun onLinkDown(reason: String)
    }

    companion object {
        private const val TAG = "Bridge:link"

        /** No host message for longer than this means the peer is gone. */
        const val HOST_TIMEOUT_MS = 10_000L
        const val RECONNECT_BASE_MS = 1_000L
        const val RECONNECT_MAX_MS = 8_000L

        /**
         * One attempt that neither opens nor reports down is over after this:
         * a boot-time dial with no usable network must not hold the slot for
         * the process lifetime.
         */
        const val ATTEMPT_TIMEOUT_MS = 20_000L

        /**
         * 500 ms of 48 kHz mono PCM16: above this backlog a live capture frame
         * is dropped instead of turning a network stall into growing latency.
         */
        const val MAX_AUDIO_BACKLOG_BYTES = 48_000L
    }

    private val lock = Any()

    /**
     * Whether the calling thread is inside a supervisor transition. Attempt outcomes must be
     * applied there, in transition order; the tests assert it rather than racing threads.
     */
    internal fun inTransition(): Boolean = Thread.holdsLock(lock)

    private var running = false
    private var closed = false
    private var link: DeviceLink? = null
    private var linkUp = false

    /**
     * True once the live link's `hello` was accepted by the transport.  The
     * daemon reads the first post-authentication control frame as the
     * handshake and closes the link when it is not `hello`, so every other
     * control, audio and attachment operation refuses until this is true.
     */
    private var helloQueued = false
    private var reconnectScheduled = false
    private var reconnectDelayMs = RECONNECT_BASE_MS
    private var reconnectTask: LinkTask? = null
    private var attemptTask: LinkTask? = null
    private var lastHostActivity = 0L
    private var opens = 0

    /**
     * Begins connecting and keeps reconnecting until [cancel].  Called once by
     * the engine after its startup work; a call after [cancel] does nothing.
     */
    fun start() {
        synchronized(lock) {
            if (running || closed) return
            running = true
        }
        connect()
    }

    /**
     * Stops for good: no reconnect, no more callbacks.  The current link is
     * closed without reporting down.  Never blocks.
     */
    fun cancel() {
        val current: DeviceLink?
        synchronized(lock) {
            if (closed) return
            closed = true
            running = false
            linkUp = false
            helloQueued = false
            lastHostActivity = 0L
            current = link
            link = null
            clearAttemptDeadlineLocked()
            reconnectTask?.cancel()
            reconnectTask = null
            reconnectScheduled = false
        }
        if (current != null) runCatching { current.cancel() }
    }

    /** Releases the factory once, at the end of the engine's close. */
    fun shutdown() {
        runCatching { factory.shutdown() }
    }

    fun isUp(): Boolean = synchronized(lock) { linkUp }

    /** Candidate the current (or last) attempt was built for, when the factory chooses one. */
    fun selectedCandidate(): SelectedCandidate? = factory.selected()

    /**
     * One idle selection round: true when a probe says another candidate now
     * wins by the hysteresis rule, so the owner drops the link to move to it.
     * Never true while the transport has no selection.
     */
    fun considerSwitch(): Boolean = factory.considerSwitch()

    /** Route of the live link for the UI, or null while down. */
    fun route(): DeviceLink.Route? = synchronized(lock) {
        if (!linkUp) null else link?.route()
    }

    /** Successful opens after the first one; the UI's reconnect counter. */
    fun reconnects(): Int = synchronized(lock) { (opens - 1).coerceAtLeast(0) }

    /** True while the live transport can carry a phone-opened attachment stream. */
    fun supportsAttachments(): Boolean = synchronized(lock) {
        linkUp && link?.supportsAttachments == true
    }

    /** Bytes accepted but not yet written to the live link. */
    fun backlogBytes(): Long = synchronized(lock) { if (linkUp) link?.backlogBytes ?: 0L else 0L }

    /**
     * Opens one attachment stream on the live link; null while down, unsupported
     * or before the link's `hello` was queued.
     */
    suspend fun openAttachment(header: String): AttachmentChannel? {
        val target = synchronized(lock) {
            if (linkUp && helloQueued) link else null
        } ?: return null
        return target.openAttachment(header)
    }

    /**
     * Queues one JSON control message; false when there is no live link or its
     * `hello` is not queued yet.  The daemon closes a link whose first
     * post-authentication control frame is not `hello`.
     */
    fun sendText(message: String): Boolean = synchronized(lock) {
        if (!linkUp || !helloQueued) return false
        val target = link ?: return false
        target.sendText(message)
    }

    /** False when the frame was discarded: no link, no hello, or a backlog above the cap. */
    fun sendFrame(frame: ByteArray, length: Int): Boolean = synchronized(lock) {
        if (!linkUp || !helloQueued) return false
        val target = link ?: return false
        // Live capture: never let a stalled network turn into growing latency.
        // Control messages use sendText and are never dropped.
        if (target.backlogBytes > MAX_AUDIO_BACKLOG_BYTES) return false
        target.sendBinary(frame, length)
        true
    }

    /**
     * Queues the live link's `hello`, the only frame allowed to precede it.
     *
     * The gate check and the hand-off to the transport happen under [lock], the
     * same monitor every other send checks, so a producer can never enqueue
     * between them: whichever runs first wins, and the hello either goes out
     * first or not at all.  Both transports only enqueue in `sendText` (iroh's
     * unlimited control channel, OkHttp's send queue) — no I/O and no
     * suspension under the lock.
     *
     * False while down or when a hello is already queued; the flag is set only
     * after the transport accepted the frame, so a refused hello leaves every
     * other send refused too.
     */
    fun sendHello(message: String): Boolean = synchronized(lock) {
        if (!linkUp || helloQueued) return false
        val target = link ?: return false
        if (!target.sendText(message)) return false
        helloQueued = true
        true
    }

    /**
     * Host-activity deadline check; the engine's existing 1 s scheduler drives
     * it.  A live link that has heard nothing from the host for longer than
     * [HOST_TIMEOUT_MS] is reported down as `host_timeout`.
     */
    fun tick() {
        val now = clock()
        val stale = synchronized(lock) {
            if (!running || !linkUp) return
            if (now - lastHostActivity <= HOST_TIMEOUT_MS) return
            link
        } ?: return
        BridgeLog.w(TAG, "host activity deadline expired")
        handleDown(stale, "host_timeout")
        runCatching { stale.cancel() }
    }

    private fun connect() {
        synchronized(lock) {
            if (!running || link != null) return
        }
        val attempt = try {
            factory.create()
        } catch (t: Throwable) {
            // Building one attempt — a candidate plan or a probe — is itself an
            // attempt: a throw moves to the next dial instead of ending here.
            BridgeLog.w(TAG) { "link attempt creation failed: ${t.javaClass.simpleName}" }
            // No candidate was selected, so the transport is unknown and this
            // never feeds a transport's own recovery accounting.
            factory.attemptEnded(AttemptOutcome.FAILED_BEFORE_OPEN, null)
            scheduleReconnect()
            return
        }
        val installed = synchronized(lock) {
            when {
                !running -> false

                link == null -> {
                    link = attempt
                    true
                }

                else -> false
            }
        }
        if (!installed) {
            runCatching { attempt.cancel() }
            return
        }
        try {
            // Not under the lock: a link may call onOpen before start() returns.
            attempt.start(Listener(attempt))
        } catch (t: Throwable) {
            BridgeLog.w(TAG) { "link attempt start failed: ${t.javaClass.simpleName}" }
            handleDown(attempt, "connect_failed")
            runCatching { attempt.cancel() }
            return
        }
        armAttemptDeadline(attempt)
    }

    /**
     * Arms the one deadline for [owner]: an attempt that neither opens nor
     * reports down inside [ATTEMPT_TIMEOUT_MS] is dropped as `connect_timeout`
     * so the backoff re-dials.  A link that opened in the meantime keeps no
     * deadline at all.
     */
    private fun armAttemptDeadline(owner: DeviceLink) {
        val task = try {
            scheduler.after(ATTEMPT_TIMEOUT_MS) { expireAttempt(owner) }
        } catch (_: RejectedExecutionException) {
            return
        }
        val keep = synchronized(lock) {
            if (running && link === owner && !linkUp) {
                attemptTask = task
                true
            } else {
                false
            }
        }
        if (!keep) task.cancel()
    }

    /**
     * Retires the attempt under the lock: an open that arrives after this sees
     * no slot and is refused, so the report below is the attempt's only fate.
     * Notification and cancellation run outside the lock, in that order.
     */
    private fun expireAttempt(owner: DeviceLink) {
        val notify = synchronized(lock) {
            if (!running || linkUp || link !== owner) return
            noteOutcomeLocked(AttemptOutcome.FAILED_BEFORE_OPEN, owner)
            retireCurrentLocked()
        }
        if (!notify) return
        notifyDown("connect_timeout")
        runCatching { owner.cancel() }
    }

    /** Caller holds [lock]. */
    private fun clearAttemptDeadlineLocked() {
        attemptTask?.cancel()
        attemptTask = null
    }

    /**
     * Caller holds [lock] and the attempt is current: hands the slot back.
     * Clearing the deadline here is what keeps an open racing the deadline from
     * being torn down as `connect_timeout`.
     */
    private fun retireCurrentLocked(): Boolean {
        link = null
        linkUp = false
        helloQueued = false
        lastHostActivity = 0L
        clearAttemptDeadlineLocked()
        return running
    }

    /**
     * Feeds one attempt's outcome to the transport's recovery account.
     *
     * Callers hold [lock] and call this in the very critical section as the
     * state transition it classifies, so outcomes are applied in transition
     * order.  A report made after the lock is released can be delayed past the
     * attempts that follow: an `OPENED` landing between their failures resets
     * the streak those newer attempts built, and three consecutive failures
     * then never trip.  Carrying a per-attempt sequence number and dropping
     * stale outcomes would need a new parameter through [DeviceLinkFactory] and
     * [IrohAttemptOutcomes] plus a second piece of budget state; applying the
     * report here gets the same order for free.
     *
     * Safe under the lock: the production path is
     * [CandidateLinkFactory.attemptEnded] → [IrohAttemptOutcomes.report] →
     * [IrohLinkFactory.attemptEnded] → [IrohFailureBudget.note], which takes
     * only its own short-lived monitor and reads the injected clock — no I/O,
     * no suspension, and nothing that calls back into this supervisor.  The
     * lock order is supervisor → budget only, so no cycle (deadlock) is
     * possible.
     */
    private fun noteOutcomeLocked(outcome: AttemptOutcome, owner: DeviceLink) {
        factory.attemptEnded(outcome, owner.kind)
    }

    /** Binds one transport attempt to its own callbacks. */
    private inner class Listener(private val owner: DeviceLink) : DeviceLink.Listener {
        override fun onLinkOpened() = this@LinkSupervisor.handleOpen(owner)

        override fun onLinkText(text: String) = this@LinkSupervisor.handleText(owner, text)

        override fun onLinkBinary(bytes: ByteString) = this@LinkSupervisor.handleBinary(owner, bytes)

        override fun onLinkDown(reason: String) = this@LinkSupervisor.handleDown(owner, reason)
    }

    private fun handleOpen(owner: DeviceLink) {
        val accepted = synchronized(lock) {
            if (!running || link !== owner) {
                // A late open after the deadline retired the attempt, or one
                // that lost the race: never live, never delivered as opened.
                false
            } else {
                linkUp = true
                // A fresh link starts closed until its own hello is queued.
                helloQueued = false
                opens++
                reconnectScheduled = false
                reconnectDelayMs = RECONNECT_BASE_MS
                lastHostActivity = clock()
                clearAttemptDeadlineLocked()
                // This attempt's one outcome: it opened.  A later down is not a
                // second classification, and the accepted-open state it carries
                // is what keeps a post-open failure out of the transport's
                // recovery accounting.  Applied inside this accept, so a report
                // can never arrive after the failures of the attempts that
                // replace this one.
                noteOutcomeLocked(AttemptOutcome.OPENED, owner)
                true
            }
        }
        if (!accepted) {
            // Outside the lock: closing the refused transport must not run
            // under the supervisor state.
            runCatching { owner.cancel() }
            return
        }
        // A hello that throws is this attempt's failure, not the end of the
        // supervisor: report down and let the backoff re-dial.
        val hello = try {
            callbacks.onLinkOpened()
        } catch (t: Throwable) {
            BridgeLog.w(TAG) { "link hello failed: ${t.javaClass.simpleName}" }
            false
        }
        if (!hello) {
            // The failed transport is cancelled too: nothing abandoned here may
            // keep readers, writers or subscriptions alive beside the reconnect.
            handleDown(owner, "hello_failed")
            runCatching { owner.cancel() }
        }
    }

    private fun handleText(owner: DeviceLink, text: String) {
        synchronized(lock) {
            if (link !== owner) return
            lastHostActivity = clock()
        }
        callbacks.onLinkText(text)
    }

    private fun handleBinary(owner: DeviceLink, bytes: ByteString) {
        synchronized(lock) {
            if (link !== owner) return
            lastHostActivity = clock()
        }
        callbacks.onLinkBinary(bytes)
    }

    private fun handleDown(owner: DeviceLink, reason: String) {
        val notify = synchronized(lock) {
            // Only the current attempt may report down.  A retired attempt that
            // surfaces late (e.g. after its deadline) is no longer the link, and
            // a replaced one must not disturb its successor.
            if (link !== owner) return
            // Read before the retirement clears it: an attempt that was accepted
            // as open has already reported that one outcome, whatever reason
            // brings it down now — `hello_failed` and `connect_failed` can both
            // arrive after QUIC connected.
            if (!linkUp) noteOutcomeLocked(AttemptOutcome.FAILED_BEFORE_OPEN, owner)
            retireCurrentLocked()
        }
        if (!notify) return
        notifyDown(reason)
    }

    /** The slot is already given up: tell the engine and arm the backoff. */
    private fun notifyDown(reason: String) {
        BridgeLog.w(TAG, "device link down: $reason")
        callbacks.onLinkDown(reason)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val delay: Long
        synchronized(lock) {
            if (!running || reconnectScheduled) return
            reconnectScheduled = true
            delay = reconnectDelayMs
            reconnectDelayMs = minOf(reconnectDelayMs * 2, RECONNECT_MAX_MS)
        }
        val task = try {
            scheduler.after(delay) { reconnectNow() }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { reconnectScheduled = false }
            return
        }
        val keep = synchronized(lock) {
            if (reconnectScheduled) {
                reconnectTask = task
                true
            } else {
                false
            }
        }
        if (!keep) task.cancel()
    }

    private fun reconnectNow() {
        synchronized(lock) {
            reconnectScheduled = false
            reconnectTask = null
        }
        connect()
    }

    /**
     * Drops the live link so the next attempt picks a candidate again, e.g.
     * after the connection mode changed.  Reports down, so the reconnect runs
     * through the usual backoff; false when nothing is live.
     */
    fun reconnect(reason: String): Boolean {
        val current = synchronized(lock) {
            if (!running || !linkUp) null else link
        } ?: return false
        handleDown(current, reason)
        runCatching { current.cancel() }
        return true
    }

    /**
     * A network just became available: a link waiting out its backoff tries
     * again now, from the base delay, instead of up to 8 s later.
     */
    fun networkAvailable() {
        val pending = synchronized(lock) {
            if (!running || linkUp || !reconnectScheduled) return
            reconnectDelayMs = RECONNECT_BASE_MS
            reconnectTask.also { reconnectTask = null }
        }
        pending?.cancel()
        reconnectNow()
    }
}
