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
 * The lifecycle sequences card G2 names: a placed call is pulled back exactly once, and nothing
 * else — a later cover, a recreated activity, an incoming call, a spent call — brings it forward.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallFrontReturnTest {
    private var call: CallState? = null
    private var interactive = true
    private var returns = 0

    @Before
    fun setUp() {
        LiveCallFront.resetForTest()
        call = null
        interactive = true
        returns = 0
    }

    @After
    fun tearDown() {
        LiveCallFront.resetForTest()
    }

    private fun controller() = CallFrontReturn(
        handler = Handler(Looper.getMainLooper()),
        call = { call },
        screenInteractive = { interactive },
        openFront = { returns++ }
    )

    private fun idle(millis: Long) {
        ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS)
    }

    private fun dialing(callId: String) = CallState(
        callId = callId,
        destinationMasked = "+998 90 *** ** 67",
        phase = CallPhase.DIALING,
        startedAtEpochMs = 1L
    )

    private fun active(callId: String) = dialing(callId).copy(phase = CallPhase.ACTIVE)

    private fun ended(callId: String) = dialing(callId).copy(phase = CallPhase.ENDED)

    @Test
    fun placedCallReturnsOnceAfterThePlacementReply() {
        val front = controller()

        // The composer asked for the call; Telecom's screen covers the app before the reply lands.
        LiveCallFront.expectPlacement()
        front.onStop(canReturn = true)
        idle(400)
        assertEquals(0, returns)

        // The daemon answers: this phone placed call c1, and it is dialing behind the cover.
        LiveCallFront.remember("c1")
        call = dialing("c1")
        idle(300)

        assertEquals(1, returns)
        assertNull(LiveCallFront.pendingCallId())
        assertFalse(LiveCallFront.awaitsCover())
    }

    @Test
    fun secondStopDuringActiveDoesNotReturn() {
        val front = controller()
        LiveCallFront.remember("c1")
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
        assertNull(LiveCallFront.pendingCallId())
    }

    @Test
    fun recreatedActivityDoesNotReturnAgain() {
        val first = controller()
        LiveCallFront.remember("c1")
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
    fun anotherCallWhileAPlacementIsPendingDoesNotReturn() {
        val front = controller()
        LiveCallFront.remember("c1")
        call = dialing("incoming")

        front.onStop(canReturn = true)
        idle(4000)

        assertEquals(0, returns)
        assertEquals("c1", LiveCallFront.pendingCallId())
    }

    @Test
    fun callEndedClearsTheToken() {
        val front = controller()
        LiveCallFront.remember("c1")
        call = ended("c1")

        front.onStart()

        assertNull(LiveCallFront.placedCallId())
        assertNull(LiveCallFront.pendingCallId())
        assertFalse(LiveCallFront.awaitsCover())

        front.onStop(canReturn = true)
        idle(4000)
        assertEquals(0, returns)
    }

    @Test
    fun endedBeforeTheReturnLandsClearsTheToken() {
        val front = controller()
        LiveCallFront.remember("c1")
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
        LiveCallFront.remember("c1")
        call = dialing("c1")

        front.onUserLeaveHint()
        front.onStop(canReturn = true)
        idle(4000)

        assertEquals(0, returns)
    }

    @Test
    fun aFinishingActivityDoesNotReturn() {
        val front = controller()
        LiveCallFront.remember("c1")
        call = dialing("c1")

        front.onStop(canReturn = false)
        idle(4000)

        assertEquals(0, returns)
        assertEquals("c1", LiveCallFront.pendingCallId())
    }

    @Test
    fun screenOffSpendsTheTokenWithoutReturning() {
        val front = controller()
        LiveCallFront.remember("c1")
        call = dialing("c1")
        interactive = false

        front.onStop(canReturn = true)
        idle(4000)

        assertEquals(0, returns)
        assertNull(LiveCallFront.pendingCallId())
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
