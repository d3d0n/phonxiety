package com.phonxiety.bridge.data

import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.model.CallEvent
import com.phonxiety.bridge.model.CallEventsPage
import com.phonxiety.bridge.model.CallId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

/** How long a call nobody watches keeps its cursor before the feed forgets it. */
internal const val ABANDONED_CALL_IDLE_MS = 10 * 60 * 1000L

/** Terminal pages kept after their call is forgotten, so a screen that comes back still reads one. */
internal const val REMEMBERED_TERMINAL_CALLS = 4

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
     * Accepted pages so far. A reconnect-status emission keeps it, so a screen can tell a new
     * page from a note that the last attempt failed and never apply stale metadata as news.
     */
    val acceptedPages: Long = 0,
    /**
     * The revision at which the last page arrived without the events before it. Everything at or
     * below it predates the record a screen must re-read; only later events are newer than it.
     */
    val truncationRevision: Long? = null,
    /** The last attempt failed and another follows. */
    val reconnecting: Boolean = false,
    /** The daemon called the call over; the stream is finished. */
    val ended: Boolean = false
)

/**
 * The live-call events of each call, shared by every screen that shows that call: the live call
 * and the test lab's test call.
 *
 * The feed is a singleton because the request belongs to the call, not to a screen. A call's loop
 * runs while at least one collector watches it: the first collector publishes the accepted value
 * on its way in and starts the loop behind it, and the last one stops the loop, keeping the cursor
 * for whoever comes back. A terminal page finishes the loop, and its entry is kept so a screen that
 * leaves and returns still reads the end instead of asking a finished call again.
 *
 * Memory is bounded: a call with no collectors is forgotten after [ABANDONED_CALL_IDLE_MS], and
 * finished calls kept for a return are capped at [REMEMBERED_TERMINAL_CALLS] with the
 * most-recently-touched ones winning. A live call with a collector is never forgotten.
 */
