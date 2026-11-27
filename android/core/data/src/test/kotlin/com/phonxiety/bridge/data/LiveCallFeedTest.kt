package com.phonxiety.bridge.data

import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.model.CallEvent
import com.phonxiety.bridge.model.CallEventsPage
import com.phonxiety.bridge.model.CallId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * One feed per call: the request stream, the cursor and the dedupe belong to the feed, so every
 * screen of the same call reads the same stream and a rejoin continues instead of restarting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveCallFeedTest {
    private val callId = CallId("call-1")

    /** The feed is not a child of the test: it owns its loop, and the test cancels it here. */
    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var previousSink: BridgeLog.Sink

    @Before
    fun setUp() {
        previousSink = BridgeLog.sink
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
        BridgeLog.sink = previousSink
    }

    /** The feed and its collectors on the test clock, so delays are virtual and ordering is fixed. */
    private fun TestScope.feed(daemon: ScriptedDaemon): Pair<LiveCallFeed, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        scopes += scope
        return LiveCallFeed(daemon.source, scope) to scope
    }

    /** The daemon under the test: each request takes the next page the test sent. */
    private class ScriptedDaemon {
        val requests = mutableListOf<Long?>()
        val pages = Channel<ApiOutcome<CallEventsPage>>(Channel.UNLIMITED)

        val source = CallEventsSource { _, since, _ ->
            requests += since
            pages.receive()
        }
    }

    @Test
    fun twoCollectorsShareOneRequestStream() = runTest {
        val daemon = ScriptedDaemon()
        val (feed, scope) = feed(daemon)
        val seen = mutableListOf<LiveCallFeedState>()
        val first = scope.launch { feed.state(callId).collect { seen += it } }
        val second = scope.launch { feed.state(callId).collect { seen += it } }
        runCurrent()

        assertEquals("the call's stream is opened once, not once per collector", 1, daemon.requests.size)

        daemon.pages.send(ApiOutcome.Ok(200, page(1, listOf(stateEvent(1, "active")))))
        runCurrent()

        assertTrue("both collectors saw the same page", seen.count { it.events.isNotEmpty() } >= 2)
        assertEquals("the one stream already waits for the next page", listOf<Long?>(0L, 1L), daemon.requests)

        daemon.pages.send(ApiOutcome.Ok(200, page(2, listOf(endedEvent(2)))))
        runCurrent()
        assertEquals(listOf<Long?>(0L, 1L), daemon.requests)
        assertTrue(feed.state(callId).value.ended)

        first.cancel()
        second.cancel()
    }

    @Test
    fun theLastCollectorLeavingStopsTheLoop() = runTest {
        val daemon = ScriptedDaemon()
        val (feed, scope) = feed(daemon)
        val only = scope.launch { feed.state(callId).collect() }
        runCurrent()
        daemon.pages.send(ApiOutcome.Ok(200, page(3, listOf(stateEvent(3, "active")))))
        runCurrent()
        val whileWatched = daemon.requests.size
        assertEquals("one page applied, the next request already waiting", 2, whileWatched)

        only.cancel()
        runCurrent()

        daemon.pages.send(ApiOutcome.Ok(200, page(4, listOf(endedEvent(4)))))
        runCurrent()

        assertEquals("nothing asks the daemon after the last collector leaves", whileWatched, daemon.requests.size)
        assertFalse("the terminal page that arrived for nobody was never read", feed.state(callId).value.ended)
        assertFalse("and the loop is not left running", feed.isPolling(callId))
    }

    @Test
    fun aCollectorWhoComesBackResumesFromTheCursor() = runTest {
        val daemon = ScriptedDaemon()
        val (feed, scope) = feed(daemon)
        val first = scope.launch { feed.state(callId).collect() }
        runCurrent()
        daemon.pages.send(ApiOutcome.Ok(200, page(5, listOf(transcriptEvent(5, 1)))))
        runCurrent()
        assertEquals("the watched stream waits from the accepted revision", listOf<Long?>(0L, 5L), daemon.requests)
        first.cancel()
        // The loop stops on its own; the idle bound is far away, so the cursor is still here.
        runCurrent()

        val again = scope.launch { feed.state(callId).collect() }
        runCurrent()

        assertEquals("the rejoin asks from the last accepted revision", listOf<Long?>(0L, 5L, 5L), daemon.requests)
        assertEquals("and the accumulated events are still here", listOf(5L), feed.state(callId).value.events.map { it.revision })

        again.cancel()
    }

    @Test
    fun aReconnectResumesWithoutDuplicatesOrGaps() = runTest {
        val daemon = ScriptedDaemon()
        val (feed, scope) = feed(daemon)
        val job = scope.launch { feed.state(callId).collect() }
        runCurrent()

        // The link drops before anything arrived: the retry is the same cursor.
        daemon.pages.send(ApiOutcome.Failure(503, "link_down", "the bridge service is not running"))
        runCurrent()
        assertTrue(feed.state(callId).value.reconnecting)
        advanceTimeBy(1_000)
        runCurrent()

        daemon.pages.send(
            ApiOutcome.Ok(
                200,
                page(5, listOf(transcriptEvent(4, 1), transcriptEvent(5, 2)))
            )
        )
        runCurrent()

        // The daemon repeats the whole page: a revision already applied is not new.
        daemon.pages.send(ApiOutcome.Ok(200, page(5, listOf(transcriptEvent(4, 1), transcriptEvent(5, 2)))))
        runCurrent()

        // A re-sent turn under a new revision is still the same turn.
        daemon.pages.send(ApiOutcome.Ok(200, page(6, listOf(transcriptEvent(6, 2)))))
        runCurrent()

        daemon.pages.send(ApiOutcome.Ok(200, page(7, listOf(endedEvent(7)))))
        runCurrent()

        assertEquals(
            "the failed read retried the same cursor; the duplicates asked from the last revision",
            listOf<Long?>(0L, 0L, 5L, 5L, 6L),
            daemon.requests
        )
        val state = feed.state(callId).value
        assertEquals(listOf(4L, 5L, 7L), state.events.map { it.revision })
        assertEquals("the repeated turn was drawn once", 2, state.events.count { it.type == "transcript" })
        assertTrue(state.ended)
        assertFalse(state.reconnecting)

        job.cancel()
    }

    @Test
    fun aTerminalPageEndsTheFeed() = runTest {
        val daemon = ScriptedDaemon()
        val (feed, scope) = feed(daemon)
        val job = scope.launch { feed.state(callId).collect() }
        runCurrent()

        daemon.pages.send(ApiOutcome.Ok(200, page(3, listOf(endedEvent(3)))))
        runCurrent()
        assertTrue(feed.state(callId).value.ended)
        assertEquals(1, daemon.requests.size)

        // A second collector joined while the first is still there: the stream is not reopened.
        val second = scope.launch { feed.state(callId).collect() }
        advanceUntilIdle()
        assertEquals(1, daemon.requests.size)

        job.cancel()
        second.cancel()
        advanceUntilIdle()

        // Everyone left and a screen comes back: the finished feed is still finished.
        val back = scope.launch { feed.state(callId).collect() }
        advanceUntilIdle()
        assertEquals("a finished call is not polled again", 1, daemon.requests.size)
        assertTrue(feed.state(callId).value.ended)
        back.cancel()
    }

    @Test
    fun aTruncatedPageCarriesItsBoundaryAndDoesNotDropTheCursor() = runTest {
        val daemon = ScriptedDaemon()
        val (feed, scope) = feed(daemon)
        val job = scope.launch { feed.state(callId).collect() }
        runCurrent()

        daemon.pages.send(ApiOutcome.Ok(200, page(2, emptyList(), truncated = true)))
        runCurrent()
        val truncated = feed.state(callId).value
        assertEquals("the page that dropped events is the boundary a screen must re-read from", 2L, truncated.truncationRevision)
        assertTrue("a truncated page carries no events to apply", truncated.events.isEmpty())

        daemon.pages.send(ApiOutcome.Ok(200, page(3, listOf(endedEvent(3)))))
        runCurrent()
        assertEquals("the next request resumes from the truncated page's revision", listOf<Long?>(0L, 2L), daemon.requests)

        job.cancel()
    }

    /**
     * A cancellation can land after a page is accepted and before it is published. The accepted
     * page is the call's, not the collector's: a screen that comes back sees it without asking the
     * daemon again.
     */
    @Test
    fun anAcceptedTerminalPageIsSeenWhenACollectorComesBack() = runTest {
        val daemon = ScriptedDaemon()
        val (feed, scope) = feed(daemon)
        val accepted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        feed.afterAccept = {
            accepted.complete(Unit)
            release.await()
        }
        val first = scope.launch { feed.state(callId).collect() }
        runCurrent()

        daemon.pages.send(ApiOutcome.Ok(200, page(3, listOf(endedEvent(3)))))
        runCurrent()
        withTimeout(1_000) { accepted.await() }
        assertFalse("the publish has not happened yet", feed.state(callId).value.ended)

        first.cancel()
        runCurrent()
        release.complete(Unit)
        runCurrent()

        val back = scope.launch { feed.state(callId).collect() }
        runCurrent()
        assertTrue("the accepted terminal page survives the cancelled publish", feed.state(callId).value.ended)
        assertEquals("and it is not fetched again", 1, daemon.requests.size)
        back.cancel()
    }

    /**
     * A call nobody watches is not kept forever: inside the idle bound it still holds its cursor,
     * and past it the entry is gone.
     */
    @Test
    fun aCallNobodyWatchesIsForgottenAfterTheIdleBound() = runTest {
        val daemon = ScriptedDaemon()
        val (feed, scope) = feed(daemon)
        val only = scope.launch { feed.state(callId).collect() }
        runCurrent()
        daemon.pages.send(ApiOutcome.Ok(200, page(4, listOf(transcriptEvent(4, 1)))))
        runCurrent()
        val watched = feed.state(callId)

        only.cancel()
        runCurrent()
        assertFalse("the last leave stops the loop", feed.isPolling(callId))

        advanceTimeBy(ABANDONED_CALL_IDLE_MS - 1_000)
        runCurrent()
        assertTrue("inside the idle bound the call keeps its cursor", feed.isRetained(callId))
        assertSame(watched, feed.state(callId))

        advanceTimeBy(2_000)
        runCurrent()
        assertFalse("past the idle bound the abandoned call is forgotten", feed.isRetained(callId))
        assertNotSame("and the next screen gets a fresh stream", watched, feed.state(callId))
    }

    /** A call whose loop threw still retries with the same backoff, and its failure is logged. */
    @Test
    fun anUnexpectedFailureIsSurfacedAndRetried() = runTest {
        val logged = mutableListOf<String>()
        BridgeLog.sink = BridgeLog.Sink { _, tag, message, _ -> logged += "$tag: $message" }
        var attempts = 0
        val source = CallEventsSource { _, _, _ ->
            attempts++
            if (attempts == 1) throw IllegalStateException("the socket went away")
            ApiOutcome.Ok(200, page(2, listOf(endedEvent(2))))
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        scopes += scope
        val feed = LiveCallFeed(source, scope)

        val job = scope.launch { feed.state(callId).collect() }
        runCurrent()
        assertTrue("the failure is surfaced as a reconnect", feed.state(callId).value.reconnecting)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("the next attempt is the retry", 2, attempts)
        assertTrue(feed.state(callId).value.ended)
        assertTrue("the exception is logged", logged.any { it.startsWith("LiveCallFeed:") })
        job.cancel()
    }

    /** One call's exception must not reach the uncaught handler or stop another call's loop. */
    @Test
    fun oneCallsFailureDoesNotStopAnotherCallsLoop() = runTest {
        val boom = CallId("call-boom")
        val calm = CallId("call-calm")
        val calmPages = Channel<ApiOutcome<CallEventsPage>>(Channel.UNLIMITED)
        var attempts = 0
        val source = CallEventsSource { callId, _, _ ->
            if (callId == boom) {
                attempts++
                if (attempts == 1) throw IllegalStateException("boom")
                ApiOutcome.Ok(200, page(2, listOf(endedEvent(2))))
            } else {
                calmPages.receive()
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        scopes += scope
        val feed = LiveCallFeed(source, scope)

        val calmJob = scope.launch { feed.state(calm).collect() }
        val boomJob = scope.launch { feed.state(boom).collect() }
        runCurrent()
        assertTrue("the throwing call reports a reconnect", feed.state(boom).value.reconnecting)

        calmPages.send(ApiOutcome.Ok(200, page(1, listOf(stateEvent(1, "active")))))
        runCurrent()
        assertEquals("the other call keeps its own stream", "active", feed.state(calm).value.state)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("the throwing call recovered on the next attempt", 2, attempts)
        assertTrue(feed.state(boom).value.ended)
        assertEquals("the calm call was never disturbed", "active", feed.state(calm).value.state)

        calmJob.cancel()
        boomJob.cancel()
    }

    /** A call with a collector is never forgotten, however long nothing happens. */
    @Test
    fun aWatchedCallIsNeverForgotten() = runTest {
        val daemon = ScriptedDaemon()
        val (feed, scope) = feed(daemon)
        val only = scope.launch { feed.state(callId).collect() }
        runCurrent()
        val watched = feed.state(callId)

        advanceTimeBy(ABANDONED_CALL_IDLE_MS * 3)
        runCurrent()

        assertTrue("a collected call keeps its entry", feed.isRetained(callId))
        assertSame(watched, feed.state(callId))
        assertTrue("and keeps polling", feed.isPolling(callId))
        only.cancel()
    }

    /** Finished calls are bounded: only the last few are kept for a screen that returns. */
    @Test
    fun finishedCallsAreKeptInABoundedSet() = runTest {
        val daemon = ScriptedDaemon()
        val (feed, scope) = feed(daemon)
        val ids = (1..REMEMBERED_TERMINAL_CALLS + 1).map { CallId("call-$it") }
        val views = ids.map { feed.state(it) }
        val collectors = ids.map { id -> scope.launch { feed.state(id).collect() } }
        runCurrent()
        assertEquals(ids.size, daemon.requests.size)

        ids.forEach { daemon.pages.send(ApiOutcome.Ok(200, page(3, listOf(endedEvent(3))))) }
        runCurrent()
        views.forEach { assertTrue("every call saw its terminal page", it.value.ended) }
        collectors.forEach { it.cancel() }
        runCurrent()

        assertEquals(
            "only a few finished calls are kept",
            REMEMBERED_TERMINAL_CALLS,
            feed.retainedTerminalCalls()
        )
        assertEquals(
            "and only those keep an entry",
            REMEMBERED_TERMINAL_CALLS,
            feed.retainedCalls()
        )
        assertFalse("the least recently touched finished call was evicted", feed.isRetained(ids.first()))
    }

    /**
     * Two collectors attaching at the same moment on different threads still share one request
     * stream: the call, not the collector, owns the loop.
     */
    @Test
    fun collectorsThatAttachTogetherShareOneStream() = runBlocking {
        val requests = CopyOnWriteArrayList<Long?>()
        val firstRequest = CompletableDeferred<Unit>()
        val secondRequest = CompletableDeferred<Unit>()
        val source = CallEventsSource { _, since, _ ->
            requests += since
            firstRequest.complete(Unit)
            if (requests.size > 1) secondRequest.complete(Unit)
            awaitCancellation()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val feed = LiveCallFeed(source, scope)
            val barrier = CyclicBarrier(3)
            repeat(2) {
                scope.launch {
                    barrier.await()
                    feed.state(callId).collect()
                }
            }
            barrier.await()
            withTimeout(5_000) { firstRequest.await() }
            withTimeoutOrNull(1_000) { secondRequest.await() }
            assertFalse("a second loop also asked the daemon", secondRequest.isCompleted)
            assertEquals("one request stream serves both collectors", 1, requests.size)
        } finally {
            scope.cancel()
        }
    }

    private fun page(revision: Long, events: List<CallEvent>, truncated: Boolean = false): CallEventsPage = CallEventsPage(
        callId = callId.value,
        revision = revision,
        state = events.lastOrNull()?.state,
        events = events,
        truncated = truncated
    )

    private fun stateEvent(revision: Long, state: String): CallEvent = CallEvent(type = "state", revision = revision, state = state)

    private fun transcriptEvent(revision: Long, sequence: Long): CallEvent = CallEvent(
        type = "transcript",
        revision = revision,
        role = "agent",
        text = "turn $sequence",
        sequence = sequence,
        finalTurn = true
    )

    private fun endedEvent(revision: Long): CallEvent = CallEvent(type = "ended", revision = revision, state = "ended")
}
