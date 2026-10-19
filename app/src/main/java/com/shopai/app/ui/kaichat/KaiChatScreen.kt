package com.shopai.app.ui.kaichat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.GreenUi
import com.shopai.app.ui.kai.KaiCharacter
import com.shopai.app.ui.kai.KaiContentCard
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
    /** A record from Kai's Morning Work card: CUSTOMER / SUPPLIER / PRODUCT (with id), INVENTORY, REMINDERS. */
    onOpenRecord: (kind: String, id: String) -> Unit = { _, _ -> },
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
            // Android 14+: "Allow full-screen alerts" so Kai Urgent Action Mode can appear over the lock screen.
            com.shopai.app.brain.chat.KaiAction.OpenFullScreenSettings -> runCatching {
                context.startActivity(com.shopai.app.notifications.KaiReminderEngine.fullScreenSettingsIntent(context))
            }
            is com.shopai.app.brain.chat.KaiAction.EditPlan -> session.plan(action.key)?.let { editing = messageId to it }
            is com.shopai.app.brain.chat.KaiAction.EditStock -> {
                if (session.stockDraft(action.key) != null) editingStock = messageId to action.key
            }
            // STOCK_IN_CAMERA: straight to the camera; the photo fills the form the owner checks.
            is com.shopai.app.brain.chat.KaiAction.OpenStockCamera -> capture = Triple(messageId, action.prefill, true)
            is com.shopai.app.brain.chat.KaiAction.CreateProduct -> capture = Triple(messageId, action.prefill, false)
            // Morning Work (from Kai's MORNING_WORK answer): the guided screen, or the record behind a task.
            is com.shopai.app.brain.chat.KaiAction.OpenMorningWork -> onOpenMorningWork(action.start)
            is com.shopai.app.brain.chat.KaiAction.OpenRecord -> onOpenRecord(action.kind, action.id)
            // "Kumar statement WhatsApp-la anuppu": the PDF is made here from the books, then WhatsApp opens with it (the owner sends).
            is com.shopai.app.brain.chat.KaiAction.ShareStatement -> {
                val lang = session.messages.firstOrNull { it.id == messageId }?.lang ?: com.shopai.app.brain.KaiLang.TANGLISH
                scope.launch {
                    val shared = runCatching { shareStatement(container, context, action, lang) }
                    if (shared.isFailure) android.widget.Toast.makeText(context, pdfFailed(lang), android.widget.Toast.LENGTH_LONG).show()
                }
            }
            // "innaikku sales PDF kudu" / Day close: that day's Daily Sales Note as a PDF, made here, then the share sheet (the owner sends).
            is com.shopai.app.brain.chat.KaiAction.ShareCashNote -> {
                val lang = session.messages.firstOrNull { it.id == messageId }?.lang ?: com.shopai.app.brain.KaiLang.TANGLISH
                scope.launch {
                    val shared = runCatching { shareCashNote(container, context, action.day, action.to) }
                    if (shared.isFailure) android.widget.Toast.makeText(context, pdfFailed(lang), android.widget.Toast.LENGTH_LONG).show()
                }
            }
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
    // Kai's hand-note follows the mic: listening only while the recognizer really records.
    LaunchedEffect(listening) { session.listening(listening) }
    // The owner's shop category (business profile) picks the scene behind Kai's notebook; unknown → a neutral counter.
    var shopCategory by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { shopCategory = runCatching { container.businessRepository.getMyBusiness()?.category }.getOrNull() }
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
                // The recognizer gave up (silence, no final match) after it had already heard words: those words go to Kai —
                // never dropped (owner, 18 Oct 2026: "mic-la sonna varamatikuthu").
                val partial = heard?.trim()
                listening = false
                heard = null
                if (!partial.isNullOrEmpty()) send(partial, voice = true)
            },
        )
    }
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) listen() }
    fun onMic() {
        if (listening) {
            // The owner tapped the mic to finish: what was heard so far is sent (stopping the recognizer drops its final result).
            val partial = heard?.trim()
            speech.stopListening()
            listening = false
            heard = null
            if (!partial.isNullOrEmpty()) send(partial, voice = true)
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

    // Kai's hand-note, full screen while he writes and the draft waits (owner, 17 Oct 2026): closes by itself a moment after it is
    // saved or cancelled, or with its ✕; the draft stays in the chat as before.
    var fullNoteClosed by remember { mutableStateOf<Long?>(null) }
    val fullNote = session.note.note?.takeIf { it.messageId != fullNoteClosed }
    LaunchedEffect(session.note.noteState, fullNote?.messageId) {
        val id = fullNote?.messageId ?: return@LaunchedEffect
        if (session.note.noteState == com.shopai.app.brain.note.KaiNoteState.SAVE_SUCCESS || session.note.noteState == com.shopai.app.brain.note.KaiNoteState.CANCELLED ||
            session.note.noteState == com.shopai.app.brain.note.KaiNoteState.LISTED) {
            delay(1800)
            fullNoteClosed = id
        }
    }
    fullNote?.let { n ->
        val m = session.messages.firstOrNull { it.id == n.messageId }
        val card = m?.card
        if (m != null && card != null) androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullNoteClosed = n.messageId },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                com.shopai.app.ui.kai.KaiNoteScene(
                    flow = session.note, note = n, card = card, category = shopCategory,
                    closed = m.cardClosed || session.thinking, onWritten = { session.noteWritten() },
                    onTap = { tap(m.id, it) }, modifier = Modifier.fillMaxSize().padding(bottom = 16.dp),
                    said = ownerLineOf(session.messages, m.id), fullScreen = true, onClose = { fullNoteClosed = n.messageId },
                )
            }
        }
    }

    DetailScaffold(title = stringResource(R.string.kai_chat_title), onBack = onBack) { contentModifier ->
        Column(modifier = contentModifier.fillMaxSize().imePadding()) {
            // The green UI (owner, 19 Oct 2026): Kai on a soft green tile, no "digital" look.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(GreenUi.Tile)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        Text("Kai", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, color = GreenUi.Ink)
                        Text(stringResource(R.string.kai_chat_subtitle), color = GreenPositive)
                        Text(
                            stringResource(R.string.kai_chat_mic_tip),
                            style = MaterialTheme.typography.bodySmall,
                            color = com.shopai.app.ui.theme.TextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (session.messages.isNotEmpty()) {
                            TextButton(onClick = { session.clear() }) { Text(stringResource(R.string.kai_chat_clear), color = GreenUi.Ink) }
                        }
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
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            for (s in listOf(R.string.kai_chat_suggest_1, R.string.kai_chat_suggest_2, R.string.kai_chat_suggest_3, R.string.kai_chat_suggest_4)) {
                                val text = stringResource(s)
                                Text(
                                    text,
                                    fontSize = 14.sp,
                                    color = GreenUi.Ink,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .border(1.dp, GreenBorder, RoundedCornerShape(18.dp))
                                        .clickable { send(text) }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
                items(session.messages, key = { it.id }) { m ->
                    val note = session.note.note?.takeIf { it.messageId == m.id }
                    if (m.fromOwner) OwnerBubble(if (m.voice) "🎙 ${m.text}" else m.text)
                    // Kai asking the one thing he needs: a clear question card (owner, 17 Oct 2026).
                    else if (note == null && m.mood == com.shopai.app.brain.KaiMood.CLARIFY) com.shopai.app.ui.kai.KaiAskCard(
                        question = m.text, card = m.card, lang = m.lang, closed = m.cardClosed || session.thinking, onTap = { tap(m.id, it) },
                        progress = m.ask,
                        // The mic on the newest question: answer it by voice.
                        onMic = if (m.id == session.messages.lastOrNull()?.id && !listening) ({ onMic() }) else null,
                    ) else {
                        KaiBubble(m.text)
                        m.card?.let { card ->
                            // The draft waiting for Confirm (and how it ended): Kai's notebook in the shop; older cards as before.
                            if (note != null) com.shopai.app.ui.kai.KaiNoteScene(
                                flow = session.note, note = note, card = card, category = shopCategory,
                                closed = m.cardClosed || session.thinking, onWritten = { session.noteWritten() },
                                onTap = { tap(m.id, it) }, modifier = Modifier.padding(top = 4.dp),
                                said = ownerLineOf(session.messages, m.id),
                            ) else if (card.stock != null) com.shopai.app.ui.kai.KaiStockCard(card.stock, m.lang, shopCategory)
                            else KaiCardView(card, closed = m.cardClosed || session.thinking) { tap(m.id, it) }
                        }
                    }
                }
                if (session.thinking) item { KaiBubble("…") }
            }

            if (listening) {
                Text(
                    heard ?: stringResource(R.string.kai_chat_listening),
                    color = GreenPositive,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp),
                )
            }
            // The input: one white pill — type, or the round mic on the right (same Kai either way); send shows when there is text.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp)
                    .shadow(14.dp, RoundedCornerShape(32.dp), ambientColor = GreenUi.Brand.copy(alpha = 0.18f), spotColor = GreenUi.Brand.copy(alpha = 0.18f))
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color.White)
                    .padding(start = 20.dp, end = 7.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = GreenUi.Ink),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(GreenUi.Brand),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(input) }),
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (input.isEmpty()) Text(stringResource(R.string.kai_chat_hint), color = GreenUi.Muted, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            field()
                        }
                    },
                )
                if (input.isNotBlank()) IconButton(
                    onClick = { send(input) },
                    enabled = !session.thinking,
                    modifier = Modifier.size(44.dp).border(1.dp, GreenBorder, CircleShape),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.kai_chat_send), tint = GreenUi.Brand)
                }
                IconButton(
                    onClick = { onMic() },
                    modifier = Modifier.size(52.dp)
                        .background(if (listening) com.shopai.app.ui.theme.Danger else GreenUi.Brand, CircleShape),
                ) {
                    Icon(
                        androidx.compose.material.icons.Icons.Filled.Mic,
                        contentDescription = stringResource(R.string.kai_chat_speak),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * The owner's line a draft came from: the first of the owner's lines since Kai last answered without a question ("vengayam
 * 10kg" → "50" → "60" is "vengayam 10kg", not the last answer "60").
 */
private fun ownerLineOf(messages: List<KaiChatMessage>, kaiMessageId: Long): String? {
    var j = messages.indexOfFirst { it.id == kaiMessageId } - 1
    var first: String? = null
    while (j >= 0) {
        val x = messages[j]
        if (x.fromOwner) first = x.text else if (x.mood != com.shopai.app.brain.KaiMood.CLARIFY) break
        j--
    }
    return first
}

/**
 * The details of a draft / action under Kai's message, with its buttons — iPhone style (owner, 18 Oct 2026): a white card, each line a
 * row ("Colgate — 1150 PCS" → the name left, the value right, bold), the main button a black pill, Cancel in red, any other in blue.
 */
@Composable
private fun KaiCardView(card: com.shopai.app.brain.chat.KaiCard, closed: Boolean, onTap: (com.shopai.app.brain.chat.KaiAction) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(GreenUi.Tile)
                .padding(vertical = 8.dp),
        ) {
            card.lines.forEachIndexed { i, line ->
                if (i > 0) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(GreenBorder.copy(alpha = 0.6f)))
                val parts = line.split(" — ", limit = 2)
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (parts.size == 2) {
                        Text(parts[0], fontSize = 15.sp, color = com.shopai.app.ui.kai.IosLabel, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f).padding(end = 12.dp))
                        Text(parts[1], fontSize = 15.sp, color = com.shopai.app.ui.kai.IosLabel, fontWeight = FontWeight.SemiBold)
                    } else Text(line, fontSize = 15.sp, color = com.shopai.app.ui.kai.IosLabel)
                }
            }
            card.warning?.let { Text(it, fontSize = 13.sp, color = com.shopai.app.ui.kai.IosRed, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            if (card.buttons.isNotEmpty()) Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                card.buttons.forEach { b ->
                    val enabled = b.enabled && !closed
                    if (b.primary) Box(
                        Modifier.fillMaxWidth().height(48.dp).alpha(if (enabled) 1f else 0.3f).clip(RoundedCornerShape(24.dp)).background(GreenUi.Brand)
                            .clickable(enabled = enabled) { onTap(b.action) },
                        contentAlignment = Alignment.Center,
                    ) { Text(b.label, color = GreenUi.OnBrand, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                    else Text(
                        b.label,
                        color = when { !enabled -> com.shopai.app.ui.kai.IosGrey; com.shopai.app.ui.kai.isCancel(b.action) -> com.shopai.app.ui.kai.IosRed; else -> GreenUi.Ink },
                        fontSize = 16.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, GreenBorder, RoundedCornerShape(24.dp))
                            .clickable(enabled = enabled) { onTap(b.action) }.padding(vertical = 11.dp),
                    )
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
            color = GreenUi.OnBrand,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(GreenUi.Brand, RoundedCornerShape(24.dp, 24.dp, 8.dp, 24.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun KaiBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(24.dp, 24.dp, 24.dp, 8.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("Kai", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = GreenPositive)
            Text(text, style = MaterialTheme.typography.bodyLarge, color = GreenUi.Ink)
        }
    }
}

/** The green UI's two extra tones here: Kai's green (his name, "ready") and the soft outline of pills. */
private val GreenPositive = Color(0xFF1F6B3E)
private val GreenBorder = Color(0xFFB9CDBD)
