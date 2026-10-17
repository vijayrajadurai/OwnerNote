package com.shopai.app.ui.kai

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.note.KaiNoteKind
import com.shopai.app.brain.note.KaiNotes
import com.shopai.app.brain.note.KaiShopScene
import com.shopai.app.brain.note.KaiStockLevel
import com.shopai.app.brain.note.KaiStockRow

private val IosOrange = Color(0xFFFF9500)

/**
 * Kai's stock answer as an iPhone card (owner, 18 Oct 2026: "stock evlo iruku card-um digital-aa super-aa"): a header with how many
 * products and how many are low, then one row per product — its picture, name, out / low / fine, the stock in bold, and a bar of how
 * full it is against the reorder level the owner set (no bar when none is set). The rows slide in and the bars fill, one after another.
 * The numbers are the books' own — the same as Kai's lines.
 */
@Composable
fun KaiStockCard(rows: List<KaiStockRow>, lang: KaiLang, category: String?, modifier: Modifier = Modifier) {
    val scene = KaiShopScene.of(category)
    val shown = remember(rows) { Animatable(0f) }
    LaunchedEffect(rows) { shown.animateTo(rows.size.toFloat() + 1f, tween(durationMillis = 220 * rows.size + 600, easing = FastOutSlowInEasing)) }
    val low = rows.count { it.level == KaiStockLevel.LOW || it.level == KaiStockLevel.OUT }

    Box(modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.08f))
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White),
        ) {
            // Header: the count, and the low ones in red.
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(when (lang) { KaiLang.TAMIL -> "ஸ்டாக்"; else -> "STOCK" }, color = IosGrey, fontSize = 12.sp, letterSpacing = 0.6.sp)
                    Text(
                        "${rows.size} " + when (lang) { KaiLang.TAMIL -> "பொருள்"; KaiLang.TANGLISH -> "products"; KaiLang.ENGLISH -> if (rows.size == 1) "product" else "products" },
                        color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    )
                }
                if (low > 0) Text(
                    "$low " + when (lang) { KaiLang.TAMIL -> "குறைவு"; else -> "low" },
                    color = IosRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(IosRed.copy(alpha = 0.10f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                ) else Text(
                    when (lang) { KaiLang.TAMIL -> "எல்லாம் சரி"; KaiLang.TANGLISH -> "Ellaam OK"; KaiLang.ENGLISH -> "All good" },
                    color = IosGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(IosGreen.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            rows.forEachIndexed { i, row ->
                val t = (shown.value - i).coerceIn(0f, 1f)
                Box(Modifier.padding(start = 62.dp).fillMaxWidth().height(0.5.dp).background(IosSeparator))
                StockRow(row, lang, scene, t)
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun StockRow(row: KaiStockRow, lang: KaiLang, scene: KaiShopScene, t: Float) {
    val tint = when (row.level) {
        KaiStockLevel.OUT -> IosRed
        KaiStockLevel.LOW -> IosOrange
        KaiStockLevel.OK -> IosGreen
        KaiStockLevel.NO_LEVEL -> IosGrey
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).alpha(t).graphicsLayer { translationY = (1f - t) * 8.dp.toPx() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).background(IosGrouped, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Text(KaiNotes.icon(KaiNoteKind.STOCK_IN, row.name, scene), fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, color = IosLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text(row.qty, color = if (row.level == KaiStockLevel.OUT) IosRed else IosLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            }
            Text(com.shopai.app.brain.note.KaiStockView.label(row, lang), color = if (row.level == KaiStockLevel.NO_LEVEL) IosGrey else tint,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.fill?.let { fill ->
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(IosSeparator)) {
                    Box(Modifier.fillMaxWidth((fill * t).coerceIn(0.02f, 1f)).fillMaxHeight().background(tint, RoundedCornerShape(3.dp)))
                }
            }
        }
    }
}
