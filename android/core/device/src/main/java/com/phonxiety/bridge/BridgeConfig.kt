package com.phonxiety.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import com.phonxiety.bridge.net.CandidateMerge
import com.phonxiety.bridge.net.IrohSupport
import com.phonxiety.bridge.net.StoredCandidate
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * One paired daemon endpoint.
 *
 * An [Address] link is `wss://host:port/device`, optionally pinned to the
 * daemon certificate's SHA-256; an [Iroh] link carries the daemon's
 * `EndpointTicket`.  The bearer [token] is never logged or shown in the UI.
 */
sealed class BridgeConfig {
    abstract val token: String

    /** Narrower credential for the app API; null for a pairing made before app tokens existed. */
    abstract val appToken: String?
    abstract val kind: Kind

    enum class Kind { ADDRESS, IROH }

    data class Address(
        val url: String,
        override val token: String,
        val certSha256: String?,
        override val appToken: String? = null
    ) : BridgeConfig() {
        override val kind: Kind get() = Kind.ADDRESS

        /** The URL authority (`host:port`) without any userinfo. */
        val host: String
            get() {
                val rest = url.substring(SCHEME.length)
                val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
                val authority = if (end < 0) rest else rest.substring(0, end)
                return authority.substringAfterLast('@')
            }
    }

    data class Iroh(
        val ticket: String,
        override val token: String,
        override val appToken: String? = null,
        /** The daemon certificate pin, when the pairing carried one. */
        val certSha256: String? = null
    ) : BridgeConfig() {
        override val kind: Kind get() = Kind.IROH
    }

    companion object {
        const val SCHEME = "wss://"
        private const val PAIR_SCHEME = "phonxiety"
        private const val PAIR_HOST = "pair"
        private val CERT = Regex("^[0-9a-fA-F]{64}$")

        /** Applies the config-file and pairing-link rules for an address link. */
        fun validateAddress(url: String, token: String, certSha256: String?, appToken: String? = null): Address {
            if (!url.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) {
                throw BridgeConfigException(BridgeConfigError.URL_INVALID)
            }
            if (url.any { it.isWhitespace() || it.isISOControl() }) {
                throw BridgeConfigException(BridgeConfigError.URL_INVALID)
            }
            val authority = url.substring(SCHEME.length)
                .substringBefore('/').substringBefore('?').substringBefore('#')
            if (authority.isEmpty()) throw BridgeConfigException(BridgeConfigError.URL_INVALID)
            validateToken(token)
            if (certSha256 != null && !CERT.matches(certSha256)) {
                throw BridgeConfigException(BridgeConfigError.CERT_INVALID)
            }
            validateAppToken(appToken)
            return Address(
                url = url,
                token = token,
                certSha256 = certSha256?.lowercase(Locale.US),
                appToken = appToken?.trim()?.ifEmpty { null }
            )
        }

        /**
         * Structural rules for an iroh ticket; the ticket must additionally
         * parse with the bindings (`IrohSupport.endpointId`).
         */
        fun validateIroh(
            ticket: String,
            token: String,
            appToken: String? = null,
            certSha256: String? = null
        ): Iroh {
            if (ticket.isEmpty()) throw BridgeConfigException(BridgeConfigError.IROH_MISSING)
            if (ticket.any { it.isWhitespace() || it.isISOControl() }) {
                throw BridgeConfigException(BridgeConfigError.IROH_INVALID)
            }
            validateToken(token)
            validateAppToken(appToken)
            if (certSha256 != null && !CERT.matches(certSha256)) {
                throw BridgeConfigException(BridgeConfigError.CERT_INVALID)
            }
            return Iroh(
                ticket = ticket,
                token = token,
                appToken = appToken?.trim()?.ifEmpty { null },
                certSha256 = certSha256?.lowercase(Locale.US)
            )
        }

        fun isPairingHost(scheme: String?, host: String?): Boolean = scheme.equals(PAIR_SCHEME, ignoreCase = true) && host.equals(PAIR_HOST, ignoreCase = true)

        private fun validateToken(token: String) {
            if (token.isEmpty() || token.any { it.isWhitespace() || it.isISOControl() }) {
                throw BridgeConfigException(BridgeConfigError.TOKEN_INVALID)
            }
        }

        private fun validateAppToken(token: String?) {
            if (token == null) return
            if (token.isEmpty() || token.any { it.isWhitespace() || it.isISOControl() }) {
                throw BridgeConfigException(BridgeConfigError.TOKEN_INVALID)
            }
        }
    }
}

