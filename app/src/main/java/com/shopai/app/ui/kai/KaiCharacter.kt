package com.shopai.app.ui.kai

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import com.shopai.app.ui.theme.LedgerCredit
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.rive.runtime.kotlin.RiveAnimationView
import com.shopai.app.R
import com.shopai.app.ui.theme.Primary
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Names KAI's Rive file must use (docs/KAI_RIVE_SPEC.md). */
object KaiRig {
    const val RAW_NAME = "kai"
    const val STATE_MACHINE = "KAI"
    const val INPUT_STATE = "state"
    const val INPUT_MOUTH = "mouth"
    const val INPUT_LOOK_X = "lookX"
    const val INPUT_BLINK = "blink"
    const val INPUT_REDUCED_MOTION = "reducedMotion"
}

/**
 * KAI — OwnerNote's AI Business Companion, live.
 *
 * With KAI's Rive rig (res/raw/kai.riv) the rig animates everything from
 * [state], [mouthLevel] (voice loudness, 0..1), blinks and eye movement
 * driven here. Without it, KAI's own artwork is animated live through a 2D
 * mesh rig (KaiMesh): blinking, glances, lip-sync with the voice, head tilt
 * and nod, breathing, sway, and an expression for every state.
 *
 * Idle life runs only while the screen is visible, and motion is reduced
 * to a still pose (lip-sync only) when the system turns animations off.
 */
