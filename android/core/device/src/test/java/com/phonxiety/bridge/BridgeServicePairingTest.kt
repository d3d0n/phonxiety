package com.phonxiety.bridge

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A service start is also the moment a host-written pairing is picked up, and a
 * re-pair may run at the same time.  The adoption is one transaction: a start
 * that has to wait behind a re-pair adopts the pairing that was published, never
 * the one it would have read before the wait.
 */
class BridgeServicePairingTest {
    private val pinA = "a".repeat(64)
    private val pinB = "b".repeat(64)

    private fun pairing(pin: String, token: String): PairingRead = PairingRead(
        config = BridgeConfig.validateAddress("wss://example.invalid:18763/device", token, pin),
        addresses = PairingAddresses()
    )

    private class Fakes(initial: PairingRead?) {
        var stored: PairingRead? = initial
        var expected: String? = null
        var installed: String? = null
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var installs = 0
        var replaces = 0
        var forgotten = 0

        /** The next [read] is slow: it reports to [readStarted] and waits on [readRelease]. */
        var readStarted = CountDownLatch(0)
        var readRelease = CountDownLatch(0)
        var blockNextRead = false

        val adoption = EngineAdoption(
            read = {
                val value = stored
                if (blockNextRead) {
                    blockNextRead = false
                    readStarted.countDown()
                    readRelease.await(5, TimeUnit.SECONDS)
                }
                value
            },
            runningIdentity = { installed },
            expect = { identity ->
                expected = identity
                log += "expect:$identity"
            },
            forget = {
                forgotten += 1
                expected = null
                installed = null
                log += "forget"
            },
            replace = {
                // The service's replacement closes a running engine; with none,
                // there is nothing to close.
                if (installed != null) {
                    replaces += 1
                    installed = null
                    log += "replace"
                }
            },
            install = { pairing, identity ->
                installs += 1
                installed = identity
                stored = pairing
                log += "install:$identity"
            }
        )
    }

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

    @Test
    fun anUnreadableStoreForgetsThePairingAndStartsNothing() {
        val fakes = Fakes(null)
        fakes.expected = "cert:$pinA"
        fakes.installed = "cert:$pinA"

        assertFalse(fakes.adoption.adopt())

        assertTrue("no engine is installed from no pairing", fakes.installations().isEmpty())
        assertEquals(1, fakes.forgotten)
    }

    @Test
    fun aStartAdoptsTheStoredPairingOnce() {
        val fakes = Fakes(pairing(pinA, "token-a"))

        assertTrue(fakes.adoption.adopt())
        assertEquals("cert:$pinA", fakes.expected)
        assertEquals(1, fakes.installs)

        // A second start for the same pairing keeps the engine.
        assertTrue(fakes.adoption.adopt())
        assertEquals(1, fakes.installs)
        assertEquals(0, fakes.replaces)
    }

    @Test
    fun aRepairClosesThePreviousEngineBeforeInstallingTheNewOne() {
        val fakes = Fakes(pairing(pinA, "token-a"))
        fakes.adoption.adopt()

        fakes.stored = pairing(pinB, "token-b")
        fakes.adoption.adopt()

        assertEquals("cert:$pinB", fakes.installed)
        assertEquals(
            listOf("install:cert:$pinA", "replace", "install:cert:$pinB"),
            fakes.log.filterNot { it.startsWith("expect:") }
        )
    }

    @Test
    fun aStartThatReadTheOldPairingCannotPublishItAfterARepair() {
        val fakes = Fakes(pairing(pinA, "token-a"))
        fakes.adoption.adopt()

        // The next read is slow: it holds A, then waits.  Under the transaction
        // that read also holds the adoption, so the re-pair cannot pass it.
        fakes.readStarted = CountDownLatch(1)
        fakes.readRelease = CountDownLatch(1)
        fakes.blockNextRead = true
        val start = Thread { fakes.adoption.adopt() }
        start.start()
        assertTrue(fakes.readStarted.await(5, TimeUnit.SECONDS))

        val published = CountDownLatch(1)
        val publish = Thread {
            BridgeRuntime.pairingTransaction {
                fakes.stored = pairing(pinB, "token-b")
                fakes.expected = "cert:$pinB"
                // The service start that follows the publication.
                fakes.adoption.adopt()
            }
            published.countDown()
        }
        publish.start()
        assertFalse("the publication waits behind the adoption", published.await(100, TimeUnit.MILLISECONDS))

        fakes.readRelease.countDown()
        start.join(5_000)
        publish.join(5_000)
        assertFalse(publish.isAlive)

        assertEquals("the pairing published last is the pairing kept", "cert:$pinB", fakes.installed)
        assertEquals("expect:cert:$pinB", fakes.log.last { it.startsWith("expect:") })
    }

    private fun Fakes.installations(): List<String> = log.filter { it.startsWith("install:") }
}
