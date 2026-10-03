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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
 * Kai Chat — the owner's business conversation with KAI. Typed or spoken
 * (the mic), the same Kai answers from the owner's records (no AI service);
 * a spoken question is answered aloud too. Kai learns the owner's own words
 * right here in the chat — there is no separate language screen.
 */
@Composable
fun KaiChatScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenScanner: () -> Unit = {},
    onOpenMorningWork: (start: Boolean) -> Unit = {},
) {
    val session = container.kaiChat
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Edit of a draft: (message id, plan).
    var editing by remember { mutableStateOf<Pair<Long, com.shopai.app.brain.tools.ActionPlan>?>(null) }
    // Edit of a stock draft: (message id, draft key).
    var editingStock by remember { mutableStateOf<Pair<Long, String>?>(null) }
    // A new product's form (message id, what Kai knows, open the camera at once).
    var capture by remember { mutableStateOf<Triple<Long, com.shopai.app.brain.chat.StockPrefill, Boolean>?>(null) }

    /** Phone actions the owner asked for — Kai only opens them; the owner presses call. */
    fun perform(messageId: Long, action: com.shopai.app.brain.chat.KaiAction) {
        when (action) {
            is com.shopai.app.brain.chat.KaiAction.Dial -> runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + (action.phone ?: ""))))
            }
            com.shopai.app.brain.chat.KaiAction.OpenScanner -> onOpenScanner()
            com.shopai.app.brain.chat.KaiAction.OpenAlarmSettings -> runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + context.packageName)))
                }
            }
            com.shopai.app.brain.chat.KaiAction.OpenNotificationSettings -> runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
            is com.shopai.app.brain.chat.KaiAction.EditPlan -> session.plan(action.key)?.let { editing = messageId to it }
            is com.shopai.app.brain.chat.KaiAction.EditStock -> {
                if (session.stockDraft(action.key) != null) editingStock = messageId to action.key
            }
            // STOCK_IN_CAMERA: straight to the camera; the photo fills the form the owner checks.
            is com.shopai.app.brain.chat.KaiAction.OpenStockCamera -> capture = Triple(messageId, action.prefill, true)
            is com.shopai.app.brain.chat.KaiAction.CreateProduct -> capture = Triple(messageId, action.prefill, false)
            else -> Unit
        }
    }

    // Kai's answer asked for the bill scanner / product camera: it opens now (no extra tap).
    LaunchedEffect(session.pendingDirect) {
        session.takeDirect()?.let { (messageId, action) -> perform(messageId, action) }
    }

    fun tap(messageId: Long, action: com.shopai.app.brain.chat.KaiAction) {
        scope.launch { session.tap(messageId, action)?.let { perform(messageId, it) } }
    }

    // Opened from a reminder notification: Kai shows that reminder.
    val openReminder by com.shopai.app.notifications.KaiReminderInbox.pending.collectAsState()
    LaunchedEffect(openReminder) {
        com.shopai.app.notifications.KaiReminderInbox.take()?.let { session.showRang(it) }
    }
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

    /** Typed or spoken — the same Kai. A spoken question gets a spoken answer (and the text stays in the chat). */
    fun send(text: String, voice: Boolean = false) {
        val q = text.trim()
        if (q.isEmpty() || session.thinking) return
        input = ""
        scope.launch {
            session.send(q, voice)
            // Spoken only when the owner spoke; a typed message gets text only (same answer either way).
            session.messages.lastOrNull { !it.fromOwner }?.let { a ->
                com.shopai.app.brain.chat.KaiSpeech.forReply(a.text, voice)?.let { line ->
                    container.kaiBrain.say(com.shopai.app.brain.KaiReply(a.text, line.speech, line.languageTag, a.mood ?: com.shopai.app.brain.KaiMood.NEUTRAL))
                }
            }
        }
    }

    // Voice input: the phone's speech-to-text → the same send() as typing.
    val speech = remember { com.shopai.app.util.DeviceSpeechRecognizer(context) }
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { speech.stopListening() } }
    fun listen() {
        if (!speech.isAvailable()) return
        container.naturalTtsSpeaker.stop()
        listening = true
        heard = null
        speech.startListening(
            languageTag = "ta-IN",
            onPartialResult = { heard = it },
            onResult = { spoken ->
                listening = false
                heard = null
                send(spoken, voice = true)
            },
            onError = {
                listening = false
                heard = null
            },
        )
    }
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) listen() }
    fun onMic() {
        if (listening) {
            speech.stopListening()
            listening = false
            return
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) listen() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(session.messages.size) {
        if (session.messages.isNotEmpty()) listState.animateScrollToItem(session.messages.size)
    }

    editing?.let { (messageId, plan) ->
        EditPlanDialog(plan, onDismiss = { editing = null }) { name, amount, mode, outgoing ->
            editing = null
            scope.launch { session.revise(messageId, plan.key, name, amount, mode, outgoing) }
        }
    }

    editingStock?.let { (messageId, key) ->
        session.stockDraft(key)?.let { (name, qty, unit) ->
            StockEditDialog(name, qty, unit, onDismiss = { editingStock = null }) { q, u ->
                editingStock = null
                scope.launch { session.reviseStock(messageId, key, q, u) }
            }
        } ?: run { editingStock = null }
    }
    capture?.let { (messageId, prefill, openCamera) ->
        StockCaptureSheet(prefill, openCamera, onDismiss = { capture = null }) { form ->
            capture = null
            scope.launch { session.addProduct(messageId, form) }
        }
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
                Column(horizontalAlignment = Alignment.End) {
                    if (session.messages.isNotEmpty()) {
                        TextButton(onClick = { session.clear() }) { Text(stringResource(R.string.kai_chat_clear)) }
                    }
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
                    if (m.fromOwner) OwnerBubble(if (m.voice) "🎙 ${m.text}" else m.text) else {
                        KaiBubble(m.text)
                        m.card?.let { card -> KaiCardView(card, closed = m.cardClosed || session.thinking) { tap(m.id, it) } }
                        // Morning Work hand-over: "Start My Morning".
                        m.action?.let { action ->
                            AssistChip(
                                onClick = { onOpenMorningWork(action == KaiChatAction.START_MORNING_WORK) },
                                label = { Text(stringResource(R.string.morning_start), fontWeight = FontWeight.Bold, color = Primary) },
                            )
                        }
                    }
                }
                if (session.thinking) item { KaiBubble("…") }
            }

            if (listening) {
                Text(
                    heard ?: stringResource(R.string.kai_chat_listening),
                    color = Primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onMic() },
                    modifier = Modifier.padding(end = 6.dp).size(48.dp)
                        .background(if (listening) com.shopai.app.ui.theme.Danger else Primary.copy(alpha = 0.12f), CircleShape),
                ) {
                    Icon(
                        androidx.compose.material.icons.Icons.Filled.Mic,
                        contentDescription = stringResource(R.string.kai_chat_speak),
                        tint = if (listening) Color.White else Primary,
                    )
                }
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

/** The details of a draft / action under Kai's message, with its buttons. */
@Composable
private fun KaiCardView(card: com.shopai.app.brain.chat.KaiCard, closed: Boolean, onTap: (com.shopai.app.brain.chat.KaiAction) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(Primary.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
                .border(1.dp, Primary.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            card.lines.forEachIndexed { i, line ->
                Text(line, style = if (i == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal, color = ShopAiThemeColors.onSurface)
            }
            card.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = com.shopai.app.ui.theme.Danger) }
            card.buttons.forEach { b ->
                val enabled = b.enabled && !closed
                if (b.primary) {
                    androidx.compose.material3.Button(onClick = { onTap(b.action) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(b.label) }
                } else {
                    androidx.compose.material3.OutlinedButton(onClick = { onTap(b.action) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(b.label) }
                }
            }
        }
    }
}

/** Edit a draft before confirming: who, how much, money given or received, and how. */
@Composable
private fun EditPlanDialog(
    plan: com.shopai.app.brain.tools.ActionPlan,
    onDismiss: () -> Unit,
    onSave: (name: String, amount: java.math.BigDecimal, mode: com.shopai.app.books.model.PaymentMode, outgoing: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(plan.partyName) }
    var amount by remember { mutableStateOf(plan.amount.stripTrailingZeros().toPlainString()) }
    var mode by remember { mutableStateOf(plan.mode) }
    val outgoingAtStart = plan.kind == com.shopai.app.brain.tools.PlanKind.PAYMENT_OUT || plan.kind == com.shopai.app.brain.tools.PlanKind.CREDIT_GIVEN
    var outgoing by remember { mutableStateOf(outgoingAtStart) }
    val parsed = amount.replace(",", "").toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kai_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.kai_edit_name)) }, singleLine = true)
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    label = { Text(stringResource(R.string.kai_edit_amount)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    isError = parsed == null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(selected = outgoing, onClick = { outgoing = true }, label = { Text(stringResource(R.string.kai_edit_given)) })
                    androidx.compose.material3.FilterChip(selected = !outgoing, onClick = { outgoing = false }, label = { Text(stringResource(R.string.kai_edit_received)) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(com.shopai.app.books.model.PaymentMode.CASH to "Cash", com.shopai.app.books.model.PaymentMode.UPI to "UPI", com.shopai.app.books.model.PaymentMode.BANK_TRANSFER to "Bank").forEach { (m, label) ->
                        androidx.compose.material3.FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let { onSave(name, it, mode, outgoing) } }, enabled = parsed != null && name.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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
