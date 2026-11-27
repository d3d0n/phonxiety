package com.phonxiety.bridge.net

import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.model.LinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Link selection: the plan a pairing produces, the scores it produces from a
 * probe, and the rules that keep the incumbent from flapping.
 */
class LinkSelectionTest {
    private val pin = "a".repeat(64)

    private fun lanCandidate(url: String = "wss://192.168.1.10:18763/device"): Candidate = Candidate.address(url, pin, primary = true)

    private fun tailnetCandidate(url: String = "wss://100.64.0.9:18763/device"): Candidate = Candidate.address(url, pin)

    private fun report(candidate: Candidate, rttMs: Long?, passes: Int = 3): ProbeReport = ProbeReport(candidate.id, passes, rttMs)

    // -- plan ------------------------------------------------------------

    @Test
    fun planStartsWithThePairedTransport() {
        val config = BridgeConfig.validateAddress("wss://192.168.1.10:18763/device", "token", pin)
        val plan = CandidatePlanner.plan(config)
        assertEquals(1, plan.size)
        assertEquals(LinkKind.WEBSOCKET, plan[0].kind)
        assertTrue(plan[0].primary)
    }

    @Test
    fun aTailnetPairingIsATailscaleLink() {
        val config = BridgeConfig.validateAddress("wss://100.64.5.6:18763/device", "token", pin)
        assertEquals(LinkKind.TAILSCALE, CandidatePlanner.plan(config)[0].kind)
        val magic = BridgeConfig.validateAddress("wss://daemon.tail1.ts.net:18763/device", "token", pin)
        assertEquals(LinkKind.TAILSCALE, CandidatePlanner.plan(magic)[0].kind)
    }

    @Test
    fun storedAddressesNeedAPinAndAreClassified() {
        val config = BridgeConfig.validateIroh("ticket", "token")
        val stored = listOf(
            StoredCandidate("wss://100.64.0.9:18763/device", pin),
            StoredCandidate("wss://192.168.1.10:18763/device", pin),
            StoredCandidate("wss://192.168.1.11:18763/device", null)
        )
        val plan = CandidatePlanner.plan(config, stored, storeIdentity = CandidateMerge.identity(config))
        assertEquals(listOf(LinkKind.IROH, LinkKind.TAILSCALE, LinkKind.WEBSOCKET), plan.map { it.kind })
        assertFalse(plan.drop(1).any { it.id.endsWith("192.168.1.11:18763/device") })
    }

    @Test
    fun mdnsAddressesAreTrustedOnlyWithThePairingPin() {
        val config = BridgeConfig.validateAddress("wss://192.168.1.10:18763/device", "token", pin)
        val discovered = listOf(
            DiscoveredDaemon("192.168.1.20", 18763, pin, 3, "phonxiety-a"),
            DiscoveredDaemon("192.168.1.21", 18763, "b".repeat(64), 3, "phonxiety-b"),
            DiscoveredDaemon("192.168.1.22", 18763, null, 3, "phonxiety-c")
        )
        val plan = CandidatePlanner.plan(config, emptyList(), discovered)
        assertEquals(2, plan.size)
        assertEquals("wss://192.168.1.20:18763/device", plan[1].url)
    }

    @Test
    fun aKnownPinDropsStoredAndMdnsAddressesOfAnotherDaemon() {
        val config = BridgeConfig.validateIroh("ticket", "token", certSha256 = pin)
        val other = "b".repeat(64)
        val stored = listOf(StoredCandidate("wss://192.168.1.10:18763/device", other))
        val discovered = listOf(
            DiscoveredDaemon("192.168.1.30", 18763, other, 3, "phonxiety-b"),
            DiscoveredDaemon("192.168.1.31", 18763, pin, 3, "phonxiety-a")
        )

        val plan = CandidatePlanner.plan(config, stored, discovered, CandidateMerge.identity(config))

        assertEquals(listOf(LinkKind.IROH, LinkKind.WEBSOCKET), plan.map { it.kind })
        assertEquals("wss://192.168.1.31:18763/device", plan[1].url)
    }

