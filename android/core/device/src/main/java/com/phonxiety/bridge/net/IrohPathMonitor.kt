package com.phonxiety.bridge.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One live subscription to a connection's path changes. */
internal fun interface IrohPathWatch {
    /** Unregisters the subscription; safe to call more than once. */
    fun stop()
}

/** Where [IrohPathMonitor] gets path changes; [IrohLink] adapts `watchPaths` to it. */
internal fun interface IrohPathSource {
    fun subscribe(onPath: (IrohPathKind) -> Unit): IrohPathWatch
}

/** Runs monitor deadlines on a coroutine scope the link cancels with itself. */
internal class CoroutineLinkScheduler(private val scope: CoroutineScope) : LinkScheduler {
    override fun after(delayMs: Long, task: () -> Unit): LinkTask {
        val job = scope.launch {
            delay(delayMs)
            task()
        }
        return LinkTask { job.cancel() }
    }
}

/**
 * Feeds path observations into [IrohEscalationPolicy] and turns its decision
 * into one [onEscalate] call.
 *
 * Push updates arrive through [start]'s [IrohPathSource]; the link's 1 s sample
 * loop also calls [observe].  Every observation (re)arms one cancellable
 * deadline, so a link that stays on the relay escalates even if no further
 * event ever arrives.
 *
 * A monitor owns one connection: [start] opens a generation in the policy and
 * [cancel] retires it, each clearing that connection's relay residence without
 * touching the endpoint's escalation history.  Once the link delivers an
 * escalation the monitor is finished; if the link refuses it (a call was
 * reserved in the race, or the link is gone) the watch is revived so the
 * sample loop and the policy's cooldown can escalate later — the link never
 * stays on the relay because of one lost race.
 */
internal class IrohPathMonitor(
    private val policy: IrohEscalationPolicy,
    private val scheduler: LinkScheduler,
    private val ownsCall: () -> Boolean,
    private val onEscalate: (IrohEscalation) -> Boolean
) {
    private val lock = Any()
    private var finished = false
    private var cancelled = false
    private var armed: LinkTask? = null
    private var watch: IrohPathWatch? = null
    private var generation = 0L

    /** Subscribes to the connection's path changes; a subscription arriving after the end is stopped at once. */
    fun start(source: IrohPathSource) {
        synchronized(lock) {
            if (cancelled || generation != 0L) return
            generation = policy.beginConnection()
        }
        val subscription = source.subscribe { kind -> observe(kind) }
        val keep = synchronized(lock) {
            if (finished || cancelled) {
                false
            } else {
                watch = subscription
                true
            }
        }
        if (!keep) subscription.stop()
    }

    /** One observation of the selected path; safe from any thread. */
    fun observe(kind: IrohPathKind) {
        var escalation: IrohEscalation? = null
        synchronized(lock) {
            if (finished || cancelled) return
            policy.observePath(kind, ownsCall())
            escalation = policy.evaluate(ownsCall())
            if (escalation == null) scheduleLocked()
        }
        deliver(escalation)
    }

    /** Drops the connection's stale relay evidence after a network change. */
    fun noteNetworkChange() {
        synchronized(lock) {
            if (finished || cancelled) return
            policy.noteNetworkChange()
        }
    }

    /** Whether the policy admits a network-triggered endpoint recycle right now. */
    fun admitNetworkRecycle(): IrohNetworkDecision = synchronized(lock) {
        if (finished || cancelled) return IrohNetworkDecision.HELD
        policy.admitNetworkRecycle(ownsCall())
    }

    /** Stops for good: the deadline is cancelled, the subscription unregistered and the generation retired. */
    fun cancel() {
        val (task, subscription, retired) = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            val armedNow = if (finished) null else armed
            val watched = if (finished) null else watch
            finished = true
            armed = null
            watch = null
            val current = generation.takeIf { it != 0L }
            generation = 0L
            Triple(armedNow, watched, current)
        }
        task?.cancel()
        subscription?.stop()
        retired?.let { policy.endConnection(it) }
    }

    private fun onDeadline() {
        var escalation: IrohEscalation? = null
        synchronized(lock) {
            armed = null
            if (finished || cancelled) return
            escalation = policy.evaluate(ownsCall())
            if (escalation == null) scheduleLocked()
        }
        deliver(escalation)
    }

    /**
     * Hands one escalation to the link.  A refusal means the link could not
     * act (a call took ownership in the race); the monitor stays alive and the
     * policy's cooldown plus the 1 s sample re-arm it.
     */
    private fun deliver(escalation: IrohEscalation?) {
        if (escalation == null) return
        if (!finishAndDeliver(escalation)) {
            synchronized(lock) {
                if (cancelled) return
                finished = false
                armed = null
                scheduleLocked()
            }
        }
    }

    /** Ends the watch and runs the callback; false when the link refused it. */
    private fun finishAndDeliver(escalation: IrohEscalation): Boolean {
        val stopped = synchronized(lock) {
            if (finished || cancelled) return true
            finishLocked()
        }
        stopped?.stop()
        return onEscalate(escalation)
    }

    /** Caller holds [lock]. */
    private fun finishLocked(): IrohPathWatch? {
        finished = true
        armed?.cancel()
        armed = null
        val watched = watch
        watch = null
        return watched
    }

    /** Caller holds [lock]. */
    private fun scheduleLocked() {
        armed?.cancel()
        armed = null
        if (finished || cancelled) return
        val delayMs = policy.nextCheckMs(ownsCall()) ?: return
        armed = try {
            scheduler.after(delayMs.coerceAtLeast(1L)) { onDeadline() }
        } catch (_: Exception) {
            // The engine's scheduler is shut down; the sample loop still ticks.
            null
        }
    }
}
