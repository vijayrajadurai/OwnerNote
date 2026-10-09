package com.shopai.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shopai.app.brain.tools.KaiInventory
import com.shopai.app.brain.tools.ProductKind

/**
 * A small animated picture for a product in Inventory / Stock: a rice sack for grocery, a bottle for drinks, an oil can,
 * a shirt, a shoe, a screw, a charger, a carton for packed goods. Drawn in code (no image files, no network), it pops in
 * once and then gently bobs. The kind comes from the product's category / name ([KaiInventory.artKindOf]).
 */
@Composable
fun ProductArt(name: String, category: String?, unit: String?, modifier: Modifier = Modifier, artSize: Dp = 56.dp) {
    val kind = remember(name, category, unit) { KaiInventory.artKindOf(name, category, unit) }
    // Eggs get their own tray picture (they are kept as grocery, but a rice sack would be the wrong picture).
    val egg = remember(name) { KaiInventory.isEgg(name) }
    val palette = if (egg) eggPalette else paletteOf(kind)
    // Pop in once when the card first shows (a product Kai just added appears with it).
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val pop by animateFloatAsState(
        targetValue = if (shown) 1f else 0.6f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "artPop",
    )
    // Then a slow, small bob and tilt — calm, not distracting.
    val idle = rememberInfiniteTransition(label = "artIdle")
    val bob by idle.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "artBob",
    )
    Box(
        modifier = modifier
            .size(artSize)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.background)
            .semantics { contentDescription = kind.category },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
            val lift = -this.size.height * 0.04f * bob
            // A soft shadow that shrinks while the picture lifts.
            val shadowW = this.size.width * (0.55f - 0.08f * bob)
            drawOval(
                color = Color.Black.copy(alpha = 0.10f),
                topLeft = Offset((this.size.width - shadowW) / 2f, this.size.height * 0.90f),
                size = Size(shadowW, this.size.height * 0.07f),
            )
            translate(top = lift) {
                rotate(degrees = (bob - 0.5f) * 6f, pivot = Offset(this.size.width / 2f, this.size.height * 0.9f)) {
                    scale(scale = pop, pivot = center) { if (egg) eggTray(palette) else drawKind(kind, palette) }
                }
            }
        }
    }
}

private val eggPalette = ArtPalette(Color(0xFFFFF8E1), Color(0xFFF3E0C7), Color(0xFFB08968), Color(0xFFFFFFFF), Color(0xFFD7B98E))

/** A tray of eggs. */
private fun DrawScope.eggTray(p: ArtPalette) {
    val w = size.width
    val h = size.height
    drawRoundRect(p.dark, topLeft = Offset(w * 0.08f, h * 0.62f), size = Size(w * 0.84f, h * 0.20f), cornerRadius = CornerRadius(w * 0.05f))
    for (row in 0..1) for (col in 0..2) {
        val cx = w * (0.26f + col * 0.24f)
        val cy = h * (0.50f - row * 0.16f)
        drawOval(p.main, topLeft = Offset(cx - w * 0.10f, cy - h * 0.13f), size = Size(w * 0.20f, h * 0.26f))
        drawOval(p.light.copy(alpha = 0.7f), topLeft = Offset(cx - w * 0.05f, cy - h * 0.09f), size = Size(w * 0.05f, h * 0.08f))
    }
    for (i in 0..3) drawLine(p.accent, Offset(w * (0.14f + i * 0.24f), h * 0.64f), Offset(w * (0.14f + i * 0.24f), h * 0.80f), strokeWidth = w * 0.02f)
}

private data class ArtPalette(val background: Color, val main: Color, val dark: Color, val light: Color, val accent: Color)