    @Test
    fun modeFiltersCandidates() {
        val lan = lanCandidate()
        val tailnet = tailnetCandidate()
        val iroh = Candidate.iroh()
        assertTrue(ConnectionMode.AUTO.allows(lan))
        assertFalse(ConnectionMode.IROH_ONLY.allows(lan))
        assertTrue(ConnectionMode.IROH_ONLY.allows(iroh))
        assertTrue(ConnectionMode.LAN_ONLY.allows(lan))
        assertFalse(ConnectionMode.LAN_ONLY.allows(tailnet))
        assertTrue(ConnectionMode.TAILSCALE_ONLY.allows(tailnet))
        assertFalse(ConnectionMode.TAILSCALE_ONLY.allows(lan))
        assertEquals(ConnectionMode.AUTO, ConnectionMode.of("nonsense"))
        assertEquals(ConnectionMode.TAILSCALE_ONLY, ConnectionMode.of(" Tailscale "))
    }

    // -- scoring ---------------------------------------------------------

    @Test
    fun scoresFollowTheBaseMinusAQuarterOfTheRoundTrip() {
        val lan = lanCandidate()
        val tailnet = tailnetCandidate()
        val iroh = Candidate.iroh()
        assertEquals(100.0, CandidateScore.score(CandidateScore.base(lan), 0), 0.001)
        assertEquals(95.0, CandidateScore.score(CandidateScore.base(lan), 20), 0.001)
        assertEquals(80.0, CandidateScore.score(CandidateScore.base(tailnet), 0), 0.001)
        assertEquals(90.0, CandidateScore.score(CandidateScore.base(iroh), 0), 0.001)
        assertEquals(50.0, CandidateScore.score(CandidateScore.base(iroh, relayOnly = true), 0), 0.001)
        // The penalty is capped at 50, so a terrible LAN path ties an iroh relay with no round trip.
        assertEquals(50.0, CandidateScore.score(CandidateScore.base(lan), 4_000), 0.001)
        assertTrue(CandidateScore.score(CandidateScore.base(lan), 2_000) > CandidateScore.score(CandidateScore.base(iroh, relayOnly = true), 50))
    }

    // -- selection -------------------------------------------------------

    @Test
    fun theFirstRoundPicksTheBestMeasuredCandidate() {
        val selector = LinkSelector()
        val lan = lanCandidate()
        val iroh = Candidate.iroh()
        val dec = selector.observe(
            listOf(lan, iroh),
            listOf(report(lan, 20), report(iroh, 0)),
            ConnectionMode.AUTO,
            duringCall = false
        )
        assertEquals(lan.id, dec.candidate?.id)
        assertTrue(dec.switched)
        assertEquals("first_pick", dec.reason)
        assertEquals(lan.id, selector.incumbent()?.id)
    }

    @Test
    fun aCandidateThatHasNotPassedThreeProbesIsNeverChosen() {
        val selector = LinkSelector()
        val lan = lanCandidate()
        val dec = selector.observe(listOf(lan), listOf(report(lan, 10, passes = 2)), ConnectionMode.AUTO, duringCall = false)
        assertNull(dec.candidate)
        assertEquals("no_probe", dec.reason)
    }

    @Test
    fun aSmallAdvantageDoesNotMoveTheLink() {
        val selector = LinkSelector()
        val lan = lanCandidate()
        val faster = lanCandidate("wss://192.168.1.20:18763/device")
        selector.observe(listOf(lan, faster), listOf(report(lan, 40)), ConnectionMode.AUTO, duringCall = false)
        assertEquals(lan.id, selector.incumbent()?.id)
        repeat(2) {
            val dec = selector.observe(
                listOf(lan, faster),
                listOf(report(lan, 40), report(faster, 0)),
                ConnectionMode.AUTO,
                duringCall = false
            )
            assertEquals(lan.id, dec.candidate?.id)
            assertFalse(dec.switched)
            assertEquals("hysteresis", dec.reason)
        }
    }

