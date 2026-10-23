package com.shopai.app.ui.screens

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shopai.app.R
import com.shopai.app.ui.kai.KaiFace
import com.shopai.app.ui.kai.KaiInk
import com.shopai.app.ui.kai.VeshtiBand

/** One full-screen Kai story behind a Home tile: what Kai says, and the button that opens the screen behind it. */
data class HomeStory(
    val key: String,
    val tag: String,
    val value: String,
    val text: String,
    val cta: String,
    val route: String,
    /** Top and bottom of the background. */
    val colors: List<Color>,
)

/**
 * Home tile stories (owner, 23 Oct 2026): full screen, Kai standing, golden progress bars along the top. Each runs six
 * seconds then the next; a tap on the right goes on, on the left goes back, ✕ closes. The big button and the reply bar open
 * the screens the app already has. Read only — nothing is saved here.
 */
@Composable
fun HomeStoryViewer(
    stories: List<HomeStory>,
    start: Int,
    onClose: () -> Unit,
    onOpen: (String) -> Unit,
) {
    if (stories.isEmpty()) return
    var index by remember { mutableIntStateOf(start.coerceIn(0, stories.lastIndex)) }
    val progress = remember(index) { Animatable(0f) }
    LaunchedEffect(index) {
        progress.animateTo(1f, tween(6000, easing = LinearEasing))
        if (index < stories.lastIndex) index++ else onClose()
    }
    val s = stories[index]
    val noRipple = remember { MutableInteractionSource() }
    val bob = rememberInfiniteTransition(label = "storyKai")
    val dy by bob.animateFloat(0f, -6f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "storyKaiY")

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(s.colors))) {
            Image(
                painter = painterResource(R.drawable.kai_mascot),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 24.dp, y = dy.dp).height(440.dp),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x8C000000)))),
            )
            // Tap zones: left goes back, right goes on.
            Row(Modifier.fillMaxSize().padding(top = 80.dp, bottom = 160.dp)) {
                Box(Modifier.weight(0.35f).fillMaxHeight().clickable(interactionSource = noRipple, indication = null) { if (index > 0) index-- })
                Box(
                    Modifier.weight(0.65f).fillMaxHeight().clickable(interactionSource = noRipple, indication = null) {
                        if (index < stories.lastIndex) index++ else onClose()
                    },
                )
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    stories.forEachIndexed { i, _ ->
                        val fill = when {
                            i < index -> 1f
                            i == index -> progress.value
                            else -> 0f
                        }
                        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x47FFFFFF))) {
                            Box(
                                Modifier
                                    .fillMaxWidth(fill)
                                    .fillMaxHeight()
                                    .background(Brush.horizontalGradient(listOf(KaiInk.GoldLight, KaiInk.Gold))),
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    KaiFace(size = 36.dp)
                    Text(stringResource(R.string.home_kai_says), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x29FFFFFF)).clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.story_close), tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
                Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        s.tag.uppercase(),
                        color = KaiInk.GoldLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.border(1.dp, KaiInk.GoldLight.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                    Text(s.value, color = Color.White, fontSize = 50.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 54.sp, maxLines = 2)
                    Text(s.text, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp, modifier = Modifier.widthIn(max = 280.dp))
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .clickable { onClose(); onOpen(s.route) },
                ) {
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                        Text(s.cta, color = KaiInk.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    VeshtiBand(height = 6.dp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0x24FFFFFF))
                            .clickable { onClose(); onOpen(com.shopai.app.ui.navigation.Routes.KaiChat) }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(stringResource(R.string.story_reply), color = Color(0xD9FFFFFF), fontSize = 14.sp)
                    }
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(KaiInk.Green)
                            .clickable { onClose(); onOpen(com.shopai.app.ui.navigation.Routes.KaiChat) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.home_ask_kai), tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(0.dp))
            }
        }
    }
}

/**
 * The stories behind the Home tiles, from what Home already loaded: Kai's brief, today's Daily Cash Note, the reminders, and
 * (for a shop with stock) the products at or under their minimum. A figure not loaded is said plainly, never made up.
 */
@Composable
fun homeStories(
    brief: String,
    today: com.shopai.app.data.model.TodayCashSummary?,
    reminders: List<com.shopai.app.ui.reminders.PaymentReminder>,
    lowStock: List<com.shopai.app.data.model.InventoryProduct>?,
    stock: Boolean,
): List<HomeStory> {
    val routes = com.shopai.app.ui.navigation.Routes
    val inr = { v: Double -> com.shopai.app.util.formatInr(v) }
    return buildList {
        add(
            HomeStory(
                "kai", stringResource(R.string.home_kai_says), stringResource(R.string.kai_greeting), brief,
                stringResource(R.string.home_ask_kai), routes.KaiChat, listOf(Color(0xFF047857), Color(0xFF052E2B)),
            ),
        )
        add(
            HomeStory(
                "today", stringResource(R.string.home_tile_today), inr(today?.totalIn ?: 0.0),
                stringResource(R.string.home_in_out, inr(today?.totalIn ?: 0.0), inr(today?.totalOut ?: 0.0)),
                stringResource(R.string.story_today_cta), routes.DailyCashNote, listOf(Color(0xFF059669), Color(0xFF064E3B)),
            ),
        )
        val late = reminders.count { it.urgency == com.shopai.app.ui.reminders.ReminderUrgency.OVERDUE }
        val who = reminders.take(3).joinToString(" · ") { r -> r.partyName + (r.amount?.let { " " + inr(it) } ?: "") }
        add(
            HomeStory(
                "reminders", stringResource(R.string.home_tile_reminders), stringResource(R.string.story_rem_value, reminders.size),
                when {
                    reminders.isEmpty() -> stringResource(R.string.story_rem_none)
                    late > 0 -> stringResource(R.string.story_rem_late, late) + " — " + who
                    else -> who
                },
                stringResource(R.string.story_rem_cta), routes.Reminders, listOf(Color(0xFF7C3AED), Color(0xFF2E1065)),
            ),
        )
        if (stock) {
            val low = lowStock.orEmpty()
            val names = low.take(3).joinToString(", ") { p -> "${p.name} ${com.shopai.app.util.formatQty(p.currentStock)} ${p.unit}" } +
                if (low.size > 3) "…" else ""
            add(
                HomeStory(
                    "stock", stringResource(R.string.story_stock_tag),
                    if (lowStock == null) "—" else stringResource(R.string.story_stock_value, low.size),
                    when {
                        lowStock == null -> stringResource(R.string.story_stock_loading)
                        low.isEmpty() -> stringResource(R.string.story_stock_ok)
                        else -> stringResource(R.string.story_stock_text, names)
                    },
                    stringResource(R.string.story_stock_cta), routes.Inventory, listOf(Color(0xFFB91C1C), Color(0xFF450A0A)),
                ),
            )
        }
    }
}
