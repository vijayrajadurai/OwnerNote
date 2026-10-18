package com.shopai.app.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.brain.home.DueWhen
import com.shopai.app.brain.home.HomeMoney
import com.shopai.app.brain.home.HomeParty
import com.shopai.app.brain.home.HomeSide
import com.shopai.app.util.formatInr
import com.shopai.app.util.whatsappNumber

/** The "Kadai Bold" home (owner, 18 Oct 2026): one colour per meaning, chunky tiles, the soft moving background behind. */
object HomeColors {
    val Ink = Color(0xFF1A1530)
    val Soft = Color(0xFF6B6580)
    val Leaf = Color(0xFF0E9F6E)
    val LeafDark = Color(0xFF0A7A54)
    val Kum = Color(0xFFE23D28)
    val KumDark = Color(0xFFB82A19)
    val Turmeric = Color(0xFFF6B300)
    val Peacock = Color(0xFF1463FF)
    val LateTint = Color(0xFFFFB4A8)
    val TodayTint = Color(0xFFFDE68A)
    val Line = Color(0xFFF2F0F6)
    val GreenTop = Color(0xFF19B97F)
    val GreenBottom = Color(0xFF0B8A5C)
    val InkTop = Color(0xFF2E2753)
}

private val TileShape = RoundedCornerShape(24.dp)
private val CardShape = RoundedCornerShape(22.dp)

/** A tile's chunky look: its colour, a shadow of the same family, and the soft light circle in its corner. */
private fun Modifier.tile(color: Color, shape: RoundedCornerShape = TileShape): Modifier =
    shadow(8.dp, shape, ambientColor = color, spotColor = color).clip(shape).background(color)

private fun Modifier.whiteCard(shape: RoundedCornerShape = CardShape): Modifier =
    shadow(6.dp, shape, ambientColor = HomeColors.Ink.copy(alpha = 0.25f), spotColor = HomeColors.Ink.copy(alpha = 0.25f))
        .clip(shape).background(Color.White)

/**
 * The soft moving colour behind the home (green, blue, peach, pink): drawn as radial light, so it looks the same on every
 * Android version (no blur needed), and moves slowly.
 */
@Composable
fun HomeBlobBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "home-blobs")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(12_000), RepeatMode.Reverse), label = "a")
    Canvas(modifier.background(Color(0xFFEEF0F4))) {
        val w = size.width
        val h = size.height
        fun blob(color: Color, cx: Float, cy: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.75f), color.copy(alpha = 0f)), center = Offset(cx, cy), radius = r),
                radius = r, center = Offset(cx, cy))
        val d = 30f * a
        blob(Color(0xFF58D68D), w * 0.05f + d, h * 0.20f - d, w * 0.75f)
        blob(Color(0xFF7FB3FF), w * 1.0f - d, h * 0.48f + d, w * 0.70f)
        blob(Color(0xFFFFC77A), w * 0.35f + d, h * 1.0f - d, w * 0.70f)
        blob(Color(0xFFFF9FB6), w * 0.90f - d, h * 0.02f + d, w * 0.55f)
    }
}

/** Kai's face, the shop's name and the day; the bell with how many reminders wait. */
@Composable
fun HomeHeader(shopName: String, day: String, unread: Int, onBell: () -> Unit, onProfile: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(HomeColors.GreenTop, HomeColors.GreenBottom))).clickable { onProfile() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Storefront, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(shopName, color = HomeColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(day, color = Color(0xFF3B3550), fontSize = 12.sp)
        }
        Box {
            SquareButton(Icons.Rounded.Notifications, HomeColors.Ink, onBell)
            if (unread > 0) Box(
                Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(18.dp).border(2.dp, Color.White, CircleShape)
                    .background(HomeColors.Kum, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(if (unread > 9) "9+" else "$unread", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black) }
        }
    }
}

/** A white square button with an icon (back, bell, search…). */
@Composable
fun SquareButton(icon: ImageVector, tint: Color, onClick: () -> Unit, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).whiteCard(RoundedCornerShape(13.dp)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp)) }
}

