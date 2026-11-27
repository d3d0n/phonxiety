package com.phonxiety.bridge.telecom

import android.content.Context
import com.phonxiety.bridge.AppStorage
import com.phonxiety.bridge.BridgeLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable record of the bridge's dial ownership.
 *
 * The record is written to one private file, through a temporary file plus
 * fsync and an atomic rename, before [android.telecom.TelecomManager.placeCall]
 * runs.  It is rewritten only on control-event boundaries (dial, cancel,
 * termination), never from the audio path.
 *
 * The ledger is deliberately not a call log: it holds at most one pending
 * intent, a bounded recent-ID set and a bounded set of cancellation tombstones
 * so that a late Telecom [android.telecom.Call] can be recognised and dropped
 * after a process restart.
 */
internal class CallLedgerException(message: String) : Exception(message)

internal class CallLedger(context: Context, private val bootId: String) : CallStore {
    companion object {
        private const val TAG = "PhonxietyBridge"
        private const val FILE_NAME = "call-ledger.json"
        private const val VERSION = 1
        private const val MAX_RECENT = 32
        private const val MAX_ORPHANS = 4
        private const val MAX_DESTINATION_CHARS = 64
        private const val MAX_ID_CHARS = 128
    }

    data class Pending(
        val callId: String,
        val destination: String,
        val phoneAccount: String?,
        val createdWall: Long,
        val createdElapsed: Long,
        val cancelled: Boolean,
        val state: String
    )

    data class Recent(val callId: String, val destination: String, val state: String)

    data class Orphan(
        val callId: String,
        val destination: String,
        val dialStartedWall: Long,
        val expiresWall: Long
    )

    data class Snapshot(
        val pending: Pending?,
        val recent: List<Recent>,
        val orphans: List<Orphan>
    )

    private val file = File(AppStorage.deviceProtected(context).filesDir, FILE_NAME)

    private var state: Snapshot = load()

    @Synchronized
    override fun current(): Snapshot = state

    @Synchronized
    override fun reserve(
        callId: String,
        destination: String,
        phoneAccount: String?,
        createdWall: Long,
        createdElapsed: Long
    ) {
        val next = Snapshot(
            pending = Pending(
                callId = callId,
                destination = destination,
                phoneAccount = phoneAccount,
                createdWall = createdWall,
                createdElapsed = createdElapsed,
                cancelled = false,
                state = "dialing"
            ),
            recent = withRecent(state.recent, Recent(callId, destination, "dialing")),
            orphans = state.orphans
        )
        commit(next)
    }

    @Synchronized
    override fun markState(callId: String, callState: String) {
        val pending = state.pending ?: return
        if (pending.callId != callId || pending.state == callState) return
        commit(state.copy(pending = pending.copy(state = callState)))
    }

    @Synchronized
    override fun cancel(callId: String) {
        val pending = state.pending ?: return
        if (pending.callId != callId || pending.cancelled) return
        commit(state.copy(pending = pending.copy(cancelled = true)))
    }

    @Synchronized
    override fun finish(callId: String, destination: String, recentState: String) {
        val pending = state.pending
        val next = Snapshot(
            pending = if (pending != null && pending.callId == callId) null else pending,
            recent = withRecent(state.recent, Recent(callId, destination, recentState)),
            orphans = state.orphans
        )
        commit(next)
    }

    @Synchronized
    override fun orphan(
        callId: String,
        destination: String,
        dialStartedWall: Long,
        ttlMs: Long
    ) {
        val now = System.currentTimeMillis()
        val pending = state.pending
        val orphans = state.orphans
            .filter { it.expiresWall > now }
            .filterNot { it.callId == callId && it.dialStartedWall == dialStartedWall }
            .toMutableList()
        while (orphans.size >= MAX_ORPHANS) {
            orphans.removeAt(0)
        }
        orphans.add(
            Orphan(
                callId = callId,
                destination = destination,
                dialStartedWall = dialStartedWall,
                expiresWall = now + ttlMs
            )
        )
        val next = Snapshot(
            pending = if (pending != null && pending.callId == callId) null else pending,
            recent = withRecent(state.recent, Recent(callId, destination, "ended")),
            orphans = orphans
        )
        commit(next)
    }

    @Synchronized
    override fun consumeOrphan(callId: String, dialStartedWall: Long) {
        val remaining = state.orphans.filterNot {
            it.callId == callId && it.dialStartedWall == dialStartedWall
        }
        if (remaining.size == state.orphans.size) return
        commit(state.copy(orphans = remaining))
    }

    @Synchronized
    override fun pruneOrphans() {
        val now = System.currentTimeMillis()
        val remaining = state.orphans.filter { it.expiresWall > now }
        if (remaining.size == state.orphans.size) return
        commit(state.copy(orphans = remaining))
    }

    private fun withRecent(existing: List<Recent>, entry: Recent): List<Recent> {
        val merged = existing.filterNot { it.callId == entry.callId }.toMutableList()
        merged.add(entry)
        while (merged.size > MAX_RECENT) merged.removeAt(0)
        return merged
    }

    private fun commit(next: Snapshot) {
        write(next)
        state = next
    }

    private fun write(snapshot: Snapshot) {
        val bytes = encode(snapshot).toString().toByteArray(Charsets.UTF_8)
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("rename_failed")
            // Best effort: fsync the directory so the rename itself is durable.
            runCatching {
                file.parentFile?.let { directory ->
                    FileChannel.open(directory.toPath(), StandardOpenOption.READ)
                        .use { it.force(true) }
                }
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            BridgeLog.e(TAG, "call ledger write failed: ${e.javaClass.simpleName}")
            throw CallLedgerException("ledger_write_failed")
        }
    }

