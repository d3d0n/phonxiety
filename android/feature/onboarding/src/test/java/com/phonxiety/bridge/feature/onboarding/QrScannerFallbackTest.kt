package com.phonxiety.bridge.feature.onboarding

import android.content.ContextWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Google scanner is a convenience, not a dependency. A release build once died inside
 * `GmsBarcodeScanning.getClient` because R8 had removed the ML Kit registrar constructors, and the
 * null `SharedPrefManager` became a `NullPointerException` on the wizard thread. No failure in this
 * path may reach the app: a factory or a scan that throws has to come back as one
 * [PlayScan.Unavailable] so the wizard opens the in-app scanner and the paste field.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QrScannerFallbackTest {
    // Only passed through to the factory; these tests never reach Play services.
    private val context = ContextWrapper(null)

    private class FakeScanner(
        private val results: List<PlayScan>,
        private val installResult: Boolean = false
    ) : PlayScanner {
        var scans = 0
        var installs = 0

        override suspend fun scan(): PlayScan {
            val result = results[scans.coerceAtMost(results.lastIndex)]
            scans++
            return result
        }

        override suspend fun installModule(): Boolean {
            installs++
            return installResult
        }
    }

    private fun unavailable(result: PlayScan): PlayScan.Unavailable {
        assertTrue("expected Unavailable, got $result", result is PlayScan.Unavailable)
        return result as PlayScan.Unavailable
    }

    @Test
    fun aFactoryThatThrowsIsAnUnavailableScanner() = runTest {
        val result = scanWithPlayServices(
            context,
            PlayScannerFactory { _ ->
                throw NullPointerException(
                    "Attempt to invoke virtual method 'java.lang.Class java.lang.Object.getClass()' " +
                        "on a null object reference"
                )
            }
        )

        val reason = unavailable(result).reason
        assertTrue(reason, reason.startsWith("the Google code scanner could not start"))
        assertTrue(reason, reason.contains("null object reference"))
    }

    @Test
    fun aFatalErrorIsNotTurnedIntoAFallback() = runTest {
        val fatal = OutOfMemoryError("no room for the scanner")
        val thrown = runCatching {
            scanWithPlayServices(context, PlayScannerFactory { _ -> throw fatal })
        }

        // An Error is not a scanner outcome: opening the CameraX fallback would only allocate more.
        assertTrue(thrown.exceptionOrNull() === fatal)
    }

    @Test
    fun aScanThatThrowsIsUnavailableInsteadOfCrashing() = runTest {
        val scanner = object : PlayScanner {
            override suspend fun scan(): PlayScan = throw IllegalStateException(
                "the scanner activity could not open"
            )

            override suspend fun installModule(): Boolean = false
        }

        val reason = unavailable(scanWithPlayServices(context) { scanner }).reason
        assertTrue(reason, reason.contains("the scanner activity could not open"))
    }

    @Test
    fun aModuleInstallThatThrowsKeepsTheFirstReason() = runTest {
        val scanner = object : PlayScanner {
            override suspend fun scan(): PlayScan = PlayScan.Unavailable(
                "the phone does not have the Google code scanner module yet"
            )

            override suspend fun installModule(): Boolean = throw SecurityException(
                "Play services refused the download"
            )
        }

        val reason = unavailable(scanWithPlayServices(context) { scanner }).reason
        assertTrue(reason, reason.contains("Play services refused the download"))
    }

    @Test
    fun aCodeReachesTheWizardWithoutAnInstall() = runTest {
        val scanner = FakeScanner(listOf(PlayScan.Code("phonxiety://pair?token=abc")))

        assertEquals(PlayScan.Code("phonxiety://pair?token=abc"), scanWithPlayServices(context) { scanner })
        assertEquals(0, scanner.installs)
    }

    @Test
    fun anUnavailableFirstAttemptInstallsTheModuleAndScansAgain() = runTest {
        val scanner = FakeScanner(
            listOf(PlayScan.Unavailable("the module is not installed"), PlayScan.Code("pair")),
            installResult = true
        )

        assertEquals(PlayScan.Code("pair"), scanWithPlayServices(context) { scanner })
        assertEquals(2, scanner.scans)
        assertEquals(1, scanner.installs)
    }

    @Test
    fun aRefusedInstallReportsTheFirstReason() = runTest {
        val scanner = FakeScanner(listOf(PlayScan.Unavailable("the module is not installed")))

        assertEquals(
            PlayScan.Unavailable("the module is not installed"),
            scanWithPlayServices(context) { scanner }
        )
        assertEquals(1, scanner.installs)
    }

    @Test
    fun aCancelledScanIsNotAFailure() = runTest {
        val scanner = FakeScanner(listOf(PlayScan.Cancelled))

        assertEquals(PlayScan.Cancelled, scanWithPlayServices(context) { scanner })
        assertEquals(0, scanner.installs)
    }

    @Test
    fun leavingTheWizardCancelsInsteadOfFallingBack() = runTest {
        val thrown = runCatching {
            scanWithPlayServices(
                context,
                PlayScannerFactory { _ -> throw CancellationException("the wizard left the screen") }
            )
        }

        assertTrue(thrown.exceptionOrNull() is CancellationException)
    }
}
