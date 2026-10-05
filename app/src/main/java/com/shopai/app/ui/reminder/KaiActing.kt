package com.shopai.app.ui.reminder

import com.shopai.app.ui.kai.KaiPose
import com.shopai.app.ui.reminder.KaiUrgentMotion.ARRIVE_END
import com.shopai.app.ui.reminder.KaiUrgentMotion.easeInOut
import com.shopai.app.ui.reminder.KaiUrgentMotion.easeOut
import com.shopai.app.ui.reminder.KaiUrgentMotion.segment
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** What Kai's voice is doing (ms on the screen's clock), so his body can follow it. */
data class SpeechCue(
    val startedAt: Long,
    /** Null while the line is still being spoken. */
    val endedAt: Long? = null,
    /** The line asks the owner to act: the open hand comes out with it. */
    val gesture: Boolean = true,
)

/** One frame of Kai's performance. */
data class KaiTake(
    /** His body: head, face, shoulders, chest, hips, the gesturing hand. */
    val pose: KaiPose,
    /** 0 hands in his pockets … 1 his open hand toward the owner. */
    val gesture: Float,
    val alpha: Float = 1f,
    val scale: Float = 1f,
    /** Below his place, dp (arrival). */
    val riseDp: Float = 0f,
    /** The soft light around him: 0 … 1. */
    val glow: Float = 1f,
)

/**
 * Kai acting the reminder — the body, not the background: he steps in, looks for the owner,
 * lifts his brows and shoulders, brings his open hand out, leans in and nods; then lives —
 * breathing, shifting his weight, small head moves, glances, a gesture now and then — and
 * moves with his voice (a nod and brows on each line, the hand out on the lines that ask).
 *
 * Idle timing is organic: each channel has its own rhythm, randomised within bounds by [seed]
 * (the same ring always acts the same, so a recreated screen continues the same performance).
 * A pure function of the clock — no Android, tested.
 */
class KaiActing(seed: Long, attempt: Int = 1) {

    private val speed = KaiUrgentMotion.speed(attempt)

    private val look = Beats(seed * 31 + 1, IDLE_FROM, 5_000, 8_000)
    private val weight = Beats(seed * 31 + 2, IDLE_FROM + 1_500, 7_000, 12_000)
    private val gestures = Beats(seed * 31 + 3, IDLE_FROM + (6_000 / speed).toLong(), (10_000 / speed).toLong(), (16_000 / speed).toLong())
    private val glances = Beats(seed * 31 + 4, IDLE_FROM + 2_500, 6_000, 10_000)
    private val blinks = Beats(seed * 31 + 5, 700, 2_400, 5_800)

