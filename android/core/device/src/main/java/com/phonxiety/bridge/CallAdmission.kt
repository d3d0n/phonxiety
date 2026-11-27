package com.phonxiety.bridge

/**
 * The door between idle-only link maintenance and call reservation.
 *
 * [lock] is the engine's call-ownership monitor: every read and write of the
 * owned call already happens under it, so an action admitted here can never
 * overlap a reservation, and a dial can never reserve while an action is in
 * flight.  One action runs at a time; [awaitIdle] lets a dial wait the action
 * out instead of failing a command that arrives in the same millisecond.
 */
internal class CallAdmission {
    /** The engine's call-ownership lock: one monitor for the owned call and this door. */
    internal val lock: Any = Any()

    private var actionInFlight = false

    /**
     * Admits one idle-only action, or null while a call is owned (including
     * the reservation window) or another action still runs.  [callOwned] is
     * read under [lock] and must not block.
     */
    fun tryBeginIdleLinkAction(callOwned: () -> Boolean): IdleLinkAction? = synchronized(lock) {
        if (actionInFlight || callOwned()) return null
        actionInFlight = true
        IdleLinkAction {
            synchronized(lock) {
                actionInFlight = false
                (lock as Object).notifyAll()
            }
        }
    }

    /**
     * Waits up to [timeoutMs] for an in-flight action to end; true when the
     * caller may reserve.  The monitor is released while waiting, so the action
     * can finish and a link-down abort can proceed meanwhile.
     */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(lock) {
            while (actionInFlight) {
                val leftMs = (deadline - System.nanoTime()) / 1_000_000L
                if (leftMs <= 0L) return false
                try {
                    (lock as Object).wait(leftMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return true
        }
    }

    /** True while an action holds the door; the caller holds [lock] to reserve. */
    fun actionInFlightLocked(): Boolean = actionInFlight
}
