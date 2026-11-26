package com.phonxiety.bridge.net

/** What the selected iroh path is, as far as the link can tell. */
internal enum class IrohPathKind {
    /** A direct IP path carries application data. */
    DIRECT,

    /** The relay carries application data. */
    RELAY,

    /** No path is selected, or the transport did not say: never counted as relay. */
    UNKNOWN
}

/** What the link must do once staying on the relay no longer works. */
internal data class IrohEscalation(
    /**
     * True when the endpoint itself must be recycled before the link reports
     * down: this is a second escalation inside
     * [IrohEscalationPolicy.ESCALATION_WINDOW_MS].
     */
    val recycleEndpoint: Boolean
)

/** What the policy admitted for a network change; only [RECYCLE] may act. */
internal enum class IrohNetworkDecision {
    /** No call and no cooldown: recycle the endpoint and report the link down. */
    RECYCLE,

    /** A bridge call owns the link: iroh keeps migrating the connection. */
    DURING_CALL,

    /** A recent escalation or recycle still holds the cooldown: keep this link. */
    HELD
}

/**
 * The relay-only escalation rule as a pure state machine over an injected
 * monotonic clock.
 *
 * One instance lives in [IrohLinkFactory] for the paired endpoint, so the
 * escalation history and the cooldown survive link replacement and endpoint
 * recycling.  The rule:
 *
 * - a selected direct path clears the relay residence time and any pending work;
 * - an unknown path is not a relay and does not count as one;
 * - relay-only for [RELAY_LIMIT_MS] outside a call escalates at once, unless a
 *   previous escalation or network recycle still holds the cooldown;
 * - during a call the escalation is recorded as pending and released only by a
 *   fresh relay observation after the call ends and after the cooldown;
 * - a second escalation within [ESCALATION_WINDOW_MS] recycles the endpoint;
 * - a network change drops the connection's relay evidence and recycles the
 *   endpoint only when no call is owned and the cooldown allows it, so the
 *   same window holds whether the action came from the relay rule or the
 *   network;
 * - retiring or starting a monitor resets that connection's residence and
 *   pending work (the disconnect never counts as relay residence), while the
 *   escalation timestamps and the cooldown stay with the paired endpoint.
 */
