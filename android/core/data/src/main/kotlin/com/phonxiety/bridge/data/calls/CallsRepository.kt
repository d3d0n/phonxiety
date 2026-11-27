package com.phonxiety.bridge.data.calls

import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.data.DaemonJson
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.CallSummary
import com.phonxiety.bridge.model.DeleteCallResult
import com.phonxiety.bridge.model.MarksResponse
import com.phonxiety.bridge.model.TranscriptTurn
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
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

    fun observeCall(callId: String): Flow<CallSummary?>

    fun observeDetail(callId: String): Flow<CallSnapshot?>

    fun observeTranscript(callId: String): Flow<List<TranscriptTurn>>

    fun observeFarEnds(): Flow<List<String>>

    suspend fun page(filter: CallFilter, before: Double? = null, limit: Int = PAGE): CallPageResult

    suspend fun detail(callId: String, refresh: Boolean = true): CallDetailResult

    suspend fun transcript(callId: String, cursor: Long? = null, limit: Int = TRANSCRIPT_PAGE): TranscriptResult

    suspend fun addMark(callId: String, mark: String, note: String? = null): ApiOutcome<MarksResponse>

    suspend fun delete(callId: String): ApiOutcome<DeleteCallResult>

    suspend fun downloadAudio(callId: String, format: String, file: File): ApiResult

    companion object {
        const val PAGE = 30
        const val TRANSCRIPT_PAGE = 100
    }
}

@Singleton
class DefaultCallsRepository @Inject constructor(
    private val api: DaemonApi,
    private val dao: CallDao
) : CallsRepository {
    private val json: Json = DaemonJson

    override fun observeCalls(filter: CallFilter): Flow<List<CallSummary>> = dao.observeMatching(filter.outcome, filter.farEnd, filter.query.trim()).map { rows ->
        rows.map { it.toSummary() }
    }

    override fun observeCall(callId: String): Flow<CallSummary?> = dao.observe(callId).map { it?.toSummary() }

    override fun observeDetail(callId: String): Flow<CallSnapshot?> = dao.observe(callId).map { it?.detailJson?.let(::decode) }

    override fun observeTranscript(callId: String): Flow<List<TranscriptTurn>> = dao.observeTranscript(callId).map { rows -> rows.map { it.toTurn() } }

    override fun observeFarEnds(): Flow<List<String>> = dao.observeFarEnds()

    override suspend fun page(filter: CallFilter, before: Double?, limit: Int): CallPageResult = withContext(Dispatchers.IO) {
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

    override suspend fun detail(callId: String, refresh: Boolean): CallDetailResult = withContext(Dispatchers.IO) {
        val existing = dao.get(callId)
        if (!refresh) {
            return@withContext CallDetailResult(existing?.detailJson?.let(::decode), null, cached = true)
        }
        when (val outcome = api.call(callId)) {
            is ApiOutcome.Ok -> {
                val snapshot = outcome.value
                dao.upsert(listOf(snapshot.toEntity(existing, encode(snapshot), now())))
                if (snapshot.transcript.isNotEmpty()) {
                    dao.upsertTranscript(snapshot.transcript.map { it.toEntity(callId) })
                }
                CallDetailResult(snapshot, null, cached = false)
            }

            is ApiOutcome.Failure -> CallDetailResult(
                snapshot = existing?.detailJson?.let(::decode),
                failure = outcome,
                cached = true
            )
        }
    }

    override suspend fun transcript(callId: String, cursor: Long?, limit: Int): TranscriptResult = withContext(Dispatchers.IO) {
        when (val outcome = api.transcript(callId, cursor, limit)) {
            is ApiOutcome.Ok -> {
                val page = outcome.value
                if (page.transcript.isNotEmpty()) {
                    dao.upsertTranscript(page.transcript.map { it.toEntity(callId) })
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

    override suspend fun addMark(callId: String, mark: String, note: String?): ApiOutcome<MarksResponse> = withContext(Dispatchers.IO) {
        val outcome = api.addMark(callId, mark, note)
        if (outcome is ApiOutcome.Ok) {
            val existing = dao.get(callId)
            if (existing != null) {
                dao.upsert(listOf(existing.copy(marks = outcome.value.marks.size, cachedAt = now())))
            }
        }
        outcome
    }

    override suspend fun delete(callId: String): ApiOutcome<DeleteCallResult> = withContext(Dispatchers.IO) {
        val outcome = api.deleteCall(callId)
        if (outcome is ApiOutcome.Ok) {
            dao.deleteTranscript(callId)
            dao.delete(callId)
        }
        outcome
    }

    override suspend fun downloadAudio(callId: String, format: String, file: File): ApiResult = withContext(Dispatchers.IO) {
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
        val rows = ArrayList<CallEntity>(calls.size)
        for (summary in calls) {
            rows += summary.toEntity(dao.get(summary.callId), now)
        }
        dao.upsert(rows)
    }

    private fun encode(snapshot: CallSnapshot): String = json.encodeToString(CallSnapshot.serializer(), snapshot)

    private fun decode(json: String): CallSnapshot? = runCatching { this.json.decodeFromString(CallSnapshot.serializer(), json) }.getOrNull()

    private fun now(): Long = System.currentTimeMillis()
}

/** The marks a call can carry; the daemon rejects anything else. */
val CALL_MARK_KINDS: List<String> = listOf("agreed", "refused", "follow-up", "unclear", "callback")
