package com.phonxiety.bridge.net

import com.phonxiety.bridge.model.DeviceApiResult
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request path: a request issued while the link is reconnecting waits for
 * the hello instead of failing at once, the deadline covers the wait and the
 * answer, every entry leaves the table, and the inbound router completes a
 * pending request before anything else sees the frame.
 */
class RequestPathTest {
    private fun answer(id: String, status: Int = 200) = DeviceApiResult(id = id, status = status)

    @Test
    fun aRequestIssuedDuringAReconnectGoesOutOnceHelloIsQueued() = runTest {
        val table = ApiRequestTable()
        val sent = mutableListOf<String>()
        val exchange = ApiExchange(
            table,
            LinkReadiness(),
            { 0L },
            { wire ->
                sent += wire
                true
            }
        )
        var result: DeviceApiResult? = null

        val job = launch { result = exchange.exchange("r1", "wire-r1", 30_000) }
        runCurrent()
        assertTrue("nothing is sent before the link is usable", sent.isEmpty())
        assertEquals(1, table.size())

        // A transport opens first; only the queued hello makes the link usable.
        exchange.transportOpened()
        runCurrent()
        assertTrue("an open transport without a hello is not readiness", sent.isEmpty())

        exchange.helloQueued()
        runCurrent()
        assertEquals(listOf("wire-r1"), sent)

        table.complete(answer("r1"))
        job.join()
        assertEquals(200, result?.status)
        assertEquals(0, table.size())
    }

    @Test
    fun aLinkLossWhileWaitingKeepsTheRequestForTheNextLink() = runTest {
        val table = ApiRequestTable()
        val sent = mutableListOf<String>()
        val exchange = ApiExchange(
            table,
            LinkReadiness(),
            { 0L },
            { wire ->
                sent += wire
                true
            }
        )
        var result: DeviceApiResult? = null

        val job = launch { result = exchange.exchange("r1", "wire-r1", 30_000) }
        runCurrent()
        exchange.linkDown()
        runCurrent()
        assertTrue(sent.isEmpty())

        exchange.helloQueued()
        runCurrent()
        assertEquals(listOf("wire-r1"), sent)
        table.complete(answer("r1"))
        job.join()
        assertEquals(200, result?.status)
    }

    @Test
    fun aRequestThatNeverSeesAUsableLinkFailsWithTheTypedTimeout() = runTest {
        val table = ApiRequestTable()
        val sent = mutableListOf<String>()
        val exchange = ApiExchange(
            table,
            LinkReadiness(),
            { 0L },
            { wire ->
                sent += wire
                true
            }
        )

        val result = exchange.exchange("r1", "wire-r1", 5_000)

        assertEquals(504, result.status)
        assertEquals("timeout", result.error?.code)
        assertTrue(sent.isEmpty())
        assertEquals("a timeout leaves no entry behind", 0, table.size())
    }

    @Test
    fun aCancelledRequestLeavesNoEntryBehind() = runTest {
        val table = ApiRequestTable()
        val exchange = ApiExchange(table, LinkReadiness(), { 0L }, { true })

        val job = launch { exchange.exchange("r1", "wire-r1", 30_000) }
        runCurrent()
        assertEquals(1, table.size())

        job.cancelAndJoin()
        assertEquals(0, table.size())
    }

    @Test
    fun failAllCompletesEveryPendingRequestWithItsReason() = runTest {
        val table = ApiRequestTable()
        val waiting = table.post("waiting", 0L)
        val sent = table.post("sent", 0L)
        assertNotNull(waiting)
        assertNotNull(sent)
        sent!!.markSent()
        runCurrent()

        val completed = table.failAll(RequestFailure.ShuttingDown, includeWaiting = true)

        assertEquals(2, completed)
        assertEquals(0, table.size())
        val first = waiting!!.answer.await()
        assertEquals(503, first.status)
        assertEquals("link_down", first.error?.code)
        assertEquals("the bridge is shutting down", first.error?.message)
    }

    @Test
    fun aLinkLossFailsOnlyRequestsThatAlreadyLeft() = runTest {
        val table = ApiRequestTable()
        val waiting = table.post("waiting", 0L)!!
        val sent = table.post("sent", 0L)!!
        sent.markSent()

        val completed = table.failAll(RequestFailure.LinkDown, includeWaiting = false)

        assertEquals(1, completed)
        assertEquals(1, table.size())
        assertFalse(waiting.answer.isCompleted)
        assertEquals("link_down", sent.answer.await().error?.code)
    }

