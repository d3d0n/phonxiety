package com.phonxiety.bridge.data.updates

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateCoordinatorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val apk = ApkRelease(
        version = "0.4.0",
        versionCode = 4,
        url = "https://example.test/phonxiety-bridge-0.4.0.apk",
        sha256 = SHA,
        signingCertSha256 = CERT,
        minSdk = 34
    )

    @Test
    fun `downloads, verifies and installs`() = runTest {
        val harness = Harness()
        val states = mutableListOf<AppInstallState>()
        val result = harness.coordinator(states).install(apk)

        assertTrue(result is AppInstallState.Installed)
        assertEquals("0.4.0", (result as AppInstallState.Installed).versionName)
        assertEquals(1, harness.keeper.keepCalls)
        assertEquals(1, harness.verifier.calls)
        assertEquals(SHA, harness.verifier.expectedSha256)
        assertEquals(CERT, harness.verifier.expectedCert)
        assertEquals(1, harness.installer.calls)
        assertEquals(ReleaseSource.Https("https://example.test/phonxiety-bridge-0.4.0.apk"), harness.downloadSource())
        assertTrue(states.first() is AppInstallState.Downloading)
        assertTrue(states.any { it is AppInstallState.Verifying })
        assertTrue(states.any { it is AppInstallState.Installing })
        assertFalse(harness.pendingFile().exists())
    }

    @Test
    fun `a digest mismatch is refused before the apk is read`() = runTest {
        val harness = Harness(downloadedSha = "f".repeat(64))
        val result = harness.coordinator().install(apk)

        assertEquals(InstallFailure.HASH_MISMATCH, (result as AppInstallState.Failed).code)
        assertEquals(0, harness.verifier.calls)
        assertEquals(0, harness.installer.calls)
        assertEquals(0, harness.keeper.keepCalls)
        assertFalse(harness.pendingFile().exists())
    }

    @Test
    fun `a signing certificate mismatch is refused`() = runTest {
        val harness = Harness(
            verification = ApkVerification.SigningCertMismatch(
                expected = setOf(CERT),
                found = setOf("9".repeat(64))
            )
        )
        val result = harness.coordinator().install(apk)

        assertEquals(InstallFailure.SIGNING_CERT_MISMATCH, (result as AppInstallState.Failed).code)
        assertEquals(0, harness.installer.calls)
        assertEquals(0, harness.keeper.keepCalls)
    }

    @Test
    fun `an archive older than the installed app is refused`() = runTest {
        val harness = Harness(verification = ApkVerification.Valid("0.2.0", 2))
        val result = harness.coordinator(currentVersionCode = 3L).install(apk)

        assertEquals(InstallFailure.DOWNGRADE_REFUSED, (result as AppInstallState.Failed).code)
        assertEquals(0, harness.installer.calls)
    }

    @Test
    fun `a wrong package is refused`() = runTest {
        val harness = Harness(verification = ApkVerification.WrongPackage("com.example.other"))
        val result = harness.coordinator().install(apk)

        val failure = result as AppInstallState.Failed
        assertEquals(InstallFailure.WRONG_PACKAGE, failure.code)
        assertEquals("com.example.other", failure.detail)
    }

    @Test
    fun `a failed download reports its reason`() = runTest {
        val harness = Harness(downloadError = IllegalStateException("HTTP 404"))
        val result = harness.coordinator().install(apk)

        assertEquals(InstallFailure.DOWNLOAD_FAILED, (result as AppInstallState.Failed).code)
        assertEquals("HTTP 404", result.detail)
    }

    @Test
    fun `the previous apk is kept before the installer runs`() = runTest {
        val harness = Harness()
        harness.installer.beforeInstall = { assertEquals(1, harness.keeper.keepCalls) }
        harness.coordinator().install(apk)

        assertEquals(1, harness.keeper.keepCalls)
    }

    @Test
    fun `an installer failure is surfaced with its code`() = runTest {
        val harness = Harness(
            installOutcome = ApkInstallOutcome.Failed(InstallFailure.INSTALL_CONFLICT, "version conflict")
        )
        val result = harness.coordinator().install(apk)

        assertEquals(InstallFailure.INSTALL_CONFLICT, (result as AppInstallState.Failed).code)
        assertEquals("version conflict", (result as AppInstallState.Failed).detail)
    }

    @Test
    fun `a pending user action ends in the awaiting state`() = runTest {
        val harness = Harness(installOutcome = ApkInstallOutcome.AwaitingConfirmation)
        val result = harness.coordinator().install(apk)

        assertTrue(result is AppInstallState.AwaitingConfirmation)
    }

    @Test
    fun `rollback installs the kept apk through root`() = runTest {
        val keeper = FakeKeeper(keptFile = temporary.newFile("previous.apk"))
        val privileged = FakePrivilegedInstaller()
        val states = mutableListOf<AppInstallState>()
        val result = AppRollbackCoordinator(keeper, privileged) { states += it }.rollback()

        assertEquals("0.3.0", (result as AppInstallState.Installed).versionName)
        assertEquals(1, privileged.calls)
        assertTrue(states.any { it is AppInstallState.Installing })
    }

    @Test
    fun `rollback without a kept apk fails without touching root`() = runTest {
        val privileged = FakePrivilegedInstaller()
        val result = AppRollbackCoordinator(FakeKeeper(keptFile = null), privileged) {}.rollback()

        assertEquals(InstallFailure.NO_KEPT_APK, (result as AppInstallState.Failed).code)
        assertEquals(0, privileged.calls)
    }

    private inner class Harness(
        downloadedSha: String = SHA,
        downloadError: Exception? = null,
        verification: ApkVerification = ApkVerification.Valid("0.4.0", 4),
        installOutcome: ApkInstallOutcome = ApkInstallOutcome.Installed("0.4.0")
    ) {
        val directory: File = temporary.newFolder("updates")
        val keeper = FakeKeeper(keptFile = File(directory, "previous.apk"))
        val verifier = FakeVerifier(verification)
        val installer = FakeInstaller(installOutcome)
        private val downloader = FakeDownloader(directory, downloadedSha, downloadError)

        fun downloadSource(): ReleaseSource? = downloader.source

        fun coordinator(
            states: MutableList<AppInstallState> = mutableListOf(),
            currentVersionCode: Long = 4L
        ) = AppUpdateCoordinator(
            downloader = downloader,
            verifier = verifier,
            installer = installer,
            keeper = keeper,
            currentVersionCode = { currentVersionCode },
            pendingDirectory = directory,
            onState = { states += it }
        )

        fun pendingFile(): File = File(directory, "phonxiety-4.apk")
    }

    private class FakeDownloader(
        private val directory: File,
        private val sha: String,
        private val error: Exception?
    ) : ReleaseDownloader {
        var source: ReleaseSource? = null

        override suspend fun download(
            source: ReleaseSource,
            destination: File,
            onProgress: (Float?) -> Unit
        ): DownloadedFile {
            error?.let { throw it }
            this.source = source
            onProgress(0.5f)
            destination.parentFile?.mkdirs()
            destination.writeText("apk")
            return DownloadedFile(destination, sha, destination.length())
        }
    }

    private class FakeVerifier(private val result: ApkVerification) : ApkVerifier {
        var calls = 0
        var expectedSha256: String? = null
        var expectedCert: String? = null

        override fun verify(file: File, expectedSha256: String, expectedSigningCertSha256: String): ApkVerification {
            calls++
            this.expectedSha256 = expectedSha256
            this.expectedCert = expectedSigningCertSha256
            return result
        }
    }

    private class FakeInstaller(private val outcome: ApkInstallOutcome) : ApkInstallGateway {
        var calls = 0
        var beforeInstall: (() -> Unit)? = null

        override suspend fun install(apk: File, onState: (AppInstallState) -> Unit): ApkInstallOutcome {
            calls++
            beforeInstall?.invoke()
            return outcome
        }
    }

    private class FakeKeeper(private val keptFile: File?) : PreviousApkKeeper {
        var keepCalls = 0

        override suspend fun keepCurrentApk(): File? {
            keepCalls++
            return keptFile
        }

        override fun keptApk(): File? = keptFile

        override fun keptVersionName(): String? = "0.3.0"

        override fun keptVersionCode(): Long = 3L
    }

    private class FakePrivilegedInstaller : PrivilegedApkInstaller {
        var calls = 0

        override suspend fun installWithRoot(apk: File): ApkInstallOutcome {
            calls++
            return ApkInstallOutcome.Installed("0.3.0")
        }
    }

    private companion object {
        const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val CERT = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    }
}