/**
 * Kai standing, his whole body, beside "Vanakkam, Owner" and what he says today (owner, 18 Oct 2026: "Kai chat-la Kai eppadi
 * irukkaano adha maadhiri"). The same live Kai as in Kai chat: he greets, his mouth moves while he speaks the brief, then he rests.
 * Tapping opens Kai chat.
 */
@Composable
fun KaiHero(
    title: String,
    line: String?,
    state: com.shopai.app.ui.kai.KaiState,
    mouthLevel: Float,
    speakingAs: com.shopai.app.ui.kai.KaiState?,
    askLabel: String,
    onAsk: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp).whiteCard(RoundedCornerShape(28.dp)).clickable { onAsk() }
            .padding(start = 4.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.shopai.app.ui.kai.KaiCharacter(state = state, mouthLevel = mouthLevel, speakingAs = speakingAs, size = 128.dp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = HomeColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.Black, lineHeight = 26.sp)
            if (!line.isNullOrBlank()) Text(
                line, color = Color(0xFF2A2440), fontSize = 13.5.sp, lineHeight = 19.sp, maxLines = 5, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp))
                    .background(Color(0xFFF4F2F8)).padding(horizontal = 11.dp, vertical = 9.dp),
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(askLabel, color = HomeColors.LeafDark, fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold)
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = HomeColors.LeafDark, modifier = Modifier.size(15.dp))
            }
        }
    }
}

/**
 * Vaanganum (green) or Kudukkanum (red), small (owner, 18 Oct 2026: "green and red box romba perusa irukku"): the total, how many
 * and how much is late, and a thin late / today bar. Tapping opens the full list ([MoneySheet]).
 */
@Composable
fun MoneyTile(title: String, people: String, side: HomeSide, toShop: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (toShop) HomeColors.Leaf else HomeColors.Kum
    val late = if (toShop) HomeColors.LateTint else Color(0xFFFFE4E0)
    Column(modifier.tile(color, RoundedCornerShape(20.dp)).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (toShop) Icons.Rounded.SouthWest else Icons.Rounded.NorthEast, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            if (side.count > 0) Text(
                "${side.count}", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Black, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.22f)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Text(formatInr(side.total), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black, maxLines = 1,
            modifier = Modifier.padding(top = 2.dp))
        // How much is late (the people are counted in the badge above); no late → how many people.
        Text(
            if (side.late > 0) stringOf(R.string.home_late) + " " + formatInr(side.late) else people,
            color = Color.White.copy(alpha = 0.92f), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        SplitBar(listOf(side.late to late, side.today to HomeColors.TodayTint, side.later to Color.White), Color.White.copy(alpha = 0.25f),
            Modifier.padding(top = 7.dp), 5.dp)
    }
}

@Composable
private fun stringOf(id: Int): String = androidx.compose.ui.res.stringResource(id)

/** A thin bar split by amount (each part its colour), the rest the track. */
@Composable
fun SplitBar(parts: List<Pair<Double, Color>>, track: Color, modifier: Modifier = Modifier, height: Dp = 7.dp) {
    val total = parts.sumOf { it.first }
    Row(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(4.dp)).background(track), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (total > 0) parts.filter { it.first > 0 }.forEach { (v, c) -> Box(Modifier.weight((v / total).toFloat()).fillMaxHeight().background(c)) }
    }
}

/** The first few people as overlapping initials, each in their own colour. */
@Composable
fun InitialsStack(names: List<String>, ring: Color, modifier: Modifier = Modifier) {
    Row(modifier) {
        names.forEachIndexed { i, n ->
            Box(
                Modifier.offset(x = (-7 * i).dp).size(24.dp).border(2.dp, ring, CircleShape).clip(CircleShape).background(avatarBrush(n)),
                contentAlignment = Alignment.Center,
            ) { Text(HomeMoney.initials(n), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black, maxLines = 1) }
        }
    }
}

