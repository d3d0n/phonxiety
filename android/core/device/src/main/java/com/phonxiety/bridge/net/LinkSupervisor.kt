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
 * owner checks, reconnect backoff, the host-activity deadline and the two send
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
         * sends `hello` here and returns whether that send was queued; false
         * reports the link down as `hello_failed`.
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
        private const val TAG = "PhonxietyBridge"

        /** No host message for longer than this means the peer is gone. */
        const val HOST_TIMEOUT_MS = 10_000L
        const val RECONNECT_BASE_MS = 1_000L
        const val RECONNECT_MAX_MS = 8_000L

        /**
         * 500 ms of 48 kHz mono PCM16: above this backlog a live capture frame
         * is dropped instead of turning a network stall into growing latency.
         */
        const val MAX_AUDIO_BACKLOG_BYTES = 48_000L
    }

    private val lock = Any()
    private var running = false
    private var closed = false
    private var link: DeviceLink? = null
    private var linkUp = false
    private var reconnectScheduled = false
    private var reconnectDelayMs = RECONNECT_BASE_MS
    private var reconnectTask: LinkTask? = null
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
            lastHostActivity = 0L
            current = link
            link = null
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

    /** Opens one attachment stream on the live link; null while down or unsupported. */
    suspend fun openAttachment(header: String): AttachmentChannel? {
        val target = synchronized(lock) { if (linkUp) link else null } ?: return null
        return target.openAttachment(header)
    }

    /** Queues one JSON text message; false when there is no live link. */
    fun sendText(message: String): Boolean {
        val target = synchronized(lock) { if (linkUp) link else null } ?: return false
        return target.sendText(message)
    }

    /** False when the frame was discarded: no link, or a backlog above the cap. */
    fun sendFrame(frame: ByteArray, length: Int): Boolean {
        val target = synchronized(lock) { if (linkUp) link else null } ?: return false
        // Live capture: never let a stalled network turn into growing latency.
        // Control messages use sendText and are never dropped.
        if (target.backlogBytes > MAX_AUDIO_BACKLOG_BYTES) return false
        target.sendBinary(frame, length)
        return true
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
        val attempt = factory.create()
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
        // Not under the lock: a link may call onOpen before start() returns.
        attempt.start(Listener(attempt))
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
            if (!running || (link != null && link !== owner)) {
                // A link that lost the race, or arrived after close: never live.
                runCatching { owner.cancel() }
                false
            } else {
                link = owner
                linkUp = true
                opens++
                reconnectScheduled = false
                reconnectDelayMs = RECONNECT_BASE_MS
                lastHostActivity = clock()
                true
            }
        }
        if (!accepted) return
        if (!callbacks.onLinkOpened()) handleDown(owner, "hello_failed")
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
            if (link === owner) {
                link = null
            } else if (link != null) {
                // A replaced link reporting down: the live link is unaffected.
                return
            }
            linkUp = false
            lastHostActivity = 0L
            running
        }
        if (!notify) return
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
