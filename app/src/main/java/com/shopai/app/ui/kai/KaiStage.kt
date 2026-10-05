package com.shopai.app.ui.kai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shopai.app.ui.theme.Primary
import kotlinx.coroutines.delay

private const val HeadlineDwellMs = 3_800L

/** What KAI says: two lines at a time; longer copy ticks like a headline. */
@Composable
fun KaiSpeechBubble(
    text: String?,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
    /** Home brief sits on the page, the same way the Kai details message does. */
    background: Boolean = true,
) {
    val line = text?.trim().orEmpty()
    if (line.isBlank()) return

    val textStyle = MaterialTheme.typography.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        color = com.shopai.app.ui.theme.TextPrimary,
        textAlign = textAlign,
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var page by remember(line) { mutableIntStateOf(0) }

    BoxWithConstraints(
        modifier = if (background) {
            modifier
                .widthIn(max = 320.dp)
                .background(Color.White, RoundedCornerShape(18.dp))
                .border(1.dp, Primary.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        } else {
            modifier.fillMaxWidth()
        },
    ) {
        val widthPx = with(density) {
            val cap = if (maxWidth.value.isFinite() && maxWidth > 0.dp) maxWidth else 280.dp
            cap.roundToPx().coerceAtLeast(1)
        }
        val pages = remember(line, widthPx, textStyle) {
            val layout = measurer.measure(
                text = AnnotatedString(line),
                style = textStyle,
                constraints = Constraints(maxWidth = widthPx),
            )
            twoLineHeadlinePages(line, layout)
        }
        LaunchedEffect(line, pages.size) {
            page = 0
            if (pages.size <= 1) return@LaunchedEffect
            while (true) {
                delay(HeadlineDwellMs)
                page = (page + 1) % pages.size
            }
        }
        AnimatedContent(
            targetState = page.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
            transitionSpec = {
                (slideInVertically(tween(420)) { it } + fadeIn(tween(280))) togetherWith
                    (slideOutVertically(tween(420)) { -it } + fadeOut(tween(280)))
            },
            label = "kaiHeadline",
        ) { index ->
            Text(
                text = pages.getOrElse(index) { line },
                style = textStyle,
                textAlign = textAlign,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun twoLineHeadlinePages(text: String, layout: TextLayoutResult): List<String> {
    if (layout.lineCount <= 2) return listOf(text)
    val pages = ArrayList<String>((layout.lineCount + 1) / 2)
    var line = 0
    while (line < layout.lineCount) {
        val start = layout.getLineStart(line)
        val last = (line + 1).coerceAtMost(layout.lineCount - 1)
        val end = layout.getLineEnd(last, visibleEnd = true).coerceIn(start, text.length)
        val chunk = text.substring(start, end).trim()
        if (chunk.isNotEmpty()) pages.add(chunk)
        line += 2
    }
    return pages.ifEmpty { listOf(text) }
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
    KaiContentCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KaiCharacter(
                state = state,
                mouthLevel = mouthLevel,
                speakingAs = speakingAs,
                size = 220.dp,
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
                    color = com.shopai.app.ui.theme.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
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
    KaiContentCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KaiCharacter(state = state, mouthLevel = mouthLevel, size = size, speakingAs = speakingAs)
            KaiSpeechBubble(
                line,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                background = false,
            )
        }
    }
}
