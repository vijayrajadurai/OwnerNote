package com.shopai.app.ui.kai

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.brain.chat.KaiAction
import com.shopai.app.brain.chat.KaiCard
import com.shopai.app.brain.note.KaiNote
import com.shopai.app.brain.note.KaiNoteFlow
import com.shopai.app.brain.note.KaiNoteState
import com.shopai.app.brain.note.KaiNotes
import com.shopai.app.brain.note.KaiShopScene
import kotlin.math.ceil
import kotlin.math.roundToInt

private val Emerald = Color(0xFF0F7A55)
private val EmeraldDark = Color(0xFF0B5C40)
private val Gold = Color(0xFFC9A227)
private val Paper = Color(0xFFFFFCF4)
private val Binder = Color(0xFF7A4A24)
private val Rule = Color(0xFFBFD3E6)
private val Ink = Color(0xFF1F3A8A)
private val TextDark = Color(0xFF1C2B26)
private val TextSoft = Color(0xFF5B6B66)
private val Danger = Color(0xFFC62828)

/**
 * Kai's hand-note (owner, 17 Oct 2026 — "Kai live-aa note pannanum, side-la same digital-aa varanum, background shop-ku etha
 * maadhiri"): Kai stands in the owner's own kind of shop with his notebook; his pen writes the draft line by line, and the digital
 * card beside him fills with the same rows as they are written. Everything comes from [flow] — the real draft and the real state:
 * a row ticks only after the books saved it. The buttons are the draft card's own, enabled once the note is written.
 */
@Composable
fun KaiNoteScene(
    flow: KaiNoteFlow,
    note: KaiNote,
    card: KaiCard,
    category: String?,
    closed: Boolean,
    onWritten: () -> Unit,
    onTap: (KaiAction) -> Unit,
    modifier: Modifier = Modifier,
    /** The owner's own words this draft came from (typed or by mic), shown in a bubble as in the shop. */
    said: String? = null,
) {
    val context = LocalContext.current
    val reducedMotion = remember { reducedMotion(context) }
    val dark = isSystemInDarkTheme()
    val scene = KaiShopScene.of(category)
    val noteState = flow.noteState

    // Writing: line by line (each about 0.9 s), then the buttons. Reduced motion: written at once.
    val written = remember(note.messageId) { Animatable(if (reducedMotion || noteState != KaiNoteState.DRAFT_READY) note.rows.size.toFloat() else 0f) }
    LaunchedEffect(note.messageId) {
        if (written.value < note.rows.size) written.animateTo(note.rows.size.toFloat(), tween(durationMillis = 900 * note.rows.size, easing = LinearEasing))
        onWritten()
    }
    val progress = written.value
    val writing = progress < note.rows.size

    // Kai breathes a little while he writes and waits (still when the phone turns animations off).
    val life = rememberInfiniteTransition(label = "kai-note")
    val bob by life.animateFloat(0f, if (reducedMotion) 0f else 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "bob")
    val dots by life.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "dots")

    val rows = note.rows.size
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height((440 + 44 * (rows - 1).coerceIn(0, 4)).dp)
                .clip(RoundedCornerShape(24.dp)),
        ) {
            ShopBackdrop(scene, Modifier.matchParentSize())
            // A soft veil from top to bottom: the shop stays a shop, the notebook and the card stay readable.
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                if (dark) listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.6f))
                else listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.45f)),
            )))

            // The owner's words, as said.
            val finished = noteState == KaiNoteState.SAVE_SUCCESS || noteState == KaiNoteState.SAVE_ERROR
            said?.takeIf { it.isNotBlank() && !finished }?.let { words ->
                // (left of the card: the card takes the right 58%)
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 10.dp, top = 12.dp)
                        .fillMaxWidth(0.36f)
                        .shadow(4.dp, RoundedCornerShape(16.dp))
                        .background(Color.White, RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(24.dp).background(Emerald, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(words, color = TextDark, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }

            // The digital card: the same rows, as they are written.
            DigitalCard(note, progress, noteState, writing, dots, scene, Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp).fillMaxWidth(0.58f))

            // Kai himself, at his notebook.
            Image(
                painter = painterResource(kaiPose(context, noteState, writing)),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-6).dp, y = (bob * -4f).dp)
                    .height(270.dp),
            )

            // The notebook, open in front of him, with the pen at the line being written.
            Notebook(note, progress, noteState, reducedMotion, bob,
                Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 14.dp).fillMaxWidth(0.66f).graphicsLayer { rotationZ = -3f })

            // Saved: said big, once the books confirmed it.
            if (finished) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 10.dp, top = 12.dp)
                        .fillMaxWidth(0.38f)
                        .shadow(8.dp, RoundedCornerShape(20.dp))
                        .background(if (noteState == KaiNoteState.SAVE_SUCCESS) Emerald else Danger, RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (noteState == KaiNoteState.SAVE_SUCCESS) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(KaiNotes.status(noteState, note.lang), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }

        card.warning?.let { Text(it, color = Danger, fontSize = 13.sp) }
        val ready = noteState == KaiNoteState.REVIEWING && !closed
        if (noteState in setOf(KaiNoteState.DRAFT_READY, KaiNoteState.REVIEWING, KaiNoteState.SAVING)) card.buttons.forEach { b ->
            if (b.primary) {
                Button(onClick = { onTap(b.action) }, enabled = ready && b.enabled, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald)) { Text(b.label) }
            } else {
                OutlinedButton(onClick = { onTap(b.action) }, enabled = ready && b.enabled, modifier = Modifier.fillMaxWidth()) { Text(b.label) }
            }
        }
    }
}

