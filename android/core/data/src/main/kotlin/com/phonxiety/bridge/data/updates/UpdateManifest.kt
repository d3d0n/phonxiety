package com.phonxiety.bridge.data.updates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The payload schema of a module release. Bumped when the files inside the module zip or the
 * privapp allowlist change shape; the app only applies a release whose schema it understands.
 */
const val MODULE_SCHEMA_VERSION: Int = 1

/**
 * One update document for everything the phone runs.
 *
 * The top-level fields are the Magisk/KernelSU `updateJson` contract (`version`, `versionCode`,
 * `zipUrl`, `changelog`), so the root manager offers the same module release the app does.
 * Everything else is this project's extension:
 *
 * - [apk] — the sideloaded app: where to get it, its SHA-256 and the SHA-256 of the signing
 *   certificate the installed app must already trust;
 * - [module] — the module zip's own SHA-256, the payload schema and the privileged permissions it
 *   grants (a permission the installed app does not hold means the update needs a reboot);
 * - [daemon] — the versions this release speaks, checked against the daemon through `/compat`.
 */
@Serializable
data class UpdateManifest(
    val channel: String = CHANNEL_STABLE,
    val version: String,
    val versionCode: Long,
    val zipUrl: String,
    val changelog: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("updateJsonUrl") val updateJsonUrl: String? = null,
    val apk: ApkRelease,
    val module: ModuleRelease,
    val daemon: DaemonRelease
) {
    companion object {
        const val CHANNEL_STABLE: String = "stable"
    }
}

/** The app update: a signed APK the phone installs over itself. */
@Serializable
data class ApkRelease(
    val version: String,
    val versionCode: Long,
    /** `https://…` or [DAEMON_URL_APK]; a daemon source names its file in [file]. */
    val url: String,
    val sha256: String,
    val size: Long = 0,
    @SerialName("signingCertSha256") val signingCertSha256: String,
    @SerialName("minSdk") val minSdk: Int = 34,
    /** Basename of the artifact under `<state dir>/updates/` when [url] is a daemon source. */
    val file: String? = null
)

/** The module update: the same zip the root manager receives through `updateJson`. */
@Serializable
data class ModuleRelease(
    val version: String,
    val versionCode: Long,
    /** Where the root manager downloads the zip: always https, its `updateJson` contract. */
    val zipUrl: String,
    val sha256: String,
    val size: Long = 0,
    @SerialName("schemaVersion") val schemaVersion: Int = MODULE_SCHEMA_VERSION,
    @SerialName("requiresReboot") val requiresReboot: Boolean = true,
    val permissions: List<String> = emptyList(),
    /** The app-facing source of the same zip: `https://…` or [DAEMON_URL_MODULE]. */
    val url: String? = null,
    /** Basename of the artifact under `<state dir>/updates/` when [url] is a daemon source. */
    val file: String? = null
)

/** What the daemon must speak for this release to work end to end. */
@Serializable
data class DaemonRelease(
    @SerialName("minVersion") val minVersion: String,
    val protocol: Int,
    @SerialName("appApi") val appApi: Int,
    @SerialName("moduleSchema") val moduleSchema: Int = MODULE_SCHEMA_VERSION
)

sealed interface ManifestParse {
    data class Valid(val manifest: UpdateManifest) : ManifestParse

    /** [reason] is a stable code (`manifest_invalid`); [detail] is for the screen's evidence. */
    data class Invalid(val reason: String, val detail: String? = null) : ManifestParse
}

/** Marker URLs for artifacts the daemon itself serves; the real name is in `file`. */
const val DAEMON_URL_APK: String = "daemon:apk"
const val DAEMON_URL_MODULE: String = "daemon:module"

/** Where the bytes of one release artifact come from. */
sealed interface ReleaseSource {
    data class Https(val url: String) : ReleaseSource

    /**
     * The daemon's own `updates/` directory over the authenticated link. [name] is the route's
     * selector (`apk` or `module`), not a path: the daemon resolves it through `apk.file` /
     * `module.file` in its manifest and refuses anything that is not a plain basename.
     */
    data class Daemon(val name: String) : ReleaseSource
}

/** The APK's source, primary first; a daemon source names the served file. */
fun ApkRelease.source(): ReleaseSource = resolveSource(url, file) { "the APK source is a daemon marker without a file name" }

/** The module zip's app-facing source; managers keep using [ModuleRelease.zipUrl]. */
fun ModuleRelease.source(): ReleaseSource? = url?.let { resolveSource(it, file) { "the module source is a daemon marker without a file name" } }

