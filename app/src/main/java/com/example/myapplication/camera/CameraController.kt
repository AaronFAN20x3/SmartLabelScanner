package com.example.myapplication.camera

import android.content.Context
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.camera.view.PreviewView
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImageCaptureException
import android.graphics.ImageFormat

class CameraController(
    private val context: Context
) {

    private var imageCapture: ImageCapture? = null

    fun startPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({

            val cameraProvider =
                cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture =
                ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setBufferFormat(ImageFormat.JPEG)
                    .build()

            val cameraSelector =
                CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()

            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )

        }, ContextCompat.getMainExecutor(context))
    }

    fun capturePhoto(onImageCaptured: (ImageProxy) -> Unit) {

        imageCapture?.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {

                override fun onCaptureSuccess(image: ImageProxy) {
                    onImageCaptured(image)
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                }
            }
        )
    }
}
