package com.shopai.app.ui.kai

import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.chat.KaiAction
import com.shopai.app.brain.chat.KaiCard
import com.shopai.app.brain.note.KaiAskProgress

/** iPhone colours (system green, grey, grouped background, separator, red, label). */
internal val IosGreen = Color(0xFF34C759)
internal val IosGrey = Color(0xFF8E8E93)
internal val IosGrouped = Color(0xFFF2F2F7)
internal val IosSeparator = Color(0xFFE5E5EA)
internal val IosRed = Color(0xFFFF3B30)
internal val IosLabel = Color(0xFF1C1C1E)

/** Kai's own "drop it" buttons — shown as red text, as on an iPhone. */
internal fun isCancel(a: KaiAction): Boolean = a is KaiAction.CancelPlan || a is KaiAction.CancelRequest || a is KaiAction.CancelReminder ||
    a is KaiAction.CancelStock || a == KaiAction.CancelGoods || a is KaiAction.CancelSale || a is KaiAction.CancelDayClose

/**
 * Kai asking the one thing he needs ("Oru piece selling rate evlo?") — small and clean (owner, 18 Oct 2026: "question card text
 * ellaam perusa varudhu, nalla illa"): Kai's face, "Kekkuraar · 2/3" and the step bars on one line; the question in one short
 * line; what the owner already said as small chips (Product Noodles · Qty 100 pcs · Purchase ₹50); the one thing asked as a green
 * field with a blinking cursor; then a small "Sollunga" mic, Kai's own buttons as small pills and Cancel in red. Nothing is
 * guessed: the chips come from Kai's pending item.
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
    val cursor by life.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "cursor")
    val shape = RoundedCornerShape(20.dp)

    Box(modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f))
                .clip(shape)
                .background(Color.White)
                .alpha(if (closed) 0.6f else 1f)
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        ) {
            // Kai, "Kekkuraar · 2/3", and the step bars.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(Color(0xFFFFF1D6)), contentAlignment = Alignment.TopCenter) {
                    Image(painterResource(R.drawable.kai_curious), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    "Kai · " + when (lang) { KaiLang.TAMIL -> "கேக்குறார்"; KaiLang.TANGLISH -> "Kekkuraar"; KaiLang.ENGLISH -> "Asking" } +
                        (progress?.takeIf { it.steps > 1 }?.let { " · ${it.step}/${it.steps}" } ?: ""),
                    color = IosGrey, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                progress?.takeIf { it.steps > 1 }?.let { p ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(p.steps) { i -> Box(Modifier.size(width = 14.dp, height = 3.dp).background(if (i < p.step) AskGreen else IosSeparator, RoundedCornerShape(2.dp))) }
                    }
                }
            }
            Text(question, color = IosLabel, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 7.dp))

            // What the owner already said, as small chips; then the one thing asked, as a field waiting for the answer.
            progress?.let { p ->
                if (p.known.isNotEmpty()) FlowRow(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) { p.known.forEach { (label, value) -> KnownChip(label, value) } }
                Row(
                    Modifier.padding(top = 9.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (closed) IosGrouped else AskGreenTint)
                        .border(1.5.dp, if (closed) IosSeparator else AskGreen, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.asking, color = AskGreenDark, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    if (!closed) Box(Modifier.size(width = 2.dp, height = 16.dp).background(if (cursor < 0.5f) AskGreen else Color.Transparent))
                }
            }
            if (progress == null) card?.lines?.takeIf { it.isNotEmpty() }?.let { lines ->
                Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(IosGrouped)
                    .padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    lines.forEach { line -> Text(line, color = IosLabel, fontSize = 13.sp, lineHeight = 17.sp) }
                }
            }

            // The small mic, Kai's own buttons as pills, and the "drop it" ones in red at the end.
            val buttons = card?.buttons.orEmpty()
            val showMic = onMic != null && !closed
            if (showMic || buttons.isNotEmpty()) FlowRow(
                modifier = Modifier.padding(top = 9.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (showMic) Row(
                    Modifier.height(32.dp).clip(RoundedCornerShape(10.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFF19B97F), Color(0xFF0B8A5C))))
                        .clickable { onMic?.invoke() }.padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(when (lang) { KaiLang.TAMIL -> "சொல்லுங்க"; KaiLang.TANGLISH -> "Sollunga"; KaiLang.ENGLISH -> "Say it" },
                        color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                buttons.filterNot { isCancel(it.action) }.forEach { b ->
                    val enabled = b.enabled && !closed
                    Box(
                        Modifier.height(32.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (b.primary && enabled) AskInk else IosGrouped)
                            .clickable(enabled = enabled) { onTap(b.action) }.padding(horizontal = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(b.label, color = when { !enabled -> IosGrey; b.primary -> Color.White; else -> IosLabel },
                            fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
                buttons.filter { isCancel(it.action) }.forEach { b ->
                    val enabled = b.enabled && !closed
                    Box(
                        Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { onTap(b.action) }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(b.label, color = if (enabled) IosRed else IosGrey, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

private val AskGreen = Color(0xFF0E9F6E)
private val AskGreenDark = Color(0xFF0A7A54)
private val AskGreenTint = Color(0xFFF0FAF5)
private val AskInk = Color(0xFF1A1530)

/** One thing the owner already said: "Product **Noodles**". */
@Composable
private fun KnownChip(label: String, value: String) {
    Text(
        buildAnnotatedString {
            append("$label ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = IosLabel)) { append(value) }
        },
        color = Color(0xFF55506A), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(IosGrouped).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
