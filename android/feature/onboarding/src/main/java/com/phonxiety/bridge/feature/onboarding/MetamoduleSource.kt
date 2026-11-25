package com.phonxiety.bridge.feature.onboarding

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Pinned KernelSU metamodule: the exact archive the wizard installs and verifies. */
const val METAMODULE_URL: String =
    "https://github.com/KernelSU-Modules-Repo/meta-overlayfs/releases/download/v1.3.1/meta-overlayfs-13100-1.3.1.zip"

/** SHA-256 of [METAMODULE_URL], read from the GitHub release API. */
const val METAMODULE_SHA256: String = "279d85a6a35724dfcf8aa1137a9630c7e989e154b94543517c539ee70f9b8811"

/** Outcome of obtaining the metamodule archive; a mismatch is a failure, never a warning. */
sealed interface MetamoduleResult {
    data class Ready(val file: File) : MetamoduleResult

    data class Failed(val reason: String) : MetamoduleResult
}

/** Obtains the pinned metamodule archive; injectable so the SHA-256 path is unit-tested. */
fun interface MetamoduleSource {
    suspend fun zip(): MetamoduleResult
}

/** Bytes for one URL; production is [OkHttpFetcher], tests pass a lambda. */
fun interface HttpFetcher {
    suspend fun get(url: String): ByteArray
}

class OkHttpFetcher(
    private val client: OkHttpClient = OkHttpClient()
) : HttpFetcher {
    override suspend fun get(url: String): ByteArray = withContext(Dispatchers.IO) {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            require(it.isSuccessful) { "HTTP ${it.code} from $url" }
            val body = requireNotNull(it.body) { "empty body from $url" }
            body.bytes()
        }
    }
}

/**
 * Downloads the pinned meta-overlayfs zip into the app cache and refuses it unless its SHA-256
 * matches [expectedSha256]. A cached copy that still matches is reused, so a retry after a provider
 * failure does not download again.
 */
class HttpMetamoduleSource(
    private val cacheDir: File,
    private val fetcher: HttpFetcher = OkHttpFetcher(),
    private val url: String = METAMODULE_URL,
    private val expectedSha256: String = METAMODULE_SHA256
) : MetamoduleSource {
    override suspend fun zip(): MetamoduleResult {
        val cached = File(cacheDir, CACHE_NAME)
        if (cached.isFile && sha256Hex(cached.readBytes()).equals(expectedSha256, ignoreCase = true)) {
            return MetamoduleResult.Ready(cached)
        }
        val bytes = try {
            fetcher.get(url)
        } catch (error: Exception) {
            return MetamoduleResult.Failed(
                "the metamodule download failed: ${error.message ?: "no reason given"}"
            )
        }
        val actual = sha256Hex(bytes)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            return MetamoduleResult.Failed(
                "meta-overlayfs SHA-256 mismatch: expected $expectedSha256, got $actual; " +
                    "refusing to install an unverified zip"
            )
        }
        cacheDir.mkdirs()
        val temporary = File(cacheDir, "$CACHE_NAME.part")
        val destination = File(cacheDir, CACHE_NAME)
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(destination)) {
            destination.delete()
            if (!temporary.renameTo(destination)) {
                return MetamoduleResult.Failed("the metamodule zip could not be written to ${cacheDir.path}")
            }
        }
        return MetamoduleResult.Ready(destination)
    }

    private companion object {
        const val CACHE_NAME = "meta-overlayfs-13100-1.3.1.zip"
    }
}

/** Lowercase hex SHA-256 of [bytes]; exposed for the integrity test. */
internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
