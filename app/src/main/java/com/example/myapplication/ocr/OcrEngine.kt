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

    fun processStable(
        bitmap: Bitmap,
        onResult: (String) -> Unit
    ) {

        val base = bitmap

        processBitmap(base) { text1 ->

            if (text1.length > 30) {
                onResult(text1)
                return@processBitmap
            }

            val rotatedPlus = ImageProcessor.rotateBitmap(base, 15f)

            processBitmap(rotatedPlus) { text2 ->

                if (text2.length > text1.length) {
                    onResult(text2)
                    return@processBitmap
                }

                val rotatedMinus = ImageProcessor.rotateBitmap(base, -15f)

                processBitmap(rotatedMinus) { text3 ->

                    val best = listOf(text1, text2, text3)
                        .maxByOrNull { it.length } ?: ""

                    onResult(best)
                }
            }
        }
    }
}