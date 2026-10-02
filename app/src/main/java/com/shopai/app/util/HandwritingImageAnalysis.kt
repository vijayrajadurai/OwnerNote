package com.shopai.app.util

import kotlin.math.max
import kotlin.math.min

/** Why a handwritten-note photo may be too poor to read. */
enum class PhotoProblem { BLURRY, TOO_DARK, TOO_BRIGHT, LOW_RESOLUTION, NO_WRITING }

data class PhotoQuality(
    val sharpness: Double,
    val brightness: Double,
    val inkRatio: Double,
    val problems: Set<PhotoProblem>,
) {
    val acceptable: Boolean get() = problems.isEmpty()
}

/**
 * Pure pixel analysis for handwritten notes (ARGB ints, no Android types),
 * so it can be unit-tested: where the writing is, and whether the photo is
 * usable. Handwriting-only — printed bills never go through this.
 */
object HandwritingImageAnalysis {

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF

    /**
     * Coloured pen ink (blue/violet/red/green): clearly saturated and not
     * light. Faint back-page show-through is too pale to pass, and skin or
     * wood (orange/brown: a thumb holding the page, the desk) is excluded.
     */
    private fun isColouredInk(c: Int): Boolean {
        val mx = max(r(c), max(g(c), b(c)))
        val mn = min(r(c), min(g(c), b(c)))
        return mx - mn > 45 && mn < 170 && !isSkinOrWood(c)
    }

    /**
     * Orange/brown hues (about 12°–60°): skin, wood, cardboard — never pen
     * ink. Dull reds (skin in shadow) too; red pen ink is strongly saturated.
     */
    private fun isSkinOrWood(c: Int): Boolean {
        val r = r(c); val g = g(c); val b = b(c)
        if (r < g || r < b) return false
        val mn = min(g, b)
        if (r == mn) return false
        if (g < b) return (r - mn) < r * 0.55f // magenta-red side
        val hue = 60f * (g - b) / (r - b)
        return hue >= 12f || (r - mn) < r * 0.55f
    }

    /** Black/dark ink (or pencil): dark and fairly neutral. */
    private fun isDarkInk(c: Int): Boolean = max(r(c), max(g(c), b(c))) < 95

