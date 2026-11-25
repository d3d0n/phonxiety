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
 * event ever arrives.  Once the policy escalates or [cancel] runs the monitor
 * is finished: it stops its subscription, cancels the deadline and ignores
 * every callback that arrives afterwards.
 */
internal class IrohPathMonitor(
    private val policy: IrohEscalationPolicy,
    private val scheduler: LinkScheduler,
    private val ownsCall: () -> Boolean,
    private val onEscalate: (IrohEscalation) -> Unit
) {
    private val lock = Any()
    private var finished = false
    private var armed: LinkTask? = null
    private var watch: IrohPathWatch? = null

    /** Subscribes to the connection's path changes; a subscription arriving after the end is stopped at once. */
    fun start(source: IrohPathSource) {
        val subscription = source.subscribe { kind -> observe(kind) }
        val keep = synchronized(lock) {
            if (finished) {
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
        var stopped: IrohPathWatch? = null
        synchronized(lock) {
            if (finished) return
            policy.observePath(kind, ownsCall())
            escalation = policy.evaluate(ownsCall())
            if (escalation != null) {
                stopped = finishLocked()
            } else {
                scheduleLocked()
            }
        }
        stopped?.stop()
        escalation?.let(onEscalate)
    }

    /** A network change restarts the cooldown and drops stale relay evidence. */
    fun networkChanged() {
        var escalation: IrohEscalation? = null
        var stopped: IrohPathWatch? = null
        synchronized(lock) {
            if (finished) return
            policy.observeNetworkChange()
            escalation = policy.evaluate(ownsCall())
            if (escalation != null) stopped = finishLocked() else scheduleLocked()
        }
        stopped?.stop()
        escalation?.let(onEscalate)
    }

    /** Stops for good: the deadline is cancelled and the subscription unregistered. */
    fun cancel() {
        val (task, subscription) = synchronized(lock) {
            val armedNow = if (finished) null else armed
            val watched = if (finished) null else watch
            finished = true
            armed = null
            watch = null
            armedNow to watched
        }
        task?.cancel()
        subscription?.stop()
    }

    private fun onDeadline() {
        var escalation: IrohEscalation? = null
        var stopped: IrohPathWatch? = null
        synchronized(lock) {
            armed = null
            if (finished) return
            escalation = policy.evaluate(ownsCall())
            if (escalation != null) {
                stopped = finishLocked()
            } else {
                scheduleLocked()
            }
        }
        stopped?.stop()
        escalation?.let(onEscalate)
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
        val delayMs = policy.nextCheckMs(ownsCall()) ?: return
        armed = try {
            scheduler.after(delayMs.coerceAtLeast(1L)) { onDeadline() }
        } catch (_: Exception) {
            // The engine's scheduler is shut down; the sample loop still ticks.
            null
        }
    }
}