    @Test
    fun aPairingThatMovesWhileWaitingCompletesTheRequestAndNeverSendsIt() = runTest {
        val table = ApiRequestTable()
        val sent = mutableListOf<String>()
        var epoch = 0L
        val exchange = ApiExchange(
            table,
            LinkReadiness(),
            { epoch },
            { wire ->
                sent += wire
                true
            }
        )
        var result: DeviceApiResult? = null

        val job = launch { result = exchange.exchange("r1", "wire-r1", 30_000) }
        runCurrent()

        // The pairing moves: the old gateway stops answering at once.
        epoch = 1L
        exchange.stop(RequestFailure.PairingChanged)
        job.join()

        assertEquals("pairing_changed", result?.error?.code)
        assertTrue("the request never reaches the new pairing's link", sent.isEmpty())
        assertEquals(0, table.size())
    }

    @Test
    fun anAnswerThatOutlivesItsPairingIsRefused() = runTest {
        val table = ApiRequestTable()
        var epoch = 0L
        val exchange = ApiExchange(table, LinkReadiness(), { epoch }, { true })
        var result: DeviceApiResult? = null
        exchange.helloQueued()

        val job = launch { result = exchange.exchange("r1", "wire-r1", 30_000) }
        runCurrent()

        epoch = 1L
        table.complete(answer("r1", 200))
        job.join()

        assertEquals("pairing_changed", result?.error?.code)
        assertEquals(0, table.size())
    }

    @Test
    fun theRouterCompletesThePendingRequestBeforeDispatch() = runTest {
        val table = ApiRequestTable()
        val entry = table.post("r1", 0L)!!
        val order = mutableListOf<String>()
        val router = InboundRouter(
            requests = table,
            dispatch = { type, _ -> order += "dispatch:$type:${table.size()}" },
            log = { order += "log:$it" }
        )

        router.route("""{"type":"api_result","id":"r1","status":200}""")

        assertEquals(listOf("dispatch:api_result:0"), order)
        assertEquals(200, entry.answer.await().status)
        assertEquals(0, table.size())
    }

    @Test
    fun anUnknownApiResultIsLoggedAndLeavesNoStateBehind() = runTest {
        val table = ApiRequestTable()
        val other = table.post("other", 0L)!!
        val order = mutableListOf<String>()
        val router = InboundRouter(
            requests = table,
            dispatch = { type, _ -> order += "dispatch:$type" },
            log = { order += "log:$it" }
        )

        router.route("""{"type":"api_result","id":"unknown-request","status":200}""")

        assertEquals(listOf("log:dropped an api_result for an unknown request unknown-request"), order)
        assertEquals("the other request is untouched", 1, table.size())
        assertFalse(other.answer.isCompleted)
    }

    @Test
    fun aCommandStillReachesDispatch() = runTest {
        val order = mutableListOf<String>()
        val router = InboundRouter(
            requests = ApiRequestTable(),
            dispatch = { type, text -> order += "$type:$text" },
            log = { order += "log:$it" }
        )

        router.route("""{"type":"command","id":"c1"}""")

        assertEquals(listOf("""command:{"type":"command","id":"c1"}"""), order)
    }

    @Test
    fun aFailedHelloAnswersTheWaitingRequestWithItsTypedReason() = runTest {
        val table = ApiRequestTable()
        val sent = mutableListOf<String>()
        val exchange = ApiExchange(table, LinkReadiness(), { 0L }, { wire ->
            sent += wire
            true
        })
        var result: DeviceApiResult? = null

        val job = launch { result = exchange.exchange("r1", "wire-r1", 30_000) }
        runCurrent()
        assertEquals(1, table.size())

        exchange.transportOpened()
        runCurrent()
        exchange.helloFailed()
        runCurrent()

        job.join()
        assertEquals("the waiting request is answered instead of waiting out its deadline", 503, result?.status)
        assertEquals("hello_failed", result?.error?.code)
        assertTrue("nothing was sent on a link whose handshake failed", sent.isEmpty())
        assertEquals(0, table.size())

        // The failure is not terminal: the next connection carries requests exactly as before.
        exchange.transportOpened()
        exchange.helloQueued()
        var next: DeviceApiResult? = null
        val followUp = launch { next = exchange.exchange("r2", "wire-r2", 30_000) }
        runCurrent()
        assertEquals(listOf("wire-r2"), sent)
        table.complete(answer("r2"))
        followUp.join()
        assertEquals(200, next?.status)
    }

    @Test
    fun aTransportOpeningAsTheHelloFailsDoesNotStealTheAnswer() = runTest {
        val table = ApiRequestTable()
        val exchange = ApiExchange(table, LinkReadiness(), { 0L }, { true })
        var result: DeviceApiResult? = null

        val job = launch { result = exchange.exchange("r1", "wire-r1", 30_000) }
        runCurrent()
        exchange.transportOpened()
        runCurrent()

        // The failed attempt's answer and the next transport's open land together: the request
        // that was waiting on the failed hello still gets its typed reason, not a fresh wait.
        exchange.helloFailed()
        exchange.transportOpened()
        runCurrent()

        job.join()
        assertEquals("hello_failed", result?.error?.code)
        assertEquals(0, table.size())
    }
}
