package com.phonxiety.bridge.data.calls

import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.data.DaemonJson
import com.phonxiety.bridge.data.DeleteOutcome
import com.phonxiety.bridge.data.MarkOutcome
import com.phonxiety.bridge.data.di.IoDispatcher
import com.phonxiety.bridge.model.CallId
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.CallSummary
import com.phonxiety.bridge.model.DeleteCallResult
import com.phonxiety.bridge.model.MarksResponse
import com.phonxiety.bridge.model.TranscriptTurn
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** What the list asks the cache and the daemon for. */
data class CallFilter(
    val outcome: String? = null,
    val farEnd: String? = null,
    val query: String = ""
) {
    val isFiltered: Boolean get() = outcome != null || farEnd != null || query.isNotBlank()
}

/**
 * One page of history. [failure] is set when the daemon could not answer, in which case [calls] is
 * empty and the caller shows the cache instead of an invented list.
 */
data class CallPageResult(
    val calls: List<CallSummary>,
    val hasMore: Boolean,
    val nextBefore: Double?,
    val failure: ApiOutcome.Failure?
)

/**
 * The full record, either fresh from the daemon or from the cache when the link is down. [failure]
 * is set when the refresh failed; [snapshot] may still be a cached copy.
 */
data class CallDetailResult(
    val snapshot: CallSnapshot?,
    val failure: ApiOutcome.Failure?,
    val cached: Boolean
)

data class TranscriptResult(
    val turns: List<TranscriptTurn>,
    val nextCursor: Long?,
    val hasMore: Boolean,
    val complete: Boolean,
    val failure: ApiOutcome.Failure?
)

/**
 * Call history: the daemon is the record, the Room cache keeps the list and the opened calls
 * readable with no link, and every write (mark, delete) goes to the daemon first.
 */
interface CallsRepository {
    fun observeCalls(filter: CallFilter): Flow<List<CallSummary>>

    fun observeCall(callId: CallId): Flow<CallSummary?>

    fun observeDetail(callId: CallId): Flow<CallSnapshot?>

    fun observeTranscript(callId: CallId): Flow<List<TranscriptTurn>>

    fun observeFarEnds(): Flow<List<String>>

    suspend fun page(filter: CallFilter, before: Double? = null, limit: Int = PAGE): CallPageResult

    suspend fun detail(callId: CallId, refresh: Boolean = true): CallDetailResult

    suspend fun transcript(callId: CallId, cursor: Long? = null, limit: Int = TRANSCRIPT_PAGE): TranscriptResult

    suspend fun addMark(callId: CallId, mark: String, note: String? = null): MarkOutcome

    suspend fun delete(callId: CallId): DeleteOutcome

    suspend fun downloadAudio(callId: CallId, format: String, file: File): ApiResult

    companion object {
        const val PAGE = 30
        const val TRANSCRIPT_PAGE = 100
    }
}

