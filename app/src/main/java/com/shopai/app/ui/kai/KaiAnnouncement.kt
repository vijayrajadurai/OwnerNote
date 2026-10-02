package com.shopai.app.ui.kai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.brain.KaiBrain
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.theme.Primary
import com.shopai.app.ui.theme.ShopAiThemeColors
import kotlinx.coroutines.delay

/**
 * Kai talking to the owner outside a conversation — after a manual entry,
 * a bill or a handwritten note, a reminder or an insight. It is not a
 * notification that flashes by:
 *
 *   SPEAKING (lip-synced) → RESPONSE_VISIBLE (Kai's reaction, text readable)
 *   → IDLE → fades out
 *
 * At least 7 s, longer while Kai is still talking (to a limit); tap to keep
 * it (pinned), ✕ to close. A newer reply replaces the old one smoothly. The
 * timing is a state checked a few times a second — nothing blocks the app,
 * and only the card itself takes touches.
 */
@Composable
fun KaiAnnouncementOverlay(container: AppContainer, modifier: Modifier = Modifier) {
    val brain = container.kaiBrain
    val announcement by brain.announcement.collectAsState()
    val speakingNow by container.naturalTtsSpeaker.speaking.collectAsState()
    val mouth by container.naturalTtsSpeaker.mouthLevel.collectAsState()

    // The one on screen (kept while it fades out after the flow is cleared).
    var shown by remember { mutableStateOf<KaiBrain.Announcement?>(null) }
    var phase by remember { mutableStateOf(KaiResponsePhase.HIDDEN) }
    var pinned by remember { mutableStateOf(false) }
    var speechStarted by remember { mutableStateOf(false) }
    var speechEndedAt by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Closed by the owner (✕): stays closed.
    var closedId by remember { mutableStateOf<Long?>(null) }

    // A new announcement replaces the current one.
    LaunchedEffect(announcement?.id) {
        val next = announcement ?: return@LaunchedEffect
        shown = next
        pinned = false
        speechStarted = false
        speechEndedAt = null
        phase = KaiResponsePhase.RESPONSE_VISIBLE
    }
    // Track this announcement's speech: it started, then it ended.
    LaunchedEffect(speakingNow, shown?.id) {
        if (shown == null) return@LaunchedEffect
        if (speakingNow) speechStarted = true
        else if (speechStarted && speechEndedAt == null) speechEndedAt = System.currentTimeMillis()
    }
    // The clock: re-evaluates the phase a few times a second while something is shown.
    LaunchedEffect(shown?.id, pinned) {
        val current = shown ?: return@LaunchedEffect
        while (closedId != current.id) {
            now = System.currentTimeMillis()
            phase = KaiResponseTiming.phase(now, current.startedAt, speakingNow && speechStarted, speechEndedAt, pinned)
            if (phase == KaiResponsePhase.HIDDEN) {
                if (speakingNow) container.naturalTtsSpeaker.stop()
                brain.dismissAnnouncement(current.id)
                break
            }
            delay(200)
        }
    }

    AnimatedVisibility(
        visible = shown != null && phase != KaiResponsePhase.HIDDEN && shown?.id != closedId,
        enter = slideInVertically(tween(320)) { -it } + fadeIn(tween(320)),
        exit = slideOutVertically(tween(420)) { -it / 2 } + fadeOut(tween(420)),
        modifier = modifier,
    ) {
        val current = shown ?: return@AnimatedVisibility
        val kaiState = when (phase) {
            KaiResponsePhase.SPEAKING -> KaiState.SPEAKING
            KaiResponsePhase.RESPONSE_VISIBLE -> current.reply.mood.state()
            else -> KaiState.IDLE
        }
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(22.dp))
                .background(Color.White, RoundedCornerShape(22.dp))
                .border(1.dp, Primary.copy(alpha = if (pinned) 0.55f else 0.18f), RoundedCornerShape(22.dp))
                // Tap: keep it on screen longer.
                .clickable { pinned = true }
                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KaiCharacter(
                state = kaiState,
                mouthLevel = if (phase == KaiResponsePhase.SPEAKING) mouth else 0f,
                speakingAs = current.reply.mood.state(),
                size = 96.dp,
            )
            // A newer reply cross-fades in place of the old one.
            AnimatedContent(
                targetState = current,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(200)) },
                contentKey = { it.id },
                label = "kaiReply",
                modifier = Modifier.weight(1f),
            ) { a ->
                Column {
                    Text(
                        a.reply.display,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = ShopAiThemeColors.onSurface,
                    )
                    if (pinned) {
                        Text(
                            stringResource(R.string.kai_pinned_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = ShopAiThemeColors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            Box(contentAlignment = Alignment.TopEnd) {
                IconButton(onClick = {
                    container.naturalTtsSpeaker.stop()
                    brain.dismissAnnouncement(current.id)
                    closedId = current.id
                }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.capture_close), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
