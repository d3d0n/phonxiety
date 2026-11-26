package com.phonxiety.bridge.feature.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The in-app scanner: a CameraX preview with the bundled ML Kit barcode model, for phones where the
 * Google code scanner module cannot be installed. It exists only while this dialog is open, so the
 * camera is released on dismiss, and the paste-the-link path stays available under it.
 */
@Composable
internal fun QrScanDialog(
    onCode: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(cameraGranted(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
    }
    LaunchedEffect(granted) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Scan the pairing code") },
        text = {
            if (granted) {
                CameraQrPreview(
                    onCode = onCode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                )
            } else {
                Text("The camera permission is needed to scan the code. Paste the pairing link instead if you prefer.")
            }
        }
    )
}

@Composable
private fun CameraQrPreview(
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val scanner = remember { BarcodeScanning.getClient(barcodeOptions()) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val found = remember { AtomicBoolean(false) }
    val code = rememberUpdatedState(onCode)
    var failure by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val provider = ProcessCameraProvider.getInstance(context)
        var analysis: ImageAnalysis? = null
        provider.addListener(
            {
                runCatching {
                    val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
                    val pipeline = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    pipeline.setAnalyzer(executor) { proxy -> decode(proxy, scanner, found) { raw -> code.value(raw) } }
                    analysis = pipeline
                    val camera = provider.get()
                    camera.unbindAll()
                    camera.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        pipeline
                    )
                }.onFailure { error -> failure = error.message ?: error.javaClass.simpleName }
            },
            ContextCompat.getMainExecutor(context)
        )
        onDispose {
            runCatching { analysis?.clearAnalyzer() }
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
            scanner.close()
        }
    }

    val message = failure
    if (message == null) {
        AndroidView(factory = { previewView }, modifier = modifier)
    } else {
        Text(
            text = "The camera could not start ($message). Paste the pairing link instead.",
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun barcodeOptions(): BarcodeScannerOptions = BarcodeScannerOptions.Builder()
    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
    .build()

@OptIn(ExperimentalGetImage::class)
private fun decode(
    proxy: ImageProxy,
    scanner: BarcodeScanner,
    found: AtomicBoolean,
    onCode: (String) -> Unit
) {
    val media = proxy.image
    if (media == null || found.get()) {
        proxy.close()
        return
    }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            val raw = barcodes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
            if (raw != null && found.compareAndSet(false, true)) onCode(raw)
        }
        .addOnCompleteListener { proxy.close() }
}

private fun cameraGranted(context: Context): Boolean = ContextCompat.checkSelfPermission(
    context,
    Manifest.permission.CAMERA
) == PackageManager.PERMISSION_GRANTED
