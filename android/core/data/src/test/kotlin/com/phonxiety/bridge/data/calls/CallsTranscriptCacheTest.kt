package com.phonxiety.bridge.data.calls

import android.content.Context
import androidx.room.Room
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.model.CallEventsPage
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.CallsPage
import com.phonxiety.bridge.model.CompatReport
import com.phonxiety.bridge.model.DaemonInfo
import com.phonxiety.bridge.model.DaemonStatus
import com.phonxiety.bridge.model.DeleteCallResult
import com.phonxiety.bridge.model.DiagnosticsBundle
import com.phonxiety.bridge.model.DoctorFixResult
import com.phonxiety.bridge.model.DoctorReport
import com.phonxiety.bridge.model.DtmfResult
import com.phonxiety.bridge.model.InstructionResult
import com.phonxiety.bridge.model.LinkTestResult
import com.phonxiety.bridge.model.LinkTests
import com.phonxiety.bridge.model.LoopbackResult
import com.phonxiety.bridge.model.MarksResponse
import com.phonxiety.bridge.model.PauseResult
import com.phonxiety.bridge.model.PlaceCallRequest
import com.phonxiety.bridge.model.ProbeResult
import com.phonxiety.bridge.model.RotateResult
import com.phonxiety.bridge.model.SettingsSnapshot
import com.phonxiety.bridge.model.SettingsUpdate
import com.phonxiety.bridge.model.SummaryResult
import com.phonxiety.bridge.model.TestCallRequest
import com.phonxiety.bridge.model.ToneResult
import com.phonxiety.bridge.model.TranscriptPage
import com.phonxiety.bridge.model.TranscriptTurn
import com.phonxiety.bridge.model.ValidationResult
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A page of transcript is cached only under a call that still exists. The delete and the page
 * arrive as separate transactions — the delete from the detail screen, the page from the paging
 * call — so the check and the write have to be one transaction: a delete in between is a quiet
 * no-op, not a foreign-key failure and not a turn without a call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallsTranscriptCacheTest {
    private lateinit var database: CallCacheDatabase
    private lateinit var cache: CallCache
    private lateinit var transactions: GatedTransactionExecutor

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        transactions = GatedTransactionExecutor()
        database = Room.inMemoryDatabaseBuilder(context, CallCacheDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Executors.newFixedThreadPool(4))
            .setTransactionExecutor(transactions)
            .build()
        cache = CallCache(database)
    }

    @After
    fun tearDown() {
        transactions.release.countDown()
        database.close()
    }

    @Test
    fun aDeleteThatLandsDuringThePageLeavesNoOrphanTurnAndNoFailure() = runBlocking {
        cache.list.upsert(listOf(summary("call-1")))
        cache.detail.upsert(CallDetailEntity(callId = "call-1", detailJson = "{}", detailFetchedAt = 1L))
        val repository = DefaultCallsRepository(TranscriptOnlyApi(turnPage("call-1", "Hello")), cache)

        // The page's transaction is held at its first statement. Everything the old code did before
        // the write is done by now, so the delete below lands exactly in the window it left open.
        transactions.holdFirstTask()
        val page = async(Dispatchers.IO) { runCatching { repository.transcript("call-1") } }
        assertTrue("the page reached its write", transactions.reached.await(5, TimeUnit.SECONDS))

        database.openHelper.writableDatabase.execSQL("DELETE FROM calls WHERE callId = 'call-1'")
        transactions.release.countDown()

        val result = page.await()
        assertNull("a call deleted mid-page is not a failure", result.exceptionOrNull())
        assertEquals("no turn is cached under a call that is gone", 0, transcriptRows())
    }

    private fun transcriptRows(): Int = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM call_transcript")
        .use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
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

/**
 * Holds the first task it is given until [release] opens: the exact point before a transaction's
 * first statement, with nothing of that transaction started yet.
 */
private class GatedTransactionExecutor : Executor {
    private val pool = Executors.newFixedThreadPool(2)
    private val armed = AtomicBoolean(false)
    private val first = AtomicBoolean(true)

    /** Opened when the held task arrives. */
    val reached = CountDownLatch(1)

    /** The held task runs once this opens. */
    val release = CountDownLatch(1)

    fun holdFirstTask() {
        first.set(true)
        armed.set(true)
    }

