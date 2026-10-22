package com.shopai.app.ui.kai

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.chat.KaiAction
import com.shopai.app.brain.chat.KaiButton
import com.shopai.app.brain.chat.KaiCard
import com.shopai.app.brain.note.KaiAskPad
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
 * Kai asking the one thing he needs — made for a shop owner's thumb (owner, 22 Oct 2026: "question card innum user friendly"):
 * Kai's face and "Kekkuraar · 2/3"; the question short and big ("Kumar ₹2,000 — enna aachu?") with what the books say first;
 * what the owner already said as chips; each likely answer a big tile that says what it does ("Kumar tharanum ₹1,000 → ₹3,000");
 * a number asked (a rate, a pack size) gets its own number pad — with the profit shown while the selling rate is typed and three
 * rates to tap; then a big mic and "Venaam". Answered, the card folds to one "✓" line. Every tap is a line to Kai like typing it
 * (the pad sends "60"), so typed and spoken stay one Kai; nothing is saved before Confirm.
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
    /** The number pad's answer, sent as the owner's own line (null hides the pad — an older question). */
    onAnswer: ((String) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(22.dp)
    val headline = card?.headline ?: question

    // Answered (or an older question): one quiet line, so the chat stays clean.
    if (closed) {
        Box(modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.CenterStart) {
            Row(
                Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
                    .border(1.dp, IosSeparator, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = AskGreen, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(headline, color = AskMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }

    Box(modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f))
                .clip(shape)
                .background(Color.White)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Kai and "Kekkuraar · 2/3".
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFFFF1D6)), contentAlignment = Alignment.TopCenter) {
                    Image(painterResource(R.drawable.kai_curious), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    when (lang) { KaiLang.TAMIL -> "Kai கேக்குறார்"; KaiLang.TANGLISH -> "Kai kekkuraar"; KaiLang.ENGLISH -> "Kai is asking" } +
                        (progress?.takeIf { it.steps > 1 }?.let { " · ${it.step} / ${it.steps}" } ?: ""),
                    color = AskMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }

            // What the owner already said, then the one thing asked now.
            progress?.let { p ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.known.forEach { (label, value) -> KnownChip(label, value) }
                    Text(p.asking, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(AskInk).padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }

            // The question, short and big; the books' figure first.
            Text(headline, color = IosLabel, fontWeight = FontWeight.Bold, fontSize = if (headline.length <= 60) 20.sp else 17.sp,
                lineHeight = if (headline.length <= 60) 26.sp else 23.sp)
            card?.context?.let { c ->
                Text(c, color = AskBody, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(AskTile).padding(horizontal = 10.dp, vertical = 8.dp))
            }
            if (progress == null) card?.lines?.takeIf { it.isNotEmpty() }?.let { lines ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AskTile)
                    .padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    lines.forEach { line -> Text(line, color = IosLabel, fontSize = 14.sp, lineHeight = 19.sp) }
                }
            }

            // A number asked: the number pad, the profit as it is typed, three rates to tap.
            if (progress?.number == true && onAnswer != null) NumberPad(progress, lang, onAnswer)

            // Kai's likely answers, each a big tile that says what it does.
            val buttons = card?.buttons.orEmpty()
            val answers = buttons.filterNot { isCancel(it.action) }
            if (answers.isNotEmpty()) {
                if (answers.size >= 4 && answers.none { it.hint != null }) {
                    // Many short answers ("Balance / Statement / Payment / Reminder"): two to a row.
                    answers.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { b -> AnswerTile(b, small = true, modifier = Modifier.weight(1f), onTap = onTap) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else answers.forEach { b -> AnswerTile(b, small = false, modifier = Modifier.fillMaxWidth(), onTap = onTap) }
            }

            // The big mic and "Venaam".
            val cancels = buttons.filter { isCancel(it.action) }
            if (onMic != null || cancels.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onMic != null) Row(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(AskGreen).clickable { onMic() },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(when (lang) { KaiLang.TAMIL -> "வாயால சொல்லுங்க"; KaiLang.TANGLISH -> "Vaayaala sollunga"; KaiLang.ENGLISH -> "Say it" },
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                cancels.forEach { b ->
                    Box(
                        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).border(1.5.dp, AskRedLine, RoundedCornerShape(24.dp))
                            .clickable(enabled = b.enabled) { onTap(b.action) }.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(b.label, color = if (b.enabled) AskRed else IosGrey, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                }
            }
            if (answers.isNotEmpty() || progress != null) Text(
                when (lang) {
                    KaiLang.TAMIL -> "Confirm அமுக்குற வரைக்கும் எதுவும் save ஆகாது"
                    KaiLang.TANGLISH -> "Confirm amukkura varaikkum edhuvum save aagadhu"
                    KaiLang.ENGLISH -> "Nothing is saved until you press Confirm"
                },
                color = AskMuted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One likely answer: its icon, its label and what it does ("Kumar tharanum ₹1,000 → ₹3,000"), the whole tile tappable. */
@Composable
private fun AnswerTile(b: KaiButton, small: Boolean, modifier: Modifier, onTap: (KaiAction) -> Unit) {
    val (icon, tint, wash) = tileIcon(b)
    val tileShape = RoundedCornerShape(14.dp)
    val base = modifier.heightIn(min = if (small) 76.dp else 60.dp).clip(tileShape)
        .background(if (b.primary) AskInk else Color.White)
        .border(1.5.dp, if (b.primary) AskInk else AskLine, tileShape)
        .clickable(enabled = b.enabled) { onTap(b.action) }
        .alpha(if (b.enabled) 1f else 0.4f)
        .padding(horizontal = 12.dp, vertical = 10.dp)
    if (small) Column(base, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(wash), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Text(b.label, color = if (b.primary) Color.White else IosLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    } else Row(base, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(wash), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(b.label, color = if (b.primary) Color.White else IosLabel, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            b.hint?.let { Text(it, color = if (b.primary) Color.White.copy(alpha = 0.85f) else AskBody, fontSize = 13.sp, maxLines = 2) }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = IosGrey, modifier = Modifier.size(20.dp))
    }
}

/** A tile's icon from what the answer does (money in / out / owed, balance, statement, stock, rate, profit…); a plain arrow else. */
private fun tileIcon(b: KaiButton): Triple<ImageVector, Color, Color> {
    val t = (b.label + " " + b.hint.orEmpty() + " " + ((b.action as? KaiAction.Say)?.line ?: "")).lowercase()
    fun has(vararg w: String) = w.any { t.contains(it) }
    return when {
        has("payment in", "vandhuchu", "came in", "வந்துச்சு", "kuduthaar", "கொடுத்தார்") -> Triple(Icons.Filled.ArrowDownward, AskBlue, AskBlueWash)
        has("payment out", "pochu", "போச்சு", "kuduthen", "கொடுத்தேன்", "i paid") -> Triple(Icons.Filled.ArrowUpward, AskPurple, AskPurpleWash)
        has("tharanum", "தரணும்", "kadan", "கடன்", "credit", "debit", "owe") -> Triple(Icons.AutoMirrored.Filled.MenuBook, AskOrange, AskOrangeWash)
        has("balance", "evlo tharanum") -> Triple(Icons.Filled.AccountBalanceWallet, AskBlue, AskBlueWash)
        has("statement", "pdf") -> Triple(Icons.Filled.Description, AskPurple, AskPurpleWash)
        has("remind") -> Triple(Icons.Filled.NotificationsActive, AskOrange, AskOrangeWash)
        has("profit", "labam", "லாபம்") -> Triple(Icons.AutoMirrored.Filled.TrendingUp, AskGreen, AskGreenWash)
        has("rate", "price", "vilai", "விலை") -> Triple(Icons.Filled.Sell, AskOrange, AskOrangeWash)
        has("stock", "vithuchu", "வித்துச்சு") -> Triple(Icons.Filled.Inventory2, AskGreen, AskGreenWash)
        has("report", "sales", "purchase", "selavu", "expense") -> Triple(Icons.Filled.BarChart, AskBlue, AskBlueWash)
        has("payment", "entry", "add") -> Triple(Icons.Filled.Add, AskGreen, AskGreenWash)
        else -> Triple(Icons.AutoMirrored.Filled.KeyboardArrowRight, AskBody, AskTile)
    }
}

/**
 * The number asked, on its own pad (no phone keyboard): the value big, the profit while the selling rate is typed ("Oru piece-ku
 * labam ₹10 · 100 pcs-ku ₹1,000", a loss in red), three rates to tap, the keys, and "Adutha →" — which sends the number as the
 * owner's line.
 */
@Composable
private fun NumberPad(p: KaiAskProgress, lang: KaiLang, onAnswer: (String) -> Unit) {
    var value by remember(p) { mutableStateOf("") }
    val money = p.money
    val profit = KaiAskPad.profit(value, p.cost, p.count, p.countLabel, p.per, lang)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (money) Text("₹ ", color = AskMuted, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(value.ifEmpty { "0" }, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold,
                    color = when { value.isEmpty() -> IosGrey; profit?.loss == true -> AskRed; else -> IosLabel })
            }
            profit?.let { pr ->
                Text(pr.text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = if (pr.loss) AskRed else AskGreenDark,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (pr.loss) AskRedWash else AskGreenWash).padding(horizontal = 12.dp, vertical = 6.dp))
            } ?: p.cost?.let { c ->
                Text(when (lang) {
                    KaiLang.TAMIL -> "வாங்கின விலை ${KaiFormat.rupees(c.toDouble())} — அதை விட கொஞ்சம் கூட வைங்க"
                    KaiLang.TANGLISH -> "Vaangina rate ${KaiFormat.rupees(c.toDouble())} — adha vida konjam kooda vainga"
                    KaiLang.ENGLISH -> "Bought at ${KaiFormat.rupees(c.toDouble())} — a little above it"
                }, fontSize = 13.sp, color = AskMuted, textAlign = TextAlign.Center)
            }
        }
        val quick = KaiAskPad.quickRates(p.cost)
        if (quick.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            quick.forEach { r ->
                val picked = KaiAskPad.amount(value)?.compareTo(r) == 0
                Column(
                    Modifier.weight(1f).heightIn(min = 54.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (picked) AskBlueWash else Color.White)
                        .border(if (picked) 2.dp else 1.5.dp, if (picked) AskBlue else AskLine, RoundedCornerShape(12.dp))
                        .clickable { value = r.toPlainString() }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(KaiFormat.rupees(r.toDouble()), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = IosLabel)
                    Text(when (lang) { KaiLang.TAMIL -> "லாபம் "; KaiLang.TANGLISH -> "labam "; KaiLang.ENGLISH -> "profit " } + KaiAskPad.gain(r, p.cost!!),
                        fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = AskGreenDark)
                }
            }
        }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "del")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).background(AskTile)
                            .clickable { value = KaiAskPad.press(value, k) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == "del") Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete", tint = IosLabel, modifier = Modifier.size(22.dp))
                        else Text(k, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = IosLabel)
                    }
                }
            }
        }
        val ready = KaiAskPad.amount(value) != null
        Box(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(if (ready) AskInk else IosSeparator)
                .clickable(enabled = ready) { onAnswer(value.trimEnd('.')) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (ready) when (lang) { KaiLang.TAMIL -> "அடுத்து  →"; KaiLang.TANGLISH -> "Adutha  →"; KaiLang.ENGLISH -> "Next  →" }
                else when (lang) { KaiLang.TAMIL -> "எண் போடுங்க"; KaiLang.TANGLISH -> "Number podunga"; KaiLang.ENGLISH -> "Type the number" },
                color = if (ready) Color.White else AskMuted, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}

private val AskGreen = Color(0xFF0E7F59)
private val AskGreenDark = Color(0xFF0A6B4B)
private val AskGreenWash = Color(0xFFE3F5EC)
private val AskInk = Color(0xFF1A1530)
private val AskMuted = Color(0xFF5C5C61)
private val AskBody = Color(0xFF4A4A4F)
private val AskTile = Color(0xFFF6F6F9)
private val AskLine = Color(0xFFD9D9DE)
private val AskRed = Color(0xFFC4281C)
private val AskRedLine = Color(0xFFF2C9C5)
private val AskRedWash = Color(0xFFFDECEA)
private val AskBlue = Color(0xFF0A5DB8)
private val AskBlueWash = Color(0xFFE3F1FF)
private val AskPurple = Color(0xFF4B3A8C)
private val AskPurpleWash = Color(0xFFEEEAF7)
private val AskOrange = Color(0xFFA35200)
private val AskOrangeWash = Color(0xFFFFF0DD)

/** One thing the owner already said: "Product **Noodles**". */
@Composable
private fun KnownChip(label: String, value: String) {
    Text(
        buildAnnotatedString {
            append("$label ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = IosLabel)) { append(value) }
        },
        color = AskBody, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(AskGreenWash).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
