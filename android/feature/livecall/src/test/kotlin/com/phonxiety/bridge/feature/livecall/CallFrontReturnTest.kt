package com.phonxiety.bridge.feature.livecall

import android.os.Handler
import android.os.Looper
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The lifecycle sequences card G3 names: a placed call is pulled back exactly once, only inside the
 * initial cover, and nothing else — a later cover, a manual return, a late or missing placement
 * reply, a hangup, an incoming call — brings it forward. Call state stays exactly what production
 * publishes: when an incoming call covers an owned outgoing call, [BridgeEngine] ignores the
 * incoming one, so the runtime keeps showing the placed call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallFrontReturnTest {
    private var call: CallState? = null
    private var interactive = true
    private var returns = 0
    private var now = 1_000L

    @Before
    fun setUp() {
        LiveCallFront.resetForTest()
        call = null
        interactive = true
        returns = 0
        now = 1_000L
    }

    @After
    fun tearDown() {
        LiveCallFront.resetForTest()
    }

    private fun controller() = CallFrontReturn(
        handler = Handler(Looper.getMainLooper()),
        call = { call },
        screenInteractive = { interactive },
        openFront = { returns++ },
        clock = { now }
    )

    private fun idle(millis: Long) {
        now += millis
        ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS)
    }

    /** The composer asked for this call and the daemon already accepted it. */
    private fun placed(callId: String) {
        LiveCallFront.expectPlacement(now)
        LiveCallFront.remember(callId, now)
    }

    private fun dialing(callId: String) = CallState(
        callId = callId,
        destinationMasked = "+998 90 *** ** 67",
        phase = CallPhase.DIALING,
        startedAtEpochMs = 1L
    )

    private fun active(callId: String) = dialing(callId).copy(phase = CallPhase.ACTIVE)

    private fun ending(callId: String) = dialing(callId).copy(phase = CallPhase.ENDING)

    private fun ended(callId: String) = dialing(callId).copy(phase = CallPhase.ENDED)

    @Test
    fun placedCallReturnsOnceAfterThePlacementReply() {
        val front = controller()

        // The composer asked for the call; Telecom's screen covers the app before the reply lands.
        LiveCallFront.expectPlacement(now)
        front.onStop(canReturn = true)
        idle(400)
        assertEquals(0, returns)

        // The daemon answers: this phone placed call c1, and it is dialing behind the cover.
        LiveCallFront.remember("c1", now)
        call = dialing("c1")
        idle(300)

        assertEquals(1, returns)
        assertNull(LiveCallFront.pendingCallId(now))
        assertFalse(LiveCallFront.awaitsCover(now))
    }

    @Test
    fun secondStopDuringActiveDoesNotReturn() {
        val front = controller()
        placed("c1")
        call = dialing("c1")
        front.onStop(canReturn = true)
        idle(400)
        assertEquals(1, returns)

        // The call connects; the user leaves again. The one shot is spent.
        call = active("c1")
        front.onResume()
        front.onStop(canReturn = true)
        idle(4000)

        assertEquals(1, returns)
        assertNull(LiveCallFront.pendingCallId(now))
    }

    @Test
    fun recreatedActivityDoesNotReturnAgain() {
        val first = controller()
        placed("c1")
        call = dialing("c1")
        first.onStop(canReturn = true)
        idle(400)
        assertEquals(1, returns)

        // A new activity instance (configuration change, process kept alive) gets the same stop.
        val recreated = controller()
        call = active("c1")
        recreated.onStop(canReturn = true)
        idle(4000)

        assertEquals(1, returns)
    }

    @Test
    fun incomingCallCoverDoesNotReturn() {
        val front = controller()

        // Nobody placed anything from this phone; an incoming call covers the app.
        call = dialing("incoming")
        front.onStop(canReturn = true)
        idle(4000)

        assertEquals(0, returns)
        assertNull(LiveCallFront.placedCallId())
    }

    @Test
    fun anotherCallWhileThePlacementReplyIsPendingDoesNotReturn() {
        val front = controller()
        LiveCallFront.expectPlacement(now)

        // An incoming call arrives before the daemon confirms the placement and covers the app.
        front.onStop(canReturn = true)
        call = dialing("incoming")
        idle(4000)

        assertEquals(0, returns)
        assertNull(LiveCallFront.pendingCallId(now))
    }

    @Test
    fun aManualReturnBeforeTheAttemptClosesTheOpportunity() {
        val front = controller()
        LiveCallFront.expectPlacement(now)
        call = dialing("c1")

        front.onStop(canReturn = true)
        // The user comes back by hand before the delayed attempt runs.
        front.onResume()
        // The placement reply lands while the app is in front.
        LiveCallFront.remember("c1", now)
        idle(4000)

        assertEquals(0, returns)
        assertFalse(LiveCallFront.awaitsCover(now))

        // A later cover of the same, still owned call does not schedule anything.
        front.onStop(canReturn = true)
        idle(4000)
        assertEquals(0, returns)
    }

    @Test
    fun aReplyThatLandsAfterTheRetriesExpiredDoesNothing() {
        val front = controller()
        LiveCallFront.expectPlacement(now)
        call = dialing("c1")

        // Telecom's screen is up but the daemon's reply never arrives within the wait.
        front.onStop(canReturn = true)
        idle(4000)
        assertEquals(0, returns)

        // The reply lands afterwards; the cover it belonged to is long over.
        LiveCallFront.remember("c1", now)
        idle(4000)

        assertEquals(0, returns)
        assertNull(LiveCallFront.pendingCallId(now))
        assertFalse(LiveCallFront.awaitsCover(now))

        front.onStop(canReturn = true)
        idle(4000)
        assertEquals(0, returns)
    }

    @Test
    fun aReplyThatLandsAfterTheOpportunityExpiredDoesNothing() {
        val front = controller()
        LiveCallFront.expectPlacement(now)
        call = dialing("c1")
        now += LiveCallFront.OPPORTUNITY_TTL_MS + 1

        LiveCallFront.remember("c1", now)
        front.onStop(canReturn = true)
        idle(4000)

        assertEquals(0, returns)
        assertNull(LiveCallFront.pendingCallId(now))
    }

    @Test
    fun aHangupBeforeTheReturnLandsDoesNothing() {
        val front = controller()
        placed("c1")
        front.onStop(canReturn = true)

        // The far end hangs up (or the user does) during the wait; BridgeEngine publishes ENDING.
        call = ending("c1")
        idle(4000)

        assertEquals(0, returns)
        assertNull(LiveCallFront.pendingCallId(now))
        assertNull(LiveCallFront.placedCallId())

        front.onStop(canReturn = true)
        idle(4000)
        assertEquals(0, returns)
    }

    @Test
    fun callEndedClearsTheToken() {
        val front = controller()
        placed("c1")
        call = ended("c1")

        front.onStart()

        assertNull(LiveCallFront.placedCallId())
        assertNull(LiveCallFront.pendingCallId(now))
        assertFalse(LiveCallFront.awaitsCover(now))

        front.onStop(canReturn = true)
        idle(4000)
        assertEquals(0, returns)
    }

    @Test
    fun endedBeforeTheReturnLandsClearsTheToken() {
        val front = controller()
        placed("c1")
        call = dialing("c1")
        front.onStop(canReturn = true)

        // The call dies during the wait for the reply.
        call = ended("c1")
        idle(4000)

        assertEquals(0, returns)
        assertNull(LiveCallFront.placedCallId())
    }

    @Test
    fun aDeliberateLeaveIsNotUndone() {
        val front = controller()
        placed("c1")
        call = dialing("c1")

        front.onUserLeaveHint()
        front.onStop(canReturn = true)
        idle(4000)

        assertEquals(0, returns)
        assertFalse(LiveCallFront.awaitsCover(now))
    }

    @Test
    fun aFinishingActivityDoesNotReturn() {
        val front = controller()
        placed("c1")
        call = dialing("c1")

        front.onStop(canReturn = false)
        idle(4000)

        assertEquals(0, returns)
        assertEquals("c1", LiveCallFront.pendingCallId(now))
    }

    @Test
    fun screenOffSpendsTheTokenWithoutReturning() {
        val front = controller()
        placed("c1")
        call = dialing("c1")
        interactive = false

        front.onStop(canReturn = true)
        idle(4000)

        assertEquals(0, returns)
        assertNull(LiveCallFront.pendingCallId(now))
    }

    @Test
    fun anUnplacedStopLeavesTheWaitIdle() {
        val front = controller()
        call = active("c9")

        front.onStop(canReturn = true)
        idle(4000)

        assertEquals(0, returns)
    }
}
