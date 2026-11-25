package com.phonxiety.bridge.data.calls

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.CallSummary
import com.phonxiety.bridge.model.TranscriptTurn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One call as the list and the detail screen need it: the daemon's summary fields plus the last
 * full record, kept as JSON so an opened call still renders with no link. A summary refresh never
 * blanks a value the detail record already filled in.
 */
@Entity(tableName = "calls")
data class CallEntity(
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
    val detailJson: String?,
    val detailFetchedAt: Long?,
    val cachedAt: Long
)

/** One transcript turn, cached per call so a paged transcript survives the process. */
@Entity(tableName = "call_transcript", primaryKeys = ["callId", "sequence"])
data class TranscriptEntity(
    val callId: String,
    val sequence: Long,
    val role: String?,
    val text: String?,
    val at: Double?,
    val finalTurn: Boolean
)

fun CallEntity.toSummary(): CallSummary = CallSummary(
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
fun CallSummary.toEntity(existing: CallEntity?, now: Long): CallEntity = CallEntity(
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
    detailJson = existing?.detailJson,
    detailFetchedAt = existing?.detailFetchedAt,
    cachedAt = now
)

/** A full record: the JSON is what the detail screen decodes, the columns feed the list. */
fun CallSnapshot.toEntity(existing: CallEntity?, detailJson: String, now: Long): CallEntity {
    val request = request ?: JsonObject(emptyMap())
    val destination = destination ?: request.string("destination") ?: existing?.destination
    val goal = request.string("goal") ?: existing?.goal
    return CallEntity(
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
        detailJson = detailJson,
        detailFetchedAt = now,
        cachedAt = now
    )
}

private fun CallSnapshot.durationSeconds(existing: CallEntity?): Double? {
    val ended = endedAt
    val connected = connectedAt
    if (ended == null || connected == null) return existing?.durationSeconds
    return ((ended - connected).coerceAtLeast(0.0) * 10).toLong() / 10.0
}

/** A recording exists when the archive names a file, either the web copy or the master. */
private fun CallSnapshot.hasRecording(existing: CallEntity?): Boolean {
    val descriptor = recording ?: return existing?.hasRecording == true
    val compressed = descriptor["compressed"] as? JsonObject
    val candidates = listOf(descriptor["file"], compressed?.get("file"))
    return candidates.any { (it as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true } ||
        existing?.hasRecording == true
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
