package com.shopai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.ui.theme.BrandGlow
import com.shopai.app.ui.theme.BrandGradientEnd
import com.shopai.app.ui.theme.BrandGradientStart
import com.shopai.app.ui.theme.OnPrimary
import com.shopai.app.ui.theme.OutfitFamily

// Shared look for the Login and OTP screens.

// Same mint as the welcome artwork, so the cropped logo/KAI blend in.
val AuthBackgroundBrush = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFEEFDF7)))

// Regions of R.drawable.img_splash_welcome (1080x1600 px).
private val LogoSrcOffset = IntOffset(200, 100)
val LogoSrcSize = IntSize(680, 460)
// KAI, full body (R.drawable.kai_full).
val KaiSrcSize = IntSize(620, 998)

class WelcomeArtwork(val logo: BitmapPainter, val kai: BitmapPainter)

@Composable
fun rememberWelcomeArtwork(): WelcomeArtwork {
    val artwork = ImageBitmap.imageResource(R.drawable.img_splash_welcome)
    val kai = ImageBitmap.imageResource(R.drawable.kai_full)
    return remember(artwork, kai) {
        WelcomeArtwork(
            logo = BitmapPainter(artwork, LogoSrcOffset, LogoSrcSize, FilterQuality.High),
            kai = BitmapPainter(kai, filterQuality = FilterQuality.High),
        )
    }
}

@Composable
fun GradientActionButton(
    label: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    val active = enabled && !loading
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .graphicsLayer { alpha = if (enabled || loading) 1f else 0.6f }
            .shadow(
                elevation = if (active) 10.dp else 0.dp,
                shape = shape,
                clip = false,
                ambientColor = BrandGlow,
                spotColor = BrandGlow,
            )
            .clip(shape)
            .background(Brush.linearGradient(listOf(BrandGradientStart, BrandGradientEnd)))
            .clickable(enabled = active, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = OnPrimary,
                strokeWidth = 2.5.dp,
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label,
                    color = OnPrimary,
                    fontFamily = OutfitFamily,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = OnPrimary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(20.dp),
                )
            }
        }
    }
}

// Fades the edges of a cropped artwork region to transparent so it blends
// into the screen background instead of showing a hard rectangle.
fun Modifier.fadeEdges(horizontal: Float, vertical: Float): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                horizontal to Color.Black,
                1f - horizontal to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                vertical to Color.Black,
                1f - vertical to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