private fun paletteOf(kind: ProductKind): ArtPalette = when (kind) {
    ProductKind.GROCERY -> ArtPalette(Color(0xFFFFF4E0), Color(0xFFD9B77E), Color(0xFF9C7A45), Color(0xFFF2DDB5), Color(0xFF2E7D32))
    ProductKind.BEVERAGE -> ArtPalette(Color(0xFFFFEBEE), Color(0xFF8D2C2C), Color(0xFF5D1A1A), Color(0xFFFFCDD2), Color(0xFFD32F2F))
    ProductKind.LIQUID -> ArtPalette(Color(0xFFFFFDE7), Color(0xFFF9C74F), Color(0xFFB8860B), Color(0xFFFFF3B0), Color(0xFF2E7D32))
    ProductKind.GARMENT -> ArtPalette(Color(0xFFE3F2FD), Color(0xFF42A5F5), Color(0xFF1565C0), Color(0xFFBBDEFB), Color(0xFFFFFFFF))
    ProductKind.FOOTWEAR -> ArtPalette(Color(0xFFF3E5F5), Color(0xFF8D6E63), Color(0xFF4E342E), Color(0xFFD7CCC8), Color(0xFFFFFFFF))
    ProductKind.HARDWARE -> ArtPalette(Color(0xFFECEFF1), Color(0xFF90A4AE), Color(0xFF455A64), Color(0xFFCFD8DC), Color(0xFFFFB300))
    ProductKind.ELECTRONICS -> ArtPalette(Color(0xFFE8EAF6), Color(0xFF37474F), Color(0xFF212121), Color(0xFF90A4AE), Color(0xFF3F51B5))
    ProductKind.FMCG -> ArtPalette(Color(0xFFE8F5E9), Color(0xFF43A047), Color(0xFF1B5E20), Color(0xFFC8E6C9), Color(0xFFE53935))
    ProductKind.VEGETABLE -> ArtPalette(Color(0xFFFFEBEE), Color(0xFFE53935), Color(0xFFB71C1C), Color(0xFFFFCDD2), Color(0xFF43A047))
    ProductKind.MEAT -> ArtPalette(Color(0xFFE1F5FE), Color(0xFF4FC3F7), Color(0xFF0277BD), Color(0xFFB3E5FC), Color(0xFFFF7043))
    ProductKind.SWEETS -> ArtPalette(Color(0xFFFFF3E0), Color(0xFFFFA726), Color(0xFFE65100), Color(0xFFFFE0B2), Color(0xFFD81B60))
    ProductKind.POOJA -> ArtPalette(Color(0xFFFFF8E1), Color(0xFFD4A017), Color(0xFF8D6E00), Color(0xFFFFECB3), Color(0xFFFF6F00))
    ProductKind.AGRI -> ArtPalette(Color(0xFFF1F8E9), Color(0xFFE8E2C8), Color(0xFF8D8150), Color(0xFFFFFFFF), Color(0xFF558B2F))
    ProductKind.GENERAL -> ArtPalette(Color(0xFFF5F0E6), Color(0xFFC8A472), Color(0xFF8D6E46), Color(0xFFE6D3B3), Color(0xFF6D4C41))
}

private fun DrawScope.drawKind(kind: ProductKind, p: ArtPalette) {
    when (kind) {
        ProductKind.GROCERY -> riceSack(p)
        ProductKind.BEVERAGE -> bottle(p)
        ProductKind.LIQUID -> oilCan(p)
        ProductKind.GARMENT -> shirt(p)
        ProductKind.FOOTWEAR -> shoe(p)
        ProductKind.HARDWARE -> screw(p)
        ProductKind.ELECTRONICS -> charger(p)
        ProductKind.FMCG -> tube(p)
        ProductKind.VEGETABLE -> tomato(p)
        ProductKind.MEAT -> fish(p)
        ProductKind.SWEETS -> laddus(p)
        ProductKind.POOJA -> diya(p)
        ProductKind.AGRI -> fertilizerSack(p)
        ProductKind.GENERAL -> carton(p)
    }
}

/** A jute rice sack ("moota"), tied at the top, with a grain label. */
private fun DrawScope.riceSack(p: ArtPalette) {
    val w = size.width
    val h = size.height
    val body = Path().apply {
        moveTo(w * 0.26f, h * 0.32f)
        cubicTo(w * 0.12f, h * 0.50f, w * 0.10f, h * 0.78f, w * 0.18f, h * 0.88f)
        cubicTo(w * 0.35f, h * 0.95f, w * 0.65f, h * 0.95f, w * 0.82f, h * 0.88f)
        cubicTo(w * 0.90f, h * 0.78f, w * 0.88f, h * 0.50f, w * 0.74f, h * 0.32f)
        close()
    }
    drawPath(body, p.main)
    // The gathered top.
    val top = Path().apply {
        moveTo(w * 0.30f, h * 0.32f)
        lineTo(w * 0.36f, h * 0.10f)
        lineTo(w * 0.50f, h * 0.20f)
        lineTo(w * 0.64f, h * 0.10f)
        lineTo(w * 0.70f, h * 0.32f)
        close()
    }
    drawPath(top, p.light)
    drawRoundRect(p.dark, topLeft = Offset(w * 0.28f, h * 0.28f), size = Size(w * 0.44f, h * 0.07f), cornerRadius = CornerRadius(w * 0.03f))
    // Label with rice grains.
    drawRoundRect(Color.White, topLeft = Offset(w * 0.30f, h * 0.48f), size = Size(w * 0.40f, h * 0.26f), cornerRadius = CornerRadius(w * 0.05f))
    val grains = listOf(0.38f to 0.56f, 0.48f to 0.53f, 0.58f to 0.57f, 0.43f to 0.65f, 0.54f to 0.66f, 0.63f to 0.64f)
    grains.forEach { (x, y) -> drawOval(p.accent, topLeft = Offset(w * x - w * 0.025f, h * y - h * 0.018f), size = Size(w * 0.05f, h * 0.035f)) }
    // Stitches along the sack.
    for (i in 0..3) drawLine(p.dark.copy(alpha = 0.35f), Offset(w * (0.24f + i * 0.17f), h * 0.84f), Offset(w * (0.30f + i * 0.17f), h * 0.84f), strokeWidth = w * 0.015f)
}