enum class BridgeConfigError(val code: String) {
    MISSING("config_missing"),
    UNREADABLE("config_unreadable"),
    INVALID_JSON("config_invalid"),
    KIND_INVALID("config_kind_invalid"),
    URL_INVALID("config_url_invalid"),
    TOKEN_INVALID("config_token_invalid"),
    CERT_INVALID("config_cert_invalid"),
    IROH_MISSING("config_iroh_missing"),
    IROH_INVALID("config_iroh_invalid"),
    WRITE_FAILED("config_write_failed"),
    PAIR_LINK_INVALID("pair_link_invalid"),
    PAIR_URL_MISSING("pair_url_missing"),
    PAIR_IROH_MISSING("pair_iroh_missing"),
    PAIR_IROH_INVALID("pair_iroh_invalid"),
    PAIR_TOKEN_MISSING("pair_token_missing")
}

class BridgeConfigException(val error: BridgeConfigError) : Exception(error.code) {
    val code: String get() = error.code
}

sealed class PairingResult {
    /**
     * [config] is the transport; [candidates] are the other addresses the QR
     * carried, already pinned to the daemon certificate.  A pairing link from
     * before those parameters existed simply has none.
     */
    data class Valid(
        val config: BridgeConfig,
        val candidates: List<StoredCandidate> = emptyList()
    ) : PairingResult()

    data class Invalid(val error: BridgeConfigError) : PairingResult()
}

/**
 * The `lan` and `tailscale` addresses a pairing carries besides its transport,
 * exactly as written in the pairing file or the QR.
 */
data class PairingAddresses(
    val lan: List<String> = emptyList(),
    val tailscale: List<String> = emptyList()
) {
    val all: List<String> get() = lan + tailscale

    val isEmpty: Boolean get() = lan.isEmpty() && tailscale.isEmpty()
}

/**
 * One read of the pairing file: the config and the addresses it carries beside
 * its transport, parsed from the same bytes and asking storage once.
 */
data class PairingRead(
    val config: BridgeConfig,
    val addresses: PairingAddresses
)

/**
 * Reads, writes and deletes the paired-daemon file in device-protected storage:
 *
 *   config.json
 *   {"url":"wss://192.168.1.10:18763/device","token":"...","cert_sha256":"..."}
 *   {"iroh":"<EndpointTicket>","token":"..."}
 *
 * The file is written atomically (temporary file, fsync, rename) with owner-only
 * mode.  The host pairing task may also write the file through a root shell, so the
 * loader tolerates the file appearing while the app runs.  The token is never
 * logged.
 */
object BridgeConfigLoader {
    private const val FILE_NAME = "config.json"
    private const val MAX_BYTES = 64 * 1024L

    /**
     * Serializes every read and write of the file, so a token update cannot tear a pairing change
     * and a caller never observes the identity and the addresses of two different pairings: the
     * host task writes the same file from outside the process, and only this monitor keeps the
     * app's own reads consistent with its writes.
     */
    private val lock = Any()

    private fun configFile(context: Context): File = File(AppStorage.deviceProtected(context).filesDir, FILE_NAME)

    /**
     * The pairing and its other addresses from one read of the file: the identity and the
     * addresses can never come from two different snapshots.
     */
    fun read(context: Context): PairingRead = synchronized(lock) {
        val json = document(context)
        PairingRead(
            config = configOf(json),
            addresses = addressesOf(json)
        )
    }

    fun readOrNull(context: Context): PairingRead? = try {
        read(context)
    } catch (_: BridgeConfigException) {
        null
    }

    fun load(context: Context): BridgeConfig = read(context).config

    fun loadOrNull(context: Context): BridgeConfig? = readOrNull(context)?.config

    /**
     * The addresses the pairing file carries besides its transport.  The host
     * pairing task writes them; the app's own QR path stores them in the
     * candidate document instead, so an empty answer is normal.  Only `wss://`
     * entries survive; the pin is applied by [candidates].
     */
    fun addresses(context: Context): PairingAddresses = synchronized(lock) {
        runCatching { addressesOf(document(context)) }.getOrDefault(PairingAddresses())
    }

