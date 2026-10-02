package com.shopai.app.ui.kai

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Where Kai's face and body are in one piece of his artwork (source
 * pixels), so the mesh knows which part of the picture is an eye, the
 * mouth, the head, the neck and the chest.
 */
data class KaiArtRig(
    val width: Float,
    val height: Float,
    val eyeLeftX: Float, val eyeLeftY: Float,
    val eyeRightX: Float, val eyeRightY: Float,
    /** Half-width / half-height of one eye. */
    val eyeRx: Float, val eyeRy: Float,
    val mouthX: Float, val mouthY: Float,
    val mouthHalfWidth: Float,
    /** The mouth is drawn open (teeth/tongue visible): talking stretches it more. */
    val mouthOpen: Boolean,
    val headX: Float, val headY: Float, val headRadius: Float,
    /** Head turns around here. */
    val neckX: Float, val neckY: Float,
    /** Chest band that breathes. */
    val torsoTop: Float, val torsoBottom: Float,
    /** The left eye is already closed (a wink): it doesn't blink again. */
    val leftEyeClosed: Boolean = false,
    /** A hand touches the face (thinking pose): the head moves less so the hand stays on it. */
    val handOnFace: Boolean = false,
)

/** One moment of Kai's live motion. All values are small, natural amounts. */
data class KaiPose(
    /** −1…1 breathing cycle. */
    val breath: Float = 0f,
    /** 0 open … 1 closed. */
    val blink: Float = 0f,
    /** −1 left … 1 right glance. */
    val lookX: Float = 0f,
    /** 0 closed … 1 wide: voice loudness. */
    val mouth: Float = 0f,
    /** Head tilt, degrees. */
    val headTilt: Float = 0f,
    /** Head nod (down +), as a fraction of the head radius. */
    val headNod: Float = 0f,
    /** Whole-body sway, degrees (pivot at the feet). */
    val sway: Float = 0f,
    /** Lean toward the owner (listening): 0…1. */
    val lean: Float = 0f,
)

/**
 * Kai's live 2D rig: the artwork is laid on a fine grid of points and the
 * points are moved every frame — eyelids close for a blink, the jaw opens
 * with the voice, the head tilts and nods on the neck, the chest breathes
 * and the body sways on its feet. Pure Kotlin (no Android): testable.
 *
 * [vertices] receives (cols+1)·(rows+1) x/y pairs in source pixels, in the
 * layout Android's Canvas.drawBitmapMesh expects.
 */
object KaiMesh {

    fun deform(rig: KaiArtRig, pose: KaiPose, cols: Int, rows: Int, vertices: FloatArray) {
        var k = 0
        for (r in 0..rows) {
            val y0 = rig.height * r / rows
            for (c in 0..cols) {
                val x0 = rig.width * c / cols
                val (x, y) = point(rig, pose, x0, y0)
                vertices[k++] = x
                vertices[k++] = y
            }
        }
    }

    /** Where source point (x0, y0) is drawn in this pose. */
    fun point(rig: KaiArtRig, pose: KaiPose, x0: Float, y0: Float): Pair<Float, Float> {
        var x = x0
        var y = y0

        // Eyes: lids close toward the eye's middle; a glance shifts the eye a little.
        for (left in listOf(true, false)) {
            val ex = if (left) rig.eyeLeftX else rig.eyeRightX
            val ey = if (left) rig.eyeLeftY else rig.eyeRightY
            val dx = x0 - ex
            val dy = y0 - ey
            val u = sq(dx / (rig.eyeRx * 1.5f)) + sq(dy / (rig.eyeRy * 1.9f))
            if (u < 1f) {
                val f = sq(1f - u)
                val blink = if (left && rig.leftEyeClosed) 0f else pose.blink
                // Close downward a touch (upper lid moves more).
                y = ey + rig.eyeRy * 0.15f * blink * f + dy * (1f - 0.92f * blink * f)
                x += pose.lookX * rig.eyeRx * 0.22f * f
            }
        }

        // Mouth: the jaw drops with the voice (the lower lip moves, the upper barely).
        val ax = max(rig.mouthHalfWidth * 1.45f, rig.eyeRx * 1.4f)
        val ay = max(rig.mouthHalfWidth * 1.15f, rig.eyeRy * 1.6f)
        val mdx = x0 - rig.mouthX
        val mdy = y0 - rig.mouthY
        val mu = sq(mdx / ax) + sq(mdy / ay)
        if (mu < 1f && pose.mouth > 0f) {
            val f = pow15(1f - mu)
            val amp = max(rig.mouthHalfWidth, rig.eyeRx) * (if (rig.mouthOpen) 0.34f else 0.16f) * pose.mouth
            y += if (mdy > 0f) amp * f else -amp * 0.25f * f
        }

        // Head: tilt and nod around the neck, fading out over the neck.
        val d = hypot(x0 - rig.headX, y0 - rig.headY)
        var hw = when {
            d <= rig.headRadius -> 1f
            d <= rig.headRadius * 1.35f -> 1f - (d - rig.headRadius) / (rig.headRadius * 0.35f)
            else -> 0f
        }
        if (y0 > rig.neckY) hw *= max(0f, 1f - (y0 - rig.neckY) / (rig.headRadius * 0.25f))
        if (hw > 0f) {
            val strength = if (rig.handOnFace) 0.35f else 1f
            val a = (pose.headTilt * strength * hw) * PI.toFloat() / 180f
            val rx = x - rig.neckX
            val ry = y - rig.neckY
            x = rig.neckX + rx * cos(a) - ry * sin(a)
            y = rig.neckY + rx * sin(a) + ry * cos(a) + pose.headNod * rig.headRadius * strength * hw
        }

        // Chest: breathes out and up a little.
        if (y0 > rig.torsoTop && y0 < rig.torsoBottom) {
            val t = (y0 - rig.torsoTop) / (rig.torsoBottom - rig.torsoTop)
            val bell = sin(PI.toFloat() * t)
            x = rig.neckX + (x - rig.neckX) * (1f + 0.012f * pose.breath * bell)
            y -= rig.height * 0.0025f * pose.breath * bell
        }
        // Whole head + chest rise slightly on the in-breath.
        if (y0 < rig.torsoTop) y -= rig.height * 0.0025f * pose.breath

        // Lean toward the owner: the upper body scales up slightly from the waist.
        if (pose.lean != 0f && y0 < rig.torsoBottom) {
            val w = (rig.torsoBottom - y0) / rig.torsoBottom
            val s = 1f + 0.03f * pose.lean * w
            x = rig.neckX + (x - rig.neckX) * s
            y = rig.torsoBottom + (y - rig.torsoBottom) * s
        }

        // Body sway around the feet.
        if (pose.sway != 0f) {
            val a = pose.sway * PI.toFloat() / 180f * (1f - y0 / rig.height)
            val px = rig.width / 2f
            val py = rig.height
            val rx = x - px
            val ry = y - py
            x = px + rx * cos(a) - ry * sin(a)
            y = py + rx * sin(a) + ry * cos(a)
        }
        return x to y
    }

