package com.phonxiety.bridge.model

/**
 * The process-wide pairing generation.
 *
 * A pairing change advances [epoch] and replaces the expected identity at
 * once, before the service has rebuilt its link.  The installed gateway is
 * handed out only while it was built for the expected pairing, so no request
 * can carry the new pairing's credentials through the previous daemon's link:
 * a caller either gets the gateway built for this pairing or none at all.
 *
 * Anything learned across an answer — a probe's addresses, an app token, a
 * capability sample — can capture [epoch] before it starts and compare it
 * afterwards, which is how a late answer is refused instead of being applied
 * to the pairing that replaced it.
 */
object PairingGate {
    private data class Installed(val gateway: AppGateway, val identity: String)

    private val lock = Any()
    private var installed: Installed? = null
    private var expected: String? = null
    private var epoch = 0L

    /**
     * Points the gate at [identity].  A different identity is a new pairing and
     * advances the epoch in the same step, so an old gateway stops answering
     * immediately; null means unpaired.
     */
    fun expect(identity: String?) = synchronized(lock) {
        if (identity != expected) {
            expected = identity
            epoch += 1
        }
    }

    /** The current pairing generation. */
    fun epoch(): Long = synchronized(lock) { epoch }

    /** The pairing identity the gate expects, or null while unpaired. */
    fun expectedIdentity(): String? = synchronized(lock) { expected }

    /** Installs [gateway], built for [identity]. */
    fun install(gateway: AppGateway, identity: String) = synchronized(lock) {
        installed = Installed(gateway, identity)
    }

    /** Removes [gateway] only if it is still the installed one; a late teardown never removes its successor. */
    fun uninstall(gateway: AppGateway) = synchronized(lock) {
        if (installed?.gateway === gateway) installed = null
    }

    /** The gateway built for the expected pairing, or null once the pairing moved away from it. */
    fun gateway(): AppGateway? = synchronized(lock) {
        val current = installed
        if (current != null && current.identity == expected) current.gateway else null
    }

    /** Clears every field; tests that share the process need a known starting point. */
    fun resetForTest() = synchronized(lock) {
        installed = null
        expected = null
        epoch = 0
    }
}
