package com.phonxiety.bridge.data.calls

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * The list: the summary columns only, so the history query never touches a record's JSON.
 * Filtering and search happen in SQL so the cached list answers the same questions as the
 * daemon's page, with or without a link.
 */
@Dao
interface CallListDao {
    @Query(
        """
        SELECT * FROM calls
        WHERE (:outcome IS NULL OR callOutcome = :outcome)
          AND (:farEnd IS NULL OR farEnd = :farEnd)
          AND (
            :query = ''
            OR summary LIKE '%' || :query || '%'
            OR destination LIKE '%' || :query || '%'
            OR goal LIKE '%' || :query || '%'
          )
        ORDER BY createdAt DESC, cachedAt DESC
        """
    )
    fun observeMatching(outcome: String?, farEnd: String?, query: String): Flow<List<CallSummaryEntity>>

    @Query("SELECT * FROM calls WHERE callId = :callId")
    fun observe(callId: String): Flow<CallSummaryEntity?>

    @Query("SELECT * FROM calls WHERE callId = :callId")
    suspend fun get(callId: String): CallSummaryEntity?

    @Query("SELECT DISTINCT farEnd FROM calls WHERE farEnd IS NOT NULL AND farEnd != '' ORDER BY farEnd")
    fun observeFarEnds(): Flow<List<String>>

    @Upsert
    suspend fun upsert(calls: List<CallSummaryEntity>)

    @Query("DELETE FROM calls WHERE callId = :callId")
    suspend fun delete(callId: String)
}

/** One opened call: its record and its transcript. Only the detail screen reads these. */
@Dao
interface CallDetailDao {
    @Query("SELECT detailJson FROM call_detail WHERE callId = :callId")
    fun observeJson(callId: String): Flow<String?>

    @Query("SELECT * FROM call_detail WHERE callId = :callId")
    suspend fun get(callId: String): CallDetailEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM call_detail WHERE callId = :callId)")
    suspend fun exists(callId: String): Boolean

    @Upsert
    suspend fun upsert(detail: CallDetailEntity)

    @Query("SELECT * FROM call_transcript WHERE callId = :callId ORDER BY sequence")
    fun observeTranscript(callId: String): Flow<List<TranscriptEntity>>

    @Upsert
    suspend fun upsertTranscript(turns: List<TranscriptEntity>)
}

/**
 * The call cache's tables and the transactions that keep them consistent. A detail write and its
 * transcript land together or not at all; a delete is one statement whose foreign keys cascade.
 */
@Singleton
class CallCache @Inject constructor(private val database: CallCacheDatabase) {
    val list: CallListDao get() = database.callListDao()

    val detail: CallDetailDao get() = database.callDetailDao()

    suspend fun <R> transaction(block: suspend CallCache.() -> R): R = database.withTransaction { this@CallCache.block() }
}

@Database(
    entities = [CallSummaryEntity::class, CallDetailEntity::class, TranscriptEntity::class],
    version = 2,
    exportSchema = false
)
abstract class CallCacheDatabase : RoomDatabase() {
    abstract fun callListDao(): CallListDao

    abstract fun callDetailDao(): CallDetailDao
}
