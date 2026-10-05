package com.shopai.app.ui.reminder

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shopai.app.ui.kai.KaiCharacter
import com.shopai.app.ui.kai.KaiState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Owner Note green — the only accent in Urgent Action Mode. */
internal val UrgentAccent = Color(0xFF2FBE7C)

/**
 * The intro clock for one ring: milliseconds since Kai arrived, 0 → [KaiUrgentMotion.INTRO_MS].
 * Played once per [ringKey]; a recreated screen (rotation, resume, back from the dialer) starts at the end.
 */
@Composable
internal fun rememberIntroClock(ringKey: String, still: Boolean): State<Float> {
    var played by rememberSaveable(ringKey) { mutableStateOf(still) }
    val clock = remember(ringKey) { Animatable(if (played) KaiUrgentMotion.INTRO_MS.toFloat() else 0f) }
    LaunchedEffect(ringKey) {
        if (!played) {
            clock.animateTo(KaiUrgentMotion.INTRO_MS.toFloat(), tween(KaiUrgentMotion.INTRO_MS.toInt(), easing = LinearEasing))
            played = true
        }
    }
    return clock.asState()
}

private class Particle(val radius: Float, val angle: Float, val speed: Float, val size: Float, val twinkle: Float, val phase: Float)

/**
 * Kai, arriving: the existing animated Kai (his REMINDER face, blinks, lip-sync) inside a layered
 * cinematic stage — a soft energy field, a slow orbit, a few drifting light particles, one pulse wave
 * when he turns to the owner, then a calm idle (breathing, sway, an occasional nod). Restrained,
 * on black, Owner Note green only. Reduced motion → Kai still, no particles.
 */
@Composable
internal fun KaiUrgentStage(
    intro: Float,
    attempt: Int,
    speaking: Boolean,
    mouthLevel: Float,
    /** 0 → 1 while the screen closes after Done / Snooze. */
    exit: Float,
    /** Snooze: Kai nods as he leaves; Done: he relaxes down. */
    acknowledging: Boolean,
    still: Boolean,
    size: Dp = 280.dp,
    modifier: Modifier = Modifier,
) {
    val speed = KaiUrgentMotion.speed(attempt)
    val depth = KaiUrgentMotion.breathDepth(attempt)

    // Idle life (runs only while the screen is shown).
    val idle = rememberInfiniteTransition(label = "kai-idle")
    val breath by idle.animateFloat(0f, 1f, infiniteRepeatable(tween(KaiUrgentMotion.BREATH_MS / 2), RepeatMode.Reverse), label = "breath")
    val sway by idle.animateFloat(-1f, 1f, infiniteRepeatable(tween(KaiUrgentMotion.SWAY_MS / 2), RepeatMode.Reverse), label = "sway")
    val nod by idle.animateFloat(
        0f, 0f,
        infiniteRepeatable(
            keyframes {
                durationMillis = KaiUrgentMotion.NOD_EVERY_MS
                0f at 0
                0f at 5_900
                1f at 6_250
                -0.3f at 6_600
                0f at 6_950
            },
        ),
        label = "nod",
    )

    // A continuous clock for the orbit and particles (no wrap-around jump).
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) seconds += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }
    val particles = remember {
        val rnd = Random(7)
        List(22) {
            Particle(
                radius = 0.50f + rnd.nextFloat() * 0.48f,
                angle = rnd.nextFloat() * (2 * PI).toFloat(),
                speed = (0.05f + rnd.nextFloat() * 0.13f) * if (rnd.nextBoolean()) 1f else -1f,
                size = 1.1f + rnd.nextFloat() * 1.6f,
                twinkle = 0.6f + rnd.nextFloat() * 1.4f,
                phase = rnd.nextFloat() * 6.28f,
            )
        }
    }

    val wake = if (still) 1f else KaiUrgentMotion.wake(intro)
    val pulse = KaiUrgentMotion.pulse(intro)
    val idleW = if (still) 0f else KaiUrgentMotion.idleWeight(intro)
    val density = LocalDensity.current

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            val fieldAlpha = (1f - exit) * wake
            // Energy field: a very soft green breath behind Kai.
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(UrgentAccent.copy(alpha = 0.20f * fieldAlpha * (0.85f + 0.15f * breath * idleW)), UrgentAccent.copy(alpha = 0.04f * fieldAlpha), Color.Transparent),
                    center = center, radius = r,
                ),
                radius = r,
            )
            if (still) return@Canvas
            // Orbit: a thin arc slowly circling Kai.
            val orbitR = r * 0.86f
            val start = (seconds * 22f * speed) % 360f
            drawArc(
                brush = Brush.sweepGradient(listOf(Color.Transparent, UrgentAccent.copy(alpha = 0.45f * fieldAlpha), Color.Transparent), center = center),
                startAngle = start, sweepAngle = 210f, useCenter = false,
                topLeft = Offset(center.x - orbitR, center.y - orbitR), size = androidx.compose.ui.geometry.Size(orbitR * 2, orbitR * 2),
                style = Stroke(width = 1.4.dp.toPx()),
            )
            // Particles: tiny light traces drifting around him.
            particles.forEach { p ->
                val a = p.angle + p.speed * speed * seconds
                val pr = r * p.radius * (0.96f + 0.04f * sin(seconds * 0.7f + p.phase))
                val alpha = (0.25f + 0.55f * (0.5f + 0.5f * sin(seconds * p.twinkle + p.phase))) * fieldAlpha
                drawCircle(Color.White.copy(alpha = alpha * 0.55f), radius = p.size.dp.toPx(), center = Offset(center.x + pr * cos(a), center.y + pr * sin(a) * 0.82f))
            }
            // The one pulse wave as Kai turns to the owner.
            if (pulse in 0.001f..0.999f) {
                drawCircle(
                    color = UrgentAccent.copy(alpha = 0.40f * (1f - pulse) * (1f - exit)),
                    radius = r * (0.45f + 0.55f * pulse),
                    style = Stroke(width = (2.5f * (1f - pulse) + 0.8f).dp.toPx()),
                )
            }
        }
        // Kai: arrival (fade, 92 % → 100 %, rise), attention (lean + bump), idle (breath, sway, nod), exit.
        val alpha = (if (still) 1f else KaiUrgentMotion.kaiAlpha(intro)) * (1f - exit)
        val scale = (if (still) 1f else KaiUrgentMotion.kaiScale(intro)) * (1f + depth * breath * idleW) * (1f - 0.05f * exit)
        val riseDp = (if (still) 0f else KaiUrgentMotion.kaiRiseDp(intro)) - 3f * nod * idleW + (if (acknowledging) 6f else 10f) * exit
        val lean = (if (still) 0f else KaiUrgentMotion.kaiLeanDeg(intro)) + 0.7f * sway * idleW
        // During the attention beat Kai greets the owner; then his reminder face (or speaking, lips with the voice).
        val state = when {
            !still && KaiUrgentMotion.phaseAt(intro.toLong()) == KaiUrgentMotion.Phase.ATTENTION -> KaiState.GREETING
            speaking -> KaiState.SPEAKING
            else -> KaiState.REMINDER
        }
        KaiCharacter(
            state = state,
            speakingAs = KaiState.REMINDER,
            mouthLevel = mouthLevel,
            size = size * 0.74f,
            modifier = Modifier.graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                translationY = with(density) { riseDp.dp.toPx() }
                rotationZ = lean
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.92f)
            },
        )
    }
}
