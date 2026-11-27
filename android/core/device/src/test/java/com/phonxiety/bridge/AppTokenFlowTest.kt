package com.phonxiety.bridge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runtime's one app-token flow: it follows the engine installed now, re-emits null on detach,
 * and never keeps a retired engine's flow — not even an emission that was already inside its
 * callback when the replacement landed.
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

        val pausedInside = CountDownLatch(1)
        val resume = CountDownLatch(1)
        BridgeRuntime.beforeTokenPublishForTest = { token ->
            if (token == "token-a2") {
                pausedInside.countDown()
                resume.await(5, TimeUnit.SECONDS)
            }
        }

        // The engine thread publishes its last token while a replacement lands on the app thread.
        val engine = thread(name = "engine-token") { first.value = "token-a2" }
        assertTrue("the old collector is inside its callback", pausedInside.await(5, TimeUnit.SECONDS))

        val second = MutableStateFlow<String?>("token-b")
        BridgeRuntime.followAppToken(second)
        assertEquals("the new engine's token is published", "token-b", BridgeRuntime.appToken.value)

        resume.countDown()
        engine.join(5_000)
        BridgeRuntime.beforeTokenPublishForTest = null

        assertEquals("the retired emission never lands after the replacement", "token-b", BridgeRuntime.appToken.value)

        // The same for a detach: it re-emits null, and the retired emission stays out.
        val pausedAgain = CountDownLatch(1)
        val resumeAgain = CountDownLatch(1)
        BridgeRuntime.beforeTokenPublishForTest = { token ->
            if (token == "token-b2") {
                pausedAgain.countDown()
                resumeAgain.await(5, TimeUnit.SECONDS)
            }
        }

        val engine2 = thread(name = "engine-token-2") { second.value = "token-b2" }
        assertTrue("the new collector is inside its callback", pausedAgain.await(5, TimeUnit.SECONDS))

        BridgeRuntime.followAppToken(null)
        assertNull("detach re-emits null", BridgeRuntime.appToken.value)

        resumeAgain.countDown()
        engine2.join(5_000)
        BridgeRuntime.beforeTokenPublishForTest = null

        assertNull("the detached engine's emission never lands after the detach", BridgeRuntime.appToken.value)
    }
}
