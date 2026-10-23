package com.shopai.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.ui.kai.KaiFace
import com.shopai.app.ui.kai.KaiInk
import com.shopai.app.ui.kai.VeshtiBand
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Top of Home: "Vanakkam, <owner>", today's date with the shop, the bell and the owner's photo. */
@Composable
fun OwnerNoteHomeHeader(
    ownerName: String,
    shopName: String,
    unreadCount: Int,
    photoPath: String?,
    onBell: () -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())) }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                // "Vanakkam Owner" — OwnerNote's own greeting (owner, 22 Oct 2026).
                text = stringResource(R.string.kai_greeting),
                color = KaiInk.Ink,
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (shopName.isBlank()) today else "$today · $shopName",
                color = KaiInk.InkSoft,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(42.dp)
                .shadow(4.dp, RoundedCornerShape(14.dp), ambientColor = Color(0x0F1C1917), spotColor = Color(0x0F1C1917))
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .clickable(onClick = onBell),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Notifications, contentDescription = stringResource(R.string.home_bell), tint = KaiInk.Ink, modifier = Modifier.size(21.dp))
            if (unreadCount > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 7.dp, end = 7.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFDC2626))
                        .border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (unreadCount > 9) "9+" else unreadCount.toString(), color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        val photo = remember(photoPath) {
            photoPath?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() }
        }
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(KaiInk.Green, KaiInk.Gold)))
                .padding(2.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(KaiInk.Ink)
                .clickable(onClick = onProfile),
            contentAlignment = Alignment.Center,
        ) {
            if (photo != null) {
                Image(photo, contentDescription = stringResource(R.string.home_profile), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    text = ownerName.trim().take(1).uppercase().ifBlank { "O" },
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}

/** One of the square "nyabagam" tiles across the top of Home. */
data class HomeMoment(
    val label: String,
    val value: String?,
    val colors: List<Color>,
    val badge: String? = null,
    val kai: Boolean = false,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val onClick: () -> Unit,
)

@Composable
fun HomeMomentsRow(moments: List<HomeMoment>, modifier: Modifier = Modifier, edge: androidx.compose.ui.unit.Dp = 16.dp) {
    var seen by remember { mutableStateOf(setOf<String>()) }
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = edge, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        moments.forEach { m ->
            val isSeen = m.label in seen
            Column(
                Modifier
                    .width(70.dp)
                    .clickable {
                        seen = seen + m.label
                        m.onClick()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(66.dp)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                if (isSeen) Brush.linearGradient(listOf(KaiInk.Line, KaiInk.Line))
                                else Brush.linearGradient(listOf(KaiInk.Green, KaiInk.Gold, KaiInk.GreenDeep)),
                            )
                            .padding(if (isSeen) 2.dp else 3.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .background(KaiInk.Paper)
                            .padding(2.5.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(Brush.linearGradient(m.colors)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (m.kai) {
                            Image(
                                painter = androidx.compose.ui.res.painterResource(R.drawable.kai_mascot_face),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else if (m.icon != null) {
                            Icon(m.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                        } else {
                            Text(
                                text = m.value.orEmpty(),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                            )
                        }
                    }
                    m.badge?.let { b ->
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(0xFFDC2626))
                                .border(2.dp, KaiInk.Paper, RoundedCornerShape(9.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(b, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        }
                    }
                }
                Text(
                    text = m.label,
                    color = if (isSeen) Color(0xFFA8A29E) else KaiInk.Ink,
                    fontSize = 11.5.sp,
                    fontWeight = if (isSeen) FontWeight.Medium else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Home's tiles from what Home already loads: Kai, today's cash in (Daily Cash Note), reminders and,
 * for a shop with stock, Stock. A figure not loaded yet shows "—", never a made-up number.
 */
@Composable
fun homeMoments(
    todayIn: Double?,
    reminderCount: Int,
    urgentReminders: Int,
    stock: Boolean,
    onNavigate: (String) -> Unit,
): List<HomeMoment> {
    val dash = "—"
    fun money(v: Double?) = v?.let { com.shopai.app.util.shortRupees(it) } ?: dash
    return buildList {
        add(HomeMoment(stringResource(R.string.home_tile_kai), null, listOf(Color(0xFFECFDF5), Color(0xFFD1FAE5)), kai = true) { onNavigate(com.shopai.app.ui.navigation.Routes.KaiChat) })
        add(HomeMoment(stringResource(R.string.home_tile_today), money(todayIn), listOf(Color(0xFF10B981), Color(0xFF047857))) { onNavigate(com.shopai.app.ui.navigation.Routes.DailyCashNote) })
        add(
            HomeMoment(
                stringResource(R.string.home_tile_reminders),
                reminderCount.toString(),
                listOf(Color(0xFFA78BFA), Color(0xFF6D28D9)),
                badge = urgentReminders.takeIf { it > 0 }?.toString(),
            ) { onNavigate(com.shopai.app.ui.navigation.Routes.Reminders) },
        )
        if (stock) add(HomeMoment(stringResource(R.string.home_tile_stock), null, listOf(Color(0xFFF87171), Color(0xFFB91C1C)), icon = androidx.compose.material.icons.Icons.Outlined.Inventory2) { onNavigate(com.shopai.app.ui.navigation.Routes.Inventory) })
    }
}

/**
 * Vaanganum / Kodukkanum, small, in one row where the diary heading was (owner, 22 Oct 2026). The same figures as the
 * old summary card: from the books when they are in use, else the dashboard's pending totals. Tapping opens Customers /
 * Suppliers. Read only.
 */
@Composable
fun HomeMoneyChips(
    container: com.shopai.app.data.AppContainer,
    refreshKey: Any?,
    fallbackToGet: Double?,
    fallbackToGive: Double?,
    onCustomers: () -> Unit,
    onSuppliers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var get by remember { mutableStateOf<com.shopai.app.books.engine.DueSummary?>(null) }
    var give by remember { mutableStateOf<com.shopai.app.books.engine.DueSummary?>(null) }
    androidx.compose.runtime.LaunchedEffect(refreshKey) {
        val s = container.books.session() ?: return@LaunchedEffect
        val today = LocalDate.now()
        get = s.ledger.receivableDue(today)
        give = s.ledger.payableDue(today)
    }
    val getText = get?.let { com.shopai.app.util.formatInr(it.totalPaise / 100.0) } ?: fallbackToGet?.let { com.shopai.app.util.formatInr(it) } ?: "—"
    val giveText = give?.let { com.shopai.app.util.formatInr(it.totalPaise / 100.0) } ?: fallbackToGive?.let { com.shopai.app.util.formatInr(it) } ?: "—"
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MoneyChip(stringResource(R.string.home_receivable), getText, get?.overdueCount ?: 0, true, Modifier.weight(1f), onCustomers)
        MoneyChip(stringResource(R.string.home_payable), giveText, give?.overdueCount ?: 0, false, Modifier.weight(1f), onSuppliers)
    }
}

@Composable
private fun MoneyChip(label: String, amount: String, late: Int, incoming: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fg = if (incoming) Color(0xFF15803D) else Color(0xFFB91C1C)
    val bg = if (incoming) Color(0xFFDCFCE7) else Color(0xFFFEE2E2)
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .height(52.dp)
            .shadow(4.dp, shape, ambientColor = Color(0x0F0F172A), spotColor = Color(0x0F0F172A))
            .clip(shape)
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(bg), contentAlignment = Alignment.Center) {
            Text(if (incoming) "↓" else "↑", color = fg, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = if (late > 0) "$label · " + stringResource(R.string.home_late_count, late) else label,
                color = if (late > 0) Color(0xFFB45309) else KaiInk.InkSoft,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(amount, color = fg, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A Kai diary card: Kai's face and "Kai solraar", what Kai says, an optional body, then
 * "Seri, paathen" (only marks it seen on this screen), Kai-kitta kelunga, and one action.
 */
@Composable
fun KaiDiaryCard(
    title: String,
    line: String,
    onAsk: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    body: (@Composable () -> Unit)? = null,
) {
    var seen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(10.dp, shape, ambientColor = Color(0x120F172A), spotColor = Color(0x120F172A))
            .clip(shape)
            .background(Color.White),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KaiFace(size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(title, color = KaiInk.Ink, fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text(stringResource(R.string.home_kai_says), color = KaiInk.InkSoft, fontSize = 12.sp, maxLines = 1)
            }
        }
        Text(
            text = line,
            color = KaiInk.Ink,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
        body?.let {
            Box(Modifier.padding(horizontal = 10.dp).clip(RoundedCornerShape(20.dp))) { it() }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (seen) Color(0xFFDCFCE7) else Color.White)
                    .border(1.5.dp, if (seen) Color(0xFF86EFAC) else KaiInk.Line, RoundedCornerShape(14.dp))
                    .clickable { seen = !seen }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(if (seen) R.string.home_seen_done else R.string.home_seen),
                    color = if (seen) Color(0xFF15803D) else Color(0xFF44403C),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                )
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFF5F3EE)).clickable(onClick = onAsk),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = stringResource(R.string.home_ask_kai), tint = KaiInk.Ink, modifier = Modifier.size(19.dp))
            }
            Box(Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                Box(
                    Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(KaiInk.Ink)
                        .clickable(onClick = onAction)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$actionLabel ›", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }
            }
        }
        VeshtiBand()
    }
}

/** Today's sales from the Daily Cash Note, as a Kai diary card with Kai beside the figure; opens the Cash Note. */
@Composable
fun HomeSalesCard(summary: com.shopai.app.data.model.TodayCashSummary?, onOpen: () -> Unit, onAsk: () -> Unit) {
    val totalIn = summary?.totalIn ?: 0.0
    val totalOut = summary?.totalOut ?: 0.0
    KaiDiaryCard(
        title = stringResource(R.string.home_sales_title),
        line = if (totalIn > 0) stringResource(R.string.home_sales_line, com.shopai.app.util.formatInr(totalIn)) else stringResource(R.string.home_sales_empty),
        onAsk = onAsk,
        actionLabel = stringResource(R.string.home_cash_note),
        onAction = onOpen,
        body = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(Brush.linearGradient(listOf(Color(0xFF047857), Color(0xFF064E3B), Color(0xFF052E2B))))
                    .clickable(onClick = onOpen),
            ) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.kai_mascot_bust),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.BottomEnd).height(140.dp),
                )
                Column(Modifier.padding(16.dp).fillMaxWidth(0.62f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.home_tile_today).uppercase(), color = KaiInk.Mint, fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                    Text(com.shopai.app.util.formatInr(totalIn), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Text(
                        stringResource(R.string.home_in_out, com.shopai.app.util.formatInr(totalIn), com.shopai.app.util.formatInr(totalOut)),
                        color = KaiInk.Mint,
                        fontSize = 12.sp,
                        maxLines = 2,
                    )
                }
            }
        },
    )
}

/** Payments due (the reminders Home already reads): who, how much, when — as a Kai diary card. */
@Composable
fun HomeVasoolCard(
    items: List<com.shopai.app.ui.reminders.PaymentReminder>,
    total: Int,
    onOpen: (String) -> Unit,
    onAll: () -> Unit,
    onAsk: () -> Unit,
) {
    KaiDiaryCard(
        title = stringResource(R.string.home_vasool_title),
        line = stringResource(R.string.home_vasool_line, total),
        onAsk = onAsk,
        actionLabel = stringResource(R.string.home_see),
        onAction = onAll,
        body = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(Color(0xFFFFF7ED), Color(0xFFFEF3C7))))
                    .padding(12.dp),
            ) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.9f)).padding(horizontal = 12.dp)) {
                    items.forEachIndexed { i, r ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF1F1F1)))
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val tone = listOf(Color(0xFFFEE2E2) to Color(0xFFB91C1C), Color(0xFFFEF3C7) to Color(0xFF92400E), Color(0xFFDBEAFE) to Color(0xFF1D4ED8))[i % 3]
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tone.first), contentAlignment = Alignment.Center) {
                                Text(r.partyName.trim().take(1).uppercase().ifBlank { "•" }, color = tone.second, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(r.partyName.ifBlank { r.description }, color = KaiInk.Ink, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    com.shopai.app.util.formatDisplayDate(r.dueDateIso),
                                    color = if (r.urgency == com.shopai.app.ui.reminders.ReminderUrgency.OVERDUE) Color(0xFFB91C1C) else Color(0xFFB45309),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                            }
                            r.amount?.let { Text(com.shopai.app.util.formatInr(it), color = KaiInk.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) }
                        }
                    }
                }
            }
        },
    )
}

