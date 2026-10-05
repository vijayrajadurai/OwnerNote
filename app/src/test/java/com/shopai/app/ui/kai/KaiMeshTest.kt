package com.shopai.app.ui.kai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import kotlin.math.abs

class KaiMeshTest {

    private val rig = KaiArt.FULL

    @Test
    fun restPoseLeavesTheArtUntouched() {
        val cols = 20
        val rows = 32
        val v = FloatArray((cols + 1) * (rows + 1) * 2)
        KaiMesh.deform(rig, KaiPose(), cols, rows, v)
        var k = 0
        for (r in 0..rows) for (c in 0..cols) {
            assertEquals(rig.width * c / cols, v[k++], 0.001f)
            assertEquals(rig.height * r / rows, v[k++], 0.001f)
        }
    }

    @Test
    fun blinkClosesTheEyesButNotTheMouth() {
        // Closed: a lid on each eye comes down past the eye's centre.
        val lids = KaiMesh.lids(rig, KaiPose(blink = 1f))
        assertEquals(2, lids.size)
        assertTrue(lids.all { it.bottom > it.centerY })
        // Open: no lids.
        assertTrue(KaiMesh.lids(rig, KaiPose()).isEmpty())
        // A winking eye is already shut: it gets no second lid.
        assertEquals(1, KaiMesh.lids(KaiArt.WINKING, KaiPose(blink = 1f)).size)
        // The eye itself also narrows.
        val above = KaiMesh.point(rig, KaiPose(blink = 1f), rig.eyeLeftX, rig.eyeLeftY - rig.eyeRy * 0.5f)
        val below = KaiMesh.point(rig, KaiPose(blink = 1f), rig.eyeLeftX, rig.eyeLeftY + rig.eyeRy * 0.5f)
        assertTrue(abs(below.second - above.second) < rig.eyeRy * 0.5f)
        val mouth = KaiMesh.point(rig, KaiPose(blink = 1f), rig.mouthX, rig.mouthY + 10f)
        assertEquals(rig.mouthY + 10f, mouth.second, 0.01f)
    }

    @Test
    fun talkingOpensTheJawAndNothingBelowTheFace() {
        val lip = KaiMesh.point(rig, KaiPose(mouth = 1f), rig.mouthX, rig.mouthY + 12f)
        assertTrue(lip.second > rig.mouthY + 12f + 3f)
        val feet = KaiMesh.point(rig, KaiPose(mouth = 1f), rig.width / 2, rig.height - 5f)
        assertEquals(rig.height - 5f, feet.second, 0.01f)
    }

    @Test
    fun headTiltMovesTheHeadButKeepsTheFeetPlanted() {
        val top = KaiMesh.point(rig, KaiPose(headTilt = 4f), rig.headX, rig.headY - rig.headRadius * 0.8f)
        assertTrue(abs(top.first - rig.headX) > 3f)
        val sandal = KaiMesh.point(rig, KaiPose(headTilt = 4f, sway = 1f), rig.width / 2, rig.height)
        assertEquals(rig.width / 2, sandal.first, 0.01f)
        assertEquals(rig.height, sandal.second, 0.01f)
    }

    // --- the body acts (Kai Urgent Action Mode): head turn, brows, shoulders, weight, the gesturing arm.

    private fun moved(rig: KaiArtRig, pose: KaiPose, x: Float, y: Float): Float {
        val (px, py) = KaiMesh.point(rig, pose, x, y)
        return kotlin.math.hypot(px - x, py - y)
    }

    @Test
    fun headTurnMovesTheFaceMoreThanTheHeadsEdge() {
        val turn = KaiPose(headTurn = 1f)
        val nose = moved(rig, turn, rig.headX, rig.headY + rig.headRadius * 0.2f)
        val edge = moved(rig, turn, rig.headX - rig.headRadius * 0.95f, rig.headY)
        assertTrue("a turn, not a slide: nose $nose, edge $edge", nose > 8f && edge < nose / 4f)
        assertEquals(0f, moved(rig, turn, rig.width / 2, rig.height - 10f), 0.001f)
    }

    @Test
    fun browsLiftWithoutTheMouth() {
        val up = KaiMesh.point(rig, KaiPose(brows = 1f), rig.eyeLeftX, rig.eyeLeftY - rig.eyeRy * 2.25f)
        assertTrue(up.second < rig.eyeLeftY - rig.eyeRy * 2.25f - 5f)
        assertEquals(0f, moved(rig, KaiPose(brows = 1f), rig.mouthX, rig.mouthY), 0.001f)
    }

