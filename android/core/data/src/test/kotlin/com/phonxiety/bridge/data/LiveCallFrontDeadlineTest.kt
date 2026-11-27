package com.phonxiety.bridge.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two deadlines the data layer owns: the answer-cover wait keeps its deadline when the next
 * activity instance adopts it, and the placement opportunity outlives the daemon's voice session
 * while still closing on a placement that never dials. The answer read is the one place that
 * deadline leaves the data layer, so it answers only for the call that owns an open wait and only
 * while that wait still has time left.
 */
class LiveCallFrontDeadlineTest {
    @Before
    fun setUp() {
        LiveCallFront.resetForTest()
    }

    @After
    fun tearDown() {
        LiveCallFront.resetForTest()
    }

    private fun place(callId: String = "c1") {
        LiveCallFront.expectPlacement(1_000)
        LiveCallFront.remember(callId, 1_000)
    }

    @Test
    fun theDeadlineIsTheInstantTheOpenWaitExpires() {
        place()
        LiveCallFront.armAnswerCover(1_500)

        assertEquals(1_500 + LiveCallFront.ANSWER_TTL_MS, LiveCallFront.answerCoverDeadline("c1", 1_500))
    }

    @Test
    fun anotherCallHasNoDeadlineToAdopt() {
        place()
        LiveCallFront.armAnswerCover(1_500)

        assertNull(LiveCallFront.answerCoverDeadline("c2", 1_500))
    }

    @Test
    fun aClosedWaitHasNoDeadlineToAdopt() {
        place()
        LiveCallFront.armAnswerCover(1_500)
        LiveCallFront.closeAnswerCover()

        assertNull(LiveCallFront.answerCoverDeadline("c1", 1_500))
    }

    @Test
    fun aWaitThatRanOutHasNoDeadlineToAdopt() {
        place()
        LiveCallFront.armAnswerCover(1_500)

        assertNull(LiveCallFront.answerCoverDeadline("c1", 1_500 + LiveCallFront.ANSWER_TTL_MS + 1))
    }

    @Test
    fun thePlacementStaysOpenWhileTheVoiceSessionRuns() {
        // The daemon prepares the voice session before it dials, so the reply and Telecom's cover
        // follow the tap by seconds: the opportunity has to still be open when the cover comes.
        LiveCallFront.expectPlacement(1_000)
        LiveCallFront.remember("c1", 5_000)

        assertTrue(LiveCallFront.awaitsCover(14_000))
        assertEquals("c1", LiveCallFront.pendingCallId(14_000))
        assertTrue(LiveCallFront.claimReturn("c1", 14_300))
    }

    @Test
    fun aPlacementThatNeverDialsClosesAtTheCap() {
        LiveCallFront.expectPlacement(1_000)

        assertTrue(LiveCallFront.awaitsCover(1_000 + LiveCallFront.PLACEMENT_CAP_MS))
        assertFalse(LiveCallFront.awaitsCover(1_001 + LiveCallFront.PLACEMENT_CAP_MS))
    }

    @Test
    fun aReplyAfterTheCapArmsNothing() {
        LiveCallFront.expectPlacement(1_000)
        LiveCallFront.remember("c1", 1_001 + LiveCallFront.PLACEMENT_CAP_MS)

        assertFalse(LiveCallFront.awaitsCover(1_001 + LiveCallFront.PLACEMENT_CAP_MS))
        assertNull(LiveCallFront.pendingCallId(1_001 + LiveCallFront.PLACEMENT_CAP_MS))
    }
}
