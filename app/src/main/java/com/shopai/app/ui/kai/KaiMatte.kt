package com.shopai.app.ui.kai

/**
 * Cleans Kai's cut-out for a large, dark stage. The full-body art has a hard 0/255 cut-out whose
 * outermost pixels still carry the light studio background (a pale ring around his hair and skin),
 * a few stray specks, and an opaque pale floor reflection under his sandals — invisible when Kai is
 * small on a light card, obvious when he fills a black screen.
 *
 * Done once on load, in memory (the art files are not touched): the floor reflection and specks go,
 * the edge ring takes the colour of the pixels just inside it, and the edge gets a soft 2-px alpha.
 * Pure Kotlin on ARGB ints (no Android), tested.
 */
object KaiMatte {

    /** Rows from here down are below his sandals in the 998-px-tall full-body art (scaled for other heights). */
    private const val FLOOR_FROM = 940f / 998f

    /** Islands smaller than this many pixels are specks, not Kai. */
    private const val MIN_ISLAND = 40

    fun clean(argb: IntArray, w: Int, h: Int): IntArray {
        val px = argb.copyOf()
        val opaque = BooleanArray(w * h) { (px[it] ushr 24) > 127 }

        // 1. The pale floor reflection under his feet.
        val floorFrom = (h * FLOOR_FROM).toInt()
        for (y in floorFrom until h) for (x in 0 until w) {
            val i = y * w + x
            if (opaque[i] && minChannel(px[i]) >= 200) opaque[i] = false
        }

        // 2. Specks: keep only islands of a real size.
        dropSmallIslands(opaque, w, h)

        // 3. Notches and slits cut into his outline (sleeve, dhoti), and holes left inside it: closed,
        //    filled from the cloth beside them. Wider below the neck (clothes) than on the head (hair tips).
        val neck = (h * NECK_FROM).toInt()
        val closed = BooleanArray(w * h)
        val body = erode(dilate(opaque, w, h, BODY_CLOSE), w, h, BODY_CLOSE)
        val head = erode(dilate(opaque, w, h, HEAD_CLOSE), w, h, HEAD_CLOSE)
        for (i in 0 until w * h) closed[i] = if (i / w >= neck) body[i] else head[i]
        fillHoles(closed, w, h)
        val filling = BooleanArray(w * h) { closed[it] && !opaque[it] }
        fillFromBeside(px, opaque, filling, w, h)
        System.arraycopy(closed, 0, opaque, 0, opaque.size)

        // 4. Distance to the outside (1, 2, or 3 = inside), 8-neighbourhood.
        val dist = IntArray(w * h) { if (opaque[it]) 3 else 0 }
        for (pass in 1..2) {
            val snapshot = dist.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (snapshot[i] <= pass) continue
                var touches = false
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    // Outside the picture counts as outside Kai.
                    if (xx !in 0 until w || yy !in 0 until h) { if (pass == 1) touches = true }
                    else if (snapshot[yy * w + xx] == pass - 1) touches = true
                }
                if (touches) dist[i] = pass
            }
        }

        // 5. Edge colour from just inside; a soft edge alpha.
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val d = dist[i]
            if (d == 0) { out[i] = 0; continue }
            var c = px[i]
            if (d < 3) {
                var r = 0; var g = 0; var b = 0; var n = 0
                for (yy in maxOf(0, y - 3)..minOf(h - 1, y + 3)) for (xx in maxOf(0, x - 3)..minOf(w - 1, x + 3)) {
                    val j = yy * w + xx
                    if (dist[j] == 3) { val p = px[j]; r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; n++ }
                }
                if (n > 0) c = (r / n shl 16) or (g / n shl 8) or (b / n)
            }
            val alpha = when (d) { 1 -> 110; 2 -> 215; else -> 255 }
            out[i] = (alpha shl 24) or (c and 0xFFFFFF)
        }
        return out
    }

    /** Gaps up to about twice this wide are closed: on his clothes… */
    private const val BODY_CLOSE = 6
    /** …and on his head (hair tips stay). */
    private const val HEAD_CLOSE = 2
    /** The head ends about here (fraction of the art's height). */
    private const val NECK_FROM = 0.31f
    /** Background pockets enclosed by Kai up to this size are holes in the cut-out, not gaps. */
    private const val MAX_HOLE = 4_000

    /** Transparent pockets not reachable from the picture's border (and small) become Kai. */
    private fun fillHoles(m: BooleanArray, w: Int, h: Int) {
        val outside = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var top = 0
        fun push(i: Int) { if (!m[i] && !outside[i]) { outside[i] = true; stack[top++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (top > 0) {
            val i = stack[--top]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        // Enclosed pockets, one by one: small ones are filled.
        val seen = BooleanArray(w * h)
        val pocket = IntArray(w * h)
        for (start in 0 until w * h) {
            if (m[start] || outside[start] || seen[start]) continue
            var size = 0
            top = 0
            stack[top++] = start
            seen[start] = true
            while (top > 0) {
                val i = stack[--top]
                pocket[size++] = i
                val x = i % w
                val y = i / w
                for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)) {
                    if (j >= 0 && !m[j] && !outside[j] && !seen[j]) { seen[j] = true; stack[top++] = j }
                }
            }
            if (size <= MAX_HOLE) for (k in 0 until size) m[pocket[k]] = true
        }
    }

    /** Gives each [target] pixel the average colour of its filled neighbours, growing in from the edges. */
    private fun fillFromBeside(px: IntArray, opaque: BooleanArray, target: BooleanArray, w: Int, h: Int) {
        val done = opaque.copyOf()
        var left = target.count { it }
        var pass = 0
        while (left > 0 && pass++ < 64) {
            val next = ArrayList<Pair<Int, Int>>()
            for (i in 0 until w * h) {
                if (!target[i] || done[i]) continue
                val x = i % w
                val y = i / w
                var r = 0; var g = 0; var b = 0; var n = 0
                for (yy in maxOf(0, y - 1)..minOf(h - 1, y + 1)) for (xx in maxOf(0, x - 1)..minOf(w - 1, x + 1)) {
                    val j = yy * w + xx
                    if (done[j]) { val p = px[j]; r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; n++ }
                }
                if (n > 0) next += i to ((255 shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n))
            }
            if (next.isEmpty()) break
            for ((i, c) in next) { px[i] = c; done[i] = true }
            left -= next.size
        }
    }

    /** Square max filter (separable). */
    private fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray = filter(m, w, h, r, grow = true)

    /** Square min filter (separable); outside the picture counts as empty. */
    private fun erode(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray = filter(m, w, h, r, grow = false)

    private fun filter(m: BooleanArray, w: Int, h: Int, r: Int, grow: Boolean): BooleanArray {
        val rows = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = !grow
            for (xx in x - r..x + r) {
                val on = xx in 0 until w && m[y * w + xx]
                if (grow && on) { v = true; break }
                if (!grow && !on) { v = false; break }
            }
            rows[y * w + x] = v
        }
        val out = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = !grow
            for (yy in y - r..y + r) {
                val on = yy in 0 until h && rows[yy * w + x]
                if (grow && on) { v = true; break }
                if (!grow && !on) { v = false; break }
            }
            out[y * w + x] = v
        }
        return out
    }

    private fun minChannel(p: Int) = minOf((p shr 16) and 255, (p shr 8) and 255, p and 255)

    private fun dropSmallIslands(opaque: BooleanArray, w: Int, h: Int) {
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val island = IntArray(w * h)
        for (start in 0 until w * h) {
            if (!opaque[start] || seen[start]) continue
            var top = 0
            var size = 0
            stack[top++] = start
            seen[start] = true
            while (top > 0) {
                val i = stack[--top]
                island[size++] = i
                val x = i % w
                val y = i / w
                if (x > 0 && opaque[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[top++] = i - 1 }
                if (x < w - 1 && opaque[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[top++] = i + 1 }
                if (y > 0 && opaque[i - w] && !seen[i - w]) { seen[i - w] = true; stack[top++] = i - w }
                if (y < h - 1 && opaque[i + w] && !seen[i + w]) { seen[i + w] = true; stack[top++] = i + w }
            }
            if (size < MIN_ISLAND) for (k in 0 until size) opaque[island[k]] = false
        }
    }
}