/** A drink bottle with cap and label. */
private fun DrawScope.bottle(p: ArtPalette) {
    val w = size.width
    val h = size.height
    drawRoundRect(p.dark, topLeft = Offset(w * 0.42f, h * 0.06f), size = Size(w * 0.16f, h * 0.08f), cornerRadius = CornerRadius(w * 0.02f))
    val body = Path().apply {
        moveTo(w * 0.44f, h * 0.14f)
        lineTo(w * 0.56f, h * 0.14f)
        cubicTo(w * 0.56f, h * 0.26f, w * 0.68f, h * 0.30f, w * 0.68f, h * 0.42f)
        lineTo(w * 0.68f, h * 0.86f)
        cubicTo(w * 0.68f, h * 0.92f, w * 0.64f, h * 0.94f, w * 0.60f, h * 0.94f)
        lineTo(w * 0.40f, h * 0.94f)
        cubicTo(w * 0.36f, h * 0.94f, w * 0.32f, h * 0.92f, w * 0.32f, h * 0.86f)
        lineTo(w * 0.32f, h * 0.42f)
        cubicTo(w * 0.32f, h * 0.30f, w * 0.44f, h * 0.26f, w * 0.44f, h * 0.14f)
        close()
    }
    drawPath(body, p.main)
    drawRect(p.accent, topLeft = Offset(w * 0.32f, h * 0.52f), size = Size(w * 0.36f, h * 0.18f))
    drawLine(Color.White, Offset(w * 0.38f, h * 0.61f), Offset(w * 0.62f, h * 0.61f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
    drawLine(Color.White.copy(alpha = 0.5f), Offset(w * 0.37f, h * 0.40f), Offset(w * 0.37f, h * 0.48f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
}

/** An oil can with handle and spout. */
private fun DrawScope.oilCan(p: ArtPalette) {
    val w = size.width
    val h = size.height
    drawArc(p.dark, startAngle = 180f, sweepAngle = 180f, useCenter = false,
        topLeft = Offset(w * 0.30f, h * 0.10f), size = Size(w * 0.30f, h * 0.24f), style = Stroke(width = w * 0.05f))
    drawRoundRect(p.main, topLeft = Offset(w * 0.20f, h * 0.24f), size = Size(w * 0.56f, h * 0.68f), cornerRadius = CornerRadius(w * 0.08f))
    drawRoundRect(p.dark, topLeft = Offset(w * 0.64f, h * 0.16f), size = Size(w * 0.10f, h * 0.12f), cornerRadius = CornerRadius(w * 0.02f))
    drawRoundRect(Color.White, topLeft = Offset(w * 0.28f, h * 0.46f), size = Size(w * 0.40f, h * 0.26f), cornerRadius = CornerRadius(w * 0.04f))
    // A drop on the label.
    val drop = Path().apply {
        moveTo(w * 0.48f, h * 0.50f)
        cubicTo(w * 0.42f, h * 0.60f, w * 0.44f, h * 0.68f, w * 0.48f, h * 0.68f)
        cubicTo(w * 0.52f, h * 0.68f, w * 0.54f, h * 0.60f, w * 0.48f, h * 0.50f)
        close()
    }
    drawPath(drop, p.accent)
}

/** A shirt (never weighed). */
private fun DrawScope.shirt(p: ArtPalette) {
    val w = size.width
    val h = size.height
    val body = Path().apply {
        moveTo(w * 0.38f, h * 0.14f)
        lineTo(w * 0.14f, h * 0.26f)
        lineTo(w * 0.06f, h * 0.46f)
        lineTo(w * 0.22f, h * 0.52f)
        lineTo(w * 0.26f, h * 0.44f)
        lineTo(w * 0.26f, h * 0.92f)
        lineTo(w * 0.74f, h * 0.92f)
        lineTo(w * 0.74f, h * 0.44f)
        lineTo(w * 0.78f, h * 0.52f)
        lineTo(w * 0.94f, h * 0.46f)
        lineTo(w * 0.86f, h * 0.26f)
        lineTo(w * 0.62f, h * 0.14f)
        cubicTo(w * 0.58f, h * 0.24f, w * 0.42f, h * 0.24f, w * 0.38f, h * 0.14f)
        close()
    }
    drawPath(body, p.main)
    drawLine(p.dark, Offset(w * 0.50f, h * 0.24f), Offset(w * 0.50f, h * 0.90f), strokeWidth = w * 0.02f)
    listOf(0.36f, 0.50f, 0.64f, 0.78f).forEach { y -> drawCircle(p.accent, radius = w * 0.022f, center = Offset(w * 0.54f, h * y)) }
    drawRoundRect(p.light, topLeft = Offset(w * 0.30f, h * 0.40f), size = Size(w * 0.13f, h * 0.10f), cornerRadius = CornerRadius(w * 0.02f))
}

/** A shoe. */
private fun DrawScope.shoe(p: ArtPalette) {
    val w = size.width
    val h = size.height
    val upper = Path().apply {
        moveTo(w * 0.10f, h * 0.70f)
        lineTo(w * 0.14f, h * 0.30f)
        lineTo(w * 0.34f, h * 0.30f)
        cubicTo(w * 0.40f, h * 0.44f, w * 0.52f, h * 0.48f, w * 0.66f, h * 0.52f)
        cubicTo(w * 0.86f, h * 0.56f, w * 0.92f, h * 0.62f, w * 0.92f, h * 0.70f)
        close()
    }
    drawPath(upper, p.main)
    drawRoundRect(p.dark, topLeft = Offset(w * 0.08f, h * 0.70f), size = Size(w * 0.86f, h * 0.10f), cornerRadius = CornerRadius(w * 0.04f))
    for (i in 0..2) drawLine(p.accent, Offset(w * (0.36f + i * 0.08f), h * (0.42f + i * 0.03f)), Offset(w * (0.42f + i * 0.08f), h * (0.36f + i * 0.03f)),
        strokeWidth = w * 0.025f, cap = StrokeCap.Round)
}

/** A screw for hardware. */
private fun DrawScope.screw(p: ArtPalette) {
    val w = size.width
    val h = size.height
    drawRoundRect(p.dark, topLeft = Offset(w * 0.28f, h * 0.10f), size = Size(w * 0.44f, h * 0.14f), cornerRadius = CornerRadius(w * 0.05f))
    drawLine(p.light, Offset(w * 0.40f, h * 0.17f), Offset(w * 0.60f, h * 0.17f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
    val shaft = Path().apply {
        moveTo(w * 0.40f, h * 0.24f)
        lineTo(w * 0.60f, h * 0.24f)
        lineTo(w * 0.60f, h * 0.76f)
        lineTo(w * 0.50f, h * 0.94f)
        lineTo(w * 0.40f, h * 0.76f)
        close()
    }
    drawPath(shaft, p.main)
    for (i in 0..5) {
        val y = h * (0.30f + i * 0.08f)
        drawLine(p.dark, Offset(w * 0.38f, y), Offset(w * 0.62f, y + h * 0.04f), strokeWidth = w * 0.025f, cap = StrokeCap.Round)
    }
    drawCircle(p.accent, radius = w * 0.05f, center = Offset(w * 0.80f, h * 0.22f))
}

/** A phone charger with its cable. */
private fun DrawScope.charger(p: ArtPalette) {
    val w = size.width
    val h = size.height
    drawLine(p.light, Offset(w * 0.30f, h * 0.10f), Offset(w * 0.30f, h * 0.24f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
    drawLine(p.light, Offset(w * 0.48f, h * 0.10f), Offset(w * 0.48f, h * 0.24f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
    drawRoundRect(p.main, topLeft = Offset(w * 0.18f, h * 0.22f), size = Size(w * 0.42f, h * 0.40f), cornerRadius = CornerRadius(w * 0.08f))
    drawCircle(p.accent, radius = w * 0.04f, center = Offset(w * 0.39f, h * 0.42f))
    val cable = Path().apply {
        moveTo(w * 0.39f, h * 0.62f)
        cubicTo(w * 0.40f, h * 0.88f, w * 0.80f, h * 0.60f, w * 0.80f, h * 0.88f)
    }
    drawPath(cable, p.dark, style = Stroke(width = w * 0.04f, cap = StrokeCap.Round))
    drawRoundRect(p.dark, topLeft = Offset(w * 0.75f, h * 0.84f), size = Size(w * 0.10f, h * 0.10f), cornerRadius = CornerRadius(w * 0.02f))
}

/** A toothpaste / cream tube for packed daily goods. */
private fun DrawScope.tube(p: ArtPalette) {
    val w = size.width
    val h = size.height
    val body = Path().apply {
        moveTo(w * 0.16f, h * 0.30f)
        lineTo(w * 0.70f, h * 0.30f)
        lineTo(w * 0.80f, h * 0.42f)
        lineTo(w * 0.80f, h * 0.58f)
        lineTo(w * 0.70f, h * 0.70f)
        lineTo(w * 0.16f, h * 0.70f)
        close()
    }
    drawPath(body, Color.White)
    drawRect(p.main, topLeft = Offset(w * 0.16f, h * 0.30f), size = Size(w * 0.36f, h * 0.40f))
    drawRect(p.accent, topLeft = Offset(w * 0.16f, h * 0.46f), size = Size(w * 0.36f, h * 0.08f))
    drawRoundRect(p.dark, topLeft = Offset(w * 0.80f, h * 0.43f), size = Size(w * 0.10f, h * 0.14f), cornerRadius = CornerRadius(w * 0.02f))
    for (i in 0..4) drawLine(p.dark.copy(alpha = 0.4f), Offset(w * 0.12f, h * (0.32f + i * 0.09f)), Offset(w * 0.16f, h * (0.32f + i * 0.09f)), strokeWidth = w * 0.02f)
}

/** A closed carton with tape — anything else. */
private fun DrawScope.carton(p: ArtPalette) {
    val w = size.width
    val h = size.height
    val top = Path().apply {
        moveTo(w * 0.14f, h * 0.36f)
        lineTo(w * 0.34f, h * 0.18f)
        lineTo(w * 0.86f, h * 0.18f)
        lineTo(w * 0.66f, h * 0.36f)
        close()
    }
    drawPath(top, p.light)
    val side = Path().apply {
        moveTo(w * 0.66f, h * 0.36f)
        lineTo(w * 0.86f, h * 0.18f)
        lineTo(w * 0.86f, h * 0.70f)
        lineTo(w * 0.66f, h * 0.90f)
        close()
    }
    drawPath(side, p.dark)
    drawRect(p.main, topLeft = Offset(w * 0.14f, h * 0.36f), size = Size(w * 0.52f, h * 0.54f))
    drawRect(p.accent.copy(alpha = 0.6f), topLeft = Offset(w * 0.36f, h * 0.36f), size = Size(w * 0.08f, h * 0.20f))
    drawRoundRect(Color.White, topLeft = Offset(w * 0.22f, h * 0.64f), size = Size(w * 0.24f, h * 0.14f), cornerRadius = CornerRadius(w * 0.02f))
}

/** A tomato with its green crown — vegetables and fruits. */
private fun DrawScope.tomato(p: ArtPalette) {
    val w = size.width
    val h = size.height
    drawOval(p.main, topLeft = Offset(w * 0.14f, h * 0.26f), size = Size(w * 0.72f, h * 0.64f))
    drawOval(p.light.copy(alpha = 0.6f), topLeft = Offset(w * 0.26f, h * 0.36f), size = Size(w * 0.14f, h * 0.12f))
    for (i in 0..4) {
        val a = Math.toRadians(-90.0 + i * 72.0)
        drawLine(p.accent, Offset(w * 0.5f, h * 0.28f), Offset(w * 0.5f + (w * 0.16f * Math.cos(a)).toFloat(), h * 0.28f + (h * 0.10f * Math.sin(a)).toFloat()),
            strokeWidth = w * 0.05f, cap = StrokeCap.Round)
    }
    drawLine(p.accent, Offset(w * 0.5f, h * 0.26f), Offset(w * 0.54f, h * 0.12f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
}

/** A fish — meat and fish shops. */
private fun DrawScope.fish(p: ArtPalette) {
    val w = size.width
    val h = size.height
    val body = Path().apply {
        moveTo(w * 0.12f, h * 0.50f)
        cubicTo(w * 0.30f, h * 0.22f, w * 0.62f, h * 0.22f, w * 0.74f, h * 0.50f)
        cubicTo(w * 0.62f, h * 0.78f, w * 0.30f, h * 0.78f, w * 0.12f, h * 0.50f)
        close()
    }
    drawPath(body, p.main)
    val tail = Path().apply {
        moveTo(w * 0.72f, h * 0.50f)
        lineTo(w * 0.92f, h * 0.32f)
        lineTo(w * 0.92f, h * 0.68f)
        close()
    }
    drawPath(tail, p.dark)
    drawCircle(Color.White, radius = w * 0.05f, center = Offset(w * 0.26f, h * 0.45f))
    drawCircle(p.dark, radius = w * 0.025f, center = Offset(w * 0.26f, h * 0.45f))
    for (i in 0..2) drawArc(p.light, startAngle = -60f, sweepAngle = 120f, useCenter = false,
        topLeft = Offset(w * (0.36f + i * 0.10f), h * 0.40f), size = Size(w * 0.10f, h * 0.20f), style = Stroke(width = w * 0.02f))
}

/** A plate of laddus — sweets and bakery. */
private fun DrawScope.laddus(p: ArtPalette) {
    val w = size.width
    val h = size.height
    drawOval(p.light, topLeft = Offset(w * 0.08f, h * 0.70f), size = Size(w * 0.84f, h * 0.16f))
    val balls = listOf(0.30f to 0.62f, 0.50f to 0.62f, 0.70f to 0.62f, 0.40f to 0.44f, 0.60f to 0.44f, 0.50f to 0.27f)
    balls.forEach { (x, y) ->
        drawCircle(p.main, radius = w * 0.11f, center = Offset(w * x, h * y))
        drawCircle(p.dark.copy(alpha = 0.35f), radius = w * 0.02f, center = Offset(w * (x - 0.04f), h * (y + 0.03f)))
        drawCircle(p.light, radius = w * 0.02f, center = Offset(w * (x + 0.03f), h * (y - 0.04f)))
    }
    drawCircle(p.accent, radius = w * 0.025f, center = Offset(w * 0.5f, h * 0.18f))
}

/** A lit lamp (vilakku) — pooja items and flowers. */
private fun DrawScope.diya(p: ArtPalette) {
    val w = size.width
    val h = size.height
    val bowl = Path().apply {
        moveTo(w * 0.14f, h * 0.56f)
        cubicTo(w * 0.22f, h * 0.84f, w * 0.78f, h * 0.84f, w * 0.86f, h * 0.56f)
        close()
    }
    drawPath(bowl, p.main)
    drawRoundRect(p.dark, topLeft = Offset(w * 0.40f, h * 0.76f), size = Size(w * 0.20f, h * 0.10f), cornerRadius = CornerRadius(w * 0.02f))
    val flame = Path().apply {
        moveTo(w * 0.50f, h * 0.14f)
        cubicTo(w * 0.62f, h * 0.32f, w * 0.60f, h * 0.50f, w * 0.50f, h * 0.54f)
        cubicTo(w * 0.40f, h * 0.50f, w * 0.38f, h * 0.32f, w * 0.50f, h * 0.14f)
        close()
    }
    drawPath(flame, p.accent)
    drawOval(p.light, topLeft = Offset(w * 0.46f, h * 0.34f), size = Size(w * 0.08f, h * 0.16f))
}

/** A fertilizer sack with a leaf — agri shops. */
private fun DrawScope.fertilizerSack(p: ArtPalette) {
    val w = size.width
    val h = size.height
    drawRoundRect(p.main, topLeft = Offset(w * 0.20f, h * 0.14f), size = Size(w * 0.60f, h * 0.76f), cornerRadius = CornerRadius(w * 0.08f))
    drawLine(p.dark, Offset(w * 0.22f, h * 0.20f), Offset(w * 0.78f, h * 0.20f), strokeWidth = w * 0.03f)
    val leaf = Path().apply {
        moveTo(w * 0.36f, h * 0.66f)
        cubicTo(w * 0.36f, h * 0.40f, w * 0.56f, h * 0.34f, w * 0.66f, h * 0.36f)
        cubicTo(w * 0.66f, h * 0.58f, w * 0.52f, h * 0.68f, w * 0.36f, h * 0.66f)
        close()
    }
    drawPath(leaf, p.accent)
    drawLine(p.light, Offset(w * 0.38f, h * 0.64f), Offset(w * 0.62f, h * 0.40f), strokeWidth = w * 0.02f)
}
