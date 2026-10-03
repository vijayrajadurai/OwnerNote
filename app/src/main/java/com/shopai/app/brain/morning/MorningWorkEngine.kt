package com.shopai.app.brain.morning

import com.shopai.app.brain.KaiLang
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.Locale

/** A payment Kai prepared — nothing is recorded until the owner explicitly confirms it. */
data class MorningPaymentDraft(
    /** Also the books' idempotency key: the same draft never posts twice. */
    val id: String,
    val taskId: String?,
    val partyId: String,
    val partyName: String,
    val partyKind: MorningPartyKind,
    val amount: Double,
    /** What the ledger says is pending with this party. */
    val pending: Double,
    val mode: String = "CASH",
    val reference: String? = null,
) {
    /** Paying a supplier (out) or receiving from a customer (in). */
    val outgoing: Boolean get() = partyKind == MorningPartyKind.SUPPLIER
}

/** Where a "View" / "Add purchase" button goes — always an existing OwnerNote screen. */
sealed interface MorningTarget {
    data class Customer(val id: String) : MorningTarget
    data class Supplier(val id: String) : MorningTarget
    data class Product(val id: String) : MorningTarget
    data class AddPurchase(val supplierId: String?) : MorningTarget
    data class Payment(val partyId: String, val incoming: Boolean) : MorningTarget
    data class Draft(val id: String, val kind: String) : MorningTarget
    data object Reminders : MorningTarget
    data object Inventory : MorningTarget
}

/** Things only the app can do (open the dialer, WhatsApp, a screen, the books). Kai never does them silently. */
sealed interface MorningEffect {
    data class OpenDialer(val taskId: String?, val name: String, val phone: String) : MorningEffect

    /** Show the prepared message with Edit / Send / Cancel — never sent automatically. */
    data class WhatsAppDraft(val taskId: String, val name: String, val phone: String, val message: String) : MorningEffect

    /** The owner pressed Send: open WhatsApp with the message (the owner sends it there). */
    data class OpenWhatsApp(val taskId: String, val phone: String, val message: String) : MorningEffect

    /** Create a reminder in the existing reminder engine. */
    data class CreateReminder(val taskId: String, val title: String, val atMillis: Long) : MorningEffect

    data class Open(val taskId: String?, val target: MorningTarget) : MorningEffect

    /** Show Payment Mode / Account / Amount / Reference for review. */
    data class ReviewPayment(val draft: MorningPaymentDraft) : MorningEffect

    /** Only after the owner's explicit confirmation: post through the existing transaction engine. */
    data class PostPayment(val draft: MorningPaymentDraft) : MorningEffect

    data class ShowRemindOptions(val taskId: String) : MorningEffect
    data object ShowSummary : MorningEffect
    data object Completed : MorningEffect
}

/** What came back from doing an effect. */
sealed interface MorningResult {
    data class DialerOpened(val taskId: String?) : MorningResult
    data class WhatsAppOpened(val taskId: String) : MorningResult
    data class Opened(val taskId: String?) : MorningResult
    data class ActionFailed(val taskId: String?, val action: String) : MorningResult
    data class ReminderCreated(val taskId: String, val reminderId: String) : MorningResult

    /** Offline: kept on the phone, created once there is a connection. */
    data class ReminderQueued(val taskId: String, val atMillis: Long) : MorningResult
    data class ReminderFailed(val taskId: String) : MorningResult
    data class PaymentPosted(val draftId: String) : MorningResult
    data class PaymentRejected(val draftId: String, val message: String?) : MorningResult
}

/** One answer from Kai: what to show / say ([mode] decides which) and what the app should do. */
data class MorningReply(
    val line: MorningLine,
    val mode: ResponseMode,
    val effects: List<MorningEffect> = emptyList(),
) {
    val speak: Boolean get() = mode == ResponseMode.VOICE && line.speech.isNotBlank()
}

/** What the Morning Work screen shows. */
data class MorningState(
    val plan: MorningPlan? = null,
    val started: Boolean = false,
    val currentTaskId: String? = null,
    val mode: ResponseMode = ResponseMode.TEXT,
    val lang: KaiLang = KaiLang.TANGLISH,
    val paymentDraft: MorningPaymentDraft? = null,
    val whatsApp: MorningEffect.WhatsAppDraft? = null,
    val askingRemindFor: String? = null,
    val finished: Boolean = false,
    val summary: MorningSummary? = null,
) {
    val current: MorningTask? get() = plan?.tasks?.firstOrNull { it.taskId == currentTaskId }
}

