package com.shopai.app.ui.kai

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.chat.KaiAction
import com.shopai.app.brain.chat.KaiCard
import com.shopai.app.brain.note.KaiNote
import com.shopai.app.brain.note.KaiNoteFlow
import com.shopai.app.brain.note.KaiNoteState
import com.shopai.app.brain.note.KaiNotes
import com.shopai.app.brain.note.KaiShopScene
import kotlin.math.ceil

private val Ink = Color(0xFF1F3A8A)
private val PaperWhite = Color(0xFFFFFEFA)
private val PaperRule = Color(0xFFECE7DD)

/**
 * Kai's note, iPhone style (owner, 18 Oct 2026: "iPhone classy-la kudu", "Kai venaam, ezhuthu mattum"): a Dynamic-Island pill on top
 * ("Kai noting", voice bars), Kai standing on the left, a white sheet of paper where the note writes itself line by line, under it a
 * clean iPhone list of the same rows, a black Confirm button and a red Cancel — and, only once the books saved it, the Apple-Pay-like
 * "✓ Save aagiduchu". Everything shown comes from [flow]: a row ticks only after the save; nothing is written before Confirm.
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
    /** The owner's own words this draft came from (typed or by mic), shown as his message bubble. */
    said: String? = null,
    /** Full screen (the scene over the chat while Kai notes): the scene takes the height; [onClose] puts a close button on it. */
    fullScreen: Boolean = false,
    onClose: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val reducedMotion = remember { reducedMotion(context) }
    val scene = KaiShopScene.of(category)
    val state = flow.noteState

    // Writing: line by line (about a second each), then the buttons. Reduced motion: written at once.
    val written = remember(note.messageId) { Animatable(if (reducedMotion || state != KaiNoteState.DRAFT_READY) note.rows.size.toFloat() else 0f) }
    LaunchedEffect(note.messageId) {
        if (written.value < note.rows.size) written.animateTo(note.rows.size.toFloat(), tween(durationMillis = 1000 * note.rows.size, easing = LinearEasing))
        onWritten()
    }
    val progress = written.value
    val writing = progress < note.rows.size

    val life = rememberInfiniteTransition(label = "kai-note")
    val bob by life.animateFloat(0f, if (reducedMotion) 0f else 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "bob")
    val beat by life.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "beat")

    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(if (fullScreen) 0.dp else 28.dp))) {
        Backdrop(scene, Modifier.matchParentSize())

        Column(Modifier.fillMaxWidth().then(if (fullScreen) Modifier.fillMaxHeight() else Modifier)) {
            Spacer(Modifier.height(10.dp))
            Island(note, state, writing, beat, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(10.dp))

            // The owner's own words, as his message.
            if (state != KaiNoteState.SAVE_SUCCESS) said?.takeIf { it.isNotBlank() }?.let { words ->
                Row(
                    Modifier.align(Alignment.End).padding(end = 16.dp).widthIn(max = 250.dp)
                        .background(IosGreen, RoundedCornerShape(topStart = 19.dp, topEnd = 19.dp, bottomStart = 19.dp, bottomEnd = 5.dp))
                        .padding(horizontal = 13.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(words, color = Color.White, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }

            // Kai, and the paper the note writes itself on.
            val rows = note.rows.size
            Box(Modifier.fillMaxWidth().height((210 + 33 * (rows - 1).coerceIn(0, 4)).dp)) {
                Image(
                    painter = painterResource(kaiPose(context, state, writing)),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.BottomStart).offset(x = (-14).dp, y = (-bob * 4f).dp).fillMaxHeight(0.98f),
                )
                Paper(note, progress, state, Modifier.align(Alignment.CenterEnd).padding(end = 14.dp, top = 14.dp).fillMaxWidth(0.70f)
                    .graphicsLayer { rotationZ = -3f })
            }
            Spacer(Modifier.height(14.dp))

            GroupedList(note, progress, state, scene, Modifier.padding(horizontal = 14.dp))
            card.warning?.let { Text(it, color = IosRed, fontSize = 13.sp, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp)) }

            if (fullScreen) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(14.dp))
            Buttons(card, state, ready = state == KaiNoteState.REVIEWING && !closed, onTap = onTap)
            Spacer(Modifier.height(if (fullScreen) 18.dp else 12.dp))
        }

        onClose?.let { close ->
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 12.dp).size(30.dp).background(Color.Black.copy(alpha = 0.08f), CircleShape)
                    .clickable { close() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, contentDescription = null, tint = IosGrey, modifier = Modifier.size(18.dp)) }
        }

        // Saved — said only once the books confirmed it — or plainly not saved.
        if (state == KaiNoteState.SAVE_SUCCESS || state == KaiNoteState.SAVE_ERROR) {
            Done(state == KaiNoteState.SAVE_SUCCESS, KaiNotes.status(state, note.lang).removeSuffix(" ✓"), reducedMotion, note.messageId,
                Modifier.align(if (fullScreen) Alignment.Center else Alignment.TopCenter).padding(top = if (fullScreen) 0.dp else 120.dp))
        }
    }
}

