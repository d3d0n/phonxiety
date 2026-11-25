package com.phonxiety.bridge.data.calls

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The call cache. Filtering and search happen in SQL so the cached list answers the same
 * questions as the daemon's page, with or without a link.
 */
@Dao
interface CallDao {
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
    fun observeMatching(outcome: String?, farEnd: String?, query: String): Flow<List<CallEntity>>

    @Query("SELECT * FROM calls WHERE callId = :callId")
    fun observe(callId: String): Flow<CallEntity?>

    @Query("SELECT * FROM calls WHERE callId = :callId")
    suspend fun get(callId: String): CallEntity?

    @Query("SELECT DISTINCT farEnd FROM calls WHERE farEnd IS NOT NULL AND farEnd != '' ORDER BY farEnd")
    fun observeFarEnds(): Flow<List<String>>

    @Upsert
    suspend fun upsert(calls: List<CallEntity>)

    @Query("DELETE FROM calls WHERE callId = :callId")
    suspend fun delete(callId: String)

    @Query("SELECT * FROM call_transcript WHERE callId = :callId ORDER BY sequence")
    fun observeTranscript(callId: String): Flow<List<TranscriptEntity>>

    @Query("SELECT MAX(sequence) FROM call_transcript WHERE callId = :callId")
    suspend fun lastSequence(callId: String): Long?

    @Upsert
    suspend fun upsertTranscript(turns: List<TranscriptEntity>)

    @Query("DELETE FROM call_transcript WHERE callId = :callId")
    suspend fun deleteTranscript(callId: String)
}

@Database(entities = [CallEntity::class, TranscriptEntity::class], version = 1, exportSchema = false)
abstract class CallCacheDatabase : RoomDatabase() {
    abstract fun callDao(): CallDao
}
