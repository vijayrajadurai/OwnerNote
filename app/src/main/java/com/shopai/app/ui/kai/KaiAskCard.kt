package com.shopai.app.ui.kai

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

private val AskEmerald = Color(0xFF0F7A55)
private val AskEmeraldDark = Color(0xFF0B5C40)
private val AskText = Color(0xFF1C2B26)
private val AskSoft = Color(0xFF5B6B66)

/**
 * Kai asking the one thing he needs ("Oru kg purchase rate evlo?", "1 box-la evlo pieces?") — a clear digital question card, not a
 * plain bubble (owner, 17 Oct 2026: "kekura question-um nalla digital-aa irukkanum"). The question is Kai's own words; the
 * card's buttons are his own (Cancel, Photo…); the owner answers by mic or keyboard as always.
 */
@Composable
fun KaiAskCard(question: String, card: KaiCard?, lang: KaiLang, closed: Boolean, onTap: (KaiAction) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .shadow(8.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(AskEmeraldDark, AskEmerald)))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).background(Color.White.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.kai_curious), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    when (lang) { KaiLang.TAMIL -> "Kai கேக்குறார்"; KaiLang.TANGLISH -> "Kai kekkuraar"; KaiLang.ENGLISH -> "Kai asks" },
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                )
            }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(question, color = AskText, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp)
                card?.lines?.takeIf { it.isNotEmpty() }?.let { lines ->
                    Column(
                        Modifier.fillMaxWidth().background(Color(0xFFF3F7F5), RoundedCornerShape(12.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) { lines.forEach { Text(it, color = AskSoft, fontSize = 13.sp) } }
                }
                // How to answer: by voice or by typing — the same Kai either way.
                Row(
                    Modifier.fillMaxWidth().border(1.dp, AskEmerald.copy(alpha = 0.25f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = AskEmerald, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Keyboard, contentDescription = null, tint = AskEmerald, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (lang) {
                            KaiLang.TAMIL -> "பதில் சொல்லுங்க இல்ல type பண்ணுங்க"
                            KaiLang.TANGLISH -> "Badhil sollunga illa type pannunga"
                            KaiLang.ENGLISH -> "Say or type your answer"
                        },
                        color = AskSoft, fontSize = 13.sp,
                    )
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
