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
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
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
private val Mint = Color(0xFF7FE0B5)
private val Gold = Color(0xFFC9A227)
private val Paper = Color(0xFFFFFCF4)
private val Binder = Color(0xFF6B3F1D)
private val Rule = Color(0xFFC9D8E6)
private val Ink = Color(0xFF1F3A8A)
private val TextDark = Color(0xFF1C2B26)
private val TextSoft = Color(0xFF5B6B66)
private val Danger = Color(0xFFC62828)

/**
 * Kai's hand-note (owner, 17 Oct 2026 — his picture: Kai at his shop counter writing in the notebook, the "Kai noting…" card
 * beside him filling with the same rows, the owner's words in a bubble, "noted" once saved). The shop behind is the owner's
 * kind of shop. Everything shown comes from [flow] — the real draft and the real state: a row ticks only after the books saved
 * it. The buttons are the draft card's own, enabled once the note is written.
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

    // Writing: line by line (each about a second), then the buttons. Reduced motion: written at once.
    val written = remember(note.messageId) { Animatable(if (reducedMotion || noteState != KaiNoteState.DRAFT_READY) note.rows.size.toFloat() else 0f) }
    LaunchedEffect(note.messageId) {
        if (written.value < note.rows.size) written.animateTo(note.rows.size.toFloat(), tween(durationMillis = 1000 * note.rows.size, easing = LinearEasing))
        onWritten()
    }
    val progress = written.value
    val writing = progress < note.rows.size
    val finished = noteState == KaiNoteState.SAVE_SUCCESS || noteState == KaiNoteState.SAVE_ERROR

    val life = rememberInfiniteTransition(label = "kai-note")
    val bob by life.animateFloat(0f, if (reducedMotion) 0f else 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "bob")
    val beat by life.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "beat")
    // While writing, Kai's whole body follows his hand across the line, a little.
    val lineSway = if (writing && !reducedMotion) ((progress % 1f) - 0.5f) * 10f else 0f

    val rows = note.rows.size
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height((470 + 40 * (rows - 1).coerceIn(0, 5)).dp)
                .clip(RoundedCornerShape(24.dp)),
        ) {
            ShopBackdrop(scene, Modifier.matchParentSize())
            if (dark) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)))

            // Kai behind his counter.
            Image(
                painter = painterResource(kaiPose(context, noteState, writing)),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (8f + lineSway).dp, y = (40f - bob * 4f).dp)
                    .fillMaxHeight(0.78f),
            )
            // The counter in front of him (his legs behind it, as at a shop).
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.26f)) { drawCounter() }

            // The notebook, open on the counter, the pen at the end of the ink.
            Notebook(note, progress, noteState, reducedMotion, bob, scene,
                Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 16.dp).fillMaxWidth(0.70f).graphicsLayer { rotationZ = -4f })

            // The owner's words, as said (mic waves while Kai writes).
            if (!finished) said?.takeIf { it.isNotBlank() }?.let { words ->
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 10.dp, top = 12.dp)
                        .fillMaxWidth(0.38f)
                        .shadow(6.dp, RoundedCornerShape(18.dp))
                        .background(Color.White, RoundedCornerShape(18.dp))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(30.dp).background(Emerald, CircleShape).border(2.dp, Mint.copy(alpha = 0.7f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(words, color = TextDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        if (writing && !reducedMotion) Canvas(Modifier.fillMaxWidth().height(10.dp)) { drawWave(beat) }
                    }
                }
            }

            // The "Kai noting…" card: the same rows as they are written.
            DigitalCard(note, progress, noteState, writing, beat, scene,
                Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 10.dp).fillMaxWidth(0.56f))

            // Noted: said once the books confirmed it (or that it wasn't saved).
            if (finished) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 10.dp, top = 14.dp)
                        .fillMaxWidth(0.40f)
                        .shadow(10.dp, RoundedCornerShape(22.dp))
                        .background(
                            if (noteState == KaiNoteState.SAVE_SUCCESS) Brush.horizontalGradient(listOf(Color(0xFFE6F7EE), Color(0xFFCFF0DF)))
                            else Brush.horizontalGradient(listOf(Color(0xFFFDECEC), Color(0xFFF9D6D6))),
                            RoundedCornerShape(22.dp),
                        )
                        .border(1.5.dp, if (noteState == KaiNoteState.SAVE_SUCCESS) Mint else Danger.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (noteState == KaiNoteState.SAVE_SUCCESS) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, contentDescription = null,
                        tint = if (noteState == KaiNoteState.SAVE_SUCCESS) Emerald else Danger, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(KaiNotes.status(noteState, note.lang), color = if (noteState == KaiNoteState.SAVE_SUCCESS) EmeraldDark else Danger,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                OutlinedButton(onClick = { onTap(b.action) }, enabled = ready && b.enabled, modifier = Modifier.fillMaxWidth()) { Text(b.label, color = Emerald) }
            }
        }
    }
}

/** The "Kai noting…" card: each row as soon as the pen has written it — a dot while it waits, a tick only once saved. */
@Composable
private fun DigitalCard(note: KaiNote, progress: Float, state: KaiNoteState, writing: Boolean, beat: Float, scene: KaiShopScene, modifier: Modifier) {
    Column(
        modifier
            .shadow(14.dp, RoundedCornerShape(20.dp), ambientColor = Emerald, spotColor = Emerald)
            .background(Color.White.copy(alpha = 0.96f), RoundedCornerShape(20.dp))
            .border(1.5.dp, Mint.copy(alpha = 0.8f), RoundedCornerShape(20.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(EmeraldDark, Emerald)), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.EditNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(if (writing) KaiNotes.status(KaiNoteState.DRAFT_READY, note.lang) else note.title, color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (writing) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
                    repeat(3) { i -> Box(Modifier.size(5.dp).background(Mint.copy(alpha = if ((beat * 3).toInt() == i) 1f else 0.35f), CircleShape)) }
                } else Text(KaiNotes.status(state, note.lang), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.padding(7.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            note.rows.forEachIndexed { i, row ->
                if (progress > i) {
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFFF5F8F6), RoundedCornerShape(12.dp)).padding(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(32.dp).background(Color(0xFFFFF3D6), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                            Text(KaiNotes.icon(note.kind, row.name, scene), fontSize = 17.sp, color = Emerald, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.name, color = TextDark, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textDecoration = if (state == KaiNoteState.CANCELLED) TextDecoration.LineThrough else null)
                            Text(KaiNotes.rowStatus(note.kind, state, note.lang), color = TextSoft, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (row.value.isNotEmpty()) Text(row.value, color = TextDark, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                            RowMark(state)
                        }
                    }
                }
            }
            if (state == KaiNoteState.SAVING) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Emerald)
        }
    }
}

/** The leather-bound notebook on the counter: title, date, numbered rows in Kai's hand, the pen at the end of the ink. */
@Composable
private fun Notebook(note: KaiNote, progress: Float, state: KaiNoteState, reducedMotion: Boolean, bob: Float, scene: KaiShopScene, modifier: Modifier) {
    val crossed = state == KaiNoteState.CANCELLED
    Row(
        modifier
            .shadow(16.dp, RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF8A5A2E), Binder)), RoundedCornerShape(12.dp))
            .padding(start = 10.dp, top = 7.dp, bottom = 9.dp, end = 7.dp),
    ) {
        // The binder's gold rings.
        Column(Modifier.padding(top = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(4) { Box(Modifier.size(width = 10.dp, height = 6.dp).background(Brush.horizontalGradient(listOf(Color(0xFFF3D27A), Gold)), RoundedCornerShape(3.dp))) }
        }
        Column(
            Modifier
                .weight(1f)
                .background(Paper, RoundedCornerShape(6.dp))
                .drawBehind {
                    val step = 30.dp.toPx()
                    var y = 44.dp.toPx()
                    while (y < size.height) {
                        drawLine(Rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        y += step
                    }
                }
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(note.title, fontFamily = FontFamily.Cursive, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextDark,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("%d/%d/%d".format(note.date.dayOfMonth, note.date.monthValue, note.date.year % 100), fontFamily = FontFamily.Cursive,
                    fontSize = 13.sp, color = TextSoft)
            }
            Spacer(Modifier.height(5.dp))
            note.rows.forEachIndexed { i, row ->
                val part = (progress - i).coerceIn(0f, 1f)
                // The name, then the value: the pen writes them in that order.
                val nameShare = if (row.value.isEmpty()) 1f else 0.65f
                val namePart = (part / nameShare).coerceIn(0f, 1f)
                val valuePart = if (row.value.isEmpty()) 0f else ((part - nameShare) / (1f - nameShare)).coerceIn(0f, 1f)
                val name = if (namePart >= 1f) row.name else row.name.take(ceil(row.name.length * namePart).toInt())
                val value = if (valuePart >= 1f) row.value else row.value.take(ceil(row.value.length * valuePart).toInt())
                var penX by remember { mutableFloatStateOf(0f) }
                Row(Modifier.height(30.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", fontFamily = FontFamily.Cursive, fontSize = 17.sp, color = TextSoft, modifier = Modifier.width(22.dp))
                    if (part > 0f) Text(KaiNotes.icon(note.kind, row.name, scene), fontSize = 14.sp, modifier = Modifier.padding(end = 4.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Text(name, fontFamily = FontFamily.Cursive, fontSize = 18.sp, color = if (crossed) TextSoft else TextDark,
                            textDecoration = if (crossed) TextDecoration.LineThrough else null, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (valuePart <= 0f) penX = it.getLineRight(0) })
                        if (!reducedMotion && part > 0f && part < 1f && valuePart <= 0f) Pen(Modifier.offset { IntOffset(penX.roundToInt(), (-14 - bob * 3).roundToInt()) })
                    }
                    Box(contentAlignment = Alignment.CenterEnd) {
                        Text(value, fontFamily = FontFamily.Cursive, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (crossed) TextSoft else Ink,
                            textDecoration = if (crossed) TextDecoration.LineThrough else TextDecoration.Underline, maxLines = 1)
                        if (!reducedMotion && valuePart > 0f && valuePart < 1f) Pen(Modifier.offset(x = 10.dp, y = (-14f - bob * 3f).dp))
                    }
                }
            }
        }
    }
}

/** Kai's fountain pen: black body, gold band, steel nib — tilted as in a hand. */
@Composable
private fun Pen(modifier: Modifier) {
    Canvas(modifier.size(26.dp)) {
        rotate(degrees = 40f, pivot = Offset(size.width * 0.15f, size.height * 0.85f)) {
            val w = size.width
            val h = size.height
            drawLine(Color(0xFF1B1B1B), Offset(w * 0.15f, h * 0.85f - h * 0.05f), Offset(w * 0.15f, h * 0.05f), strokeWidth = w * 0.16f, cap = StrokeCap.Round)
            drawLine(Color(0xFFD4AF37), Offset(w * 0.15f, h * 0.55f), Offset(w * 0.15f, h * 0.48f), strokeWidth = w * 0.18f)
            drawLine(Color(0xFFB0BEC5), Offset(w * 0.15f, h * 0.85f), Offset(w * 0.15f, h * 0.97f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
        }
    }
}

/** A row's mark: a dot while it waits, a tick only after the save, a cross / warning when dropped / not saved. */
@Composable
private fun RowMark(state: KaiNoteState) {
    when (state) {
        KaiNoteState.SAVE_SUCCESS -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Emerald, modifier = Modifier.size(18.dp))
        KaiNoteState.SAVE_ERROR -> Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Danger, modifier = Modifier.size(18.dp))
        KaiNoteState.CANCELLED -> Icon(Icons.Filled.Close, contentDescription = null, tint = TextSoft, modifier = Modifier.size(16.dp))
        else -> Box(Modifier.padding(top = 3.dp).size(8.dp).background(Gold, CircleShape))
    }
}

/** The mic's sound waves, moving while Kai writes what was said. */
private fun DrawScope.drawWave(beat: Float) {
    val bars = 14
    val gap = size.width / bars
    for (k in 0 until bars) {
        val phase = ((k * 0.37f + beat) % 1f)
        val amp = 0.25f + 0.75f * (if (phase < 0.5f) phase * 2f else (1f - phase) * 2f)
        val x = gap * k + gap / 2
        drawLine(Emerald.copy(alpha = 0.8f), Offset(x, size.height / 2 - size.height * amp / 2), Offset(x, size.height / 2 + size.height * amp / 2),
            strokeWidth = gap * 0.45f, cap = StrokeCap.Round)
    }
}

/** The shop counter: polished wood with a light edge. */
private fun DrawScope.drawCounter() {
    val h = size.height
    val w = size.width
    drawRect(Brush.verticalGradient(listOf(Color(0xFFB07A45), Color(0xFF7A4A24), Color(0xFF5A3418))), Offset(0f, h * 0.18f), Size(w, h * 0.82f))
    drawRect(Brush.verticalGradient(listOf(Color(0xFFE2B27A), Color(0xFFB07A45))), Offset(0f, h * 0.12f), Size(w, h * 0.08f))
    drawRect(Color.Black.copy(alpha = 0.12f), Offset(0f, h * 0.20f), Size(w, h * 0.03f))
}

/**
 * The shop behind Kai: the owner's picture for the category when it is in the app (res/drawable-nodpi/kai_scene_*.webp), else the
 * shop drawn here out of focus — warm light, shelves of that kind of shop's goods — like a photo behind a person. Decoration only.
 */
@Composable
private fun ShopBackdrop(scene: KaiShopScene, modifier: Modifier) {
    val context = LocalContext.current
    val image = remember(scene) { sceneImage(context, scene) }
    if (image != 0) {
        Image(painterResource(image), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.blur(3.dp))
        return
    }
    Canvas(modifier.blur(12.dp)) { drawShop(scene) }
}

/** One shop, out of focus: a warm wall, wooden shelves with goods of that kind of shop, and warm lights (same picture every time). */
private fun DrawScope.drawShop(scene: KaiShopScene) {
    val wall = when (scene) {
        KaiShopScene.PHARMACY -> listOf(Color(0xFFE8F4EE), Color(0xFFCFE6DA))
        KaiShopScene.BEAUTY_SALON -> listOf(Color(0xFFF6E7EA), Color(0xFFE2CBD1))
        KaiShopScene.HARDWARE, KaiShopScene.AUTO_PARTS -> listOf(Color(0xFFE3DED6), Color(0xFFC9C0B2))
        else -> listOf(Color(0xFFF3E2C4), Color(0xFFD9B98A))
    }
    drawRect(Brush.verticalGradient(wall))
    val palette = when (scene) {
        KaiShopScene.GROCERY -> listOf(Color(0xFFD9B26A), Color(0xFFC0392B), Color(0xFF2E7D4F), Color(0xFFE6A23C), Color(0xFF2F5FBF), Color(0xFFEAD7B0))
        KaiShopScene.PHARMACY -> listOf(Color(0xFFFFFFFF), Color(0xFF2E9E6E), Color(0xFF6BB6E0), Color(0xFFDDE8F1), Color(0xFFD45555))
        KaiShopScene.HARDWARE -> listOf(Color(0xFF8E979C), Color(0xFFD9692A), Color(0xFF3F5D7D), Color(0xFFBDB5A6), Color(0xFFE8B33C))
        KaiShopScene.ELECTRICAL -> listOf(Color(0xFFEFCB3D), Color(0xFFD94F2F), Color(0xFF2F5FBF), Color(0xFFF5F5F5), Color(0xFF333333))
        KaiShopScene.TEXTILE -> listOf(Color(0xFFB0164F), Color(0xFF14549E), Color(0xFFE0951E), Color(0xFF256B2B), Color(0xFF5E1790), Color(0xFFD9620A))
        KaiShopScene.MOBILE_ACCESSORIES -> listOf(Color(0xFF263238), Color(0xFF3A93DB), Color(0xFFD63A6D), Color(0xFFF5F5F5), Color(0xFF6F4BB2))
        KaiShopScene.AUTO_PARTS -> listOf(Color(0xFF37474F), Color(0xFFA11919), Color(0xFFE6A100), Color(0xFF8297A1))
        KaiShopScene.FURNITURE -> listOf(Color(0xFF7D5E54), Color(0xFF8F786F), Color(0xFF5E4038), Color(0xFFCFC2BD))
        KaiShopScene.FOOTWEAR -> listOf(Color(0xFF52382F), Color(0xFF222222), Color(0xFFC23D12), Color(0xFFEEEEEE), Color(0xFF1767BD))
        KaiShopScene.STATIONERY -> listOf(Color(0xFF1C7BD4), Color(0xFFF2CC2E), Color(0xFFD63431), Color(0xFF3A9441), Color(0xFFF5F5F5))
        KaiShopScene.RESTAURANT_FOOD -> listOf(Color(0xFFF5F5F5), Color(0xFFD9993F), Color(0xFF7D4A20), Color(0xFFB03527))
        KaiShopScene.BEAUTY_SALON -> listOf(Color(0xFFF2A9C2), Color(0xFFFFFFFF), Color(0xFFC285CC), Color(0xFFF0C93F))
        KaiShopScene.NEUTRAL -> listOf(Color(0xFFD9B26A), Color(0xFFA5B3BA), Color(0xFF7D5E54), Color(0xFFEAD7B0))
    }
    val w = size.width
    val h = size.height
    var seed = scene.ordinal * 7 + 3
    fun next(): Int { seed = (seed * 1103515245 + 12345) and 0x7fffffff; return seed }
    if (scene == KaiShopScene.BEAUTY_SALON) {
        drawRoundRect(Color(0xFFE6F1F8), Offset(w * 0.30f, h * 0.06f), Size(w * 0.5f, h * 0.42f), CornerRadius(40f, 40f))
        for (k in 0..6) drawCircle(Color(0xFFFFF3B0), radius = w * 0.022f, center = Offset(w * (0.32f + k * 0.075f), h * 0.05f))
    }
    for (y in listOf(0.18f, 0.40f, 0.62f)) {
        drawRect(Color(0xFF7A4A24), Offset(0f, h * y), Size(w, h * 0.018f))
        var x = 0f
        while (x < w) {
            val c = palette[next() % palette.size]
            val itemH = h * (0.09f + (next() % 60) / 1000f)
            val itemW = w * (0.05f + (next() % 50) / 1000f)
            drawRoundRect(c, Offset(x, h * y - itemH), Size(itemW, itemH), CornerRadius(itemW * 0.25f, itemW * 0.25f))
            x += itemW + w * 0.008f
        }
    }
    // Warm shop lights, out of focus.
    for (k in 0 until 7) {
        val cx = w * ((next() % 1000) / 1000f)
        val cy = h * (0.05f + (next() % 500) / 1000f)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE8B0).copy(alpha = 0.55f), Color.Transparent), center = Offset(cx, cy), radius = w * 0.12f),
            radius = w * 0.12f, center = Offset(cx, cy))
    }
    // Light from the front, so the counter and Kai stand out.
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.25f))))
}

/** Kai's pose: writing while he writes (the "kai_writing" picture when added), proud once saved, worried when not. */
@SuppressLint("DiscouragedApi")
private fun kaiPose(context: Context, state: KaiNoteState, writing: Boolean): Int {
    if (writing || state == KaiNoteState.DRAFT_READY || state == KaiNoteState.REVIEWING) {
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
