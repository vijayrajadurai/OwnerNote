package com.shopai.app.util

import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/**
 * Real handwritten-note photos (the app's processed page, scaled to 1000 px)
 * stored as gzipped raw RGB so plain JVM tests can read them.
 */
class RealNotePage(val width: Int, val height: Int, val pixels: IntArray)

fun loadRealNotePage(name: String): RealNotePage {
    val stream = RealNotePage::class.java.getResourceAsStream("/handwriting/$name.rgb.gz")
        ?: error("missing test image $name")
    DataInputStream(GZIPInputStream(stream).buffered()).use { input ->
        val w = input.readInt()
        val h = input.readInt()
        val bytes = ByteArray(w * h * 3)
        input.readFully(bytes)
        val px = IntArray(w * h) { i ->
            val r = bytes[i * 3].toInt() and 0xFF
            val g = bytes[i * 3 + 1].toInt() and 0xFF
            val b = bytes[i * 3 + 2].toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return RealNotePage(w, h, px)
    }
}
