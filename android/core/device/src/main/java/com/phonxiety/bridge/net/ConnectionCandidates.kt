package com.phonxiety.bridge.net

import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.model.LinkKind

/**
 * Which transports link selection may use, as the settings screen stores it.
 * [AUTO] probes and scores every candidate; the others pin one.
 */
enum class ConnectionMode(val wire: String, val label: String) {
    AUTO("auto", "Automatic"),
    IROH_ONLY("iroh", "iroh only"),
    LAN_ONLY("lan", "LAN only"),
    TAILSCALE_ONLY("tailscale", "Tailscale only");

    internal fun allows(candidate: Candidate): Boolean = when (this) {
        AUTO -> true
        IROH_ONLY -> candidate.kind == LinkKind.IROH
        LAN_ONLY -> candidate.kind == LinkKind.WEBSOCKET
        TAILSCALE_ONLY -> candidate.kind == LinkKind.TAILSCALE
    }

    companion object {
        /** An unknown or missing stored value means automatic. */
        fun of(value: String?): ConnectionMode = entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: AUTO
    }
}

/**
 * One way to reach the paired daemon: the paired transport itself, an address
 * the daemon reported over `/app/v1/info`, or one found by mDNS.
 */
internal data class Candidate(
    val id: String,
    val kind: LinkKind,
    val url: String?,
    val certSha256: String?,
    val label: String,
    /** The paired transport itself: pairing already authenticated it, so it never needs probes. */
    val primary: Boolean = false
) {
    /** The address without any userinfo, for logs and the UI. */
    val host: String? get() = url?.let { Tailnet.hostOf(it) }

    companion object {
        const val IROH_ID = "iroh"

        fun iroh(primary: Boolean = true): Candidate = Candidate(IROH_ID, LinkKind.IROH, url = null, certSha256 = null, label = "iroh", primary = primary)

        /** An address candidate; the kind follows the host, so a tailnet address is Tailscale. */
        fun address(
            url: String,
            certSha256: String?,
            source: String = CandidateStore.Source.LEARNED,
            primary: Boolean = false
        ): Candidate {
            val host = Tailnet.hostOf(url) ?: url
            val kind = if (Tailnet.isTailnetHost(host)) LinkKind.TAILSCALE else LinkKind.WEBSOCKET
            return Candidate(id = url, kind = kind, url = url, certSha256 = certSha256, label = "$host ($source)", primary = primary)
        }
    }
}

/** What one probe round learned about a candidate. */
internal data class ProbeReport(
    val candidateId: String,
    val passes: Int,
    val p50RttMs: Long?
) {
    val eligible: Boolean get() = passes >= CandidateScore.MIN_PASSES
}

/**
 * The scoring rules of the link contract: base per transport, minus a quarter
 * of the measured round trip, capped so a bad path cannot score below the base
 * of a worse transport.
 */
internal object CandidateScore {
    const val LAN = 100.0
    const val IROH_DIRECT = 90.0
    const val TAILSCALE = 80.0
    const val IROH_RELAY = 50.0

    /** A candidate must pass this many consecutive probes before it may be chosen. */
    const val MIN_PASSES = 3

    /** One probe (TLS, auth, one ping) must answer within this budget. */
    const val PROBE_BUDGET_MS = 300L

    /** A challenger must beat the incumbent by this fraction... */
    const val HYSTERESIS = 0.15

    /** ...for this many consecutive rounds before the link moves. */
    const val CONFIRM_ROUNDS = 2

    /** The largest subtraction the round trip can cause. */
    private const val MAX_RTT_PENALTY = 50.0

    fun base(candidate: Candidate, relayOnly: Boolean = false): Double = when (candidate.kind) {
        LinkKind.WEBSOCKET -> LAN
        LinkKind.TAILSCALE -> TAILSCALE
        LinkKind.IROH -> if (relayOnly) IROH_RELAY else IROH_DIRECT
    }

    fun score(base: Double, p50RttMs: Long?): Double {
        val penalty = ((p50RttMs ?: 0L).coerceAtLeast(0L) / 4.0).coerceAtMost(MAX_RTT_PENALTY)
        return base - penalty
    }
}

/**
 * Builds the candidate list for a pairing: the paired transport first, then
 * every address the daemon reported and every mDNS advertisement whose
 * certificate matches the pairing pin.  An advertisement without a matching
 * pin is dropped: mDNS is unauthenticated.
 */