    @Test
    fun aChallengerNeedsTwoConsecutiveWins() {
        val selector = LinkSelector()
        val slow = lanCandidate("wss://192.168.1.10:18763/device")
        val fast = tailnetCandidate()
        selector.observe(listOf(slow, fast), listOf(report(slow, 20)), ConnectionMode.AUTO, duringCall = false)
        assertEquals(slow.id, selector.incumbent()?.id)

        val first = selector.observe(
            listOf(slow, fast),
            listOf(report(slow, 300), report(fast, 0)),
            ConnectionMode.AUTO,
            duringCall = false
        )
        assertEquals(slow.id, first.candidate?.id)
        assertEquals("challenger_pending", first.reason)

        val second = selector.observe(
            listOf(slow, fast),
            listOf(report(slow, 300), report(fast, 0)),
            ConnectionMode.AUTO,
            duringCall = false
        )
        assertEquals(fast.id, second.candidate?.id)
        assertTrue(second.switched)
        assertEquals("challenger_win", second.reason)
    }

    @Test
    fun aFailedIncumbentIsReplacedAtOnce() {
        val selector = LinkSelector()
        val lan = lanCandidate()
        val tailnet = tailnetCandidate()
        selector.observe(listOf(lan, tailnet), listOf(report(lan, 10), report(tailnet, 60)), ConnectionMode.AUTO, duringCall = false)
        val dec = selector.observe(listOf(lan, tailnet), listOf(report(lan, null, passes = 0), report(tailnet, 60)), ConnectionMode.AUTO, duringCall = false)
        assertEquals(tailnet.id, dec.candidate?.id)
        assertEquals("incumbent_lost", dec.reason)
    }

    @Test
    fun theLinkNeverMovesDuringACall() {
        val selector = LinkSelector()
        val lan = lanCandidate()
        val fresh = tailnetCandidate()
        selector.observe(listOf(lan), listOf(report(lan, 10)), ConnectionMode.AUTO, duringCall = false)
        repeat(3) {
            val dec = selector.observe(
                listOf(lan, fresh),
                listOf(report(lan, 400), report(fresh, 0)),
                ConnectionMode.AUTO,
                duringCall = true
            )
            assertEquals(lan.id, dec.candidate?.id)
            assertFalse(dec.switched)
            assertEquals("in_call", dec.reason)
        }
    }

    @Test
    fun aPinnedModeIgnoresTheOtherCandidates() {
        val selector = LinkSelector()
        val lan = lanCandidate()
        val tailnet = tailnetCandidate()
        val dec = selector.observe(
            listOf(lan, tailnet),
            listOf(report(lan, 10), report(tailnet, 0)),
            ConnectionMode.TAILSCALE_ONLY,
            duringCall = false
        )
        assertEquals(tailnet.id, dec.candidate?.id)
        assertEquals(LinkKind.TAILSCALE, dec.candidate?.kind)
    }

    @Test
    fun aPinnedModeWithNothingReachableReportsNoCandidate() {
        val selector = LinkSelector()
        val lan = lanCandidate()
        val dec = selector.observe(listOf(lan), listOf(report(lan, 10)), ConnectionMode.TAILSCALE_ONLY, duringCall = false)
        assertNull(dec.candidate)
        assertNotNull(dec.reason)
    }

    @Test
    fun anIrohRelayIsScoredBelowEveryAddressPath() {
        val lan = lanCandidate()
        val iroh = Candidate.iroh()
        assertTrue(
            CandidateScore.score(CandidateScore.base(lan), 0) >
                CandidateScore.score(CandidateScore.base(iroh, relayOnly = true), 0)
        )
        assertTrue(
            CandidateScore.score(CandidateScore.base(iroh), 0) >
                CandidateScore.score(CandidateScore.base(iroh, relayOnly = true), 0)
        )
        // A relayed iroh link loses to the LAN candidate even with a perfect round trip.
        val selector = LinkSelector()
        val dec = selector.observe(
            listOf(lan, iroh),
            listOf(report(lan, 40), report(iroh, 0)),
            ConnectionMode.AUTO,
            duringCall = false,
            relayOnly = true
        )
        assertEquals(lan.id, dec.candidate?.id)
    }
}
