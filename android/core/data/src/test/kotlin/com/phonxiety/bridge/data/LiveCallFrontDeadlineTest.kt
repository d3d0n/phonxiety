package com.phonxiety.bridge.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The answer-cover wait keeps its deadline when the next activity instance adopts it. The read is
 * the one place the deadline leaves the data layer, so it answers only for the call that owns an
 * open wait and only while that wait still has time left.
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
}
