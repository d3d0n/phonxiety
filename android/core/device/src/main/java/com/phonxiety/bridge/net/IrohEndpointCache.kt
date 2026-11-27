package com.phonxiety.bridge.net

import com.phonxiety.bridge.BridgeLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One pending fresh-bind request as seen by one attempt.  [pending] is a plain
 * read of the shared budget; [consume] runs under the attempt's own lifecycle
 * monitor and consumes the request only while that attempt is still live.
 * Both are non-suspending and are called under the endpoint bind mutex.
 */
internal interface FreshBind {
    /** True while a trip's fresh-bind request has not been consumed. */
    fun pending(): Boolean

    /**
     * Runs [act] under the lifecycle monitor and consumes the request when this
     * attempt is still live; null when it is not, and [act] did not run.
     */
    fun <T> consume(act: () -> T): T?
}

/** The attempt's view of the factory's shared budget, refusing to consume once it ended. */
internal class BudgetFreshBind(
    private val budget: IrohFailureBudget,
    private val lifecycle: IrohLinkLifecycle
) : FreshBind {
    override fun pending(): Boolean = budget.pending()

    override fun <T> consume(act: () -> T): T? = lifecycle.whenLive {
        if (budget.consume()) act() else null
    }
}

/**
 * The process-wide cached endpoint behind [IrohSupport].
 *
 * Generic in the bound handle so the fresh-bind section can be driven on the
 * JVM without the native library.  [mutex] serializes binds; [state] guards the
 * cached handle and its generation, so [detach] and [detach] by generation are
 * not serialized with a bind, exactly as before.
 *
 * Only the fresh-bind section is: under [mutex], a still-live attempt consumes
 * its pending request and the cached handle is detached in one non-suspending
 * step; the new bind then runs outside that step but still under [mutex].  A
 * retired attempt can therefore never detach the endpoint a replacement bound.
 *
 * [onDetach] stops one detached handle and must not block.
 */
internal class IrohEndpointCache<T : Any>(private val onDetach: (T, String) -> Unit) {
    private companion object {
        const val TAG = "Bridge:iroh"
    }

    private val mutex = Mutex()

    /** Guards [current] and [sequence]. */
    private val state = Any()

    private var current: T? = null
    private var sequence = 0L

    /** The cached handle, or a new one built by [bind] under a fresh generation. */
    suspend fun acquire(bind: suspend (Long) -> T): T {
        synchronized(state) { current }?.let { return it }
        return mutex.withLock { bindLocked(bind) }
    }

    /**
     * A fresh-bind attempt's view: the cached handle unless its [fresh] request
     * is pending, in which case a still-live attempt detaches that handle,
     * consumes the request and binds a new one.  The detach runs inside the
     * attempt's own monitor section, so a cancellation that won the race leaves
     * both the handle and the request untouched.
     */
    suspend fun acquire(fresh: FreshBind, bind: suspend (Long) -> T): T {
        if (!fresh.pending()) {
            synchronized(state) { current }?.let { return it }
        }
        return mutex.withLock {
            val detached = fresh.consume {
                synchronized(state) { current.also { current = null } }
            }
            if (detached != null) {
                onDetach(detached, "phonxiety-iroh-fresh")
                BridgeLog.w(TAG, "iroh endpoint recycled after 3 failed attempts")
            }
            bindLocked(bind)
        }
    }

    /** Caller holds [mutex]. */
    private suspend fun bindLocked(bind: suspend (Long) -> T): T {
        synchronized(state) { current }?.let { return it }
        val generation = synchronized(state) { ++sequence }
        return bind(generation).also { bound -> synchronized(state) { current = bound } }
    }

    /** Detaches the cached handle, if any; the caller stops it. */
    fun detach(): T? = synchronized(state) { current.also { current = null } }

    /** Detaches only when [generation] is still the cached one; otherwise null. */
    fun detach(generation: Long, generationOf: (T) -> Long): T? = synchronized(state) {
        val live = current ?: return null
        if (generationOf(live) != generation) return null
        current = null
        live
    }
}
