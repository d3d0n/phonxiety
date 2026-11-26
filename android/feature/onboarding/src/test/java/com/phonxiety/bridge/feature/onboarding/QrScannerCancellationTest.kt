package com.phonxiety.bridge.feature.onboarding

import android.content.ContextWrapper
import com.google.mlkit.common.MlKitException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which scanner outcomes are a dismissal and which are a genuine failure. Play services 16.1.0
 * answers Android Back's empty cancelled activity result with `INTERNAL` (13), "Failed to scan
 * code."; the wizard treats that as a dismissal — no install, no rescan, no automatic CameraX and
 * no camera permission prompt — and keeps an explicit in-app scanner in the pairing sheet for a
 * real internal failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class QrScannerCancellationTest {
    private val context = ContextWrapper(null)

    private class FakeScanner(private val result: PlayScan) : PlayScanner {
        var scans = 0
        var installs = 0

        override suspend fun scan(): PlayScan {
            scans++
            return result
        }

        override suspend fun installModule(): Boolean {
            installs++
            return true
        }
    }

    @Test
    fun aBackDismissalIsACancellationWithoutAnInstallOrARescan() = runTest {
        val back = MlKitException("Failed to scan code.", MlKitException.INTERNAL)
        assertEquals(PlayScan.Cancelled, scanFailure(back))

        val scanner = FakeScanner(scanFailure(back))
        assertEquals(PlayScan.Cancelled, scanWithPlayServices(context) { scanner })
        assertEquals(1, scanner.scans)
        assertEquals(0, scanner.installs)
    }

    @Test
    fun theCloseButtonIsACancellation() {
        assertEquals(
            PlayScan.Cancelled,
            scanFailure(MlKitException("Failed to scan code.", MlKitException.CODE_SCANNER_CANCELLED))
        )
    }

    @Test
    fun aMissingModuleIsAnUnavailableScanner() {
        assertEquals(
            PlayScan.Unavailable("the phone does not have the Google code scanner module yet"),
            scanFailure(MlKitException("Failed to scan code.", MlKitException.CODE_SCANNER_UNAVAILABLE))
        )
        assertEquals(
            PlayScan.Unavailable("the phone does not have the Google code scanner module yet"),
            scanFailure(
                MlKitException(
                    "Failed to scan code.",
                    MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD
                )
            )
        )
    }

    @Test
    fun aGenuineFailureIsReportedWithoutAnAutomaticFallback() = runTest {
        val pipeline = MlKitException(
            "Failed to scan code.",
            MlKitException.CODE_SCANNER_PIPELINE_INITIALIZATION_ERROR
        )
        assertEquals(PlayScan.Failed("Failed to scan code."), scanFailure(pipeline))

        val scanner = FakeScanner(scanFailure(pipeline))
        assertEquals(PlayScan.Failed("Failed to scan code."), scanWithPlayServices(context) { scanner })
        assertEquals(1, scanner.scans)
        assertEquals(0, scanner.installs)
    }

    @Test
    fun aFailureThatIsNotMlKitIsStillAFailure() {
        assertEquals(
            PlayScan.Failed("the scanner activity could not open"),
            scanFailure(IllegalStateException("the scanner activity could not open"))
        )
    }
}
