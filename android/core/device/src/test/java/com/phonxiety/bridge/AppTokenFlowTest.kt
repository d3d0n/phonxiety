package com.phonxiety.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The runtime's one app-token flow: it follows the engine installed now, re-emits null on detach,
 * and never keeps a retired engine's flow.
 */
class AppTokenFlowTest {
    @After
    fun tearDown() {
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
}
