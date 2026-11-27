package com.phonxiety.bridge.data

import com.phonxiety.bridge.model.CallEvent
import com.phonxiety.bridge.model.CallEventsPage
import com.phonxiety.bridge.model.CallId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
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
        advanceUntilIdle()

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
    fun aTruncatedPageIsCountedAndDoesNotDropTheCursor() = runTest {
        val daemon = ScriptedDaemon()
        val (feed, scope) = feed(daemon)
        val job = scope.launch { feed.state(callId).collect() }
        runCurrent()

        daemon.pages.send(ApiOutcome.Ok(200, page(2, emptyList(), truncated = true)))
        runCurrent()
        val truncated = feed.state(callId).value
        assertEquals(1, truncated.truncatedPages)
        assertTrue("a truncated page carries no events to apply", truncated.events.isEmpty())

        daemon.pages.send(ApiOutcome.Ok(200, page(3, listOf(endedEvent(3)))))
        runCurrent()
        assertEquals("the next request resumes from the truncated page's revision", listOf<Long?>(0L, 2L), daemon.requests)

        job.cancel()
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