    @Test
    fun shouldersLiftTheUpperBodyNotTheFeet() {
        val pose = KaiPose(shoulders = 1f)
        assertTrue(KaiMesh.point(rig, pose, rig.headX, rig.headY).second < rig.headY - 5f)
        assertEquals(0f, moved(rig, pose, rig.width / 2, rig.height - 10f), 0.001f)
    }

    @Test
    fun weightShiftMovesTheHipsOverPlantedFeet() {
        val pose = KaiPose(weightShift = 1f)
        assertTrue("hips move", moved(rig, pose, rig.neckX, rig.torsoBottom) > 8f)
        assertEquals("sandals stay", 0f, moved(rig, pose, 200f, rig.height * 0.95f), 0.001f)
    }

    @Test
    fun armSwingAndPalmMoveOnlyTheGesturingArm() {
        val point = KaiArt.POINT
        val arm = point.arm!!
        val swing = KaiPose(armSwing = 20f)
        assertTrue("the hand moves", moved(point, swing, arm.handX, arm.handY) > 20f)
        assertEquals("the face stays", 0f, moved(point, swing, point.mouthX, point.mouthY), 0.001f)
        assertEquals("his other arm (watch) stays", 0f, moved(point, swing, 500f, 570f), 0.001f)
        val wave = KaiPose(handWave = 6f)
        assertTrue("the palm moves", moved(point, wave, arm.handX + 30f, arm.handY - 30f) > 2f)
        assertEquals("the elbow stays", 0f, moved(point, wave, arm.elbowX, arm.elbowY), 0.001f)
        // Art without a gesturing arm ignores it.
        assertEquals(0f, moved(KaiArt.JOYFUL, swing, 60f, 200f), 0.001f)
    }

    @Test
    fun handOverHappensOnlyAroundTheArm() {
        assertEquals("his arm", 1f, KaiArt.gestureMask(120f, 450f), 0f)
        assertEquals("his face", 0f, KaiArt.gestureMask(320f, 200f), 0f)
        assertEquals("his other arm", 0f, KaiArt.gestureMask(500f, 560f), 0f)
        assertEquals("his feet", 0f, KaiArt.gestureMask(200f, 940f), 0f)
        assertEquals(0f, KaiArt.gestureBlend(0f), 0f)
        assertEquals(1f, KaiArt.gestureBlend(1f), 0f)
        assertEquals("brief handover: nothing shows early", 0f, KaiArt.gestureBlend(0.3f), 0f)
        var last = 0f
        for (i in 0..100) { val b = KaiArt.gestureBlend(i / 100f); assertTrue(b >= last); last = b }
    }

    /**
     * Visual check (not an assertion): renders poses of the real art to
     * build/kai-preview when the raw art exists in %TEMP%\kai_raw.
     */
    @Test
    fun renderPreviewFrames() {
        val dir = File(System.getenv("TEMP") ?: return, "kai_raw")
        val outDir = File("build/kai-preview").apply { mkdirs() }
        for ((name, art) in listOf("full" to KaiArt.FULL, "point" to KaiArt.POINT, "joyful" to KaiArt.JOYFUL, "proud" to KaiArt.PROUD)) {
            val file = File(dir, "$name.rgb")
            if (!file.exists()) continue
            val (w, h, src) = DataInputStream(file.inputStream().buffered()).use { s ->
                val w = s.readInt(); val h = s.readInt()
                Triple(w, h, ByteArray(w * h * 3).also { s.readFully(it) })
            }
            for ((label, pose) in listOf(
                "rest" to KaiPose(),
                "blink" to KaiPose(blink = 1f),
                "talk" to KaiPose(mouth = 1f),
                "tilt" to KaiPose(headTilt = 4f, headNod = 0.04f, lookX = 1f),
                "breath" to KaiPose(breath = 1f, sway = 1.2f, lean = 1f),
            )) {
                File(outDir, "$name-$label.ppm").writeBytes(render(art, pose, w, h, src))
            }
        }
    }