@Composable
fun KaiCharacter(
    state: KaiState,
    modifier: Modifier = Modifier,
    /** KAI's height (head to sandals); the width follows his full-body proportions. */
    size: Dp = 160.dp,
    mouthLevel: Float = 0f,
    /** While [state] is SPEAKING: the expression that fits what KAI says (his face follows the meaning). */
    speakingAs: KaiState? = null,
) {
    val context = LocalContext.current
    val reducedMotion = remember { isReducedMotion(context) }
    val rigResource = remember { kaiRigResource(context) }

    // Idle life: blinks every few seconds, small glances. Paused when not visible.
    var blinkCount by remember { mutableIntStateOf(0) }
    var lookX by remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(Random.nextLong(2_400, 5_600))
                blinkCount++
                if (Random.nextFloat() < 0.35f) lookX = Random.nextFloat() * 0.6f - 0.3f
                else if (Random.nextFloat() < 0.5f) lookX = 0f
            }
        }
    }

    val label = stringResource(R.string.kai_content_description)
    Box(
        modifier = modifier
            .size(width = size * KAI_ASPECT, height = size)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (rigResource != 0) {
            KaiRive(rigResource, state, mouthLevel, lookX, blinkCount, reducedMotion)
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                KaiLive(state, speakingAs, mouthLevel, reducedMotion)
                KaiSign(if (state == KaiState.SPEAKING) speakingAs ?: state else state, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

@SuppressLint("DiscouragedApi") // The rig file is optional: looked up by name so the app builds without it.
private fun kaiRigResource(context: Context): Int =
    context.resources.getIdentifier(KaiRig.RAW_NAME, "raw", context.packageName)

private fun isReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

// ------------------------------------------------------------------ rig

@Composable
private fun KaiRive(resource: Int, state: KaiState, mouthLevel: Float, lookX: Float, blinkCount: Int, reducedMotion: Boolean) {
    val sm = KaiRig.STATE_MACHINE
    val view = remember { arrayOfNulls<RiveAnimationView>(1) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            RiveAnimationView(ctx).apply {
                setRiveResource(resource, stateMachineName = sm, autoplay = true)
                view[0] = this
            }
        },
        update = { rive ->
            // A rig missing an input must never crash the screen.
            runCatching {
                rive.setNumberState(sm, KaiRig.INPUT_STATE, state.rigIndex.toFloat())
                rive.setNumberState(sm, KaiRig.INPUT_MOUTH, mouthLevel * 100f)
                rive.setNumberState(sm, KaiRig.INPUT_LOOK_X, lookX * 100f)
                rive.setBooleanState(sm, KaiRig.INPUT_REDUCED_MOTION, reducedMotion)
            }
        },
    )
    // One blink per new count.
    LaunchedEffect(blinkCount) {
        if (blinkCount > 0) runCatching { view[0]?.fireState(sm, KaiRig.INPUT_BLINK) }
    }
}

// ------------------------------------------------- live KAI (2D mesh rig)

/** Width : height of KAI's full-body art. */
const val KAI_ASPECT = 0.62f

/** One of KAI's expressions: the artwork and where his face is in it. */
private data class KaiLook(val art: Int, val rig: KaiArtRig, val cols: Int, val rows: Int)

private val LOOK_FULL = KaiLook(R.drawable.kai_full, KaiArt.FULL, 64, 104)
private val LOOK_POINT = KaiLook(R.drawable.kai_point, KaiArt.POINT, 64, 104)
private val LOOK_JOYFUL = KaiLook(R.drawable.kai_joyful, KaiArt.JOYFUL, 40, 64)
private val LOOK_SURPRISED = KaiLook(R.drawable.kai_surprised, KaiArt.SURPRISED, 40, 64)
private val LOOK_THOUGHTFUL = KaiLook(R.drawable.kai_thoughtful, KaiArt.THOUGHTFUL, 40, 64)
private val LOOK_CURIOUS = KaiLook(R.drawable.kai_curious, KaiArt.CURIOUS, 40, 64)
private val LOOK_WINKING = KaiLook(R.drawable.kai_winking, KaiArt.WINKING, 40, 64)
private val LOOK_PROUD = KaiLook(R.drawable.kai_proud, KaiArt.PROUD, 40, 60)
private val LOOK_NERVOUS = KaiLook(R.drawable.kai_nervous, KaiArt.NERVOUS, 40, 60)

/** KAI's face and body for each state. */
private fun lookFor(state: KaiState): KaiLook = when (state) {
    KaiState.IDLE, KaiState.LISTENING -> LOOK_FULL
    // Open hand toward the owner: greeting, explaining, pointing at information.
    KaiState.GREETING, KaiState.SPEAKING, KaiState.CREDIT, KaiState.INSIGHT -> LOOK_POINT
    KaiState.PROCESSING, KaiState.THOUGHTFUL -> LOOK_THOUGHTFUL
    KaiState.HAPPY -> LOOK_JOYFUL
    KaiState.SUCCESS -> LOOK_WINKING
    // Calm, professional, confident.
    KaiState.DEBIT, KaiState.CONFIDENT, KaiState.FUNDING -> LOOK_PROUD
    KaiState.SURPRISED -> LOOK_SURPRISED
    // Attentive, gently concerned — never angry.
    KaiState.REMINDER, KaiState.CONCERNED, KaiState.SERIOUS, KaiState.CLARIFY -> LOOK_CURIOUS
    KaiState.ERROR -> LOOK_NERVOUS
}

/**
 * KAI's live motion: blinks at natural random intervals, glances, breathes,
 * sways, and moves his head and body the way each state calls for; while
 * he speaks the jaw follows the voice and the head moves with emphasis.
 */
private class KaiMotion {
    private var nextBlinkAt = 1_500L
    private var blinkStartedAt = -1_000L
    private var lookTarget = 0f
    private var look = 0f
    private var nextLookAt = 2_000L
    private var lastT = 0L

    fun pose(t: Long, state: KaiState, mouth: Float, stateSince: Long): KaiPose {
        val dt = (t - lastT).coerceIn(0, 100)
        lastT = t
        // Blink: close 70 ms, open 110 ms, every 2.4–5.8 s (sometimes a double blink).
        if (t >= nextBlinkAt) {
            blinkStartedAt = t
            nextBlinkAt = t + if (Random.nextFloat() < 0.15f) 260L else Random.nextLong(2_400, 5_800)
        }
        val b = t - blinkStartedAt
        val blink = when {
            b < 0 -> 0f
            b < 70 -> b / 70f
            b < 180 -> 1f - (b - 70) / 110f
            else -> 0f
        }
        // Glances while idle or thinking; eye contact while talking or serious.
        val wanders = state == KaiState.IDLE || state == KaiState.PROCESSING || state == KaiState.THOUGHTFUL
        if (t >= nextLookAt) {
            lookTarget = if (wanders) Random.nextFloat() * 1.6f - 0.8f else 0f
            nextLookAt = t + Random.nextLong(1_400, 3_800)
        }
        if (!wanders) lookTarget = 0f
        look += (lookTarget - look) * (dt / 180f).coerceAtMost(1f)

        val s = t / 1000f
        val since = (t - stateSince) / 1000f
        fun wave(period: Float, phase: Float = 0f) = sin((s / period + phase) * 2f * PI.toFloat())
        val breath = wave(3.6f)
        var tilt = 1.2f * wave(6.1f)
        var nod = 0.01f * wave(4.3f)
        var sway = 0.5f * wave(7.3f)
        var lean = 0f
        when (state) {
            KaiState.GREETING -> { tilt = 2.5f * wave(1.4f); nod = 0.015f * wave(0.9f) }
            KaiState.LISTENING -> { lean = 1f; tilt = 2f; nod = 0.022f * maxOf(0f, wave(1.8f)) }
            KaiState.PROCESSING, KaiState.THOUGHTFUL -> { tilt = -2f + 0.8f * wave(3f); nod = 0f }
            KaiState.SPEAKING -> {
                tilt = 1.6f * wave(2.3f) + 1.4f * mouth * wave(0.9f)
                nod = 0.025f * mouth + 0.01f * wave(1.7f)
                sway = 0.8f * wave(3.1f)
            }
            KaiState.HAPPY, KaiState.SUCCESS -> { nod = 0.03f * kotlin.math.abs(wave(0.8f)) * (if (since < 2.5f) 1f else 0.3f); tilt = 2f * wave(1.6f) }
            KaiState.SURPRISED -> { tilt = -1.5f; nod = -0.02f * (if (since < 0.6f) since / 0.6f else 1f) }
            // Serious: still, direct eye contact.
            KaiState.SERIOUS -> { tilt = 0f; nod = 0.005f * wave(3f); sway = 0.2f * wave(7.3f) }
            // Gentle "no" shake when asking again / something went wrong, then still.
            KaiState.CLARIFY, KaiState.ERROR -> { tilt = if (since < 1.6f) 2.2f * wave(0.55f) else 1f }
            KaiState.REMINDER, KaiState.CONCERNED -> { tilt = 1.8f; nod = 0.012f * wave(2.2f) }
            KaiState.CREDIT, KaiState.DEBIT, KaiState.CONFIDENT, KaiState.INSIGHT, KaiState.FUNDING -> { tilt = 1f * wave(4f); nod = 0.008f * wave(2.5f) }
            KaiState.IDLE -> Unit
        }
        return KaiPose(
            breath = breath,
            blink = blink,
            lookX = look,
            mouth = if (state == KaiState.SPEAKING) mouth else 0f,
            headTilt = tilt,
            headNod = nod,
            sway = sway,
            lean = lean,
        )
    }
}

/**
 * KAI, alive: the expression for [state] drawn through the mesh rig and
 * moved every frame. While speaking, his face follows the meaning of what
 * he says ([speakingAs]) and alternates with his explaining hand gesture.
 * Frames run only while the screen is visible; ~30 fps when calm, 60 while
 * talking; still (lip-sync only) with reduced motion.
 */
@Composable
private fun KaiLive(state: KaiState, speakingAs: KaiState?, mouthLevel: Float, reducedMotion: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(0L) }
    var stateSince by remember { mutableLongStateOf(0L) }
    val motion = remember { KaiMotion() }
    val currentMouth by rememberUpdatedState(mouthLevel)
    val currentState by rememberUpdatedState(state)
    val speaking = state == KaiState.SPEAKING

    LaunchedEffect(state) { stateSince = now }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val start = withFrameMillis { it }
            while (true) {
                withFrameMillis { now = it - start }
                // Calm: ~30 fps is plenty (battery); talking: every frame.
                if (currentState != KaiState.SPEAKING) delay(16)
            }
        }
    }

    // Speaking: the meaning's expression, alternating with the explaining gesture.
    val face = speakingAs?.let(::lookFor)
    val look = when {
        !speaking -> lookFor(state)
        face == null || face == LOOK_POINT -> LOOK_POINT
        (now / 3_200L) % 2L == 0L -> face
        else -> LOOK_POINT
    }
    Crossfade(targetState = look, animationSpec = tween(360), label = "kaiLook") { shown ->
        val image = ImageBitmap.imageResource(shown.art)
        val bitmap = remember(image) { image.asAndroidBitmap() }
        val vertices = remember(shown) { FloatArray((shown.cols + 1) * (shown.rows + 1) * 2) }
        val paint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG) }
        // Skin just above each eye (below the brow), for the eyelids.
        val skins = remember(bitmap, shown) {
            KaiMesh.skinSamplePoints(shown.rig).map { (x, y) ->
                bitmap.getPixel(x.toInt().coerceIn(0, bitmap.width - 1), y.toInt().coerceIn(0, bitmap.height - 1))
            }
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(18.dp)),
        ) {
            val pose = if (reducedMotion) {
                KaiPose(mouth = if (speaking) currentMouth else 0f)
            } else {
                motion.pose(now, state, currentMouth, stateSince)
            }
            KaiMesh.deform(shown.rig, pose, shown.cols, shown.rows, vertices)
            val rig = shown.rig
            val scale = minOf(size.width / rig.width, size.height / rig.height)
            val dx = (size.width - rig.width * scale) / 2f
            val dy = size.height - rig.height * scale
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.translate(dx, dy)
                native.scale(scale, scale)
                native.drawBitmapMesh(bitmap, shown.cols, shown.rows, vertices, 0, null, 0, paint)
                // Blink: eyelids in KAI's own skin colour, feathered at the rim, with a lash line.
                for (lid in KaiMesh.lids(rig, pose)) drawLid(native, lid, skins[if (lid.left) 0 else 1])
                native.restore()
            }
        }
    }
}
private val lidPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
private val lashPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    style = android.graphics.Paint.Style.STROKE
    strokeCap = android.graphics.Paint.Cap.ROUND
    color = 0xDC3A2822.toInt()
}

