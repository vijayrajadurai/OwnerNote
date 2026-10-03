package com.shopai.app.ui.morning

import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.shopai.app.R
import com.shopai.app.brain.KaiLanguage
import com.shopai.app.brain.KaiReply
import com.shopai.app.brain.morning.MorningCommand
import com.shopai.app.brain.morning.MorningEffect
import com.shopai.app.brain.morning.MorningPartyKind
import com.shopai.app.brain.morning.MorningPriority
import com.shopai.app.brain.morning.MorningReply
import com.shopai.app.brain.morning.MorningResult
import com.shopai.app.brain.morning.MorningState
import com.shopai.app.brain.morning.MorningTarget
import com.shopai.app.brain.morning.MorningTask
import com.shopai.app.brain.morning.MorningTaskStatus
import com.shopai.app.brain.morning.MorningTaskType
import com.shopai.app.brain.morning.MorningTime
import com.shopai.app.brain.morning.ResponseMode
import com.shopai.app.brain.morning.SkipReason
import com.shopai.app.data.AppContainer
import com.shopai.app.data.morning.MorningWorkActions
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.components.OutlinedButton
import com.shopai.app.ui.components.PrimaryButton
import com.shopai.app.ui.components.ShopCard
import com.shopai.app.ui.navigation.Routes
import com.shopai.app.ui.theme.Danger
import com.shopai.app.ui.theme.Pressure
import com.shopai.app.ui.theme.Primary
import com.shopai.app.ui.theme.PrimaryLight
import com.shopai.app.ui.theme.ShopAiThemeColors
import com.shopai.app.ui.theme.Warning
import com.shopai.app.util.DeviceSpeechRecognizer
import com.shopai.app.util.formatInr
import com.shopai.app.util.formatQty
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * KAI — Do My Morning Work. The owner sees today's important work, starts
 * it, and goes through each task by tapping, typing or talking. Everything
 * goes through the one Morning Work engine; this screen only shows its
 * state, speaks its replies in voice mode, and runs the effects it asks for
 * (dialer, WhatsApp, reminder, a screen, a confirmed payment) through the
 * app's existing integrations.
 */
