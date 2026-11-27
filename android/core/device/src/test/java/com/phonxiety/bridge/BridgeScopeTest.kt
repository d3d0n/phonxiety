package com.phonxiety.bridge

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A named long-lived scope reports what a fire-and-forget launch threw. */
class BridgeScopeTest {
    @Test
    fun aThrowingLaunchReachesTheSinkWithItsClass() = runBlocking {
        val seen = AtomicReference<Throwable?>(null)
        var area: String? = null
        val scope = bridgeScope("phonxiety-test", Dispatchers.Unconfined) { name, error ->
            area = name
            seen.set(error)
        }

        scope.launch { throw IllegalStateException("boom") }

        assertEquals("phonxiety-test", area)
        assertTrue(seen.get() is IllegalStateException)
    }
}
