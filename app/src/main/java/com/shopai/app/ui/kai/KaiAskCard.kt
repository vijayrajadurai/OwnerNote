package com.shopai.app.ui.kai

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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
 * Kai asking the one thing he needs ("Oru piece selling rate evlo?") — iPhone style (owner, 18 Oct 2026: "iPhone classy-la kudu"):
 * a white sheet with Kai's face and the step bars, the question large and bold, a clean list of what the owner already said with the
 * asked row last (green, a blinking cursor), a green mic to answer by voice, and Kai's own buttons as text. Nothing is guessed: the
 * rows come from Kai's pending item.
 */
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
    val ring by life.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ring")
    val cursor by life.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "cursor")

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .shadow(14.dp, RoundedCornerShape(28.dp), ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f))
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White)
                .alpha(if (closed) 0.6f else 1f)
                .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 14.dp),
        ) {
            // The sheet's grabber.
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 38.dp, height = 5.dp).background(Color(0xFFD1D1D6), RoundedCornerShape(3.dp)))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFFE8F5EE)), contentAlignment = Alignment.TopCenter) {
                    Image(painterResource(R.drawable.kai_curious), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Kai", color = IosLabel, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        when (lang) { KaiLang.TAMIL -> "கேக்குறார்"; KaiLang.TANGLISH -> "Kekkuraar"; KaiLang.ENGLISH -> "Asking" } +
                            (progress?.takeIf { it.steps > 1 }?.let { " · ${it.step}/${it.steps}" } ?: ""),
                        color = IosGrey, fontSize = 12.sp,
                    )
                }
                progress?.takeIf { it.steps > 1 }?.let { p ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(p.steps) { i -> Box(Modifier.size(width = 18.dp, height = 4.dp).background(if (i < p.step) IosGreen else IosSeparator, RoundedCornerShape(2.dp))) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(question, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 28.sp)
            Spacer(Modifier.height(14.dp))

            // What the owner already said, and the one asked now — an iPhone inset list.
            progress?.let { p ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(IosGrouped)) {
                    p.known.forEachIndexed { i, (label, value) ->
                        if (i > 0) Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(0.5.dp).background(IosSeparator))
                        ListRow(label, value, asking = false, cursorOn = false)
                    }
                    if (p.known.isNotEmpty()) Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(0.5.dp).background(IosSeparator))
                    ListRow(p.asking, "?", asking = !closed, cursorOn = cursor < 0.5f)
                }
            }
            if (progress == null) card?.lines?.takeIf { it.isNotEmpty() }?.let { lines ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(IosGrouped)) {
                    lines.forEachIndexed { i, line ->
                        if (i > 0) Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(0.5.dp).background(IosSeparator))
                        Text(line, color = IosLabel, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp))
                    }
                }
            }

            if (onMic != null && !closed) {
                Spacer(Modifier.height(18.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        // The ring that goes out from the mic, as iPhone's Siri / dictation.
                        Box(Modifier.size(82.dp).scale(0.9f + 0.35f * ring).alpha(1f - ring).border(2.dp, IosGreen.copy(alpha = 0.45f), CircleShape))
                        Box(
                            Modifier
                                .size(66.dp)
                                .shadow(12.dp, CircleShape, ambientColor = IosGreen, spotColor = IosGreen)
                                .background(IosGreen, CircleShape)
                                .clickable { onMic() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (lang) {
                            KaiLang.TAMIL -> "Mic அமுக்கி சொல்லுங்க · இல்ல கீழ type பண்ணுங்க"
                            KaiLang.TANGLISH -> "Mic amukki sollunga · illa keezha type pannunga"
                            KaiLang.ENGLISH -> "Tap the mic and say it · or type below"
                        },
                        color = IosGrey, fontSize = 13.sp, textAlign = TextAlign.Center,
                    )
                }
            }

            card?.buttons?.takeIf { it.isNotEmpty() }?.let { buttons ->
                Spacer(Modifier.height(10.dp))
                buttons.forEach { b ->
                    val enabled = b.enabled && !closed
                    Text(
                        b.label,
                        color = when { !enabled -> IosGrey; isCancel(b.action) -> IosRed; else -> Color(0xFF007AFF) },
                        fontSize = 17.sp,
                        fontWeight = if (b.primary) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = enabled) { onTap(b.action) }.padding(vertical = 11.dp),
                    )
                }
            }
        }
    }
}

/** One list row: the label in grey on the left, the value on the right; the asked row's value in green with a blinking cursor. */
@Composable
private fun ListRow(label: String, value: String, asking: Boolean, cursorOn: Boolean) {
    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (asking) IosLabel else IosGrey, fontSize = 15.sp, fontWeight = if (asking) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Text(value, color = if (asking) IosGreen else IosLabel, fontSize = 15.sp, fontWeight = if (asking) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (asking) Box(Modifier.padding(start = 3.dp).size(width = 2.dp, height = 18.dp).background(if (cursorOn) IosGreen else Color.Transparent))
    }
}
