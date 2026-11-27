package com.phonxiety.bridge.data

import com.phonxiety.bridge.model.CallEvent
import com.phonxiety.bridge.model.CallEventsPage
import com.phonxiety.bridge.model.CallId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive

/**
 * One call's `events` long-poll, as a stream. The caller names a call, not a cursor: the feed owns
 * `since`, the revision filter and the reconnect delay, so every screen of the same call reads the
 * same request stream and none of them can resume from a different place.
 */
fun interface CallEventsSource {
    suspend fun page(callId: CallId, since: Long?, waitSeconds: Double?): ApiOutcome<CallEventsPage>
}

/** The daemon's cursor rule: only events past [since] are new, and an event without a revision (decoded as 0) is never applied. */
fun freshEvents(events: List<CallEvent>, since: Long): List<CallEvent> = events.filter { it.revision > since }

/** A daemon call state that ends the call. */
private val TERMINAL_CALL_STATES = setOf("ended", "cancelled", "failed", "unknown")

/** The daemon's own word for a call that is over; the feed stops polling it. */
fun callHasEnded(state: String?): Boolean = state in TERMINAL_CALL_STATES

/** Everything one call's event stream has accumulated so far. */
data class LiveCallFeedState(
    val callId: String,
    /** The last revision the feed accepted; the next request resumes from here. */
    val revision: Long = 0,
    /** Every event accepted since the stream started, in the order the daemon delivered them. */
    val events: List<CallEvent> = emptyList(),
    val state: String? = null,
    val callOutcome: String? = null,
    val resultStatus: String? = null,
    /**
     * Pages that arrived without the events between the previous cursor and theirs. The feed
     * carries only the count: the record, not the stream, is the complete copy a screen must read.
     */
    val truncatedPages: Int = 0,
    /** The last attempt failed and another follows. */
    val reconnecting: Boolean = false,
    /** The daemon called the call over; the stream is finished. */
    val ended: Boolean = false
)

/**
 * The live-call events of each call, shared by every screen that shows that call: the live call
 * and the test lab's test call.
 *
 * The feed is a singleton because the request belongs to the call, not to a screen. It is started
 * by the first collector of a call and stopped when the last one leaves
 * ([SharingStarted.WhileSubscribed] with a zero stop timeout; the accumulated value is kept after
 * the stop, so a screen that comes back resumes from the cursor instead of re-reading the call).
 * A terminal page finishes the loop; a truncated page is counted so the subscribing screen can
 * re-read the record, because the stream no longer has the events it missed.
 */
@Singleton
class LiveCallFeed constructor(
    private val source: CallEventsSource,
    /** The clock the long-poll runs on; production keeps its own, tests pass their own. */
    private val scope: CoroutineScope
) {
    @Inject
    constructor(source: CallEventsSource) : this(
        source,
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    private val feeds = mutableMapOf<String, CallFeed>()

    /** The accumulated stream of [callId], shared by every collector of that call. */
    fun state(callId: CallId): StateFlow<LiveCallFeedState> = synchronized(feeds) {
        feeds.getOrPut(callId.value) { CallFeed(callId) }.state
    }

    private inner class CallFeed(private val callId: CallId) {
        private val accumulated = MutableStateFlow(LiveCallFeedState(callId.value))

        /** Transcripts already accepted, so a page replayed with a new revision writes one turn. */
        private val appliedSequences = mutableSetOf<Long>()

        val state: StateFlow<LiveCallFeedState> = poll()
            .stateIn(
                scope = scope,
                // One long-poll for the call while anyone is watching; the last value and cursor
                // stay after the stop so the next collector resumes instead of starting over.
                started = SharingStarted.WhileSubscribed(
                    stopTimeoutMillis = 0,
                    replayExpirationMillis = Long.MAX_VALUE
                ),
                initialValue = accumulated.value
            )

        private fun poll(): Flow<LiveCallFeedState> = flow {
            var failures = 0
            while (currentCoroutineContext().isActive && !accumulated.value.ended) {
                when (val outcome = source.page(callId, accumulated.value.revision, WAIT_SECONDS)) {
                    is ApiOutcome.Ok -> {
                        failures = 0
                        accumulated.update { it.withPage(outcome.value) }
                        emit(accumulated.value)
                    }

                    is ApiOutcome.Failure -> {
                        failures++
                        accumulated.update { it.copy(reconnecting = true) }
                        emit(accumulated.value)
                        delay(backoffMs(failures))
                    }
                }
            }
            // The call ended: the loop is done, whatever the subscribers do next.
        }

        private fun LiveCallFeedState.withPage(page: CallEventsPage): LiveCallFeedState {
            val fresh = if (page.truncated) {
                // The feed dropped events we never saw; the record is the only complete copy.
                emptyList()
            } else {
                freshEvents(page.events, revision).filterNot { event ->
                    event.type == "transcript" && event.sequence?.let { !appliedSequences.add(it) } == true
                }
            }
            // Only events the cursor accepts can end the call; a page that dropped them carries the
            // state itself, exactly as the record does after a truncation.
            val ended = ended || callHasEnded(page.state) || fresh.any { callHasEnded(it.state) }
            return copy(
                revision = maxOf(revision, page.revision, fresh.lastOrNull()?.revision ?: 0),
                events = if (fresh.isEmpty()) events else events + fresh,
                state = page.state ?: state,
                callOutcome = page.callOutcome ?: callOutcome,
                resultStatus = page.resultStatus ?: resultStatus,
                truncatedPages = truncatedPages + if (page.truncated) 1 else 0,
                reconnecting = false,
                ended = ended
            )
        }
    }

    private fun backoffMs(failures: Int): Long = (FAILURE_BACKOFF_MS * failures.coerceIn(1, 5)).coerceAtMost(MAX_BACKOFF_MS)

    private companion object {
        const val WAIT_SECONDS = 25.0
        const val FAILURE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 5_000L
    }
}
