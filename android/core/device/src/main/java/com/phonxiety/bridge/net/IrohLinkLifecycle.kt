package com.phonxiety.bridge.net

/** What came of one destructive link action. */
internal enum class IrohActionOutcome {
    /** The action ran: the endpoint may be recycled and the link reported down. */
    EXECUTED,

    /** The link was already finished (cancelled or down): nothing was touched. */
    LINK_CLOSED,

    /** The link is live but the caller's admission refused: nothing was touched. */
    REFUSED
}

/**
 * Runs a link's destructive decision — recycle the endpoint before reporting
 * down — at most once, serialized with the link's own cancellation, so the
 * recycle is applied only to the generation this link bound.
 *
 * Cancellation sets the link's closed flag, so a callback that arrives after
 * [close] sees [IrohActionOutcome.LINK_CLOSED] and never touches the endpoint
 * a newer link bound.  The monitor covers only the check-and-recycle: reporting
 * down reaches the supervisor, which may call back into the link's cancellation
 * under its own lock, so it must never run while this monitor is held.
 */
internal class IrohLinkLifecycle(
    private val recycleEndpoint: (generation: Long) -> Boolean,
    private val isClosed: () -> Boolean
) {
    private companion object {
        const val NO_GENERATION = -1L
    }

    private val monitor = Any()
    private var generation = NO_GENERATION
    private var closed = false

    /** Records the endpoint generation this link dialled through. */
    fun bind(generation: Long) {
        synchronized(monitor) {
            if (!closed) this.generation = generation
        }
    }

    /** The link is finished; a later action must not touch the endpoint. */
    fun close() {
        synchronized(monitor) { closed = true }
    }

    /**
     * Runs [act] under the monitor while this link is still a live attempt, and
     * returns its result; null when it already ended.  Non-suspending, so an
     * attempt's decision and its effect cannot be split by a cancellation.
     */
    fun <T> whenLive(act: () -> T): T? {
        synchronized(monitor) {
            if (closed || isClosed()) return null
            return act()
        }
    }

    /**
     * Recycles this link's endpoint generation when [recycle] is true and then
     * runs [reportDown].  [admit] decides under the same monitor, only while
     * the link is live: a finished link never asks it, so a cooldown it would
     * start is never spent on a link that has nothing left to act on.
     */
    fun run(recycle: Boolean, admit: () -> Boolean = { true }, reportDown: () -> Unit): IrohActionOutcome {
        synchronized(monitor) {
            if (closed || isClosed()) return IrohActionOutcome.LINK_CLOSED
            if (!admit()) return IrohActionOutcome.REFUSED
            if (recycle && generation != NO_GENERATION) recycleEndpoint(generation)
        }
        reportDown()
        return IrohActionOutcome.EXECUTED
    }
}