    fun take(t: Long, mouth: Float = 0f, speech: SpeechCue? = null, still: Boolean = false): KaiTake {
        if (still) return KaiTake(pose = KaiPose(mouth = mouth), gesture = 1f)
        val ms = t.toFloat()
        val idle = easeInOut(segment(ms, GESTURE_DOWN_AT, IDLE_FROM))

        // --- arrival: out of the dark, stepping in onto both feet (no bounce, no pop).
        val arrive = easeOut(segment(ms, 0, ARRIVE_END))
        val alpha = easeOut(segment(ms, 0, 450))
        val scale = 0.93f + 0.07f * arrive
        val rise = 22f * (1f - arrive)

        // --- notice: eyes first, then the head turns to the owner; brows and shoulders lift.
        val eyesToOwner = easeInOut(segment(ms, 1_000, 1_260))
        val headToOwner = easeInOut(segment(ms, 1_080, 1_650))
        var lookX = -0.6f * (1f - eyesToOwner)
        var headTurn = -0.55f * (1f - headToOwner)
        var brows = bump(ms, 1_150f, 1_400f, 2_300f)
        var shoulders = 0.7f * bump(ms, 1_100f, 1_350f, 1_950f)
        var lean = 0.5f * easeInOut(segment(ms, 1_300, 2_000)) +
            0.35f * bump(ms, 2_000f, 2_600f, 4_400f) - 0.25f * easeInOut(segment(ms, 3_400, 4_400))
        var weightShift = 0.7f * (1f - arrive)

        // --- gesture: the open hand toward the owner, a small palm movement, a nod.
        var gesture = easeInOut(segment(ms, GESTURE_UP_AT, GESTURE_UP_AT + 550)) * (1f - easeInOut(segment(ms, GESTURE_DOWN_AT, GESTURE_DOWN_AT + 700)))
        var handWave = 5f * window(ms, 2_600f, 4_400f) * sin(2f * PI.toFloat() * (ms - 2_600f) / 1_200f)
        var headNod = 0.035f * bump(ms, 3_200f, 3_400f, 3_750f)
        var headTilt = 1.5f * window(ms, 2_000f, 4_600f)

        // --- idle life.
        val breath = sin(2f * PI.toFloat() * ms / BREATH_MS)
        shoulders += 0.12f * max(0f, breath)
        lean += idle * 0.15f * (speed - 1f)
        // Head: a new small position every 5–8 s, moved into over ~1.1 s.
        val (tilt0, turn0, nod0) = look.glide(t, 1_100) { _, v -> Triple(-2.5f + 5f * v[0], -0.3f + 0.6f * v[1], -0.01f + 0.022f * v[2]) }
        headTilt += idle * tilt0
        headTurn += idle * turn0
        headNod += idle * nod0
        // Weight from one leg to the other every 7–12 s.
        weightShift += idle * weight.glide(t, 1_400) { i, v -> Triple((0.55f + 0.4f * v[0]) * if (i % 2 == 0) 1f else -1f, 0f, 0f) }.first
        // A gesture now and then: hand out, a small palm movement, back.
        gestures.last(t)?.let { (at, v) ->
            val hold = 1_800f + 800f * v[0]
            val s = (t - at).toFloat()
            val g = easeInOut((s / 600f).coerceIn(0f, 1f)) * (1f - easeInOut(((s - 600f - hold) / 700f).coerceIn(0f, 1f)))
            gesture = max(gesture, idle * g)
            handWave += idle * g * 4f * sin(2f * PI.toFloat() * s / 1_200f)
        }
        // A glance away (eyes, the head follows a little), then eye contact again.
        glances.last(t)?.let { (at, v) ->
            val s = (t - at).toFloat()
            val away = 700f + 500f * v[0]
            val g = easeInOut((s / 180f).coerceIn(0f, 1f)) * (1f - easeInOut(((s - away) / 220f).coerceIn(0f, 1f)))
            val side = if (v[1] < 0.5f) -0.55f else 0.55f
            lookX += idle * g * side
            headTurn += idle * g * side * 0.22f
        }

        // --- the voice: each line starts with a small nod and brows; the hand comes out on asks.
        if (speech != null && t >= speech.startedAt) {
            val s = (t - speech.startedAt).toFloat()
            val talking = speech.endedAt?.let { 1f - easeInOut(((t - it) / 800f).coerceIn(0f, 1f)) } ?: 1f
            brows = max(brows, 0.55f * bump(s, 80f, 260f, 700f))
            headNod += 0.03f * bump(s, 150f, 320f, 650f)
            // Eye contact while he speaks.
            lookX *= 1f - talking
            headTurn *= 1f - 0.7f * talking
            headTilt += talking * 1.4f * mouth * sin(2f * PI.toFloat() * ms / 900f)
            headNod += talking * 0.018f * mouth
            if (speech.gesture) {
                val held = speech.endedAt?.let { (it - speech.startedAt + 1_200).toFloat() } ?: Float.MAX_VALUE
                val g = easeInOut(((s - 200f) / 500f).coerceIn(0f, 1f)) * (1f - easeInOut(((s - held) / 700f).coerceIn(0f, 1f)))
                gesture = max(gesture, g)
                handWave += g * talking * 3.5f * sin(2f * PI.toFloat() * ms / 1_150f + 0.6f)
            }
        }

        // --- blinks: 70 ms down, 110 ms up, every 2.4–5.8 s, sometimes twice.
        val blink = blinks.last(t)?.let { (at, v) ->
            val b = (t - at).toFloat()
            val second = if (v[0] < 0.15f) b - 260f else -1f
            max(lid(b), lid(second))
        } ?: 0f

        return KaiTake(
            pose = KaiPose(
                breath = breath,
                blink = blink,
                lookX = lookX.coerceIn(-1f, 1f),
                mouth = mouth,
                headTilt = headTilt,
                headNod = headNod,
                sway = 0.35f * sin(2f * PI.toFloat() * ms / 7_300f) * idle,
                lean = lean.coerceIn(0f, 1f),
                headTurn = headTurn.coerceIn(-1f, 1f),
                brows = brows.coerceIn(0f, 1f),
                shoulders = shoulders.coerceIn(0f, 1f),
                weightShift = weightShift.coerceIn(-1f, 1f),
                handWave = handWave.coerceIn(-8f, 8f),
            ),
            gesture = gesture.coerceIn(0f, 1f),
            alpha = alpha,
            scale = scale,
            riseDp = rise,
            glow = easeOut(segment(ms, 200, 1_600)),
        )
    }

