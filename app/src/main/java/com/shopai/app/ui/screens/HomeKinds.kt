package com.shopai.app.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.books.engine.AgeingBuckets
import com.shopai.app.books.engine.DueSummary
import com.shopai.app.data.model.DailyCashEntryType
import com.shopai.app.data.model.DailyCashPaymentMode
import com.shopai.app.ui.kai.KaiInk
import com.shopai.app.util.HomeKind
import com.shopai.app.util.StockLevel
import com.shopai.app.util.StockWatchRow
import com.shopai.app.util.formatInr
import com.shopai.app.util.formatQty
import com.shopai.app.util.localDateKey
import com.shopai.app.util.salesDeltaPercent
import java.time.LocalDate

/** One day of money in from the Daily Cash Note on this phone. */
data class HomeDayIn(val date: LocalDate, val cash: Double, val upi: Double, val count: Int) {
    val total: Double get() = cash + upi
}

/**
 * What the per-business Homes show (owner, 23 Oct 2026), all read only: the last 7 days of money in from the Daily Cash
 * Note (oldest first, today last), and from the books when they are in use — what customers and suppliers still owe,
 * customer dues by age, and today's sales. A part that cannot be read stays null and the card says "—".
 */
data class HomeFigures(
    val week: List<HomeDayIn>,
    val receivable: DueSummary?,
    val payable: DueSummary?,
    val ageing: AgeingBuckets?,
    val salesTodayPaise: Long?,
) {
    val today: HomeDayIn get() = week.last()
}

suspend fun loadHomeFigures(container: com.shopai.app.data.AppContainer, today: LocalDate = LocalDate.now()): HomeFigures {
    val week = (6 downTo 0).map { back ->
        val day = today.minusDays(back.toLong())
        val ins = runCatching { container.dailyCashRepository.listLocalEntries(localDateKey(day)) }.getOrDefault(emptyList())
            .filter { it.type == DailyCashEntryType.IN }
        HomeDayIn(
            date = day,
            cash = ins.filter { it.paymentMode == DailyCashPaymentMode.CASH }.sumOf { it.amount },
            upi = ins.filter { it.paymentMode == DailyCashPaymentMode.UPI }.sumOf { it.amount },
            count = ins.size,
        )
    }
    val ledger = runCatching { container.books.session()?.ledger }.getOrNull()
    return HomeFigures(
        week = week,
        receivable = ledger?.let { runCatching { it.receivableDue(today) }.getOrNull() },
        payable = ledger?.let { runCatching { it.payableDue(today) }.getOrNull() },
        ageing = ledger?.let { runCatching { it.receivableAgeing(today) }.getOrNull() },
        salesTodayPaise = ledger?.let { runCatching { it.netSales(today, today) }.getOrNull() },
    )
}

/** Short day name for the bars: Thi / Sev / … and "Inru" for today. */
@Composable
private fun dayShort(date: LocalDate, isToday: Boolean): String =
    if (isToday) stringResource(R.string.home_day_today) else stringArrayResource(R.array.home_week_days)[date.dayOfWeek.value - 1]

/**
 * Today's sales from the Daily Cash Note with the details (owner, 23 Oct 2026: "daily cash note-la details varanum"):
 * the total, against yesterday, cash and UPI, and seven bars — tap a bar to see that day. Green for a shop, deep pink for
 * a service business. Opens the Cash Note.
 */