    // Draws the deformed mesh (two triangles per cell), sampling the source by barycentric coordinates.
    private fun render(rig: KaiArtRig, pose: KaiPose, w: Int, h: Int, src: ByteArray): ByteArray {
        val cols = 60; val rows = 96
        val v = FloatArray((cols + 1) * (rows + 1) * 2)
        KaiMesh.deform(rig, pose, cols, rows, v)
        val out = ByteArray(w * h * 3) { 255.toByte() }
        fun sx(c: Int) = rig.width * c / cols
        fun sy(r: Int) = rig.height * r / rows
        fun tri(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, su: FloatArray) {
            val minX = maxOf(0, minOf(ax, bx, cx).toInt()); val maxX = minOf(w - 1, maxOf(ax, bx, cx).toInt() + 1)
            val minY = maxOf(0, minOf(ay, by, cy).toInt()); val maxY = minOf(h - 1, maxOf(ay, by, cy).toInt() + 1)
            val den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (abs(den) < 1e-6f) return
            for (py in minY..maxY) for (px in minX..maxX) {
                val l1 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / den
                val l2 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / den
                val l3 = 1 - l1 - l2
                if (l1 < -0.001f || l2 < -0.001f || l3 < -0.001f) continue
                val ux = (l1 * su[0] + l2 * su[2] + l3 * su[4]).toInt().coerceIn(0, w - 1)
                val uy = (l1 * su[1] + l2 * su[3] + l3 * su[5]).toInt().coerceIn(0, h - 1)
                val s = (uy * w + ux) * 3; val d = (py * w + px) * 3
                out[d] = src[s]; out[d + 1] = src[s + 1]; out[d + 2] = src[s + 2]
            }
        }
        for (r in 0 until rows) for (c in 0 until cols) {
            val i00 = (r * (cols + 1) + c) * 2; val i10 = i00 + 2
            val i01 = ((r + 1) * (cols + 1) + c) * 2; val i11 = i01 + 2
            tri(v[i00], v[i00 + 1], v[i10], v[i10 + 1], v[i11], v[i11 + 1], floatArrayOf(sx(c), sy(r), sx(c + 1), sy(r), sx(c + 1), sy(r + 1)))
            tri(v[i00], v[i00 + 1], v[i11], v[i11 + 1], v[i01], v[i01 + 1], floatArrayOf(sx(c), sy(r), sx(c + 1), sy(r + 1), sx(c), sy(r + 1)))
        }
        // Eyelids, as the app draws them: skin colour from above the eye, a lash line at the lid's edge.
        val skins = KaiMesh.skinSamplePoints(rig).map { (sx0, sy0) -> ((sy0.toInt() * w + sx0.toInt()) * 3).let { Triple(src[it], src[it + 1], src[it + 2]) } }
        for (lid in KaiMesh.lids(rig, pose)) {
            val (cr, cg, cb) = skins[if (lid.left) 0 else 1]
            for (py in (lid.centerY - lid.ry).toInt()..(lid.centerY + lid.ry).toInt()) for (px in (lid.centerX - lid.rx).toInt()..(lid.centerX + lid.rx).toInt()) {
                if (px !in 0 until w || py !in 0 until h) continue
                val u = ((px - lid.centerX) / lid.rx).let { it * it } + ((py - lid.centerY) / lid.ry).let { it * it }
                if (u > 1f) continue
                val d = (py * w + px) * 3
                val edgeY = lid.bottom + lid.ry * 0.3f * (1f - ((px - lid.centerX) / lid.rx).let { it * it })
                // Feathered rim: full skin inside, fading out over the outer 30 % of the oval.
                val a = ((1f - u) / 0.3f).coerceIn(0f, 1f)
                fun mix(o: Byte, n: Byte, t: Float) = ((o.toInt() and 0xFF) * (1 - t) + (n.toInt() and 0xFF) * t).toInt().toByte()
                val lashAlpha = a * (1f - (((px - lid.centerX) / (lid.rx * 0.85f)).let { it * it })).coerceIn(0f, 1f)
                when {
                    py <= edgeY - lid.lashWidth -> { out[d] = mix(out[d], cr, a); out[d + 1] = mix(out[d + 1], cg, a); out[d + 2] = mix(out[d + 2], cb, a) }
                    py <= edgeY -> { out[d] = mix(out[d], 0x3a, lashAlpha); out[d + 1] = mix(out[d + 1], 0x28, lashAlpha); out[d + 2] = mix(out[d + 2], 0x22, lashAlpha) }
                }
            }
        }
        return "P6\n$w $h\n255\n".toByteArray() + out
    }
}
