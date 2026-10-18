package com.shopai.app.ui.kai

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.chat.KaiAction
import com.shopai.app.brain.chat.KaiCard
import com.shopai.app.brain.note.KaiMoneyDraft
import com.shopai.app.brain.note.KaiNoteFlow
import com.shopai.app.brain.note.KaiNoteState
import com.shopai.app.brain.note.KaiNotes

/** Money to the shop (owed to the owner, received) and money going out. */
internal val MoneyIn = Color(0xFF1F7A4D)
internal val MoneyOut = Color(0xFFD92D20)

/**
 * A credit / debit / payment draft as a digital card, like the stock card (owner, 18 Oct 2026): what it is and who, the due
 * date, the person with the amount large (green to collect, red to pay), and the balance before → after with a bar. No mark
 * beside the amount (owner: "5000 pakkathula round circle venam"); the header says "Save aagiduchu ✓" only once the books saved
 * it. The buttons are the draft card's own, enabled once the note is ready.
 */
@Composable
fun KaiMoneyCard(
    money: KaiMoneyDraft,
    flow: KaiNoteFlow,
    card: KaiCard,
    lang: KaiLang,
    closed: Boolean,
    onWritten: () -> Unit,
    onTap: (KaiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = flow.noteState
    val tint = if (money.toShop) MoneyIn else MoneyOut
    // The card comes in: the amount pops, the bar fills; then the buttons are ready.
    val shown = remember(money) { Animatable(if (state == KaiNoteState.DRAFT_READY) 0f else 1f) }
    LaunchedEffect(money) {
        if (shown.value < 1f) shown.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        onWritten()
    }
    val t = shown.value

    Box(modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.08f))
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White),
        ) {
            // Header: what it is, who, and the due date — or how it ended.
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(money.heading, color = IosGrey, fontSize = 12.sp, letterSpacing = 0.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(money.name, color = Color.Black, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textDecoration = if (state == KaiNoteState.CANCELLED) TextDecoration.LineThrough else null)
                }
                Spacer(Modifier.width(8.dp))
                StatePill(state, money.due, lang)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(IosSeparator))
            // The person and the amount.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(avatarColor(money.name), CircleShape), contentAlignment = Alignment.Center) {
                    Text(initials(money.name), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(money.name, color = IosLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(money.detail, color = IosGrey, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(money.amount, color = if (state == KaiNoteState.CANCELLED) IosGrey else tint, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, modifier = Modifier.padding(start = 8.dp).scale(0.85f + 0.15f * t).alpha(0.3f + 0.7f * t))
            }
            // The balance before → after, and the bar of what this entry changes.
            if (money.balanceLabel != null && money.before != null && money.after != null) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(IosSeparator))
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(money.balanceLabel, color = IosGrey, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${money.before} → ${money.after}", color = IosLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                    val kept = money.kept
                    val changed = money.changed
                    if (kept != null && changed != null) {
                        Spacer(Modifier.height(7.dp))
                        Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(IosSeparator)) {
                            if (kept > 0f) Box(Modifier.weight(kept).fillMaxHeight().background(tint))
                            if (changed > 0f) Box(Modifier.weight(changed * t.coerceAtLeast(0.001f)).fillMaxHeight().background(tint.copy(alpha = 0.4f)))
                            val rest = 1f - kept - changed * t
                            if (rest > 0.001f) Spacer(Modifier.weight(rest))
                        }
                    }
                }
            }
            card.warning?.let { Text(it, color = IosRed, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            // The draft card's own buttons, while it waits.
            if (state in setOf(KaiNoteState.DRAFT_READY, KaiNoteState.REVIEWING, KaiNoteState.SAVING) && card.buttons.isNotEmpty()) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val ready = state == KaiNoteState.REVIEWING && !closed
                    card.buttons.forEach { b ->
                        val enabled = ready && b.enabled
                        if (b.primary) Box(
                            Modifier.fillMaxWidth().height(50.dp).alpha(if (enabled || state == KaiNoteState.SAVING) 1f else 0.3f)
                                .clip(RoundedCornerShape(14.dp)).background(Color.Black).clickable(enabled = enabled) { onTap(b.action) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state == KaiNoteState.SAVING) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                            else Text(b.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        } else Text(
                            b.label,
                            color = when { !enabled -> IosGrey; isCancel(b.action) -> IosRed; else -> Color(0xFF007AFF) },
                            fontSize = 16.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { onTap(b.action) }.padding(vertical = 10.dp),
                        )
                    }
                }
            } else Spacer(Modifier.height(4.dp))
        }
    }
}

/** The header's pill: the due date while it waits; then saved / not saved / cancelled — saved only after the books said so. */
@Composable
private fun StatePill(state: KaiNoteState, due: String?, lang: KaiLang) {
    val (text, color) = when (state) {
        KaiNoteState.SAVE_SUCCESS -> KaiNotes.status(state, lang) to IosGreen
        KaiNoteState.SAVE_ERROR -> KaiNotes.rowStatus(com.shopai.app.brain.note.KaiNoteKind.OTHER, state, lang) to IosRed
        KaiNoteState.CANCELLED -> KaiNotes.rowStatus(com.shopai.app.brain.note.KaiNoteKind.OTHER, state, lang) to IosGrey
        KaiNoteState.SAVING -> KaiNotes.status(state, lang) to IosGrey
        else -> (due ?: return) to MoneyIn
    }
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
}

/** "Kumar" → "K", "ABC Traders" → "AT", "குமார்" → "கு". */
internal fun initials(name: String): String {
    val words = name.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    fun first(w: String): String {
        val cp = w.codePointAt(0)
        val head = String(Character.toChars(cp))
        // A Tamil letter with its vowel sign stays one letter ("கு", not "க").
        val next = w.drop(head.length).takeWhile { Character.getType(it) == Character.NON_SPACING_MARK.toInt() || Character.getType(it) == Character.COMBINING_SPACING_MARK.toInt() }
        return (head + next).uppercase()
    }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> first(words[0])
        else -> first(words[0]) + first(words[1])
    }
}

/** The same colour for the same person every time. */
internal fun avatarColor(name: String): Color {
    val palette = listOf(Color(0xFF34A853), Color(0xFFE8590C), Color(0xFF3B82F6), Color(0xFF9333EA), Color(0xFF0891B2), Color(0xFFDB2777), Color(0xFFCA8A04))
    return palette[Math.floorMod(name.trim().lowercase().hashCode(), palette.size)]
}
