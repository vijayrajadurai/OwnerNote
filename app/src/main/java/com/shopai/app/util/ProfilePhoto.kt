package com.shopai.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

private const val MaxDimensionPx = 512

/**
 * Copies a picked gallery image into app-internal storage, downscaling it
 * first so a multi-megabyte camera photo doesn't get stored (and re-decoded
 * for every avatar render) at full resolution for a ~48dp circle.
 */
fun copyPickedImageInto(context: Context, source: Uri, destination: File): Boolean {
    return runCatching {
        val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() } ?: return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MaxDimensionPx)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
        FileOutputStream(destination).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()
        true
    }.getOrDefault(false)
}

private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sample = 1
    while (width / sample > maxDimension * 2 || height / sample > maxDimension * 2) {
        sample *= 2
    }
    return sample
}
