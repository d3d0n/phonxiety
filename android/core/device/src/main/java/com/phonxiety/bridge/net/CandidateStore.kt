package com.phonxiety.bridge.net

import android.content.Context
import com.phonxiety.bridge.AppStorage
import com.phonxiety.bridge.BridgeLog
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One address this phone may reach the paired daemon at, other than the
 * paired transport itself.
 *
 * [certSha256] is the pin the daemon's TLS certificate must match; a candidate
 * without one is never dialled, because a WebSocket target learned from an
 * unauthenticated source (mDNS, the internet) could otherwise collect the
 * device token.
 */
@Serializable
data class StoredCandidate(
    val url: String,
    @SerialName("cert_sha256") val certSha256: String? = null,
    val source: String = CandidateStore.Source.LEARNED
)

/** True when this address is in the tailnet, i.e. reached through the Tailscale app. */
val StoredCandidate.isTailnet: Boolean
    get() = Tailnet.isTailnetHost(Tailnet.hostOf(url))

/** The candidate document as stored on disk. */
@Serializable
private data class CandidateDocument(val candidates: List<StoredCandidate> = emptyList())

/**
 * The address candidates learned for one pairing, in device-protected storage.
 *
 * They come from the daemon's own `/app/v1/info` (`lan` and `tailscale`) and
 * from mDNS advertisements whose certificate matches the pairing pin.  The
 * engine reads them before an address link attempt and the settings screen
 * refreshes them; nothing here is authoritative on its own — [CandidatePlanner]
 * filters by mode and a candidate is only used once it answers.
 */
class CandidateStore(private val file: File) {
    fun load(): List<StoredCandidate> = try {
        if (!file.isFile || file.length() > MAX_BYTES) {
            emptyList()
        } else {
            json.decodeFromString(CandidateDocument.serializer(), file.readText(Charsets.UTF_8)).candidates
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** Replaces the stored set, de-duplicated by URL; a failed write keeps the old set. */
    fun write(candidates: List<StoredCandidate>): Boolean {
        val clean = dedupe(candidates)
        return try {
            file.parentFile?.mkdirs()
            AppStorage.writeAtomically(file, json.encodeToString(CandidateDocument.serializer(), CandidateDocument(clean)).toByteArray(Charsets.UTF_8))
            true
        } catch (e: Exception) {
            BridgeLog.w(TAG, "could not store link candidates: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Adds [learned] to the stored set and returns the result. */
    fun merge(learned: List<StoredCandidate>): List<StoredCandidate> {
        val merged = dedupe(load() + learned)
        write(merged)
        return merged
    }

    /** Where a stored address came from; shown in the settings screen and used in logs. */
    object Source {
        const val PAIRED = "pairing"
        const val LEARNED = "daemon"
        const val MDNS = "mdns"
    }

    companion object {
        private const val TAG = "PhonxietyBridge"
        private const val FILE_NAME = "link-candidates.json"
        private const val MAX_BYTES = 64 * 1024L

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        @Volatile private var instance: CandidateStore? = null

        fun of(context: Context): CandidateStore = instance ?: synchronized(this) {
            instance ?: CandidateStore(File(AppStorage.deviceProtected(context).filesDir, FILE_NAME)).also { instance = it }
        }

        /** The last entry per URL wins; a `wss://` URL without a pin is dropped. */
        fun dedupe(candidates: List<StoredCandidate>): List<StoredCandidate> {
            val byUrl = LinkedHashMap<String, StoredCandidate>()
            for (candidate in candidates) {
                val url = candidate.url.trim()
                if (!url.startsWith(URL_SCHEME, ignoreCase = true)) continue
                if (candidate.certSha256.isNullOrBlank()) continue
                byUrl[url] = candidate.copy(url = url)
            }
            return byUrl.values.toList()
        }

        private const val URL_SCHEME = "wss://"
    }
}
