package com.shopai.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.min

/**
 * Prepares camera/gallery images for on-device Tesseract OCR:
 * EXIF rotation, downscaling, grayscale, and contrast boost.
 */
object OcrImagePreprocessor {

    private const val MAX_SIDE_PX = 2048
    private const val MIN_SIDE_PX = 640

    fun prepare(context: Context, source: Bitmap, imageUri: Uri? = null): Bitmap {
        val rotation = imageUri?.let { readExifRotation(context, it) } ?: 0
        val oriented = applyRotation(source, rotation)
        val scaled = scaleForOcr(oriented)
        return enhanceForText(scaled)
    }

    private fun readExifRotation(context: Context, uri: Uri): Int {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
    }

    private fun applyRotation(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun scaleForOcr(source: Bitmap): Bitmap {
        val longest = max(source.width, source.height)
        val shortest = min(source.width, source.height)
        if (longest <= MAX_SIDE_PX && shortest >= MIN_SIDE_PX) return source

        val scale = when {
            longest > MAX_SIDE_PX -> MAX_SIDE_PX.toFloat() / longest
            shortest < MIN_SIDE_PX -> MIN_SIDE_PX.toFloat() / shortest
            else -> 1f
        }
        val targetW = max(1, (source.width * scale).toInt())
        val targetH = max(1, (source.height * scale).toInt())
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    private fun enhanceForText(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply {
                    setSaturation(0f)
                    val contrast = 1.35f
                    val translate = (-0.15f * 255f)
                    set(
                        floatArrayOf(
                            contrast, 0f, 0f, 0f, translate,
                            0f, contrast, 0f, 0f, translate,
                            0f, 0f, contrast, 0f, translate,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    )
                },
            )
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }
}
