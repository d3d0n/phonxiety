package com.phonxiety.bridge.net

/**
 * The link's late-bound subscriptions: the relay-only monitor and Android's
 * default-network watch.
 *
 * Their registration finishes after readers may already have failed the link,
 * so both sides share one lock and publication checks the link's terminal state
 * (cancellation *and* the one-time down flag): a handle offered after the end
 * is refused and closed by its caller at once, and one attached before the end
 * is released by [detachAll].  Nothing outlives the link through its own
 * native lifetime.
 */
internal class IrohLinkWatchers(private val isClosed: () -> Boolean) {
    private val lock = Any()
    private var stopped = false
    private var monitor: IrohPathMonitor? = null
    private var networkWatch: (() -> Unit)? = null

    /** The monitor while it is attached; null once the link ended. */
    fun monitor(): IrohPathMonitor? = synchronized(lock) { if (stopped) null else monitor }

    /** Attaches the relay-only monitor; false means the caller cancels it now. */
    fun attachMonitor(pathMonitor: IrohPathMonitor): Boolean = attach { monitor = pathMonitor }

    /** Attaches Android's network watch; false means the caller unregisters it now. */
    fun attachNetworkWatch(release: () -> Unit): Boolean = attach { networkWatch = release }

    /** Stops accepting handles and returns every release to run outside the lock. */
    fun detachAll(): List<() -> Unit> = synchronized(lock) {
        stopped = true
        val releases = ArrayList<() -> Unit>(2)
        networkWatch?.let { releases += it }
        monitor?.let { attached -> releases += { attached.cancel() } }
        networkWatch = null
        monitor = null
        releases
    }

    private fun attach(store: () -> Unit): Boolean = synchronized(lock) {
        if (stopped || isClosed()) {
            false
        } else {
            store()
            true
        }
    }
}
