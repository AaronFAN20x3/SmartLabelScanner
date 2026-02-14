package com.example.myapplication.ocr

import android.graphics.*
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get

object ImageProcessor {

    fun processAutoLabel(original: Bitmap): Bitmap {

        val width = original.width
        val height = original.height

        val grayBitmap = createBitmap(width, height)
        val canvas = Canvas(grayBitmap)
        val paint = Paint()

        val cm = ColorMatrix()
        cm.setSaturation(0f)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(original, 0f, 0f, paint)

        // 自动阈值
        val pixels = IntArray(width * height)
        grayBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var total = 0
        for (p in pixels) total += Color.red(p)
        val threshold = total / pixels.size

        // 找白色区域边界
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0

        for (x in 0 until width step 4) {
            for (y in 0 until height step 4) {

                val pixel = grayBitmap[x, y]
                val r = Color.red(pixel)

                if (r > threshold) {

                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }

        // 防止识别失败
        if (maxX - minX < 50 || maxY - minY < 50) {
            return grayBitmap
        }

        return Bitmap.createBitmap(
            original,
            minX,
            minY,
            maxX - minX,
            maxY - minY
        )
    }

    fun enhanceOnly(original: Bitmap): Bitmap {

        val width = original.width
        val height = original.height

        val grayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val canvas = Canvas(grayBitmap)
        val paint = Paint()

        val cm = ColorMatrix()
        cm.setSaturation(0f)

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(original, 0f, 0f, paint)

        return grayBitmap
    }

    fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {

        val matrix = Matrix()
        matrix.postRotate(angle)

        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true
        )
    }
}
