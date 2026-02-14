package com.example.myapplication.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myapplication.camera.CameraController
import com.example.myapplication.camera.CameraPreview
import com.example.myapplication.ocr.OcrEngine
import com.example.myapplication.parser.LabelParser
import com.example.myapplication.parser.ScanResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.background
import com.example.myapplication.ocr.ImageProcessor
import androidx.compose.ui.graphics.Color
import com.example.myapplication.camera.toBitmapX
@Composable
fun CameraScreen() {

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            hasPermission = isGranted
        }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            hasPermission = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasPermission) {

        val previewView = remember { PreviewView(context) }
        val controller = remember { CameraController(context) }
        val ocrEngine = remember { OcrEngine() }
        val parser = remember { LabelParser() }
        var scanResult by remember { mutableStateOf<ScanResult?>(null) }
        var isProcessing by remember { mutableStateOf(false) }
        var continuousMode by remember { mutableStateOf(true) }

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

                controller.capturePhoto { imageProxy ->

                    val bitmap = imageProxy.toBitmapX()
                    val enhanced = ImageProcessor.enhanceOnly(bitmap)
                    ocrEngine.processStable(enhanced) { text ->

                        val result = parser.parse(text)

                        scanResult = result
                        isProcessing = false

                        imageProxy.close()
                    }
                }
            }

            if (isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Processing...", color = Color.White)
                }
            }
        }

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
                    TextButton(
                        onClick = { scanResult = null }
                    ) {
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
                                scanResult = null // 关闭弹窗但继续相机
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

    } else {
        Text("需要相机权限")
    }
}