    /**
     * An eyelid for a blink: the lid (skin colour from just above the eye)
     * comes down over the eye to [bottom], clipped to the eye's oval, with a
     * lash line along its lower edge. Null when this eye isn't blinking.
     */
    data class Lid(val left: Boolean, val centerX: Float, val centerY: Float, val rx: Float, val ry: Float, val bottom: Float, val lashWidth: Float)

    fun lids(rig: KaiArtRig, pose: KaiPose): List<Lid> {
        if (pose.blink <= 0.05f) return emptyList()
        val still = pose.copy(blink = 0f, mouth = 0f, lookX = 0f)
        return listOf(true, false).mapNotNull { left ->
            if (left && rig.leftEyeClosed) return@mapNotNull null
            val (cx, cy) = point(rig, still, if (left) rig.eyeLeftX else rig.eyeRightX, if (left) rig.eyeLeftY else rig.eyeRightY)
            val rx = rig.eyeRx * 1.28f
            val ry = rig.eyeRy * 1.32f
            Lid(left, cx, cy, rx, ry, bottom = cy - ry + ry * 1.3f * pose.blink, lashWidth = maxOf(1.2f, rig.eyeRy * 0.16f))
        }
    }

    /** Where to take the lid's skin colour from in the art: just above each eye, below the brow. */
    fun skinSamplePoints(rig: KaiArtRig): List<Pair<Float, Float>> = listOf(
        rig.eyeLeftX to rig.eyeLeftY - rig.eyeRy * 1.35f,
        rig.eyeRightX to rig.eyeRightY - rig.eyeRy * 1.35f,
    )

    private fun sq(v: Float) = v * v
    private fun pow15(v: Float) = v * kotlin.math.sqrt(v)
}

/** Kai's artwork and where his face is in each (measured on the art). */
object KaiArt {
    // Standing, hands in pockets, open smile (620×998).
    val FULL = KaiArtRig(
        620f, 998f, 276f, 178f, 365f, 182f, 25f, 22f, 320f, 234f, 46f, true,
        320f, 185f, 150f, 320f, 330f, 330f, 640f,
    )
    // Open hand toward the owner — explaining / greeting (620×998).
    val POINT = KaiArtRig(
        620f, 998f, 278f, 180f, 366f, 182f, 24f, 21f, 320f, 236f, 46f, true,
        312f, 190f, 145f, 315f, 330f, 330f, 640f,
    )
    val JOYFUL = KaiArtRig(265f, 422f, 128f, 85f, 164f, 86f, 10f, 10f, 148f, 108f, 20f, true, 145f, 80f, 62f, 145f, 135f, 135f, 265f)
    val SURPRISED = KaiArtRig(265f, 422f, 127f, 93f, 164f, 93f, 10f, 10f, 146f, 127f, 9f, true, 143f, 88f, 62f, 143f, 140f, 140f, 265f)
    val THOUGHTFUL = KaiArtRig(264f, 422f, 121f, 88f, 160f, 86f, 10f, 10f, 148f, 116f, 9f, false, 140f, 82f, 60f, 140f, 135f, 135f, 265f, handOnFace = true)
    val CURIOUS = KaiArtRig(265f, 416f, 128f, 85f, 165f, 86f, 10f, 10f, 147f, 112f, 12f, false, 145f, 80f, 62f, 145f, 135f, 135f, 262f)
    val WINKING = KaiArtRig(264f, 416f, 126f, 87f, 164f, 88f, 10f, 10f, 147f, 108f, 20f, false, 145f, 80f, 62f, 145f, 135f, 135f, 262f, leftEyeClosed = true)
    val PROUD = KaiArtRig(264f, 389f, 128f, 77f, 163f, 77f, 10f, 7f, 145f, 92f, 18f, false, 143f, 72f, 60f, 143f, 125f, 125f, 245f)
    val NERVOUS = KaiArtRig(265f, 389f, 127f, 83f, 162f, 83f, 10f, 9f, 146f, 110f, 16f, false, 143f, 78f, 60f, 143f, 130f, 130f, 245f)
}
