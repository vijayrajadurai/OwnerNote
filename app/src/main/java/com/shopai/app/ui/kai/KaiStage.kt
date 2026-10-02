package com.shopai.app.ui.kai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shopai.app.ui.theme.Primary
import com.shopai.app.ui.theme.ShopAiThemeColors

/** What KAI says, in a calm bubble. Changes cross-fade; no bouncing. */
@Composable
fun KaiSpeechBubble(text: String?, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Center) {
    AnimatedContent(
        targetState = text,
        transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 6 }) togetherWith fadeOut(tween(160)) },
        label = "kaiBubble",
        modifier = modifier,
    ) { line ->
        if (!line.isNullOrBlank()) {
            Text(
                text = line,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ShopAiThemeColors.onSurface,
                textAlign = textAlign,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(Color.White, RoundedCornerShape(18.dp))
                    .border(1.dp, Primary.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * KAI as the live speaking interface (Pesunga): KAI, what he says, and
 * what the owner is saying. Tapping KAI starts / stops listening.
 */
@Composable
fun KaiStage(
    state: KaiState,
    line: String?,
    mouthLevel: Float,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    heard: String? = null,
    enabled: Boolean = true,
    /** While speaking: the expression that fits what KAI says. */
    speakingAs: KaiState? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        KaiCharacter(
            state = state,
            mouthLevel = mouthLevel,
            speakingAs = speakingAs,
            size = 300.dp,
            modifier = Modifier.clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
        )
        KaiSpeechBubble(line)
        if (!heard.isNullOrBlank()) {
            Text(
                text = heard,
                style = MaterialTheme.typography.bodyLarge,
                color = ShopAiThemeColors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * KAI's daily brief on Home: large while he greets and speaks, then he
 * settles into a small idle KAI beside the last line.
 */
@Composable
fun KaiBriefCard(
    state: KaiState,
    line: String?,
    mouthLevel: Float,
    compact: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    /** While speaking: the expression that fits what KAI says. */
    speakingAs: KaiState? = null,
) {
    val size: Dp by animateDpAsState(if (compact) 96.dp else 150.dp, tween(600), label = "kaiSize")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KaiCharacter(state = state, mouthLevel = mouthLevel, size = size, speakingAs = speakingAs)
        KaiSpeechBubble(line, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
    }
}
