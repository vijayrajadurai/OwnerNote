package com.shopai.app.ui.kai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kai's cut-out cleaned for the large dark stage: no pale edge ring, no specks, no floor patch, no notches. */
class KaiMatteTest {

    private val w = 100
    private val h = 200
    private val red = 0xFFC03020.toInt()
    private val pale = 0xFFF0F0EC.toInt()

    private fun alpha(p: Int) = p ushr 24
    private fun rgb(p: Int) = p and 0xFFFFFF

    /** A red "body" (x 30–70, y 20–180) with a 1-px pale ring, the way the studio cut-out arrives. */
    private fun body(): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        when {
            x in 30..70 && y in 20..180 -> if (x == 30 || x == 70 || y == 20 || y == 180) pale else red
            else -> 0
        }
    }

    @Test
    fun paleRingTakesTheColourFromInsideAndTheEdgeIsSoft() {
        val out = KaiMatte.clean(body(), w, h)
        val edge = out[100 * w + 30]
        assertEquals("edge colour from inside", rgb(red), rgb(edge))
        assertTrue("soft edge: ${alpha(edge)}", alpha(edge) in 120..230)
        val outside = out[100 * w + 29]
        assertTrue("the soft edge fades out just outside: ${alpha(outside)}", alpha(outside) in 20..150)
        assertEquals("…in Kai's colour, never the pale ring", rgb(red), rgb(outside))
        assertTrue(alpha(out[100 * w + 32]) >= 250)
        assertEquals(red, out[100 * w + 50])
        assertEquals(0, out[100 * w + 10])
    }

    @Test
    fun specksFloorNotchesAndHolesAreCleaned() {
        val px = body()
        // A speck far away.
        for (y in 5..7) for (x in 5..7) px[y * w + x] = pale
        // A pale floor patch under the feet (rows below 94 % of the height).
        for (y in 190..198) for (x in 20..80) px[y * w + x] = pale
        // A slit cut into the side (below the neck) and a hole inside.
        for (y in 100..103) for (x in 30..36) px[y * w + x] = 0
        for (y in 120..125) for (x in 45..50) px[y * w + x] = 0
        val out = KaiMatte.clean(px, w, h)
        assertEquals("speck gone", 0, alpha(out[6 * w + 6]))
        assertEquals("floor gone", 0, alpha(out[195 * w + 50]))
        assertEquals("slit closed", rgb(red), rgb(out[101 * w + 34]))
        assertEquals(255, alpha(out[101 * w + 34]))
        assertEquals("hole filled", red, out[122 * w + 47])
    }

    // A staircase edge (the cut-out's pixel steps) becomes a smooth ramp.
    @Test
    fun pixelStairsAreSmoothed() {
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            // Left edge steps 3 px right every 6 rows.
            if (y in 40..170 && x in (30 + ((y - 40) / 6) * 3 % 18)..70) red else 0
        }
        val out = KaiMatte.clean(px, w, h)
        // Along a row crossing a step, alpha rises gradually, not 0 → 255 in one pixel.
        val row = 100
        val ramp = (25..60).map { alpha(out[row * w + it]) }
        val partial = ramp.count { it in 1..254 }
        assertTrue("a soft ramp across the step: $ramp", partial >= 3)
    }
}