    /**
     * Pen strokes are thin; a thumb, a dark desk or a keyboard is a solid
     * area. An ink pixel whose neighbours a short way off in all four
     * directions are also ink (or off the photo) is inside such an area;
     * that area, including its rim, is dropped.
     */
    private fun dropSolidAreas(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val d = max(6, max(width, height) / 40)
        fun inkAt(x: Int, y: Int) = x < 0 || y < 0 || x >= width || y >= height || mask[y * width + x]
        // Integral image of the "inside a solid area" pixels.
        val sum = IntArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var run = 0
            for (x in 0 until width) {
                val core = mask[y * width + x] && inkAt(x - d, y) && inkAt(x + d, y) && inkAt(x, y - d) && inkAt(x, y + d)
                if (core) run++
                sum[(y + 1) * (width + 1) + x + 1] = sum[y * (width + 1) + x + 1] + run
            }
        }
        // Keep ink with no solid-area pixel within d (so the area's rim goes too).
        return BooleanArray(mask.size) { i ->
            if (!mask[i]) return@BooleanArray false
            val x = i % width
            val y = i / width
            val x0 = max(0, x - d); val x1 = min(width, x + d + 1)
            val y0 = max(0, y - d); val y1 = min(height, y + d + 1)
            val cores = sum[y1 * (width + 1) + x1] - sum[y0 * (width + 1) + x1] - sum[y1 * (width + 1) + x0] + sum[y0 * (width + 1) + x0]
            cores == 0
        }
    }

    /** Ink mask used to find the writing (coloured ink, solid areas removed). */
    private fun colouredMask(pixels: IntArray, width: Int, height: Int): BooleanArray =
        dropSolidAreas(BooleanArray(pixels.size) { isColouredInk(pixels[it]) }, width, height)

    /**
     * Box around the writing, as fractions with a margin, or null if no
     * writing is found. Prefers coloured ink (so dark cables/desk in the
     * photo are ignored); otherwise uses dark ink minus long ruled lines.
     */
    fun inkRegion(pixels: IntArray, width: Int, height: Int): CropFractions? {
        val coloured = colouredMask(pixels, width, height)
        val colouredCount = coloured.count { it }
        val mask = if (colouredCount > pixels.size * 0.0004) coloured else {
            val dark = dropSolidAreas(BooleanArray(pixels.size) { isDarkInk(pixels[it]) }, width, height)
            // Rows that are mostly dark are rules, edges or shadows, not writing.
            for (y in 0 until height) {
                var count = 0
                for (x in 0 until width) if (dark[y * width + x]) count++
                if (count > width * 0.30) for (x in 0 until width) dark[y * width + x] = false
            }
            dark
        }
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (i in mask.indices) if (mask[i]) { xs += i % width; ys += i / width }
        if (xs.size < max(30, pixels.size / 20000)) return null
        xs.sort(); ys.sort()
        // Robust edges: ignore the outermost 1% of ink pixels (specks, bits of cable).
        val x0 = xs[(xs.size * 0.01).toInt()]; val x1 = xs[(xs.size * 0.99).toInt().coerceAtMost(xs.lastIndex)]
        val y0 = ys[(ys.size * 0.01).toInt()]; val y1 = ys[(ys.size * 0.99).toInt().coerceAtMost(ys.lastIndex)]
        val mx = (x1 - x0) * 0.08f + width * 0.02f
        val my = (y1 - y0) * 0.12f + height * 0.02f
        val region = CropFractions(
            ((x0 - mx) / width).coerceIn(0f, 1f),
            ((y0 - my) / height).coerceIn(0f, 1f),
            ((x1 + mx) / width).coerceIn(0f, 1f),
            ((y1 + my) / height).coerceIn(0f, 1f),
        )
        // Tiny regions are noise; a region covering nearly everything gains nothing.
        val area = (region.right - region.left) * (region.bottom - region.top)
        return region.takeIf { area > 0.01f && area < 0.9f }
    }

    /**
     * The handwritten lines inside the writing region, top to bottom, as
     * (top, bottom) fractions with padding. Ruled lines are ignored because
     * only coloured ink is counted (or dark ink minus long rules).
     */
    fun inkRows(pixels: IntArray, width: Int, height: Int): List<Pair<Float, Float>> {
        val coloured = colouredMask(pixels, width, height)
        val useColoured = coloured.count { it } > pixels.size * 0.001
        val ink = if (useColoured) coloured else dropSolidAreas(BooleanArray(pixels.size) { isDarkInk(pixels[it]) }, width, height)
        val counts = IntArray(height)
        for (y in 0 until height) {
            var c = 0
            for (x in 0 until width) if (ink[y * width + x]) c++
            // Mostly-dark rows are rules/edges, not writing.
            counts[y] = if (!useColoured && c > width * 0.30) 0 else c
        }
        val minInk = max(2, (width * 0.004).toInt())
        val maxGap = max(2, (height * 0.02).toInt())
        val bands = mutableListOf<IntArray>()
        var start = -1
        var lastInk = -1
        for (y in 0 until height) {
            if (counts[y] >= minInk) {
                if (start < 0) start = y
                lastInk = y
            } else if (start >= 0 && y - lastInk > maxGap) {
                bands += intArrayOf(start, lastInk)
                start = -1
            }
        }
        if (start >= 0) bands += intArrayOf(start, lastInk)
        return bands
            .filter { (it[1] - it[0]) >= max(3, (height * 0.015).toInt()) }
            .map { b ->
                val pad = (b[1] - b[0]) * 0.35f
                ((b[0] - pad) / height).coerceIn(0f, 1f) to ((b[1] + pad) / height).coerceIn(0f, 1f)
            }
    }

    /**
     * The words of one handwritten row, left to right, as (left, right)
     * fractions with a little padding. Words are split where the gap in
     * the ink is wider than about half the row's height — letters inside a
     * word are much closer than that; "Kumar   2000   Credit" gives three.
     */
    fun inkWords(pixels: IntArray, width: Int, height: Int): List<Pair<Float, Float>> {
        val coloured = colouredMask(pixels, width, height)
        val useColoured = coloured.count { it } > pixels.size * 0.001
        val ink = if (useColoured) coloured else dropSolidAreas(BooleanArray(pixels.size) { isDarkInk(pixels[it]) }, width, height)
        val columns = IntArray(width)
        for (y in 0 until height) for (x in 0 until width) if (ink[y * width + x]) columns[x]++
        val minGap = max(4, (height * 0.5).toInt())
        val spans = mutableListOf<IntArray>()
        var start = -1
        var lastInk = -1
        for (x in 0 until width) {
            if (columns[x] > 0) {
                if (start < 0) start = x
                lastInk = x
            } else if (start >= 0 && x - lastInk > minGap) {
                spans += intArrayOf(start, lastInk)
                start = -1
            }
        }
        if (start >= 0) spans += intArrayOf(start, lastInk)
        // Specks (a dot, a stray mark) are not words.
        val inkPerSpan = spans.map { s -> (s[0]..s[1]).sumOf { columns[it] } }
        val biggest = inkPerSpan.maxOrNull() ?: return emptyList()
        val pad = height * 0.25f
        return spans.filterIndexed { i, _ -> inkPerSpan[i] >= biggest * 0.08 }
            .map { s -> ((s[0] - pad) / width).coerceIn(0f, 1f) to ((s[1] + pad) / width).coerceIn(0f, 1f) }
    }

    // ------------------------------------------------ OCR image variants
    // Gentle, stroke-preserving versions of a handwriting crop, each tried by
    // the OCR (no black/white thresholding, no thickening, no sharpening).

    private fun gray(c: Int) = (r(c) * 299 + g(c) * 587 + b(c) * 114) / 1000
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Plain grayscale. */
    fun grayscale(pixels: IntArray): IntArray = IntArray(pixels.size) { val v = gray(pixels[it]); argb(v, v, v) }

    /**
     * Moderate contrast: stretches brightness between the 2nd and 98th
     * percentiles (colour kept). Does nothing on an already-contrasty crop.
     */
    fun contrast(pixels: IntArray): IntArray {
        val histogram = IntArray(256)
        for (c in pixels) histogram[gray(c)]++
        fun percentile(p: Double): Int {
            var seen = 0
            val target = (pixels.size * p).toInt()
            for (v in 0..255) { seen += histogram[v]; if (seen > target) return v }
            return 255
        }
        val lo = percentile(0.02)
        val hi = percentile(0.98)
        if (hi - lo < 40 || (lo < 10 && hi > 245)) return pixels.copyOf()
        val scale = 255f / (hi - lo)
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            argb(((r(c) - lo) * scale).toInt(), ((g(c) - lo) * scale).toInt(), ((b(c) - lo) * scale).toInt())
        }
    }

    /** Light 3×3 smoothing (paper grain, JPEG noise), then moderate contrast. */
    fun denoiseContrast(pixels: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            var sr = 0; var sg = 0; var sb = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until width && yy in 0 until height) {
                    val c = pixels[yy * width + xx]
                    sr += r(c); sg += g(c); sb += b(c); n++
                }
            }
            out[y * width + x] = argb(sr / n, sg / n, sb / n)
        }
        return contrast(out)
    }

    /**
     * Foreground ink only: pale or grey pixels (ruled lines, back-page
     * show-through, paper) become white; pen strokes are kept exactly as
     * they are — not thresholded or thickened.
     */
    fun foregroundInk(pixels: IntArray): IntArray = IntArray(pixels.size) { i ->
        val c = pixels[i]
        val mx = max(r(c), max(g(c), b(c)))
        val mn = min(r(c), min(g(c), b(c)))
        val strongColour = mx - mn > 45 && mn < 170 && !isSkinOrWood(c)
        val darkPen = mx < 110
        if (strongColour || darkPen) c else argb(255, 255, 255)
    }

    /**
     * Lightweight usability check. Deliberately lenient: it flags photos
     * that are genuinely blurry, dark, washed out, tiny or blank — never a
     * photo just because it is handwriting.
     */
    fun quality(pixels: IntArray, width: Int, height: Int, originalMinSide: Int, originalMaxSide: Int): PhotoQuality {
        val gray = IntArray(pixels.size) { (r(pixels[it]) * 299 + g(pixels[it]) * 587 + b(pixels[it]) * 114) / 1000 }
        val brightness = gray.average()
        // Variance of the Laplacian: low means little sharp detail (blur).
        var sum = 0.0; var sumSq = 0.0; var n = 0
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            val lap = (4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - width] - gray[i + width]).toDouble()
            sum += lap; sumSq += lap * lap; n++
        }
        val sharpness = if (n == 0) 0.0 else sumSq / n - (sum / n) * (sum / n)
        val ink = pixels.count { isColouredInk(it) || isDarkInk(it) }.toDouble() / pixels.size
        val problems = buildSet {
            if (sharpness < 25.0) add(PhotoProblem.BLURRY)
            if (brightness < 55.0) add(PhotoProblem.TOO_DARK)
            if (brightness > 248.0) add(PhotoProblem.TOO_BRIGHT)
            if (originalMinSide < 480 || originalMaxSide < 800) add(PhotoProblem.LOW_RESOLUTION)
            if (ink < 0.0005) add(PhotoProblem.NO_WRITING)
        }
        return PhotoQuality(sharpness, brightness, ink, problems)
    }
}