private fun resolveSource(url: String, file: String?, missing: () -> String): ReleaseSource = if (url.startsWith(DAEMON_SCHEME)) {
    // The marker must carry a server-side file too; otherwise the daemon has nothing to resolve.
    file ?: error(missing())
    ReleaseSource.Daemon(url.removePrefix(DAEMON_SCHEME))
} else {
    ReleaseSource.Https(url)
}

private const val DAEMON_SCHEME = "daemon:"

/**
 * Decoder for the published document. It accepts the daemon's channel envelope (the daemon may
 * wrap the release in `entry` when the file carries a `channels` object) and validates every field
 * the installer relies on before anything is downloaded.
 */
object UpdateManifestCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun parse(text: String): ManifestParse = try {
        parse(json.parseToJsonElement(text))
    } catch (error: Exception) {
        ManifestParse.Invalid("manifest_invalid", error.message)
    }

    fun parse(element: JsonElement): ManifestParse {
        val root = element as? JsonObject
            ?: return ManifestParse.Invalid("manifest_invalid", "the manifest is not a JSON object")
        val flattened = flattenEnvelope(root)
        val manifest = try {
            json.decodeFromJsonElement(UpdateManifest.serializer(), flattened)
        } catch (error: Exception) {
            return ManifestParse.Invalid("manifest_invalid", error.message)
        }
        val problem = validate(manifest)
        return if (problem == null) ManifestParse.Valid(manifest) else ManifestParse.Invalid("manifest_invalid", problem)
    }

    /** `{..., "entry": {...}}` becomes one object; a flat manifest is returned unchanged. */
    private fun flattenEnvelope(root: JsonObject): JsonObject {
        val entry = root["entry"] as? JsonObject ?: return root
        val merged = JsonObject(root - "entry" - "channels" + entry)
        return merged
    }

    private fun validate(manifest: UpdateManifest): String? = when {
        manifest.version.isBlank() -> "version is blank"
        manifest.versionCode <= 0 -> "versionCode must be positive"
        !isHttps(manifest.zipUrl) -> "zipUrl must be an https URL"
        !isArtifactUrl(manifest.apk.url) -> "apk.url must be an https URL or $DAEMON_URL_APK"
        !hasFileWhenDaemon(manifest.apk.url, manifest.apk.file) -> "apk.file must name the served artifact"
        manifest.apk.version.isBlank() -> "apk.version is blank"
        manifest.apk.versionCode <= 0 -> "apk.versionCode must be positive"
        !isSha256(manifest.apk.sha256) -> "apk.sha256 is not a SHA-256 hex digest"
        !isSha256(manifest.apk.signingCertSha256) -> "apk.signingCertSha256 is not a SHA-256 hex digest"
        manifest.module.versionCode <= 0 -> "module.versionCode must be positive"
        !isHttps(manifest.module.zipUrl) -> "module.zipUrl must be an https URL for the root manager"
        !isSha256(manifest.module.sha256) -> "module.sha256 is not a SHA-256 hex digest"
        manifest.module.url != null && !isArtifactUrl(manifest.module.url) -> "module.url must be an https URL or $DAEMON_URL_MODULE"
        manifest.module.url != null && !hasFileWhenDaemon(manifest.module.url, manifest.module.file) -> "module.file must name the served artifact"
        manifest.module.schemaVersion < 1 -> "module.schemaVersion must be positive"
        manifest.daemon.minVersion.isBlank() -> "daemon.minVersion is blank"
        manifest.daemon.protocol < 1 -> "daemon.protocol must be positive"
        manifest.daemon.appApi < 1 -> "daemon.appApi must be positive"
        else -> null
    }

    private fun isHttps(url: String): Boolean = url.startsWith("https://") && url.length > "https://".length

    private fun isArtifactUrl(url: String): Boolean = isHttps(url) || url == DAEMON_URL_APK || url == DAEMON_URL_MODULE

    /** A daemon marker must carry a plain basename, never a path. */
    private fun hasFileWhenDaemon(url: String, file: String?): Boolean = !url.startsWith(DAEMON_SCHEME) || (file != null && ARTIFACT_NAME.matches(file))

    private val ARTIFACT_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    private fun isSha256(value: String): Boolean = SHA256_HEX.matches(value)

    private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
}

/** The HTTP shape both downloads share; anything else is refused before a connection is made. */
internal object ReleaseUrls {
    fun requireHttps(url: String, what: String): String {
        require(url.startsWith("https://") && url.length > "https://".length) {
            "$what must be an https URL: $url"
        }
        return url
    }
}