/** The card beside Kai: "Kai noting…", then each row as soon as the pen has written it — a dot while it waits, a tick once saved. */
@Composable
private fun DigitalCard(note: KaiNote, progress: Float, state: KaiNoteState, writing: Boolean, dots: Float, scene: KaiShopScene, modifier: Modifier) {
    Column(modifier.shadow(10.dp, RoundedCornerShape(18.dp)).background(Color.White, RoundedCornerShape(18.dp))) {
        val header = if (writing) KaiNotes.status(KaiNoteState.DRAFT_READY, note.lang).trimEnd('…') + ".".repeat(dots.toInt() + 1)
            else KaiNotes.status(state, note.lang)
        Row(
            Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(EmeraldDark, Emerald)), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(note.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(header, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            note.rows.forEachIndexed { i, row ->
                if (progress > i) {
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFFF6F8F7), RoundedCornerShape(12.dp)).padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(34.dp).background(Color(0xFFFFF3D6), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Text(KaiNotes.icon(note.kind, row.name, scene), fontSize = 18.sp, color = Emerald, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.name, color = TextDark, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textDecoration = if (state == KaiNoteState.CANCELLED) TextDecoration.LineThrough else null)
                            Text(row.detail ?: KaiNotes.rowStatus(note.kind, state, note.lang), color = TextSoft, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (row.value.isNotEmpty()) Text(row.value, color = TextDark, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                        Spacer(Modifier.width(4.dp))
                        RowMark(state)
                    }
                }
            }
            if (state == KaiNoteState.SAVING) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Emerald)
        }
    }
}

