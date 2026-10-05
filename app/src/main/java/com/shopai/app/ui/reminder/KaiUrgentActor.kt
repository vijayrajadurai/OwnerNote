package com.shopai.app.ui.reminder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.shopai.app.R
import com.shopai.app.ui.kai.KaiArt
import com.shopai.app.ui.kai.KaiMatte
import com.shopai.app.ui.kai.KaiMesh
import com.shopai.app.ui.kai.drawLid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Owner Note green — the only accent in Urgent Action Mode. */
internal val UrgentAccent = Color(0xFF2FBE7C)

/**
 * Kai's two full-body pictures for the dark stage: FULL (hands in pockets) cleaned of its studio edge,
 * POINT's arm alone (the open hand toward the owner) and the mask that hands FULL's arm over to it.
 *
 * Built once, off the main thread, and kept on disk (files/kai_stage_v[ART_VERSION]) — so a ring finds
 * Kai ready: [preload] runs when the app process starts (an alarm starts it too), and every later
 * start only decodes three small PNGs.
 */
class KaiStageArt private constructor(
    val full: Bitmap,
    val pointArm: Bitmap,
    val armMask: Bitmap,
    /** Skin just above each eye, for the eyelids. */
    val skins: List<Int>,
) {
    companion object {
        @Volatile private var cached: KaiStageArt? = null

        /** Bump when [KaiMatte] or the arm handover changes: the disk copy is rebuilt. */
        private const val ART_VERSION = 2

        fun ready(): KaiStageArt? = cached

        /** Starts getting Kai ready in the background (app start); never blocks. */
        fun preload(context: Context) {
            if (cached != null) return
            val app = context.applicationContext
            Thread({ runCatching { load(app) } }, "kai-stage-art").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
        }

        fun load(context: Context): KaiStageArt = cached ?: synchronized(this) {
            cached ?: (fromDisk(context.applicationContext) ?: build(context.applicationContext).also { save(context.applicationContext, it) })
                .also { cached = it }
        }

        private fun dir(context: Context) = java.io.File(context.filesDir, "kai_stage_v$ART_VERSION")

        private fun fromDisk(context: Context): KaiStageArt? = runCatching {
            val d = dir(context)
            val options = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            fun read(name: String) = java.io.File(d, name).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path, options) }
            val full = read("full.png") ?: return null
            val arm = read("arm.png") ?: return null
            val mask = read("mask.png") ?: return null
            val skins = KaiMesh.skinSamplePoints(KaiArt.FULL).map { (sx, sy) -> full.getPixel(sx.toInt().coerceIn(0, full.width - 1), sy.toInt().coerceIn(0, full.height - 1)) }
            KaiStageArt(full, arm, mask, skins)
        }.getOrNull()

        private fun save(context: Context, art: KaiStageArt) {
            runCatching {
                val d = dir(context).apply { mkdirs() }
                // Older versions go.
                context.filesDir.listFiles { f -> f.name.startsWith("kai_stage_v") && f.name != d.name }?.forEach { it.deleteRecursively() }
                for ((name, bmp) in listOf("full.png" to art.full, "arm.png" to art.pointArm, "mask.png" to art.armMask)) {
                    val tmp = java.io.File(d, "$name.tmp")
                    tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    tmp.renameTo(java.io.File(d, name))
                }
            }
        }

        private fun build(context: Context): KaiStageArt {
            val options = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            fun pixels(res: Int): Triple<IntArray, Int, Int> {
                val bmp = BitmapFactory.decodeResource(context.resources, res, options)
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                return Triple(px, bmp.width, bmp.height).also { bmp.recycle() }
            }
            val (fullRaw, w, h) = pixels(R.drawable.kai_full)
            val (pointRaw, pw, ph) = pixels(R.drawable.kai_point)
            val full = KaiMatte.clean(fullRaw, w, h)
            val point = KaiMatte.clean(pointRaw, pw, ph)
            val arm = IntArray(pw * ph)
            val mask = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val m = KaiArt.gestureMask(x.toFloat(), y.toFloat())
                val i = y * w + x
                mask[i] = (255 * m).toInt() shl 24
                if (x < pw && y < ph) {
                    val p = point[y * pw + x]
                    arm[y * pw + x] = (((p ushr 24) * m).toInt() shl 24) or (p and 0xFFFFFF)
                }
            }
            val skins = KaiMesh.skinSamplePoints(KaiArt.FULL).map { (sx, sy) -> full[sy.toInt().coerceIn(0, h - 1) * w + sx.toInt().coerceIn(0, w - 1)] }
            return KaiStageArt(
                full = Bitmap.createBitmap(full, w, h, Bitmap.Config.ARGB_8888),
                pointArm = Bitmap.createBitmap(arm, pw, ph, Bitmap.Config.ARGB_8888),
                armMask = Bitmap.createBitmap(mask, w, h, Bitmap.Config.ARGB_8888),
                skins = skins,
            )
        }
    }
}

