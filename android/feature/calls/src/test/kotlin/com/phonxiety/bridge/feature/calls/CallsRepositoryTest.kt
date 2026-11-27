package com.phonxiety.bridge.feature.calls

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.DeleteOutcome
import com.phonxiety.bridge.data.calls.CallCache
import com.phonxiety.bridge.data.calls.CallCacheDatabase
import com.phonxiety.bridge.data.calls.CallFilter
import com.phonxiety.bridge.data.calls.DefaultCallsRepository
import com.phonxiety.bridge.data.calls.TranscriptResult
import com.phonxiety.bridge.model.CallId
import com.phonxiety.bridge.model.CallsPage
import com.phonxiety.bridge.model.TranscriptPage
import com.phonxiety.bridge.model.TranscriptTurn
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cache with a real Room database: a page is written through, the filters are SQL, an offline
 * detail still answers, paging accumulates, and a delete removes both the record and the transcript.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallsRepositoryTest {
    private lateinit var database: CallCacheDatabase
    private lateinit var repository: DefaultCallsRepository
    private val api = FakeCallsApi()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, CallCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultCallsRepository(api, CallCache(database))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aPageIsCachedAndFilterable() = runTest {
        api.pages += ApiOutcome.Ok(
            200,
            CallsPage(
                calls = listOf(
                    summary("call-1", callOutcome = "answered"),
                    summary("call-2", callOutcome = "no_answer", summary = "Nobody picked up", farEnd = null),
                    summary("call-3", callOutcome = "answered", destination = "+998977654321", summary = "Pizza order")
                ),
                hasMore = true,
                nextBefore = 1_764_000_000.0
            )
        )

        val page = repository.page(CallFilter(), before = null)

        assertEquals(3, page.calls.size)
        assertTrue(page.hasMore)
        assertNull(page.failure)
        assertEquals(3, repository.observeCalls(CallFilter()).first().size)
        assertEquals(2, repository.observeCalls(CallFilter(outcome = "answered")).first().size)
        assertEquals(1, repository.observeCalls(CallFilter(query = "pizza")).first().size)
        assertEquals(2, repository.observeCalls(CallFilter(farEnd = "Bella Napoli")).first().size)
    }

    @Test
    fun anOfflineRefreshKeepsTheCachedDetail() = runTest {
        api.detail = ApiOutcome.Ok(
            200,
            detailSnapshot(
                transcript = listOf(
                    TranscriptTurn(1, "agent", "Hello", 1_764_000_006.0, true),
                    TranscriptTurn(2, "far_end", "Who is this?", 1_764_000_008.0, true)
                )
            )
        )

        val fresh = repository.detail(CallId("call-1"))
        assertNotNull(fresh.snapshot)
        assertNull(fresh.failure)

        api.offline = true
        val cached = repository.detail(CallId("call-1"))

        assertNotNull(cached.snapshot)
        assertTrue(cached.cached)
        assertEquals("Table for two on Thursday", cached.snapshot?.summary)
        assertEquals(2, repository.observeTranscript(CallId("call-1")).first().size)
    }

    @Test
    fun theSummaryRefreshDoesNotBlankTheDetail() = runTest {
        api.detail = ApiOutcome.Ok(200, detailSnapshot())
        repository.detail(CallId("call-1"))

        // A summary page carries fewer fields; the cached record must survive it.
        api.pages += ApiOutcome.Ok(200, CallsPage(calls = listOf(summary("call-1", summary = null))))
        repository.page(CallFilter(), before = null)

        assertEquals("Table for two on Thursday", repository.observeCalls(CallFilter()).first().single().summary)
        assertEquals(
            "Book a table for two",
            repository.observeDetail(CallId("call-1")).first()?.request?.get("goal")?.toString()?.trim('"')
        )
    }

    @Test
    fun transcriptPagesAccumulateInTheCache() = runTest {
        // The screen pages a transcript only for a call it has already opened: the transcript
        // table hangs off the record by a foreign key, so the record is cached first.
        api.detail = ApiOutcome.Ok(200, detailSnapshot())
        repository.detail(CallId("call-1"))
        api.transcriptPages += ApiOutcome.Ok(
            200,
            TranscriptPage(
                callId = "call-1",
                transcript = listOf(TranscriptTurn(3, "agent", "Nine it is", 1_764_000_020.0, true)),
                nextCursor = 3,
                hasMore = true
            )
        )
        api.transcriptPages += ApiOutcome.Ok(
            200,
            TranscriptPage(
                callId = "call-1",
                transcript = listOf(TranscriptTurn(4, "far_end", "Anything else?", 1_764_000_022.0, true)),
                nextCursor = 4,
                hasMore = false,
                transcriptComplete = true
            )
        )

        val first: TranscriptResult = repository.transcript(CallId("call-1"), cursor = 2)
        val second: TranscriptResult = repository.transcript(CallId("call-1"), cursor = first.nextCursor)

        assertTrue(first.hasMore)
        assertTrue(second.complete)
        assertEquals(listOf<Long?>(2L, 3L), api.transcriptCursors)
        // The opened record cached its own first turn; the two pages accumulate after it.
        assertEquals(listOf(1L, 3L, 4L), repository.observeTranscript(CallId("call-1")).first().map { it.sequence })
    }

    @Test
    fun deleteRemovesTheCallAndItsTranscript() = runTest {
        api.detail = ApiOutcome.Ok(200, detailSnapshot())
        repository.detail(CallId("call-1"))
        assertEquals(1, repository.observeCalls(CallFilter()).first().size)

        val outcome = repository.delete(CallId("call-1"))

        assertTrue(outcome is DeleteOutcome.Deleted)
        assertTrue(repository.observeCalls(CallFilter()).first().isEmpty())
        assertTrue(repository.observeTranscript(CallId("call-1")).first().isEmpty())
    }

    @Test
    fun aMarkUpdatesTheCachedCount() = runTest {
        api.detail = ApiOutcome.Ok(200, detailSnapshot())
        repository.detail(CallId("call-1"))
        api.marksResult = ApiOutcome.Ok(
            200,
            com.phonxiety.bridge.model.MarksResponse(
                marks = listOf(
                    com.phonxiety.bridge.model.CallMark("agreed"),
                    com.phonxiety.bridge.model.CallMark("follow-up")
                )
            )
        )

        repository.addMark(CallId("call-1"), "agreed")

        assertEquals(listOf("agreed"), api.markRequests)
        assertEquals(2, repository.observeCalls(CallFilter()).first().single().marks)
    }

    @Test
    fun aTranscriptWriteThatFailsLeavesNoDetailBehind() = runTest {
        // A trigger refuses one turn: the record and its transcript must land together or not at all.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_sequence_99 BEFORE INSERT ON call_transcript " +
                "WHEN NEW.sequence = 99 BEGIN SELECT RAISE(ABORT, 'boom'); END"
        )
        api.detail = ApiOutcome.Ok(
            200,
            detailSnapshot(transcript = listOf(TranscriptTurn(99, "agent", "Hello", 1_764_000_006.0, true)))
        )

        val thrown = runCatching { repository.detail(CallId("call-1")) }

        assertTrue("the write must fail", thrown.isFailure)
        assertNull("the record did not land", repository.observeDetail(CallId("call-1")).first())
        assertTrue("the summary did not land", repository.observeCalls(CallFilter()).first().isEmpty())
        assertTrue("the transcript did not land", repository.observeTranscript(CallId("call-1")).first().isEmpty())
    }

    @Test
    fun downloadAudioWritesTheFile() = runTest {
        api.audioBytes = byteArrayOf(7, 7, 7)
        val directory = File(System.getProperty("java.io.tmpdir"), "calls-test-${System.nanoTime()}")
        val file = File(directory, "call-1.m4a")
        try {
            val result = repository.downloadAudio(CallId("call-1"), "m4a", file)
            assertTrue(result is com.phonxiety.bridge.data.ApiResult.Success)
            assertEquals(3L, file.length())
        } finally {
            directory.deleteRecursively()
        }
    }
}
