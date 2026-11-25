package com.phonxiety.bridge.data.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    @Test
    fun `parses the published document`() {
        val parsed = UpdateManifestCodec.parse(SAMPLES.valid)
        val manifest = (parsed as? ManifestParse.Valid)?.manifest ?: error("expected a valid manifest: $parsed")
        assertEquals("0.4.0", manifest.version)
        assertEquals(4L, manifest.versionCode)
        assertEquals("https://example.test/phonxiety-bridge-0.4.0.zip", manifest.zipUrl)
        assertEquals("https://example.test/phonxiety-bridge-0.4.0.apk", manifest.apk.url)
        assertEquals(SHA_A, manifest.apk.sha256)
        assertEquals(CERT, manifest.apk.signingCertSha256)
        assertEquals("0.1.0", manifest.daemon.minVersion)
        assertEquals(3, manifest.daemon.protocol)
        assertEquals(1, manifest.daemon.appApi)
        assertEquals(1, manifest.module.schemaVersion)
        assertEquals(
            listOf("android.permission.CAPTURE_AUDIO_OUTPUT", "android.permission.INSTALL_SELF_UPDATES"),
            manifest.module.permissions
        )
    }

    @Test
    fun `accepts the daemon's channel envelope`() {
        val wrapped = """{"channel":"beta","entry":${SAMPLES.valid}}"""
        val manifest = (UpdateManifestCodec.parse(wrapped) as? ManifestParse.Valid)?.manifest
            ?: error("the entry was not unwrapped")
        assertEquals("0.4.0", manifest.version)
    }

    @Test
    fun `rejects a non-https download url`() {
        val invalid = SAMPLES.valid.replace(
            "\"url\": \"https://example.test/phonxiety-bridge-0.4.0.apk\"",
            "\"url\": \"http://example.test/phonxiety-bridge-0.4.0.apk\""
        )
        val parsed = UpdateManifestCodec.parse(invalid) as? ManifestParse.Invalid ?: error("accepted an http url")
        assertEquals("manifest_invalid", parsed.reason)
        assertTrue(parsed.detail!!.contains("https"))
    }

    @Test
    fun `rejects a digest that is not sha256`() {
        val invalid = SAMPLES.valid.replace(SHA_B, "abc")
        val parsed = UpdateManifestCodec.parse(invalid)
        assertEquals("manifest_invalid", (parsed as? ManifestParse.Invalid)?.reason)
    }

    @Test
    fun `rejects a missing artifact block`() {
        val invalid = """{"version":"0.4.0","versionCode":4,"zipUrl":"https://e.test/a.zip"}"""
        val parsed = UpdateManifestCodec.parse(invalid)
        assertEquals("manifest_invalid", (parsed as? ManifestParse.Invalid)?.reason)
    }

    @Test
    fun `a daemon artifact source resolves to the route selector`() {
        val served = SAMPLES.valid
            .replace(
                "\"url\": \"https://example.test/phonxiety-bridge-0.4.0.apk\"",
                "\"url\": \"daemon:apk\", \"file\": \"phonxiety-bridge-0.4.0.apk\""
            )
            .replace(
                "\"sha256\": \"$SHA_B\"",
                "\"url\": \"daemon:module\", \"file\": \"phonxiety-bridge-0.4.0.zip\", \"sha256\": \"$SHA_B\""
            )
        val manifest = (UpdateManifestCodec.parse(served) as? ManifestParse.Valid)?.manifest
            ?: error("a daemon-served manifest was refused: ${UpdateManifestCodec.parse(served)}")
        assertEquals(ReleaseSource.Daemon("apk"), manifest.apk.source())
        assertEquals(ReleaseSource.Daemon("module"), manifest.module.source())
        // The manager still needs an https zip even when the app reads the daemon's copy.
        assertTrue(manifest.module.zipUrl.startsWith("https://"))
    }

    @Test
    fun `a daemon marker without a file name is refused`() {
        val invalid = SAMPLES.valid.replace(
            "\"url\": \"https://example.test/phonxiety-bridge-0.4.0.apk\"",
            "\"url\": \"daemon:apk\""
        )
        val parsed = UpdateManifestCodec.parse(invalid) as? ManifestParse.Invalid ?: error("accepted a marker without a file")
        assertEquals("manifest_invalid", parsed.reason)
    }

    @Test
    fun `a daemon file name cannot be a path`() {
        val invalid = SAMPLES.valid.replace(
            "\"url\": \"https://example.test/phonxiety-bridge-0.4.0.apk\"",
            "\"url\": \"daemon:apk\", \"file\": \"../secrets.apk\""
        )
        val parsed = UpdateManifestCodec.parse(invalid) as? ManifestParse.Invalid ?: error("accepted a path as a file name")
        assertEquals("manifest_invalid", parsed.reason)
    }

    @Test
    fun `an https apk url resolves to the https source`() {
        val manifest = (UpdateManifestCodec.parse(SAMPLES.valid) as? ManifestParse.Valid)?.manifest
            ?: error("the sample no longer parses")
        assertEquals(ReleaseSource.Https("https://example.test/phonxiety-bridge-0.4.0.apk"), manifest.apk.source())
    }

    @Test
    fun `version order compares dotted numbers and pre-releases`() {
        assertTrue(VersionOrder.compare("0.4.0", "0.3.9") > 0)
        assertEquals(0, VersionOrder.compare("0.3", "0.3.0"))
        assertTrue(VersionOrder.compare("1.0.0-rc1", "1.0.0") < 0)
        assertTrue(VersionOrder.compare("v1.2.3", "1.2.2") > 0)
    }

    private object SAMPLES {
        val valid = """
            {
              "channel": "stable",
              "version": "0.4.0",
              "versionCode": 4,
              "zipUrl": "https://example.test/phonxiety-bridge-0.4.0.zip",
              "changelog": "https://example.test/CHANGELOG.md",
              "updateJsonUrl": "https://example.test/update.json",
              "published_at": "2026-11-26T12:00:00+00:00",
              "apk": {
                "version": "0.4.0",
                "versionCode": 4,
                "url": "https://example.test/phonxiety-bridge-0.4.0.apk",
                "sha256": "$SHA_A",
                "size": 7340032,
                "signingCertSha256": "$CERT",
                "minSdk": 34
              },
              "module": {
                "version": "0.4.0",
                "versionCode": 4,
                "zipUrl": "https://example.test/phonxiety-bridge-0.4.0.zip",
                "sha256": "$SHA_B",
                "size": 1048576,
                "schemaVersion": 1,
                "requiresReboot": true,
                "permissions": [
                  "android.permission.CAPTURE_AUDIO_OUTPUT",
                  "android.permission.INSTALL_SELF_UPDATES"
                ]
              },
              "daemon": {
                "minVersion": "0.1.0",
                "protocol": 3,
                "appApi": 1,
                "moduleSchema": 1
              }
            }
        """.trimIndent()
    }
}

private const val SHA_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val SHA_B = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
private const val CERT = "aaaabbbbccccddddeeeeffff0000111122223333444455556666777788889999"