/**
 * KAI — Do My Morning Work. ONE engine for voice and text: both adapters
 * send words to [handle] (or a button to [act]) and get the same reply back;
 * only how the reply is delivered differs ([ResponseMode]).
 *
 *  business data (read only) → [MorningAnalyzer] → candidates → priority +
 *  de-duplication → today's list (kept in [MorningTaskStore]) → owner acts →
 *  effects run by the app through the existing engines → results come back
 *  ([onResult]) → task status.
 *
 * It never changes money, stock or invoices itself: a payment is a draft
 * until the owner confirms it, and then the app posts it through the
 * existing transaction engine.
 */
class MorningWorkEngine(
    private val store: MorningTaskStore,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val maxTasks: Int = MAX_TASKS,
) {
    private val lock = Mutex()

    /** Who is using the app (set by the app from the existing user record). */
    @Volatile
    var role: MorningRole = MorningRole.OWNER

    private var snapshot: MorningSnapshot? = null
    private var candidates: List<MorningTask> = emptyList()
    private var _state = MorningState()

    /** Waiting for the owner's next words about one thing. */
    private sealed interface Awaiting {
        data class RemindTime(val taskId: String) : Awaiting
        data class Amount(val partyId: String) : Awaiting
        data class Confirmation(val draft: MorningPaymentDraft) : Awaiting
        data class WhatsAppSend(val draft: MorningEffect.WhatsAppDraft) : Awaiting
    }

    private var awaiting: Awaiting? = null
    private var postingDraftId: String? = null

    val state: MorningState get() = _state

    // ------------------------------------------------------------ prepare

    /**
     * Builds (or refreshes) today's list from [snap]. Read only: it changes
     * nothing in the books. Tasks already in today's list keep their status;
     * an open task whose condition is gone from the records (the money was
     * received, stock was bought) is marked COMPLETED as resolved.
     */
    suspend fun prepare(
        snap: MorningSnapshot,
        mode: ResponseMode? = null,
        lang: KaiLang? = null,
        greet: Boolean = true,
    ): MorningReply = lock.withLock {
        val now = clock()
        val today = now.toLocalDate()
        val day = today.toEpochDay()
        val nowMillis = now.toInstant().toEpochMilli()
        val biz = snap.businessId
        // Another business on the same phone: start clean, never mix.
        if (_state.plan?.businessId != null && _state.plan?.businessId != biz) resetSession()
        mode?.let { _state = _state.copy(mode = it) }
        lang?.let { _state = _state.copy(lang = it) }

        val all = MorningAnalyzer.candidates(snap, today, nowMillis, now.zone, store.ownReminderIds(biz))
        val byId = all.associateBy { it.taskId }
        val stored = store.load(biz, day).filter { it.businessId == biz }
        val merged = stored.map { s ->
            val c = byId[s.taskId]
            when {
                c != null -> s.copy(
                    priority = c.priority, reason = c.reason, title = c.title, description = c.description, dueAt = c.dueAt,
                    phone = c.phone, facts = c.facts, updatedAt = nowMillis,
                )
                // The records no longer show it: resolved in the books (only trusted when the data is current).
                !snap.offline && (s.open || s.status == MorningTaskStatus.POSTPONED || s.status == MorningTaskStatus.FAILED) ->
                    s.copy(status = MorningTaskStatus.COMPLETED, completedAt = nowMillis, actionTaken = ACTION_RESOLVED, updatedAt = nowMillis)
                else -> s
            }
        }
        val known = merged.map { it.taskId }.toSet()
        val room = (maxTasks - merged.count { it.open }).coerceAtLeast(0)
        val added = all.filter { it.taskId !in known }.take(room)
        val tasks = MorningAnalyzer.sort(merged + added)
        store.save(biz, day, tasks)

        snapshot = snap
        candidates = all
        val plan = MorningPlan(biz, day, tasks, snap.offline, snap.syncedAtMillis, all.size)
        // Keep the owner where they were; if that task got resolved meanwhile, move on.
        val cursor = _state.currentTaskId?.takeIf { id -> tasks.any { it.taskId == id } }
        _state = _state.copy(plan = plan, currentTaskId = cursor, summary = summaryOf(plan, all), finished = _state.started && plan.openTasks.isEmpty())

        val effects = if (snap.offline) emptyList() else store.queuedReminders(biz).map {
            MorningEffect.CreateReminder(it.taskId, reminderTitle(it), it.pendingReminderAt!!)
        }
        val lines = mutableListOf<MorningLine>()
        if (greet) lines += MorningLines.greeting(plan.openTasks.size, now.hour, _state.lang)
        if (snap.offline) lines += MorningLines.offlineNote(_state.lang)
        // A refresh while working: the current task was settled in the books.
        if (!greet && _state.started && cursor != null && tasks.first { it.taskId == cursor }.let { !it.open }) {
            return@withLock advanceLocked(MorningLines.doneNext(_state.lang), effects).also { persist() }
        }
        MorningReply(lines.fold(MorningLine.EMPTY) { a, b -> a + b }, _state.mode, effects)
    }

    /**
     * The morning brief — the ONE way Kai (chat and voice, through Kai's intent
     * router) and a future scheduled morning notification get Morning Work. It
     * is the same read-only refresh as the Morning Work screen ([prepare]), so
     * every entry point shows the same tasks from the same records.
     */
    suspend fun generate(snap: MorningSnapshot, lang: KaiLang, trigger: MorningTrigger = MorningTrigger.MANUAL): MorningBrief {
        prepare(snap, lang = lang, greet = false)
        val now = clock()
        val plan = lock.withLock { _state.plan }?.takeIf { it.businessId == snap.businessId }
            ?: MorningPlan(snap.businessId, now.toLocalDate().toEpochDay(), emptyList(), snap.offline, snap.syncedAtMillis, 0)
        return MorningBriefs.build(plan, now, lang, trigger)
    }

    // ------------------------------------------------------------- input

    /** Typed or spoken words (speech-to-text gives text) — the same path for both. */
    suspend fun handle(text: String, input: ResponseMode): MorningReply {
        val lang = MorningCommands.language(text)
        val names = lock.withLock { snapshot?.let { s -> s.parties.map { it.name } + s.products.map { it.name } }.orEmpty() }
        val command = MorningCommands.parse(text, clock().toLocalDateTime(), names)
        lock.withLock {
            // The request that starts the session chooses the mode; later inputs never switch it by accident.
            if (!_state.started && (command == MorningCommand.Open || command == MorningCommand.Start)) _state = _state.copy(mode = input)
            lang?.let { _state = _state.copy(lang = it) }
        }
        return act(command)
    }

    /** A button, or a parsed command. [taskId] targets one card in the list (null = the current task). */
    suspend fun act(command: MorningCommand, taskId: String? = null): MorningReply = lock.withLock {
        actLocked(command, taskId).also { persist() }
    }

    private suspend fun actLocked(command: MorningCommand, taskId: String?): MorningReply = run {
        val lang = _state.lang
        val plan = _state.plan ?: return@run reply(MorningLines.noData(lang))
        // Never act on a task of another business.
        if (taskId != null && plan.tasks.none { it.taskId == taskId }) return@run reply(MorningLines.unknown(lang))

        when (val wait = awaiting) {
            is Awaiting.Confirmation -> when (command) {
                MorningCommand.Confirm -> return@run confirmPayment(wait.draft)
                MorningCommand.Cancel, MorningCommand.Next, is MorningCommand.Skip -> {
                    awaiting = null
                    _state = _state.copy(paymentDraft = null)
                    if (command == MorningCommand.Cancel) return@run reply(MorningLines.cancelled(lang))
                }
                is MorningCommand.SwitchMode -> Unit
                // A new amount for the same draft ("illa, 15000").
                is MorningCommand.Amount -> return@run if (command.amount != null && !command.unclear) {
                    draftPayment(partyOf(wait.draft.partyId)!!, command.amount, wait.draft.taskId)
                } else {
                    reply(MorningLines.amountUnclear(wait.draft.partyName, lang))
                }
                // A different payment replaces the draft.
                is MorningCommand.Payment -> {
                    awaiting = null
                    _state = _state.copy(paymentDraft = null)
                }
                // Anything unclear is never a yes.
                else -> return@run reply(MorningLines.confirmAgain(lang))
            }
            is Awaiting.WhatsAppSend -> when (command) {
                MorningCommand.Confirm, is MorningCommand.WhatsApp -> return@run sendWhatsAppLocked(wait.draft.message)
                MorningCommand.Cancel -> {
                    awaiting = null
                    _state = _state.copy(whatsApp = null)
                    return@run reply(MorningLines.cancelled(lang))
                }
                else -> {
                    awaiting = null
                    _state = _state.copy(whatsApp = null)
                }
            }
            is Awaiting.RemindTime -> if (command is MorningCommand.RemindLater && command.at != null) {
                return@run remindLocked(wait.taskId, command.at)
            } else {
                awaiting = null
                _state = _state.copy(askingRemindFor = null)
                if (command == MorningCommand.Cancel) return@run reply(MorningLines.cancelled(lang))
            }
            is Awaiting.Amount -> if (command is MorningCommand.Amount || (command is MorningCommand.Payment && command.name == null)) {
                val amount = (command as? MorningCommand.Amount)?.amount ?: (command as? MorningCommand.Payment)?.amount
                val unclear = (command as? MorningCommand.Amount)?.unclear ?: (command as? MorningCommand.Payment)?.amountUnclear ?: false
                val party = partyOf(wait.partyId)!!
                if (amount == null || unclear) return@run reply(MorningLines.amountUnclear(party.name, lang))
                awaiting = null
                return@run draftPayment(party, amount, currentTask()?.takeIf { it.sourceId == party.id }?.taskId)
            } else {
                awaiting = null
                if (command == MorningCommand.Cancel) return@run reply(MorningLines.cancelled(lang))
            }
            null -> Unit
        }

        when (command) {
            MorningCommand.Open -> {
                val now = clock()
                val l = MorningLines.greeting(plan.openTasks.size, now.hour, lang)
                reply(if (plan.offline) l + MorningLines.offlineNote(lang) else l)
            }
            MorningCommand.Start -> {
                _state = _state.copy(started = true, finished = false)
                val first = plan.tasks.firstOrNull { it.open }
                if (first == null) {
                    if (plan.tasks.isEmpty()) reply(MorningLines.greeting(0, clock().hour, lang)) else finish(MorningLine.EMPTY)
                } else {
                    present(first, firstTask = true)
                }
            }
            MorningCommand.Next -> {
                _state = _state.copy(started = true)
                advanceLocked(MorningLine.EMPTY)
            }
            MorningCommand.Done -> {
                val t = target(taskId) ?: return@run reply(MorningLines.allDoneAlready(lang))
                update(t.taskId) { it.copy(status = MorningTaskStatus.COMPLETED, completedAt = millis(), actionTaken = ACTION_DONE) }
                afterAction(t.taskId, MorningLines.doneNext(lang))
            }
            is MorningCommand.Skip -> {
                val t = target(taskId) ?: return@run reply(MorningLines.allDoneAlready(lang))
                // Only the morning task is skipped; the customer, bill or product is untouched.
                update(t.taskId) { it.copy(status = MorningTaskStatus.SKIPPED, completedAt = millis(), actionTaken = ACTION_SKIPPED, skipReason = command.reason) }
                afterAction(t.taskId, MorningLines.skipped(lang))
            }
            is MorningCommand.Call -> call(command.name, taskId)
            is MorningCommand.WhatsApp -> whatsApp(command.name, taskId)
            is MorningCommand.RemindLater -> {
                val t = target(taskId) ?: return@run reply(MorningLines.allDoneAlready(lang))
                if (command.at == null) {
                    awaiting = Awaiting.RemindTime(t.taskId)
                    _state = _state.copy(askingRemindFor = t.taskId)
                    reply(MorningLines.askRemindWhen(lang), listOf(MorningEffect.ShowRemindOptions(t.taskId)))
                } else {
                    remindLocked(t.taskId, command.at)
                }
            }
            MorningCommand.View -> view(taskId)
            is MorningCommand.Payment -> payment(command, taskId)
            is MorningCommand.Amount -> {
                // An amount with a name but heard unclearly ("Kumar-ku ... 12...").
                val p = command.name?.let { n -> matchParty(n).singleOrNull() }
                when {
                    p != null && (command.unclear || command.amount == null) -> {
                        awaiting = Awaiting.Amount(p.id)
                        reply(MorningLines.amountUnclear(p.name, lang))
                    }
                    command.unclear -> reply(MorningLines.amountUnclear(null, lang))
                    else -> reply(MorningLines.unknown(lang))
                }
            }
            MorningCommand.Confirm -> reply(MorningLines.nothingToConfirm(lang))
            MorningCommand.Cancel -> reply(MorningLines.cancelled(lang))
            is MorningCommand.SwitchMode -> {
                _state = _state.copy(mode = command.mode)
                reply(MorningLines.modeSwitched(command.mode, lang))
            }
            MorningCommand.Summary -> {
                val s = summaryOf(plan, candidates)
                _state = _state.copy(summary = s)
                reply(MorningLines.summary(s, lang), listOf(MorningEffect.ShowSummary))
            }
            is MorningCommand.Unknown -> reply(MorningLines.unknown(lang))
        }
    }

    /** The owner pressed Send on the (possibly edited) WhatsApp message. */
    suspend fun sendWhatsApp(message: String): MorningReply = lock.withLock { sendWhatsAppLocked(message).also { persist() } }

    /** The owner changed the amount, payment mode or reference on the review card (still only a draft). */
    suspend fun editPaymentDraft(mode: String, reference: String?, amount: Double? = null) {
        lock.withLock {
            val wait = awaiting as? Awaiting.Confirmation ?: return@withLock
            if (postingDraftId == wait.draft.id) return@withLock
            val d = wait.draft.copy(
                mode = mode,
                reference = reference?.trim()?.ifEmpty { null },
                amount = amount?.takeIf { it > 0 } ?: wait.draft.amount,
            )
            awaiting = Awaiting.Confirmation(d)
            _state = _state.copy(paymentDraft = d)
        }
    }

    /** Results of the effects the app ran. */
    suspend fun onResult(result: MorningResult): MorningReply = lock.withLock {
        onResultLocked(result).also { persist() }
    }

    private suspend fun onResultLocked(result: MorningResult): MorningReply = run {
        val lang = _state.lang
        when (result) {
            is MorningResult.DialerOpened -> {
                // The owner makes the call — Kai never says it was completed.
                val id = result.taskId ?: return@run reply(MorningLine.EMPTY)
                update(id) { it.copy(status = MorningTaskStatus.COMPLETED, completedAt = millis(), actionTaken = ACTION_CALL) }
                afterAction(id, MorningLine.EMPTY)
            }
            is MorningResult.WhatsAppOpened -> {
                update(result.taskId) { it.copy(status = MorningTaskStatus.COMPLETED, completedAt = millis(), actionTaken = ACTION_WHATSAPP) }
                afterAction(result.taskId, MorningLines.whatsAppOpened(lang))
            }
            is MorningResult.Opened -> {
                result.taskId?.let { id -> update(id) { if (it.open) it.copy(status = MorningTaskStatus.IN_PROGRESS, actionTaken = ACTION_VIEWED) else it } }
                reply(MorningLine.EMPTY)
            }
            is MorningResult.ActionFailed -> {
                result.taskId?.let { id -> update(id) { it.copy(status = MorningTaskStatus.FAILED, actionTaken = "${result.action}_FAILED") } }
                reply(MorningLines.actionFailed(lang))
            }
            is MorningResult.ReminderCreated -> {
                if (_state.plan?.tasks?.none { it.taskId == result.taskId } != false) {
                    updateStored(result.taskId) { it.copy(reminderId = result.reminderId, pendingReminderAt = null) }
                    return@run reply(MorningLine.EMPTY)
                }
                update(result.taskId) {
                    it.copy(status = MorningTaskStatus.POSTPONED, reminderId = result.reminderId, pendingReminderAt = null, actionTaken = ACTION_POSTPONED)
                }
                afterAction(result.taskId, MorningLine.EMPTY)
            }
            is MorningResult.ReminderQueued -> {
                update(result.taskId) { it.copy(status = MorningTaskStatus.POSTPONED, pendingReminderAt = result.atMillis, actionTaken = ACTION_POSTPONED) }
                afterAction(result.taskId, MorningLines.reminderQueued(lang))
            }
            is MorningResult.ReminderFailed -> {
                update(result.taskId) { it.copy(status = MorningTaskStatus.FAILED, actionTaken = "REMINDER_FAILED") }
                reply(MorningLines.reminderFailed(lang))
            }
            is MorningResult.PaymentPosted -> {
                val d = (_state.paymentDraft ?: (awaiting as? Awaiting.Confirmation)?.draft)?.takeIf { it.id == result.draftId }
                awaiting = null
                postingDraftId = null
                _state = _state.copy(paymentDraft = null)
                if (d == null) return@run reply(MorningLine.EMPTY)
                val done = MorningLines.paymentDone(d, lang)
                val taskId = d.taskId ?: currentTask()?.takeIf { it.sourceId == d.partyId }?.taskId
                if (taskId == null) return@run reply(done)
                update(taskId) { it.copy(status = MorningTaskStatus.COMPLETED, completedAt = millis(), actionTaken = ACTION_PAYMENT) }
                afterAction(taskId, done)
            }
            is MorningResult.PaymentRejected -> {
                postingDraftId = null
                reply(MorningLines.paymentFailed(result.message, lang))
            }
        }
    }

    /** Starting over (logout, another business). The stored history stays. */
    fun reset() {
        resetSession()
        snapshot = null
        candidates = emptyList()
        _state = MorningState()
    }

    // ----------------------------------------------------------- actions

    private suspend fun call(name: String?, taskId: String?): MorningReply {
        val lang = _state.lang
        val t = target(taskId)
        // "Call Kumar": Kumar's task if he has one, else Kumar from the records.
        val party = name?.let { n -> matchParty(n).singleOrNull() }
        val forTask = t?.takeIf { party == null || it.sourceId == party.id }
            ?: party?.let { p -> _state.plan?.tasks?.firstOrNull { it.sourceId == p.id && it.open } }
        if (party == null && forTask != null && forTask.sourceType != MorningSourceType.CUSTOMER && forTask.sourceType != MorningSourceType.SUPPLIER) {
            return reply(MorningLines.unknown(lang))
        }
        val who = party?.name ?: forTask?.title ?: return reply(MorningLines.unknown(lang))
        val phone = (party?.phone ?: forTask?.phone ?: partyOf(forTask?.sourceId)?.phone)?.takeIf { it.count(Char::isDigit) >= 8 }
            ?: return reply(MorningLines.noPhone(who, lang))
        forTask?.let { task -> update(task.taskId) { it.copy(status = MorningTaskStatus.IN_PROGRESS, actionTaken = ACTION_CALL_REQUESTED) } }
        return reply(MorningLines.callReady(who, lang), listOf(MorningEffect.OpenDialer(forTask?.taskId, who, phone)))
    }

    private suspend fun whatsApp(name: String?, taskId: String?): MorningReply {
        val lang = _state.lang
        val party = name?.let { n -> matchParty(n).singleOrNull() }
        val t = (party?.let { p -> _state.plan?.tasks?.firstOrNull { it.sourceId == p.id && it.open } } ?: target(taskId))
            ?: return reply(MorningLines.unknown(lang))
        if (t.sourceType != MorningSourceType.CUSTOMER) return reply(MorningLines.whatsAppOnlyCustomers(lang))
        val phone = t.phone?.takeIf { it.count(Char::isDigit) >= 8 } ?: return reply(MorningLines.noPhone(t.title, lang))
        val draft = MorningEffect.WhatsAppDraft(t.taskId, t.title, phone, MorningLines.whatsAppMessage(t.title, t.facts.amount ?: 0.0, lang))
        awaiting = Awaiting.WhatsAppSend(draft)
        _state = _state.copy(whatsApp = draft)
        update(t.taskId) { it.copy(status = MorningTaskStatus.IN_PROGRESS, actionTaken = ACTION_WHATSAPP_PREPARED) }
        return reply(MorningLines.whatsAppReady(t.title, lang), listOf(draft))
    }

    private fun sendWhatsAppLocked(message: String): MorningReply {
        val wait = awaiting as? Awaiting.WhatsAppSend ?: return reply(MorningLines.nothingToConfirm(_state.lang))
        val text = message.trim().ifEmpty { wait.draft.message }
        awaiting = null
        _state = _state.copy(whatsApp = null)
        return reply(MorningLine.EMPTY, listOf(MorningEffect.OpenWhatsApp(wait.draft.taskId, wait.draft.phone, text)))
    }

    private suspend fun remindLocked(taskId: String, at: LocalDateTime): MorningReply {
        awaiting = null
        _state = _state.copy(askingRemindFor = null)
        val now = clock()
        val t = _state.plan?.tasks?.firstOrNull { it.taskId == taskId } ?: return reply(MorningLines.unknown(_state.lang))
        val atMillis = at.atZone(now.zone).toInstant().toEpochMilli()
        return reply(
            MorningLines.reminderSet(at, now.toLocalDate(), _state.lang),
            listOf(MorningEffect.CreateReminder(taskId, reminderTitle(t), atMillis)),
        )
    }

    private fun view(taskId: String?): MorningReply {
        val t = target(taskId) ?: return reply(MorningLines.allDoneAlready(_state.lang), listOf(MorningEffect.Open(null, MorningTarget.Reminders)))
        val target = when (t.sourceType) {
            MorningSourceType.CUSTOMER -> MorningTarget.Customer(t.sourceId)
            MorningSourceType.SUPPLIER -> if (t.taskType == MorningTaskType.SUPPLIER_PAYMENT) MorningTarget.Payment(t.sourceId, incoming = false) else MorningTarget.Supplier(t.sourceId)
            MorningSourceType.PRODUCT -> MorningTarget.Product(t.sourceId)
            MorningSourceType.BATCH -> MorningTarget.Inventory
            MorningSourceType.REMINDER -> MorningTarget.Reminders
            MorningSourceType.DRAFT -> MorningTarget.Draft(t.sourceId, t.facts.draftKind ?: t.title)
        }
        return reply(MorningLines.opening(_state.lang), listOf(MorningEffect.Open(t.taskId, target)))
    }

    private fun payment(cmd: MorningCommand.Payment, taskId: String?): MorningReply {
        val lang = _state.lang
        if (!role.canRecordPayments) return reply(MorningLines.notAllowed(lang))
        val party: MorningParty = if (cmd.name != null) {
            val matches = matchParty(cmd.name)
            when {
                matches.isEmpty() -> return reply(MorningLines.notFound(cmd.name, lang))
                matches.size > 1 -> return reply(MorningLines.whichOne(matches.map { it.name }, lang))
                else -> matches.single()
            }
        } else {
            target(taskId)?.takeIf { it.sourceType == MorningSourceType.CUSTOMER || it.sourceType == MorningSourceType.SUPPLIER }
                ?.let { partyOf(it.sourceId) } ?: return reply(MorningLines.askWho(lang))
        }
        val forTask = _state.plan?.tasks?.firstOrNull { it.sourceId == party.id && it.open }?.taskId
        if (cmd.amount == null || cmd.amountUnclear) {
            awaiting = Awaiting.Amount(party.id)
            return reply(
                if (cmd.amountUnclear) MorningLines.amountUnclear(party.name, lang)
                else MorningLines.askAmount(party.name, party.kind == MorningPartyKind.SUPPLIER, lang),
            )
        }
        return draftPayment(party, cmd.amount, forTask)
    }

    private fun draftPayment(party: MorningParty, amount: Double, taskId: String?): MorningReply {
        if (!role.canRecordPayments) return reply(MorningLines.notAllowed(_state.lang))
        val draft = MorningPaymentDraft(
            id = "morning-pay|${party.id}|${millis()}",
            taskId = taskId,
            partyId = party.id,
            partyName = party.name,
            partyKind = party.kind,
            amount = amount,
            pending = party.pending,
        )
        awaiting = Awaiting.Confirmation(draft)
        _state = _state.copy(paymentDraft = draft)
        return reply(MorningLines.paymentDraft(draft, _state.lang), listOf(MorningEffect.ReviewPayment(draft)))
    }

    private fun confirmPayment(draft: MorningPaymentDraft): MorningReply {
        if (!role.canRecordPayments) return reply(MorningLines.notAllowed(_state.lang))
        // A second "yes" while saving never posts twice (the books also de-duplicate by the draft id).
        if (postingDraftId == draft.id) return reply(MorningLine.EMPTY)
        postingDraftId = draft.id
        return reply(MorningLine.EMPTY, listOf(MorningEffect.PostPayment(draft)))
    }

    // ------------------------------------------------------------ moving

    private fun present(t: MorningTask, firstTask: Boolean, before: MorningLine = MorningLine.EMPTY, effects: List<MorningEffect> = emptyList()): MorningReply {
        val plan = _state.plan!!
        if (t.status == MorningTaskStatus.PENDING) update(t.taskId) { it.copy(status = MorningTaskStatus.IN_PROGRESS, updatedAt = millis()) }
        _state = _state.copy(currentTaskId = t.taskId, started = true)
        val line = MorningLines.prompt(t, plan.indexOf(t) + 1, plan.tasks.size, firstTask, clock().toLocalDate(), _state.lang)
        return reply(before + line, effects)
    }

    /** After an action on [taskId]: if it was the current task, go to the next one. */
    private fun afterAction(taskId: String, line: MorningLine): MorningReply {
        if (!_state.started || (_state.currentTaskId != null && _state.currentTaskId != taskId)) return reply(line)
        if (_state.currentTaskId == null) _state = _state.copy(currentTaskId = taskId)
        return advanceLocked(line)
    }

    private fun advanceLocked(before: MorningLine, effects: List<MorningEffect> = emptyList()): MorningReply {
        val plan = _state.plan ?: return reply(before, effects)
        val from = _state.currentTaskId?.let { id -> plan.tasks.indexOfFirst { it.taskId == id } } ?: -1
        val next = plan.tasks.drop(from + 1).firstOrNull { it.open && it.status == MorningTaskStatus.PENDING }
            ?: plan.tasks.drop(from + 1).firstOrNull { it.open }
        if (next == null) return finish(before, effects)
        return present(next, firstTask = from < 0, before = before, effects = effects)
    }

    private fun finish(before: MorningLine, effects: List<MorningEffect> = emptyList()): MorningReply {
        val plan = _state.plan!!
        val s = summaryOf(plan, candidates)
        _state = _state.copy(finished = true, currentTaskId = null, summary = s)
        return reply(before + MorningLines.complete(s, _state.lang), effects + MorningEffect.Completed)
    }

    // ------------------------------------------------------------ helpers

    private fun currentTask(): MorningTask? = _state.current?.takeIf { it.open }

    /** The task a button / command is about: the card pressed, else the current one, else the first open one. */
    private fun target(taskId: String?): MorningTask? {
        val plan = _state.plan ?: return null
        taskId?.let { id -> return plan.tasks.firstOrNull { it.taskId == id } }
        return currentTask() ?: plan.tasks.firstOrNull { it.open }
    }

    private fun partyOf(id: String?): MorningParty? = id?.let { pid -> snapshot?.parties?.firstOrNull { it.id == pid } }

    /** Parties matching a spoken name: exact, then a whole word / prefix, then one letter off. Never picks between several. */
    private fun matchParty(name: String): List<MorningParty> {
        val parties = snapshot?.parties.orEmpty()
        val n = name.trim().lowercase(Locale.ROOT)
        if (n.isEmpty()) return emptyList()
        parties.filter { it.name.trim().lowercase(Locale.ROOT) == n }.takeIf { it.isNotEmpty() }?.let { return it }
        parties.filter { p ->
            val pn = p.name.lowercase(Locale.ROOT)
            pn.split(' ').any { it == n } || pn.startsWith("$n ") || n.startsWith("$pn ")
        }.takeIf { it.isNotEmpty() }?.let { return it }
        return parties.filter { n.length >= 4 && editDistance(it.name.lowercase(Locale.ROOT), n) <= 1 }
    }

    private fun update(taskId: String, change: (MorningTask) -> MorningTask) {
        val plan = _state.plan ?: return
        val tasks = plan.tasks.map { if (it.taskId == taskId) change(it).copy(updatedAt = millis()) else it }
        val newPlan = plan.copy(tasks = tasks)
        _state = _state.copy(plan = newPlan, summary = summaryOf(newPlan, candidates))
        dirty = true
    }

    @Volatile
    private var dirty = false

    /** A task kept under another day (a reminder queued offline yesterday). */
    private suspend fun updateStored(taskId: String, change: (MorningTask) -> MorningTask) {
        val biz = taskId.substringBefore('|')
        val day = taskId.substringAfterLast('|').toLongOrNull() ?: return
        val tasks = store.load(biz, day)
        if (tasks.none { it.taskId == taskId }) return
        store.save(biz, day, tasks.map { if (it.taskId == taskId) change(it).copy(updatedAt = millis()) else it })
    }

    /** Saves the list after a change (called by the public entry points). */
    private suspend fun persist() {
        if (!dirty) return
        val plan = _state.plan ?: return
        store.save(plan.businessId, plan.businessDate, plan.tasks)
        dirty = false
    }

    private fun reply(line: MorningLine, effects: List<MorningEffect> = emptyList()) = MorningReply(line, _state.mode, effects)

    private fun millis() = clock().toInstant().toEpochMilli()

    private fun resetSession() {
        awaiting = null
        postingDraftId = null
        _state = _state.copy(started = false, currentTaskId = null, paymentDraft = null, whatsApp = null, askingRemindFor = null, finished = false)
    }

    private fun summaryOf(plan: MorningPlan, all: List<MorningTask>): MorningSummary {
        val today = plan.businessDate
        val parties = snapshot?.parties.orEmpty()
        fun dueBy(p: MorningParty): Boolean {
            val due = (p.docs.filter { it.outstanding > 0.005 }.mapNotNull { it.dueDay } + listOfNotNull(p.nextDueDay)).minOrNull()
            return p.pending > 0.005 && due != null && due <= today
        }
        return MorningSummary(
            collections = parties.filter { it.kind == MorningPartyKind.CUSTOMER && dueBy(it) }.sumOf { it.pending },
            payments = parties.filter { it.kind == MorningPartyKind.SUPPLIER && dueBy(it) }.sumOf { it.pending },
            lowStockProducts = all.count { it.taskType == MorningTaskType.LOW_STOCK },
            reminders = all.count { it.taskType == MorningTaskType.REMINDER },
            followUps = all.count { it.taskType == MorningTaskType.PAYMENT_FOLLOWUP },
            total = plan.tasks.size,
            completed = plan.tasks.count { it.status == MorningTaskStatus.COMPLETED },
            postponed = plan.tasks.count { it.status == MorningTaskStatus.POSTPONED },
            skipped = plan.tasks.count { it.status == MorningTaskStatus.SKIPPED },
            failed = plan.tasks.count { it.status == MorningTaskStatus.FAILED },
            pending = plan.tasks.count { it.open },
        )
    }

    private fun editDistance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 1) return 2
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            cur.copyInto(prev)
        }
        return prev[b.length]
    }

    companion object {
        /** At most this many open tasks in one morning list (highest priority first). */
        const val MAX_TASKS = 15

        const val ACTION_RESOLVED = "RESOLVED_IN_BOOKS"
        const val ACTION_DONE = "MARKED_DONE"
        const val ACTION_SKIPPED = "SKIPPED"
        const val ACTION_CALL_REQUESTED = "CALL_REQUESTED"
        const val ACTION_CALL = "CALL_DIALER_OPENED"
        const val ACTION_WHATSAPP_PREPARED = "WHATSAPP_PREPARED"
        const val ACTION_WHATSAPP = "WHATSAPP_OPENED"
        const val ACTION_POSTPONED = "REMINDER_SET"
        const val ACTION_VIEWED = "VIEWED"
        const val ACTION_PAYMENT = "PAYMENT_RECORDED"

        /** The reminder title in the existing reminder list ("Collect from Kumar — ₹12,000"). */
        fun reminderTitle(t: MorningTask): String {
            val a = t.facts.amount?.let { com.shopai.app.brain.KaiFormat.rupees(it) }
            return when (t.taskType) {
                MorningTaskType.COLLECT_PAYMENT, MorningTaskType.PAYMENT_FOLLOWUP -> "Collect from ${t.title}${a?.let { " — $it" }.orEmpty()}"
                MorningTaskType.SUPPLIER_PAYMENT -> "Pay ${t.title}${a?.let { " — $it" }.orEmpty()}"
                MorningTaskType.LOW_STOCK -> "Reorder ${t.title}"
                MorningTaskType.EXPIRY -> "Check expiry: ${t.title}"
                MorningTaskType.REMINDER -> t.title
                MorningTaskType.PENDING_DRAFT -> "Review draft: ${t.title}"
            }
        }

    }
}