internal class IrohEscalationPolicy(
    private val clock: () -> Long,
    private val relayLimitMs: Long = RELAY_LIMIT_MS,
    private val escalationWindowMs: Long = ESCALATION_WINDOW_MS,
    private val cooldownMs: Long = COOLDOWN_MS
) {
    internal companion object {
        /** Relay-only residence that triggers one escalation. */
        const val RELAY_LIMIT_MS = 20_000L

        /** How far back a previous escalation counts as "recent" for the recycle rule. */
        const val ESCALATION_WINDOW_MS = 300_000L

        /** No escalation of any kind inside this window after one already happened. */
        const val COOLDOWN_MS = 60_000L
    }

    /** When the current uninterrupted relay-only residence began; null while not relay. */
    private var relaySince: Long? = null

    /** An escalation came due during a call and waits for the call to end. */
    private var pending = false

    /** A relay observation arrived after the call ended, as the release requires. */
    private var pendingFreshRelay = false

    /** What the last observation or evaluation said about call ownership. */
    private var owned = false

    private var cooldownUntil = Long.MIN_VALUE

    /** Escalations that happened, oldest first, for the rolling recycle window. */
    private val escalations = ArrayDeque<Long>()

    /** Identifies the monitor currently watching this paired endpoint's paths. */
    private var connectionGeneration = 0L

    /**
     * A monitor starts watching a connection: its relay residence begins fresh,
     * and nothing recorded before the (re)connect can escalate it early.
     */
    @Synchronized
    fun beginConnection(): Long {
        connectionGeneration += 1
        clearConnectionEvidence()
        return connectionGeneration
    }

    /**
     * A monitor stops watching.  Its residence and pending work are dropped so
     * the disconnect time is never counted as relay residence; a retired
     * generation cannot wipe the connection that replaced it.  Escalation
     * timestamps and the cooldown stay: they belong to the endpoint.
     */
    @Synchronized
    fun endConnection(generation: Long) {
        if (generation != connectionGeneration) return
        clearConnectionEvidence()
    }

    /** Feeds one observation of the selected path and the call state into the rule. */
    @Synchronized
    fun observePath(kind: IrohPathKind, callOwned: Boolean) {
        val now = clock()
        noteCallState(callOwned)
        when (kind) {
            IrohPathKind.DIRECT -> {
                relaySince = null
                pending = false
                pendingFreshRelay = false
            }

            IrohPathKind.UNKNOWN -> {
                // Not relay evidence: stop counting relay residence so a path
                // nobody selected can never be closed as relay-only.
                relaySince = null
                pendingFreshRelay = false
            }

            IrohPathKind.RELAY -> {
                if (relaySince == null) relaySince = now
                if (pending && !callOwned) pendingFreshRelay = true
            }
        }
    }

    /**
     * Asks the rule for a decision at the current clock value.  Null means the
     * link keeps running; [nextCheckMs] says when asking again can help.
     */
    @Synchronized
    fun evaluate(callOwned: Boolean): IrohEscalation? {
        val now = clock()
        noteCallState(callOwned)
        if (pending) {
            if (callOwned || !pendingFreshRelay) return null
            if (now < cooldownUntil) return null
            return escalate(now)
        }
        val since = relaySince ?: return null
        if (now - since < relayLimitMs) return null
        if (callOwned) {
            pending = true
            pendingFreshRelay = false
            return null
        }
        if (now < cooldownUntil) return null
        return escalate(now)
    }

    /**
     * Milliseconds until asking again can produce a decision, or null when no
     * armed deadline can help: the next observation or sample must arrive first.
     */
    @Synchronized
    fun nextCheckMs(callOwned: Boolean): Long? {
        val now = clock()
        if (pending) {
            if (callOwned || !pendingFreshRelay) return null
            return remaining(now)
        }
        val since = relaySince ?: return null
        val due = since + relayLimitMs
        if (now < due) return due - now
        if (callOwned) return null
        return remaining(now)
    }

    /**
     * A network change was reported (Android or iroh): the connection's relay
     * evidence is stale and never survives the move.  This is not an action by
     * itself: [admitNetworkRecycle] decides whether the endpoint may be
     * recycled, under the same cooldown and call rule as the relay escalation.
     */
    @Synchronized
    fun noteNetworkChange() {
        clearConnectionEvidence()
    }

    /**
     * Admits or refuses a network-triggered recycle.  Only [RECYCLE] may act;
     * it consumes the same cooldown and counts as an escalation for the rolling
     * recycle window, so a network move right after a relay escalation can
     * never recycle (or report down) again inside the window.
     */
    @Synchronized
    fun admitNetworkRecycle(callOwned: Boolean): IrohNetworkDecision {
        val now = clock()
        noteCallState(callOwned)
        if (callOwned) return IrohNetworkDecision.DURING_CALL
        if (now < cooldownUntil) return IrohNetworkDecision.HELD
        recordAction(now)
        return IrohNetworkDecision.RECYCLE
    }

    /**
     * The call just ended: a pending escalation now needs a fresh relay
     * observation before it can be released, never a stale one.
     */
    private fun noteCallState(callOwned: Boolean) {
        if (owned && !callOwned && pending) pendingFreshRelay = false
        owned = callOwned
    }

    private fun clearConnectionEvidence() {
        relaySince = null
        pending = false
        pendingFreshRelay = false
    }

    private fun escalate(now: Long): IrohEscalation = IrohEscalation(recycleEndpoint = recordAction(now))

    /**
     * Records one destructive action: it holds the cooldown, counts towards the
     * rolling recycle window and drops the evidence it just acted on.  Returns
     * whether another action already happened inside that window.
     */
    private fun recordAction(now: Long): Boolean {
        while (escalations.isNotEmpty() && now - escalations.first() >= escalationWindowMs) {
            escalations.removeFirst()
        }
        val repeated = escalations.isNotEmpty()
        escalations.addLast(now)
        clearConnectionEvidence()
        cooldownUntil = now + cooldownMs
        return repeated
    }

    private fun remaining(now: Long): Long = if (now >= cooldownUntil) 0L else cooldownUntil - now
}