@Composable
fun MorningWorkScreen(
    container: AppContainer,
    startInVoice: Boolean,
    autoStart: Boolean,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val engine = container.morningWork
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf(engine.state) }
    var loading by remember { mutableStateOf(true) }
    var kaiLine by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf<String?>(null) }
    var autoListen by remember { mutableStateOf(startInVoice) }
    var remindFor by remember { mutableStateOf<String?>(null) }
    var skipFor by remember { mutableStateOf<String?>(null) }
    var showSummary by remember { mutableStateOf(false) }
    var whatsAppText by remember { mutableStateOf("") }
    var accountName by remember { mutableStateOf<String?>(null) }
    var amountText by remember { mutableStateOf("") }
    var referenceText by remember { mutableStateOf("") }
    var resumed by remember { mutableStateOf(true) }
    // Kai's next words after the dialer / WhatsApp: said when the owner comes back.
    var afterReturn by remember { mutableStateOf<MorningReply?>(null) }
    val speech = remember { DeviceSpeechRecognizer(context) }
    val needsBooks = stringResource(R.string.morning_payment_needs_books)
    val noData = stringResource(R.string.morning_no_business)

    lateinit var listen: () -> Unit

    fun sync() {
        ui = engine.state
        ui.whatsApp?.let { if (whatsAppText.isEmpty()) whatsAppText = it.message } ?: run { whatsAppText = "" }
        ui.paymentDraft?.let { d ->
            if (amountText.isEmpty()) amountText = formatQty(d.amount)
        } ?: run {
            amountText = ""
            referenceText = ""
        }
    }

    fun say(reply: MorningReply) {
        if (reply.line.display.isNotBlank()) kaiLine = reply.line.display
        if (reply.speak) {
            container.kaiBrain.say(KaiReply(reply.line.display, reply.line.speech, reply.line.speechLanguage, reply.line.mood)) {
                // Voice mode: listen again for the owner's answer (hands-free), until the work is done.
                val s = engine.state
                if (autoListen && resumed && s.mode == ResponseMode.VOICE && s.started && !s.finished && remindFor == null && skipFor == null) {
                    scope.launch { listen() }
                }
            }
        }
    }

    suspend fun refresh(greet: Boolean): MorningReply? {
        val snap = container.morningSources.snapshot() ?: return null
        return engine.prepare(snap, greet = greet)
    }

    lateinit var deliver: suspend (MorningReply) -> Unit
    deliver = { reply ->
        say(reply)
        for (effect in reply.effects) {
            when (effect) {
                is MorningEffect.OpenDialer -> {
                    val ok = MorningWorkActions.openDialer(context, effect.phone)
                    val next = engine.onResult(if (ok) MorningResult.DialerOpened(effect.taskId) else MorningResult.ActionFailed(effect.taskId, "CALL"))
                    if (ok) afterReturn = next else deliver(next)
                }
                is MorningEffect.WhatsAppDraft -> whatsAppText = effect.message
                is MorningEffect.OpenWhatsApp -> {
                    val ok = MorningWorkActions.openWhatsApp(context, effect.phone, effect.message)
                    val next = engine.onResult(if (ok) MorningResult.WhatsAppOpened(effect.taskId) else MorningResult.ActionFailed(effect.taskId, "WHATSAPP"))
                    if (ok) afterReturn = next else deliver(next)
                }
                is MorningEffect.CreateReminder -> {
                    val result = MorningWorkActions.createReminder(container.reminderRepository, container.reminderAlarms, effect.taskId, effect.title, effect.atMillis)
                    deliver(engine.onResult(result))
                }
                is MorningEffect.Open -> {
                    engine.onResult(MorningResult.Opened(effect.taskId))
                    onNavigate(routeFor(effect.target, container.books.session() != null))
                }
                is MorningEffect.ReviewPayment -> {
                    amountText = formatQty(effect.draft.amount)
                    accountName = MorningWorkActions.accountName(container.books, effect.draft.mode)
                }
                is MorningEffect.PostPayment -> {
                    val result = MorningWorkActions.postPayment(container.books, effect.draft, spoken = engine.state.mode == ResponseMode.VOICE)
                    if (result == null) {
                        // Books not set up yet: the party page records payments in the old ledger.
                        deliver(engine.onResult(MorningResult.PaymentRejected(effect.draft.id, needsBooks)))
                        onNavigate(if (effect.draft.partyKind == MorningPartyKind.CUSTOMER) Routes.customerDetail(effect.draft.partyId) else Routes.supplierDetail(effect.draft.partyId))
                    } else {
                        if (result is MorningResult.PaymentPosted) {
                            container.kaiBrain.forget()
                            container.kaiBooks.forget()
                        }
                        deliver(engine.onResult(result))
                        if (result is MorningResult.PaymentPosted) refresh(greet = false)?.let { deliver(it) }
                    }
                }
                is MorningEffect.ShowRemindOptions -> remindFor = effect.taskId
                MorningEffect.ShowSummary -> showSummary = true
                MorningEffect.Completed -> Unit
            }
        }
        sync()
    }

    fun go(block: suspend () -> MorningReply) {
        scope.launch { deliver(block()) }
    }

    fun handleText(text: String, mode: ResponseMode) {
        val t = text.trim()
        if (t.isEmpty()) return
        input = ""
        go { engine.handle(t, mode) }
    }

    listen = {
        if (!speech.isAvailable()) {
            autoListen = false
        } else {
            container.naturalTtsSpeaker.stop()
            listening = true
            partial = null
            speech.startListening(
                languageTag = "ta-IN",
                onPartialResult = { partial = it },
                onResult = { spoken ->
                    listening = false
                    partial = null
                    handleText(spoken, ResponseMode.VOICE)
                },
                onError = { code ->
                    listening = false
                    partial = null
                    // Silence is never an answer: stop listening hands-free until the owner taps the mic.
                    if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) autoListen = false
                },
            )
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            autoListen = true
            listen()
        }
    }

    fun onMic() {
        if (listening) {
            speech.stopListening()
            listening = false
            autoListen = false
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            autoListen = true
            listen()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        engine.role = container.morningSources.role()
        val snap = container.morningSources.snapshot()
        if (snap == null) {
            kaiLine = noData
            loading = false
            return@LaunchedEffect
        }
        val started = engine.state.started && engine.state.plan?.businessId == snap.businessId
        val greeting = engine.prepare(
            snap,
            mode = if (started) null else if (startInVoice) ResponseMode.VOICE else ResponseMode.TEXT,
            lang = if (started) null else KaiLanguage.forAppLocale().let { if (it == com.shopai.app.brain.KaiLang.TAMIL) it else com.shopai.app.brain.KaiLang.TANGLISH },
        )
        loading = false
        if (autoStart && !started) {
            kaiLine = greeting.line.display
            deliver(engine.act(MorningCommand.Start))
        } else {
            deliver(greeting)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    scope.launch {
                        val waiting = afterReturn
                        afterReturn = null
                        if (waiting != null) deliver(waiting)
                        // Back from a payment / party screen: the books may have changed.
                        if (!loading) refresh(greet = false)?.let { if (it.line.display.isNotBlank() || it.effects.isNotEmpty()) deliver(it) else sync() }
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    speech.stopListening()
                    listening = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            speech.stopListening()
        }
    }

    DetailScaffold(title = stringResource(R.string.morning_work_title), onBack = onBack) { modifier ->
        Column(modifier = modifier.fillMaxSize().imePadding()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { Header(ui) }
                kaiLine?.let { line -> item { KaiSays(line) } }
                if (loading) {
                    item { Text(stringResource(R.string.morning_loading), color = ShopAiThemeColors.onSurfaceVariant) }
                }
                val plan = ui.plan
                if (plan != null) {
                    item { TodayCount(ui) }
                    if (!ui.started && plan.openTasks.isNotEmpty()) {
                        item { PrimaryButton(stringResource(R.string.morning_start), { go { engine.act(MorningCommand.Start) } }, modifier = Modifier.fillMaxWidth()) }
                    }
                    ui.paymentDraft?.let { d ->
                        item {
                            PaymentReview(
                                partyName = d.partyName,
                                outgoing = d.outgoing,
                                pending = d.pending,
                                mode = d.mode,
                                account = accountName,
                                amount = amountText,
                                reference = referenceText,
                                onAmount = { v ->
                                    amountText = v
                                    scope.launch { engine.editPaymentDraft(d.mode, referenceText, v.toDoubleOrNull()); sync() }
                                },
                                onReference = { v ->
                                    referenceText = v
                                    scope.launch { engine.editPaymentDraft(d.mode, v, amountText.toDoubleOrNull()); sync() }
                                },
                                onMode = { m ->
                                    scope.launch {
                                        engine.editPaymentDraft(m, referenceText, amountText.toDoubleOrNull())
                                        accountName = MorningWorkActions.accountName(container.books, m)
                                        sync()
                                    }
                                },
                                onConfirm = { go { engine.act(MorningCommand.Confirm) } },
                                onCancel = { go { engine.act(MorningCommand.Cancel) } },
                            )
                        }
                    }
                    ui.whatsApp?.let { w ->
                        item {
                            WhatsAppCard(
                                name = w.name,
                                text = whatsAppText,
                                onText = { whatsAppText = it },
                                onSend = { go { engine.sendWhatsApp(whatsAppText) } },
                                onCancel = { go { engine.act(MorningCommand.Cancel) } },
                            )
                        }
                    }
                    val current = ui.current
                    if (ui.started && current != null && current.open() && !ui.finished) {
                        item {
                            TaskCard(
                                task = current,
                                position = plan.indexOf(current) + 1,
                                total = plan.tasks.size,
                                guided = true,
                                onAction = { cmd -> go { engine.act(cmd, current.taskId) } },
                                onSkip = { skipFor = current.taskId },
                                onRemind = { remindFor = current.taskId },
                            )
                        }
                    }
                    if (ui.finished && plan.tasks.isNotEmpty()) {
                        item { Completion(ui) { go { engine.act(MorningCommand.Summary) } } }
                    }
                    val reminders = plan.tasks.count { it.taskType == MorningTaskType.REMINDER }
                    items(plan.tasks.filter { !(ui.started && it.taskId == current?.taskId && !ui.finished) }, key = { it.taskId }) { t ->
                        TaskCard(
                            task = t,
                            position = plan.indexOf(t) + 1,
                            total = plan.tasks.size,
                            guided = false,
                            onAction = { cmd -> go { engine.act(cmd, t.taskId) } },
                            onSkip = { skipFor = t.taskId },
                            onRemind = { remindFor = t.taskId },
                        )
                    }
                    if (reminders > 0) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.morning_reminders_today, reminders), modifier = Modifier.weight(1f), color = ShopAiThemeColors.onSurface)
                                TextButton(onClick = { onNavigate(Routes.Reminders) }) { Text(stringResource(R.string.morning_view_reminders)) }
                            }
                        }
                    }
                    if (plan.candidateCount > plan.tasks.size) {
                        item {
                            Text(
                                stringResource(R.string.morning_more_tasks, plan.candidateCount - plan.tasks.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = ShopAiThemeColors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            InputBar(
                value = input,
                partial = partial,
                listening = listening,
                onValue = { input = it },
                onSend = { handleText(input, ResponseMode.TEXT) },
                onMic = ::onMic,
            )
        }
    }

    remindFor?.let { taskId ->
        RemindDialog(
            onPick = { at ->
                remindFor = null
                go { engine.act(MorningCommand.RemindLater(at), taskId) }
            },
            onDismiss = { remindFor = null },
        )
    }
    skipFor?.let { taskId ->
        SkipDialog(
            onPick = { reason ->
                skipFor = null
                go { engine.act(MorningCommand.Skip(reason), taskId) }
            },
            onDismiss = { skipFor = null },
        )
    }
    if (showSummary) ui.summary?.let { s -> SummaryDialog(s) { showSummary = false } }
}

private fun MorningTask.open() = status == MorningTaskStatus.PENDING || status == MorningTaskStatus.IN_PROGRESS

/** An existing OwnerNote screen for a Morning Work target. */
private fun routeFor(target: MorningTarget, booksReady: Boolean): String = when (target) {
    is MorningTarget.Customer -> Routes.customerDetail(target.id)
    is MorningTarget.Supplier -> Routes.supplierDetail(target.id)
    is MorningTarget.Product -> Routes.productDetail(target.id)
    is MorningTarget.AddPurchase -> if (booksReady) Routes.billEditor("PURCHASE", target.supplierId) else Routes.Inventory
    is MorningTarget.Payment -> when {
        booksReady -> Routes.payment(if (target.incoming) "IN" else "OUT", target.partyId)
        target.incoming -> Routes.customerDetail(target.partyId)
        else -> Routes.supplierDetail(target.partyId)
    }
    is MorningTarget.Draft -> when (target.kind.uppercase(Locale.ROOT)) {
        "SALE", "PURCHASE" -> Routes.billEditor(target.kind.uppercase(Locale.ROOT), draftId = target.id)
        else -> Routes.Billing
    }
    MorningTarget.Reminders -> Routes.Reminders
    MorningTarget.Inventory -> Routes.Inventory
}

// ------------------------------------------------------------------ pieces

@Composable
private fun Header(ui: MorningState) {
    val hour = LocalDateTime.now().hour
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(
                when {
                    hour < 12 -> R.string.morning_greeting_morning
                    hour < 17 -> R.string.morning_greeting_afternoon
                    else -> R.string.morning_greeting_evening
                },
            ),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = ShopAiThemeColors.onSurface,
        )
        Text(stringResource(R.string.morning_subtitle), color = ShopAiThemeColors.onSurfaceVariant)
        val plan = ui.plan
        if (plan != null && plan.offline) {
            val synced = plan.syncedAtMillis.takeIf { it > 0 }?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH))
            }
            Text(
                stringResource(R.string.morning_offline) + (synced?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = Pressure,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .background(Pressure.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun KaiSays(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(20.dp))
            .border(1.dp, Primary.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(34.dp).background(PrimaryLight, CircleShape), contentAlignment = Alignment.Center) {
            Text("K", fontWeight = FontWeight.Bold, color = Primary)
        }
        Column {
            Text("Kai", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Primary)
            Text(text, style = MaterialTheme.typography.bodyLarge, color = ShopAiThemeColors.onSurface)
        }
    }
}

@Composable
private fun TodayCount(ui: MorningState) {
    val open = ui.plan?.openTasks?.size ?: 0
    Column {
        Text(stringResource(R.string.morning_todays_work), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Primary)
        Text(
            if ((ui.plan?.tasks?.size ?: 0) == 0) stringResource(R.string.morning_no_tasks) else stringResource(R.string.morning_task_count, open),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = ShopAiThemeColors.onSurface,
        )
    }
}

private fun priorityColor(p: MorningPriority): Color = when (p) {
    MorningPriority.CRITICAL -> Danger
    MorningPriority.HIGH -> Pressure
    MorningPriority.MEDIUM -> Warning
    MorningPriority.LOW -> Primary
}

@Composable
private fun priorityLabel(p: MorningPriority) = stringResource(
    when (p) {
        MorningPriority.CRITICAL -> R.string.morning_priority_critical
        MorningPriority.HIGH -> R.string.morning_priority_high
        MorningPriority.MEDIUM -> R.string.morning_priority_medium
        MorningPriority.LOW -> R.string.morning_priority_low
    },
)

@Composable
private fun statusLabel(s: MorningTaskStatus) = stringResource(
    when (s) {
        MorningTaskStatus.PENDING -> R.string.morning_status_pending
        MorningTaskStatus.IN_PROGRESS -> R.string.morning_status_in_progress
        MorningTaskStatus.COMPLETED -> R.string.morning_status_completed
        MorningTaskStatus.POSTPONED -> R.string.morning_status_postponed
        MorningTaskStatus.SKIPPED -> R.string.morning_status_skipped
        MorningTaskStatus.FAILED -> R.string.morning_status_failed
    },
)

/** The facts of a task in plain words — every number straight from the records. */
@Composable
private fun factLines(t: MorningTask): List<String> {
    val today = LocalDate.now()
    val amount = t.facts.amount?.let(::formatInr)
    val due = t.dueDate?.let { d ->
        val late = today.toEpochDay() - d.toEpochDay()
        when {
            late > 0 -> stringResource(R.string.morning_overdue_days, late.toInt())
            late == 0L -> stringResource(R.string.morning_due_today)
            else -> stringResource(R.string.morning_due_on, d.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)))
        }
    }
    val unit = t.facts.unit.orEmpty().lowercase(Locale.ROOT)
    return when (t.taskType) {
        MorningTaskType.COLLECT_PAYMENT -> listOfNotNull(amount?.let { stringResource(R.string.morning_pending_amount, it) }, due)
        MorningTaskType.PAYMENT_FOLLOWUP -> listOfNotNull(
            if (t.facts.partialBills > 0 && t.facts.paid != null && t.facts.billed != null) stringResource(R.string.morning_paid_of, formatInr(t.facts.paid), formatInr(t.facts.billed)) else null,
            amount?.let { stringResource(R.string.morning_balance_pending, it) },
            due ?: stringResource(R.string.morning_no_due),
        )
        MorningTaskType.SUPPLIER_PAYMENT -> listOfNotNull(amount?.let { stringResource(R.string.morning_payment_due, it) }, due)
        MorningTaskType.LOW_STOCK -> listOfNotNull(
            if ((t.facts.stock ?: 0.0) <= 0.0) stringResource(R.string.morning_out_of_stock) else stringResource(R.string.morning_stock_line, formatQty(t.facts.stock ?: 0.0), unit),
            t.facts.reorderLevel?.let { stringResource(R.string.morning_reorder_line, formatQty(it), unit) }
                ?: t.facts.minimum?.let { stringResource(R.string.morning_minimum_line, formatQty(it), unit) },
        )
        MorningTaskType.EXPIRY -> {
            val exp = t.facts.expiryDay?.let(LocalDate::ofEpochDay)
            listOfNotNull(
                if (exp != null && exp.isBefore(today)) stringResource(R.string.morning_expired, t.facts.batchNo.orEmpty())
                else stringResource(R.string.morning_expires_on, t.facts.batchNo.orEmpty(), exp?.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)).orEmpty()),
                t.facts.stock?.let { stringResource(R.string.morning_stock_line, formatQty(it), unit) },
            )
        }
        MorningTaskType.REMINDER -> listOfNotNull(
            t.facts.reminderAtMillis?.let {
                stringResource(R.string.morning_reminder_at, Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)))
            },
        )
        MorningTaskType.PENDING_DRAFT -> listOf(stringResource(R.string.morning_draft_waiting))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(
    task: MorningTask,
    position: Int,
    total: Int,
    guided: Boolean,
    onAction: (MorningCommand) -> Unit,
    onSkip: () -> Unit,
    onRemind: () -> Unit,
) {
    val open = task.open()
    val color = priorityColor(task.priority)
    ShopCard(modifier = if (guided) Modifier.border(2.dp, Primary.copy(alpha = 0.55f), RoundedCornerShape(24.dp)) else Modifier) {
        if (guided) {
            Text(stringResource(R.string.morning_position, position, total), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Primary)
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(
                task.title,
                style = if (guided) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ShopAiThemeColors.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (open) priorityLabel(task.priority) else statusLabel(task.status),
                style = MaterialTheme.typography.labelMedium,
                color = if (open) color else ShopAiThemeColors.onSurfaceVariant,
                modifier = Modifier
                    .background((if (open) color else Color.Gray).copy(alpha = 0.12f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        for (line in factLines(task)) {
            Text(line, color = ShopAiThemeColors.onSurfaceVariant, modifier = Modifier.padding(start = 22.dp, top = 2.dp))
        }
        if (!open) return@ShopCard
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (task.taskType) {
                MorningTaskType.COLLECT_PAYMENT, MorningTaskType.PAYMENT_FOLLOWUP -> {
                    ActionChip(stringResource(R.string.morning_call), primary = guided) { onAction(MorningCommand.Call(null)) }
                    ActionChip(stringResource(R.string.morning_whatsapp)) { onAction(MorningCommand.WhatsApp(null)) }
                    ActionChip(stringResource(R.string.morning_collect)) { onAction(MorningCommand.Payment(task.title, task.facts.amount, false)) }
                    ActionChip(stringResource(R.string.morning_view)) { onAction(MorningCommand.View) }
                    ActionChip(stringResource(R.string.morning_remind_later), onClick = onRemind)
                }
                MorningTaskType.SUPPLIER_PAYMENT -> {
                    ActionChip(stringResource(R.string.morning_view_payment), primary = guided) { onAction(MorningCommand.View) }
                    ActionChip(stringResource(R.string.morning_pay)) { onAction(MorningCommand.Payment(task.title, task.facts.amount, false)) }
                    ActionChip(stringResource(R.string.morning_call)) { onAction(MorningCommand.Call(null)) }
                    ActionChip(stringResource(R.string.morning_remind_later), onClick = onRemind)
                }
                MorningTaskType.LOW_STOCK -> {
                    ActionChip(stringResource(R.string.morning_add_purchase), primary = guided) { onAction(MorningCommand.View) }
                    ActionChip(stringResource(R.string.morning_remind_later), onClick = onRemind)
                }
                MorningTaskType.EXPIRY -> {
                    ActionChip(stringResource(R.string.morning_view_stock), primary = guided) { onAction(MorningCommand.View) }
                    ActionChip(stringResource(R.string.morning_remind_later), onClick = onRemind)
                }
                MorningTaskType.REMINDER -> {
                    ActionChip(stringResource(R.string.morning_done), primary = guided) { onAction(MorningCommand.Done) }
                    ActionChip(stringResource(R.string.morning_remind_later), onClick = onRemind)
                }
                MorningTaskType.PENDING_DRAFT -> {
                    ActionChip(stringResource(R.string.morning_review), primary = guided) { onAction(MorningCommand.View) }
                }
            }
            if (task.taskType != MorningTaskType.REMINDER) ActionChip(stringResource(R.string.morning_done)) { onAction(MorningCommand.Done) }
            ActionChip(stringResource(R.string.morning_skip), onClick = onSkip)
        }
    }
}

@Composable
private fun ActionChip(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (primary) Color.White else Primary,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(if (primary) Primary else PrimaryLight.copy(alpha = 0.6f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentReview(
    partyName: String,
    outgoing: Boolean,
    pending: Double,
    mode: String,
    account: String?,
    amount: String,
    reference: String,
    onAmount: (String) -> Unit,
    onReference: (String) -> Unit,
    onMode: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    ShopCard(modifier = Modifier.border(2.dp, (if (outgoing) Danger else Primary).copy(alpha = 0.5f), RoundedCornerShape(24.dp))) {
        Text(stringResource(R.string.morning_payment_review), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Primary)
        Text(
            stringResource(if (outgoing) R.string.morning_payment_to else R.string.morning_payment_from, partyName),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = ShopAiThemeColors.onSurface,
        )
        Text(stringResource(R.string.morning_payment_pending, formatInr(pending)), color = ShopAiThemeColors.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = amount,
            onValueChange = { v -> onAmount(v.filter { it.isDigit() || it == '.' }) },
            label = { Text(stringResource(R.string.morning_payment_amount)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.morning_payment_mode), style = MaterialTheme.typography.labelMedium, color = ShopAiThemeColors.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in listOf(
                "CASH" to R.string.morning_mode_cash,
                "UPI" to R.string.morning_mode_upi,
                "BANK_TRANSFER" to R.string.morning_mode_bank,
                "CARD" to R.string.morning_mode_card,
            )) {
                FilterChip(selected = mode == value, onClick = { onMode(value) }, label = { Text(stringResource(label)) })
            }
        }
        account?.let { Text(stringResource(R.string.morning_payment_account, it), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant) }
        OutlinedTextField(
            value = reference,
            onValueChange = onReference,
            label = { Text(stringResource(R.string.morning_payment_reference)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton(stringResource(R.string.morning_confirm_payment), onConfirm, modifier = Modifier.fillMaxWidth(), enabled = (amount.toDoubleOrNull() ?: 0.0) > 0)
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.morning_cancel)) }
    }
}

@Composable
private fun WhatsAppCard(name: String, text: String, onText: (String) -> Unit, onSend: () -> Unit, onCancel: () -> Unit) {
    ShopCard {
        Text(stringResource(R.string.morning_whatsapp_title, name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ShopAiThemeColors.onSurface)
        Text(stringResource(R.string.morning_edit_hint), style = MaterialTheme.typography.bodySmall, color = ShopAiThemeColors.onSurfaceVariant)
        OutlinedTextField(value = text, onValueChange = onText, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(R.string.morning_send), onSend, modifier = Modifier.weight(1f), enabled = text.isNotBlank())
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.morning_cancel)) }
        }
    }
}

@Composable
private fun Completion(ui: MorningState, onSummary: () -> Unit) {
    val s = ui.summary ?: return
    ShopCard(modifier = Modifier.border(2.dp, Primary.copy(alpha = 0.4f), RoundedCornerShape(24.dp))) {
        Text(stringResource(R.string.morning_complete_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Primary)
        Text(stringResource(R.string.morning_complete_counts, s.total, s.completed, s.postponed, s.skipped), color = ShopAiThemeColors.onSurface)
        Spacer(Modifier.height(10.dp))
        PrimaryButton(stringResource(R.string.morning_view_summary), onSummary, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun InputBar(value: String, partial: String?, listening: Boolean, onValue: (String) -> Unit, onSend: () -> Unit, onMic: () -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (listening) {
            Text(partial ?: stringResource(R.string.morning_listening), color = Primary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                placeholder = { Text(stringResource(R.string.morning_input_hint)) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            IconButton(
                onClick = onMic,
                modifier = Modifier.padding(start = 6.dp).size(48.dp).background(if (listening) Danger else PrimaryLight, CircleShape),
            ) {
                Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.morning_mic), tint = if (listening) Color.White else Primary)
            }
            IconButton(
                onClick = onSend,
                enabled = value.isNotBlank(),
                modifier = Modifier.padding(start = 6.dp).size(48.dp).background(if (value.isNotBlank()) Primary else Primary.copy(alpha = 0.35f), CircleShape),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.morning_send), tint = Color.White)
            }
        }
    }
}

@Composable
private fun RemindDialog(onPick: (LocalDateTime) -> Unit, onDismiss: () -> Unit) {
    val now = LocalDateTime.now()
    // The same rules as the spoken "after lunch" / "tomorrow morning" — from the real current time.
    val options = listOf(
        R.string.morning_remind_30m to now.plusMinutes(30),
        R.string.morning_remind_1h to now.plusHours(1),
        R.string.morning_remind_lunch to (MorningTime.parse("after lunch", now) ?: now.plusHours(4)),
        R.string.morning_remind_evening to (MorningTime.parse("evening", now) ?: now.plusHours(8)),
        R.string.morning_remind_tomorrow to (MorningTime.parse("tomorrow morning", now) ?: now.plusDays(1)),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.morning_remind_title)) },
        text = {
            Column {
                for ((label, at) in options) {
                    Text(
                        stringResource(label),
                        modifier = Modifier.fillMaxWidth().clickable { onPick(at) }.padding(vertical = 12.dp),
                        color = ShopAiThemeColors.onSurface,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.morning_cancel)) } },
    )
}

@Composable
private fun SkipDialog(onPick: (SkipReason?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.morning_skip_title)) },
        text = {
            Column {
                for ((reason, label) in listOf(
                    SkipReason.ALREADY_HANDLED to R.string.morning_skip_handled,
                    SkipReason.NOT_NEEDED to R.string.morning_skip_not_needed,
                    SkipReason.WILL_DO_LATER to R.string.morning_skip_later,
                    SkipReason.WRONG_INFORMATION to R.string.morning_skip_wrong,
                    SkipReason.OTHER to R.string.morning_skip_other,
                )) {
                    Text(
                        stringResource(label),
                        modifier = Modifier.fillMaxWidth().clickable { onPick(reason) }.padding(vertical = 12.dp),
                        color = ShopAiThemeColors.onSurface,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.morning_skip_no_reason)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.morning_cancel)) } },
    )
}

