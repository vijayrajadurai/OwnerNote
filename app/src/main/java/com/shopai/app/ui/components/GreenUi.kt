package com.shopai.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import kotlin.math.roundToInt

/**
 * The light-green OwnerNote look (owner's preview, 19 Oct 2026): a soft green wash, green-grey "bento" tiles, ink-black
 * cards and pills, big light numbers. Shared by Home and the other screens so they read as one app.
 */
object GreenUi {
    /** Text — a deep green-black; no black blocks anywhere (owner, 19 Oct 2026: "black colour remove pannu"). */
    val Ink = Color(0xFF16201B)
    /**
     * The digital colours (owner, 19 Oct 2026: "background mattum green, mathu ellam nalla colour, digital"): the fills —
     * buttons, the slide, the chosen tab, the owner's bubble, the mic — are an indigo → violet gradient with a soft glow.
     */
    val Brand = Color(0xFF4F46E5)
    val BrandSoft = Color(0xFF8B5CF6)
    val BrandTint = Color(0xFFE0E7FF)
    val OnBrand = Color(0xFFFFFFFF)
    val BrandGradient: List<Color> = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
    /** Glass: translucent white cards on the green page. */
    val Glass = Color(0xBBFFFFFF)
    val GlassEdge = Color(0xE6FFFFFF)
    /** The four Home boxes as gradients (white text on them). */
    val CollectGradient: List<Color> = listOf(Color(0xFF10B981), Color(0xFF0D9488))
    val PayGradient: List<Color> = listOf(Color(0xFFF43F5E), Color(0xFFE11D48))
    val StockGradient: List<Color> = listOf(Color(0xFF3B82F6), Color(0xFF6366F1))
    val ReminderGradient: List<Color> = listOf(Color(0xFFF59E0B), Color(0xFFF97316))
    /** Confirm / save: a green gradient (money written in). */
    val ConfirmGradient: List<Color> = listOf(Color(0xFF10B981), Color(0xFF0D9488))
    /** The four Home boxes: to collect (green), to pay (rose), stock (blue), reminders (amber). */
    val CollectBox = Color(0xFFDDF0E4)
    val PayBox = Color(0xFFFBE6E1)
    val StockBox = Color(0xFFE3EBF8)
    val ReminderBox = Color(0xFFFBF0D9)
    val PayInk = Color(0xFFA1261C)
    val StockInk = Color(0xFF2F5597)
    val ReminderInk = Color(0xFF9A6200)
    val InkSoft = Color(0xFF3A3A3F)
    val Tile = Color(0xFFD6E6D9)
    val TileDark = Color(0xFF5B5B60)
    val Line = Color(0xFFD3E0D6)
    val Muted = Color(0xFF4D4D52)
    val OnInk = Color(0xFFF4F4F5)
    val OnInkMuted = Color(0xFFB4B4BA)
    val Wash = Color(0x8CFFFFFF)
}

/** A rounded green-grey tile (or the ink-dark one), tappable when [onClick] is given. */
@Composable
fun GreenTile(
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (dark) GreenUi.TileDark else GreenUi.Tile)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
    ) {
        content()
    }
}

/**
 * Home's hero: what is to be collected, big, with Kai standing on the right and a Kai line to tap ("Kai-kitta
 * kelunga") — the arrow opens the customers.
 */