private val AvatarPairs = listOf(
    Color(0xFF38BDF8) to Color(0xFF0284C7), Color(0xFF34D399) to Color(0xFF059669), Color(0xFF60A5FA) to Color(0xFF1D4ED8),
    Color(0xFFF472B6) to Color(0xFFDB2777), Color(0xFFFDBA74) to Color(0xFFEA580C), Color(0xFFC4B5FD) to Color(0xFF7C3AED),
)

/** The same colour for the same person every time. */
fun avatarBrush(name: String): Brush {
    val (a, b) = AvatarPairs[Math.floorMod(name.trim().lowercase().hashCode(), AvatarPairs.size)]
    return Brush.linearGradient(listOf(a, b))
}

@Composable
fun Avatar(name: String, size: Dp = 42.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.36f)).background(avatarBrush(name)), contentAlignment = Alignment.Center) {
        Text(HomeMoney.initials(name), color = Color.White, fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

/** What's left after both sides: "Net −₹500" and a green vs red bar. */
@Composable
fun NetCard(toShop: HomeSide, toPay: HomeSide) {
    val net = toShop.total - toPay.total
    Row(Modifier.fillMaxWidth().whiteCard().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFFFF1D6)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Balance, contentDescription = null, tint = Color(0xFFB45309), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Net " + (if (net < 0) "−" else "+") + formatInr(kotlin.math.abs(net)), color = HomeColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Black)
            Text(stringOf(if (net < 0) R.string.home_net_pay_more else R.string.home_net_collect_more), color = HomeColors.Soft, fontSize = 12.sp)
            SplitBar(listOf(toShop.total to HomeColors.Leaf, toPay.total to HomeColors.Kum), Color(0xFFEEECF3), Modifier.padding(top = 6.dp), 8.dp)
        }
    }
}

/** A section title with an optional "see all". */
@Composable
fun HomeSection(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = HomeColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) Row(Modifier.clickable { onAction() }, verticalAlignment = Alignment.CenterVertically) {
            Text(action, color = HomeColors.LeafDark, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = HomeColors.LeafDark, modifier = Modifier.size(16.dp))
        }
    }
}