@Composable
fun HomeSalesWeekCard(kind: HomeKind, figures: HomeFigures?, onOpen: () -> Unit, onAsk: () -> Unit) {
    val week = figures?.week
    var picked by remember(week) { mutableIntStateOf(6) }
    val day = week?.getOrNull(picked)
    val before = week?.getOrNull(picked - 1)
    val delta = if (day != null && before != null) salesDeltaPercent(day.total, before.total) else null
    val service = kind == HomeKind.SERVICE
    val today = figures?.today
    val sold = today?.takeIf { it.total > 0.0 }
    val line = if (sold == null) {
        stringResource(R.string.home_sales_empty)
    } else {
        val d = week?.getOrNull(5)?.let { salesDeltaPercent(sold.total, it.total) }
        val head = if (service) stringResource(R.string.home_sales_line_service, sold.count, formatInr(sold.total))
        else stringResource(R.string.home_sales_line, formatInr(sold.total))
        when {
            d == null -> head
            d >= 0 -> head + " " + stringResource(R.string.home_sales_more, d)
            else -> head + " " + stringResource(R.string.home_sales_less, -d)
        }
    }
    val colors = if (service) listOf(Color(0xFFBE185D), Color(0xFF831843), Color(0xFF4A0420)) else listOf(Color(0xFF047857), Color(0xFF064E3B), Color(0xFF052E2B))
    val soft = if (service) Color(0xFFFBCFE8) else KaiInk.Mint
    KaiDiaryCard(
        title = stringResource(R.string.home_sales_title),
        line = line,
        onAsk = onAsk,
        actionLabel = stringResource(R.string.home_cash_note),
        onAction = onOpen,
        tag = if (service) Triple(stringResource(R.string.home_tag_sales), Color(0xFFFCE7F3), Color(0xFFBE185D))
        else Triple(stringResource(R.string.home_tag_sales), Color(0xFFDCFCE7), Color(0xFF15803D)),
        body = {
            Box(Modifier.fillMaxWidth().height(250.dp).background(Brush.linearGradient(colors))) {
                Image(
                    painter = painterResource(R.drawable.kai_mascot_bust),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.BottomEnd).height(206.dp),
                )
                Column(Modifier.padding(16.dp).fillMaxWidth(0.6f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        day?.takeIf { picked != 6 }?.let { dayShort(it.date, false).uppercase() }
                            ?: if (service) stringResource(R.string.home_sales_label_service, today?.count ?: 0) else stringResource(R.string.home_sales_label),
                        color = soft,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                    )
                    Text(day?.let { formatInr(it.total) } ?: "—", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    delta?.let { d ->
                        Text(
                            stringResource(if (d >= 0) R.string.home_delta_up else R.string.home_delta_down, kotlin.math.abs(d)),
                            color = if (d >= 0) Color(0xFF064E3B) else Color(0xFF7F1D1D),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (d >= 0) Color(0xFFA7F3D0) else Color(0xFFFECACA)).padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    day?.let {
                        Text(stringResource(R.string.home_cash_upi, formatInr(it.cash), formatInr(it.upi)), color = soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (week != null) {
                    val top = week.maxOf { it.total }.takeIf { it > 0.0 } ?: 1.0
                    Row(
                        Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 14.dp).fillMaxWidth(0.6f).height(84.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        week.forEachIndexed { i, d ->
                            Column(
                                Modifier.weight(1f).fillMaxHeight().clickable { picked = i },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom,
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight((0.08f + 0.72f * (d.total / top).toFloat()).coerceIn(0.08f, 0.8f))
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(if (i == picked) Color.White else Color.White.copy(alpha = 0.25f)),
                                )
                                Text(
                                    dayShort(d.date, i == 6),
                                    color = if (i == picked) Color.White else soft,
                                    fontSize = 9.5.sp,
                                    fontWeight = if (i == picked) FontWeight.ExtraBold else FontWeight.Medium,
                                    maxLines = 1,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

/**
 * Wholesale: what the shops still owe, by age (on time / 1–30 days late / more than 30 days late), and what the owner
 * owes suppliers. From the books; without them only the total from the dashboard. Opens Customers.
 */
@Composable
fun HomeDueCard(figures: HomeFigures?, fallbackTotal: Double?, onOpen: () -> Unit, onAsk: () -> Unit) {
    val ageing = figures?.ageing
    val onTime = ageing?.currentPaise ?: 0L
    val late30 = ageing?.days1to30Paise ?: 0L
    val lateMore = (ageing?.days31to60Paise ?: 0L) + (ageing?.days61to90Paise ?: 0L) + (ageing?.over90Paise ?: 0L)
    val totalPaise = figures?.receivable?.totalPaise ?: ageing?.let { onTime + late30 + lateMore }
    val total = totalPaise?.let { it / 100.0 } ?: fallbackTotal
    val totalText = total?.let { formatInr(it) } ?: "—"
    KaiDiaryCard(
        title = stringResource(R.string.home_due_title),
        line = if (lateMore > 0) stringResource(R.string.home_due_line_late, totalText, formatInr(lateMore / 100.0)) else stringResource(R.string.home_due_line, totalText),
        onAsk = onAsk,
        actionLabel = stringResource(R.string.home_due_cta),
        onAction = onOpen,
        tag = Triple(stringResource(R.string.home_tag_due), Color(0xFFDBEAFE), Color(0xFF1D4ED8)),
        body = {
            Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF1E3A8A), Color(0xFF172554), Color(0xFF0B1530)))).clickable(onClick = onOpen)) {
                Box(Modifier.fillMaxWidth().height(150.dp)) {
                    Image(
                        painter = painterResource(R.drawable.kai_mascot_bust),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.align(Alignment.BottomEnd).height(150.dp),
                    )
                    Column(Modifier.padding(16.dp).fillMaxWidth(0.62f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.home_due_label), color = Color(0xFFBFDBFE), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, maxLines = 1)
                        Text(totalText, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        figures?.payable?.let {
                            Text(stringResource(R.string.home_due_give, formatInr(it.totalPaise / 100.0)), color = Color(0xFFBFDBFE), fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
                if (ageing != null && onTime + late30 + lateMore > 0) {
                    Column(Modifier.background(Color(0x33000000)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            listOf(onTime to Color(0xFF10B981), late30 to Color(0xFFF59E0B), lateMore to Color(0xFFEF4444)).filter { it.first > 0 }.forEach { (v, c) ->
                                Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(c))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DueBox(stringResource(R.string.home_due_ontime), onTime, Color(0xFF10B981), Modifier.weight(1f))
                            DueBox(stringResource(R.string.home_due_1to30), late30, Color(0xFFF59E0B), Modifier.weight(1f))
                            DueBox(stringResource(R.string.home_due_30plus), lateMore, Color(0xFFEF4444), Modifier.weight(1f))
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun DueBox(label: String, paise: Long, dot: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Text(label, color = Color(0xFFCBD5E1), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatInr(paise / 100.0), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/**
 * Retail and wholesale: the products running short, emptiest first, each with a bar (red at or under its minimum, amber
 * close to it, green with a little room). The send button opens WhatsApp with the order list typed in — the owner picks
 * the supplier and presses send. "Order list" opens Stock. Read only.
 */
@Composable
fun HomeStockCard(rows: List<StockWatchRow>, onOpen: () -> Unit, onSend: () -> Unit, onAsk: () -> Unit) {
    val low = rows.count { it.level == StockLevel.LOW }
    KaiDiaryCard(
        title = stringResource(R.string.home_stock_title),
        line = if (low > 0) stringResource(R.string.home_stock_line, low) else stringResource(R.string.home_stock_near),
        onAsk = onAsk,
        actionLabel = stringResource(R.string.home_stock_cta),
        onAction = onOpen,
        tag = Triple(stringResource(R.string.home_tag_stock), Color(0xFFFEE2E2), Color(0xFFB91C1C)),
        onSend = onSend,
        body = {
            Column(
                Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFFFFF1F2), Color(0xFFFFE4E6)))).clickable(onClick = onOpen).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.home_stock_media), color = KaiInk.Ink, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.92f)).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rows.forEach { r ->
                        val ink = when (r.level) {
                            StockLevel.LOW -> Color(0xFFDC2626)
                            StockLevel.NEAR -> Color(0xFFD97706)
                            StockLevel.OK -> Color(0xFF059669)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.product.name, color = KaiInk.Ink, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Text("${formatQty(r.product.currentStock)} ${r.product.unit}", color = ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            }
                            Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFCE7EA))) {
                                Box(Modifier.fillMaxWidth(r.fill).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(ink))
                            }
                        }
                    }
                }
            }
        },
    )
}

/** The order list the send button types into WhatsApp: the shop's name, then each short product and what is left. */
fun homeOrderListText(context: android.content.Context, shop: String, rows: List<StockWatchRow>): String =
    context.getString(R.string.home_order_title, shop) + "\n" +
        rows.joinToString("\n") { context.getString(R.string.home_order_line, it.product.name, "${formatQty(it.product.currentStock)} ${it.product.unit}") }