/** One eyelid (only drawn during the ~180 ms of a blink). */
internal fun drawLid(canvas: android.graphics.Canvas, lid: KaiMesh.Lid, skin: Int) {
    val cx = lid.centerX
    val cy = lid.centerY
    val rx = lid.rx
    val ry = lid.ry
    lidPaint.shader = android.graphics.RadialGradient(
        cx, cy, rx,
        intArrayOf(skin, skin, skin and 0x00FFFFFF),
        floatArrayOf(0f, 0.7f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    ).apply { setLocalMatrix(android.graphics.Matrix().apply { setScale(1f, ry / rx, cx, cy) }) }
    // The lid: from above the eye down to a gently curved lower edge.
    val lidPath = android.graphics.Path().apply {
        moveTo(cx - rx, cy - ry)
        lineTo(cx + rx, cy - ry)
        lineTo(cx + rx, lid.bottom)
        quadTo(cx, lid.bottom + ry * 0.6f, cx - rx, lid.bottom)
        close()
    }
    canvas.drawPath(lidPath, lidPaint)
    // Lashes along the lid's edge.
    lashPaint.strokeWidth = lid.lashWidth
    canvas.drawPath(
        android.graphics.Path().apply {
            moveTo(cx - rx * 0.8f, lid.bottom + ry * 0.108f)
            quadTo(cx, lid.bottom + ry * 0.492f, cx + rx * 0.8f, lid.bottom + ry * 0.108f)
        },
        lashPaint,
    )
}

// Debit is a normal recorded entry: warm orange, never error red.
private val KaiDebitColour = Color(0xFFE08A1E)
private val KaiClarifyColour = Color(0xFF5B7FA6)

/** The small sign that shows what KAI is telling the owner. */
@Composable
private fun KaiSign(state: KaiState, modifier: Modifier) {
    val sign: Pair<ImageVector, Color>? = when (state) {
        KaiState.CREDIT -> Icons.Default.CurrencyRupee to LedgerCredit
        KaiState.DEBIT -> Icons.Default.CurrencyRupee to KaiDebitColour
        KaiState.SUCCESS -> Icons.Default.ThumbUp to LedgerCredit
        KaiState.LISTENING -> Icons.Default.Mic to Primary
        KaiState.REMINDER -> Icons.Default.NotificationsActive to KaiDebitColour
        KaiState.INSIGHT -> Icons.AutoMirrored.Filled.TrendingUp to LedgerCredit
        KaiState.FUNDING -> Icons.Default.Lightbulb to Color(0xFFD4A017)
        KaiState.CLARIFY -> Icons.Default.QuestionMark to KaiClarifyColour
        else -> null
    }
    AnimatedVisibility(
        visible = sign != null,
        enter = scaleIn(spring(dampingRatio = 0.6f)) + fadeIn(),
        exit = scaleOut() + fadeOut(),
        modifier = modifier,
    ) {
        val (icon, colour) = sign ?: return@AnimatedVisibility
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(colour, CircleShape)
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}