/** Today's collections as cards to swipe through: the person, the amount, Call and a WhatsApp reminder. */
@Composable
fun CollectTodayRow(parties: List<HomeParty>, onOpen: (HomeParty) -> Unit, onCall: (HomeParty) -> Unit, onRemind: (HomeParty) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        parties.forEach { p ->
            Column(Modifier.width(200.dp).whiteCard().clickable { onOpen(p) }.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.name)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(p.name, color = HomeColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(dueLabel(p), color = dueColor(p), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(formatInr(p.amount), color = HomeColors.LeafDark, fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(vertical = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniButton(Icons.Rounded.Call, stringOf(R.string.home_call), false, Modifier.weight(1f)) { onCall(p) }
                    MiniButton(Icons.AutoMirrored.Rounded.Chat, stringOf(R.string.home_remind), true, Modifier.weight(1f)) { onRemind(p) }
                }
            }
        }
    }
}

@Composable
private fun MiniButton(icon: ImageVector, label: String, green: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(36.dp).clip(RoundedCornerShape(11.dp))
            .background(if (green) Brush.verticalGradient(listOf(Color(0xFF34D676), Color(0xFF16A34A))) else Brush.verticalGradient(listOf(Color(0xFFF2F0F6), Color(0xFFF2F0F6))))
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (green) Color.White else HomeColors.Ink, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = if (green) Color.White else HomeColors.Ink, fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun dueLabel(p: HomeParty): String = when (p.whenDue) {
    DueWhen.LATE -> androidx.compose.ui.res.stringResource(R.string.home_days_late, p.lateDays)
    DueWhen.TODAY -> stringOf(R.string.home_today)
    DueWhen.LATER -> p.due?.let { "${it.dayOfMonth} ${it.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)}" } ?: stringOf(R.string.home_later)
}

private fun dueColor(p: HomeParty): Color = when (p.whenDue) {
    DueWhen.LATE -> HomeColors.Kum
    DueWhen.TODAY -> Color(0xFFC27C00)
    DueWhen.LATER -> HomeColors.Soft
}

/** One person with a single action on the right (Remind / Pay). */
@Composable
fun ActionRow(p: HomeParty, sub: String, subColor: Color, action: String, actionIcon: ImageVector, green: Boolean, onOpen: () -> Unit, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().whiteCard().clickable { onOpen() }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(p.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, color = HomeColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = subColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.height(38.dp).clip(RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(if (green) listOf(Color(0xFF34D676), Color(0xFF16A34A)) else listOf(HomeColors.InkTop, HomeColors.Ink)))
                .clickable { onAction() }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(actionIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(action, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

/** The four things an owner does most, as big icon buttons. */
@Composable
fun QuickActionsRow(onCredit: () -> Unit, onPayment: () -> Unit, onScan: () -> Unit, onStock: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        QuickAction(Icons.Rounded.Add, stringOf(R.string.home_qa_credit), listOf(Color(0xFF34D399), Color(0xFF059669)), onCredit)
        QuickAction(Icons.Rounded.AccountBalanceWallet, stringOf(R.string.home_qa_payment), listOf(HomeColors.InkTop, HomeColors.Ink), onPayment)
        QuickAction(Icons.Rounded.DocumentScanner, stringOf(R.string.home_qa_scan), listOf(Color(0xFFFDBA74), Color(0xFFEA580C)), onScan)
        QuickAction(Icons.Rounded.Inventory2, stringOf(R.string.home_qa_stock), listOf(Color(0xFFFCD34D), Color(0xFFD97706)), onStock)
    }
}

@Composable
private fun RowScope.QuickAction(icon: ImageVector, label: String, colors: List<Color>, onClick: () -> Unit) {
    Column(Modifier.weight(1f).clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(54.dp).shadow(6.dp, RoundedCornerShape(18.dp), ambientColor = colors.last(), spotColor = colors.last())
                .clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(colors)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(25.dp)) }
        Text(label, color = HomeColors.Ink, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    }
}

/** Stock (turmeric) and today's cash (peacock blue), side by side. */
@Composable
fun StockCashTiles(stockTitle: String, stockSub: String, cashIn: Double, cashOut: Double, onStock: () -> Unit, onCash: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).tile(HomeColors.Turmeric).clickable { onStock() }.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Inventory2, contentDescription = null, tint = HomeColors.Ink, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(stringOf(R.string.home_qa_stock), color = HomeColors.Ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
            Text(stockTitle, color = HomeColors.Ink, fontSize = 19.sp, fontWeight = FontWeight.Black, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            Text(stockSub, color = HomeColors.Ink.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f).tile(HomeColors.Peacock).clickable { onCash() }.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Payments, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(stringOf(R.string.home_cash_today), color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Text(formatInr(cashIn), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            Text(stringOf(R.string.home_cash_out) + " " + formatInr(cashOut), color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** Morning Work as a dark tile: how many tasks today. */
@Composable
fun MorningTile(count: Int?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().tile(HomeColors.Ink).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.WbSunny, contentDescription = null, tint = Color(0xFFFCD34D), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringOf(R.string.home_morning_title), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Text(count?.let { androidx.compose.ui.res.stringResource(R.string.home_morning_count, it) } ?: stringOf(R.string.home_morning_sub),
                color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** "Kai-kitta kelunga…" with the green mic. */
@Composable
fun AskKaiBar(hint: String, onAsk: () -> Unit, onMic: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(54.dp).whiteCard(RoundedCornerShape(20.dp)).clickable { onAsk() }.padding(start = 14.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF8A84A0), modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Text(hint, color = Color(0xFF3B3550), fontSize = 14.5.sp, modifier = Modifier.weight(1f), maxLines = 1)
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Brush.verticalGradient(listOf(HomeColors.GreenTop, HomeColors.GreenBottom)))
                .clickable { onMic() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }
    }
}

/**
 * The whole side after a tile is tapped: the total, the split bar, then Late / Today / Later groups — each person with a
 * WhatsApp reminder (customers) or Pay (suppliers).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneySheet(title: String, side: HomeSide, toShop: Boolean, onDismiss: () -> Unit, onOpen: (HomeParty) -> Unit, onAction: (HomeParty) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (toShop) Color(0xFFDFF5EC) else Color(0xFFFDE4E0)),
                    contentAlignment = Alignment.Center) {
                    Icon(if (toShop) Icons.Rounded.SouthWest else Icons.Rounded.NorthEast, contentDescription = null,
                        tint = if (toShop) HomeColors.LeafDark else HomeColors.KumDark, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, color = HomeColors.Soft, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    Text(formatInr(side.total), color = if (toShop) HomeColors.LeafDark else HomeColors.KumDark, fontSize = 30.sp, fontWeight = FontWeight.Black)
                }
            }
            SplitBar(listOf(side.late to HomeColors.Kum, side.today to HomeColors.Turmeric, side.later to HomeColors.Leaf), Color(0xFFEEECF3),
                Modifier.padding(top = 10.dp), 9.dp)
            Group(Icons.Rounded.WarningAmber, HomeColors.Kum, stringOf(R.string.home_late), side.late, side.lateParties, toShop, onOpen, onAction)
            Group(Icons.Rounded.Schedule, Color(0xFFC27C00), stringOf(R.string.home_today), side.today, side.todayParties, toShop, onOpen, onAction)
            Group(Icons.Rounded.CheckCircle, HomeColors.LeafDark, stringOf(R.string.home_later), side.later, side.laterParties, toShop, onOpen, null)
        }
    }
}

@Composable
private fun Group(icon: ImageVector, color: Color, label: String, amount: Double, parties: List<HomeParty>, toShop: Boolean,
                  onOpen: (HomeParty) -> Unit, onAction: ((HomeParty) -> Unit)?) {
    if (parties.isEmpty()) return
    Row(Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text("$label · ${formatInr(amount)}", color = color, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, HomeColors.Line, RoundedCornerShape(20.dp))) {
        parties.forEachIndexed { i, p ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(2.dp).background(HomeColors.Line))
            Row(Modifier.fillMaxWidth().clickable { onOpen(p) }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.name)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = HomeColors.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(dueLabel(p), color = dueColor(p), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }
                Text(formatInr(p.amount), color = if (toShop) HomeColors.LeafDark else HomeColors.KumDark, fontSize = 16.sp, fontWeight = FontWeight.Black)
                if (onAction != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(11.dp))
                            .background(Brush.verticalGradient(if (toShop) listOf(Color(0xFF34D676), Color(0xFF16A34A)) else listOf(HomeColors.InkTop, HomeColors.Ink)))
                            .clickable { onAction(p) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (toShop) Icons.AutoMirrored.Rounded.Chat else Icons.Rounded.AccountBalanceWallet, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

/** Opens WhatsApp with a short, polite reminder for this person (the owner sends it); no number → the owner picks the chat. */
fun remindOnWhatsApp(context: Context, p: HomeParty, shopName: String) {
    val text = context.getString(R.string.home_remind_text, p.name, shopName, formatInr(p.amount))
    val number = whatsappNumber(p.phone)
    val uri = if (number != null) Uri.parse("https://wa.me/$number?text=${Uri.encode(text)}")
    else Uri.parse("https://wa.me/?text=${Uri.encode(text)}")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Opens the phone's dialer with this person's number (nothing is called by itself). */
fun callParty(context: Context, p: HomeParty) {
    val phone = p.phone?.filter { it.isDigit() || it == '+' }?.takeIf { it.isNotEmpty() } ?: return
    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** The context, for the actions above. */
@Composable
fun homeContext(): Context = LocalContext.current