/** The paper notebook in its leather binder: title, date, and the rows in Kai's hand — the pen at the end of the line being written. */
@Composable
private fun Notebook(note: KaiNote, progress: Float, state: KaiNoteState, reducedMotion: Boolean, bob: Float, modifier: Modifier) {
    val crossed = state == KaiNoteState.CANCELLED
    Row(
        modifier
            .shadow(10.dp, RoundedCornerShape(12.dp))
            .background(Binder, RoundedCornerShape(12.dp))
            .padding(start = 9.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
    ) {
        // The binder's rings.
        Column(Modifier.padding(top = 12.dp, end = 5.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { Box(Modifier.size(7.dp).background(Gold, CircleShape)) }
        }
        Column(
            Modifier
                .weight(1f)
                .background(Paper, RoundedCornerShape(8.dp))
                .drawBehind {
                    val step = 30.dp.toPx()
                    var y = 46.dp.toPx()
                    while (y < size.height) {
                        drawLine(Rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        y += step
                    }
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(note.title, fontFamily = FontFamily.Cursive, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextDark,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("%02d/%02d".format(note.date.dayOfMonth, note.date.monthValue), fontFamily = FontFamily.Cursive, fontSize = 13.sp, color = TextSoft)
            }
            Spacer(Modifier.height(6.dp))
            note.rows.forEachIndexed { i, row ->
                val part = (progress - i).coerceIn(0f, 1f)
                val full = row.name + if (row.value.isNotEmpty()) "  —  ${row.value}" else ""
                val shown = if (part >= 1f) full else full.take(ceil(full.length * part).toInt())
                var penX by remember { mutableFloatStateOf(0f) }
                Box(Modifier.height(30.dp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Text(
                        "${i + 1}.  $shown",
                        fontFamily = FontFamily.Cursive,
                        fontSize = 18.sp,
                        color = if (crossed) TextSoft else Ink,
                        textDecoration = if (crossed) TextDecoration.LineThrough else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { penX = it.getLineRight(0) },
                    )
                    // Kai's pen, at the end of the ink, moving a little as it writes.
                    if (!reducedMotion && part > 0f && part < 1f) Icon(
                        Icons.Filled.Edit, contentDescription = null, tint = TextDark,
                        modifier = Modifier.offset { IntOffset(penX.roundToInt(), (-10 - bob * 4).roundToInt()) }.size(22.dp),
                    )
                }
            }
        }
    }
}

/** A row's mark: a dot while it waits, a tick only after the save, a cross / warning when dropped / not saved. */
@Composable
private fun RowMark(state: KaiNoteState) {
    when (state) {
        KaiNoteState.SAVE_SUCCESS -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Emerald, modifier = Modifier.size(20.dp))
        KaiNoteState.SAVE_ERROR -> Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Danger, modifier = Modifier.size(20.dp))
        KaiNoteState.CANCELLED -> Icon(Icons.Filled.Close, contentDescription = null, tint = TextSoft, modifier = Modifier.size(18.dp))
        else -> Box(Modifier.size(9.dp).background(Gold, CircleShape))
    }
}

/**
 * The shop behind Kai: the owner's picture for the category when it is in the app (res/drawable-nodpi/kai_scene_*.webp), else the
 * shop drawn here — shelves with that kind of shop's goods — softly out of focus. Decoration only.
 */
@Composable
private fun ShopBackdrop(scene: KaiShopScene, modifier: Modifier) {
    val context = LocalContext.current
    val image = remember(scene) { sceneImage(context, scene) }
    if (image != 0) {
        Image(painterResource(image), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.blur(4.dp))
        return
    }
    Canvas(modifier.blur(3.dp)) { drawShop(scene) }
}

/** One shop, drawn: a warm wall, wooden shelves, and goods of that kind of shop on them (same picture every time). */
private fun DrawScope.drawShop(scene: KaiShopScene) {
    val wall = when (scene) {
        KaiShopScene.PHARMACY -> listOf(Color(0xFFEFF7F2), Color(0xFFDDEFE5))
        KaiShopScene.BEAUTY_SALON -> listOf(Color(0xFFF8EEF0), Color(0xFFEBDCE0))
        KaiShopScene.RESTAURANT_FOOD -> listOf(Color(0xFFFFF1DC), Color(0xFFF2D9B3))
        KaiShopScene.HARDWARE, KaiShopScene.AUTO_PARTS -> listOf(Color(0xFFE9E6E1), Color(0xFFD6D0C7))
        else -> listOf(Color(0xFFFBF1DF), Color(0xFFEBD6B3))
    }
    drawRect(Brush.verticalGradient(wall))
    val palette = when (scene) {
        KaiShopScene.GROCERY -> listOf(Color(0xFFE8C77A), Color(0xFFD94F3D), Color(0xFF3E8E5A), Color(0xFFF2B233), Color(0xFF3D6FD9), Color(0xFFF5E6C8))
        KaiShopScene.PHARMACY -> listOf(Color(0xFFFFFFFF), Color(0xFF3AA77B), Color(0xFF7FC4E8), Color(0xFFE8F0F7), Color(0xFFDD5E5E))
        KaiShopScene.HARDWARE -> listOf(Color(0xFF9AA3A8), Color(0xFFE07A2E), Color(0xFF4F6D8C), Color(0xFFC9C2B5), Color(0xFFF2C14E))
        KaiShopScene.ELECTRICAL -> listOf(Color(0xFFF2D24E), Color(0xFFE85D3B), Color(0xFF3D6FD9), Color(0xFFFFFFFF), Color(0xFF2E2E2E))
        KaiShopScene.TEXTILE -> listOf(Color(0xFFC2185B), Color(0xFF1565C0), Color(0xFFF9A825), Color(0xFF2E7D32), Color(0xFF6A1B9A), Color(0xFFEF6C00))
        KaiShopScene.MOBILE_ACCESSORIES -> listOf(Color(0xFF263238), Color(0xFF42A5F5), Color(0xFFEC407A), Color(0xFFFFFFFF), Color(0xFF7E57C2))
        KaiShopScene.AUTO_PARTS -> listOf(Color(0xFF37474F), Color(0xFFB71C1C), Color(0xFFFFB300), Color(0xFF90A4AE))
        KaiShopScene.FURNITURE -> listOf(Color(0xFF8D6E63), Color(0xFFA1887F), Color(0xFF6D4C41), Color(0xFFD7CCC8))
        KaiShopScene.FOOTWEAR -> listOf(Color(0xFF5D4037), Color(0xFF212121), Color(0xFFD84315), Color(0xFFF5F5F5), Color(0xFF1976D2))
        KaiShopScene.STATIONERY -> listOf(Color(0xFF1E88E5), Color(0xFFFDD835), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFFFFFF))
        KaiShopScene.RESTAURANT_FOOD -> listOf(Color(0xFFFFFFFF), Color(0xFFE0A84F), Color(0xFF8D5524), Color(0xFFC0392B))
        KaiShopScene.BEAUTY_SALON -> listOf(Color(0xFFF8BBD0), Color(0xFFFFFFFF), Color(0xFFCE93D8), Color(0xFFFFD54F))
        KaiShopScene.NEUTRAL -> listOf(Color(0xFFE8C77A), Color(0xFFB0BEC5), Color(0xFF8D6E63), Color(0xFFF5E6C8))
    }
    val w = size.width
    val h = size.height
    val shelf = Color(0xFF8B5A2B)
    if (scene == KaiShopScene.BEAUTY_SALON) {
        // A salon mirror with its lights.
        drawRoundRect(Color(0xFFE3F2FD), Offset(w * 0.18f, h * 0.08f), Size(w * 0.64f, h * 0.5f), CornerRadius(30f, 30f))
        for (k in 0..7) drawCircle(Color(0xFFFFF59D), radius = w * 0.018f, center = Offset(w * (0.2f + k * 0.085f), h * 0.07f))
    }
    val levels = if (scene == KaiShopScene.RESTAURANT_FOOD) listOf(0.30f, 0.62f) else listOf(0.22f, 0.44f, 0.66f, 0.88f)
    var seed = scene.ordinal * 7 + 3
    fun next(): Int { seed = (seed * 1103515245 + 12345) and 0x7fffffff; return seed }
    for (y in levels) {
        drawRect(shelf, Offset(0f, h * y), Size(w, h * 0.022f))
        var x = w * 0.02f
        while (x < w * 0.98f) {
            val c = palette[next() % palette.size]
            val itemH = h * (0.07f + (next() % 50) / 1000f)
            val itemW = w * (0.04f + (next() % 40) / 1000f)
            val top = h * y - itemH
            when (scene) {
                KaiShopScene.TEXTILE -> drawRoundRect(c, Offset(x, top), Size(itemW * 0.9f, itemH), CornerRadius(itemW / 2, itemW / 2))
                KaiShopScene.GROCERY, KaiShopScene.NEUTRAL -> if (next() % 3 == 0) {
                    // A sack.
                    drawRoundRect(c, Offset(x, top + itemH * 0.1f), Size(itemW * 1.4f, itemH * 0.9f), CornerRadius(itemW * 0.4f, itemW * 0.4f))
                    x += itemW * 0.5f
                } else drawRoundRect(c, Offset(x, top), Size(itemW, itemH), CornerRadius(6f, 6f))
                KaiShopScene.PHARMACY -> drawRoundRect(c, Offset(x, top + itemH * 0.4f), Size(itemW, itemH * 0.6f), CornerRadius(4f, 4f))
                KaiShopScene.HARDWARE -> if (next() % 2 == 0) {
                    // A paint tin.
                    drawRect(c, Offset(x, top + itemH * 0.3f), Size(itemW * 1.2f, itemH * 0.7f))
                    drawOval(c.copy(alpha = 0.8f), Offset(x, top + itemH * 0.22f), Size(itemW * 1.2f, itemH * 0.18f))
                    x += itemW * 0.2f
                } else drawRoundRect(c, Offset(x, top + itemH * 0.75f), Size(itemW * 3f, itemH * 0.22f), CornerRadius(itemH * 0.1f, itemH * 0.1f)).also { x += itemW * 2f }
                KaiShopScene.RESTAURANT_FOOD -> drawOval(c, Offset(x, h * y - itemH * 0.35f), Size(itemW * 1.6f, itemH * 0.35f)).also { x += itemW * 0.6f }
                else -> drawRoundRect(c, Offset(x, top), Size(itemW, itemH), CornerRadius(6f, 6f))
            }
            x += itemW + w * 0.012f
        }
    }
    // The counter in front.
    drawRect(Brush.verticalGradient(listOf(Color(0xFF9C6B3E), Color(0xFF6E4524)), startY = h * 0.9f, endY = h), Offset(0f, h * 0.9f), Size(w, h * 0.1f))
}

/** Kai's pose: writing while he writes (the owner's "kai_writing" picture when added), proud once saved, worried when not. */
@SuppressLint("DiscouragedApi")
private fun kaiPose(context: Context, state: KaiNoteState, writing: Boolean): Int {
    if (writing || state == KaiNoteState.DRAFT_READY) {
        context.resources.getIdentifier("kai_writing", "drawable", context.packageName).takeIf { it != 0 }?.let { return it }
    }
    return when (state) {
        KaiNoteState.SAVE_SUCCESS -> R.drawable.kai_proud
        KaiNoteState.SAVE_ERROR -> R.drawable.kai_nervous
        KaiNoteState.SAVING -> R.drawable.kai_thoughtful
        KaiNoteState.CANCELLED -> R.drawable.kai_curious
        else -> R.drawable.kai_point
    }
}

@SuppressLint("DiscouragedApi") // The scene pictures are optional: looked up by name so the app builds without them.
private fun sceneImage(context: Context, scene: KaiShopScene): Int =
    context.resources.getIdentifier(scene.image, "drawable", context.packageName)

private fun reducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
