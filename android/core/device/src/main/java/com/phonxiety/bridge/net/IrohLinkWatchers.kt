package com.phonxiety.bridge.net

/**
 * The link's late-bound subscription: Android's default-network watch.
 *
 * Its registration finishes after readers may already have failed the link, so
 * publication checks the link's terminal state (cancellation *and* the one-time
 * down flag): a handle offered after the end is refused and closed by its
 * caller at once, and one attached before the end is released by [detachAll].
 * Nothing outlives the link through its own native lifetime.
 */
internal class IrohLinkWatchers(private val isClosed: () -> Boolean) {
    private val lock = Any()
    private var stopped = false
    private var networkWatch: (() -> Unit)? = null

    /** Attaches Android's network watch; false means the caller unregisters it now. */
    fun attachNetworkWatch(release: () -> Unit): Boolean = synchronized(lock) {
        if (stopped || isClosed()) {
            false
        } else {
            networkWatch = release
            true
        }
    }

    /** Stops accepting handles and returns every release to run outside the lock. */
    fun detachAll(): List<() -> Unit> = synchronized(lock) {
        stopped = true
        val releases = networkWatch?.let { listOf(it) } ?: emptyList()
        networkWatch = null
        releases
    }
}