    private fun encode(snapshot: Snapshot): JSONObject {
        val pending = snapshot.pending
        val pendingJson = if (pending == null) {
            JSONObject.NULL
        } else {
            JSONObject()
                .put("call_id", pending.callId)
                .put("destination", pending.destination)
                .put("phone_account", pending.phoneAccount ?: JSONObject.NULL)
                .put("created_wall", pending.createdWall)
                .put("created_elapsed", pending.createdElapsed)
                .put("cancelled", pending.cancelled)
                .put("state", pending.state)
        }
        val recent = JSONArray()
        for (entry in snapshot.recent) {
            recent.put(
                JSONObject()
                    .put("call_id", entry.callId)
                    .put("destination", entry.destination)
                    .put("state", entry.state)
            )
        }
        val orphans = JSONArray()
        for (entry in snapshot.orphans) {
            orphans.put(
                JSONObject()
                    .put("call_id", entry.callId)
                    .put("destination", entry.destination)
                    .put("dial_started_wall", entry.dialStartedWall)
                    .put("expires_wall", entry.expiresWall)
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("boot_id", bootId)
            .put("pending", pendingJson)
            .put("recent", recent)
            .put("orphans", orphans)
    }

    private fun load(): Snapshot {
        if (!file.exists()) return Snapshot(null, emptyList(), emptyList())
        if (!file.isFile || file.length() <= 0L) throw CallLedgerException("ledger_invalid")
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            BridgeLog.e(TAG, "call ledger unreadable: ${e.javaClass.simpleName}")
            throw CallLedgerException("ledger_unreadable")
        }
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw CallLedgerException("ledger_invalid")
        }
        if (json.optInt("version", -1) != VERSION) {
            throw CallLedgerException("ledger_version_unsupported")
        }

        val recent = ArrayList<Recent>()
        json.optJSONArray("recent")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val callId = sanitizeId(item.optString("call_id", "")) ?: continue
                val destination = sanitizeDestination(item.optString("destination", "")) ?: continue
                val entryState = item.optString("state", "ended").ifEmpty { "ended" }
                recent.add(Recent(callId, destination, entryState))
            }
        }
        val boundedRecent = if (recent.size > MAX_RECENT) recent.takeLast(MAX_RECENT) else recent

        val orphans = ArrayList<Orphan>()
        val now = System.currentTimeMillis()
        json.optJSONArray("orphans")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val callId = sanitizeId(item.optString("call_id", "")) ?: continue
                val destination = sanitizeDestination(item.optString("destination", "")) ?: continue
                val started = item.optLong("dial_started_wall", 0L)
                val expires = item.optLong("expires_wall", 0L)
                if (started <= 0L || expires <= now) continue
                orphans.add(Orphan(callId, destination, started, expires))
                if (orphans.size >= MAX_ORPHANS) break
            }
        }

        val pending = parsePending(json.opt("pending"))

        if (json.optString("boot_id", "") != bootId) {
            // A reboot ends every Telecom call.  Drop live ownership and
            // cancellation tombstones; keep the recent-ID set so a repeated
            // call_id is still refused after a restart.  Non-terminal states
            // can no longer be trusted, so they become terminal.
            BridgeLog.i(TAG, "call ledger from a previous boot; dropping pending intent")
            val normalizedRecent = boundedRecent.map { entry ->
                if (entry.state == "failed" || entry.state == "ended") {
                    entry
                } else {
                    entry.copy(state = "ended")
                }
            }
            val normalized = Snapshot(null, normalizedRecent, emptyList())
            runCatching { write(normalized) }
            return normalized
        }

        return Snapshot(pending, boundedRecent, orphans)
    }

    private fun parsePending(raw: Any?): Pending? {
        if (raw == null || raw === JSONObject.NULL) return null
        val item = raw as? JSONObject ?: throw CallLedgerException("ledger_pending_invalid")
        val callId = sanitizeId(item.optString("call_id", ""))
            ?: throw CallLedgerException("ledger_pending_invalid")
        val destination = sanitizeDestination(item.optString("destination", ""))
            ?: throw CallLedgerException("ledger_pending_invalid")
        val createdWall = item.optLong("created_wall", 0L)
        val createdElapsed = item.optLong("created_elapsed", 0L)
        if (createdWall <= 0L || createdElapsed <= 0L) throw CallLedgerException("ledger_pending_invalid")
        val account = if (item.isNull("phone_account")) {
            null
        } else {
            item.optString("phone_account", "").takeIf { it.isNotEmpty() }
        }
        return Pending(
            callId = callId,
            destination = destination,
            phoneAccount = account,
            createdWall = createdWall,
            createdElapsed = createdElapsed,
            cancelled = item.optBoolean("cancelled", false),
            state = sanitizeState(item.optString("state", "dialing"))
        )
    }

    private fun sanitizeState(value: String): String = when (value) {
        "dialing", "active", "ending", "ended" -> value
        else -> throw CallLedgerException("ledger_state_invalid")
    }

    private fun sanitizeId(value: String): String? {
        if (value.isEmpty() || value.length > MAX_ID_CHARS) return null
        if (value.any { it.isWhitespace() }) return null
        return value
    }

    private fun sanitizeDestination(value: String): String? {
        if (value.isEmpty() || value.length > MAX_DESTINATION_CHARS) return null
        return value
    }
}
