package com.phonxiety.bridge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runtime's one app-token flow: it follows the engine installed now, re-emits null on detach,
 * and never keeps a retired engine's flow — not even an emission that was already inside its
 * callback when the replacement landed.
 *
 * The publisher side is a real thread blocked on a latch: it is released only after the
 * replacement, and every join must be observed to finish, so the assertions can never run before
 * the racing publication has actually happened.
 */
class AppTokenFlowTest {
    @After
    fun tearDown() {
        BridgeRuntime.beforeTokenPublishForTest = null
        BridgeRuntime.followAppToken(null)
    }

    @Test
    fun theFlowFollowsTheInstalledEngineAndDropsItsPredecessor() {
        val first = MutableStateFlow<String?>("token-a")
        BridgeRuntime.followAppToken(first)
        assertEquals("token-a", BridgeRuntime.appToken.value)

        first.value = "token-a2"
        assertEquals("the installed engine's own rotation is mirrored", "token-a2", BridgeRuntime.appToken.value)

        val second = MutableStateFlow<String?>("token-b")
        BridgeRuntime.followAppToken(second)
        assertEquals("a new engine re-emits its token", "token-b", BridgeRuntime.appToken.value)

        // The retired engine's flow no longer reaches a client.
        first.value = "token-a3"
        assertEquals("token-b", BridgeRuntime.appToken.value)

        BridgeRuntime.followAppToken(null)
        assertNull("detach re-emits null", BridgeRuntime.appToken.value)

        second.value = "token-b2"
        assertNull("a detached engine's flow is never followed again", BridgeRuntime.appToken.value)
    }

    /**
     * A collector that already read its emission and is inside the callback when the replacement
     * lands must not publish it afterwards: the retired engine's token can never overwrite the
     * new engine's token or a detach's null.
     */
    @Test
    fun anEmissionAlreadyInsideItsCallbackNeverOverwritesTheReplacement() {
        val first = MutableStateFlow<String?>("token-a")
        BridgeRuntime.followAppToken(first)
        assertEquals("token-a", BridgeRuntime.appToken.value)

        // The old collector is paused inside its callback, still holding the retired emission.
        val oldPaused = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        BridgeRuntime.beforeTokenPublishForTest = lockedPublication("token-a2", oldPaused, releaseOld)
        val oldFailure = AtomicReference<Throwable?>(null)
        val second = MutableStateFlow<String?>("token-b")
        val engine = thread(name = "engine-token") {
            try {
                first.value = "token-a2"
            } catch (error: Throwable) {
                oldFailure.set(error)
            }
        }
        try {
            assertTrue("the old collector is inside its callback", oldPaused.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS))
            BridgeRuntime.followAppToken(second)
            assertEquals("the new engine's token is published", "token-b", BridgeRuntime.appToken.value)
        } finally {
            releaseOld.countDown()
        }
        joinOrFail(engine)
        throwIfFailed(oldFailure, "the retired engine's publisher")
        BridgeRuntime.beforeTokenPublishForTest = null

        assertEquals("the retired emission never lands after the replacement", "token-b", BridgeRuntime.appToken.value)

        // The same for a detach: it re-emits null, and the retired emission stays out.
        val newPaused = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        BridgeRuntime.beforeTokenPublishForTest = lockedPublication("token-b2", newPaused, releaseNew)
        val newFailure = AtomicReference<Throwable?>(null)
        val engine2 = thread(name = "engine-token-2") {
            try {
                second.value = "token-b2"
            } catch (error: Throwable) {
                newFailure.set(error)
            }
        }
        try {
            assertTrue("the new collector is inside its callback", newPaused.await(HANG_GUARD_MS, TimeUnit.MILLISECONDS))
            BridgeRuntime.followAppToken(null)
            assertNull("detach re-emits null", BridgeRuntime.appToken.value)
        } finally {
            releaseNew.countDown()
        }
        joinOrFail(engine2)
        throwIfFailed(newFailure, "the detached engine's publisher")
        BridgeRuntime.beforeTokenPublishForTest = null

        assertNull("the detached engine's emission never lands after the detach", BridgeRuntime.appToken.value)
    }

    /**
     * A publication of [token] that blocks until [release]; every other publication is untouched.
     * The wait has no deadline: only the test releases it, so the race it sets up always happens.
     */
    private fun lockedPublication(token: String, paused: CountDownLatch, release: CountDownLatch): (String?) -> Unit = { published ->
        if (published == token) {
            paused.countDown()
            awaitUninterruptibly(release)
        }
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // The test releases it either way; keep the publisher blocked until then.
            }
        }
    }

    /** The publisher must be observed to finish; a hang fails the test instead of racing it. */
    private fun joinOrFail(worker: Thread) {
        worker.join(HANG_GUARD_MS)
        assertFalse("the publisher thread finished", worker.isAlive)
    }

    private fun throwIfFailed(failure: AtomicReference<Throwable?>, what: String) {
        failure.get()?.let { throw AssertionError("$what failed", it) }
    }

    private companion object {
        /** Only a hang guard: the latch, not the clock, decides when the publication runs. */
        const val HANG_GUARD_MS = 30_000L
    }
}
