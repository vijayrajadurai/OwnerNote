package com.shopai.app.ui.kaichat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.ui.kai.KaiFace
import com.shopai.app.ui.kai.KaiInk

/** Kai Chat's night-green backdrop with a soft glow and a faint dot grid — the "digital" look (owner, 22 Oct 2026). */
fun Modifier.kaiChatBackdrop(): Modifier = this
    .background(KaiInk.Night)
    .drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(Color(0x5710B981), Color.Transparent),
                center = Offset(size.width / 2f, 0f),
                radius = size.width * 0.9f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(Color(0x24D4A537), Color.Transparent),
                center = Offset(size.width, size.height * 0.55f),
                radius = size.width * 0.7f,
            ),
        )
        val step = 18.dp.toPx()
        val dot = Color(0x12FFFFFF)
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) {
                drawCircle(dot, radius = 1.dp.toPx(), center = Offset(x, y))
                x += step
            }
            y += step
        }
    }

@Composable
fun KaiChatTopBar(onBack: () -> Unit, canClear: Boolean, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x0FFFFFFF)).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color(0xFFECFDF5), modifier = Modifier.size(20.dp))
        }
        KaiFace(size = 38.dp, online = true)
        Column(Modifier.weight(1f)) {
            Text("Kai Chat", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text(stringResource(R.string.kai_chat_online), color = KaiInk.GreenLight, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (canClear) {
            Box(
                Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x14FFFFFF))
                    .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(12.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.kai_chat_clear), color = Color(0xFFECFDF5), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** A slowly turning green-gold ring of light behind Kai. */
@Composable
fun KaiHaloRing(size: Dp, modifier: Modifier = Modifier) {
    val spin = rememberInfiniteTransition(label = "kaiHalo")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "kaiHaloAngle")
    Box(
        modifier.size(size).drawBehind {
            drawCircle(Brush.radialGradient(listOf(Color(0x5910B981), Color.Transparent)), radius = this.size.minDimension / 2f)
            rotate(angle) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, KaiInk.GreenLight, KaiInk.Gold, Color.Transparent)),
                    startAngle = 0f,
                    sweepAngle = 220f,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx()),
                    topLeft = Offset(6.dp.toPx(), 6.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(this.size.width - 12.dp.toPx(), this.size.height - 12.dp.toPx()),
                )
            }
        },
    )
}

/** Kai's bubble on the dark chat: soft glass with a thin green-gold edge. */
@Composable
fun KaiDarkBubble(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KaiFace(size = 28.dp, ring = 1.5.dp)
        val shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
        Text(
            text,
            color = Color(0xFFECFDF5),
            fontSize = 15.sp,
            lineHeight = 21.sp,
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(shape)
                .background(Color(0xEB061C16))
                .border(1.dp, Brush.linearGradient(listOf(Color(0x9934D399), Color(0x80D4A537), Color(0x14FFFFFF))), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** The owner's bubble: green, right side; a spoken line carries the mic mark. */
@Composable
fun OwnerGreenBubble(text: String, voice: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        val shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
        Text(
            if (voice) "🎙 $text" else text,
            color = Color.White,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(shape)
                .background(Brush.linearGradient(listOf(KaiInk.Green, KaiInk.GreenDeep)))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** "Kai is writing…": three dots that hop. */
@Composable
fun KaiTypingBubble() {
    val t = rememberInfiniteTransition(label = "kaiTyping")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "kaiTypingPhase")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KaiFace(size = 28.dp, ring = 1.5.dp)
        Row(
            Modifier
                .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
                .background(Color(0x14FFFFFF))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(3) { i ->
                val d = ((phase - i + 3f) % 3f)
                val lift = if (d < 1f) kotlin.math.sin(d * Math.PI).toFloat() else 0f
                Box(
                    Modifier
                        .size(7.dp)
                        .offset(y = (-5f * lift).dp)
                        .graphicsLayer { alpha = 0.4f + 0.6f * lift }
                        .clip(CircleShape)
                        .background(KaiInk.GreenLight),
                )
            }
        }
    }
}

/** Suggestion chips on the dark chat (they send the line, the same as typing it). */
@Composable
fun KaiDarkChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x0FFFFFFF))
            .border(1.dp, Color(0x38A7F3D0), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color(0xFFD1FAE5), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * Full-screen voice mode while the mic records: Kai standing in rings of light, the words heard so far, and one button that
 * finishes (the same as tapping the mic — what was heard is sent).
 */
@Composable
fun KaiVoiceOverlay(heard: String?, onFinish: () -> Unit) {
    val t = rememberInfiniteTransition(label = "kaiVoice")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "kaiVoiceRipple")
    val bob by t.animateFloat(0f, -6f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "kaiVoiceBob")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "kaiVoiceWave")
    Box(
        Modifier
            .fillMaxSize()
            .kaiChatBackdrop()
            // Swallows taps so the chat underneath can't be pressed while Kai listens.
            .clickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) {},
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top))
                .padding(top = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).graphicsLayer { alpha = 0.4f + 0.6f * wave }.clip(CircleShape).background(Color(0xFFEF4444)))
                Text(stringResource(R.string.kai_voice_listening), color = Color(0xFFFCA5A5), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
            }
            Text(stringResource(R.string.kai_voice_title), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
        Box(Modifier.align(Alignment.Center).offset(y = (-30).dp), contentAlignment = Alignment.Center) {
            for (k in 0 until 3) {
                val q = (p + k / 3f) % 1f
                Box(
                    Modifier
                        .size(220.dp)
                        .scale(0.6f + 1.2f * q)
                        .graphicsLayer { alpha = 0.55f * (1f - q) }
                        .border(2.dp, if (k == 1) KaiInk.Gold else KaiInk.Green, CircleShape),
                )
            }
            Image(
                painter = painterResource(R.drawable.kai_mascot),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(380.dp).offset(y = bob.dp),
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom))
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 13) {
                    val h = 0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin((wave + i * 0.17f) * Math.PI)).toFloat()
                    Box(
                        Modifier
                            .width(5.dp)
                            .height((34f * h).dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (i % 3 == 1) KaiInk.Gold else Color(0xFF34D399)),
                    )
                }
            }
            Text(
                text = heard?.takeIf { it.isNotBlank() } ?: stringResource(R.string.kai_chat_listening),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x14FFFFFF))
                    .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF34D399), Color(0xFF059669))))
                    .clickable(onClick = onFinish),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.kai_voice_done), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}
