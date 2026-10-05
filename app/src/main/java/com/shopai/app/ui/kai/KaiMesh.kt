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
    /** The arm that gestures (only art drawn with one free arm has it). */
    val arm: KaiArmRig? = null,
)

/**
 * Kai's gesturing arm in one piece of art (source pixels): the forearm and
 * hand turn around the elbow, the hand alone around the wrist.
 */
data class KaiArmRig(
    val elbowX: Float, val elbowY: Float,
    val wristX: Float, val wristY: Float,
    val handX: Float, val handY: Float,
    /** Half-thickness of the forearm-and-hand band that moves (it fades out over half as much again). */
    val reach: Float,
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
    /** Head turn, −1 (his right, the viewer's left) … 1: the face moves across the head. */
    val headTurn: Float = 0f,
    /** Eyebrows raised: 0…1 ("Owner…!"). */
    val brows: Float = 0f,
    /** Shoulders (and the head with them) lifted: 0…1. */
    val shoulders: Float = 0f,
    /** Weight on one leg: −1 … 1, the hips move sideways over planted feet. */
    val weightShift: Float = 0f,
    /** Forearm and hand turned around the elbow, degrees (+ = down). Art with an [KaiArtRig.arm] only. */
    val armSwing: Float = 0f,
    /** The open hand turned around the wrist, degrees (a small palm movement while he talks). */
    val handWave: Float = 0f,
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

        // Eyebrows: lifted toward the hairline.
        if (pose.brows != 0f) for (left in listOf(true, false)) {
            val bx = if (left) rig.eyeLeftX else rig.eyeRightX
            val by = (if (left) rig.eyeLeftY else rig.eyeRightY) - rig.eyeRy * 2.25f
            val u = sq((x0 - bx) / (rig.eyeRx * 2f)) + sq((y0 - by) / (rig.eyeRy * 1.1f))
            if (u < 1f) y -= pose.brows * rig.eyeRy * 0.45f * sq(1f - u)
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
        // Head turn: the face slides across the head more than its outline does (a turn, not a slide).
        if (pose.headTurn != 0f && d < rig.headRadius) {
            val f = 1f - sq(d / rig.headRadius)
            x += pose.headTurn * rig.headRadius * 0.10f * f * (if (rig.handOnFace) 0.35f else 1f)
        }
        if (hw > 0f) {
            val strength = if (rig.handOnFace) 0.35f else 1f
            val a = (pose.headTilt * strength * hw) * PI.toFloat() / 180f
            val rx = x - rig.neckX
            val ry = y - rig.neckY
            x = rig.neckX + rx * cos(a) - ry * sin(a)
            y = rig.neckY + rx * sin(a) + ry * cos(a) + pose.headNod * rig.headRadius * strength * hw
        }

        // The gesturing arm: the hand around the wrist, then forearm and hand around the elbow.
        rig.arm?.let { arm ->
            if (pose.handWave != 0f) {
                val dh = hypot(x0 - arm.handX, y0 - arm.handY)
                val w = band(dh, arm.reach * 0.95f)
                if (w > 0f) {
                    val (rx, ry) = rotate(x, y, arm.wristX, arm.wristY, pose.handWave * w)
                    x = rx; y = ry
                }
            }
            if (pose.armSwing != 0f) {
                val w = band(segmentDistance(x0, y0, arm.elbowX, arm.elbowY, arm.handX, arm.handY), arm.reach) *
                    // Nothing on the far side of the elbow moves (the upper arm and the body stay).
                    smooth(((x0 - arm.elbowX) * (arm.handX - arm.elbowX) + (y0 - arm.elbowY) * (arm.handY - arm.elbowY)) /
                        (hypot(arm.handX - arm.elbowX, arm.handY - arm.elbowY) * arm.reach * 0.6f))
                if (w > 0f) {
                    val (rx, ry) = rotate(x, y, arm.elbowX, arm.elbowY, pose.armSwing * w)
                    x = rx; y = ry
                }
            }
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

        // Shoulders up (attention): the head rides along, the lift fades out down the chest.
        if (pose.shoulders != 0f) {
            val span = (rig.torsoBottom - rig.neckY) * 0.6f
            val k = if (y0 <= rig.neckY) 1f else smooth(1f - (y0 - rig.neckY) / span)
            y -= pose.shoulders * rig.height * 0.008f * k
        }

        // Weight on one leg: the hips (and everything above) move over the feet, the chest tilts back a touch.
        if (pose.weightShift != 0f) {
            val hip = rig.torsoBottom
            val feet = rig.height * 0.94f
            val k = if (y0 <= hip) 1f else smooth((feet - y0) / (feet - hip))
            if (y0 < hip) {
                val (rx, ry) = rotate(x, y, rig.neckX, hip, -0.9f * pose.weightShift * (hip - y0) / hip)
                x = rx; y = ry
            }
            x += pose.weightShift * rig.width * 0.018f * k
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

    /** 1 inside [r], fading to 0 at 1.5·[r]. */
    private fun band(d: Float, r: Float): Float = when {
        d <= r -> 1f
        d >= r * 1.5f -> 0f
        else -> smooth(1f - (d - r) / (r * 0.5f))
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax
        val vy = by - ay
        val t = (((px - ax) * vx + (py - ay) * vy) / (vx * vx + vy * vy)).coerceIn(0f, 1f)
        return hypot(px - (ax + vx * t), py - (ay + vy * t))
    }

    private fun rotate(x: Float, y: Float, cx: Float, cy: Float, degrees: Float): Pair<Float, Float> {
        val a = degrees * PI.toFloat() / 180f
        val rx = x - cx
        val ry = y - cy
        return (cx + rx * cos(a) - ry * sin(a)) to (cy + rx * sin(a) + ry * cos(a))
    }

    /** Smoothstep of [v] clamped to 0…1. */
    private fun smooth(v: Float): Float {
        val t = v.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun sq(v: Float) = v * v
    private fun pow15(v: Float) = v * kotlin.math.sqrt(v)
}

/** Kai's artwork and where his face is in each (measured on the art). */
object KaiArt {
    // Standing, hands in pockets, open smile (620×998). The arm: his right hand in the pocket.
    val FULL = KaiArtRig(
        620f, 998f, 276f, 178f, 365f, 182f, 25f, 22f, 320f, 234f, 46f, true,
        320f, 185f, 150f, 320f, 330f, 330f, 640f,
        arm = KaiArmRig(68f, 465f, 150f, 585f, 180f, 605f, 42f),
    )
    // Open hand toward the owner — explaining / greeting (620×998). Same framing as FULL: only the arm differs.
    val POINT = KaiArtRig(
        620f, 998f, 278f, 180f, 366f, 182f, 24f, 21f, 320f, 236f, 46f, true,
        312f, 190f, 145f, 315f, 330f, 330f, 640f,
        arm = KaiArmRig(88f, 420f, 148f, 425f, 185f, 368f, 62f),
    )

    /**
     * Where FULL and POINT differ (his right arm, with its sleeve and the shirt behind it): 1 inside,
     * feathered to 0 over plain shirt cloth. Kai's hand coming out is FULL → POINT crossfaded only here,
     * so his head, face and everything else stay one picture. Source pixels of the 620×998 art.
     */
    fun gestureMask(x: Float, y: Float): Float {
        val fx = 1f - ((x - 315f) / 30f).coerceIn(0f, 1f)
        val fy = when {
            y < 300f -> ((y - 270f) / 30f).coerceIn(0f, 1f)
            // Down past his pocket (where FULL's hand goes in), fading out on the plain dhoti.
            else -> 1f - ((y - 700f) / 40f).coerceIn(0f, 1f)
        }
        return fx * fy
    }

    /** While the hand comes out: FULL's arm starts lifting (degrees) before it hands over… */
    fun fullArmSwing(gesture: Float) = -30f * gesture
    /** …and POINT's arm rises into place from lower down. */
    fun pointArmSwing(gesture: Float) = 30f * (1f - gesture)
    /** How much of POINT's arm shows: the handover is brief (mid-move), so the two arms never linger as a double image. */
    fun gestureBlend(gesture: Float): Float {
        val t = ((gesture - 0.32f) / 0.36f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    val JOYFUL = KaiArtRig(265f, 422f, 128f, 85f, 164f, 86f, 10f, 10f, 148f, 108f, 20f, true, 145f, 80f, 62f, 145f, 135f, 135f, 265f)
    val SURPRISED = KaiArtRig(265f, 422f, 127f, 93f, 164f, 93f, 10f, 10f, 146f, 127f, 9f, true, 143f, 88f, 62f, 143f, 140f, 140f, 265f)
    val THOUGHTFUL = KaiArtRig(264f, 422f, 121f, 88f, 160f, 86f, 10f, 10f, 148f, 116f, 9f, false, 140f, 82f, 60f, 140f, 135f, 135f, 265f, handOnFace = true)
    val CURIOUS = KaiArtRig(265f, 416f, 128f, 85f, 165f, 86f, 10f, 10f, 147f, 112f, 12f, false, 145f, 80f, 62f, 145f, 135f, 135f, 262f)
    val WINKING = KaiArtRig(264f, 416f, 126f, 87f, 164f, 88f, 10f, 10f, 147f, 108f, 20f, false, 145f, 80f, 62f, 145f, 135f, 135f, 262f, leftEyeClosed = true)
    val PROUD = KaiArtRig(264f, 389f, 128f, 77f, 163f, 77f, 10f, 7f, 145f, 92f, 18f, false, 143f, 72f, 60f, 143f, 125f, 125f, 245f)
    val NERVOUS = KaiArtRig(265f, 389f, 127f, 83f, 162f, 83f, 10f, 9f, 146f, 110f, 16f, false, 143f, 78f, 60f, 143f, 130f, 130f, 245f)
}
