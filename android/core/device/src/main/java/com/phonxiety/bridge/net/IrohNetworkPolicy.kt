package com.phonxiety.bridge.net

/**
 * What a network change was admitted to do; only [RECYCLE] may act.
 *
 * Being on the relay is not a network change and never appears here: a link
 * that stays on the relay keeps running.
 */
internal enum class IrohNetworkDecision {
    /** No call and no cooldown: recycle the endpoint and report the link down. */
    RECYCLE,

    /** A bridge call owns the link: iroh keeps migrating the connection. */
    DURING_CALL,

    /** A recent recycle still holds the cooldown: keep this link. */
    HELD
}

/**
 * The admission for the one destructive reaction a network change can ask for:
 * recycle the paired endpoint so the next dial binds the new network.
 *
 * One instance lives in [IrohLinkFactory] for the paired endpoint, so the
 * cooldown survives link replacement and endpoint recycling.  A call owns the
 * link throughout; iroh keeps migrating its connection, and the phone reports
 * down only through its own transport failure.  A move inside the cooldown
 * keeps the link rather than churning a third endpoint for a flapping network.
 */
internal class IrohNetworkPolicy(
    private val clock: () -> Long,
    private val cooldownMs: Long = COOLDOWN_MS
) {
    internal companion object {
        /** No recycle of any kind inside this window after one already happened. */
        const val COOLDOWN_MS = 60_000L
    }

    private var cooldownUntil = Long.MIN_VALUE

    /** Admits or refuses a network-triggered recycle right now. */
    @Synchronized
    fun admitRecycle(callOwned: Boolean): IrohNetworkDecision {
        if (callOwned) return IrohNetworkDecision.DURING_CALL
        val now = clock()
        if (now < cooldownUntil) return IrohNetworkDecision.HELD
        cooldownUntil = now + cooldownMs
        return IrohNetworkDecision.RECYCLE
    }
}
