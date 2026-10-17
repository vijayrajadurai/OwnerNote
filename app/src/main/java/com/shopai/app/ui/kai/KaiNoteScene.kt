package com.shopai.app.ui.kai

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.brain.chat.KaiAction
import com.shopai.app.brain.chat.KaiCard
import com.shopai.app.brain.note.KaiNote
import com.shopai.app.brain.note.KaiNoteFlow
import com.shopai.app.brain.note.KaiNoteState
import com.shopai.app.brain.note.KaiNotes
import com.shopai.app.brain.note.KaiShopScene
import kotlin.math.ceil

private val Emerald = Color(0xFF0F7A55)
private val EmeraldLight = Color(0xFFDFF3EA)
private val WarmWhite = Color(0xFFFFFBF2)
private val Gold = Color(0xFFC9A227)
private val Paper = Color(0xFFFFFCF4)
private val Binder = Color(0xFF7A4A24)
private val Rule = Color(0xFFBFD3E6)
private val Ink = Color(0xFF1F3A8A)
private val TextDark = Color(0xFF1C2B26)
private val TextSoft = Color(0xFF5B6B66)
private val Danger = Color(0xFFC62828)

/**
 * Kai's hand-note (owner, 17 Oct 2026): in front of the owner's own kind of shop ([category] → [KaiShopScene]), Kai writes the draft
 * into a notebook line by line while the digital card follows with the same rows. Everything shown comes from [flow] — the real
 * draft and the real state: the card's rows tick only after the books saved them, never before. The buttons are the draft card's
 * own (Confirm / Edit / Cancel), enabled once the note is written.
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
) {
    val context = LocalContext.current
    val reducedMotion = remember { reducedMotion(context) }
    val dark = isSystemInDarkTheme()
    val scene = KaiShopScene.of(category)
    val image = remember(scene) { sceneImage(context, scene) }
    // Shown only for the flow's own note (the screen shows older drafts as plain cards).
    val noteState = flow.noteState

    // Writing: line by line (each about 0.6 s), then the buttons. Reduced motion: written at once.
    val written = remember(note.messageId) { Animatable(if (reducedMotion || noteState != KaiNoteState.DRAFT_READY) note.rows.size.toFloat() else 0f) }
    LaunchedEffect(note.messageId) {
        if (written.value < note.rows.size) written.animateTo(note.rows.size.toFloat(), tween(durationMillis = 600 * note.rows.size, easing = LinearEasing))
        onWritten()
    }
    val progress = written.value

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(EmeraldLight, WarmWhite))),
    ) {
        if (image != 0) {
            Image(
                painter = painterResource(image),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(6.dp),
            )
        }
        // A light veil keeps the notebook and the card readable on any scene (darker at night).
        Box(Modifier.matchParentSize().background(if (dark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.35f)))

        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KaiCharacter(state = kaiStateOf(flow.state), size = 72.dp)
                StatusPill(KaiNotes.status(noteState, note.lang), noteState)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 560.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Notebook(note, progress, noteState, reducedMotion, Modifier.weight(1f))
                        DigitalCard(note, progress, noteState, dark, Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Notebook(note, progress, noteState, reducedMotion, Modifier.fillMaxWidth())
                        DigitalCard(note, progress, noteState, dark, Modifier.fillMaxWidth())
                    }
                }
            }
            card.warning?.let { Text(it, color = Danger, fontSize = 13.sp) }
            val ready = noteState == KaiNoteState.REVIEWING && !closed
            card.buttons.forEach { b ->
                if (b.primary) {
                    Button(onClick = { onTap(b.action) }, enabled = ready && b.enabled, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald)) { Text(b.label) }
                } else {
                    OutlinedButton(onClick = { onTap(b.action) }, enabled = ready && b.enabled, modifier = Modifier.fillMaxWidth()) { Text(b.label) }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, state: KaiNoteState) {
    if (text.isEmpty()) return
    val color = when (state) {
        KaiNoteState.SAVE_SUCCESS -> Emerald
        KaiNoteState.SAVE_ERROR -> Danger
        KaiNoteState.CANCELLED -> TextSoft
        else -> Gold
    }
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** The paper notebook in its leather binder: title, date, and the rows written in Kai's hand. */
@Composable
private fun Notebook(note: KaiNote, progress: Float, state: KaiNoteState, reducedMotion: Boolean, modifier: Modifier) {
    val crossed = state == KaiNoteState.CANCELLED
    Row(
        modifier
            .shadow(6.dp, RoundedCornerShape(12.dp))
            .background(Binder, RoundedCornerShape(12.dp))
            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
    ) {
        // The binder's rings.
        Column(Modifier.padding(top = 10.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(4) { Box(Modifier.size(8.dp).background(Gold, CircleShape)) }
        }
        Column(
            Modifier
                .weight(1f)
                .background(Paper, RoundedCornerShape(8.dp))
                .drawBehind {
                    val step = 30.dp.toPx()
                    var y = 52.dp.toPx()
                    while (y < size.height) {
                        drawLine(Rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        y += step
                    }
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(note.title, fontFamily = FontFamily.Cursive, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextDark,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("%02d/%02d/%d".format(note.date.dayOfMonth, note.date.monthValue, note.date.year), fontFamily = FontFamily.Cursive,
                    fontSize = 14.sp, color = TextSoft)
            }
            Spacer(Modifier.height(8.dp))
            note.rows.forEachIndexed { i, row ->
                val part = (progress - i).coerceIn(0f, 1f)
                val full = row.name + if (row.value.isNotEmpty()) "  ·  ${row.value}" else ""
                val shown = if (part >= 1f) full else full.take(ceil(full.length * part).toInt())
                Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", fontFamily = FontFamily.Cursive, fontSize = 18.sp, color = TextSoft, modifier = Modifier.width(24.dp))
                    Text(
                        shown,
                        fontFamily = FontFamily.Cursive,
                        fontSize = 19.sp,
                        color = if (crossed) TextSoft else Ink,
                        textDecoration = if (crossed) TextDecoration.LineThrough else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Kai's pen, at the line being written.
                    if (!reducedMotion && part > 0f && part < 1f) Icon(Icons.Filled.Edit, contentDescription = null, tint = TextDark, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** The digital card: the same rows, each "ready" until the books saved it — then ticked. */
@Composable
private fun DigitalCard(note: KaiNote, progress: Float, state: KaiNoteState, dark: Boolean, modifier: Modifier) {
    val surface = if (dark) Color(0xFF1E2A26) else Color.White
    val text = if (dark) Color(0xFFF1F5F3) else TextDark
    val soft = if (dark) Color(0xFFB8C7C1) else TextSoft
    Column(
        modifier
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .background(surface, RoundedCornerShape(16.dp))
            .border(1.dp, Emerald.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(note.title, color = Emerald, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        note.rows.forEachIndexed { i, row ->
            if (progress > i) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(row.name, color = text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            textDecoration = if (state == KaiNoteState.CANCELLED) TextDecoration.LineThrough else null)
                        row.detail?.let { Text(it, color = soft, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    if (row.value.isNotEmpty()) Text(row.value, color = text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    RowMark(state)
                }
            }
        }
        if (state == KaiNoteState.SAVING) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Emerald)
    }
}

/** A row's mark: a dot while it waits, a tick only after the save, a cross / warning when dropped / not saved. */
@Composable
private fun RowMark(state: KaiNoteState) {
    when (state) {
        KaiNoteState.SAVE_SUCCESS -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Emerald, modifier = Modifier.size(22.dp))
        KaiNoteState.SAVE_ERROR -> Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Danger, modifier = Modifier.size(22.dp))
        KaiNoteState.CANCELLED -> Icon(Icons.Filled.Close, contentDescription = null, tint = TextSoft, modifier = Modifier.size(20.dp))
        else -> Box(Modifier.size(10.dp).background(Gold, CircleShape))
    }
}

/** Kai's pose for what he is doing. */
private fun kaiStateOf(state: KaiNoteState): KaiState = when (state) {
    KaiNoteState.IDLE, KaiNoteState.CANCELLED -> KaiState.IDLE
    KaiNoteState.LISTENING -> KaiState.LISTENING
    KaiNoteState.UNDERSTANDING, KaiNoteState.SAVING -> KaiState.PROCESSING
    KaiNoteState.CLARIFICATION_REQUIRED -> KaiState.CLARIFY
    KaiNoteState.DRAFT_READY, KaiNoteState.REVIEWING -> KaiState.CONFIDENT
    KaiNoteState.SAVE_SUCCESS -> KaiState.SUCCESS
    KaiNoteState.SAVE_ERROR -> KaiState.ERROR
}

@SuppressLint("DiscouragedApi") // The scene pictures are optional: looked up by name so the app builds without them.
private fun sceneImage(context: Context, scene: KaiShopScene): Int =
    context.resources.getIdentifier(scene.image, "drawable", context.packageName)

private fun reducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
