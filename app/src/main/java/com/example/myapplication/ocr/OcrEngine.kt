package com.example.myapplication.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OcrEngine {

    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun processBitmap(
        bitmap: Bitmap,
        onResult: (String) -> Unit
    ) {

        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                onResult(visionText.text)
            }
            .addOnFailureListener {
                it.printStackTrace()
                onResult("") // 防止 loading 卡住
            }
    }
}