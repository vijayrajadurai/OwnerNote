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
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Owner Note green — the only accent in Urgent Action Mode. */
internal val UrgentAccent = Color(0xFF2FBE7C)

/**
 * Kai's two full-body pictures for the dark stage, built once per process (off the main thread):
 * FULL (hands in pockets) cleaned of its studio edge, POINT's arm alone (the open hand toward the
 * owner) and the mask that hands FULL's arm over to it.
 */
internal class KaiStageArt private constructor(
    val full: Bitmap,
    val pointArm: Bitmap,
    val armMask: Bitmap,
    /** Skin just above each eye, for the eyelids. */
    val skins: List<Int>,
) {
    companion object {
        @Volatile private var cached: KaiStageArt? = null

        fun ready(): KaiStageArt? = cached

        fun load(context: Context): KaiStageArt = cached ?: synchronized(this) {
            cached ?: build(context.applicationContext).also { cached = it }
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

/** When this ring's screen first showed (per process): the intro plays once per ring, never again on return. */
internal object KaiUrgentClock {
    private val opened = HashMap<String, Long>()

    @Synchronized
    fun openedAt(ringKey: String): Long = opened.getOrPut(ringKey) { SystemClock.elapsedRealtime() }
}

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
 * with his voice ([speech], [mouthLevel]). The light around him is only a soft green floor and air.
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
    var now by remember(openedAt) { mutableLongStateOf(SystemClock.elapsedRealtime() - openedAt) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(still, openedAt) {
        if (still) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) withFrameMillis { now = SystemClock.elapsedRealtime() - openedAt }
        }
    }
    val mouth by rememberUpdatedState(mouthLevel)
    val cue by rememberUpdatedState(speech)
    val full = remember { FloatArray((COLS + 1) * (ROWS + 1) * 2) }
    val point = remember { FloatArray((COLS + 1) * (ROWS + 1) * 2) }
    val label = stringResource(R.string.kai_content_description)

    Box(modifier.semantics { contentDescription = label }) {
        Canvas(Modifier.fillMaxSize()) {
            val take = acting.take(now, mouth, cue, still)
            val s = min(size.width / KaiArt.FULL.width, size.height / KaiArt.FULL.height)
            val left = (size.width - KaiArt.FULL.width * s) / 2f
            val top = size.height - KaiArt.FULL.height * s
            val cx = size.width / 2f
            val floorY = top + FEET_Y * s
            val light = take.glow * (1f - exit)

            // The air: a very soft green light behind his upper body.
            drawCircle(
                Brush.radialGradient(listOf(UrgentAccent.copy(alpha = 0.10f * light), Color.Transparent), center = Offset(cx, top + 420f * s), radius = 560f * s),
                radius = 560f * s, center = Offset(cx, top + 420f * s),
            )
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