/** Kai's Morning Work count as a diary card; opens the guided work. Hidden until the count is known. */
@Composable
fun HomeWorkCard(count: Int?, onOpen: () -> Unit, onAsk: () -> Unit) {
    count ?: return
    KaiDiaryCard(
        title = stringResource(R.string.home_work_title),
        line = if (count > 0) stringResource(R.string.home_work_line, count) else stringResource(R.string.home_work_none),
        onAsk = onAsk,
        actionLabel = stringResource(R.string.home_see),
        onAction = onOpen,
    )
}

/** One quick action under the diary: a coloured icon and its name. */
data class HomeAction(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val from: Color,
    val to: Color,
    val onClick: () -> Unit,
)

/** The quick actions (the same places Quick Links opened), four to a row, white tiles with coloured icons. */
@Composable
fun HomeActionGrid(actions: List<HomeAction>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { a ->
                    Column(
                        Modifier
                            .weight(1f)
                            .shadow(4.dp, RoundedCornerShape(18.dp), ambientColor = Color(0x0D0F172A), spotColor = Color(0x0D0F172A))
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White)
                            .clickable(onClick = a.onClick)
                            .padding(top = 10.dp, bottom = 8.dp, start = 4.dp, end = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(a.from, a.to))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(a.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                        Text(
                            a.label,
                            color = Color(0xFF334155),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            lineHeight = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}
