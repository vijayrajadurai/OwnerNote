package com.shopai.app.ui.kaichat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.kai.KaiCharacter
import com.shopai.app.ui.kai.KaiState
import com.shopai.app.ui.kai.state
import com.shopai.app.ui.theme.Primary
import com.shopai.app.ui.theme.ShopAiThemeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Kai Chat — the owner's business conversation with KAI. The owner types or
 * uses the keyboard's own microphone; KAI's own Business Brain answers from
 * the owner's records, in text (no voice, no AI service). Messages stay.
 */
@Composable
fun KaiChatScreen(container: AppContainer, onBack: () -> Unit) {
    val session = container.kaiChat
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // KAI's animation: thinking → answering (mouth moves, no voice) → mood → idle.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.lastReplyAt, session.thinking) {
        val until = session.lastReplyAt + 6_000
        while (System.currentTimeMillis() < until || session.thinking) {
            now = System.currentTimeMillis()
            delay(80)
        }
        now = System.currentTimeMillis()
    }
    val sinceReply = now - session.lastReplyAt
    val kaiState = when {
        session.thinking -> KaiState.PROCESSING
        session.lastReplyAt > 0 && sinceReply < 1_800 -> KaiState.SPEAKING
        session.lastReplyAt > 0 && sinceReply < 5_500 -> session.lastMood?.state() ?: KaiState.IDLE
        else -> KaiState.IDLE
    }
    val mouth = if (kaiState == KaiState.SPEAKING) {
        val t = sinceReply / 1000.0
        (abs(sin(t * PI * 4.6)) * (0.55 + 0.45 * sin(t * PI * 0.9 + 1.3))).toFloat().coerceIn(0f, 1f)
    } else 0f

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || session.thinking) return
        input = ""
        scope.launch { session.send(q) }
    }

    LaunchedEffect(session.messages.size) {
        if (session.messages.isNotEmpty()) listState.animateScrollToItem(session.messages.size)
    }

    DetailScaffold(title = stringResource(R.string.kai_chat_title), onBack = onBack) { contentModifier ->
        Column(modifier = contentModifier.fillMaxSize().imePadding()) {
            // KAI, live, with who he is.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                KaiCharacter(
                    state = kaiState,
                    mouthLevel = mouth,
                    speakingAs = session.lastMood?.state(),
                    size = 120.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Kai", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface)
                    Text(stringResource(R.string.kai_chat_subtitle), color = ShopAiThemeColors.onSurfaceVariant)
                    Text(
                        stringResource(R.string.kai_chat_mic_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = ShopAiThemeColors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (session.messages.isNotEmpty()) {
                    TextButton(onClick = { session.clear() }) { Text(stringResource(R.string.kai_chat_clear)) }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { KaiBubble(stringResource(R.string.kai_chat_hello)) }
                if (session.messages.isEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                            for (s in listOf(R.string.kai_chat_suggest_1, R.string.kai_chat_suggest_2, R.string.kai_chat_suggest_3, R.string.kai_chat_suggest_4)) {
                                val text = stringResource(s)
                                AssistChip(onClick = { send(text) }, label = { Text(text) })
                            }
                        }
                    }
                }
                items(session.messages, key = { it.id }) { m ->
                    if (m.fromOwner) OwnerBubble(m.text) else KaiBubble(m.text)
                }
                if (session.thinking) item { KaiBubble("…") }
            }

            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(stringResource(R.string.kai_chat_hint)) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(input) }),
                )
                IconButton(
                    onClick = { send(input) },
                    enabled = input.isNotBlank() && !session.thinking,
                    modifier = Modifier.padding(start = 6.dp).size(48.dp).background(if (input.isNotBlank()) Primary else Primary.copy(alpha = 0.35f), CircleShape),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.kai_chat_send), tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun OwnerBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(Primary, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun KaiBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .background(Color.White, RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                .border(1.dp, Primary.copy(alpha = 0.18f), RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text("Kai", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Primary)
            Text(text, style = MaterialTheme.typography.bodyLarge, color = ShopAiThemeColors.onSurface)
        }
    }
}

/** Home entry point: "Ask Kai — Owner, enna theriyanum?" → Kai Chat. */
@Composable
fun AskKaiCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(20.dp))
            .border(1.dp, Primary.copy(alpha = 0.22f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).background(Primary.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = Primary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.kai_chat_entry_title), fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface)
            Text(stringResource(R.string.kai_chat_entry_body), style = MaterialTheme.typography.bodyMedium, color = ShopAiThemeColors.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = Primary)
    }
}