    override fun execute(command: Runnable) {
        if (armed.get() && first.compareAndSet(true, false)) {
            reached.countDown()
            assert(release.await(5, TimeUnit.SECONDS)) { "the test never released the held transaction" }
        }
        pool.execute(command)
    }
}

private fun turnPage(callId: String, text: String) = TranscriptPage(
    callId = callId,
    transcript = listOf(TranscriptTurn(sequence = 1, role = "agent", text = text, at = 2.0, finalTurn = true)),
    nextCursor = null,
    hasMore = false,
    transcriptComplete = true
)

/** The daemon answering one transcript page; nothing else in the API is used by these tests. */
private class TranscriptOnlyApi(private val page: TranscriptPage) : DaemonApi {
    override suspend fun transcript(callId: String, cursor: Long?, limit: Int?): ApiOutcome<TranscriptPage> = ApiOutcome.Ok(200, page)

    override suspend fun info(): ApiOutcome<DaemonInfo> = unused()

    override suspend fun status(): ApiOutcome<DaemonStatus> = unused()

    override suspend fun linkTest(seconds: Double?, codec: String?, transport: String?, bitrate: Int?): ApiOutcome<LinkTestResult> = unused()

    override suspend fun linkTests(limit: Int?): ApiOutcome<LinkTests> = unused()

    override suspend fun probe(candidates: List<String>?): ApiOutcome<ProbeResult> = unused()

    override suspend fun calls(limit: Int?, offset: Int?, before: Double?, outcome: String?, farEnd: String?): ApiOutcome<CallsPage> = unused()

    override suspend fun placeCall(request: PlaceCallRequest): ApiOutcome<CallSnapshot> = unused()

    override suspend fun validateCall(request: PlaceCallRequest): ApiOutcome<ValidationResult> = unused()

    override suspend fun testCall(request: TestCallRequest): ApiOutcome<CallSnapshot> = unused()

    override suspend fun call(callId: String): ApiOutcome<CallSnapshot> = unused()

    override suspend fun events(callId: String, since: Long?, waitSeconds: Double?): ApiOutcome<CallEventsPage> = unused()

    override suspend fun instruction(callId: String, text: String): ApiOutcome<InstructionResult> = unused()

    override suspend fun pause(callId: String): ApiOutcome<PauseResult> = unused()

    override suspend fun resume(callId: String): ApiOutcome<PauseResult> = unused()

    override suspend fun summarize(callId: String): ApiOutcome<SummaryResult> = unused()

    override suspend fun dtmf(callId: String, digits: String): ApiOutcome<DtmfResult> = unused()

    override suspend fun hangup(callId: String): ApiOutcome<CallSnapshot> = unused()

    override suspend fun marks(callId: String): ApiOutcome<MarksResponse> = unused()

    override suspend fun addMark(callId: String, mark: String, note: String?): ApiOutcome<MarksResponse> = unused()

    override suspend fun deleteCall(callId: String): ApiOutcome<DeleteCallResult> = unused()

    override suspend fun settings(): ApiOutcome<SettingsSnapshot> = unused()

    override suspend fun putSettings(values: JsonObject): ApiOutcome<SettingsUpdate> = unused()

    override suspend fun loopback(enabled: Boolean, callId: String?): ApiOutcome<LoopbackResult> = unused()

    override suspend fun tone(hz: Int?, dbfs: Double?, seconds: Double?, callId: String?): ApiOutcome<ToneResult> = unused()

    override suspend fun doctor(): ApiOutcome<DoctorReport> = unused()

    override suspend fun doctorFix(fix: String): ApiOutcome<DoctorFixResult> = unused()

    override suspend fun diagnostics(): ApiOutcome<JsonObject> = unused()

    override suspend fun createDiagnosticsBundle(): ApiOutcome<DiagnosticsBundle> = unused()

    override suspend fun updateManifest(channel: String?): ApiOutcome<JsonObject> = unused()

    override suspend fun compat(appApi: Int?, protocol: Int?): ApiOutcome<CompatReport> = unused()

    override suspend fun rotatePair(rotateDeviceToken: Boolean): ApiOutcome<RotateResult> = unused()

    override suspend fun downloadAudio(callId: String, format: String, sink: OutputStream): ApiResult = unused()

    override suspend fun downloadBundle(bundleId: String, sink: OutputStream): ApiResult = unused()

    override suspend fun downloadUpdateArtifact(name: String, sink: OutputStream): ApiResult = unused()

    private fun unused(): Nothing = error("not used by these tests")
}
