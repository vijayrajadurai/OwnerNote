package com.shopai.app.util

import kotlin.math.max
import kotlin.math.min

/** The pixel arithmetic of [OcrImagePreprocessor], free of Android so it can be unit-tested. */
object OcrImageMath {
    /**
     * Bradley adaptive threshold over [gray] (0–255): a pixel is ink (black)
     * when it is [percent] % darker than the mean of its neighbourhood
     * (1/[windowDivisor] of the width). Returns ARGB black/white pixels.
     */
    fun bradley(gray: IntArray, w: Int, h: Int, windowDivisor: Int = 16, percent: Int = 15): IntArray {
        val stride = w + 1
        val integral = LongArray(stride * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += gray[y * w + x]
                integral[(y + 1) * stride + (x + 1)] = integral[y * stride + (x + 1)] + row
            }
        }
        val half = max(1, w / windowDivisor / 2)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y1 = max(0, y - half)
            val y2 = min(h - 1, y + half)
            for (x in 0 until w) {
                val x1 = max(0, x - half)
                val x2 = min(w - 1, x + half)
                val count = (x2 - x1 + 1).toLong() * (y2 - y1 + 1)
                val sum = integral[(y2 + 1) * stride + (x2 + 1)] - integral[y1 * stride + (x2 + 1)] -
                    integral[(y2 + 1) * stride + x1] + integral[y1 * stride + x1]
                val ink = gray[y * w + x].toLong() * count * 100 <= sum * (100 - percent)
                out[y * w + x] = if (ink) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return out
    }

    /**
     * The tilt (degrees, −6…6, 0.5° steps) at which dark pixels gather into the
     * sharpest horizontal rows (largest variance of the row profile). 0 unless
     * some angle is clearly better than straight.
     */
    fun skewDegrees(gray: IntArray, w: Int, h: Int): Float {
        val mean = gray.average()
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) if (gray[y * w + x] < mean * 0.6) { xs += x; ys += y }
        if (xs.size < 50) return 0f
        fun spread(deg: Float): Double {
            val t = Math.tan(Math.toRadians(deg.toDouble()))
            val rows = IntArray(h * 2)
            for (i in xs.indices) {
                val r = (ys[i] - xs[i] * t).toInt() + h / 2
                if (r in rows.indices) rows[r]++
            }
            val m = rows.average()
            return rows.sumOf { (it - m) * (it - m) }
        }
        val straight = spread(0f)
        var best = 0f
        var bestSpread = straight
        var a = -6f
        while (a <= 6f) {
            val s = spread(a)
            if (s > bestSpread) {
                bestSpread = s
                best = a
            }
            a += 0.5f
        }
        return if (bestSpread > straight * 1.15) best else 0f
    }
}
