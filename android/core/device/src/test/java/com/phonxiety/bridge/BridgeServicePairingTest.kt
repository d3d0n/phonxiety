package com.phonxiety.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A service start is also the moment a host-written pairing is picked up: the
 * engine is reused only for the pairing it was built for.
 */
class BridgeServicePairingTest {
    @Test
    fun aStoredPairingChangeRebuildsTheEngineInsteadOfReusingIt() {
        assertTrue(mustBuildEngine("cert:a", "cert:b"))
        assertTrue(mustBuildEngine("url:wss://a/device", "iroh:ticket-b"))
        assertTrue(mustBuildEngine(null, "cert:a"))
    }

    @Test
    fun theSamePairingKeepsTheRunningEngine() {
        assertFalse(mustBuildEngine("cert:a", "cert:a"))
    }
}
