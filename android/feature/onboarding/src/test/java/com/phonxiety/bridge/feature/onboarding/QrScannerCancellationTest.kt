package com.phonxiety.bridge.feature.onboarding

import android.content.ContextWrapper
import android.os.Looper
import com.google.android.gms.common.Feature
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    @Test
    fun aCancelledTaskIsADismissalNotAFailure() = runTest {
        // What Play services hands back when the person dismisses the scanner out from under it.
        // Its listeners fire on the main thread, as they do in the app.
        val scan = async { startScan(TaskScanner(Tasks.forCanceled())) }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(PlayScan.Cancelled, scan.await())
    }

    @Test
    fun leavingTheScreenCancelsTheWaitAndALateResultChangesNothing() = runTest {
        val source = TaskCompletionSource<Barcode>()
        val scan = launch { startScan(TaskScanner(source.task)) }
        runCurrent()

        scan.cancel()
        scan.join()

        // Play services answers anyway after the screen left; the settled wait must stay settled.
        source.setException(MlKitException("Failed to scan code.", MlKitException.INTERNAL))
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(scan.isCancelled)
    }

    private class TaskScanner(private val task: Task<Barcode>) : GmsBarcodeScanner {
        override fun startScan(): Task<Barcode> = task

        override fun getOptionalFeatures(): Array<Feature> = emptyArray()
    }
}
