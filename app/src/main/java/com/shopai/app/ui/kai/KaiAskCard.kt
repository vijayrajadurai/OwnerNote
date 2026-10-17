package com.shopai.app.ui.kai

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.chat.KaiAction
import com.shopai.app.brain.chat.KaiCard
import com.shopai.app.brain.note.KaiAskProgress

private val AskEmerald = Color(0xFF0F7A55)
private val AskEmeraldDark = Color(0xFF0B5C40)
private val AskMint = Color(0xFF7FE0B5)
private val AskGold = Color(0xFFE8C35A)
private val AskText = Color(0xFF1C2B26)
private val AskSoft = Color(0xFF5B6B66)

/**
 * Kai asking the one thing he needs ("Oru piece selling rate evlo?") — light glass, mint glow (owner, 17 Oct 2026, style B):
 * Kai's face and "2/3", the question large, chips of what the owner already said and the one asked (gold), a big mic to answer,
 * and Kai's own buttons. Everything comes from Kai's turn and his pending item; nothing is guessed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KaiAskCard(
    question: String,
    card: KaiCard?,
    lang: KaiLang,
    closed: Boolean,
    onTap: (KaiAction) -> Unit,
    modifier: Modifier = Modifier,
    progress: KaiAskProgress? = null,
    /** The mic: answer by voice (null hides the button — an older question). */
    onMic: (() -> Unit)? = null,
) {
    val life = rememberInfiniteTransition(label = "kai-ask")
    val glow by life.animateFloat(0.10f, 0.28f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "glow")
    val pulse by life.animateFloat(1f, 1.06f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        // The mint glow ring around the card.
        Box(
            Modifier
                .widthIn(max = 360.dp)
                .background(AskMint.copy(alpha = if (closed) 0.08f else glow), RoundedCornerShape(26.dp))
                .padding(5.dp),
        ) {
            Column(
                Modifier
                    .shadow(10.dp, RoundedCornerShape(22.dp), ambientColor = AskEmerald, spotColor = AskEmerald)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White)
                    .border(1.5.dp, AskMint, RoundedCornerShape(22.dp)),
            ) {
                Row(
                    Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(AskEmeraldDark, AskEmerald))).padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(42.dp).background(Color.White.copy(alpha = 0.2f), CircleShape).border(2.dp, AskMint, CircleShape).clip(CircleShape),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Image(painterResource(R.drawable.kai_curious), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(42.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when (lang) { KaiLang.TAMIL -> "Kai கேக்குறார்"; KaiLang.TANGLISH -> "Kai kekkuraar"; KaiLang.ENGLISH -> "Kai asks" } +
                            (progress?.takeIf { it.steps > 1 }?.let { " · ${it.step}/${it.steps}" } ?: ""),
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    )
                }
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    progress?.takeIf { it.steps > 1 }?.let { p ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            repeat(p.steps) { i ->
                                Box(Modifier.weight(1f).height(5.dp).background(if (i < p.step) AskEmerald else Color(0xFFE3ECE8), RoundedCornerShape(3.dp)))
                            }
                        }
                    }
                    Text(question, color = AskText, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp)
                    progress?.let { p ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            p.known.forEach { Chip(it, asking = false, pulse = 1f) }
                            Chip(p.asking, asking = !closed, pulse = pulse)
                        }
                    }
                    if (progress == null) card?.lines?.takeIf { it.isNotEmpty() }?.let { lines ->
                        Column(Modifier.fillMaxWidth().background(Color(0xFFF3F7F5), RoundedCornerShape(12.dp)).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)) { lines.forEach { Text(it, color = AskSoft, fontSize = 13.sp) } }
                    }
                    if (onMic != null && !closed) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .scale(pulse)
                                    .size(72.dp)
                                    .background(AskMint.copy(alpha = 0.25f), CircleShape)
                                    .padding(7.dp)
                                    .shadow(10.dp, CircleShape, ambientColor = AskEmerald, spotColor = AskEmerald)
                                    .background(Brush.radialGradient(listOf(Color(0xFF19A874), AskEmeraldDark)), CircleShape)
                                    .clickable { onMic() },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp)) }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                when (lang) {
                                    KaiLang.TAMIL -> "Mic அமுக்கி சொல்லுங்க · இல்ல கீழ type பண்ணுங்க"
                                    KaiLang.TANGLISH -> "Mic amukki sollunga · illa keezha type pannunga"
                                    KaiLang.ENGLISH -> "Tap the mic and say it · or type below"
                                },
                                color = AskSoft, fontSize = 12.sp,
                            )
                        }
                    }
                    card?.buttons?.forEach { b ->
                        val enabled = b.enabled && !closed
                        if (b.primary) Button(onClick = { onTap(b.action) }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AskEmerald)) { Text(b.label) }
                        else OutlinedButton(onClick = { onTap(b.action) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(b.label, color = AskEmerald) }
                    }
                }
            }
        }
    }
}

/** What the owner already said (mint), or the one thing Kai asks now (gold, gently pulsing). */
@Composable
private fun Chip(text: String, asking: Boolean, pulse: Float) {
    Text(
        text,
        color = if (asking) Color(0xFF5A4300) else AskEmeraldDark,
        fontSize = 13.sp,
        fontWeight = if (asking) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier
            .scale(if (asking) pulse else 1f)
            .background(if (asking) Color(0xFFFFF4D1) else Color(0xFFEEF8F3), RoundedCornerShape(50))
            .border(1.dp, if (asking) AskGold else Color(0xFFBFE8D5), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
