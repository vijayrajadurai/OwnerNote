package com.shopai.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Crop as fractions (0..1) of the rotated image. */
data class CropFractions(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val FULL = CropFractions(0f, 0f, 1f, 1f)
    }
}

/**
 * Prepares a photo of a handwritten note (handwriting only — printed bills
 * use OcrImagePreprocessor, unchanged):
 * EXIF + user rotation → user crop → auto-straighten → find the writing.
 *
 * No black-and-white thresholding: it broke thin pen strokes, and the
 * handwriting OCR (ML Kit) reads the colour image better.
 */
object HandwritingImageProcessor {
    /**
     * [page]: the straightened page (stored; OCR boxes refer to it).
     * [writingRegion]: where the ink is on [page], or null if not found.
     */
    data class Result(val page: Bitmap, val writingRegion: CropFractions?, val deskewDegrees: Float)

    private const val MAX_SIDE = 2600

    fun load(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        return rotate(bitmap, exifRotation(context, uri))
    }

    /** Pixel size of the image without decoding it. */
    fun imageSize(context: Context, uri: Uri): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        return bounds.outWidth to bounds.outHeight
    }

    fun process(context: Context, uri: Uri, userRotation: Int, crop: CropFractions): Result? {
        val oriented = load(context, uri) ?: return null
        val cropped = crop(rotate(oriented, userRotation), crop)
        val scaled = scaleDown(cropped)
        val angle = estimateSkew(toGray(scaled), scaled.width, scaled.height)
        val page = if (abs(angle) >= 0.4f) rotateFree(scaled, -angle) else scaled
        return Result(page = page, writingRegion = findWriting(page), deskewDegrees = angle)
    }

    /** Where the ink is, analysed on a small copy for speed. */
    fun findWriting(page: Bitmap): CropFractions? {
        val factor = 700f / max(page.width, page.height)
        val small = if (factor < 1f) Bitmap.createScaledBitmap(page, max(1, (page.width * factor).roundToInt()), max(1, (page.height * factor).roundToInt()), true) else page
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        return HandwritingImageAnalysis.inkRegion(px, small.width, small.height)
    }

    /** Representations of a handwriting crop tried by the multi-pass OCR, in order. */
    enum class Variant { ORIGINAL, FOREGROUND_INK, CONTRAST, GRAYSCALE, DENOISE_CONTRAST }

    fun variant(source: Bitmap, variant: Variant): Bitmap {
        if (variant == Variant.ORIGINAL) return source
        val w = source.width
        val h = source.height
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, 0, 0, w, h)
        val out = when (variant) {
            Variant.ORIGINAL -> px
            Variant.FOREGROUND_INK -> HandwritingImageAnalysis.foregroundInk(px)
            Variant.CONTRAST -> HandwritingImageAnalysis.contrast(px)
            Variant.GRAYSCALE -> HandwritingImageAnalysis.grayscale(px)
            Variant.DENOISE_CONTRAST -> HandwritingImageAnalysis.denoiseContrast(px, w, h)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Photo usability, from the thumbnail plus the original's size. */
    fun quality(thumbnail: Bitmap, originalWidth: Int, originalHeight: Int): PhotoQuality {
        val factor = 600f / max(thumbnail.width, thumbnail.height)
        val small = if (factor < 1f) Bitmap.createScaledBitmap(thumbnail, max(1, (thumbnail.width * factor).roundToInt()), max(1, (thumbnail.height * factor).roundToInt()), true) else thumbnail
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        return HandwritingImageAnalysis.quality(px, small.width, small.height, minOf(originalWidth, originalHeight), maxOf(originalWidth, originalHeight))
    }

    // ---- geometry ----

    private fun exifRotation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    }.getOrDefault(0)

    fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(d.toFloat()) }, true)
    }

    fun crop(source: Bitmap, crop: CropFractions): Bitmap {
        if (crop == CropFractions.FULL) return source
        val left = (crop.left.coerceIn(0f, 1f) * source.width).roundToInt()
        val top = (crop.top.coerceIn(0f, 1f) * source.height).roundToInt()
        val right = (crop.right.coerceIn(0f, 1f) * source.width).roundToInt().coerceAtLeast(left + 16).coerceAtMost(source.width)
        val bottom = (crop.bottom.coerceIn(0f, 1f) * source.height).roundToInt().coerceAtLeast(top + 16).coerceAtMost(source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= MAX_SIDE) return source
        val factor = MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(source, max(1, (source.width * factor).roundToInt()), max(1, (source.height * factor).roundToInt()), true)
    }

    private fun rotateFree(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(rotated).apply {
            drawColor(Color.WHITE)
            translate(source.width / 2f, source.height / 2f)
            concat(matrix)
            translate(-source.width / 2f, -source.height / 2f)
            drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return rotated
    }

    private fun toGray(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            pixels[i] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        return pixels
    }

    /**
     * Estimates how tilted the writing is (−8°…+8°): rotates the dark
     * pixels of a small copy and keeps the angle where rows line up best.
     */
    private fun estimateSkew(gray: IntArray, width: Int, height: Int): Float {
        val step = max(1, max(width, height) / 700)
        val sw = width / step; val sh = height / step
        if (sw < 50 || sh < 50) return 0f
        val small = IntArray(sw * sh) { i -> gray[(i / sw) * step * width + (i % sw) * step] }
        val mean = small.average()
        val ink = ArrayList<Pair<Int, Int>>()
        for (y in 0 until sh) for (x in 0 until sw) if (small[y * sw + x] < mean * 0.7) ink += x to y
        if (ink.size < 200) return 0f
        var bestAngle = 0f
        var bestScore = Double.NEGATIVE_INFINITY
        var angle = -8f
        while (angle <= 8.001f) {
            val radians = Math.toRadians(angle.toDouble())
            val s = sin(radians); val c = cos(radians)
            val rows = IntArray(sh * 2 + 1)
            for ((x, y) in ink) {
                val r = (y * c - x * s).roundToInt() + sh / 2
                if (r in rows.indices) rows[r]++
            }
            var score = 0.0
            for (i in 1 until rows.size) { val d = (rows[i] - rows[i - 1]).toDouble(); score += d * d }
            if (score > bestScore) { bestScore = score; bestAngle = angle }
            angle += 0.5f
        }
        return bestAngle
    }
}
