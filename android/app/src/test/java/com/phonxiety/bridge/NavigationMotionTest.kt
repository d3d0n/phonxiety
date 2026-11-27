package com.phonxiety.bridge

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * The transition specs: real motion from the tokens when the platform allows it, an instant swap
 * when the owner asked for less motion, and a wizard preview that is never silent.
 */
class NavigationMotionTest {
    @Test
    fun aPushAndAPopAreDistinctTransitions() {
        val transitions = navTransitions(reduceMotion = false)

        assertNotSame(EnterTransition.None, transitions.enter.targetContentEnter)
        assertNotSame(ExitTransition.None, transitions.enter.initialContentExit)
        assertNotSame(EnterTransition.None, transitions.pop.targetContentEnter)
        assertNotSame(ExitTransition.None, transitions.pop.initialContentExit)
        assertNotEquals(transitions.enter.initialContentExit, transitions.pop.initialContentExit)
    }

    @Test
    fun thePredictivePopSettlesTheSceneUnderTheGesture() {
        val transitions = navTransitions(reduceMotion = false)

        assertNotSame(EnterTransition.None, transitions.predictivePop.targetContentEnter)
        assertNotSame(ExitTransition.None, transitions.predictivePop.initialContentExit)
    }

    @Test
    fun reducedMotionSwapsEverySceneAtOnce() {
        val transitions = navTransitions(reduceMotion = true)

        assertEquals(EnterTransition.None, transitions.enter.targetContentEnter)
        assertEquals(EnterTransition.None, transitions.pop.targetContentEnter)
        assertEquals(ExitTransition.None, transitions.pop.initialContentExit)
        assertEquals(ExitTransition.None, transitions.predictivePop.initialContentExit)
    }

    @Test
    fun theWizardPreviewIsNeverSilent() {
        val preview: ContentTransform = wizardPredictivePop()

        assertNotSame(EnterTransition.None, preview.targetContentEnter)
        assertNotSame(ExitTransition.None, preview.initialContentExit)
    }
}
