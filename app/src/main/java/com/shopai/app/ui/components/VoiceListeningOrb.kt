package com.shopai.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shopai.app.ui.theme.Accent
import com.shopai.app.ui.theme.Primary
import com.shopai.app.ui.theme.ShopAiThemeColors

enum class VoiceOrbState {
    Idle,
    Listening,
    Processing,
}

@Composable
fun VoiceListeningOrb(
    state: VoiceOrbState,
    audioLevel: Float,
    statusText: String,
    partialText: String?,
    onOrbClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "voiceOrb")
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "idlePulse",
    )
    val ripple1 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ripple1",
    )
    val ripple2 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, delayMillis = 450, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ripple2",
    )
    val levelScale by animateFloatAsState(
        targetValue = if (state == VoiceOrbState.Listening) 1f + (audioLevel * 0.18f) else 1f,
        animationSpec = tween(120),
        label = "levelScale",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        ) {
            if (state == VoiceOrbState.Listening) {
                RippleRing(scale = ripple1, alpha = 0.22f)
                RippleRing(scale = ripple2, alpha = 0.14f)
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(132.dp)
                    .scale(
                        when (state) {
                            VoiceOrbState.Idle -> idlePulse
                            VoiceOrbState.Listening -> levelScale
                            VoiceOrbState.Processing -> 1f
                        },
                    )
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Accent, Primary, PrimaryDarkGradient),
                        ),
                    )
                    .clickable(enabled = state != VoiceOrbState.Processing, onClick = onOrbClick),
            ) {
                when (state) {
                    VoiceOrbState.Processing -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(44.dp),
                            color = Color.White,
                            strokeWidth = 3.dp,
                        )
                    }
                    else -> {
                        VoiceWaveBars(
                            active = state == VoiceOrbState.Listening,
                            audioLevel = audioLevel,
                        )
                    }
                }
            }
        }

        Text(
            text = statusText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = ShopAiThemeColors.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (!partialText.isNullOrBlank()) {
            Text(
                text = partialText,
                style = MaterialTheme.typography.bodyLarge,
                color = ShopAiThemeColors.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun RippleRing(scale: Float, alpha: Float) {
    Box(
        modifier = Modifier
            .size(132.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(Primary.copy(alpha = alpha)),
    )
}

@Composable
private fun VoiceWaveBars(active: Boolean, audioLevel: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveBars")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(5) { index ->
            val animated = if (active) {
                val wave = ((phase + index * 0.15f) % 1f)
                0.35f + (kotlin.math.sin(wave * kotlin.math.PI * 2).toFloat() + 1f) * 0.25f
            } else {
                0.3f + index * 0.05f
            }
            val height = (18.dp + (44.dp * animated * (0.45f + audioLevel * 0.55f)))
            Box(
                modifier = Modifier
                    .size(width = 6.dp, height = height)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = if (active) 0.92f else 0.65f)),
            )
        }
    }
}

private val PrimaryDarkGradient = Color(0xFF154F39)
