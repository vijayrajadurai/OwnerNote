package com.shopai.app.ui.kai

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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.chat.KaiAction
import com.shopai.app.brain.chat.KaiCard
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.PlanKind

/**
 * A money draft waiting for Confirm, made for the owner's thumb (owner, 22 Oct 2026, preview approved): what it is and the amount
 * big ("Kadan — Kumar tharanum · ₹2,000"), the rows Kai prepared (who, mode, due date …), the balance before → after, one big
 * Confirm, then Edit and Cancel, and "nothing is saved before Confirm". Shows only what Kai's draft holds — its card lines and its
 * plan; the buttons are Kai's own (the same actions as before), so saving is unchanged.
 */
@Composable
fun KaiConfirmCard(card: KaiCard, plan: ActionPlan?, lang: KaiLang, closed: Boolean, onTap: (KaiAction) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    val first = card.lines.firstOrNull().orEmpty().split(" — ", limit = 2)
    val who = plan?.partyName ?: first.firstOrNull().orEmpty()
    val amount = plan?.amount?.let { KaiFormat.rupees(it.toDouble()) } ?: first.getOrNull(1).orEmpty()
    val what = card.lines.getOrNull(1).orEmpty()
    val rest = card.lines.drop(2)
    val ref = rest.lastOrNull { refLine.containsMatchIn(it) }
    // The balance row: from the plan when it has both figures, else the card's own "Balance: ₹1,000 → ₹3,000" line.
    val balanceLine = rest.lastOrNull { it.contains(" → ") && balanceLabel.containsMatchIn(it) }
    val (before, after) = when {
        plan?.balanceBefore != null && plan.balanceAfter != null -> KaiFormat.rupees(plan.balanceBefore.toDouble()) to KaiFormat.rupees(plan.balanceAfter.toDouble())
        balanceLine != null -> balanceLine.substringAfter(':').substringBefore(" → ").trim() to balanceLine.substringAfter(" → ").trim()
        else -> null to null
    }
    val rows = rest.filter { it != ref && it != balanceLine }
    val (icon, tint, wash) = kindLook(plan?.kind)

    Box(modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth()
                .shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f))
                .clip(shape).background(Color.White).alpha(if (closed) 0.6f else 1f).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                when (lang) {
                    KaiLang.TAMIL -> "Kai தயார் பண்ணிருக்காரு — சரிபாருங்க"
                    KaiLang.TANGLISH -> "Kai ready pannirukkaar — check pannunga"
                    KaiLang.ENGLISH -> "Kai has it ready — please check"
                },
                color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
            // What it is and how much, big.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(wash), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (what.isNotBlank()) Text(what, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(amount, color = IosLabel, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            // Kai's rows: who, then each detail ("Mode: Cash" → Mode · Cash), then the balance before → after.
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tile)) {
                DetailRow(label = when (lang) { KaiLang.TAMIL -> "யாரு"; KaiLang.TANGLISH -> "Yaaru"; KaiLang.ENGLISH -> "Who" }, value = who, divider = rows.isNotEmpty() || before != null)
                rows.forEachIndexed { i, line ->
                    val more = i < rows.lastIndex || before != null
                    val colon = line.indexOf(": ")
                    if (colon in 1..24) DetailRow(label = line.substring(0, colon), value = line.substring(colon + 2), divider = more)
                    else Text(line, color = IosLabel, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp))
                }
                if (before != null && after != null) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when (lang) { KaiLang.TAMIL -> "$who பாக்கி"; KaiLang.TANGLISH -> "$who balance"; KaiLang.ENGLISH -> "$who's balance" },
                        color = Body, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
                    Text(before, color = Muted, fontSize = 15.sp, textDecoration = TextDecoration.LineThrough)
                    Text("  →  ", color = Muted, fontSize = 15.sp)
                    Text(after, color = tint, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
            card.warning?.let { w ->
                Text(w, color = WarnInk, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(WarnWash).border(1.dp, WarnLine, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp))
            }
            // One big Confirm; Edit and Cancel under it.
            card.buttons.filter { it.primary }.forEach { b ->
                val enabled = b.enabled && !closed
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(if (enabled) Ink else IosSeparator)
                        .clickable(enabled = enabled) { onTap(b.action) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = if (enabled) Color.White else Muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(b.label, color = if (enabled) Color.White else Muted, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            val others = card.buttons.filterNot { it.primary }
            if (others.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                others.forEach { b ->
                    val enabled = b.enabled && !closed
                    val cancel = isCancel(b.action)
                    Box(
                        Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(12.dp)).background(if (cancel) Color.White else IosGrouped)
                            .then(if (cancel) Modifier.border(1.5.dp, RedLine, RoundedCornerShape(12.dp)) else Modifier)
                            .clickable(enabled = enabled) { onTap(b.action) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(b.label, color = when { !enabled -> IosGrey; cancel -> Red; else -> IosLabel }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            Text(
                when (lang) {
                    KaiLang.TAMIL -> "Confirm அமுக்குற வரைக்கும் எதுவும் save ஆகாது"
                    KaiLang.TANGLISH -> "Confirm amukkura varaikkum edhuvum save aagadhu"
                    KaiLang.ENGLISH -> "Nothing is saved until you press Confirm"
                } + (ref?.let { " · $it" } ?: ""),
                color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One row of the card: the label left, the value right and bold. */
@Composable
private fun DetailRow(label: String, value: String, divider: Boolean) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Body, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Text(value, color = IosLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
        }
        if (divider) Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(1.dp).background(IosSeparator))
    }
}

/** The draft's icon: money in (down), out (up), owed (the book). */
private fun kindLook(kind: PlanKind?): Triple<ImageVector, Color, Color> = when (kind) {
    PlanKind.PAYMENT_IN -> Triple(Icons.Filled.ArrowDownward, Color(0xFF0A5DB8), Color(0xFFE3F1FF))
    PlanKind.PAYMENT_OUT -> Triple(Icons.Filled.ArrowUpward, Color(0xFF4B3A8C), Color(0xFFEEEAF7))
    PlanKind.DEBIT_TAKEN -> Triple(Icons.AutoMirrored.Filled.MenuBook, Color(0xFF4B3A8C), Color(0xFFEEEAF7))
    else -> Triple(Icons.AutoMirrored.Filled.MenuBook, Color(0xFFA35200), Color(0xFFFFF0DD))
}

private val refLine = Regex("""^(?:Ref|குறிப்பு எண்)\s*:""")
private val balanceLabel = Regex("""^(?:Balance|பாக்கி)\s*:""")
private val Ink = Color(0xFF1A1530)
private val Muted = Color(0xFF5C5C61)
private val Body = Color(0xFF4A4A4F)
private val Tile = Color(0xFFF6F6F9)
private val Red = Color(0xFFC4281C)
private val RedLine = Color(0xFFF2C9C5)
private val WarnInk = Color(0xFF7A3E00)
private val WarnWash = Color(0xFFFFF8EC)
private val WarnLine = Color(0xFFF3D19C)
