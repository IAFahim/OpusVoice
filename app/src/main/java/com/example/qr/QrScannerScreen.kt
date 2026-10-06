package com.example.qr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.NeonEmerald
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen QR scanner. One decoded code is handed to [onPayload] as a typed
 * [QrPayload]; whatever the format, the screen closes itself after the first hit.
 */
@Composable
fun QrScannerScreen(
    hint: String,
    onPayload: (QrPayload) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            QrCameraPreview(onQrText = { onPayload(QrPayload.parse(it)) })
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Camera permission needed to scan QR codes",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera access")
                }
            }
        }

        // Viewfinder frame
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                .align(Alignment.Center)
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(280.dp)) {
                val stroke = 4.dp.toPx()
                val inset = 24.dp.toPx()
                drawRoundRect(
                    color = NeonEmerald.copy(alpha = 0.7f),
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - 2 * inset, size.height - 2 * inset),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(stroke)
                )
            }
        }

        // Top bar: close button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 36.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("qr_scanner_close")) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close QR scanner",
                    tint = Color.White
                )
            }
            Text(
                text = "Scan QR code",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.width(48.dp)) // balances the close button
        }

        // Bottom: hint + torch
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = hint,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun QrCameraPreview(onQrText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val delivered = remember { AtomicBoolean(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            val mediaImage = proxy.image
            if (mediaImage == null) {
                proxy.close()
                return@setAnalyzer
            }
            val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { barcodes ->
                    val text = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue
                    if (text != null && delivered.compareAndSet(false, true)) {
                        mainExecutor.execute { onQrText(text) }
                    }
                }
                .addOnCompleteListener { proxy.close() }
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        var boundProvider: ProcessCameraProvider? = null
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                boundProvider = provider
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                // camera busy or absent: the user still has the close button
                android.util.Log.w("QrScanner", "camera bind failed", e)
            }
        }, mainExecutor)

        onDispose {
            delivered.set(true) // in-flight decodes must not fire after disposal
            boundProvider?.unbindAll()
            analysis.clearAnalyzer()
            analysisExecutor.shutdown()
            scanner.close()
        }
    }

    LaunchedEffect(torchOn) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        FilledTonalButton(
            onClick = { torchOn = !torchOn },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
        ) {
            Text(if (torchOn) "Torch off" else "Torch on")
        }
    }
}