    companion object {
        const val GESTURE_UP_AT = 2_000L
        const val GESTURE_DOWN_AT = 4_600L
        /** The intro's last move ends here; from now on Kai only lives (and follows his voice). */
        const val IDLE_FROM = 5_300L
        const val BREATH_MS = 3_700f

        /** 0 → 1 at [peak] → 0 at [end], smooth. */
        internal fun bump(ms: Float, start: Float, peak: Float, end: Float): Float = when {
            ms <= start || ms >= end -> 0f
            ms < peak -> easeInOut((ms - start) / (peak - start))
            else -> 1f - easeInOut((ms - peak) / (end - peak))
        }

        /** 1 between [from] and [to], easing in and out over 300 ms. */
        private fun window(ms: Float, from: Float, to: Float): Float =
            easeInOut(((ms - from) / 300f).coerceIn(0f, 1f)) * (1f - easeInOut(((ms - to) / 300f).coerceIn(0f, 1f)))

        private fun lid(b: Float): Float = when {
            b < 0f -> 0f
            b < 70f -> b / 70f
            b < 180f -> 1f - (b - 70f) / 110f
            else -> 0f
        }
    }
}

/**
 * Moments that happen every [minGap]–[maxGap] ms from [first] on, each with a few random values —
 * the same [seed] gives the same moments, so the clock alone decides where Kai is.
 */
internal class Beats(seed: Long, private val first: Long, private val minGap: Long, private val maxGap: Long) {
    private val random = Random(seed)
    private val times = ArrayList<Long>()
    private val values = ArrayList<FloatArray>()

    private fun extendTo(t: Long) {
        while (times.isEmpty() || times.last() <= t) {
            times += if (times.isEmpty()) first else times.last() + random.nextLong(minGap, maxGap + 1)
            values += FloatArray(4) { random.nextFloat() }
        }
    }

    /** Index of the latest moment at or before [t], or −1 before the first. */
    private fun lastIndex(t: Long): Int {
        if (t < first) return -1
        extendTo(t)
        var lo = 0
        var hi = times.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (times[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The latest moment at or before [t] (time and values), or null before the first. */
    fun last(t: Long): Pair<Long, FloatArray>? = lastIndex(t).takeIf { it >= 0 }?.let { times[it] to values[it] }

    /** All moment times up to [t] (tests). */
    fun timesUpTo(t: Long): List<Long> { extendTo(t); return times.filter { it <= t } }

    /**
     * A value that moves to each moment's target in [moveMs] and stays there until the next:
     * from the previous target (zero before the first) to [target] of the latest moment.
     */
    fun glide(t: Long, moveMs: Long, target: (Int, FloatArray) -> Triple<Float, Float, Float>): Triple<Float, Float, Float> {
        val index = lastIndex(t)
        if (index < 0) return Triple(0f, 0f, 0f)
        val from = if (index > 0) target(index - 1, values[index - 1]) else Triple(0f, 0f, 0f)
        val to = target(index, values[index])
        val k = easeInOut(((t - times[index]).toFloat() / moveMs).coerceIn(0f, 1f))
        return Triple(from.first + (to.first - from.first) * k, from.second + (to.second - from.second) * k, from.third + (to.third - from.third) * k)
    }
}
