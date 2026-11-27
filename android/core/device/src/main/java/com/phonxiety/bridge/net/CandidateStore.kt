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
    /** Which pairing these addresses belong to. */
    val pairing: String
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
    /** One document read: the addresses and the pairing they belong to, never separately. */
    data class Snapshot(val candidates: List<StoredCandidate>, val pairing: String?)

    // Every read and write goes through one monitor, so two writers cannot
    // interleave a read of the old identity with a write of the new one.
    private val lock = Any()

    /** The addresses and the pairing identity from one read. */
    fun snapshot(): Snapshot = synchronized(lock) {
        val document = document() ?: return Snapshot(emptyList(), null)
        Snapshot(document.candidates, document.pairing)
    }

    fun load(): List<StoredCandidate> = snapshot().candidates

    /** The pairing the stored addresses belong to, or null when nothing is stored. */
    fun pairingIdentity(): String? = snapshot().pairing

    private fun document(): CandidateDocument? = try {
        if (!file.isFile || file.length() > MAX_BYTES) {
            null
        } else {
            json.decodeFromString(CandidateDocument.serializer(), file.readText(Charsets.UTF_8))
        }
    } catch (_: Exception) {
        null
    }

    private fun writeDocument(pairing: String, candidates: List<StoredCandidate>): Boolean = try {
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
        BridgeLog.w(TAG) { "could not store link candidates: ${e.javaClass.simpleName}" }
        false
    }

    /**
     * Adds [learned] to the document only while it still belongs to
     * [expected].  Null means the answer was not stored: the store has been
     * rebound to another pairing, no binding ever succeeded, or the write
     * failed.  A late answer from the previous daemon therefore never becomes
     * an address of the current one.
     */
    fun merge(expected: String, learned: List<StoredCandidate>): List<StoredCandidate>? = synchronized(lock) {
        val current = document() ?: return null
        if (current.pairing != expected) return null
        val merged = CandidateStore.dedupe(current.candidates + learned)
        if (!writeDocument(current.pairing, merged)) return null
        merged
    }

    /**
     * Makes [pairing] the document's identity.  A different pairing's
     * addresses are discarded in one atomic write, so no address learned for
     * one daemon can be dialled with another daemon's token.  Null means the
     * binding could not be written; callers must not use any learned address
     * until a later binding succeeds.
     */
    fun bind(pairing: String): Snapshot? = synchronized(lock) {
        val current = document()
        if (current != null && current.pairing == pairing) return Snapshot(current.candidates, pairing)
        if (!writeDocument(pairing, emptyList())) return null
        Snapshot(emptyList(), pairing)
    }

    /** [bind], then merge this pairing's own addresses (the QR's) in the same write. */
    fun rebind(pairing: String, candidates: List<StoredCandidate>): Snapshot? = synchronized(lock) {
        val current = document()
        val next = if (current != null && current.pairing == pairing) {
            CandidateStore.dedupe(current.candidates + candidates)
        } else {
            CandidateStore.dedupe(candidates)
        }
        if (!writeDocument(pairing, next)) return null
        Snapshot(next, pairing)
    }

    /** Where a stored address came from; shown in the settings screen and used in logs. */
    object Source {
        const val PAIRED = "pairing"
        const val LEARNED = "daemon"
        const val MDNS = "mdns"
    }

    companion object {
        private const val TAG = "Bridge:candidates"
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
 * path, the host-written pairing file, the engine's own refresh and the
 * settings refresh all call it, so every stored address carries the same pin
 * and source.
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

    /**
     * Stores [connection]'s addresses for [identity]; null when the store has
     * been rebound to another pairing and the answer no longer applies.
     */
    fun store(store: CandidateStore, connection: ConnectionInfo?, identity: String): List<StoredCandidate>? = store.merge(identity, learned(connection))

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