@Composable
private fun SummaryDialog(s: com.shopai.app.brain.morning.MorningSummary, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.morning_summary_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryRow(stringResource(R.string.morning_summary_collections), formatInr(s.collections), Primary)
                SummaryRow(stringResource(R.string.morning_summary_payments), formatInr(s.payments), Danger)
                SummaryRow(stringResource(R.string.morning_summary_low_stock), stringResource(R.string.morning_summary_products, s.lowStockProducts), ShopAiThemeColors.onSurface)
                SummaryRow(stringResource(R.string.morning_summary_reminders), s.reminders.toString(), ShopAiThemeColors.onSurface)
                SummaryRow(stringResource(R.string.morning_summary_followups), s.followUps.toString(), ShopAiThemeColors.onSurface)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.morning_close)) } },
    )
}

@Composable
private fun SummaryRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = ShopAiThemeColors.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold, color = color)
    }
}

/** Home: "Do My Morning Work — 6 important tasks today" → the Morning Work screen. */
@Composable
fun MorningWorkCard(count: Int?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Primary, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).background(Color.White.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            Text("☀", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.morning_home_title), fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                if (count != null) stringResource(R.string.morning_home_count, count) else stringResource(R.string.morning_home_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
        Text("›", color = Color.White, style = MaterialTheme.typography.headlineSmall)
    }
}