    /**
     * The addresses inside one read of the file, for a caller that must not mix them with an
     * identity read at another moment.  An unreadable or invalid file has none.
     */
    private fun document(context: Context): JSONObject {
        val file = configFile(context)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_BYTES) {
            throw BridgeConfigException(BridgeConfigError.MISSING)
        }
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            throw BridgeConfigException(BridgeConfigError.UNREADABLE)
        }
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            throw BridgeConfigException(BridgeConfigError.INVALID_JSON)
        }
    }

    private fun configOf(json: JSONObject): BridgeConfig {
        val hasIroh = json.has("iroh") && !json.isNull("iroh")
        val hasUrl = json.has("url") && !json.isNull("url")
        if (hasIroh == hasUrl) throw BridgeConfigException(BridgeConfigError.KIND_INVALID)
        val token = json.optString("token", "").trim()
        val appToken = if (json.has("app_token") && !json.isNull("app_token")) json.optString("app_token") else null
        val hasCert = json.has("cert_sha256") && !json.isNull("cert_sha256")
        if (hasIroh) {
            return BridgeConfig.validateIroh(
                json.optString("iroh", "").trim(),
                token,
                appToken,
                if (hasCert) json.optString("cert_sha256") else null
            )
        }
        return BridgeConfig.validateAddress(
            url = json.optString("url", "").trim(),
            token = token,
            certSha256 = if (hasCert) json.optString("cert_sha256") else null,
            appToken = appToken
        )
    }

    private fun addressesOf(json: JSONObject): PairingAddresses = PairingAddresses(
        lan = addressArray(json, "lan"),
        tailscale = addressArray(json, "tailscale")
    )

    /**
     * The host-written addresses as validated, pinned candidates — the same
     * form the QR path produces, so both enter the store through one binding.
     */
    fun candidates(config: BridgeConfig, addresses: PairingAddresses): List<StoredCandidate> {
        val pin = when (config) {
            is BridgeConfig.Address -> config.certSha256
            is BridgeConfig.Iroh -> config.certSha256
        }
        return CandidateMerge.fromPairing(pin, addresses.all)
    }

    private fun addressArray(json: JSONObject, name: String): List<String> {
        val array = json.optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).trim().takeIf { it.startsWith(BridgeConfig.SCHEME, ignoreCase = true) }
        }.filter { it.isNotEmpty() }
    }

    /**
     * Validates and stores [config] atomically in device-protected storage.
     *
     * [addresses] are the pairing's other addresses.  The default is null, which
     * writes none: a new pairing (the QR path) must never inherit the addresses
     * of the daemon it replaced.  A writer that reloads a host-written pairing
     * and only changes a token hands the current addresses back explicitly
     * (see [updateAppToken]), so its own write does not drop what the host put
     * there.
     */
    fun write(
        context: Context,
        config: BridgeConfig,
        addresses: PairingAddresses? = null
    ): BridgeConfig = synchronized(lock) {
        val stored = when (config) {
            is BridgeConfig.Address ->
                BridgeConfig.validateAddress(config.url, config.token, config.certSha256, config.appToken)

            is BridgeConfig.Iroh -> BridgeConfig.validateIroh(
                config.ticket,
                config.token,
                config.appToken,
                config.certSha256
            )
        }
        val json = JSONObject().put("token", stored.token)
        if (stored.appToken != null) json.put("app_token", stored.appToken)
        when (stored) {
            is BridgeConfig.Address -> {
                json.put("url", stored.url)
                if (stored.certSha256 != null) json.put("cert_sha256", stored.certSha256)
            }

            is BridgeConfig.Iroh -> {
                json.put("iroh", stored.ticket)
                if (stored.certSha256 != null) json.put("cert_sha256", stored.certSha256)
            }
        }
        if (addresses != null) {
            if (addresses.lan.isNotEmpty()) json.put("lan", JSONArray(addresses.lan))
            if (addresses.tailscale.isNotEmpty()) json.put("tailscale", JSONArray(addresses.tailscale))
        }
        try {
            AppStorage.writeAtomically(
                configFile(context),
                json.toString().toByteArray(Charsets.UTF_8)
            )
        } catch (_: Exception) {
            throw BridgeConfigException(BridgeConfigError.WRITE_FAILED)
        }
        stored
    }

    /**
     * Stores [token] only while the pairing file still belongs to
     * [pairingIdentity].  The read, the identity check and the write happen
     * under one lock, so a pairing change cannot land between them and leave
     * one daemon's app token in the other daemon's file.
     */
    fun updateAppToken(context: Context, pairingIdentity: String, token: String): Boolean = synchronized(lock) {
        val current = loadOrNull(context) ?: return false
        if (CandidateMerge.identity(current) != pairingIdentity) return false
        val updated = when (current) {
            is BridgeConfig.Address -> current.copy(appToken = token)
            is BridgeConfig.Iroh -> current.copy(appToken = token)
        }
        write(context, updated, addresses(context))
        true
    }

    /**
     * The stored config exactly as it sits on disk, or null when there is none. A caller that must
     * undo its own [write] keeps this and hands it back to [restore]: the pairing store is also
     * written from outside the app, so only the bytes put it back the way it was.
     */
    fun snapshot(context: Context): ByteArray? = synchronized(lock) {
        if (loadOrNull(context) == null) return null
        runCatching { configFile(context).readBytes() }.getOrNull()
    }

    /**
     * Puts [snapshot] back byte for byte, or removes the file when there was none. A failed restore
     * removes the file instead: no config is better than the rejected one.
     */
    fun restore(context: Context, snapshot: ByteArray?): Boolean = synchronized(lock) {
        if (snapshot == null) return delete(context)
        runCatching {
            AppStorage.writeAtomically(configFile(context), snapshot)
            true
        }.getOrElse { delete(context) }
    }

    /** Removes the paired-daemon file; returns true when no config remains. */
    fun delete(context: Context): Boolean = synchronized(lock) {
        val file = configFile(context)
        runCatching { File(file.parentFile, "$FILE_NAME.tmp").delete() }
        var removed = true
        if (file.exists() && !file.delete()) removed = false
        removed
    }
}

