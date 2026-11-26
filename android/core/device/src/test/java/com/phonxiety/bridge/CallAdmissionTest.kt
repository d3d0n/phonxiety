package com.phonxiety.bridge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The door between the link's idle-only actions and call reservation.  The
 * admission is the engine's call-ownership monitor: an action may start only
 * while no call is owned or being reserved, and a dial waits for an action
 * already in flight instead of reserving a call that the coming link-down
 * would abort.
 */
class CallAdmissionTest {
    @Test
    fun anActionIsRefusedWhileACallIsOwned() {
        val admission = CallAdmission()
        var owned = true

        assertNull(admission.tryBeginIdleLinkAction { owned })
    }

    @Test
    fun aReservedCallBlocksEveryIdleLinkAction() {
        val admission = CallAdmission()
        var owned = false
        val first = admission.tryBeginIdleLinkAction { owned }
        assertNotNull(first)
        // The dial reserves under the same monitor the action holds.
        synchronized(admission.lock) { owned = true }
        first!!.release()

        // A relay-only escalation or network recycle arriving now must not act:
        // the link-down it would report is exactly what would abort this call.
        assertNull(admission.tryBeginIdleLinkAction { owned })
    }

    @Test
    fun onlyOneIdleActionRunsAtATime() {
        val admission = CallAdmission()
        val action = admission.tryBeginIdleLinkAction { false }
        assertNotNull(action)
        assertNull(admission.tryBeginIdleLinkAction { false })

        action!!.release()
        val next = admission.tryBeginIdleLinkAction { false }
        assertNotNull(next)
        next!!.release()
        assertTrue(admission.awaitIdle(0))
    }

    @Test
    fun aDialWaitsForAnInFlightActionAndNeverReservesUnderIt() {
        val admission = CallAdmission()
        var owned = false
        val action = admission.tryBeginIdleLinkAction { owned }!!
        val aboutToWait = CountDownLatch(1)
        val reserved = AtomicBoolean(false)

        // What handleDial does: wait for the door, then reserve under the lock.
        val dial = Thread {
            aboutToWait.countDown()
            if (!admission.awaitIdle(5_000)) return@Thread
            synchronized(admission.lock) {
                if (admission.actionInFlightLocked()) return@Thread
                owned = true
                reserved.set(true)
            }
        }
        dial.start()
        assertTrue(aboutToWait.await(5, TimeUnit.SECONDS))
        dial.join(100)
        assertTrue(dial.isAlive)
        assertFalse(reserved.get())

        action.release()
        dial.join(5_000)
        assertTrue(reserved.get())

        // The call is safe: the escalation that would have aborted it is now
        // refused instead of closing the link under the reservation.
        assertNull(admission.tryBeginIdleLinkAction { owned })
    }

    @Test
    fun awaitIdleReportsTheActionStillInFlightAtTheDeadline() {
        val admission = CallAdmission()
        val action = admission.tryBeginIdleLinkAction { false }!!

        assertFalse(admission.awaitIdle(20))
        assertFalse(admission.awaitIdle(20)) // still held, not a one-shot wait

        action.release()
        assertTrue(admission.awaitIdle(20))
    }

    @Test
    fun releasingAnActionTwiceIsHarmless() {
        val admission = CallAdmission()
        val action = admission.tryBeginIdleLinkAction { false }!!

        action.release()
        action.release()
        action.close()

        assertTrue(admission.awaitIdle(0))
        val next = admission.tryBeginIdleLinkAction { false }
        assertNotNull(next)
        next!!.release()
    }

    @Test
    fun theActionSeesTheCallStateUnderTheMonitor() {
        val admission = CallAdmission()
        var owned = false
        var observedOwned = false

        // The engine's ownsCall() reads the owned call under the same lock.
        val action = admission.tryBeginIdleLinkAction {
            synchronized(admission.lock) {
                observedOwned = owned
                owned
            }
        }

        assertNotNull(action)
        assertFalse(observedOwned)
        action!!.release()
    }
}
