package com.shopai.app.ui.kai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import kotlinx.coroutines.delay

/** Kai's colours: the green towel and the gold-and-green border of his veshti. */
object KaiInk {
    val Night = Color(0xFF03100C)
    val NightCard = Color(0xFF071A14)
    val Green = Color(0xFF10B981)
    val GreenDeep = Color(0xFF047857)
    val GreenLight = Color(0xFF6EE7B7)
    val Mint = Color(0xFFA7F3D0)
    val Gold = Color(0xFFD4A537)
    val GoldLight = Color(0xFFFDE68A)
    val Paper = Color(0xFFF6F4EE)
    /** The light green under the mixed-green backdrop — the gap around Home's tiles and badges. */
    val Leaf = Color(0xFFE3F4E9)
    val Ink = Color(0xFF1C1917)
    val InkSoft = Color(0xFF78716C)
    val Line = Color(0xFFE7E3D8)
}

/**
 * Home's mixed-green background (owner, 23 Oct 2026: "app background mixed green theme", every business type): light mint at
 * the top fading to a soft green-white, two quiet green glows and a faint dot grid.
 */
fun Modifier.kaiGreenBackdrop(): Modifier = drawBehind {
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFFD9F2E3),
            0.3f to Color(0xFFE8F6EC),
            0.7f to Color(0xFFF1F8F1),
            1f to Color(0xFFF6F9F2),
        ),
    )
    val r1 = size.width * 0.9f
    drawCircle(
        Brush.radialGradient(listOf(Color(0x99A7F3D0), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.width * 0.15f), radius = r1),
        radius = r1,
        center = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.width * 0.15f),
    )
    val r2 = size.width * 0.95f
    val c2 = androidx.compose.ui.geometry.Offset(size.width * 1.05f, size.height * 0.55f)
    drawCircle(Brush.radialGradient(listOf(Color(0x99BBF7D0), Color.Transparent), center = c2, radius = r2), radius = r2, center = c2)
    val step = 18.dp.toPx()
    val dot = 1.dp.toPx()
    val ink = Color(0x14047857)
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) {
            drawCircle(ink, radius = dot, center = androidx.compose.ui.geometry.Offset(x, y))
            x += step
        }
        y += step
    }
}

private val veshtiSweep = listOf(KaiInk.Green, KaiInk.GoldLight, KaiInk.GreenDeep, KaiInk.Gold, KaiInk.Green)

/** The veshti border stripe (green, gold, green) under cards — OwnerNote's own mark. */
@Composable
fun VeshtiBand(modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Column(modifier.fillMaxWidth().height(height)) {
        Box(Modifier.fillMaxWidth().weight(3f).background(Color(0xFF15803D)))
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.White))
        Box(Modifier.fillMaxWidth().weight(2f).background(KaiInk.Gold))
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.White))
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF15803D)))
    }
}

/** Kai's face in a green-gold ring, with an optional "online" dot. */
@Composable
fun KaiFace(size: Dp, modifier: Modifier = Modifier, online: Boolean = false, ring: Dp = 2.dp) {
    Box(modifier.size(size)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(KaiInk.Green, KaiInk.Gold, KaiInk.GreenDeep)))
                .padding(ring)
                .clip(CircleShape)
                .background(Color(0xFFECFDF5)),
        ) {
            Image(
                painter = painterResource(R.drawable.kai_mascot_face),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (online) {
            val blink = rememberInfiniteTransition(label = "kaiOnline")
            val a by blink.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "kaiOnlineDot")
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.26f)
                    .graphicsLayer { alpha = a }
                    .clip(CircleShape)
                    .background(Color(0xFF22C55E))
                    .border(2.dp, KaiInk.Night, CircleShape),
            )
        }
    }
}

/** A box whose border is a slowly turning green-gold ring of light. */
@Composable
fun SpinningVeshtiBorder(
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    width: Dp = 2.dp,
    content: @Composable () -> Unit,
) {
    val spin = rememberInfiniteTransition(label = "veshtiSpin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "veshtiAngle")
    Box(
        modifier
            .clip(shape)
            .drawBehind {
                rotate(angle) {
                    drawCircle(brush = Brush.sweepGradient(veshtiSweep), radius = size.maxDimension)
                }
            }
            .padding(width),
    ) {
        Box(Modifier.clip(shape)) { content() }
    }
}

/** A line that changes every few seconds, sliding up — Kai's example sentences. */
@Composable
fun RotatingLine(lines: List<String>, color: Color, modifier: Modifier = Modifier, everyMs: Long = 3000) {
    if (lines.isEmpty()) return
    var index by remember(lines) { mutableIntStateOf(0) }
    LaunchedEffect(lines) {
        while (true) {
            delay(everyMs)
            index = (index + 1) % lines.size
        }
    }
    AnimatedContent(
        targetState = index,
        transitionSpec = {
            (slideInVertically(tween(350)) { it } + fadeIn(tween(350))) togetherWith
                (slideOutVertically(tween(350)) { -it } + fadeOut(tween(250)))
        },
        label = "kaiRotatingLine",
        modifier = modifier,
    ) { i ->
        Text(
            text = "“${lines[i]}”",
            color = color,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Mic button with soft ripples going out from it. */
@Composable
fun KaiMicOrb(size: Dp, modifier: Modifier = Modifier, listening: Boolean = false) {
    val ripple = rememberInfiniteTransition(label = "kaiMicRipple")
    val p by ripple.animateFloat(0f, 1f, infiniteRepeatable(tween(if (listening) 1100 else 2200, easing = FastOutSlowInEasing)), label = "kaiMicRippleP")
    val base = if (listening) Color(0xFFEF4444) else KaiInk.Green
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .scale(1f + 0.7f * p)
                .graphicsLayer { alpha = 0.5f * (1f - p) }
                .clip(CircleShape)
                .background(base),
        )
        Box(
            Modifier
                .size(size)
                .shadow(10.dp, CircleShape, ambientColor = base, spotColor = base)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        if (listening) listOf(Color(0xFFF87171), Color(0xFFB91C1C)) else listOf(KaiInk.GreenLight, Color(0xFF059669)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.48f))
        }
    }
}

/**
 * Home's "Kai-kitta kelunga" bar: Kai's face, a changing example line and the mic, inside a turning
 * green-gold border. Tapping anywhere opens Kai Chat — the app's main feature.
 */
@Composable
fun KaiAskStrip(
    title: String,
    examples: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    val bob = rememberInfiniteTransition(label = "kaiStripBob")
    val dy by bob.animateFloat(0f, -3f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "kaiStripBobY")
    SpinningVeshtiBorder(
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .shadow(12.dp, shape, ambientColor = Color(0x47042F24), spotColor = Color(0x47042F24))
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .background(
                    Brush.horizontalGradient(listOf(Color(0xFF0B3B2D), KaiInk.Night, KaiInk.Night)),
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KaiFace(size = 46.dp, online = true, modifier = Modifier.offset(y = dy.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                RotatingLine(lines = examples, color = KaiInk.GoldLight)
            }
            KaiMicOrb(size = 44.dp)
        }
    }
}
