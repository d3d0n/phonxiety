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
 *   previous escalation or network change still holds the cooldown;
 * - during a call the escalation is recorded as pending and released only by a
 *   fresh relay observation after the call ends and after the cooldown;
 * - a second escalation within [ESCALATION_WINDOW_MS] recycles the endpoint.
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
     * A network change was reported (Android or iroh): restart the cooldown and
     * forget stale relay evidence, so the re-dialled link is judged afresh.
     */
    @Synchronized
    fun observeNetworkChange() {
        val now = clock()
        val until = now + cooldownMs
        if (until > cooldownUntil) cooldownUntil = until
        relaySince = null
        pending = false
        pendingFreshRelay = false
    }

    /**
     * The call just ended: a pending escalation now needs a fresh relay
     * observation before it can be released, never a stale one.
     */
    private fun noteCallState(callOwned: Boolean) {
        if (owned && !callOwned && pending) pendingFreshRelay = false
        owned = callOwned
    }

    private fun escalate(now: Long): IrohEscalation {
        while (escalations.isNotEmpty() && now - escalations.first() >= escalationWindowMs) {
            escalations.removeFirst()
        }
        val recycle = escalations.isNotEmpty()
        escalations.addLast(now)
        pending = false
        pendingFreshRelay = false
        relaySince = null
        cooldownUntil = now + cooldownMs
        return IrohEscalation(recycleEndpoint = recycle)
    }

    private fun remaining(now: Long): Long = if (now >= cooldownUntil) 0L else cooldownUntil - now
}
