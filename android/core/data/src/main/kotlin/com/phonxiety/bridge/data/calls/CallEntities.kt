package com.phonxiety.bridge.data.calls

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.CallSummary
import com.phonxiety.bridge.model.TranscriptTurn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One call as the list needs it: the daemon's summary fields and nothing else. The full record
 * lives in [CallDetailEntity], so scrolling and filtering history never read a record's JSON.
 * Every filter and sort column carries an index: the list is a SQL query, not an in-memory pass.
 */
@Entity(
    tableName = "calls",
    indices = [
        Index("callOutcome"),
        Index("farEnd"),
        Index("createdAt"),
        Index("cachedAt")
    ]
)
data class CallSummaryEntity(
    @PrimaryKey val callId: String,
    val state: String?,
    val destination: String?,
    val goal: String?,
    val callOutcome: String?,
    val taskOutcome: String?,
    val resultStatus: String?,
    val summary: String?,
    val error: String?,
    val farEnd: String?,
    val createdAt: Double?,
    val connectedAt: Double?,
    val endedAt: Double?,
    val durationSeconds: Double?,
    val marks: Int,
    val hasRecording: Boolean,
    val live: Boolean,
    val cachedAt: Long
)

/**
 * The opened call's full record, kept as the daemon's JSON so the detail screen renders with no
 * link. One row per call, removed with its summary by [ForeignKey.CASCADE].
 */
@Entity(
    tableName = "call_detail",
    foreignKeys = [
        ForeignKey(
            entity = CallSummaryEntity::class,
            parentColumns = ["callId"],
            childColumns = ["callId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class CallDetailEntity(
    @PrimaryKey val callId: String,
    val detailJson: String?,
    val detailFetchedAt: Long?
)

/**
 * One transcript turn, cached per call so a paged transcript survives the process. Deleting the
 * call's detail row removes every turn with it; nothing else has to remember the second table.
 */
@Entity(
    tableName = "call_transcript",
    primaryKeys = ["callId", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = CallDetailEntity::class,
            parentColumns = ["callId"],
            childColumns = ["callId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TranscriptEntity(
    val callId: String,
    val sequence: Long,
    val role: String?,
    val text: String?,
    val at: Double?,
    val finalTurn: Boolean
)

fun CallSummaryEntity.toSummary(): CallSummary = CallSummary(
    callId = callId,
    state = state,
    destination = destination,
    goal = goal,
    callOutcome = callOutcome,
    taskOutcome = taskOutcome,
    resultStatus = resultStatus,
    summary = summary,
    error = error,
    farEnd = farEnd,
    createdAt = createdAt,
    connectedAt = connectedAt,
    endedAt = endedAt,
    durationSeconds = durationSeconds,
    marks = marks,
    hasRecording = hasRecording,
    live = live
)

fun TranscriptEntity.toTurn(): TranscriptTurn = TranscriptTurn(
    sequence = sequence,
    role = role,
    text = text,
    at = at,
    finalTurn = finalTurn
)

fun TranscriptTurn.toEntity(callId: String): TranscriptEntity = TranscriptEntity(
    callId = callId,
    sequence = sequence,
    role = role,
    text = text,
    at = at,
    finalTurn = finalTurn ?: true
)

/** A summary page entry, merged into whatever the detail screen already stored. */
fun CallSummary.toEntity(existing: CallSummaryEntity?, now: Long): CallSummaryEntity = CallSummaryEntity(
    callId = callId,
    state = state ?: existing?.state,
    destination = destination ?: existing?.destination,
    goal = goal ?: existing?.goal,
    callOutcome = callOutcome ?: existing?.callOutcome,
    taskOutcome = taskOutcome ?: existing?.taskOutcome,
    resultStatus = resultStatus ?: existing?.resultStatus,
    summary = summary ?: existing?.summary,
    error = error ?: existing?.error,
    farEnd = farEnd ?: existing?.farEnd,
    createdAt = createdAt ?: existing?.createdAt,
    connectedAt = connectedAt ?: existing?.connectedAt,
    endedAt = endedAt ?: existing?.endedAt,
    durationSeconds = durationSeconds ?: existing?.durationSeconds,
    marks = marks,
    hasRecording = hasRecording || existing?.hasRecording == true,
    live = live,
    cachedAt = now
)

/** A full record: the JSON is what the detail screen decodes, the columns feed the list. */
fun CallSnapshot.toEntity(existing: CallSummaryEntity?, now: Long): CallSummaryEntity {
    val request = request ?: JsonObject(emptyMap())
    val destination = destination ?: request.string("destination") ?: existing?.destination
    val goal = request.string("goal") ?: existing?.goal
    return CallSummaryEntity(
        callId = callId,
        state = state ?: existing?.state,
        destination = destination,
        goal = goal,
        callOutcome = callOutcome ?: existing?.callOutcome,
        taskOutcome = taskOutcome ?: existing?.taskOutcome,
        resultStatus = resultStatus ?: existing?.resultStatus,
        summary = summary ?: existing?.summary,
        error = error ?: existing?.error,
        farEnd = farEnd ?: existing?.farEnd,
        createdAt = createdAt ?: existing?.createdAt,
        connectedAt = connectedAt ?: existing?.connectedAt,
        endedAt = endedAt ?: existing?.endedAt,
        durationSeconds = durationSeconds(existing),
        marks = marks.size,
        hasRecording = hasRecording(existing),
        live = live != null,
        cachedAt = now
    )
}

private fun CallSnapshot.durationSeconds(existing: CallSummaryEntity?): Double? {
    val ended = endedAt
    val connected = connectedAt
    if (ended == null || connected == null) return existing?.durationSeconds
    return ((ended - connected).coerceAtLeast(0.0) * 10).toLong() / 10.0
}

/** A recording exists when the archive names a file, either the web copy or the master. */
private fun CallSnapshot.hasRecording(existing: CallSummaryEntity?): Boolean {
    val descriptor = recording ?: return existing?.hasRecording == true
    val compressed = descriptor["compressed"] as? JsonObject
    val candidates = listOf(descriptor["file"], compressed?.get("file"))
    return candidates.any { (it as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true } ||
        existing?.hasRecording == true
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
