package com.phonxiety.bridge.feature.onboarding

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Play services module install: one budget for the whole download, and the status listener is
 * released on every exit — completion, failure, timeout and cancellation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QrScannerTest {
    private class FakeDownload(
        private val answer: ((Boolean) -> Unit) -> Unit = { _ -> }
    ) : ModuleDownload {
        var stops = 0

        override fun start(onResult: (Boolean) -> Unit) {
            answer(onResult)
        }

        override fun stop() {
            stops++
        }
    }

    @Test
    fun aCompletedDownloadIsReportedAndTheListenerReleased() = runTest {
        val download = FakeDownload { onResult -> onResult(true) }

        assertTrue(awaitModuleDownload(1_000, download))
        assertEquals(1, download.stops)
    }

    @Test
    fun aFailedDownloadIsReportedAndTheListenerReleased() = runTest {
        val download = FakeDownload { onResult -> onResult(false) }

        assertFalse(awaitModuleDownload(1_000, download))
        assertEquals(1, download.stops)
    }

    @Test
    fun aDownloadThatOutlivesTheBudgetFailsAndTheListenerIsReleased() = runTest {
        val download = FakeDownload()

        assertFalse(awaitModuleDownload(1_000, download))
        assertEquals(1, download.stops)
    }

    @Test
    fun aDownloadThatThrowsIsAFailedInstallAndTheListenerIsReleased() = runTest {
        val download = FakeDownload { throw IllegalStateException("the Play services binding failed") }

        assertFalse(awaitModuleDownload(1_000, download))
        assertEquals(1, download.stops)
    }

    @Test
    fun aCancelledWaitReleasesTheListener() = runTest {
        val download = FakeDownload()
        val wait = launch { awaitModuleDownload(60_000, download) }
        runCurrent()

        wait.cancel()
        wait.join()

        assertEquals(1, download.stops)
    }
}