internal object CandidatePlanner {
    fun plan(
        config: BridgeConfig,
        stored: List<StoredCandidate> = emptyList(),
        discovered: List<DiscoveredDaemon> = emptyList(),
        storeIdentity: String? = null
    ): List<Candidate> {
        // Only a document bound to this exact pairing may contribute.  An
        // identity-less document (from before identities, or a binding that
        // never succeeded) and another daemon's document are both unusable:
        // their addresses and their single pin belong to a pairing this token
        // was not issued for.
        val bound = storeIdentity != null && storeIdentity == CandidateMerge.identity(config)
        val storedPins = if (bound) {
            stored.mapNotNull { it.certSha256?.takeIf(String::isNotBlank)?.lowercase() }.distinct()
        } else {
            emptyList()
        }
        val pin = when (config) {
            is BridgeConfig.Address -> config.certSha256?.lowercase()

            // An iroh pairing carries its pin when the QR (or config file) had one.
            // Without it, only a bound document's unambiguous pin can be trusted;
            // mixed pins mean the document still holds another daemon's addresses.
            is BridgeConfig.Iroh -> config.certSha256?.lowercase() ?: storedPins.singleOrNull()
        }
        val ambiguous = bound && config is BridgeConfig.Iroh &&
            config.certSha256 == null && storedPins.size > 1
        val primary = when (config) {
            is BridgeConfig.Iroh -> Candidate.iroh()

            is BridgeConfig.Address ->
                Candidate.address(config.url, config.certSha256, source = CandidateStore.Source.PAIRED, primary = true)
        }
        val addresses = buildList {
            if (bound) {
                for (candidate in stored) {
                    val cert = candidate.certSha256 ?: continue
                    if (cert.isBlank()) continue
                    // Defence in depth: an address pinned to another daemon is never
                    // dialled with this pairing's token, even if it is in the store.
                    if (ambiguous) continue
                    if (pin == null || !cert.equals(pin, ignoreCase = true)) continue
                    add(Candidate.address(candidate.url, cert, candidate.source))
                }
            }
            for (daemon in discovered) {
                val cert = daemon.certSha256 ?: continue
                if (pin == null || !cert.equals(pin, ignoreCase = true)) continue
                add(Candidate.address(daemon.url, cert, CandidateStore.Source.MDNS))
            }
        }
        return (listOf(primary) + addresses).distinctBy { it.id }
    }
}

/**
 * Chooses the candidate to connect with, keeping the incumbent unless a
 * challenger wins by [CandidateScore.HYSTERESIS] for
 * [CandidateScore.CONFIRM_ROUNDS] consecutive rounds.
 *
 * Never moves the link during a call: the contract is that a dead path ends
 * the call instead of migrating audio mid-sentence.  State lives across
 * attempts, so a reconnect prefers the path that was working.
 */
internal class LinkSelector {
    data class Decision(
        val candidate: Candidate?,
        val switched: Boolean,
        val reason: String,
        val score: Double?
    )

    private var incumbent: Candidate? = null
    private var challengerId: String? = null
    private var challengerRounds = 0

    /** The candidate the current link uses, across calls to [observe]. */
    fun incumbent(): Candidate? = incumbent

    fun reset() {
        incumbent = null
        challengerId = null
        challengerRounds = 0
    }

    fun observe(
        plan: List<Candidate>,
        reports: List<ProbeReport>,
        mode: ConnectionMode,
        duringCall: Boolean,
        relayOnly: Boolean = false
    ): Decision {
        val byId = plan.filter { mode.allows(it) }.associateBy { it.id }
        val reportById = reports.associateBy { it.candidateId }
        val scored = byId.mapNotNull { (id, candidate) ->
            val report = reportById[id] ?: return@mapNotNull null
            if (!report.eligible) return@mapNotNull null
            candidate to CandidateScore.score(CandidateScore.base(candidate, relayOnly && candidate.kind == LinkKind.IROH), report.p50RttMs)
        }.sortedByDescending { it.second }

        val current = incumbent?.takeIf { byId.containsKey(it.id) }
        if (duringCall) {
            val held = current ?: scored.firstOrNull()?.first
            return Decision(held, switched = false, reason = "in_call", score = scored.firstOrNull { it.first.id == held?.id }?.second)
        }
        if (scored.isEmpty()) {
            // Nothing measured yet: keep the incumbent so a reconnect retries the known path.
            return Decision(current, switched = false, reason = "no_probe", score = null)
        }
        val best = scored.first()
        if (current == null) {
            incumbent = best.first
            challengerId = null
            challengerRounds = 0
            return Decision(best.first, switched = true, reason = "first_pick", score = best.second)
        }
        val incumbentScore = scored.firstOrNull { it.first.id == current.id }?.second
        if (incumbentScore == null) {
            // The incumbent failed its probes: move to the best measured candidate now.
            incumbent = best.first
            challengerId = null
            challengerRounds = 0
            return Decision(best.first, switched = true, reason = "incumbent_lost", score = best.second)
        }
        if (best.first.id == current.id) {
            challengerId = null
            challengerRounds = 0
            return Decision(current, switched = false, reason = "incumbent_best", score = incumbentScore)
        }
        if (best.second >= incumbentScore * (1.0 + CandidateScore.HYSTERESIS)) {
            if (challengerId == best.first.id) {
                challengerRounds++
            } else {
                challengerId = best.first.id
                challengerRounds = 1
            }
            if (challengerRounds >= CandidateScore.CONFIRM_ROUNDS) {
                incumbent = best.first
                challengerId = null
                challengerRounds = 0
                return Decision(best.first, switched = true, reason = "challenger_win", score = best.second)
            }
            return Decision(current, switched = false, reason = "challenger_pending", score = incumbentScore)
        }
        challengerId = null
        challengerRounds = 0
        return Decision(current, switched = false, reason = "hysteresis", score = incumbentScore)
    }
}