@Singleton
class DefaultCallsRepository @Inject constructor(
    private val api: DaemonApi,
    private val cache: CallCache,
    @IoDispatcher private val io: CoroutineDispatcher
) : CallsRepository {
    private val json: Json = DaemonJson

    override fun observeCalls(filter: CallFilter): Flow<List<CallSummary>> = cache.list.observeMatching(filter.outcome, filter.farEnd, filter.query.trim()).map { rows ->
        rows.map { it.toSummary() }
    }

    override fun observeCall(callId: CallId): Flow<CallSummary?> = cache.list.observe(callId.value).map { it?.toSummary() }

    override fun observeDetail(callId: CallId): Flow<CallSnapshot?> = cache.detail.observeJson(callId.value).map { it?.let(::decode) }

    override fun observeTranscript(callId: CallId): Flow<List<TranscriptTurn>> = cache.detail.observeTranscript(callId.value).map { rows -> rows.map { it.toTurn() } }

    override fun observeFarEnds(): Flow<List<String>> = cache.list.observeFarEnds()

    override suspend fun page(filter: CallFilter, before: Double?, limit: Int): CallPageResult = withContext(io) {
        when (val outcome = api.calls(limit = limit, before = before, outcome = filter.outcome, farEnd = filter.farEnd)) {
            is ApiOutcome.Ok -> {
                cacheSummaries(outcome.value.calls)
                CallPageResult(
                    calls = outcome.value.calls,
                    hasMore = outcome.value.hasMore,
                    nextBefore = outcome.value.nextBefore,
                    failure = null
                )
            }

            is ApiOutcome.Failure -> CallPageResult(emptyList(), false, before, outcome)
        }
    }

    override suspend fun detail(callId: CallId, refresh: Boolean): CallDetailResult = withContext(io) {
        if (!refresh) {
            val cached = cache.detail.get(callId.value)?.detailJson?.let(::decode)
            return@withContext CallDetailResult(cached, null, cached = true)
        }
        when (val outcome = api.call(callId)) {
            is ApiOutcome.Ok -> {
                cacheDetail(callId.value, outcome.value)
                CallDetailResult(outcome.value, null, cached = false)
            }

            is ApiOutcome.Failure -> CallDetailResult(
                snapshot = cache.detail.get(callId.value)?.detailJson?.let(::decode),
                failure = outcome,
                cached = true
            )
        }
    }

    override suspend fun transcript(callId: CallId, cursor: Long?, limit: Int): TranscriptResult = withContext(io) {
        when (val outcome = api.transcript(callId, cursor, limit)) {
            is ApiOutcome.Ok -> {
                val page = outcome.value
                // A turn is only cached under a call whose detail row exists: the transcript
                // table hangs off it by a foreign key, and only an opened call pages turns. The
                // check shares the write's transaction, so a delete that lands while the page is
                // on its way cannot leave turns without a call: it is a quiet no-op instead.
                if (page.transcript.isNotEmpty()) {
                    cache.transaction {
                        if (detail.exists(callId.value)) detail.upsertTranscript(page.transcript.map { it.toEntity(callId.value) })
                    }
                }
                TranscriptResult(
                    turns = page.transcript,
                    nextCursor = page.nextCursor,
                    hasMore = page.hasMore,
                    complete = page.transcriptComplete,
                    failure = null
                )
            }

            is ApiOutcome.Failure -> TranscriptResult(
                turns = emptyList(),
                nextCursor = cursor,
                hasMore = false,
                complete = false,
                failure = outcome
            )
        }
    }

    override suspend fun addMark(callId: CallId, mark: String, note: String?): MarkOutcome = withContext(io) {
        val outcome = api.addMark(callId, mark, note)
        if (outcome is MarkOutcome.Marked) {
            val existing = cache.list.get(callId.value)
            if (existing != null) {
                cache.list.upsert(listOf(existing.copy(marks = outcome.marks.size, cachedAt = now())))
            }
        }
        outcome
    }

    override suspend fun delete(callId: CallId): DeleteOutcome = withContext(io) {
        val outcome = api.deleteCall(callId)
        if (outcome is DeleteOutcome.Deleted) {
            // One delete; the detail row and every transcript turn go with it by cascade.
            cache.transaction { list.delete(callId.value) }
        }
        outcome
    }

    override suspend fun downloadAudio(callId: CallId, format: String, file: File): ApiResult = withContext(io) {
        file.parentFile?.mkdirs()
        val sink = runCatching { FileOutputStream(file) }.getOrElse {
            return@withContext ApiResult.Failure(500, "audio_file_unwritable", it.javaClass.simpleName)
        }
        try {
            val result = api.downloadAudio(callId, format, sink)
            if (result is ApiResult.Failure) file.delete()
            result
        } finally {
            runCatching { sink.close() }
        }
    }

    /** Newest marks are what a detail view shows; the cache keeps the count, not the list. */
    private suspend fun cacheSummaries(calls: List<CallSummary>) {
        if (calls.isEmpty()) return
        val now = now()
        cache.transaction {
            val rows = ArrayList<CallSummaryEntity>(calls.size)
            for (summary in calls) {
                rows += summary.toEntity(list.get(summary.callId), now)
            }
            list.upsert(rows)
        }
    }

    /** The summary row, the record and its transcript land in one transaction or not at all. */
    private suspend fun cacheDetail(callId: String, snapshot: CallSnapshot) {
        val now = now()
        cache.transaction {
            list.upsert(listOf(snapshot.toEntity(list.get(callId), now)))
            detail.upsert(CallDetailEntity(callId = callId, detailJson = encode(snapshot), detailFetchedAt = now))
            if (snapshot.transcript.isNotEmpty()) {
                detail.upsertTranscript(snapshot.transcript.map { it.toEntity(callId) })
            }
        }
    }

    private fun encode(snapshot: CallSnapshot): String = json.encodeToString(CallSnapshot.serializer(), snapshot)

    private fun decode(json: String): CallSnapshot? = runCatching { this.json.decodeFromString(CallSnapshot.serializer(), json) }.getOrNull()

    private fun now(): Long = System.currentTimeMillis()
}

/** The marks a call can carry; the daemon rejects anything else. */
val CALL_MARK_KINDS: List<String> = listOf("agreed", "refused", "follow-up", "unclear", "callback")
