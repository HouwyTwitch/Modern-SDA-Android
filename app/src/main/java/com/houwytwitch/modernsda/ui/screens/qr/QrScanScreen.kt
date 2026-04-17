package com.houwytwitch.modernsda.ui.screens.qr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScanScreen(
    onBack: () -> Unit,
    viewModel: QrScanViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Controls whether the analyzer processes frames
    val scanning = remember { AtomicBoolean(true) }
    LaunchedEffect(uiState) {
        scanning.set(uiState is QrScanViewModel.UiState.Idle)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (hasPermission) {
            CameraPreviewView(
                onQrDetected = { viewModel.onQrCodeDetected(it) },
                scanning = scanning,
            )

            // Semi-transparent overlay outside the scan frame
            ScanOverlay()
        } else {
            NoCameraPermissionState(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) }
            )
        }

        // Back button
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopStart),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
        }

        // Bottom label when idle
        if (uiState is QrScanViewModel.UiState.Idle && hasPermission) {
            Text(
                text = "Point at the Steam QR login code",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 60.dp)
                    .padding(horizontal = 32.dp),
            )
        }

        // Status overlays
        when (val state = uiState) {
            is QrScanViewModel.UiState.Processing -> ProcessingOverlay()
            is QrScanViewModel.UiState.Success    -> SuccessOverlay(onDone = onBack)
            is QrScanViewModel.UiState.Error      -> ErrorOverlay(
                message  = state.message,
                onBack   = onBack,
                onRetry  = { viewModel.resetState() },
            )
            is QrScanViewModel.UiState.Idle -> Unit
        }
    }
}

// ── Camera preview ────────────────────────────────────────────────────────────

@Composable
private fun CameraPreviewView(
    onQrDetected: (String) -> Unit,
    scanning: AtomicBoolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    val cameraProvider = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider.value?.unbindAll()
            scanner.close()
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                cameraProvider.value = provider

                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(executor) { imageProxy ->
                    if (!scanning.get()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val mediaImage = imageProxy.image
                    if (mediaImage == null) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val inputImage = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees,
                    )
                    scanner.process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            barcodes.firstOrNull()?.rawValue?.let { onQrDetected(it) }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }

                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize(),
    )
}

// ── Scan overlay (frame corners) ──────────────────────────────────────────────

@Composable
private fun ScanOverlay() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(240.dp)) {
            val corner = 40.dp.toPx()
            val stroke = 4.dp.toPx()
            val w = size.width
            val h = size.height
            val color = Color.White

            // Top-left corner
            drawLine(color, Offset(0f, corner), Offset(0f, 0f), stroke)
            drawLine(color, Offset(0f, 0f), Offset(corner, 0f), stroke)
            // Top-right corner
            drawLine(color, Offset(w - corner, 0f), Offset(w, 0f), stroke)
            drawLine(color, Offset(w, 0f), Offset(w, corner), stroke)
            // Bottom-left corner
            drawLine(color, Offset(0f, h - corner), Offset(0f, h), stroke)
            drawLine(color, Offset(0f, h), Offset(corner, h), stroke)
            // Bottom-right corner
            drawLine(color, Offset(w - corner, h), Offset(w, h), stroke)
            drawLine(color, Offset(w, h - corner), Offset(w, h), stroke)
        }
    }
}

// ── Status overlays ───────────────────────────────────────────────────────────

@Composable
private fun ProcessingOverlay() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Approving login…")
            }
        }
    }
}

@Composable
private fun SuccessOverlay(onDone: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text("Login approved!", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "The sign-in was successfully confirmed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, onBack: () -> Unit, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text("QR Login Failed", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
                    Button(onClick = onRetry, modifier = Modifier.weight(1f))         { Text("Try Again") }
                }
            }
        }
    }
}

@Composable
private fun NoCameraPermissionState(onRequestPermission: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(64.dp),
            )
            Text(
                "Camera permission is required to scan QR codes",
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRequestPermission) { Text("Grant Permission") }
        }
    }
}