@Composable
fun GreenHeroCard(
    label: String,
    amount: String,
    subtitle: String,
    kaiLine: String,
    onOpen: () -> Unit,
    onKai: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(30.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(214.dp)
            .clip(shape)
            .background(GreenUi.Tile)
            .clickable(onClick = onOpen),
    ) {
        Image(
            painter = painterResource(R.drawable.kai_proud),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomEnd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 4.dp)
                .width(140.dp)
                .fillMaxHeight(),
        )
        Column(
            modifier = Modifier
                .padding(start = 20.dp, top = 18.dp, end = 140.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = label, fontSize = 13.sp, color = GreenUi.Muted)
            Text(text = amount, fontSize = 38.sp, fontWeight = FontWeight.SemiBold, color = GreenUi.Ink, maxLines = 1)
            Text(text = subtitle, fontSize = 13.sp, color = GreenUi.Muted, maxLines = 2)
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, bottom = 14.dp, end = 120.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEEF5EF))
                    .clickable(onClick = onOpen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.NorthEast, contentDescription = label, tint = GreenUi.Ink, modifier = Modifier.size(18.dp))
            }
            Text(
                text = kaiLine,
                fontSize = 12.sp,
                color = GreenUi.OnInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(GreenUi.Ink)
                    .clickable(onClick = onKai)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** A bento tile: a label (with an optional round icon) at the top, the figure at the bottom. */
@Composable
fun GreenStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    icon: ImageVector? = null,
    dark: Boolean = false,
    onClick: () -> Unit,
) {
    val fg = if (dark) GreenUi.OnInk else GreenUi.Ink
    val sub = if (dark) GreenUi.OnInk.copy(alpha = 0.85f) else GreenUi.Muted
    GreenTile(modifier = modifier, dark = dark, onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .border(1.dp, if (dark) Color(0xFF8A8A90) else Color(0xFFA3B9A8), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                    }
                }
                Text(text = label, fontSize = 14.sp, color = sub, maxLines = 2)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = value, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
                if (unit != null) Text(text = unit, fontSize = 14.sp, color = sub, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

/**
 * "Slide → Day close pannunga": a knob dragged to the end does [onDone] (a tap on the bar does the same) — the
 * owner's swipe, never an accidental press.
 */
@Composable
fun GreenSlideBar(
    label: String,
    knob: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(30.dp)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .shadow(14.dp, shape, ambientColor = GreenUi.Brand.copy(alpha = 0.45f), spotColor = GreenUi.Brand.copy(alpha = 0.45f))
            .clip(shape)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF4F46E5), Color(0xFF7C3AED), Color(0xFFA855F7))))
            .clickable(onClick = onDone),
    ) {
        val density = LocalDensity.current
        val knobPx = with(density) { 48.dp.toPx() }
        val padPx = with(density) { 6.dp.toPx() }
        val maxPx = (with(density) { maxWidth.toPx() } - knobPx - padPx * 2).coerceAtLeast(0f)
        var drag by remember { mutableFloatStateOf(0f) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(start = 64.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.KeyboardDoubleArrowRight, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
            Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = GreenUi.OnInk, modifier = Modifier.size(18.dp))
            }
        }
        Box(
            modifier = Modifier
                .padding(start = 6.dp)
                .align(Alignment.CenterStart)
                .offset { IntOffset(drag.roundToInt(), 0) }
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.White)
                .pointerInput(maxPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (drag >= maxPx * 0.85f) onDone()
                            drag = 0f
                        },
                        onDragCancel = { drag = 0f },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            drag = (drag + amount).coerceIn(0f, maxPx)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = knob, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = GreenUi.Brand, maxLines = 1)
        }
    }
}

/**
 * One of Home's four boxes (owner, 19 Oct 2026): a coloured tile with its title, the big figure, and up to three detail lines on
 * the front (overdue, the low stock items, the next reminders) — tap opens the screen behind it.
 */
@Composable
fun GreenDetailBox(
    title: String,
    value: String,
    gradient: List<Color>,
    icon: ImageVector,
    lines: List<Pair<String, String>>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    empty: String? = null,
) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier = modifier
            .shadow(12.dp, shape, ambientColor = gradient.first().copy(alpha = 0.4f), spotColor = gradient.first().copy(alpha = 0.4f))
            .clip(shape)
            .background(Brush.linearGradient(gradient))
            .clickable(onClick = onClick),
    ) {
        // The digital touch: two soft rings in the corner.
        Box(Modifier.align(Alignment.TopEnd).offset(x = 30.dp, y = (-30).dp).size(100.dp).border(14.dp, Color.White.copy(alpha = 0.12f), CircleShape))
        Box(Modifier.align(Alignment.BottomEnd).offset(x = (-18).dp, y = 40.dp).size(70.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)))
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color.White.copy(alpha = 0.22f))
                        .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Text(text = value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            if (lines.isEmpty() && empty != null) {
                Text(text = empty, fontSize = 12.sp, color = Color.White.copy(alpha = 0.9f), maxLines = 2)
            }
            lines.take(3).forEach { (left, right) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)))
                    Text(text = left, fontSize = 12.sp, color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (right.isNotEmpty()) Text(text = right, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                }
            }
        }
    }
}
