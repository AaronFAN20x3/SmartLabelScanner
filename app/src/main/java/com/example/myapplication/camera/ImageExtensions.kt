package com.example.myapplication.camera

import android.graphics.*
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

fun ImageProxy.toBitmapX(): Bitmap {

    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    return bitmap
}
