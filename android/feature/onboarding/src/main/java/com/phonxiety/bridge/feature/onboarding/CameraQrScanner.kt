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
import androidx.camera.core.UseCase
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
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
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
    val code = rememberUpdatedState(onCode)
    var failure by remember { mutableStateOf<String?>(null) }
    val release = remember {
        val released = AtomicBoolean(false)
        val closeOnce: () -> Unit = {
            if (released.compareAndSet(false, true)) {
                executor.shutdown()
                scanner.close()
            }
        }
        closeOnce
    }

    DisposableEffect(Unit) {
        val report: (Throwable) -> Unit = { error -> failure = error.message ?: error.javaClass.simpleName }
        val future = try {
            ProcessCameraProvider.getInstance(context)
        } catch (error: Exception) {
            // No session was created, but the scanner and executor above are already live: the
            // dialog's own disposal is the only owner that can release them, exactly once.
            report(error)
            return@DisposableEffect onDispose { release() }
        }
        val session = CameraSession(
            future = future,
            mainExecutor = ContextCompat.getMainExecutor(context),
            onError = report,
            bind = { provider, camera ->
                val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    decode(proxy, scanner, camera) { raw -> camera.deliver(raw) { code.value(it) } }
                }
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                listOf<UseCase>(preview, analysis)
            },
            unbind = { provider, bound ->
                bound.filterIsInstance<ImageAnalysis>().forEach { runCatching { it.clearAnalyzer() } }
                provider.unbind(*bound.toTypedArray())
            },
            release = release
        )
        session.start()
        onDispose { session.dispose() }
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

/**
 * One camera session, alive for as long as the dialog is.
 *
 * The provider is asked for through a future whose listener runs on the main executor, and that
 * listener can run long after the dialog was dismissed. Nothing here waits for the future — not even
 * [dispose] — so the main thread never blocks on the provider. [dispose] marks the session gone
 * before it unbinds anything, so a late listener binds no camera onto a released executor and
 * scanner, and the barcode callback asks [wantsCode] so a code from a closed dialog is dropped.
 */
internal class CameraSession<P, H>(
    private val future: ListenableFuture<P>,
    private val mainExecutor: Executor,
    private val onError: (Throwable) -> Unit,
    private val bind: (P, CameraSession<P, H>) -> H,
    private val unbind: (P, H) -> Unit,
    private val release: () -> Unit
) {
    private val disposed = AtomicBoolean(false)
    private val delivered = AtomicBoolean(false)

    // Written on the main thread only: the provider resolves there and the dialog closes there.
    private var provider: P? = null
    private var handles: H? = null

    /** Listens for the resolved provider; an already resolved future runs the listener at once. */
    fun start() {
        future.addListener({ initialize() }, mainExecutor)
    }

    /** Whether a code is still worth decoding: the dialog is open and nothing was delivered yet. */
    fun wantsCode(): Boolean = !disposed.get() && !delivered.get()

    /** Hands the first code of a live session to the dialog; a code after dismissal is dropped. */
    fun deliver(raw: String, onCode: (String) -> Unit) {
        if (disposed.get() || !delivered.compareAndSet(false, true)) return
        onCode(raw)
    }

    /**
     * Ends the session: no bind happens after this, only this session's use cases are unbound, and
     * the executor and the scanner are released once, after the unbind.
     */
    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        val resolved = provider
        val bound = handles
        if (resolved != null && bound != null) runCatching { unbind(resolved, bound) }
        release()
    }

    private fun initialize() {
        if (disposed.get()) return
        val resolved = try {
            if (!future.isDone) return
            future.get()
        } catch (error: Throwable) {
            onError(error)
            return
        }
        if (disposed.get()) return
        provider = resolved
        handles = try {
            bind(resolved, this)
        } catch (error: Throwable) {
            onError(error)
            return
        }
    }
}

private fun barcodeOptions(): BarcodeScannerOptions = BarcodeScannerOptions.Builder()
    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
    .build()

@OptIn(ExperimentalGetImage::class)
private fun decode(
    proxy: ImageProxy,
    scanner: BarcodeScanner,
    session: CameraSession<*, *>,
    onFound: (String) -> Unit
) {
    val media = proxy.image
    if (media == null || !session.wantsCode()) {
        proxy.close()
        return
    }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    val task = try {
        scanner.process(image)
    } catch (error: Exception) {
        // A frame still in flight when the dialog closed meets the closed scanner: drop it.
        proxy.close()
        return
    }
    task
        .addOnSuccessListener { barcodes ->
            barcodes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }?.let(onFound)
        }
        .addOnCompleteListener { proxy.close() }
}

private fun cameraGranted(context: Context): Boolean = ContextCompat.checkSelfPermission(
    context,
    Manifest.permission.CAMERA
) == PackageManager.PERMISSION_GRANTED