/** The Dynamic-Island pill: green dot and voice bars while Kai notes, the state in words after. */
@Composable
private fun Island(note: KaiNote, state: KaiNoteState, writing: Boolean, beat: Float, modifier: Modifier) {
    val live = writing || state == KaiNoteState.DRAFT_READY || state == KaiNoteState.SAVING
    val (dot, text) = when {
        state == KaiNoteState.SAVE_SUCCESS -> IosGreen to when (note.lang) { KaiLang.TAMIL -> "சேமிச்சாச்சு"; KaiLang.TANGLISH -> "Save aagiduchu"; KaiLang.ENGLISH -> "Saved" }
        state == KaiNoteState.SAVE_ERROR -> IosRed to when (note.lang) { KaiLang.TAMIL -> "சேமிக்கல"; KaiLang.TANGLISH -> "Save aagala"; KaiLang.ENGLISH -> "Not saved" }
        state == KaiNoteState.CANCELLED -> IosGrey to when (note.lang) { KaiLang.TAMIL -> "ரத்து"; KaiLang.TANGLISH -> "Cancel"; KaiLang.ENGLISH -> "Cancelled" }
        state == KaiNoteState.SAVING -> IosGreen to KaiNotes.status(state, note.lang)
        writing || state == KaiNoteState.DRAFT_READY -> IosGreen to when (note.lang) { KaiLang.TAMIL -> "Kai எழுதுறார்"; else -> "Kai noting" }
        else -> IosGreen to when (note.lang) { KaiLang.TAMIL -> "Confirm பண்ணுங்க"; KaiLang.TANGLISH -> "Confirm pannunga"; KaiLang.ENGLISH -> "Please confirm" }
    }
    Row(
        modifier.height(32.dp).widthIn(min = if (live) 190.dp else 120.dp, max = 280.dp).background(Color.Black, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).shadow(4.dp, CircleShape, ambientColor = dot, spotColor = dot).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        if (live) {
            Spacer(Modifier.width(12.dp))
            Row(Modifier.height(12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(4) { k ->
                    val phase = (beat + k * 0.17f) % 1f
                    val h = 3f + 9f * (if (phase < 0.5f) phase * 2f else (1f - phase) * 2f)
                    Box(Modifier.size(width = 2.5.dp, height = h.dp).background(IosGreen, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

/** The white sheet: a cursive title and the date, faint lines, and the rows writing themselves (name, then the value). */
@Composable
private fun Paper(note: KaiNote, progress: Float, state: KaiNoteState, modifier: Modifier) {
    val crossed = state == KaiNoteState.CANCELLED
    Column(
        modifier
            .shadow(20.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black.copy(alpha = 0.14f), spotColor = Color.Black.copy(alpha = 0.14f))
            .background(PaperWhite, RoundedCornerShape(18.dp))
            .drawBehind {
                val step = 33.dp.toPx()
                var y = 52.dp.toPx()
                while (y < size.height - 6.dp.toPx()) {
                    drawLine(PaperRule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    y += step
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(note.title, fontFamily = FontFamily.Cursive, fontSize = 21.sp, color = IosLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Text("%d/%d/%d".format(note.date.dayOfMonth, note.date.monthValue, note.date.year % 100), fontSize = 11.sp, color = IosGrey,
                modifier = Modifier.padding(top = 8.dp, start = 6.dp))
        }
        Spacer(Modifier.height(4.dp))
        note.rows.take(5).forEachIndexed { i, row ->
            val part = (progress - i).coerceIn(0f, 1f)
            val nameShare = if (row.value.isEmpty()) 1f else 0.65f
            val namePart = (part / nameShare).coerceIn(0f, 1f)
            val valuePart = if (row.value.isEmpty()) 0f else ((part - nameShare) / (1f - nameShare)).coerceIn(0f, 1f)
            val name = if (namePart >= 1f) row.name else row.name.take(ceil(row.name.length * namePart).toInt())
            val value = if (valuePart >= 1f) row.value else row.value.take(ceil(row.value.length * valuePart).toInt())
            Row(Modifier.height(33.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (part > 0f) Text("${i + 1}.", fontFamily = FontFamily.Cursive, fontSize = 17.sp, color = if (crossed) IosGrey else Ink,
                    modifier = Modifier.padding(end = 6.dp))
                Text(name, fontFamily = FontFamily.Cursive, fontSize = 17.sp, color = if (crossed) IosGrey else Ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textDecoration = if (crossed) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f))
                if (value.isNotEmpty()) Text(value, fontFamily = FontFamily.Cursive, fontSize = 17.sp, color = if (crossed) IosGrey else IosLabel,
                    maxLines = 1, textDecoration = if (crossed) TextDecoration.LineThrough else null, modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (note.rows.size > 5 && progress >= 5f) Text("+${note.rows.size - 5}", fontFamily = FontFamily.Cursive, fontSize = 15.sp, color = IosGrey)
    }
}

/** The iPhone list under the paper: "STOCK ADD" and the state, then each row — icon, name, its state, value, and its circle. */
@Composable
private fun GroupedList(note: KaiNote, progress: Float, state: KaiNoteState, scene: KaiShopScene, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.08f))
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.92f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(note.title.uppercase(), color = IosGrey, fontSize = 12.sp, letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Text(KaiNotes.status(state, note.lang), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = when (state) { KaiNoteState.SAVE_ERROR -> IosRed; KaiNoteState.CANCELLED -> IosGrey; else -> IosGreen },
                modifier = Modifier.padding(start = 8.dp).widthIn(max = 200.dp))
        }
        note.rows.forEachIndexed { i, row ->
            val shown = ((progress - i) * 3f).coerceIn(0f, 1f)
            if (shown > 0f) Column(Modifier.alpha(shown).graphicsLayer { translationY = (1f - shown) * 6.dp.toPx() }) {
                Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(0.5.dp).background(IosSeparator))
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).background(IosGrouped, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                        Text(KaiNotes.icon(note.kind, row.name, scene), fontSize = 17.sp, color = IosLabel, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.name, color = IosLabel, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textDecoration = if (state == KaiNoteState.CANCELLED) TextDecoration.LineThrough else null)
                        Text(row.detail?.let { "$it · " }.orEmpty() + KaiNotes.rowStatus(note.kind, state, note.lang), color = IosGrey, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (row.value.isNotEmpty()) Text(row.value, color = IosLabel, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp))
                    RowMark(state)
                }
            }
        }
    }
}

/** A row's circle: hollow while it waits, green with a tick only after the save; red when not saved, a cross when dropped. */
@Composable
private fun RowMark(state: KaiNoteState) {
    when (state) {
        KaiNoteState.SAVE_SUCCESS -> Box(Modifier.size(22.dp).background(IosGreen, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
        KaiNoteState.SAVE_ERROR -> Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = IosRed, modifier = Modifier.size(22.dp))
        KaiNoteState.CANCELLED -> Icon(Icons.Filled.Close, contentDescription = null, tint = IosGrey, modifier = Modifier.size(18.dp))
        else -> Box(Modifier.size(22.dp).border(1.5.dp, Color(0xFFC7C7CC), CircleShape))
    }
}

/** The draft card's own buttons: the main one a black pill (dim until the note is written), Cancel in red, any other in blue. */
@Composable
private fun ColumnScope.Buttons(card: KaiCard, state: KaiNoteState, ready: Boolean, onTap: (KaiAction) -> Unit) {
    if (state !in setOf(KaiNoteState.DRAFT_READY, KaiNoteState.REVIEWING, KaiNoteState.SAVING)) return
    card.buttons.forEach { b ->
        val enabled = ready && b.enabled
        if (b.primary) {
            Box(
                Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth().height(52.dp)
                    .alpha(if (enabled || state == KaiNoteState.SAVING) 1f else 0.3f)
                    .clip(RoundedCornerShape(16.dp)).background(Color.Black)
                    .clickable(enabled = enabled) { onTap(b.action) },
                contentAlignment = Alignment.Center,
            ) {
                if (state == KaiNoteState.SAVING) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                else Text(b.label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text(
                b.label,
                color = when { !enabled -> IosGrey; isCancel(b.action) -> IosRed; else -> Color(0xFF007AFF) },
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) { onTap(b.action) }.padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
    }
}

/** Apple-Pay-like: a frosted square, a green circle whose tick draws itself, and the word. Not saved: the red mark. */
@Composable
private fun Done(saved: Boolean, text: String, reducedMotion: Boolean, key: String, modifier: Modifier) {
    val pop = remember(key, saved) { Animatable(if (reducedMotion) 1f else 0.6f) }
    val tick = remember(key, saved) { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(key, saved) {
        if (!reducedMotion) {
            pop.animateTo(1f, tween(450, easing = CubicBezierEasing(0.2f, 1.4f, 0.4f, 1f)))
            tick.animateTo(1f, tween(500))
        }
    }
    Column(
        modifier.scale(pop.value).alpha(((pop.value - 0.6f) / 0.4f).coerceIn(0f, 1f)).size(156.dp)
            .shadow(24.dp, RoundedCornerShape(34.dp), ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
            .background(Color.White.copy(alpha = 0.95f), RoundedCornerShape(34.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (saved) Canvas(Modifier.size(64.dp)) {
            drawCircle(IosGreen)
            val path = Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.67f)
                lineTo(size.width * 0.73f, size.height * 0.36f)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val part = Path()
            measure.getSegment(0f, measure.length * tick.value, part, true)
            drawPath(part, Color.White, style = Stroke(width = size.width * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = IosRed, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(10.dp))
        Text(text, color = IosLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/**
 * Behind it all: the owner's shop picture for the category when it is in the app (res/drawable-nodpi/kai_scene_*.webp), softly out of
 * focus under a white veil; else a plain warm light, as iPhone's wallpapers. Decoration only.
 */
@Composable
private fun Backdrop(scene: KaiShopScene, modifier: Modifier) {
    val context = LocalContext.current
    val image = remember(scene) { sceneImage(context, scene) }
    Box(modifier.background(Brush.radialGradient(listOf(Color(0xFFFFF6E8), Color(0xFFF3E7D6), Color(0xFFE8DCCB)), radius = 1400f, center = Offset(250f, 120f)))) {
        if (image != 0) {
            Image(painterResource(image), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().blur(14.dp))
            Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 0.55f)))
        }
    }
}

/** Kai's pose: writing while the note writes (the "kai_writing" picture when added), proud once saved, worried when not. */
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
        KaiNoteState.DRAFT_READY, KaiNoteState.REVIEWING -> R.drawable.kai_full
        else -> R.drawable.kai_point
    }
}

@SuppressLint("DiscouragedApi") // The scene pictures are optional: looked up by name so the app builds without them.
private fun sceneImage(context: Context, scene: KaiShopScene): Int =
    context.resources.getIdentifier(scene.image, "drawable", context.packageName)

private fun reducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
