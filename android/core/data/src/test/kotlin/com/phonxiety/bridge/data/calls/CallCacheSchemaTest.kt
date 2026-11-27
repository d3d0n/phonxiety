package com.phonxiety.bridge.data.calls

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The cache's shape as SQLite sees it: the list table holds summary columns only, the filters and
 * the sort have indices, and a call's detail and transcript go with it when the call is deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallCacheSchemaTest {
    private lateinit var database: CallCacheDatabase
    private lateinit var cache: CallCache

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, CallCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cache = CallCache(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun theListTableKeepsSummaryColumnsOnly() {
        val columns = columns("calls")

        assertTrue(columns.contains("callOutcome"))
        assertTrue(columns.contains("summary"))
        assertFalse("the record's JSON is not a list column", columns.contains("detailJson"))
        assertFalse("the record's fetch time is not a list column", columns.contains("detailFetchedAt"))
        assertTrue("the record has its own table", tables().contains("call_detail"))
    }

    @Test
    fun everyFilterAndSortColumnIsIndexed() {
        val indexed = indexedColumns("calls")

        assertTrue("outcome filter", indexed.contains("callOutcome"))
        assertTrue("far-end filter", indexed.contains("farEnd"))
        assertTrue("newest-first sort", indexed.contains("createdAt"))
        assertTrue("sort tie-break", indexed.contains("cachedAt"))
    }

    @Test
    fun deletingACallCascadesItsDetailAndTranscript() = runTest {
        cache.list.upsert(listOf(summary("call-1")))
        cache.detail.upsert(CallDetailEntity(callId = "call-1", detailJson = "{}", detailFetchedAt = 1L))
        cache.detail.upsertTranscript(listOf(TranscriptEntity("call-1", 1, "agent", "Hello", 1.0, true)))

        cache.list.delete("call-1")

        assertNull(cache.detail.get("call-1"))
        assertEquals(emptyList<TranscriptEntity>(), cache.detail.observeTranscript("call-1").first())
    }

    private fun columns(table: String): Set<String> = database.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(1))
        }
    }

    private fun tables(): Set<String> = database.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    /** Every column an ordinary (not primary-key) index covers on [table]. */
    private fun indexedColumns(table: String): Set<String> {
        val names = database.openHelper.readableDatabase.query("PRAGMA index_list($table)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    // seq, name, unique, origin, partial
                    if (cursor.getString(3) != "pk") add(cursor.getString(1))
                }
            }
        }
        return names.flatMapTo(mutableSetOf()) { name ->
            database.openHelper.readableDatabase.query("PRAGMA index_info($name)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(2))
                }
            }
        }
    }

    private fun summary(callId: String): CallSummaryEntity = CallSummaryEntity(
        callId = callId,
        state = "ended",
        destination = "+998901234567",
        goal = "Book a table",
        callOutcome = "answered",
        taskOutcome = "complete",
        resultStatus = "ready",
        summary = "Table booked",
        error = null,
        farEnd = "Bella Napoli",
        createdAt = 1_764_000_000.0,
        connectedAt = 1_764_000_005.0,
        endedAt = 1_764_000_065.0,
        durationSeconds = 60.0,
        marks = 0,
        hasRecording = false,
        live = false,
        cachedAt = 1L
    )
}
