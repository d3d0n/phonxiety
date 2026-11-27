package com.phonxiety.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Startup timing: the journal's answer decides the first screen, the deadline decides it when the
 * answer is slow, and the splash is never held past the deadline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartDestinationTest {
    @Test
    fun aFinishedSetupStartsOnTheDashboard() = runTest {
        val controller = StartDestinationController()

        controller.resolve { true }

        assertEquals(DashboardKey, controller.start.value)
        assertFalse(controller.keepsSplash())
    }

    @Test
    fun anUnfinishedSetupStartsOnTheWizard() = runTest {
        val controller = StartDestinationController()

        controller.resolve { false }

        assertEquals(OnboardingKey(), controller.start.value)
        assertFalse(controller.keepsSplash())
    }

    @Test
    fun aDelayedAnswerStillDecidesTheStart() = runTest {
        val controller = StartDestinationController(deadlineMs = 400)

        launch {
            controller.resolve {
                delay(120)
                false
            }
        }

        advanceTimeBy(119)
        runCurrent()
        assertTrue(controller.keepsSplash())
        assertNull(controller.start.value)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(OnboardingKey(), controller.start.value)
    }

    @Test
    fun aSlowAnswerLosesToTheDeadlineAndTheDashboardRenders() = runTest {
        val controller = StartDestinationController(deadlineMs = 400)

        launch {
            controller.resolve {
                delay(10_000)
                false
            }
        }

        advanceTimeBy(399)
        runCurrent()
        assertTrue(controller.keepsSplash())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(DashboardKey, controller.start.value)
        assertFalse(controller.keepsSplash())
    }
}
