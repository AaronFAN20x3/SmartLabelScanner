package com.example.myapplication.screen

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myapplication.camera.CameraController
import com.example.myapplication.camera.CameraPreview
import com.example.myapplication.camera.toBitmapX
import com.example.myapplication.ocr.ImageProcessor
import com.example.myapplication.ocr.OcrEngine
import com.example.myapplication.parser.LabelParser
import com.example.myapplication.parser.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@Composable
fun CameraScreen() {

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ---------- Permission ----------
    var hasPermission by remember { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            hasPermission = isGranted
        }

    LaunchedEffect(Unit) {
        hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasPermission) {
        Text("需要相机权限")
        return
    }

    // ---------- Core ----------
    val previewView = remember { PreviewView(context) }
    val controller = remember { CameraController(context) }
    val ocrEngine = remember { OcrEngine() }
    val parser = remember { LabelParser() }

    // ---------- UI States ----------
    var scanResult by remember { mutableStateOf<ScanResult?>(null) }

    // gate: avoid double trigger
    var isProcessing by remember { mutableStateOf(false) }

    // step text
    var processingStatus by remember { mutableStateOf("Idle") }

    // flash
    var flashGreen by remember { mutableStateOf(false) }
    var flashText by remember { mutableStateOf("") }

    var continuousMode by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        controller.startPreview(lifecycleOwner, previewView)
    }

    Box(modifier = Modifier.fillMaxSize()) {

        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            previewView = previewView
        )

        CaptureButton(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
        ) {
            if (isProcessing) return@CaptureButton

            isProcessing = true
            processingStatus = "Capturing..."

            controller.capturePhoto(
                onImageCaptured = { imageProxy ->

                    scope.launch {
                        try {
                            // 1) imageProxy -> bitmap (then close ASAP)
                            processingStatus = "Converting image..."
                            val bitmap: Bitmap = withContext(Dispatchers.Default) {
                                imageProxy.toBitmapX()
                            }
                            imageProxy.close()

                            // ✅ captured flash (now you can move camera immediately)
                            flashText = "Captured"
                            flashGreen = true
                            delay(300)
                            flashGreen = false
                            flashText = ""

                            // 2) enhance
                            processingStatus = "Enhancing..."
                            val enhanced: Bitmap = withContext(Dispatchers.Default) {
                                ImageProcessor.enhanceOnly(bitmap)
                            }

                            // 3) OCR 4 angles
                            val rotations = listOf(0f, 90f, 180f, 270f)

                            var bestText = ""
                            var bestLen = 0

                            for ((idx0, angle) in rotations.withIndex()) {
                                val angleIndex = idx0 + 1
                                val total = rotations.size

                                processingStatus = "Angle ${angleIndex}/${total} - Rotating ${angle.toInt()}°..."
                                val rotated: Bitmap = withContext(Dispatchers.Default) {
                                    ImageProcessor.rotateBitmap(enhanced, angle)
                                }

                                // OCR stable with progress
                                val text = suspendCancellableOcrStable(
                                    ocrEngine = ocrEngine,
                                    bitmap = rotated
                                ) { passMsg ->
                                    processingStatus = "Angle ${angleIndex}/${total} (${angle.toInt()}°) - $passMsg"
                                }

                                android.util.Log.d("OCR_RAW", text)

                                if (text.length > bestLen) {
                                    bestLen = text.length
                                    bestText = text
                                }
                            }

                            // 4) parse
                            processingStatus = "Parsing..."
                            val result: ScanResult = withContext(Dispatchers.Default) {
                                parser.parse(bestText)
                            }

                            android.util.Log.d("PARSED_RESULT", result.toString())

                            scanResult = result
                            processingStatus = "Done"

                        } catch (e: Exception) {
                            e.printStackTrace()
                            processingStatus = "Failed: ${e.message ?: "unknown"}"
                        } finally {
                            isProcessing = false
                        }
                    }
                },
                onError = { e ->
                    e.printStackTrace()
                    scope.launch {
                        processingStatus = "Capture failed: ${e.message ?: "unknown"}"
                        isProcessing = false
                    }
                }
            )
        }

        // processing overlay (do not cover green flash)
        if (isProcessing && !flashGreen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Text(processingStatus, color = Color.White)
            }
        }

        // green flash overlay
        if (flashGreen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF00C853).copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Text(flashText, color = Color.White)
            }
        }
    }

    // -------- Result dialog --------
    scanResult?.let { result ->

        var stockCode by remember(result) { mutableStateOf(result.stockCode ?: "") }
        var salesOrder by remember(result) { mutableStateOf(result.salesOrder ?: "") }
        var po by remember(result) { mutableStateOf(result.po ?: "") }
        var qty by remember(result) { mutableStateOf(result.qty ?: "") }
        var weight by remember(result) { mutableStateOf(result.weight ?: "") }

        AlertDialog(
            onDismissRequest = { scanResult = null },
            title = { Text("扫描结果") },
            text = {
                Column {
                    OutlinedTextField(
                        value = stockCode,
                        onValueChange = { stockCode = it },
                        label = { Text("Stock Code") },
                        isError = stockCode.isBlank()
                    )
                    OutlinedTextField(
                        value = salesOrder,
                        onValueChange = { salesOrder = it },
                        label = { Text("Sales Order") },
                        isError = salesOrder.isBlank()
                    )
                    OutlinedTextField(
                        value = po,
                        onValueChange = { po = it },
                        label = { Text("PO") },
                        isError = po.isBlank()
                    )
                    OutlinedTextField(
                        value = qty,
                        onValueChange = { qty = it },
                        label = { Text("Qty") },
                        isError = qty.isBlank()
                    )
                    OutlinedTextField(
                        value = weight,
                        onValueChange = { weight = it },
                        label = { Text("Weight") },
                        isError = weight.isBlank()
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { scanResult = null }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val finalResult = ScanResult(
                            stockCode = stockCode,
                            salesOrder = salesOrder,
                            po = po,
                            qty = qty,
                            weight = weight
                        )
                        android.util.Log.d("FINAL_RESULT", finalResult.toString())
                        scanResult = null
                    }
                ) { Text("Confirm") }
            }
        )
    }
}

/**
 * callback OCR -> suspend
 */
suspend fun suspendCancellableOcrStable(
    ocrEngine: OcrEngine,
    bitmap: Bitmap,
    onProgress: (String) -> Unit
): String = suspendCancellableCoroutine { cont ->
    ocrEngine.processStable(
        bitmap = bitmap,
        onProgress = onProgress,
        onResult = { text ->
            if (!cont.isCompleted) cont.resume(text)
        }
    )
}
