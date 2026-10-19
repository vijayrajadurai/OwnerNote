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
    val Ink = Color(0xFF141416)
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
            .clip(shape)
            .background(GreenUi.Ink)
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
            Icon(Icons.Filled.KeyboardDoubleArrowRight, contentDescription = null, tint = Color(0xFF8A8A90), modifier = Modifier.size(22.dp))
            Text(text = label, fontSize = 14.sp, color = Color(0xFFD4D4D8), maxLines = 1, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A2E)),
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
                .background(GreenUi.OnInk)
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
            Text(text = knob, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = GreenUi.Ink, maxLines = 1)
        }
    }
}
