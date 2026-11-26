package com.phonxiety.bridge.feature.onboarding

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** What one attempt at the Play services scanner produced. */
private sealed interface PlayScan {
    data class Code(val value: String) : PlayScan

    data object Cancelled : PlayScan

    /** No usable scanner: the module is missing, Play services refused, or the camera was denied. */
    data class Unavailable(val reason: String) : PlayScan
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

private suspend fun scanWithPlayServices(context: Context): PlayScan {
    val scanner = GmsBarcodeScanning.getClient(context, scannerOptions())
    val first = startScan(scanner)
    if (first !is PlayScan.Unavailable) return first
    if (!installScannerModule(context, scanner)) return first
    return startScan(scanner)
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
 * Asks Play services for the code scanner module and waits for the download. False means the phone
 * cannot get it this way (no Play services, refused, no network, or longer than the budget).
 */
private suspend fun installScannerModule(context: Context, scanner: GmsBarcodeScanner): Boolean {
    val client = ModuleInstall.getClient(context)
    val available = client.areModulesAvailable(scanner).awaitOrNull()
    if (available?.areModulesAvailable() == true) return true
    return withTimeoutOrNull(MODULE_INSTALL_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val listener = InstallStatusListener { update ->
                when (update.installState) {
                    ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED ->
                        if (continuation.isActive) continuation.resume(true)

                    ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                    ModuleInstallStatusUpdate.InstallState.STATE_CANCELED ->
                        if (continuation.isActive) continuation.resume(false)

                    else -> Unit
                }
            }
            val request = ModuleInstallRequest.newBuilder()
                .addApi(scanner)
                .setListener(listener)
                .build()
            client.installModules(request)
                .addOnSuccessListener { response ->
                    if (response.areModulesAlreadyInstalled() && continuation.isActive) continuation.resume(true)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(false)
                }
            continuation.invokeOnCancellation { client.unregisterListener(listener) }
        }
    } ?: false
}

/** One Play services call; a failed or cancelled task answers null instead of throwing. */
private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
    addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
    addOnCanceledListener { if (continuation.isActive) continuation.resume(null) }
}

/** The module is a few hundred kilobytes; a download slower than this is not worth blocking on. */
private const val MODULE_INSTALL_TIMEOUT_MS = 20_000L