/**
 * Parses `phonxiety://pair?iroh=<ticket>&token=<token>` and
 * `phonxiety://pair?url=<url>&token=<token>&cert=<64 hex>`, and applies the
 * same validation as [BridgeConfigLoader].  Both forms may carry a `cert` pin
 * and repeated `lan` / `tailscale` addresses; those become the pairing's
 * candidates.  An iroh ticket is additionally parsed by the bindings, so
 * [parse] must run off the main thread.  The link is not logged.
 */
object PairingLink {
    @SuppressLint("UseKtx")
    fun parse(link: String, ticketValid: (String) -> Boolean = { IrohSupport.ticketIsValid(it) }): PairingResult {
        val uri = try {
            Uri.parse(link)
        } catch (_: Exception) {
            return PairingResult.Invalid(BridgeConfigError.PAIR_LINK_INVALID)
        }
        if (!BridgeConfig.isPairingHost(uri.scheme, uri.host)) {
            return PairingResult.Invalid(BridgeConfigError.PAIR_LINK_INVALID)
        }
        val token = queryParameter(uri, "token")
        val appToken = queryParameter(uri, "app_token")
        val iroh = queryParameter(uri, "iroh")
        val url = queryParameter(uri, "url")
        val cert = queryParameter(uri, "cert")
        if (iroh == null && url == null) {
            return PairingResult.Invalid(BridgeConfigError.PAIR_LINK_INVALID)
        }
        if (iroh != null && url != null) {
            return PairingResult.Invalid(BridgeConfigError.PAIR_LINK_INVALID)
        }
        if (iroh != null) {
            if (iroh.isEmpty()) return PairingResult.Invalid(BridgeConfigError.PAIR_IROH_MISSING)
            if (token.isNullOrEmpty()) {
                return PairingResult.Invalid(BridgeConfigError.PAIR_TOKEN_MISSING)
            }
            return try {
                val config = BridgeConfig.validateIroh(iroh, token, appToken, cert)
                if (!ticketValid(config.ticket)) {
                    PairingResult.Invalid(BridgeConfigError.PAIR_IROH_INVALID)
                } else {
                    PairingResult.Valid(
                        config,
                        CandidateMerge.fromPairing(
                            config.certSha256,
                            queryParameters(uri, "lan") + queryParameters(uri, "tailscale")
                        )
                    )
                }
            } catch (e: BridgeConfigException) {
                PairingResult.Invalid(
                    if (e.error == BridgeConfigError.IROH_MISSING) {
                        BridgeConfigError.PAIR_IROH_MISSING
                    } else {
                        e.error
                    }
                )
            }
        }
        if (url.isNullOrEmpty()) return PairingResult.Invalid(BridgeConfigError.PAIR_URL_MISSING)
        if (token.isNullOrEmpty()) return PairingResult.Invalid(BridgeConfigError.PAIR_TOKEN_MISSING)
        return try {
            PairingResult.Valid(
                BridgeConfig.validateAddress(url, token, cert, appToken),
                CandidateMerge.fromPairing(
                    cert,
                    queryParameters(uri, "lan") + queryParameters(uri, "tailscale")
                )
            )
        } catch (e: BridgeConfigException) {
            PairingResult.Invalid(e.error)
        }
    }

    private fun queryParameter(uri: Uri, name: String): String? = try {
        uri.getQueryParameter(name)
    } catch (_: Exception) {
        null
    }

    private fun queryParameters(uri: Uri, name: String): List<String> = try {
        uri.getQueryParameters(name).orEmpty()
    } catch (_: Exception) {
        emptyList()
    }
}
