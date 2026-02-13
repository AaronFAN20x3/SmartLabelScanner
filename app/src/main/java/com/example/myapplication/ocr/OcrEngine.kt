package com.example.myapplication.ocr

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import androidx.camera.core.ImageProxy
@androidx.camera.core.ExperimentalGetImage
class OcrEngine {

    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    //    fun processImage(
    //        file: File,
    //        onResult: (String) -> Unit
    //    ) {
    //
    //        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
    //        val image = InputImage.fromBitmap(bitmap, 0)
    //
    //        recognizer.process(image)
    //            .addOnSuccessListener { visionText ->
    //                onResult(visionText.text)
    //            }
    //            .addOnFailureListener {
    //                it.printStackTrace()
    //            }
    //    }

    fun processImageProxy(
        imageProxy: ImageProxy,
        onResult: (String) -> Unit
    ) {

        val mediaImage = imageProxy.image ?: return

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                onResult(visionText.text)
            }
            .addOnFailureListener {
                it.printStackTrace()
            }
            .addOnCompleteListener {
                imageProxy.close() // 非常重要
            }
    }
}
