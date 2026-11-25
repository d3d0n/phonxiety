package com.phonxiety.bridge.feature.onboarding

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Google code scanner: no camera permission, no preview UI of our own. Returns a launcher function
 * because the scan is an activity-scoped task; a missing Play Services install is reported as a
 * failure so the paste-the-link path stays available.
 */
@Composable
fun rememberQrScanner(
    onCode: (String) -> Unit,
    onFailure: (String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    return remember(context, onCode, onFailure) {
        { launchQrScanner(context, onCode, onFailure) }
    }
}

private fun launchQrScanner(
    context: Context,
    onCode: (String) -> Unit,
    onFailure: (String) -> Unit
) {
    try {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue
                if (raw.isNullOrBlank()) {
                    onFailure("the QR code was empty")
                } else {
                    onCode(raw)
                }
            }
            .addOnFailureListener { error ->
                onFailure(error.message ?: "the QR scanner failed; paste the link instead")
            }
    } catch (error: Exception) {
        onFailure("no QR scanner is available on this phone; paste the pairing link instead")
    }
}
