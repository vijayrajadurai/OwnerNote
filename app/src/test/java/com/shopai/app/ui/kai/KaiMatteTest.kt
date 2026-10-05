package com.shopai.app.ui.kai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

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
        assertTrue("soft edge", alpha(edge) in 60..160)
        assertEquals(215, alpha(out[100 * w + 31]))
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

    /** The real art: nothing pale under the sandals, one Kai, soft edges, the inside untouched. */
    @Test
    fun realArtCleansUp() {
        for (name in listOf("kai_full", "kai_point")) {
            val file = File("src/main/res/drawable-nodpi/$name.png").takeIf { it.exists() } ?: return
            val img = ImageIO.read(file)
            val aw = img.width
            val ah = img.height
            val raw = IntArray(aw * ah) { img.getRGB(it % aw, it / aw) }
            val out = KaiMatte.clean(raw, aw, ah)
            var paleFloor = 0
            var soft = 0
            for (y in (ah * 0.95f).toInt() until ah) for (x in 0 until aw) {
                val p = out[y * aw + x]
                if (alpha(p) > 0 && minOf((p shr 16) and 255, (p shr 8) and 255, p and 255) >= 200) paleFloor++
            }
            for (p in out) if (alpha(p) in 1..254) soft++
            assertEquals("$name: pale floor left", 0, paleFloor)
            assertTrue("$name: soft edges", soft > 1_000)
            // His face is untouched (an eye pixel keeps its colour).
            val eye = (KaiArt.FULL.eyeLeftY.toInt() * aw + KaiArt.FULL.eyeLeftX.toInt())
            assertEquals(rgb(raw[eye]), rgb(out[eye]))
            assertEquals(255, alpha(out[eye]))
        }
    }
}
