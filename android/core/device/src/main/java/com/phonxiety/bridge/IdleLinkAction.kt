package com.phonxiety.bridge

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One admitted idle-only link action: the iroh link holds it while it recycles
 * the endpoint on a network change, and the engine refuses to reserve a call
 * until it is released.  The action is short
 * (null the endpoint, report down); anything long must not hold it.
 */
internal class IdleLinkAction internal constructor(private val onRelease: () -> Unit) : AutoCloseable {
    private val released = AtomicBoolean(false)

    /** Ends the action; safe to call more than once and from any thread. */
    fun release() {
        if (released.compareAndSet(false, true)) onRelease()
    }

    override fun close() = release()
}
