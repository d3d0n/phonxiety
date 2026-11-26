package com.phonxiety.bridge.net

import android.content.Context
import com.phonxiety.bridge.AppStorage
import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.model.ConnectionInfo
import java.io.File
import java.util.Locale
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
private data class CandidateDocument(
    val candidates: List<StoredCandidate> = emptyList(),
    /** Which pairing these addresses belong to; an older document has none. */
    val pairing: String? = null
)

/**
 * The address candidates learned for one pairing, in device-protected storage.
 *
 * They come from the pairing QR, from the daemon's own `/app/v1/info` (`lan`
 * and `tailscale`) — the engine asks after every hello and the settings screen
 * can refresh on demand — and from mDNS advertisements whose certificate
 * matches the pairing pin.  Each document is bound to the pairing that learned
 * it.  Nothing here is authoritative on its own: [CandidatePlanner] filters by
 * mode and a candidate is only used once it answers.
 */
class CandidateStore(private val file: File) {
    fun load(): List<StoredCandidate> = document().candidates

    /** The pairing the stored addresses belong to, or null for a document from before identities. */
    fun pairingIdentity(): String? = document().pairing

    private fun document(): CandidateDocument = try {
        if (!file.isFile || file.length() > MAX_BYTES) {
            CandidateDocument()
        } else {
            json.decodeFromString(CandidateDocument.serializer(), file.readText(Charsets.UTF_8))
        }
    } catch (_: Exception) {
        CandidateDocument()
    }

    private fun writeDocument(pairing: String?, candidates: List<StoredCandidate>): Boolean = try {
        file.parentFile?.mkdirs()
        val clean = CandidateStore.dedupe(candidates)
        AppStorage.writeAtomically(
            file,
            json.encodeToString(
                CandidateDocument.serializer(),
                CandidateDocument(clean, pairing)
            ).toByteArray(Charsets.UTF_8)
        )
        true
    } catch (e: Exception) {
        BridgeLog.w(TAG, "could not store link candidates: ${e.javaClass.simpleName}")
        false
    }

    /** Adds [learned] to the stored set and returns the result; the pairing identity stays. */
    fun merge(learned: List<StoredCandidate>): List<StoredCandidate> {
        val current = document()
        val merged = CandidateStore.dedupe(current.candidates + learned)
        writeDocument(current.pairing, merged)
        return merged
    }

    /**
     * Makes [pairing] the document's identity.  A different pairing's
     * addresses are discarded in one atomic write, so no address learned for
     * one daemon can be dialled with another daemon's token.
     */
    fun bind(pairing: String): List<StoredCandidate> {
        val current = document()
        if (current.pairing == pairing) return current.candidates
        writeDocument(pairing, emptyList())
        return emptyList()
    }

    /** [bind], then merge this pairing's own addresses (the QR's) in the same write. */
    fun rebind(pairing: String, candidates: List<StoredCandidate>): List<StoredCandidate> {
        val current = document()
        val next = if (current.pairing == pairing) {
            CandidateStore.dedupe(current.candidates + candidates)
        } else {
            CandidateStore.dedupe(candidates)
        }
        writeDocument(pairing, next)
        return next
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

/**
 * The one place a set of addresses becomes stored candidates: the QR pairing
 * path, the engine's own refresh after a hello and the settings refresh all
 * call it, so every stored address carries the same pin and source.
 */
object CandidateMerge {
    private val PIN = Regex("^[0-9a-fA-F]{64}$")

    /** The other addresses a pairing QR carried; empty without a certificate pin. */
    fun fromPairing(certificate: String?, urls: List<String>): List<StoredCandidate> {
        val pin = certificate?.takeIf { PIN.matches(it) }?.lowercase(Locale.US) ?: return emptyList()
        return CandidateStore.dedupe(
            urls.map { StoredCandidate(it.trim(), pin, CandidateStore.Source.PAIRED) }
        )
    }

    /** The daemon's `lan` and `tailscale` addresses from `/app/v1/info`; needs its pin. */
    fun learned(connection: ConnectionInfo?): List<StoredCandidate> {
        val pin = connection?.certificate?.takeIf { PIN.matches(it) }?.lowercase(Locale.US)
            ?: return emptyList()
        return CandidateStore.dedupe(
            (connection.lan + connection.tailscale)
                .map { StoredCandidate(it.trim(), pin, CandidateStore.Source.LEARNED) }
        )
    }

    /** Stores [connection]'s addresses and returns the whole set. */
    fun store(store: CandidateStore, connection: ConnectionInfo?): List<StoredCandidate> = store.merge(learned(connection))

    /**
     * What identifies the paired daemon across token rotations and refreshes:
     * its certificate pin, or the transport when the pairing predates pins.
     * A different identity means every stored address belongs to another
     * daemon and must not be dialled with this pairing's token.
     */
    fun identity(config: BridgeConfig): String = when (config) {
        is BridgeConfig.Address -> config.certSha256?.let { "cert:$it" } ?: "url:${config.url}"
        is BridgeConfig.Iroh -> config.certSha256?.let { "cert:$it" } ?: "iroh:${config.ticket}"
    }
}