/** What the device test reads: where Kai is on screen and whether he is visible yet. */
@androidx.annotation.VisibleForTesting
object KaiUrgentDebug {
    @Volatile var kaiBounds: android.graphics.Rect? = null
    /** [SystemClock.elapsedRealtime] when Kai was first drawn visible on the urgent screen (0 = not yet). */
    @Volatile var kaiVisibleAt: Long = 0L
}

/** When this ring's screen first showed (per process): the intro plays once per ring, never again on return. */
internal object KaiUrgentClock {
    private val opened = HashMap<String, Long>()

    @Synchronized
    fun openedAt(ringKey: String): Long = opened.getOrPut(ringKey) { SystemClock.elapsedRealtime() }
}

/** One drifting light trace on the stage. */
private class Particle(val radius: Float, val angle: Float, val speed: Float, val size: Float, val twinkle: Float, val phase: Float)

private const val COLS = 64
private const val ROWS = 104
/** Where his sandals meet the floor in the 998-px art. */
private const val FEET_Y = 948f

private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
private val erasePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
private val addPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }

/**
 * Kai, large and alive, standing on a dark stage: the body acts ([KaiActing]) — he steps in, notices
 * the owner, brings his open hand out, leans in, nods, breathes, shifts his weight, glances, and moves
 * with his voice ([speech], [mouthLevel]) — on a glowing stage: a green energy field, a slow orbit,
 * drifting light particles, one pulse as he notices the owner, and a soft floor light under him.
 *
 * Fills its box: Kai is fitted head to sandals (never cropped), feet at the bottom.
 * Frames run only while the screen is resumed; reduced motion → still (lip-sync only).
 */
