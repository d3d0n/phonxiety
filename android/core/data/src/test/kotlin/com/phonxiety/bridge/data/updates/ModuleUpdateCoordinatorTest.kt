package com.phonxiety.bridge.data.updates

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModuleUpdateCoordinatorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val manifest = UpdateManifest(
        version = "0.4.0",
        versionCode = 4,
        zipUrl = "https://example.test/phonxiety-bridge-0.4.0.zip",
        updateJsonUrl = "https://example.test/update.json",
        apk = ApkRelease(
            version = "0.4.0",
            versionCode = 4,
            url = "https://example.test/phonxiety-bridge-0.4.0.apk",
            sha256 = "0".repeat(64),
            signingCertSha256 = "1".repeat(64)
        ),
        module = ModuleRelease(
            version = "0.4.0",
            versionCode = 4,
            zipUrl = "https://example.test/phonxiety-bridge-0.4.0.zip",
            sha256 = "2".repeat(64)
        ),
        daemon = DaemonRelease(minVersion = "0.1.0", protocol = 3, appApi = 1)
    )

    @Test
    fun `builds, installs and asks for the reboot`() = runTest {
        val gateway = FakeGateway()
        val states = mutableListOf<ModuleInstallState>()
        val result = ModuleUpdateCoordinator(gateway) { states += it }.install(manifest)

        assertTrue(result is ModuleInstallState.RebootPending)
        assertEquals("https://example.test/update.json", gateway.updateJsonUrl)
        assertEquals(listOf(ModuleInstallState.Building, ModuleInstallState.Installing), states.take(2))
        assertEquals(1, gateway.installs)
        assertEquals(1, gateway.reboots)
    }

    @Test
    fun `a build failure stops before the provider`() = runTest {
        val gateway = FakeGateway(buildError = IllegalStateException("the running APK was not found"))
        val result = ModuleUpdateCoordinator(gateway) {}.install(manifest)

        assertEquals(ModuleInstallFailure.BUILD_FAILED, (result as ModuleInstallState.Failed).code)
        assertEquals(0, gateway.installs)
        assertEquals(0, gateway.reboots)
    }

    @Test
    fun `a refused root command is reported`() = runTest {
        val gateway = FakeGateway(
            installResult = ProviderInstall.Failed(ModuleInstallFailure.ROOT_UNAVAILABLE, "su: permission denied")
        )
        val result = ModuleUpdateCoordinator(gateway) {}.install(manifest)

        val failure = result as ModuleInstallState.Failed
        assertEquals(ModuleInstallFailure.ROOT_UNAVAILABLE, failure.code)
        assertEquals("su: permission denied", failure.detail)
        assertEquals(0, gateway.reboots)
    }

    @Test
    fun `a refused reboot keeps the update and explains it`() = runTest {
        val gateway = FakeGateway(
            rebootResult = ProviderInstall.Failed(ModuleInstallFailure.REBOOT_FAILED, "REBOOT not granted")
        )
        val result = ModuleUpdateCoordinator(gateway) {}.install(manifest)

        val pending = result as ModuleInstallState.RebootPending
        assertTrue(pending.detail!!.contains("REBOOT not granted"))
    }

    private class FakeGateway(
        private val buildError: Exception? = null,
        private val installResult: ProviderInstall = ProviderInstall.Installed,
        private val rebootResult: ProviderInstall = ProviderInstall.Installed
    ) : ModuleInstallGateway {
        var updateJsonUrl: String? = null
        var installs = 0
        var reboots = 0

        override suspend fun buildZip(updateJsonUrl: String?): File {
            buildError?.let { throw it }
            this.updateJsonUrl = updateJsonUrl
            return File.createTempFile("module", ".zip")
        }

        override suspend fun install(zip: File): ProviderInstall {
            installs++
            return installResult
        }

        override suspend fun reboot(): ProviderInstall {
            reboots++
            return rebootResult
        }
    }
}
