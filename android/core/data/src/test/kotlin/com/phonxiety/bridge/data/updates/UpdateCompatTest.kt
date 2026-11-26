package com.phonxiety.bridge.data.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCompatTest {
    @Test
    fun `everything current is compatible with no action`() {
        val verdict = UpdateCompat.evaluate(manifest(), installed())
        assertEquals(UpdateStatus.UP_TO_DATE, verdict.app.status)
        assertEquals(UpdateStatus.UP_TO_DATE, verdict.module.status)
        assertEquals(UpdateStatus.UP_TO_DATE, verdict.daemon.status)
        assertTrue(verdict.compatible)
        assertFalse(verdict.hasUpdate)
    }

    @Test
    fun `a newer version code is offered`() {
        val verdict = UpdateCompat.evaluate(manifest(), installed(appVersionCode = 3, moduleVersionCode = 3))
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, verdict.app.status)
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, verdict.module.status)
        assertEquals("0.4.0", verdict.app.availableVersion)
    }

    @Test
    fun `a release above the phone's API is incompatible`() {
        val verdict = UpdateCompat.evaluate(manifest(), installed(deviceSdk = 33))
        assertEquals(UpdateStatus.INCOMPATIBLE, verdict.app.status)
        assertFalse(verdict.compatible)
        assertEquals(
            CompatibilityNote.NeedsNewerAndroid(requiredSdk = 34, phoneSdk = 33),
            verdict.app.notes.single()
        )
    }

    @Test
    fun `a new privileged grant makes the module update need a reboot`() {
        val verdict = UpdateCompat.evaluate(
            manifest(
                modulePermissions = listOf(
                    "android.permission.CAPTURE_AUDIO_OUTPUT",
                    "android.permission.CONTROL_INCALL_EXPERIENCE"
                )
            ),
            installed(moduleVersionCode = 3, granted = setOf("android.permission.CAPTURE_AUDIO_OUTPUT"))
        )
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, verdict.module.status)
        assertTrue(verdict.module.requiresReboot)
        assertEquals(
            listOf(
                CompatibilityNote.ModuleRebootRequired,
                CompatibilityNote.NewPrivilegedGrants(listOf("android.permission.CONTROL_INCALL_EXPERIENCE"))
            ),
            verdict.module.notes
        )
    }

    @Test
    fun `a newer payload schema needs the app first`() {
        val verdict = UpdateCompat.evaluate(manifest(moduleSchema = 2), installed())
        assertEquals(UpdateStatus.INCOMPATIBLE, verdict.module.status)
        assertEquals(
            CompatibilityNote.NewerModuleSchema(requiredSchema = 2, supportedSchema = 1),
            verdict.module.notes.single()
        )
    }

    @Test
    fun `an unreadable module state is unknown, not up to date`() {
        val verdict = UpdateCompat.evaluate(manifest(), installed(moduleInstalled = null))
        assertEquals(UpdateStatus.UNKNOWN, verdict.module.status)
    }

    @Test
    fun `an absent module offers the install`() {
        val verdict = UpdateCompat.evaluate(
            manifest(),
            installed(moduleInstalled = false, moduleVersion = null, moduleVersionCode = null)
        )
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, verdict.module.status)
        assertTrue(verdict.module.requiresReboot)
    }

    @Test
    fun `an older daemon is called out for the computer`() {
        val verdict = UpdateCompat.evaluate(manifest(), installed(daemonVersion = "0.0.9"))
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, verdict.daemon.status)
        assertEquals(CompatibilityNote.NeedsNewerDaemon("0.1.0"), verdict.daemon.notes.single())
    }

    @Test
    fun `the daemon's own words stay verbatim`() {
        val verdict = UpdateCompat.evaluate(
            manifest(),
            installed(daemonCompatible = false, daemonNotes = listOf("protocol ranges do not overlap"))
        )
        assertEquals(UpdateStatus.INCOMPATIBLE, verdict.daemon.status)
        assertEquals(
            CompatibilityNote.Reported("protocol ranges do not overlap"),
            verdict.daemon.notes.single()
        )
    }

    @Test
    fun `a daemon that refuses the protocol is incompatible`() {
        val verdict = UpdateCompat.evaluate(manifest(), installed(daemonCompatible = false))
        assertEquals(UpdateStatus.INCOMPATIBLE, verdict.daemon.status)
        assertFalse(verdict.compatible)
    }

    @Test
    fun `a protocol the daemon does not accept is incompatible`() {
        val verdict = UpdateCompat.evaluate(manifest(protocol = 4), installed(daemonProtocols = listOf(2, 3)))
        assertEquals(UpdateStatus.INCOMPATIBLE, verdict.daemon.status)
    }

    @Test
    fun `an unanswered daemon is unknown`() {
        val verdict = UpdateCompat.evaluate(
            manifest(),
            installed(daemonVersion = null, daemonProtocols = emptyList(), daemonCompatible = null)
        )
        assertEquals(UpdateStatus.UNKNOWN, verdict.daemon.status)
    }

    private fun manifest(
        modulePermissions: List<String> = listOf("android.permission.CAPTURE_AUDIO_OUTPUT"),
        moduleSchema: Int = 1,
        protocol: Int = 3
    ) = UpdateManifest(
        version = "0.4.0",
        versionCode = 4,
        zipUrl = "https://example.test/phonxiety-bridge-0.4.0.zip",
        apk = ApkRelease(
            version = "0.4.0",
            versionCode = 4,
            url = "https://example.test/phonxiety-bridge-0.4.0.apk",
            sha256 = "0".repeat(64),
            signingCertSha256 = "1".repeat(64),
            minSdk = 34
        ),
        module = ModuleRelease(
            version = "0.4.0",
            versionCode = 4,
            zipUrl = "https://example.test/phonxiety-bridge-0.4.0.zip",
            sha256 = "2".repeat(64),
            permissions = modulePermissions,
            schemaVersion = moduleSchema
        ),
        daemon = DaemonRelease(minVersion = "0.1.0", protocol = protocol, appApi = 1)
    )

    private fun installed(
        appVersionCode: Long = 4,
        moduleVersionCode: Int? = 4,
        moduleInstalled: Boolean? = true,
        moduleVersion: String? = "0.4.0",
        deviceSdk: Int = 35,
        granted: Set<String> = setOf("android.permission.CAPTURE_AUDIO_OUTPUT"),
        daemonVersion: String? = "0.1.0",
        daemonProtocols: List<Int> = listOf(3),
        daemonCompatible: Boolean? = true,
        daemonNotes: List<String> = emptyList()
    ) = InstalledVersions(
        appVersion = "0.4.0",
        appVersionCode = appVersionCode,
        deviceSdk = deviceSdk,
        moduleVersion = moduleVersion,
        moduleVersionCode = moduleVersionCode,
        moduleInstalled = moduleInstalled,
        grantedPrivilegedPermissions = granted,
        daemonVersion = daemonVersion,
        daemonAppApi = 1,
        daemonProtocolVersions = daemonProtocols,
        daemonCompatible = daemonCompatible,
        daemonNotes = daemonNotes
    )
}