@Composable
internal fun KaiUrgentActor(
    ringKey: String,
    attempt: Int,
    /** [SystemClock.elapsedRealtime] when this ring's screen first showed. */
    openedAt: Long,
    mouthLevel: Float,
    /** The current voice line, in this screen's clock (ms since [openedAt]). */
    speech: SpeechCue?,
    /** 0 → 1 while the screen closes after Done / Snooze. */
    exit: Float,
    /** Snooze: Kai nods as he goes; Done: he settles down. */
    acknowledging: Boolean,
    still: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val art by produceState(KaiStageArt.ready(), context) {
        if (value == null) value = withContext(Dispatchers.Default) { KaiStageArt.load(context) }
    }
    val acting = remember(ringKey) { KaiActing(seed = ringKey.hashCode().toLong(), attempt = attempt) }
    var clock by remember(openedAt) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(still, openedAt) {
        if (still) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) withFrameMillis { clock = SystemClock.elapsedRealtime() }
        }
    }
    // Kai's performance starts when his picture is ready (never an arrival that already happened
    // on an empty stage); the stage itself lights up from the moment the screen opens.
    var actingFrom by remember(openedAt) { mutableLongStateOf(if (KaiStageArt.ready() != null) openedAt else Long.MIN_VALUE) }
    LaunchedEffect(art, openedAt) {
        if (art != null && actingFrom == Long.MIN_VALUE) {
            val t = SystemClock.elapsedRealtime()
            actingFrom = if (t - openedAt < 400) openedAt else t
        }
    }
    val mouth by rememberUpdatedState(mouthLevel)
    val cue by rememberUpdatedState(speech)
    val full = remember { FloatArray((COLS + 1) * (ROWS + 1) * 2) }
    val point = remember { FloatArray((COLS + 1) * (ROWS + 1) * 2) }
    val label = stringResource(R.string.kai_content_description)
    val speed = KaiUrgentMotion.speed(attempt)
    val particles = remember {
        val random = Random(7)
        List(26) {
            Particle(
                radius = 0.45f + random.nextFloat() * 0.6f,
                angle = random.nextFloat() * (2 * PI).toFloat(),
                speed = (0.05f + random.nextFloat() * 0.13f) * if (random.nextBoolean()) 1f else -1f,
                size = 1.1f + random.nextFloat() * 1.6f,
                twinkle = 0.6f + random.nextFloat() * 1.4f,
                phase = random.nextFloat() * 6.28f,
            )
        }
    }

    Box(
        modifier
            .semantics { contentDescription = label }
            .onGloballyPositioned { c ->
                val b = c.boundsInWindow()
                KaiUrgentDebug.kaiBounds = android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stageMs = clock - openedAt
            val now = if (actingFrom == Long.MIN_VALUE) 0L else clock - actingFrom
            val take = acting.take(now, mouth, cue?.let { it.copy(startedAt = it.startedAt + openedAt - actingFrom.coerceAtLeast(openedAt), endedAt = it.endedAt?.plus(openedAt - actingFrom.coerceAtLeast(openedAt))) }, still)
            val s = min(size.width / KaiArt.FULL.width, size.height / KaiArt.FULL.height)
            val left = (size.width - KaiArt.FULL.width * s) / 2f
            val top = size.height - KaiArt.FULL.height * s
            val cx = size.width / 2f
            val floorY = top + FEET_Y * s
            val light = KaiUrgentMotion.easeOut(KaiUrgentMotion.segment(stageMs.toFloat(), 0, 1_200)) * (1f - exit)

            // The glowing stage behind him (the Urgent Action Mode look): a soft green energy field,
            // a thin orbit slowly circling him, light particles drifting, one pulse as he notices the owner.
            val seconds = stageMs / 1000f
            val r = min(size.width * 0.49f, 470f * s)
            val c = Offset(cx, top + 400f * s)
            val breathing = 0.85f + 0.15f * (0.5f + 0.5f * take.pose.breath)
            drawCircle(
                Brush.radialGradient(
                    listOf(UrgentAccent.copy(alpha = 0.22f * light * breathing), UrgentAccent.copy(alpha = 0.05f * light), Color.Transparent),
                    center = c, radius = r * 1.25f,
                ),
                radius = r * 1.25f, center = c,
            )
            if (!still) {
                val orbitR = r * 0.92f
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, UrgentAccent.copy(alpha = 0.45f * light), Color.Transparent), center = c),
                    startAngle = (seconds * 22f * speed) % 360f, sweepAngle = 210f, useCenter = false,
                    topLeft = Offset(c.x - orbitR, c.y - orbitR), size = Size(orbitR * 2, orbitR * 2),
                    style = Stroke(width = 1.4.dp.toPx()),
                )
                for (p in particles) {
                    val a = p.angle + p.speed * speed * seconds
                    val pr = r * p.radius * (0.96f + 0.04f * sin(seconds * 0.7f + p.phase))
                    val twinkle = (0.25f + 0.55f * (0.5f + 0.5f * sin(seconds * p.twinkle + p.phase))) * light
                    drawCircle(Color.White.copy(alpha = twinkle * 0.55f), radius = p.size.dp.toPx(), center = Offset(c.x + pr * cos(a), c.y + pr * sin(a) * 1.3f))
                }
                // One pulse wave as he turns to the owner.
                val pulse = if (actingFrom == Long.MIN_VALUE) 0f else KaiUrgentMotion.segment(now.toFloat(), 1_100, 1_900)
                if (pulse in 0.001f..0.999f) {
                    drawCircle(
                        color = UrgentAccent.copy(alpha = 0.40f * (1f - pulse) * (1f - exit)),
                        radius = r * (0.45f + 0.55f * pulse), center = c,
                        style = Stroke(width = (2.5f * (1f - pulse) + 0.8f).dp.toPx()),
                    )
                }
            }
            // The floor: a faint pool of light he stands in (it follows his weight), and his contact shadow.
            val floorX = cx + take.pose.weightShift * 8f * s
            drawOval(
                Brush.radialGradient(listOf(UrgentAccent.copy(alpha = 0.16f * light), Color.Transparent), center = Offset(floorX, floorY), radius = 230f * s),
                topLeft = Offset(floorX - 230f * s, floorY - 34f * s), size = Size(460f * s, 68f * s),
            )
            drawOval(
                Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.6f * take.alpha * (1f - exit)), Color.Transparent), center = Offset(floorX, floorY), radius = 150f * s),
                topLeft = Offset(floorX - 150f * s, floorY - 12f * s), size = Size(300f * s, 24f * s),
            )

            val a = art ?: return@Canvas
            if (actingFrom == Long.MIN_VALUE) return@Canvas
            if (take.alpha > 0.5f && KaiUrgentDebug.kaiVisibleAt == 0L) KaiUrgentDebug.kaiVisibleAt = SystemClock.elapsedRealtime()
            // Snooze: a small nod as he goes; Done: he settles down out of the light.
            val nod = if (acknowledging) 0.05f * sin(PI.toFloat() * exit) else 0f
            val g = take.gesture
            val blend = KaiArt.gestureBlend(g)
            val fullPose = take.pose.copy(armSwing = KaiArt.fullArmSwing(g), handWave = 0f, headNod = take.pose.headNod + nod)
            val pointPose = take.pose.copy(armSwing = KaiArt.pointArmSwing(g), headNod = take.pose.headNod + nod)
            KaiMesh.deform(KaiArt.FULL, fullPose, COLS, ROWS, full)
            if (blend > 0.002f) KaiMesh.deform(KaiArt.POINT, pointPose, COLS, ROWS, point)
            val scale = take.scale * (1f - 0.04f * exit)
            val rise = (take.riseDp + (if (acknowledging) 6f else 10f) * exit).dp.toPx()
            val alpha = (255 * take.alpha * (1f - exit)).toInt().coerceIn(0, 255)

            withTransform({
                translate(top = rise)
                scale(scale, scale, pivot = Offset(cx, floorY))
            }) {
                drawIntoCanvas { canvas ->
                    val n = canvas.nativeCanvas
                    n.save()
                    n.translate(left, top)
                    n.scale(s, s)
                    // One layer when he is fading or his hand is changing over; otherwise drawn directly.
                    val layered = alpha < 255 || blend > 0.002f
                    if (layered) n.saveLayerAlpha(-60f, -60f, KaiArt.FULL.width + 60f, KaiArt.FULL.height + 60f, alpha)
                    n.drawBitmapMesh(a.full, COLS, ROWS, full, 0, null, 0, meshPaint)
                    if (blend > 0.002f) {
                        // FULL's arm out, POINT's arm in — an exact crossfade only inside the arm's region.
                        erasePaint.alpha = (255 * blend).toInt()
                        n.drawBitmapMesh(a.armMask, COLS, ROWS, full, 0, null, 0, erasePaint)
                        addPaint.alpha = (255 * blend).toInt()
                        n.drawBitmapMesh(a.pointArm, COLS, ROWS, point, 0, null, 0, addPaint)
                    }
                    for (lid in KaiMesh.lids(KaiArt.FULL, fullPose)) drawLid(n, lid, a.skins[if (lid.left) 0 else 1])
                    if (layered) n.restore()
                    n.restore()
                }
            }
        }
    }
}
