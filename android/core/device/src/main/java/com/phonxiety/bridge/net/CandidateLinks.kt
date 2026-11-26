package com.phonxiety.bridge.net

import android.content.Context
import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.IdleLinkAction
import com.phonxiety.bridge.model.LinkKind
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import okio.ByteString

/** One probe round against one candidate; [ProbeReport.passes] counts consecutive successes. */
internal fun interface CandidateProbe {
    fun probe(candidate: Candidate): ProbeReport
}

/** The candidate the current link was built for; the engine shows it in [com.phonxiety.bridge.model.BridgeState]. */
internal data class SelectedCandidate(val kind: LinkKind, val host: String?)

/**
 * Routes attempt outcomes to the iroh factory: only a known-iroh attempt
 * feeds the endpoint's own recovery accounting.  A LAN or Tailscale failure
 * says nothing about the phone's endpoint, and an attempt that ended before a
 * candidate was selected has no transport at all.
 */
internal class IrohAttemptOutcomes(private val irohFactory: () -> DeviceLinkFactory?) {
    fun report(outcome: AttemptOutcome, kind: LinkKind?) {
        if (kind != LinkKind.IROH) return
        irohFactory()?.attemptEnded(outcome, kind)
    }
}

/** What this phone measured on one daemon LAN address. */
data class LanPathProbe(
    val address: String,
    val reachable: Boolean,
    val rttMs: Long?
)

/**
 * Probes the daemon's LAN addresses from this phone, with the measurement link
 * selection already trusts: [WebSocketCandidateProbe] opens three consecutive
 * TLS handshakes pinned to the daemon certificate and carrying the bearer
 * token, so a socket that opens proves both the pin and the credential.
 *
 * The daemon cannot see this half of the path — it only knows that its own
 * listener answers on the host — so a phone on the same network asks the
 * question here instead of inferring it from whichever link happens to be up.
 * Stops at the first address that answers; at most
 * [LAN_PATH_PROBE_ADDRESSES] addresses are tried.
 */
object LanPathProber {
    fun probe(urls: List<String>, certSha256: String?, token: String): List<LanPathProbe> {
        val prober = WebSocketCandidateProbe(token)
        val results = ArrayList<LanPathProbe>(minOf(urls.size, LAN_PATH_PROBE_ADDRESSES))
        for (url in urls.take(LAN_PATH_PROBE_ADDRESSES)) {
            val report = prober.probe(Candidate.address(url, certSha256))
            results += LanPathProbe(url, report.eligible, report.p50RttMs)
            if (report.eligible) break
        }
        return results
    }
}

/** A phone asking for a LAN check does not probe every address the daemon listed. */
private const val LAN_PATH_PROBE_ADDRESSES = 4

/**
 * Creates links for the candidate selection: before every address attempt it
 * probes the candidates allowed by the current [ConnectionMode], asks
 * [LinkSelector] which one to use and builds that transport.  iroh candidates
 * are not probed — the attempt itself is the probe — and the paired transport
 * needs none at all.
 *
 * Discovery (mDNS) starts with the first attempt and stops with the factory.
 */