@Singleton
class LiveCallFeed constructor(
    private val source: CallEventsSource,
    /** The clock the long-poll and the idle bound run on; tests pass their own. */
    private val scope: CoroutineScope
) {
    @Inject
    constructor(source: CallEventsSource) : this(
        source,
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    /**
     * Awaited after a page is accepted and before it is published. It exists so tests can cancel a
     * loop exactly in that window; production leaves it empty.
     */
    internal var afterAccept: suspend () -> Unit = {}

    /**
     * Awaited after a view is handed out and before its collection attaches. It exists so tests can
     * forget the entry in that window; production leaves it empty.
     */
    internal var beforeAttach: suspend () -> Unit = {}

    private val lock = Any()

    /** Live calls and the recently finished ones, in creation order. */
    private val feeds = LinkedHashMap<String, CallFeed>()

    private var touches = 0L

    /** The accumulated stream of [callId], shared by every collector of that call. */
    fun state(callId: CallId): StateFlow<LiveCallFeedState> = synchronized(lock) {
        entryLocked(callId).view
    }

    /** The current entry of [callId], created if the call is not held yet. Caller holds [lock]. */
    private fun entryLocked(callId: CallId): CallFeed = feeds.getOrPut(callId.value) { CallFeed(callId) }

    /** How many calls still hold a cursor in memory. */
    internal fun retainedCalls(): Int = synchronized(lock) { feeds.size }

    /** Whether [callId] still has an entry in memory. */
    internal fun isRetained(callId: CallId): Boolean = synchronized(lock) { feeds.containsKey(callId.value) }

    /** How many finished calls are kept for a screen that comes back. */
    internal fun retainedTerminalCalls(): Int = synchronized(lock) { feeds.values.count { it.finished } }

    /** Whether [callId] still has a request loop of its own. */
    internal fun isPolling(callId: CallId): Boolean = synchronized(lock) {
        feeds[callId.value]?.polling == true
    }

    /** How many collectors are attached to [callId]'s current entry. */
    internal fun collectorsOf(callId: CallId): Int = synchronized(lock) {
        feeds[callId.value]?.collectors ?: 0
    }

    private inner class CallFeed(private val callId: CallId) {
        private var accumulated = LiveCallFeedState(callId.value)
        private val published = MutableStateFlow(accumulated)

        /** Transcripts already accepted, so a page replayed with a new revision writes one turn. */
        private val appliedSequences = mutableSetOf<Long>()

        var collectors = 0
        private var generation = 0
        private var job: Job? = null
        private var idleJob: Job? = null
        private var touchedAt = 0L

        val polling: Boolean get() = job?.isActive == true

        val finished: Boolean get() = accumulated.ended

        val view: StateFlow<LiveCallFeedState> = object : StateFlow<LiveCallFeedState> {
            override val value: LiveCallFeedState get() = published.value
            override val replayCache: List<LiveCallFeedState> get() = published.replayCache

            override suspend fun collect(collector: FlowCollector<LiveCallFeedState>): Nothing {
                // A view outlives the entry it was handed out with, so it never keeps that entry
                // across collections. Resolving the current entry here, and counting the collector
                // on it under the map lock, is what stops an evicted entry from being restarted
                // through an old view: the collection always belongs to whatever the map holds now.
                this@LiveCallFeed.beforeAttach()
                val entry = synchronized(lock) {
                    val current = this@LiveCallFeed.entryLocked(callId)
                    current.attached()
                    current
                }
                try {
                    entry.published.collect(collector)
                } finally {
                    synchronized(lock) { entry.detached() }
                }
            }
        }

        /** Caller holds [lock]. */
        private fun attached() {
            collectors++
            touches++
            touchedAt = touches
            // Whatever was accepted goes out before the loop is allowed to start: a cancellation
            // between accepting a page and publishing it leaves the accumulation ahead of the
            // value every later collector reads, and this is where that gap is closed.
            published.value = accumulated
            if (collectors == 1 && !accumulated.ended && job == null) start()
        }

        /** Caller holds [lock]. */
        private fun detached() {
            if (collectors > 0) collectors--
            if (collectors > 0) return
            // The request belongs to the call, not the screen: nobody is left, so the loop stops
            // and the accumulated value and cursor stay for the next collector.
            stop()
            // A page accepted but not yet published is still the collectors' final value; publish
            // it here so a reader that stays behind never sees the accumulation fall behind it.
            published.value = accumulated
            touches++
            touchedAt = touches
            // A finished call keeps its entry for a screen that comes back; the live ones only keep
            // it while the idle bound allows.
            if (accumulated.ended) trimFinished() else scheduleIdleEviction()
        }

        private fun start() {
            val token = ++generation
            job = scope.launch {
                try {
                    poll(token)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    // One call's surprise is not the monitor's: the loop is already over here, and
                    // its next collector starts a fresh one.
                    BridgeLog.e(TAG, "call ${callId.value} events loop failed", error)
                }
            }
        }

        private fun stop() {
            // Invalidate the running loop before cancelling it: a job cancelled while suspended in
            // a non-cancellable spot still checks its token before it writes anything.
            generation++
            job?.cancel()
            job = null
        }

        private suspend fun poll(token: Int) {
            publish(token)
            if (!isCurrent(token) || accumulated.ended) return
            var failures = 0
            while (isCurrent(token) && !accumulated.ended && currentCoroutineContext().isActive) {
                val outcome = try {
                    source.page(callId, accumulated.revision, WAIT_SECONDS)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    // The daemon failing one request is not the end of the call, and not the end of
                    // any other call's loop: it takes the same backoff as a reported failure.
                    BridgeLog.e(TAG, "call ${callId.value} events request failed", error)
                    null
                }
                val delivered = when (outcome) {
                    null -> false

                    is ApiOutcome.Ok -> try {
                        if (!accept(token, outcome.value)) return
                        afterAccept()
                        if (!publish(token)) return
                        if (accumulated.ended) synchronized(lock) { trimFinished() }
                        true
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        // A surprise while applying a delivered page is the same as a failed read:
                        // surface it, keep the cursor and try again, so neither the page already
                        // accepted nor the loop is lost to it.
                        BridgeLog.e(TAG, "call ${callId.value} events page failed", error)
                        false
                    }

                    is ApiOutcome.Failure -> false
                }
                if (delivered) {
                    failures = 0
                } else {
                    failures++
                    if (!reconnecting(token)) return
                    delay(backoffMs(failures))
                }
            }
        }

        private fun isCurrent(token: Int): Boolean = synchronized(lock) { token == generation }

        /** Publishes the accumulated value; false when this loop has been replaced or stopped. */
        private fun publish(token: Int): Boolean = synchronized(lock) {
            if (token != generation) return false
            published.value = accumulated
            touches++
            touchedAt = touches
            true
        }

        private fun accept(token: Int, page: CallEventsPage): Boolean = synchronized(lock) {
            if (token != generation) return false
            accumulated = accumulated.withPage(page)
            true
        }

        private fun reconnecting(token: Int): Boolean = synchronized(lock) {
            if (token != generation) return false
            accumulated = accumulated.copy(reconnecting = true)
            published.value = accumulated
            true
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
                acceptedPages = acceptedPages + 1,
                truncationRevision = if (page.truncated) maxOf(truncationRevision ?: 0, page.revision) else truncationRevision,
                reconnecting = false,
                ended = ended
            )
        }

        /**
         * Caps the finished entries kept for a return. Only entries nobody watches are victims: a
         * screen still reading a call is never cut off, even while its terminal page is still on
         * its way out, so the cap is met once those collectors leave. Caller holds [lock].
         */
        private fun trimFinished() {
            val finished = feeds.values.filter { it.finished }
            if (finished.size <= REMEMBERED_TERMINAL_CALLS) return
            val over = finished.size - REMEMBERED_TERMINAL_CALLS
            val ordered = finished.sortedBy { it.touchedAt }
            val victims = ordered.filter { it.collectors == 0 }.take(over)
            victims.forEach { it.evict() }
        }

        private fun scheduleIdleEviction() {
            idleJob?.cancel()
            val token = generation
            idleJob = scope.launch {
                delay(ABANDONED_CALL_IDLE_MS)
                synchronized(lock) {
                    if (token != generation || collectors > 0 || accumulated.ended) return@synchronized
                    if (feeds[callId.value] !== this@CallFeed) return@synchronized
                    evict()
                }
            }
        }

        /** Caller holds [lock]. */
        private fun evict() {
            if (feeds[callId.value] === this) feeds.remove(callId.value)
            stop()
            idleJob?.cancel()
            idleJob = null
        }
    }

    private fun backoffMs(failures: Int): Long = (FAILURE_BACKOFF_MS * failures.coerceIn(1, 5)).coerceAtMost(MAX_BACKOFF_MS)

    private companion object {
        const val TAG = "LiveCallFeed"
        const val WAIT_SECONDS = 25.0
        const val FAILURE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 5_000L
    }
}
