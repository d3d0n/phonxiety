package com.phonxiety.bridge.feature.onboarding

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** What one attempt at the Play services scanner produced. */
internal sealed interface PlayScan {
    data class Code(val value: String) : PlayScan

    data object Cancelled : PlayScan

    /** No usable scanner: the module is missing, Play services refused, or the camera was denied. */
    data class Unavailable(val reason: String) : PlayScan
}

/**
 * One attempt at a code scanner. Production wraps the Google code scanner; a test supplies a
 * scanner that fails without Play services.
 */
internal interface PlayScanner {
    /** Opens the scanner and reports what the person did with it. */
    suspend fun scan(): PlayScan

    /** Downloads the on-demand module. True means [scan] may be tried again. */
    suspend fun installModule(): Boolean
}

/** Creates [PlayScanner]s. The factory itself may fail before a scanner exists. */
internal fun interface PlayScannerFactory {
    suspend fun create(context: Context): PlayScanner
}

/**
 * The pairing scanner. The Google code scanner is preferred: no camera permission and no preview of
 * our own. Its module is an on-demand Play services download, so a phone that has never installed it
 * answers [MlKitException.CODE_SCANNER_UNAVAILABLE]; the module is then requested through
 * `ModuleInstallClient` and the scan retried. When Play services cannot provide it (no Play Store,
 * no network, module install refused) [onUnavailable] is called and the host opens the in-app
 * CameraX scanner, so pairing never depends on one vendor path.
 */
@Composable
fun rememberQrScanner(
    onCode: (String) -> Unit,
    onUnavailable: (String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, onCode, onUnavailable) {
        {
            scope.launch {
                when (val result = scanWithPlayServices(context)) {
                    is PlayScan.Code -> onCode(result.value)
                    PlayScan.Cancelled -> Unit
                    is PlayScan.Unavailable -> onUnavailable(result.reason)
                }
            }
        }
    }
}

/**
 * The whole Google scanner path for one attempt. Anything that goes wrong in it — Play services
 * missing or broken, a scanner that cannot be constructed, a scan that throws instead of reporting
 * an error — becomes one [PlayScan.Unavailable], so the host opens the in-app CameraX scanner and
 * the paste field instead of losing the wizard. Cancellation still propagates: leaving the screen
 * is not a scanner failure.
 */
internal suspend fun scanWithPlayServices(
    context: Context,
    factory: PlayScannerFactory = PlayServicesScannerFactory
): PlayScan = try {
    val scanner = factory.create(context)
    val first = scanner.scan()
    if (first !is PlayScan.Unavailable) {
        first
    } else if (!scanner.installModule()) {
        first
    } else {
        scanner.scan()
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    PlayScan.Unavailable(scanFailureReason(error))
}

/** A reason the wizard can show: what failed, plus the platform message when there is one. */
private fun scanFailureReason(error: Exception): String {
    val detail = error.message?.takeIf { it.isNotBlank() }
        ?: return "the Google code scanner could not start"
    return "the Google code scanner could not start: $detail"
}

private object PlayServicesScannerFactory : PlayScannerFactory {
    override suspend fun create(context: Context): PlayScanner = PlayServicesScanner(
        context = context,
        scanner = GmsBarcodeScanning.getClient(context, scannerOptions())
    )
}

private class PlayServicesScanner(
    private val context: Context,
    private val scanner: GmsBarcodeScanner
) : PlayScanner {
    override suspend fun scan(): PlayScan = startScan(scanner)

    override suspend fun installModule(): Boolean = installScannerModule(context, scanner)
}

private fun scannerOptions(): GmsBarcodeScannerOptions = GmsBarcodeScannerOptions.Builder()
    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
    .enableAutoZoom()
    .build()

private suspend fun startScan(scanner: GmsBarcodeScanner): PlayScan = suspendCancellableCoroutine { continuation ->
    scanner.startScan()
        .addOnSuccessListener { barcode ->
            if (!continuation.isActive) return@addOnSuccessListener
            val raw = barcode.rawValue
            continuation.resume(
                if (raw.isNullOrBlank()) PlayScan.Unavailable("the QR code was empty") else PlayScan.Code(raw)
            )
        }
        .addOnFailureListener { error ->
            if (!continuation.isActive) return@addOnFailureListener
            val code = (error as? MlKitException)?.errorCode
            continuation.resume(
                when (code) {
                    MlKitException.CODE_SCANNER_CANCELLED -> PlayScan.Cancelled

                    MlKitException.CODE_SCANNER_UNAVAILABLE,
                    MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD ->
                        PlayScan.Unavailable("the phone does not have the Google code scanner module yet")

                    MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED ->
                        PlayScan.Unavailable("the camera permission was not granted to the code scanner")

                    else -> PlayScan.Unavailable(error.message ?: "the Google code scanner failed")
                }
            )
        }
}

/**
 * Asks Play services for the code scanner module and waits for the download inside one budget. False
 * means the phone cannot get it this way (no Play services, refused, no network, or longer than
 * [MODULE_INSTALL_TIMEOUT_MS]). No separate availability check runs first: an already installed
 * module answers on the install response itself, so nothing can stall outside the budget.
 */
private suspend fun installScannerModule(context: Context, scanner: GmsBarcodeScanner): Boolean {
    val client = runCatching { ModuleInstall.getClient(context) }.getOrNull() ?: return false
    return awaitModuleDownload(MODULE_INSTALL_TIMEOUT_MS, PlayServicesModuleDownload(client, scanner))
}

/**
 * One module download as the scanner needs it: [start] registers a status listener and reports the
 * outcome at most once, [stop] releases it. Production wraps `ModuleInstallClient`; a test supplies
 * both halves without Play services.
 */
internal interface ModuleDownload {
    fun start(onResult: (Boolean) -> Unit)

    fun stop()
}

private class PlayServicesModuleDownload(
    private val client: ModuleInstallClient,
    private val scanner: GmsBarcodeScanner
) : ModuleDownload {
    private var listener: InstallStatusListener? = null

    override fun start(onResult: (Boolean) -> Unit) {
        val registered = InstallStatusListener { update ->
            when (update.installState) {
                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> onResult(true)

                ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> onResult(false)

                else -> Unit
            }
        }
        listener = registered
        client.installModules(
            ModuleInstallRequest.newBuilder()
                .addApi(scanner)
                .setListener(registered)
                .build()
        )
            .addOnSuccessListener { response -> if (response.areModulesAlreadyInstalled()) onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    override fun stop() {
        listener?.let { client.unregisterListener(it) }
        listener = null
    }
}

/**
 * Waits for the module download inside [budgetMs]. The status listener is released in `finally`, so
 * a download that completes, fails, times out or is cancelled all unregister it.
 */
internal suspend fun awaitModuleDownload(budgetMs: Long, download: ModuleDownload): Boolean {
    val installed = withTimeoutOrNull(budgetMs) {
        try {
            suspendCancellableCoroutine { continuation ->
                // Every Play services callback may fire more than once; only the first moves the wait.
                val report: (Boolean) -> Unit = { ok ->
                    if (continuation.isActive) continuation.resume(ok)
                }
                try {
                    download.start(report)
                } catch (error: Exception) {
                    // A Play services binding failure is an unavailable module, not a broken scan.
                    report(false)
                }
            }
        } finally {
            download.stop()
        }
    }
    return installed ?: false
}

/** The module is a few hundred kilobytes; a download slower than this is not worth blocking on. */
private const val MODULE_INSTALL_TIMEOUT_MS = 20_000L
