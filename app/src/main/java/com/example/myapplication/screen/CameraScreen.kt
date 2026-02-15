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

    // -------- Permission --------
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

    // -------- Core objects (remember) --------
    val previewView = remember { PreviewView(context) }
    val controller = remember { CameraController(context) }
    val ocrEngine = remember { OcrEngine() }
    val parser = remember { LabelParser() }

    // -------- UI states --------
    var scanResult by remember { mutableStateOf<ScanResult?>(null) }

    // processing gate: avoid double trigger
    var isProcessing by remember { mutableStateOf(false) }

    // show which step we are in
    var processingStatus by remember { mutableStateOf("Idle") }

    // flash overlay
    var flashGreen by remember { mutableStateOf(false) }
    var flashText by remember { mutableStateOf("") }

    // optional: you had it, keep it
    var continuousMode by remember { mutableStateOf(true) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        controller.startPreview(lifecycleOwner, previewView)
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // -------- Camera preview --------
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            previewView = previewView
        )

        // -------- Capture Button --------
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

                    // IMPORTANT: heavy work must go to coroutine background
                    scope.launch {
                        try {
                            // 1) Convert imageProxy -> bitmap (then close imageProxy ASAP)
                            processingStatus = "Converting image..."

                            val bitmap: Bitmap = withContext(Dispatchers.Default) {
                                imageProxy.toBitmapX()
                            }
                            imageProxy.close()

                            // ✅ "Captured" feedback (bitmap 已经拿到了，比较严谨)
                            flashText = "Captured"
                            flashGreen = true
                            delay(1000)
                            flashGreen = false
                            flashText = ""

                            // 2) Enhance
                            processingStatus = "Enhancing..."
                            val enhanced: Bitmap = withContext(Dispatchers.Default) {
                                ImageProcessor.enhanceOnly(bitmap)
                            }

                            // 3) OCR 4 angles, each uses processStable
                            val rotations = listOf(0f, 90f, 180f, 270f)

                            var bestText = ""
                            var bestLength = 0

                            for ((idx0, angle) in rotations.withIndex()) {
                                val angleIndex = idx0 + 1
                                val total = rotations.size

                                processingStatus = "Rotating ${angle.toInt()}° (${angleIndex}/${total})..."
                                val rotated: Bitmap = withContext(Dispatchers.Default) {
                                    ImageProcessor.rotateBitmap(enhanced, angle)
                                }

                                processingStatus = "OCR ${angleIndex}/${total} (${angle.toInt()}°)..."
                                val text = suspendCancellableOcrStable(
                                    ocrEngine = ocrEngine,
                                    bitmap = rotated
                                ) { stepMsg ->
                                    processingStatus = "OCR ${angleIndex}/${total} (${angle.toInt()}°) - $stepMsg"
                                }

                                android.util.Log.d("OCR_RAW", text)

                                if (text.length > bestLength) {
                                    bestLength = text.length
                                    bestText = text
                                }
                            }

                            // 4) Parse
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

        // -------- Processing overlay (do NOT cover green flash) --------
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

        // -------- Green flash overlay --------
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
                TextButton(onClick = { scanResult = null }) {
                    Text("Cancel")
                }
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

                        if (continuousMode) {
                            scanResult = null
                        } else {
                            scanResult = null
                        }
                    }
                ) {
                    Text("Confirm")
                }
            }
        )
    }
}

/**
 * callback OCR -> suspend
 *
 * 要求：你的 OcrEngine 有这个函数：
 * processStable(bitmap, onProgress, onResult)
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