internal class CandidateLinkFactory(
    private val context: Context,
    private val config: BridgeConfig,
    private val selector: LinkSelector,
    private val prober: CandidateProbe?,
    private val store: CandidateStore,
    private val mode: () -> ConnectionMode,
    private val ownsCall: () -> Boolean,
    private val duringCall: () -> Boolean,
    private val beginIdleLinkAction: () -> IdleLinkAction?
) : DeviceLinkFactory {
    private val irohFactory: IrohLinkFactory? = (config as? BridgeConfig.Iroh)?.let { iroh ->
        IrohLinkFactory(context, iroh, { ownsCall() }, beginIdleLinkAction)
    }
    private val addressFactories = ConcurrentHashMap<String, WebSocketLinkFactory>()
    private val attempts = ConcurrentHashMap<String, Attempts>()
    private val discovered = CopyOnWriteArrayList<DiscoveredDaemon>()
    private val discoveryStarted = AtomicBoolean(false)
    private val mdns = MdnsDiscovery(context) { daemon ->
        if (discovered.none { it.url == daemon.url }) discovered.add(daemon)
    }

    @Volatile private var relayOnly = false

    @Volatile private var selected: SelectedCandidate? = null

    /** The candidate the live link was built for; a live link is its own proof of reachability. */
    @Volatile private var current: Candidate? = null

    // Per-attempt iroh attribution: the attempt's own kind, never `current`.
    private val outcomes = IrohAttemptOutcomes { irohFactory }

    override fun selected(): SelectedCandidate? = selected

    override fun attemptEnded(outcome: AttemptOutcome, kind: LinkKind?) = outcomes.report(outcome, kind)

    override fun create(): DeviceLink {
        val plan = plan()
        val reports = if (duringCall()) emptyList() else reports(plan)
        val decision = selector.observe(plan, reports, mode(), duringCall(), relayOnly)
        val candidate = decision.candidate ?: plan.firstOrNull()?.also {
            if (plan.size > 1) BridgeLog.w(TAG, "no candidate for ${mode().wire}; keeping ${it.label}")
        }
        if (candidate == null) {
            selected = null
            // No candidate at all: an iroh pairing can still be built from its ticket.
            return irohFactory?.create() ?: ProbeFailureLink("no_candidate")
        }
        BridgeLog.d(TAG, "link candidate ${candidate.label} (${decision.reason})")
        current = candidate
        selected = SelectedCandidate(candidate.kind, candidate.host)
        val link = when (candidate.kind) {
            LinkKind.IROH -> irohFactory?.create() ?: ProbeFailureLink("iroh_unavailable")
            else -> addressFactory(candidate).create()
        }
        return ReportingLink(candidate, link)
    }

    override fun considerSwitch(): Boolean {
        if (duringCall()) return false
        val plan = plan()
        val live = current
        val measured = reports(plan).toMutableList()
        if (live != null && measured.none { it.candidateId == live.id }) {
            // The link that is up right now is proof its path works; probes only rank the others.
            measured += ProbeReport(live.id, CandidateScore.MIN_PASSES, attempts[live.id]?.rttMs)
        }
        val decision = selector.observe(plan, measured, mode(), duringCall(), relayOnly)
        if (!decision.switched) return false
        BridgeLog.i(TAG, "selection moves the link to ${decision.candidate?.label} (${decision.reason})")
        selected = decision.candidate?.let { SelectedCandidate(it.kind, it.host) }
        return true
    }

    override fun shutdown() {
        mdns.stop()
        for (factory in addressFactories.values) runCatching { factory.shutdown() }
        addressFactories.clear()
        irohFactory?.shutdown()
    }

    private fun plan(): List<Candidate> {
        startDiscovery()
        val stored = store.load()
        return CandidatePlanner.plan(config, stored, discovered.toList())
    }

    private fun reports(plan: List<Candidate>): List<ProbeReport> {
        // A pinned mode probes only what it may use; the rest learn nothing while it is pinned.
        val allowed = mode()
        return plan.filter { allowed.allows(it) }.mapNotNull { candidate ->
            report(candidate)
        }
    }

    private fun report(candidate: Candidate): ProbeReport = if (candidate.kind == LinkKind.IROH) {
        // The pairing ticket already authenticates iroh; a reconnect only needs the history.
        val record = attempts[candidate.id]
        val passes = if (candidate.primary) CandidateScore.MIN_PASSES else record?.consecutive ?: 0
        ProbeReport(candidate.id, passes, record?.rttMs)
    } else {
        prober?.probe(candidate) ?: ProbeReport(candidate.id, if (candidate.primary) CandidateScore.MIN_PASSES else 0, null)
    }

    private fun addressFactory(candidate: Candidate): WebSocketLinkFactory {
        val pin = candidate.certSha256
        val url = candidate.url ?: error("address candidate without a url")
        val address = BridgeConfig.validateAddress(url, config.token, pin, config.appToken)
        return addressFactories.getOrPut(candidate.id) { WebSocketLinkFactory(address) }
    }

    private fun startDiscovery() {
        if (!discoveryStarted.compareAndSet(false, true)) return
        mdns.start()
    }

    /** Attempt history per candidate: consecutive successes, and the last measured route. */
    private class Attempts {
        @Volatile var consecutive: Int = 0

        @Volatile var rttMs: Long? = null
    }

    private fun noteOpened(candidate: Candidate, connectMs: Long) {
        val record = attempts.getOrPut(candidate.id) { Attempts() }
        record.consecutive++
        if (record.rttMs == null) record.rttMs = connectMs
    }

    private fun noteDown(candidate: Candidate) {
        attempts.getOrPut(candidate.id) { Attempts() }.consecutive = 0
    }

    private fun noteRoute(candidate: Candidate, route: DeviceLink.Route) {
        val record = attempts.getOrPut(candidate.id) { Attempts() }
        // Keep the connect-time sample when the transport reports no round trip of its own.
        if (route.rttMs != null) record.rttMs = route.rttMs
        if (candidate.kind == LinkKind.IROH) relayOnly = route.relay
    }

    /** Forwards one attempt and records what it taught the selector. */
    private inner class ReportingLink(
        private val candidate: Candidate,
        private val delegate: DeviceLink
    ) : DeviceLink {
        @Volatile private var openedAt = 0L

        override val kind: LinkKind = candidate.kind

        override val backlogBytes: Long get() = delegate.backlogBytes

        override val supportsAttachments: Boolean get() = delegate.supportsAttachments

        override fun start(listener: DeviceLink.Listener) {
            openedAt = System.nanoTime()
            delegate.start(
                object : DeviceLink.Listener {
                    override fun onLinkOpened() {
                        noteOpened(candidate, (System.nanoTime() - openedAt) / 1_000_000)
                        listener.onLinkOpened()
                    }

                    override fun onLinkText(text: String) = listener.onLinkText(text)

                    override fun onLinkBinary(bytes: ByteString) = listener.onLinkBinary(bytes)

                    override fun onLinkDown(reason: String) {
                        noteDown(candidate)
                        listener.onLinkDown(reason)
                    }
                }
            )
        }

        override fun sendText(message: String): Boolean = delegate.sendText(message)

        override fun sendBinary(frame: ByteArray, length: Int) = delegate.sendBinary(frame, length)

        override fun cancel() = delegate.cancel()

        override fun route(): DeviceLink.Route? = delegate.route()?.also { noteRoute(candidate, it) }

        override suspend fun openAttachment(header: String): AttachmentChannel? = delegate.openAttachment(header)
    }

    /** A link that fails at once with [reason]; used when no transport could be built. */
    private class ProbeFailureLink(private val reason: String) : DeviceLink {
        override val backlogBytes: Long get() = 0L

        override fun start(listener: DeviceLink.Listener) {
            listener.onLinkDown(reason)
        }

        override fun sendText(message: String): Boolean = false

        override fun sendBinary(frame: ByteArray, length: Int) = Unit

        override fun cancel() = Unit

        override fun route(): DeviceLink.Route? = null
    }

    private companion object {
        const val TAG = "PhonxietyBridge"
    }
}
