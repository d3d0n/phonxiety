package com.phonxiety.bridge.feature.livecall

import android.os.Handler
import android.os.Looper
import com.phonxiety.bridge.data.LiveCallFront
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The lifecycle sequences the return policy has to get right: a placed call is pulled back once
 * over the initial cover and once more over the cover Telecom raises when it is answered, and
 * nothing else — a manual return, a late or missing placement reply, a hangup, an incoming call, a
 * deliberate leave, a second cover after the answer — brings it forward. Call state stays exactly
 * what production publishes: when an incoming call covers an owned outgoing call, [BridgeEngine]
 * ignores the incoming one, so the runtime keeps showing the placed call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallFrontReturnTest {
    private var call: CallState? = null
    private var interactive = true
    private var another = false
    private var returns = 0
    private var now = 1_000L

    @Before
    fun setUp() {
        LiveCallFront.resetForTest()
        call = null
        interactive = true
        another = false
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
        anotherCall = { another },
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
    fun answeredCoverReturnsOnceAfterTheAnswer() {
        val front = controller()
        placed("c1")
        call = dialing("c1")
        front.onStop(canReturn = true)
        idle(400)
        assertEquals(1, returns)

        // The live screen is back in front. The far end answers, Telecom raises its screen again,
        // and the daemon reports ACTIVE a link round-trip after the stop.
        front.onResume()
        call = dialing("c1")
        front.onStop(canReturn = true)
        idle(400)
        assertEquals("still ringing: nothing to pull back", 1, returns)

        call = active("c1")
        idle(300)
        assertEquals(2, returns)
        assertFalse(LiveCallFront.answerCoverPending(now))
    }

    @Test
    fun aSecondCoverAfterTheAnswerDoesNotReturn() {
        val front = controller()
        placed("c1")
        call = dialing("c1")
        front.onStop(canReturn = true)
        idle(400)
        front.onResume()

        // Answered before the cover: the answer screen comes up over the live screen.
        call = active("c1")
        front.onStop(canReturn = true)
        idle(400)
        assertEquals(2, returns)

        // A hold and unhold raise Telecom's screen once more; the answer permit is spent.
        front.onResume()
        front.onStop(canReturn = true)
        idle(4000)
        assertEquals(2, returns)
    }

    @Test
    fun leavingBeforeTheAnswerMeansNoReturn() {
        val front = controller()
        placed("c1")
        call = dialing("c1")
        front.onStop(canReturn = true)
        idle(400)
        assertEquals(1, returns)

        // The user goes Home and answers from the system screen: the live screen was left behind
        // on purpose and must stay there.
        front.onUserLeaveHint()
        front.onStop(canReturn = true)
        idle(400)
        assertEquals(1, returns)

        call = active("c1")
        front.onStop(canReturn = true)
        idle(4000)
        assertEquals(1, returns)
        assertFalse(LiveCallFront.answerCoverPending(now))
    }

    @Test
    fun incomingCallAnsweredDoesNotReturn() {
        val front = controller()

        // An incoming call is answered while the app is covered; this phone placed nothing.
        call = active("incoming")
        front.onStop(canReturn = true)
        idle(4000)

        assertEquals(0, returns)
        assertNull(LiveCallFront.placedCallId())
        assertFalse(LiveCallFront.answerCoverPending(now))
    }

    @Test
    fun anAnswerAfterTheAnswerWaitExpiredDoesNothing() {
        val front = controller()
        placed("c1")
        call = dialing("c1")
        front.onStop(canReturn = true)
        idle(400)
        front.onResume()

        // The cover is up but the ringback lasts past the wait for ACTIVE.
        call = dialing("c1")
        front.onStop(canReturn = true)
        idle(4_500)
        assertEquals(1, returns)

        // The answer lands afterwards; the cover it belonged to is long over.
        call = active("c1")
        idle(4_000)
        assertEquals(1, returns)
        assertFalse(LiveCallFront.answerCoverPending(now))
    }

    @Test
    fun secondStopDuringActiveDoesNotReturn() {
        val front = controller()
        placed("c1")
        call = dialing("c1")
        front.onStop(canReturn = true)
        idle(400)
        assertEquals(1, returns)

        // The call connects while the live screen is in front: the answered screen is behind it, and
        // a cover after it — a hold, a reopened dialer — is not the answer.
        call = active("c1")
        front.onResume()
        front.onStop(canReturn = true)
        idle(4000)

        assertEquals(1, returns)
        assertNull(LiveCallFront.pendingCallId(now))
    }

    @Test
    fun leavingAgainDuringActiveDoesNotReturn() {
        val front = controller()
        placed("c1")
        call = dialing("c1")
        front.onStop(canReturn = true)
        idle(400)
        assertEquals(1, returns)

        // The call connects; the user leaves again on purpose instead of watching it.
        call = active("c1")
        front.onUserLeaveHint()
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
    fun aCoverFromAnotherCallDoesNotOpenTheAnswerWait() {
        val front = controller()
        placed("c1")
        call = dialing("c1")
        front.onStop(canReturn = true)
        idle(400)
        assertEquals(1, returns)
        front.onResume()

        // An incoming call is answered over ours, so the screen in front belongs to that call while
        // the daemon keeps publishing the call this phone placed.
        another = true
        call = active("c1")
        front.onStop(canReturn = true)
        idle(1_000)
        assertEquals("another call's screen is in front", 1, returns)

        // That call ends and our own answer cover comes up; the permit was still there.
        another = false
        front.onStop(canReturn = true)
        idle(400)
        assertEquals(2, returns)
        assertFalse(LiveCallFront.answerCoverPending(now))
    }

    @Test
    fun aCoverFromAnotherCallDuringTheAnswerWaitIsNotReplaced() {
        val front = controller()
        placed("c1")
        call = dialing("c1")
        front.onStop(canReturn = true)
        idle(400)
        front.onResume()

        // The answer cover is up while the daemon still reports the call as ringing.
        call = dialing("c1")
        front.onStop(canReturn = true)
        idle(400)
        assertEquals(1, returns)

        // An incoming call arrives and is answered over ours before the daemon reports the answer.
        another = true
        call = active("c1")
        idle(1_000)
        assertEquals("another call came in over ours", 1, returns)

        // That call ends. Telecom does not stop this activity again, yet the wait this stop opened
        // is still ours: the placed call is ACTIVE and the wait's own deadline has not passed.
        another = false
        idle(300)
        assertEquals(2, returns)
    }

    @Test
    fun anotherCallThatOutlivesTheAnswerWaitEndsItWithoutATickBehind() {
        val front = controller()
        placed("c1")
        call = dialing("c1")
        front.onStop(canReturn = true)
        idle(400)
        front.onResume()

        // The answer cover is up and the daemon reports the answer, but another call is in front.
        front.onStop(canReturn = true)
        another = true
        call = active("c1")
        idle(1_000)
        assertEquals(1, returns)

        // The other call outlasts the wait: its deadline closes the permit, and nothing is spent on
        // the other call's screen.
        idle(LiveCallFront.ANSWER_TTL_MS)
        assertFalse(LiveCallFront.answerCoverPending(now))

        // When the other call finally ends there is no tick left to pull this call forward.
        another = false
        call = active("c1")
        idle(2_000)
        assertEquals(1, returns)
        assertEquals(
            "the deadline leaves no tick behind",
            Duration.ZERO,
            shadowOf(Looper.getMainLooper()).nextScheduledTaskTime
        )
    }

    @Test
    fun anAnsweredCallUnderTheInitialReturnIsNotReturnedOverAgain() {
        val front = controller()
        placed("c1")

        // The far end answers before the placement wait lands, so the initial return already puts
        // the live screen over the answered call.
        call = active("c1")
        front.onStop(canReturn = true)
        idle(400)
        assertEquals(1, returns)

        // That return retired the permit itself: the answered screen was already behind it.
        assertFalse(LiveCallFront.armAnswerCover(now))

        // A hold raises Telecom's screen again: nothing is left to wait for.
        front.onResume()
        front.onStop(canReturn = true)
        idle(4_000)
        assertEquals(1, returns)
    }

    @Test
    fun anAnswerWaitSurvivesRecreationWithItsOriginalDeadline() {
        val first = controller()
        placed("c1")
        call = dialing("c1")
        first.onStop(canReturn = true)
        idle(400)
        assertEquals(1, returns)
        first.onResume()

        // The answer cover is up while the daemon still reports the call as ringing.
        call = dialing("c1")
        first.onStop(canReturn = true)
        idle(400)
        assertEquals(1, returns)

        // A configuration change recreates the activity behind the same cover.
        first.onDestroy(recreating = true)
        val recreated = controller()
        recreated.onStart()
        recreated.onStop(canReturn = false)

        call = active("c1")
        idle(500)
        assertEquals("the wait the first instance opened is still ours", 2, returns)

        // Spent once: a later cover stays with Telecom.
        recreated.onResume()
        recreated.onStop(canReturn = true)
        idle(4_000)
        assertEquals(2, returns)
    }

    @Test
    fun aRecreatedAnswerWaitKeepsTheDeadlineItOpenedWith() {
        val first = controller()
        placed("c1")
        call = dialing("c1")
        first.onStop(canReturn = true)
        idle(400)
        assertEquals(1, returns)
        first.onResume()

        // The wait opens at this stop and the daemon is slow to report the answer.
        call = dialing("c1")
        first.onStop(canReturn = true)
        idle(400)
        assertEquals(1, returns)

        // The activity is recreated, and while no instance holds the wait its deadline runs out.
        first.onDestroy(recreating = true)
        now += LiveCallFront.ANSWER_TTL_MS

        // Adopting the old wait cannot mint a fresh window: the answer is past its deadline.
        val recreated = controller()
        recreated.onStart()
        call = active("c1")
        idle(1_000)
        assertEquals(1, returns)
    }

    @Test
    fun aRejectedSecondPlacementKeepsTheSpentAnswerPermit() {
        val front = controller()
        placed("c1")
        call = dialing("c1")
        front.onStop(canReturn = true)
        idle(400)
        front.onResume()
        call = active("c1")
        front.onStop(canReturn = true)
        idle(400)
        assertEquals(2, returns)
        front.onResume()

        // The lab starts another placement while the call is up and the daemon rejects it.
        LiveCallFront.expectPlacement(now)
        call = active("c1")
        LiveCallFront.cancelPlacement()

        // A hold raises Telecom's screen again; the spent answer permit must stay spent.
        front.onStop(canReturn = true)
        idle(4_000)
        assertEquals(2, returns)
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
    fun anotherCallDuringTheInitialDelayIsNotReplaced() {
        val front = controller()
        placed("c1")
        call = dialing("c1")

        // Telecom's screen is up and this phone placed the call, but the initial delay is still
        // running when an incoming call arrives over it.
        front.onStop(canReturn = true)
        another = true
        idle(400)
        assertEquals("another call's screen is in front", 0, returns)

        // It stays in front; the initial permit is not spent on its screen.
        idle(1_000)
        assertEquals(0, returns)

        // It ends before the placement opportunity's own deadline: the permit is still there.
        another = false
        idle(300)
        assertEquals(1, returns)
        assertFalse(LiveCallFront.awaitsCover(now))
    }

    @Test
    fun anotherCallOutlastingTheInitialDeadlineSpendsNothing() {
        val front = controller()
        placed("c1")
        call = dialing("c1")
        front.onStop(canReturn = true)
        another = true
        idle(400)
        assertEquals(0, returns)

        // The other call outlasts the placement opportunity's own deadline: the permit is gone.
        idle(LiveCallFront.OPPORTUNITY_TTL_MS)
        assertFalse(LiveCallFront.awaitsCover(now))

        // Its screen ending later cannot pull this call forward either.
        another = false
        idle(1_000)
        assertEquals(0, returns)
    }

    @Test
    fun noTickIsLeftAtTheAnswerWaitDeadline() {
        val front = controller()
        placed("c1")
        call = dialing("c1")
        front.onStop(canReturn = true)
        idle(400)
        assertEquals(1, returns)
        front.onResume()

        // The answer cover is up while another call is in front; the daemon reports the answer.
        call = dialing("c1")
        front.onStop(canReturn = true)
        val openedAt = now
        another = true
        call = active("c1")
        idle(1_000)
        assertEquals(1, returns)

        // Idle exactly to the wait's own deadline, not past it: the last tick must close the wait
        // and leave nothing queued after the deadline instant.
        idle(openedAt + LiveCallFront.ANSWER_TTL_MS - now)
        assertFalse(LiveCallFront.answerCoverPending(openedAt + LiveCallFront.ANSWER_TTL_MS))
        assertEquals(
            "no tick is left at the deadline",
            Duration.ZERO,
            shadowOf(Looper.getMainLooper()).nextScheduledTaskTime
        )
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
