package com.phonxiety.bridge.net

import com.phonxiety.bridge.CallAdmission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escalation's admission token outlives the escalation's own `fail()`.
 *
 * A relay-only escalation or a network recycle takes the engine's admission
 * token, then starts an action (endpoint shutdown) that can make a reader or
 * the closed watcher win the link's one failure publication first.  Releasing
 * the token when the losing `fail()` returns would let a dial reserve a call
 * in the window before the winning failure reaches the supervisor and the
 * engine — the call would be aborted by the very link-down being published.
 * The token therefore belongs to the transition: it is released once the
 * link-down callback has returned, and a finished link releases it at once
 * because no publication is left.
 */
class IrohLinkAdmissionTest {
    @Test
    fun aDialWaitsWhileTheWinningFailurePublishesTheEscalationsLinkDown() {
        val admission = CallAdmission()
        val gate = IrohLinkAdmission()
        val token = admission.tryBeginIdleLinkAction { false }!!
        // The escalation took the token; the recycle it started woke the closed
        // watcher, which won `downOnce` and is still inside the link-down callback.
        assertTrue(gate.attach(token))

        val aboutToWait = CountDownLatch(1)
        val reserved = AtomicBoolean(false)
        // What handleDial does: wait for the door, then reserve under the lock.
        val dial = Thread {
            aboutToWait.countDown()
            if (!admission.awaitIdle(5_000)) return@Thread
            synchronized(admission.lock) {
                if (admission.actionInFlightLocked()) return@Thread
                reserved.set(true)
            }
        }
        dial.start()
        assertTrue(aboutToWait.await(5, TimeUnit.SECONDS))
        dial.join(100)
        assertTrue("the dial must be waiting, not reserving", dial.isAlive)
        assertFalse("a call must never be reserved under a publishing link-down", reserved.get())

        // The winner reached the supervisor and the engine and returned; the
        // released token lets the same dial reserve now.
        gate.finish()
        dial.join(5_000)
        assertTrue(reserved.get())
    }

    @Test
    fun theLosingFailureDoesNotReleaseTheTokenAndTheDoorStaysShut() {
        val admission = CallAdmission()
        val gate = IrohLinkAdmission()
        val token = admission.tryBeginIdleLinkAction { false }!!
        assertTrue(gate.attach(token))

        // The escalation's own fail() lost the terminal transition: it returns
        // without touching the gate.  Nothing opens the door.
        assertFalse(admission.awaitIdle(20))
        assertNull(admission.tryBeginIdleLinkAction { false })

        gate.finish()
        assertTrue(admission.awaitIdle(0))
        val next = admission.tryBeginIdleLinkAction { false }
        assertTrue(next != null)
        next!!.release()
    }

    @Test
    fun aDialAfterThePublicationSeesTheLinkDownAndNeverReserves() {
        val admission = CallAdmission()
        val gate = IrohLinkAdmission()
        var linkUp = true
        val token = admission.tryBeginIdleLinkAction { false }!!
        assertTrue(gate.attach(token))

        // LinkSupervisor.handleDown drops the link under its lock before the
        // engine's callback runs; the transition then releases the token.
        synchronized(admission.lock) { linkUp = false }
        gate.finish()

        // handleDial's production order: door open, then the link check.
        assertTrue(admission.awaitIdle(0))
        var reserved = false
        synchronized(admission.lock) {
            if (!admission.actionInFlightLocked() && linkUp) reserved = true
        }
        assertFalse("a dial onto a link the failure dropped must refuse, not reserve", reserved)
    }

    @Test
    fun anEscalationThatFoundTheLinkFinishedEscalatesNothingAndKeepsTheDoorOpen() {
        val admission = CallAdmission()
        val gate = IrohLinkAdmission()
        gate.finish() // the link had already ended; no transition is left to carry a token

        var escalated = false
        val token = admission.tryBeginIdleLinkAction { false }!!
        if (gate.attach(token)) escalated = true else token.release()

        assertFalse(escalated)
        assertTrue(admission.awaitIdle(0))
        val next = admission.tryBeginIdleLinkAction { false }
        assertTrue(next != null)
        next!!.release()
    }

    @Test
    fun finishingTwiceReleasesTheTokenOnceAndEndsTheEscalation() {
        val admission = CallAdmission()
        val gate = IrohLinkAdmission()
        val token = admission.tryBeginIdleLinkAction { false }!!
        assertTrue(gate.attach(token))

        gate.finish()
        gate.finish()
        gate.finish()

        assertTrue(admission.awaitIdle(0))
        // A finished link refuses every later token; its caller releases it, so
        // the next escalation can still be admitted.
        val later = admission.tryBeginIdleLinkAction { false }!!
        assertFalse(gate.attach(later))
        later.release()
        assertTrue(admission.awaitIdle(0))
    }
}
