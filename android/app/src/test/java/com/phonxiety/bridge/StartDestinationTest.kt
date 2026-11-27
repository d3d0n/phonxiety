package com.phonxiety.bridge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Startup timing: the journal's answer decides the first screen, the deadline decides it when the
 * answer is slow or blocked, and the splash is never held past the deadline.
 *
 * The production failure this guards is a preference read that blocks its thread: the read must
 * run on a worker and lose to a deadline that runs on the caller's dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartDestinationTest {
    @Test
    fun aFinishedSetupStartsOnTheDashboard() = runTest {
        val controller = StartDestinationController(readDispatcher = Dispatchers.Unconfined)

        controller.resolve { true }

        assertEquals(DashboardKey, controller.start.value)
        assertFalse(controller.keepsSplash())
    }

    @Test
    fun anUnfinishedSetupStartsOnTheWizard() = runTest {
        val controller = StartDestinationController(readDispatcher = Dispatchers.Unconfined)

        controller.resolve { false }

        assertEquals(OnboardingKey(), controller.start.value)
        assertFalse(controller.keepsSplash())
    }

    @Test
    fun aFailingReadRendersTheDashboardInsteadOfHoldingTheSplash() = runTest {
        val controller = StartDestinationController(readDispatcher = Dispatchers.Unconfined)

        controller.resolve { throw IllegalStateException("the journal is unreadable") }

        assertEquals(DashboardKey, controller.start.value)
        assertFalse(controller.keepsSplash())
    }

    @Test
    fun aReadThatBlocksItsThreadCannotHoldTheDeadline() = runTest {
        val controller = StartDestinationController(deadlineMs = 400)
        val readerThread = AtomicReference<String>()

        controller.resolve {
            readerThread.set(Thread.currentThread().name)
            // The first preference load blocks its thread; a controller that shares the caller's
            // thread with the read would never reach its deadline.
            Thread.sleep(2_000)
            false
        }

        assertNotEquals(
            "the read ran on the thread that owns the deadline",
            Thread.currentThread().name,
            readerThread.get()
        )
        assertEquals(DashboardKey, controller.start.value)
        assertFalse(controller.keepsSplash())
    }

    @Test
    fun aBlockedReadLosesToTheDeadlineOnARealWorkerAndALateAnswerChangesNothing() = runTest {
        val controller = StartDestinationController(deadlineMs = 400)
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val readerThread = AtomicReference<String>()

        launch {
            controller.resolve {
                readerThread.set(Thread.currentThread().name)
                reading.countDown()
                release.await()
                finished.countDown()
                false // would start the wizard if it were allowed to win
            }
        }

        runCurrent()
        assertTrue("the read never started", reading.await(5, TimeUnit.SECONDS))
        assertNotEquals(
            "the read ran on the thread that owns the deadline",
            Thread.currentThread().name,
            readerThread.get()
        )
        assertTrue(controller.keepsSplash())

        advanceTimeBy(401)
        runCurrent()

        // The read is still blocked on its worker; the deadline published anyway.
        assertEquals(DashboardKey, controller.start.value)
        assertFalse(controller.keepsSplash())

        release.countDown()
        assertTrue("the reader never finished", finished.await(5, TimeUnit.SECONDS))
        advanceUntilIdle()

        assertEquals("a late answer flipped the start", DashboardKey, controller.start.value)
    }

    @Test
    fun aCallerCancelledBeforeTheReadStartsNeverRunsTheRead() = runTest {
        // The read worker has its own scheduler, so the read is queued but has not started when
        // the caller is cancelled — the state an activity destroyed during the splash leaves.
        val readScheduler = TestCoroutineScheduler()
        val controller = StartDestinationController(readDispatcher = StandardTestDispatcher(readScheduler))
        var readCalls = 0

        val caller = launch {
            controller.resolve {
                readCalls++
                true
            }
        }
        runCurrent()
        caller.cancel()
        caller.join()
        readScheduler.advanceUntilIdle()

        assertEquals("a read outlived its cancelled caller", 0, readCalls)
        assertEquals(null, controller.start.value)
    }
}
