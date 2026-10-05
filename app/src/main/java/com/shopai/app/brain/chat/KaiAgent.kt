package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiLanguage
import com.shopai.app.brain.KaiMood
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.KaiCalculator
import com.shopai.app.brain.tools.KaiCommand
import com.shopai.app.brain.tools.KaiCommands
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiTime
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.KaiWhen
import com.shopai.app.brain.tools.MoneyKind
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.Repeat
import com.shopai.app.brain.tools.ScheduleResult
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** A button under a Kai message. */
data class KaiButton(val label: String, val action: KaiAction, val primary: Boolean = false, val enabled: Boolean = true)

/** What a button does. Financial ones go back to the agent; the rest are phone actions the screen performs. */
sealed interface KaiAction {
    data class ConfirmPlan(val key: String) : KaiAction
    data class CancelPlan(val key: String) : KaiAction
    data class EditPlan(val key: String) : KaiAction
    /** The owner picked who / what an ambiguous payment is. */
    data class ChoosePlan(val requestKey: String, val kind: PlanKind, val partyId: String?, val partyName: String) : KaiAction
    data class CancelRequest(val requestKey: String) : KaiAction
    /** Opens the phone's dialer — the owner presses call; Kai never calls by itself. */
    data class Dial(val name: String, val phone: String?) : KaiAction
    data object OpenScanner : KaiAction
    data object OpenAlarmSettings : KaiAction
    data object OpenNotificationSettings : KaiAction
    // ---- reminders (the reminder engine) ----
    data class CancelReminder(val id: String) : KaiAction
    /** Change a reminder just set: Kai asks the new time. */
    data class EditReminder(val id: String) : KaiAction
    data class CompleteReminder(val id: String) : KaiAction
    /** A new reminder is set only after this (Confirm on "… reminder set pannalama?"). */
    data class ConfirmReminder(val requestKey: String) : KaiAction
    /** Change a reminder's time before it is set. */
    data class EditReminderRequest(val requestKey: String) : KaiAction
    /** Android's "full-screen alerts" setting for Kai Urgent Action Mode (Android 14+). */
    data object OpenFullScreenSettings : KaiAction
    data class SnoozeReminder(val id: String, val minutes: Long) : KaiAction
    /** A time chosen for a reminder that was asked without one. */
    data class RemindAt(val requestKey: String, val at: LocalDateTime) : KaiAction
    /** Which of several people with the same name. */
    data class PickContact(val requestKey: String, val index: Int) : KaiAction
    /** Which of several matching reminders to cancel / complete / snooze / change. */
    data class PickReminder(val requestKey: String, val id: String, val op: KaiReminderAssistant.Op, val minutes: Long) : KaiAction
    // ---- the shop's own language (private memory of this business) ----
    data class LearnMeaning(val key: String, val meaning: com.shopai.app.brain.memory.KaiMeaning) : KaiAction
    data class LearnEntity(val key: String, val entityId: String) : KaiAction
    data class NotThis(val key: String) : KaiAction
    data class OnlyNow(val key: String) : KaiAction
    data class LearnAlias(val phrase: String, val entityId: String) : KaiAction
    /** "'ramba' na 'romba'" — the owner said yes: remember the word (this owner only). */
    data class LearnWord(val key: String) : KaiAction
    // ---- stock in / out (draft → confirm → inventory engine) ----
    data class ConfirmStock(val key: String) : KaiAction
    data class CancelStock(val key: String) : KaiAction
    /** Change the draft's quantity / unit before confirming (the screen's edit form). */
    data class EditStock(val key: String) : KaiAction
    /** A product that isn't in the inventory yet: the camera opens, the photo fills an editable form (STOCK_IN_CAMERA). */
    data class OpenStockCamera(val prefill: StockPrefill) : KaiAction
    /** The same form, typed (no photo). */
    data class CreateProduct(val prefill: StockPrefill) : KaiAction
    // ---- Morning Work (read only; every action still asks the owner) ----
    /** The Morning Work screen ([start] = go straight to the first task). */
    data class OpenMorningWork(val start: Boolean) : KaiAction
    /** Open a record screen: CUSTOMER / SUPPLIER / PRODUCT (with its id), INVENTORY or REMINDERS. */
    data class OpenRecord(val kind: String, val id: String = "") : KaiAction
    /** "Remind Me" on a morning task: Kai asks when (nothing is set without the owner's time). */
    data class RemindAbout(val task: String) : KaiAction
    /** Skip: the next morning task. */
    data class MorningNext(val index: Int) : KaiAction
    /** "Add Stock" on a low-stock task: Kai asks how many came in (a draft, confirmed by the owner). */
    data class AddStockFor(val productId: String) : KaiAction
    /** "Save: 1 box = 12 pieces" — keep this product's conversion (only after the owner taps it). */
    data class SaveUnitConversion(val productId: String, val productName: String, val unit: String, val perUnit: String, val baseUnit: String) : KaiAction
    /** "Owner, `petti` na box-ah?" — the unit the owner picked ("" = something else: Kai asks). */
    data class PickUnit(val key: String, val unit: String) : KaiAction
    /** After "Kai naan enna teach panniruken?": EDIT / FORGET / KEEP. */
    data class ManageMemory(val op: String) : KaiAction
    /** The owner's Morning Routine Kai proposed: [Save] is the only way it changes. */
    data class SaveRoutine(val key: String) : KaiAction
    data class RoutineNotNow(val key: String) : KaiAction
}

/**
 * Kai's Morning Work: the signed-in business's morning brief, from the one
 * MorningWorkEngine (the app reads the snapshot for the current login).
 * Null: no business data could be read — Kai says so, never invents.
 */
fun interface KaiMorningAccess {
    suspend fun brief(lang: KaiLang): com.shopai.app.brain.morning.MorningBrief?
}

/** What Kai already knows for a new product's form (everything stays editable). */
data class StockPrefill(val name: String, val qty: BigDecimal? = null, val unit: String? = null, val said: String = "")

/** The owner's checked form for a new product (from a photo or typed). */
data class ProductForm(
    val name: String,
    val category: String,
    val variant: String?,
    val brand: String?,
    val unit: String,
    val weight: String?,
    val qty: BigDecimal?,
    val imageUri: String? = null,
    val said: String = "",
    /** "Pack of 10", "150 g x 2" — as printed / typed. */
    val packSize: String? = null,
)

/** A card under a Kai message: the details of a draft / action, and its buttons. */
data class KaiCard(val lines: List<String>, val buttons: List<KaiButton>, val warning: String? = null)

/**
 * One Kai turn: the reply, and a card when there is something to confirm or do.
 * [direct]: a phone action the screen performs at once (open the bill scanner /
 * the product camera) — never a money action, those always wait for Confirm.
 */
data class KaiTurn(val reply: ChatReply, val card: KaiCard? = null, val plan: ActionPlan? = null, val direct: KaiAction? = null)

/** A specific field Kai is waiting for in the current conversation. */
internal enum class KaiPendingQuestion { DUE_DATE }
internal enum class KaiConversationPaymentDirection { PAYMENT_IN, PAYMENT_OUT }

/** Conversation facts only. Business facts still come from KaiBooks/KaiTools and learned phrases from KaiPrivateMemory. */
internal data class KaiConversationState(
    var currentIntent: String? = null,
    var currentAction: String? = null,
    var lastRelevantEntity: String? = null,
    var lastPerson: String? = null,
    var lastCustomer: String? = null,
    var lastSupplier: String? = null,
    var lastProduct: String? = null,
    var lastAmount: BigDecimal? = null,
    var lastUnit: String? = null,
    var lastDate: LocalDate? = null,
    var lastTime: LocalTime? = null,
    var lastPaymentDirection: String? = null,
    var lastPaymentMode: PaymentMode? = null,
    var lastQuestion: String? = null,
    var pendingQuestion: KaiPendingQuestion? = null,
    var pendingEntity: String? = null,
    var pendingAmount: BigDecimal? = null,
    var pendingPaymentDirection: KaiConversationPaymentDirection? = null,
    var pendingDraft: ActionPlan? = null,
    var pendingConfirmation: Boolean = false,
    var pendingCorrection: Boolean = false,
    var lastBusinessTopic: String? = null,
    var previousBusinessContext: String? = null,
    var previousTopicBeforeCalculator: String? = null,
    var conversationTurn: Long = 0,
) {
    fun clear() {
        currentIntent = null; currentAction = null; lastRelevantEntity = null
        lastPerson = null; lastCustomer = null; lastSupplier = null; lastProduct = null
        lastAmount = null; lastUnit = null; lastDate = null; lastTime = null
        lastPaymentDirection = null; lastPaymentMode = null; lastQuestion = null
        pendingQuestion = null; pendingEntity = null; pendingAmount = null; pendingPaymentDirection = null
        pendingDraft = null; pendingConfirmation = false; pendingCorrection = false
        lastBusinessTopic = null; previousBusinessContext = null; previousTopicBeforeCalculator = null
        conversationTurn = 0
    }
}

/** Context-aware safety language for an already open action draft. */
internal object KaiConversationSemantics {
    private val amountPattern = Regex("""(?i)(?:₹|rs\.?\s*)?([0-9][0-9,]*(?:\.[0-9]+)?\s*k?)""")

    fun correctionAmount(text: String): BigDecimal? {
        val n = text.trim().lowercase(Locale.ROOT)
        val correctionCue = listOf("illai", "illa", "actually", "wrong", "no,", "no ", "amount", "only", "dhaan", "thaan", "received", "மட்டும்", "இல்லை")
            .any(n::contains)
        val raw = amountPattern.findAll(n).map { it.groupValues[1].replace(",", "").replace(Regex("\\s+"), "") }.toList()
        if (raw.size != 1 || (raw.single().endsWith("k") && !raw.single().dropLast(1).toBigDecimalOrNull().let { it != null })) return null
        if (!correctionCue && text.trim().trimEnd('.', '!', '?').toBigDecimalOrNull() == null && raw.single() != text.trim().lowercase(Locale.ROOT)) return null
        val token = raw.single()
        val multiplier = if (token.endsWith("k")) BigDecimal("1000") else BigDecimal.ONE
        val number = token.removeSuffix("k").toBigDecimalOrNull() ?: return null
        return number.multiply(multiplier).setScale(2, java.math.RoundingMode.HALF_UP)
    }

    fun cancelsDraft(text: String): Boolean {
        val n = text.lowercase(Locale.ROOT).trim().replace(Regex("[.!?]+$"), "").replace(Regex("\\s+"), " ")
        if (correctionAmount(text) != null && listOf("illai", "illa", "actually", "wrong", "no", "இல்லை").any(n::contains)) return false
        return n in setOf("venda", "venam", "venaam", "vendaam", "vendam", "cancel", "skip", "vidunga", "viddu", "no", "no need", "illa", "illai", "வேண்டாம்") ||
            listOf("cancel pannu", "cancel pannunga", "don't add", "dont add", "add panna vendam", "add pannadhe", "வேண்டாம்").any(n::contains)
    }
}

/**
 * KAI — the owner's AI business operating assistant.
 *
 *   owner speaks → Kai understands (KaiCommands, rules — no paid AI) →
 *   question / calculation / business action / reminder / document →
 *   the right tool (KaiTools over the books engine, reminders, phone) →
 *   financial action → DRAFT + CONFIRM → engine → verified result → reply.
 *
 * Kai never invents a number: business facts come from the books, maths
 * from the calculator, and anything that can't be verified is said so.
 * Questions Kai doesn't treat as actions go to the Business Brain.
 */
class KaiAgent(
    private val brain: KaiBusinessBrain,
    private val books: KaiBooks,
    private val tools: KaiTools,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    /** The signed-in business's private language (null: none — global Kai only). */
    private val memory: KaiMemoryAccess? = null,
    /** Morning Work (MORNING_WORK intent); null: not available here. */
    private val morning: KaiMorningAccess? = null,
) {
    /** [qty] / [unit] are what the inventory gets (the product's base unit); [conv] = how the owner's quantity became that. */
    private data class StockPlan(
        val key: String, val product: com.shopai.app.brain.tools.ProductRef, val qty: BigDecimal, val incoming: Boolean, val said: String, val unit: String,
        val conv: com.shopai.app.brain.tools.UnitResolution.Ok? = null,
    )
    /** "Owner, 1 box-la evlo pieces irukku?" — waiting for the number (nothing is drafted without it). */
    private data class UnitQuestion(val product: com.shopai.app.brain.tools.ProductRef, val parts: List<com.shopai.app.brain.tools.QtyPart>, val incoming: Boolean, val said: String, val unit: String, val key: String)
    private var unitQuestion: UnitQuestion? = null
    /** Conversions the owner gave in this conversation, per product (productId → unit → base units in 1). */
    private val sessionUnits = HashMap<String, MutableMap<String, BigDecimal>>()
    /** "Colgate stock vandhiruku" with no number: Kai asked how many. */
    private data class StockQuestion(val product: com.shopai.app.brain.tools.ProductRef, val incoming: Boolean, val said: String)

    private val learner = memory?.let { KaiMemoryAssistant(it) }?.also { l ->
        // Learning events go to the action log: the meaning only — never the conversation.
        l.onLearned = { m -> tools.log(com.shopai.app.brain.tools.KaiIntents.LEARN_PERSONAL_TERM, "kai memory",
            "${m.triggerPhrase} = ${m.meaning?.en ?: m.meaningValue}", ActionStatus.CONFIRMED, m.id, null) }
    }
    /** The draft Kai just prepared (what the owner said, PAYMENT / STOCK) — "Illai, …" right after it corrects Kai. */
    private var lastDraft: Pair<String, String>? = null
    /** The owner's Morning Routine (kept in their private memory). */
    private val routine = memory?.let { KaiMorningRoutineAssistant(it) }
    /** Kai's previous answer was the morning brief. */
    private var briefJustShown = false
    private val stockPlans = LinkedHashMap<String, StockPlan>()
    /** The product just talked about ("Colgate stock low ah?" … "20 add pannu"). */
    private var lastProduct: com.shopai.app.brain.tools.ProductRef? = null
    private var stockQuestion: StockQuestion? = null
    /** The last morning brief (Skip → its next task). */
    private var lastBrief: com.shopai.app.brain.morning.MorningBrief? = null
    private data class PaymentRequest(
        val key: String, val name: String?, val amount: BigDecimal?, val outgoing: Boolean,
        val mode: PaymentMode, val said: String, val directionKnown: Boolean = true,
    )

    private val plans = LinkedHashMap<String, ActionPlan>()
    private val requests = LinkedHashMap<String, PaymentRequest>()
    /** A payment still missing its amount or person; the next message can complete it. */
    private var incompletePayment: PaymentRequest? = null
    internal val conversationState = KaiConversationState()
    private var conversationScope: String? = null
    /** Reminders: the one reminder conversation (also used by the Speak screen). */
    private val reminders = KaiReminderAssistant(tools, books, now)

    fun plan(key: String): ActionPlan? = plans[key]

    /** Kai is waiting for the owner's answer about their own words (voice answers go to the same conversation). */
    val waitingForLearningAnswer: Boolean get() = learner?.isAsking == true || routine?.waiting == true

    /** A reminder rang and the owner opened it: its message with Call / Snooze / Done. */
    fun rang(id: String, lang: KaiLang): KaiTurn? = reminders.rang(id, lang)

    suspend fun ask(raw: String): KaiTurn {
        val said = raw.trim()
        val lang = KaiLanguage.forChat(said)
        val scope = runCatching { memory?.current()?.let { "${it.businessId.orEmpty()}:${it.ownerId.orEmpty()}" } }.getOrNull()
        if (scope != null && conversationScope != null && scope != conversationScope) {
            plans.values.toList().forEach { runCatching { tools.discard(it) } }
            reset()
        }
        if (scope != null) conversationScope = scope
        conversationState.conversationTurn++
        val afterBrief = briefJustShown
        briefJustShown = false

        // An open finance draft owns short replies. Resolve corrections before
        // cancellation, and both before private-memory teaching or normal routing.
        val openPayment = plans.values.singleOrNull()
        if (openPayment != null) {
            conversationState.pendingDraft = openPayment
            conversationState.pendingConfirmation = true
            val correctedAmount = KaiConversationSemantics.correctionAmount(said)
            if (correctedAmount != null && correctedAmount.compareTo(openPayment.amount) != 0) {
                conversationState.pendingCorrection = true
                val outgoing = openPayment.kind == PlanKind.PAYMENT_OUT || openPayment.kind == PlanKind.CREDIT_GIVEN
                return revise(openPayment.key, openPayment.partyName, correctedAmount, openPayment.mode, outgoing, lang)
                    .also { conversationState.pendingCorrection = false }
            }
            if (KaiConversationSemantics.cancelsDraft(said)) {
                return act(KaiAction.CancelPlan(openPayment.key), lang)!!
            }
        }

        pendingDueDateAnswer(said, lang)?.let { return it }

        val draftBefore = lastDraft
        lastDraft = null
        // "Illai Kai, avan bill mattum kuduthaan" right after a draft: Kai misread — the draft is dropped and Kai asks what to remember.
        if (draftBefore != null && learner != null) {
            val people = runCatching { books.snapshot()?.people.orEmpty() }.getOrDefault(emptyList())
            learner.correctionAfter(said, draftBefore.first, draftBefore.second, people, lang)?.let { turn ->
                plans.values.filter { it.said == draftBefore.first }.forEach { p -> plans.remove(p.key); runCatching { tools.discard(p) } }
                stockPlans.values.filter { it.said == draftBefore.first }.map { it.key }.forEach { stockPlans.remove(it) }
                tools.log(com.shopai.app.brain.tools.KaiIntents.LEARN_PERSONAL_TERM, "draft", "owner said it was wrong: draft dropped", ActionStatus.CANCELLED, null, null)
                return turn
            }
        }
        // The owner's own language first: answers to Kai's question, teaching,
        // forgetting, and meaning corrections must be resolved before normal
        // business/action routing can consume those messages.
        learner?.before(said, lang)?.let { step ->
            return when (step) {
                is MemoryStep.Reply -> step.turn
                is MemoryStep.Rerun -> withPrefix(step.prefix, ask(step.text))
            }
        }
        // The owner's confirmed words → words the global Kai core understands.
        val applied = learner?.apply(said) ?: said
        // Words the owner told Kai are NOT a payment / stock change: nothing is drafted, and Kai says why.
        learner?.correctionUsed?.let { c ->
            val what = c.meaningType.removePrefix("NOT_").lowercase()
            val What = what.replaceFirstChar { it.uppercase() }
            tools.log(com.shopai.app.brain.tools.KaiIntents.LEARN_PERSONAL_TERM, "kai memory", "${c.triggerPhrase}: not $what (owner's correction)", ActionStatus.ANSWERED, c.id, null)
            return say(lang, KaiMood.NEUTRAL, null,
                ta = "ஓனர், `${c.triggerPhrase}` $what இல்ல-னு நீங்க சொல்லியிருக்கீங்க 👍 அதனால எதுவும் பதிவு பண்ணல. $What-னா தெளிவா சொல்லுங்க.",
                tl = "Owner, `${c.triggerPhrase}` $what illa-nu neenga sollirukeenga 👍 Adhanala edhuvum record pannala. $What-na theliva sollunga.",
                en = "Owner, you told me `${c.triggerPhrase}` isn't a $what 👍 So nothing was recorded. If it is a $what, please say it plainly.")
        }
        // Spoken Tamil script ("2 நிமிஷத்துல … ரிமைண்டர் பண்ணு") → the same words as typed Tanglish: one brain for voice and text.
        val text = com.shopai.app.brain.tools.KaiSpokenWords.normalize(applied)
        val at = now()
        val people = runCatching { books.snapshot()?.people.orEmpty() }.getOrDefault(emptyList())
        val products = runCatching { tools.products() }.getOrNull()

        // A named financial action with no amount is a missing-field prompt,
        // not a balance lookup and never an implicit transaction.
        if (KaiCommands.personIn(text, people) != null && isAmountAddRequest(text) &&
            com.shopai.app.brain.KaiUnderstanding.amountsIn(text, at.toLocalDate()).none { it > 0 }) {
            val person = KaiCommands.personIn(text, people)!!
            return payment(PaymentRequest(newKey(), person, null, outgoing = false, mode = PaymentMode.CASH, said = text, directionKnown = false), lang)
        }

        contextualReceivable(text, lang, people)?.let { return it }
        contextualProductFollowUp(text, lang)?.let { return it }

        // Completing what Kai just asked for (a reminder's time, a stock quantity, a payment's amount / name).
        reminders.continueWith(text, lang)?.let { return it }
        stockQuestion?.let { q ->
            stockQuestion = null
            com.shopai.app.brain.tools.KaiStock.partsIn(text).takeIf { it.isNotEmpty() && KaiCommands.route(text, at, people).let { c -> c == KaiCommand.Question || c is KaiCommand.Calculate } }
                ?.let { parts -> return draftParts(q.product, parts, q.incoming, "${q.said} · $text", lang) }
        }
        // "1 box-la evlo pieces?" → "12" / "12 pieces" / "1 box = 12 pieces": the draft, converted (still waiting for Confirm).
        unitQuestion?.let { q ->
            unitQuestion = null
            perUnitAnswer(text, q.product)?.let { f ->
                sessionUnits.getOrPut(q.product.id) { HashMap() }[q.unit] = f
                return draftParts(q.product, q.parts, q.incoming, q.said, lang, q.key, learned = true)
            }
        }
        // "1 box = 10 pieces" while a converted draft is open: the conversion is corrected and the draft recalculated.
        conversionEdit(text, lang)?.let { return it }
        incompletePayment?.let { p ->
            val cmd = KaiCommands.route(text, at, people)
            if (!p.directionKnown && cmd is KaiCommand.Payment) {
                incompletePayment = null
                val amount = p.amount ?: cmd.amount
                val name = p.name ?: cmd.name
                if (amount != null && !name.isNullOrBlank()) return payment(p.copy(amount = amount, name = name, outgoing = cmd.outgoing,
                    mode = if (cmd.modeSaid) cmd.mode else p.mode, directionKnown = true), lang)
            }
            if (cmd == KaiCommand.Question || cmd is KaiCommand.Calculate) {
                val amount = p.amount ?: com.shopai.app.brain.KaiUnderstanding.amountsIn(text, at.toLocalDate()).singleOrNull()?.let { BigDecimal.valueOf(it) }
                val name = p.name ?: KaiCommands.personIn(text, people) ?: text.takeIf { it.split(' ').size <= 3 && it.none(Char::isDigit) }
                if (amount != p.amount || name != p.name) {
                    if (!p.directionKnown && amount != null) {
                        incompletePayment = p.copy(amount = amount, name = name)
                        return askPaymentDirection(lang, name)
                    }
                    incompletePayment = null
                    return payment(p.copy(amount = amount, name = name), lang)
                }
            }
        }

        // The owner's Morning Routine ("Stock first, collection next") — proposed, saved only on [Save].
        routine?.handle(said, text, lang, afterBrief, people)?.let { return it }

        // MORNING_WORK is a core intent: chat, voice and a scheduled morning brief all use the one MorningWorkEngine.
        // An explicit reminder ("tomorrow morning remind me …", "morning 10 manikku … remind pannu") stays a reminder.
        val morningAsk = com.shopai.app.brain.morning.MorningCommands.morningRequest(said) ?: com.shopai.app.brain.morning.MorningCommands.morningRequest(text)
        if (morningAsk != null && !com.shopai.app.brain.tools.KaiReminderUnderstanding.mentionsReminder(text)) return morningWork(morningAsk, text, said, lang, people)

        // Priority: pending action/context → reminder → stock → bill scan → call → money → calculator → questions → memory → conversation.
        com.shopai.app.brain.tools.KaiReminderUnderstanding.understand(text, at, people)?.let { return reminders.handle(it, lang) }
        if (products != null) {
            // "Colgate 2 petti vandhiruku" with a unit word Kai doesn't know: ask (box? packet?) — never guess the quantity's unit.
            learner?.unknownUnit(text, said, lang, products)?.let { return it }
            // SCAN_STOCK: "Colgate photo edu", "stock photo edu", "new stock add pannu" → the product camera at once.
            com.shopai.app.brain.tools.KaiStock.scanRequest(text, products)?.let { name -> return stockCamera(name, said, lang, products) }
            stockChange(text, said, lang, products, people)?.let { return it }
        }
        if (com.shopai.app.brain.tools.KaiIntents.isBillScan(text)) {
            val cmd = KaiCommands.route(text, at, people)
            return scan((cmd as? KaiCommand.ScanBill)?.classifyOnly == true, lang, said)
        }
        val cmd = KaiCommands.route(applied, at, people).let { c -> if (c == KaiCommand.Question && text != applied) KaiCommands.route(text, at, people) else c }
        return when (cmd) {
            is KaiCommand.Calculate -> {
                if (conversationState.lastBusinessTopic != null) conversationState.previousTopicBeforeCalculator = conversationState.lastBusinessTopic
                conversationState.currentIntent = "CALCULATOR"
                calculate(cmd.answer, lang, text)
            }
            is KaiCommand.Payment -> payment(PaymentRequest(newKey(), cmd.name, cmd.amount, cmd.outgoing, cmd.mode, text), lang)
            is KaiCommand.Reminder -> reminders.handle(cmd.request, lang)
            is KaiCommand.Call -> call(cmd.name, lang, said)
            is KaiCommand.ScanBill -> scan(cmd.classifyOnly, lang, said)
            is KaiCommand.Stock -> {
                cmd.product?.let { rememberProduct(it) }
                stock(cmd.product, lang)
            }
            KaiCommand.LowStock -> lowStock(lang)
            is KaiCommand.MoneyBalance -> money(cmd.kind, lang)
            KaiCommand.TopProducts -> topProducts(text, lang, at.toLocalDate(), people)
            KaiCommand.Question -> {
                rememberNamedBusinessContext(text, people)
                learner?.before(said, lang)?.let { step ->
                    return when (step) {
                        is MemoryStep.Reply -> step.turn
                        is MemoryStep.Rerun -> withPrefix(step.prefix, ask(step.text))
                    }
                }
                conversation(text, said, lang, at)
                    ?: learner?.unknown(text, said, lang, people, products.orEmpty().map { it.name })
                    ?: run {
                        val answer = questionOrLearn(applied, text, said, lang, at.toLocalDate(), people, products.orEmpty())
                        if (answer.reply.intent != ChatIntent.UNKNOWN) conversationState.currentIntent = "BUSINESS_QUERY"
                        answer
                    }
            }
        }
    }

    /** Save a receivable mentioned in conversation as session context only; it is not a ledger entry. */
    private suspend fun contextualReceivable(text: String, lang: KaiLang, people: List<String>): KaiTurn? {
        val cmd = KaiCommands.route(text, now(), people)
        val hasReceivableMeaning = Regex("(?i)\\b(tharanum|tharanum|pending|baaki|bakki|owe|owes|collect|varanum)\\b|தரணும்|பாக்கி")
            .containsMatchIn(text)
        val person = KaiCommands.personIn(text, people)
        val amount = com.shopai.app.brain.KaiUnderstanding.amountsIn(text, now().toLocalDate())
            .filter { it > 0 }.singleOrNull()?.let { BigDecimal.valueOf(it).setScale(2, java.math.RoundingMode.HALF_UP) }
        if (hasReceivableMeaning && person != null && amount != null && cmd == KaiCommand.Question) {
            val ownerIsRecipient = Regex("(?i)\\b(enakku|enaku|to me|for me)\\b").containsMatchIn(text)
            val direction = if (ownerIsRecipient || !Regex("(?i)\\btharanum\\b|தரணும்").containsMatchIn(text)) {
                KaiConversationPaymentDirection.PAYMENT_IN
            } else {
                KaiConversationPaymentDirection.PAYMENT_OUT
            }
            conversationState.currentIntent = "RECEIVABLE_CONTEXT"
            conversationState.currentAction = if (direction == KaiConversationPaymentDirection.PAYMENT_OUT) "PAY" else "COLLECT"
            conversationState.lastBusinessTopic = "RECEIVABLE_CONTEXT"
            conversationState.previousBusinessContext = "RECEIVABLE_CONTEXT"
            conversationState.lastPerson = person
            conversationState.lastCustomer = person
            conversationState.lastRelevantEntity = person
            conversationState.lastAmount = amount
            conversationState.lastPaymentDirection = if (direction == KaiConversationPaymentDirection.PAYMENT_OUT) "OUT" else "IN"
            conversationState.lastQuestion = text
            conversationState.pendingQuestion = KaiPendingQuestion.DUE_DATE
            conversationState.pendingEntity = person
            conversationState.pendingAmount = amount
            conversationState.pendingPaymentDirection = direction
            val value = KaiFormat.rupees(amount.toDouble())
            return say(lang, KaiMood.EXPLAINING, null,
                ta = if (direction == KaiConversationPaymentDirection.PAYMENT_OUT) "$person-க்கு $value கொடுக்கணும்னு சொல்றீங்க Owner. Due date தெரியல; எந்த தேதிக்குள் pay பண்ணணும்?" else "$person கிட்டிருந்து $value வரணும்னு சொல்றீங்க Owner. Due date எனக்குத் தெரியல; எந்த தேதிக்குள் collect பண்ணணும்?",
                tl = if (direction == KaiConversationPaymentDirection.PAYMENT_OUT) "Owner, $person-ku $value pay pannanum-nu note pannikiren. Due date theriyala; endha date-kulla pay pannanum?" else "Owner, $person kitta $value collect pannanum-nu note pannikiren. Due date theriyala; endha date-kulla collect pannanum?",
                en = if (direction == KaiConversationPaymentDirection.PAYMENT_OUT) "Got it, Owner. You need to pay $value to $person. I don't have a due date; what date should I use?" else "Got it, Owner. You said $value is due from $person. I don't have a due date for that amount; what date should I use?")
        }

        if (conversationState.lastBusinessTopic != "RECEIVABLE_CONTEXT" || conversationState.lastPerson == null || conversationState.lastAmount == null) return null
        val followUp = Regex("(?i)\\b(eppa|eppo|when|collect|varum|tharuvaan|tharuvar|adha|adhu|athu|avan|avar|amount)\\b|எப்ப|அத")
            .containsMatchIn(text)
        if (!followUp) return null
        val personName = KaiCommands.personIn(text, people) ?: conversationState.lastPerson!!
        val value = KaiFormat.rupees(conversationState.lastAmount!!.toDouble())
        conversationState.currentIntent = "RECEIVABLE_FOLLOW_UP"
        conversationState.currentAction = "ASK_DUE_DATE"
        conversationState.lastQuestion = text
        conversationState.lastPerson = personName
        conversationState.lastCustomer = personName
        conversationState.lastRelevantEntity = personName
        return say(lang, KaiMood.CLARIFY, null,
            ta = "இந்த $value-ஐ $personName கிட்ட collect பண்ண due date கேக்குறீங்களா Owner? அந்தத் தேதி record-ல இல்லை.",
            tl = "Owner, indha $value $personName kitta collect panna due date kekkureengala? Andha date record-la illa.",
            en = "Are you asking for the due date to collect this $value from $personName, Owner? I don't have that date recorded.")
    }

    /** Resolve a parsed calendar date while a specific due-date field is pending. */
    private fun pendingDueDateAnswer(text: String, lang: KaiLang): KaiTurn? {
        if (conversationState.pendingQuestion != KaiPendingQuestion.DUE_DATE) return null
        val at = now()
        val parsed = KaiTime.parse(text, at)?.takeIf { it.daySpecified } ?: return null
        val entity = conversationState.pendingEntity ?: return null
        val amount = conversationState.pendingAmount ?: return null
        val direction = conversationState.pendingPaymentDirection ?: return null
        val date = parsed.at.toLocalDate()
        val dateText = KaiFormat.date(date, lang, at.toLocalDate())
        val amountText = KaiFormat.rupees(amount.toDouble())

        conversationState.pendingQuestion = null
        conversationState.pendingEntity = null
        conversationState.pendingAmount = null
        conversationState.pendingPaymentDirection = null
        conversationState.lastDate = date
        conversationState.lastPerson = entity
        conversationState.lastRelevantEntity = entity
        conversationState.lastCustomer = entity
        conversationState.lastAmount = amount
        conversationState.lastPaymentDirection = if (direction == KaiConversationPaymentDirection.PAYMENT_OUT) "OUT" else "IN"
        conversationState.currentIntent = "DUE_DATE_ANSWER"
        conversationState.currentAction = "DUE_DATE_CAPTURED"
        conversationState.lastQuestion = text

        return say(lang, KaiMood.EXPLAINING, null,
            ta = if (direction == KaiConversationPaymentDirection.PAYMENT_OUT) "$entity-க்கு $amountText pay பண்ண due date $dateText Owner." else "$entity கிட்ட $amountText collect பண்ண due date $dateText Owner.",
            tl = if (direction == KaiConversationPaymentDirection.PAYMENT_OUT) "Owner, $entity-ku $amountText pay panna due date $dateText." else "Owner, $entity kitta $amountText collect panna due date $dateText.",
            en = if (direction == KaiConversationPaymentDirection.PAYMENT_OUT) "Got it, Owner. Pay $amountText to $entity by $dateText." else "Got it, Owner. Collect $amountText from $entity by $dateText.")
    }

    /** Resolve a stock pronoun against the last product mentioned, while reading quantity only from inventory. */
    private suspend fun contextualProductFollowUp(text: String, lang: KaiLang): KaiTurn? {
        val name = conversationState.lastProduct ?: return null
        val lowQuestion = Regex("(?i)\\b(low|kammi|kuraivaa|kuraivu|low-aa|low-ah)\\b|குறைவ")
            .containsMatchIn(text)
        val reference = Regex("(?i)\\b(adhu|adha|athu|athula|this|that|it)\\b|அது|அதுல|இத")
            .containsMatchIn(text)
        if (!lowQuestion || !reference) return null
        val facts = tools.stock(name)
        conversationState.currentIntent = "STOCK_FOLLOW_UP"
        conversationState.lastQuestion = text
        if (facts == null) return unverified(lang, "stock")
        val fact = facts.singleOrNull()
        if (fact == null) return say(lang, KaiMood.CLARIFY, null,
            ta = "$name record-ல stock இல்லை ஓனர். Low stock-ஆ compare பண்ண current stock record வேணும்.",
            tl = "$name record-la stock illa Owner. Low stock-ah compare panna current stock record venum.",
            en = "$name has no stock record, Owner. I need a current stock record before I can compare it with the low-stock level.")
        val reorderAt = fact.reorderAt ?: return say(lang, KaiMood.CLARIFY, null,
            ta = "$name stock ${qty(fact.qty, fact.unit)} இருக்கு ஓனர்; low-ஆ compare பண்ண reorder level set ஆகல.",
            tl = "$name stock ${qty(fact.qty, fact.unit)} irukku Owner; low-ah compare panna reorder level set aagala.",
            en = "$name has ${qty(fact.qty, fact.unit)} in stock, Owner, but no reorder level is set for a low-stock comparison.")
        val isLow = fact.qty <= reorderAt
        return say(lang, if (isLow) KaiMood.CONCERNED else KaiMood.HAPPY, null,
            ta = "$name stock ${qty(fact.qty, fact.unit)} ${if (isLow) "இருக்கு; reorder level-க்கு கீழ" else "இருக்கு; reorder level-க்கு மேல"} ஓனர்.",
            tl = "$name stock ${qty(fact.qty, fact.unit)} irukku; ${if (isLow) "reorder level-kku keezha" else "reorder level-kku mela"} Owner.",
            en = "$name has ${qty(fact.qty, fact.unit)} in stock, ${if (isLow) "at or below" else "above"} its reorder level, Owner.")
    }

    private suspend fun rememberNamedBusinessContext(text: String, people: List<String>) {
        val person = KaiCommands.personIn(text, people) ?: return
        conversationState.lastPerson = person
        conversationState.lastRelevantEntity = person
        conversationState.lastQuestion = text
        conversationState.previousBusinessContext = conversationState.lastBusinessTopic
        conversationState.lastBusinessTopic = "PARTY_QUERY"
    }

    private fun isAmountAddRequest(text: String): Boolean {
        val n = text.lowercase(Locale.ROOT)
        return Regex("(?i)\\b(amount\\s+add|add\\s+(?:the\\s+)?amount|amount\\s+add\\s+pannu|add\\s+pannu|account[- ]?la\\s+podu|account[- ]?la\\s+add|record\\s+(?:this|it))\\b|தொகை.*சேர்|கணக்கில்.*போடு")
            .containsMatchIn(n)
    }

    private fun askPaymentDirection(lang: KaiLang, person: String?): KaiTurn {
        val who = person?.let { "$it-oda" } ?: "indha payment"
        return say(lang, KaiMood.CLARIFY, "payment: direction missing",
            ta = "$who payment-la பணம் உங்களுக்கு வந்ததா, நீங்க கொடுத்தீங்களா ஓனர்?",
            tl = "Owner, $who payment-la money ungalukku vandhucha, illa neenga kudutheengala?",
            en = "Was this payment from $who received by you, or did you give it to them, Owner?")
    }

    private suspend fun rememberProduct(name: String) {
        conversationState.lastProduct = name
        conversationState.lastRelevantEntity = name
        conversationState.lastBusinessTopic = "STOCK_QUERY"
        conversationState.currentIntent = "STOCK_QUERY"
        lastProduct = runCatching { tools.products() }.getOrNull()?.firstOrNull { it.name.equals(name, true) }
    }

    /** "saaptiya?", "enna panra?", "innaiku romba busy", "good morning" — a friend's answer, no business intent forced. */
    private fun conversation(text: String, said: String, lang: KaiLang, at: LocalDateTime): KaiTurn? {
        val answer = KaiSmallTalk.reply(text, lang, at.hour) ?: return null
        tools.log(com.shopai.app.brain.tools.KaiIntents.SMALL_TALK, "conversation", "answered", ActionStatus.ANSWERED, null, said)
        return KaiTurn(ChatReply(answer, KaiMood.HAPPY, ChatIntent.GENERAL_BUSINESS_QUERY))
    }

    /** A business question; when Kai can't read it at all and one word is new, Kai asks what that word means (and learns it). */
    private suspend fun questionOrLearn(applied: String, text: String, said: String, lang: KaiLang, today: LocalDate, people: List<String>,
                                        products: List<com.shopai.app.brain.tools.ProductRef>): KaiTurn {
        val answer = question(applied, lang, today, people)
        if (answer.reply.intent != ChatIntent.UNKNOWN || learner == null) return answer
        val names = (people + products.map { it.name }).flatMap { KaiPrivateMemory.normalize(it).split(' ') }.toSet()
        return learner.unknownWord(text, said, lang) { w -> w in names || KaiLexicon.knows(w) } ?: answer
    }

    private fun withPrefix(prefix: String, turn: KaiTurn) = turn.copy(reply = turn.reply.copy(text = prefix + "\n\n" + turn.reply.text))

    // ------------------------------------------------------------ stock in / out

    private suspend fun stockChange(text: String, said: String, lang: KaiLang, products: List<com.shopai.app.brain.tools.ProductRef>, people: List<String>): KaiTurn? {
        val req = com.shopai.app.brain.tools.KaiStock.understand(text, products)
            // "20 add pannu" after talking about Colgate — never when someone else is named (that is a payment).
            ?: lastProduct?.takeIf { KaiCommands.personIn(text, people) == null }
                ?.let { p -> com.shopai.app.brain.tools.KaiStock.understand("${p.name} $text", products)?.takeIf { it.product?.id == p.id } }
            ?: return null
        // "Kumar account-la stock in 500": a person in the books, not a product — never guessed: Kai asks.
        if (req.product == null && namesPerson(text, req.spokenName, people)) {
            return say(lang, KaiMood.CLARIFY, "stock or payment: asked",
                ta = "ஓனர், இது ${req.spokenName} — payment-ஆ, product stock-ஆ? கொஞ்சம் தெளிவா சொல்லுங்க. எதுவும் சேமிக்கல.",
                tl = "Owner, idhu ${req.spokenName} — payment-ah, illa product stock-ah? Konjam theliva sollunga. Edhuvum save pannala.",
                en = "Owner, is this ${req.spokenName} a payment or product stock? Please say it a little more clearly — nothing was saved.")
        }
        val product = req.product ?: return unknownProduct(req, said, lang, products)
        lastProduct = product
        // Every quantity the owner said, in its own unit ("1 box 3 pieces"); converted to the product's unit below.
        val parts = req.parts.ifEmpty { req.qty?.let { listOf(com.shopai.app.brain.tools.QtyPart(it, req.unit)) }.orEmpty() }
        if (parts.isEmpty()) return askQuantity(product, req.incoming, said, lang)
        return draftParts(product, parts, req.incoming, said, lang)
    }

    /**
     * The owner's quantity → the product's stock unit. A unit whose size for this
     * product isn't known is ASKED ("1 box-la evlo pieces irukku?") — never taken as pieces.
     */
    private fun draftParts(
        product: com.shopai.app.brain.tools.ProductRef, parts: List<com.shopai.app.brain.tools.QtyPart>, incoming: Boolean, said: String, lang: KaiLang,
        key: String = newKey(), learned: Boolean = false,
    ): KaiTurn = when (val r = com.shopai.app.brain.tools.KaiUnits.resolve(product, parts, sessionUnits[product.id].orEmpty())) {
        is com.shopai.app.brain.tools.UnitResolution.Missing -> askConversion(product, parts, incoming, said, r.unit, lang, key)
        is com.shopai.app.brain.tools.UnitResolution.Ok -> stockDraft(product, r.baseQty, product.unit, incoming, said, lang, key, r.takeIf { it.converted }, learned)
    }

    private fun askConversion(
        product: com.shopai.app.brain.tools.ProductRef, parts: List<com.shopai.app.brain.tools.QtyPart>, incoming: Boolean, said: String, unit: String, lang: KaiLang, key: String,
    ): KaiTurn {
        unitQuestion = UnitQuestion(product, parts, incoming, said, unit, key)
        lastProduct = product
        val one = com.shopai.app.brain.tools.KaiStock.unitWord(unit, BigDecimal.ONE)
        val base = com.shopai.app.brain.tools.KaiStock.unitWord(product.unit, BigDecimal.TEN)
        tools.log(if (incoming) com.shopai.app.brain.tools.KaiIntents.STOCK_IN else com.shopai.app.brain.tools.KaiIntents.STOCK_OUT, "kai",
            "${product.name}: 1 $one = ? $base asked", ActionStatus.ANSWERED, null, said)
        return KaiTurn(
            ChatReply(pick(lang,
                ta = "ஓனர், 1 $one-ல எத்தனை $base இருக்கு?",
                tl = "Owner, 1 $one-la evlo $base irukku?",
                en = "Owner, how many $base are in 1 $one?"), KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(
                listOf(product.name + " — " + com.shopai.app.brain.tools.KaiUnits.describe(parts, product.unit),
                    pick(lang, ta = "ஸ்டாக் எதுவும் மாறல.", tl = "Stock edhuvum maaralai.", en = "Nothing in stock has changed.")),
                listOf(KaiButton(cancelLabel(lang), KaiAction.CancelStock("ask"))),
            ),
        )
    }

    /** "12", "12 pieces", "pannendu", "1 box = 12 pieces" → 12 (in the product's unit). Null: not an answer. */
    private fun perUnitAnswer(text: String, product: com.shopai.app.brain.tools.ProductRef): BigDecimal? {
        val right = text.substringAfter('=', text)
        val parts = com.shopai.app.brain.tools.KaiStock.partsIn(right)
        if (text.contains('=')) {
            val p = parts.firstOrNull() ?: return null
            return p.qty.takeIf { p.unit == null || com.shopai.app.brain.tools.KaiUnits.canon(p.unit) == com.shopai.app.brain.tools.KaiUnits.canon(product.unit) }
        }
        if (KaiPrivateMemory.normalize(text).split(' ').size > 4) return null
        val p = parts.singleOrNull() ?: return null
        return p.qty.takeIf { p.unit == null || com.shopai.app.brain.tools.KaiUnits.canon(p.unit) == com.shopai.app.brain.tools.KaiUnits.canon(product.unit) }
    }

    /** "1 box = 10 pieces" with an open converted draft of that unit: new conversion, draft recalculated (same draft). */
    private fun conversionEdit(text: String, lang: KaiLang): KaiTurn? {
        val m = Regex("""(?i)^\s*(?:1|oru|one)\s+([\p{L}]+)\s*=\s*(\d+(?:\.\d+)?)\s*([\p{L}]+)?\s*$""").find(text) ?: return null
        val unit = com.shopai.app.brain.tools.KaiStock.unitOf(m.groupValues[1]) ?: return null
        val plan = stockPlans.values.lastOrNull { p -> p.conv?.input?.any { com.shopai.app.brain.tools.KaiUnits.canon(it.unit) == unit } == true } ?: return null
        val f = m.groupValues[2].toBigDecimal().takeIf { it.signum() > 0 } ?: return null
        m.groupValues[3].takeIf { it.isNotEmpty() }?.let { u ->
            if (com.shopai.app.brain.tools.KaiUnits.canon(com.shopai.app.brain.tools.KaiStock.unitOf(u) ?: u) != com.shopai.app.brain.tools.KaiUnits.canon(plan.product.unit)) return null
        }
        stockPlans.remove(plan.key)
        sessionUnits.getOrPut(plan.product.id) { HashMap() }[unit] = f
        // The product's saved conversion is not used for this draft any more: the owner just said otherwise.
        val product = plan.product.copy(conversions = plan.product.conversions.filterKeys { com.shopai.app.brain.tools.KaiUnits.canon(it) != unit })
        return draftParts(product, plan.conv!!.input, plan.incoming, plan.said, lang, plan.key, learned = true)
    }

    /** "Colgate stock vandhiruku add pannu": how many? (the camera can count / fill it too). */
    private fun askQuantity(product: com.shopai.app.brain.tools.ProductRef, incoming: Boolean, said: String, lang: KaiLang): KaiTurn {
        stockQuestion = StockQuestion(product, incoming, said)
        tools.log(if (incoming) com.shopai.app.brain.tools.KaiIntents.STOCK_IN else com.shopai.app.brain.tools.KaiIntents.STOCK_OUT, "kai", "${product.name}: quantity asked", ActionStatus.ANSWERED, null, said)
        val unit = com.shopai.app.brain.tools.KaiStock.unitWord(product.unit)
        return KaiTurn(
            ChatReply(if (incoming) pick(lang,
                ta = "சரி ஓனர் 👍 ${product.name} stock சேர்க்கலாம்.\nஎத்தனை $unit வந்திருக்கு?",
                tl = "Seri Owner 👍 ${product.name} stock add pannalam.\nEvlo $unit vandhirukku?",
                en = "Sure Owner 👍 Let's add ${product.name} stock.\nHow many $unit came in?")
            else pick(lang,
                ta = "சரி ஓனர் — ${product.name} எத்தனை போச்சு?",
                tl = "Seri Owner — ${product.name} evlo pochu?",
                en = "Sure Owner — how many ${product.name} went out?"), KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(listOf(pick(lang, ta = "இப்போ ஸ்டாக்: ", tl = "Ippo stock: ", en = "Stock now: ") + com.shopai.app.brain.tools.KaiStock.shown(product.stock, product.unit)), listOfNotNull(
                if (incoming) KaiButton(pick(lang, ta = "📷 கேமரா", tl = "📷 Camera", en = "📷 Camera"),
                    KaiAction.OpenStockCamera(StockPrefill(product.name, null, product.unit, said))) else null,
                KaiButton(cancelLabel(lang), KaiAction.CancelStock("ask")),
            )),
        )
    }

    private fun namesPerson(text: String, spoken: String, people: List<String>): Boolean {
        if (com.shopai.app.brain.KaiUnderstanding.knownPerson(text, people) != null) return true
        val words = KaiPrivateMemory.normalize(spoken).split(' ').toSet()
        return people.any { p -> KaiPrivateMemory.normalize(p).split(' ').firstOrNull()?.takeIf { it.length >= 3 }?.let { it in words } == true }
    }

    /** A name that isn't in the inventory: a nickname of a product (Kai asks which), or a new product (camera / form). */
    private suspend fun unknownProduct(req: com.shopai.app.brain.tools.StockRequest, said: String, lang: KaiLang, products: List<com.shopai.app.brain.tools.ProductRef>): KaiTurn {
        val prefill = StockPrefill(req.spokenName, req.qty, req.unit, said)
        // "colgate paste" when "Colgate 200g" exists: which product is it? (a new one is offered too)
        val words = KaiPrivateMemory.normalize(req.spokenName).split(' ').filter { it.length >= 3 }
        val similar = products.filter { p -> KaiPrivateMemory.normalize(p.name).split(' ').any { pw -> words.any { w -> pw == w || (pw.length >= 4 && w.length >= 4 && pw.take(4) == w.take(4)) } } }
        if (similar.isNotEmpty() && learner != null) {
            learner.unknownProduct(req.spokenName, said, lang, similar.map { com.shopai.app.brain.memory.KnownEntity(it.id, it.name, com.shopai.app.brain.memory.MemoryType.PRODUCT_ALIAS) })?.let { t ->
                val newOne = KaiButton(pick(lang, ta = "புது product 📷", tl = "Pudhu product 📷", en = "New product 📷"), KaiAction.OpenStockCamera(prefill))
                return if (req.incoming) t.copy(card = t.card?.copy(buttons = t.card.buttons + newOne)) else t
            }
        }
        if (!req.incoming) {
            tools.log(com.shopai.app.brain.tools.KaiIntents.STOCK_OUT, "inventory", "${req.spokenName}: product not found", ActionStatus.ANSWERED, null, said)
            return KaiTurn(
                ChatReply(pick(lang,
                    ta = "ஓனர், ${req.spokenName} inventory-ல இல்ல. Product create பண்ணணுமா?",
                    tl = "Owner, ${req.spokenName} inventory-la illa. Product create pannanuma?",
                    en = "Owner, ${req.spokenName} isn't in your inventory. Create the product?"), KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY),
                KaiCard(emptyList(), listOf(
                    KaiButton(pick(lang, ta = "Product create பண்ணு", tl = "Create Product", en = "Create Product"), KaiAction.CreateProduct(prefill.copy(qty = null)), primary = true),
                    KaiButton(cancelLabel(lang), KaiAction.CancelStock("new")),
                )),
            )
        }
        // STOCK_IN_CAMERA: a new product — the camera opens straight away; the photo fills a form the owner checks.
        tools.log(com.shopai.app.brain.tools.KaiIntents.STOCK_IN_CAMERA, "camera", "${req.spokenName}: new product, camera opened", ActionStatus.OPENED, null, said)
        val shown = req.qty?.let { " (${com.shopai.app.brain.tools.KaiStock.shown(it, req.unit)})" } ?: ""
        return KaiTurn(
            ChatReply(pick(lang,
                ta = "ஓனர், ${req.spokenName}$shown புது product மாதிரி தெரியுது.\nபோட்டோ எடுத்து விவரம் auto-fill பண்ணவா? 📷",
                tl = "Owner, ${req.spokenName}$shown new product madhiri theriyudhu.\nPhoto eduthu details auto-fill pannava? 📷",
                en = "Owner, ${req.spokenName}$shown looks like a new product.\nShall I take a photo and auto-fill the details? 📷"), KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(emptyList(), listOf(
                KaiButton(pick(lang, ta = "📷 கேமரா", tl = "📷 Camera open pannu", en = "📷 Open camera"), KaiAction.OpenStockCamera(prefill), primary = true),
                KaiButton(pick(lang, ta = "Type பண்ணு", tl = "Type pannu", en = "Type details"), KaiAction.CreateProduct(prefill)),
                KaiButton(cancelLabel(lang), KaiAction.CancelStock("new")),
            )),
            direct = KaiAction.OpenStockCamera(prefill),
        )
    }

    /** The stock draft: product, quantity, before → after; Confirm / Edit / Cancel. Nothing is saved yet. */
    private fun stockDraft(
        product: com.shopai.app.brain.tools.ProductRef, qty: BigDecimal, unit: String, incoming: Boolean, said: String, lang: KaiLang, key: String = newKey(),
        conv: com.shopai.app.brain.tools.UnitResolution.Ok? = null, learned: Boolean = false,
    ): KaiTurn {
        lastProduct = product
        stockPlans[key] = StockPlan(key, product, qty, incoming, said, unit, conv)
        lastDraft = said to "STOCK"
        if (conv != null) return convertedDraft(product, qty, incoming, said, lang, key, conv, learned)
        val after = if (incoming) product.stock + qty else product.stock - qty
        val short = !incoming && after.signum() < 0
        fun shown(q: BigDecimal, u: String) = com.shopai.app.brain.tools.KaiStock.shown(q, u)
        val n = shown(qty, unit)
        tools.log(if (incoming) com.shopai.app.brain.tools.KaiIntents.STOCK_IN else com.shopai.app.brain.tools.KaiIntents.STOCK_OUT, "draft", "${product.name} $n draft=$key", ActionStatus.DRAFT, null, said)
        val reply = if (incoming) pick(lang,
            ta = "${product.name} — $n stock-in draft ரெடி. உறுதி செய்யுங்க.",
            tl = "${product.name} — $n stock-in draft ready. Confirm pannunga.",
            en = "${product.name} — $n stock-in draft ready. Please confirm.")
        else if (short) pick(lang,
            ta = "${product.name} — $n stock-out. அவ்வளவு ஸ்டாக் இல்ல ஓனர் (இருப்பது ${shown(product.stock, product.unit)}).",
            tl = "${product.name} — $n stock-out. Owner, avlo stock illa (irukradhu ${shown(product.stock, product.unit)}).",
            en = "${product.name} — $n stock-out. There isn't that much stock, Owner (${shown(product.stock, product.unit)} in stock).")
        else pick(lang,
            ta = "சரி ஓனர். ${product.name} — $n stock-out. மீதம்: ${shown(after, product.unit)}.",
            tl = "Seri Owner. ${product.name} — $n stock-out. Remaining: ${shown(after, product.unit)}.",
            en = "Okay Owner. ${product.name} — $n stock-out. Remaining: ${shown(after, product.unit)}.")
        val lines = if (incoming) listOf(
            "${product.name} — $n",
            pick(lang, ta = "ஸ்டாக் உள்ளே (Stock In)", tl = "Stock In", en = "Stock In"),
            pick(lang, ta = "ஸ்டாக்: ", tl = "Stock: ", en = "Stock: ") + "${shown(product.stock, product.unit)} → ${shown(after, product.unit)}",
        ) else listOf(
            product.name,
            pick(lang, ta = "Stock Out: ", tl = "Stock Out: ", en = "Stock Out: ") + n,
            pick(lang, ta = "மீதம்: ", tl = "Remaining: ", en = "Remaining: ") + if (short) "—" else shown(after, product.unit),
        )
        return KaiTurn(
            ChatReply(reply, KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(
                lines,
                listOf(
                    KaiButton(pick(lang, ta = "உறுதி செய்", tl = "Confirm", en = "Confirm"), KaiAction.ConfirmStock(key), primary = true, enabled = !short),
                    KaiButton(pick(lang, ta = "மாற்று", tl = "Edit", en = "Edit"), KaiAction.EditStock(key)),
                    KaiButton(cancelLabel(lang), KaiAction.CancelStock(key)),
                ),
                warning = if (short) pick(lang, ta = "அவ்வளவு ஸ்டாக் இல்ல ஓனர்.", tl = "Owner, avlo stock illa.", en = "There isn't that much stock, Owner.") else null,
            ),
        )
    }

    /**
     * A draft in another unit: what the owner said, the conversion, the total and
     * the inventory effect — so the confirmed quantity is exactly what is shown.
     */
    private fun convertedDraft(
        product: com.shopai.app.brain.tools.ProductRef, base: BigDecimal, incoming: Boolean, said: String, lang: KaiLang, key: String,
        conv: com.shopai.app.brain.tools.UnitResolution.Ok, learned: Boolean,
    ): KaiTurn {
        fun shown(q: BigDecimal, u: String?) = com.shopai.app.brain.tools.KaiStock.shown(q, u)
        val input = com.shopai.app.brain.tools.KaiUnits.describe(conv.input, product.unit)
        val total = shown(base, product.unit)
        val rules = conv.rules.map { (u, f) -> com.shopai.app.brain.tools.KaiUnits.rule(u, f, product.unit) }
        val after = if (incoming) product.stock + base else product.stock - base
        val short = !incoming && after.signum() < 0
        tools.log(if (incoming) com.shopai.app.brain.tools.KaiIntents.STOCK_IN else com.shopai.app.brain.tools.KaiIntents.STOCK_OUT, "draft",
            "${product.name} $input = $total draft=$key", ActionStatus.DRAFT, null, said)
        val head = if (learned) pick(lang,
            ta = "சரி ஓனர் 👍\n${rules.joinToString("\n")}.\n$input = $total.\n",
            tl = "Seri Owner 👍\n${rules.joinToString("\n")}.\n$input = $total.\n",
            en = "Okay Owner 👍\n${rules.joinToString("\n")}.\n$input = $total.\n") else ""
        val body = when {
            incoming -> pick(lang,
                ta = if (learned) "Stock-in draft ரெடி." else "${product.name} — $input = $total stock-in draft ரெடி. உறுதி செய்யுங்க.",
                tl = if (learned) "Stock-in draft ready." else "${product.name} — $input = $total stock-in draft ready. Confirm pannunga.",
                en = if (learned) "Stock-in draft ready." else "${product.name} — $input = $total stock-in draft ready. Please confirm.")
            short -> pick(lang,
                ta = "${product.name} — $input stock-out = $total. அவ்வளவு ஸ்டாக் இல்ல ஓனர் (இருப்பது ${shown(product.stock, product.unit)}).",
                tl = "${product.name} — $input stock-out = $total. Owner, avlo stock illa (irukradhu ${shown(product.stock, product.unit)}).",
                en = "${product.name} — $input stock-out = $total. There isn't that much stock, Owner (${shown(product.stock, product.unit)} in stock).")
            else -> pick(lang,
                ta = "சரி ஓனர். ${product.name} — $input stock-out = $total. மீதம்: ${shown(after, product.unit)}.",
                tl = "Seri Owner. ${product.name} — $input stock-out = $total. Remaining: ${shown(after, product.unit)}.",
                en = "Okay Owner. ${product.name} — $input stock-out = $total. Remaining: ${shown(after, product.unit)}.")
        }
        val sign = if (incoming) "+" else "−"
        val lines = listOf(
            product.name,
            pick(lang, ta = "நீங்க சொன்னது: ", tl = "Input: ", en = "Input: ") + input,
        ) + rules.map { pick(lang, ta = "மாற்றம்: ", tl = "Conversion: ", en = "Conversion: ") + it } + listOf(
            pick(lang, ta = "மொத்தம்: ", tl = "Total: ", en = "Total: ") + total,
            pick(lang, ta = "ஸ்டாக்: ", tl = "Inventory: ", en = "Inventory: ") + "$sign$total (${shown(product.stock, product.unit)} → " +
                (if (short) "—" else shown(after, product.unit)) + ")",
        )
        // Conversions the owner gave in this chat (not yet saved for the product): saved only if they tap it.
        val saves = conv.rules.filter { (u, _) -> product.conversions.keys.none { com.shopai.app.brain.tools.KaiUnits.canon(it) == u } }.map { (u, f) ->
            KaiButton(pick(lang, ta = "சேமி: ", tl = "Save: ", en = "Save: ") + com.shopai.app.brain.tools.KaiUnits.rule(u, f, product.unit),
                KaiAction.SaveUnitConversion(product.id, product.name, u, f.toPlainString(), product.unit))
        }
        return KaiTurn(
            ChatReply(head + body, KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(
                lines,
                listOf(
                    KaiButton(if (incoming) pick(lang, ta = "Stock In உறுதி செய்", tl = "Confirm Stock In", en = "Confirm Stock In")
                    else pick(lang, ta = "Stock Out உறுதி செய்", tl = "Confirm Stock Out", en = "Confirm Stock Out"), KaiAction.ConfirmStock(key), primary = true, enabled = !short),
                    KaiButton(pick(lang, ta = "மாற்று", tl = "Edit", en = "Edit"), KaiAction.EditStock(key)),
                    KaiButton(cancelLabel(lang), KaiAction.CancelStock(key)),
                ) + saves,
                warning = if (short) pick(lang, ta = "அவ்வளவு ஸ்டாக் இல்ல ஓனர்.", tl = "Owner, avlo stock illa.", en = "There isn't that much stock, Owner.") else null,
            ),
        )
    }

    /** SCAN_STOCK: the product camera opens at once; the photo fills a form the owner checks before Confirm Stock In. */
    private fun stockCamera(name: String, said: String, lang: KaiLang, products: List<com.shopai.app.brain.tools.ProductRef>): KaiTurn {
        val known = products.firstOrNull { KaiPrivateMemory.normalize(it.name) == KaiPrivateMemory.normalize(name) }
        val prefill = StockPrefill(known?.name ?: name, null, known?.unit, said)
        tools.log(com.shopai.app.brain.tools.KaiIntents.SCAN_STOCK, "camera", "${name.ifBlank { "product" }}: camera opened", ActionStatus.OPENED, null, said)
        return KaiTurn(
            ChatReply(pick(lang,
                ta = "சரி ஓனர் 📷 போட்டோ எடுங்க — விவரம் நான் fill பண்றேன். உறுதி செய்யும் வரை எதுவும் சேமிக்காது.",
                tl = "Seri Owner 📷 Photo edunga — details naan fill panren. Confirm pannura varaikkum edhuvum save aagaadhu.",
                en = "Sure Owner 📷 Take a photo — I'll fill in the details. Nothing is saved until you confirm."), KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(emptyList(), listOf(
                KaiButton(pick(lang, ta = "📷 கேமரா", tl = "📷 Camera open pannu", en = "📷 Open camera"), KaiAction.OpenStockCamera(prefill), primary = true),
                KaiButton(pick(lang, ta = "Type பண்ணு", tl = "Type pannu", en = "Type details"), KaiAction.CreateProduct(prefill)),
            )),
            direct = KaiAction.OpenStockCamera(prefill),
        )
    }

    /** The stock draft being edited (for the screen's form). */
    fun stockDraftOf(key: String): Triple<String, BigDecimal, String>? = stockPlans[key]?.let { p ->
        // The form shows what the owner said ("5 BOX"), not the converted total.
        p.conv?.input?.singleOrNull()?.let { Triple(p.product.name, it.qty, it.unit ?: p.product.unit) } ?: Triple(p.product.name, p.qty, p.unit)
    }

    /** Edit: a new quantity / unit for the draft — shown again for Confirm. */
    fun reviseStock(key: String, qty: BigDecimal, unit: String, lang: KaiLang): KaiTurn? {
        val plan = stockPlans.remove(key) ?: return null
        if (qty.signum() <= 0) return null
        // Recalculated from the new quantity / unit — never the old total.
        return draftParts(plan.product, listOf(com.shopai.app.brain.tools.QtyPart(qty, unit.ifBlank { null })), plan.incoming, plan.said, lang, key)
    }

    /**
     * The owner confirmed a new product's form (photo-filled or typed): the
     * product is created in the inventory, then its stock comes in through
     * the same inventory engine. An existing product with that name is used
     * instead of making a copy.
     */
    suspend fun addProduct(form: ProductForm, lang: KaiLang): KaiTurn {
        val name = form.name.trim()
        if (name.isEmpty()) return say(lang, KaiMood.CLARIFY, null, ta = "Product பெயர் சொல்லுங்க ஓனர்.", tl = "Product name sollunga Owner.", en = "Tell me the product name, Owner.")
        val existing = runCatching { tools.products() }.getOrNull()?.firstOrNull { KaiPrivateMemory.normalize(it.name) == KaiPrivateMemory.normalize(name) }
        val product = existing ?: tools.createProduct(com.shopai.app.brain.tools.NewProduct(name, form.category, form.variant, form.brand, form.unit, form.weight, form.imageUri, form.packSize))
            ?: run {
                tools.log(com.shopai.app.brain.tools.KaiIntents.CREATE_PRODUCT, "inventory engine", "$name not created", ActionStatus.FAILED, null, form.said)
                return say(lang, KaiMood.ERROR, null, ta = "Product சேமிக்க முடியல ஓனர்.", tl = "Owner, product save aagala.", en = "I couldn't save the product, Owner.")
            }
        if (existing == null) tools.log(com.shopai.app.brain.tools.KaiIntents.CREATE_PRODUCT, "inventory engine", "${product.name} created", ActionStatus.CONFIRMED, null, form.said)
        lastProduct = product
        val q = form.qty?.takeIf { it.signum() > 0 }
            ?: return say(lang, KaiMood.SUCCESS, null,
                ta = "சரி ஓனர் ✅ ${product.name} product சேர்த்துட்டேன்.", tl = "Done Owner ✅ ${product.name} product create panniten.", en = "Done Owner ✅ ${product.name} is added to your products.")
        return when (val outcome = tools.changeStock(product, q, true, form.said.ifBlank { "photo stock in" })) {
            is ActionOutcome.Done -> {
                tools.log(com.shopai.app.brain.tools.KaiIntents.STOCK_IN, "inventory engine", "${product.name} +${qty(q, form.unit)} saved", ActionStatus.CONFIRMED, null, form.said)
                stockDone(product.name, q, form.unit, true, outcome.balanceAfter?.let { com.shopai.app.brain.tools.KaiStock.shown(it, product.unit) }, lang)
            }
            is ActionOutcome.Failed -> {
                tools.log(com.shopai.app.brain.tools.KaiIntents.STOCK_IN, "inventory engine", outcome.reason, ActionStatus.FAILED, null, form.said)
                say(lang, KaiMood.ERROR, null, ta = "சேமிக்க முடியல ஓனர்: ${outcome.reason}", tl = "Owner, save aagala: ${outcome.reason}", en = "It wasn't saved, Owner: ${outcome.reason}")
            }
        }
    }

    /** "Done Owner ✅ Colgate stock-la 12 pieces add panniten." */
    private fun stockDone(name: String, q: BigDecimal, unit: String, incoming: Boolean, after: String?, lang: KaiLang): KaiTurn {
        val n = q.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
        val u = com.shopai.app.brain.tools.KaiStock.unitWord(unit)
        return if (incoming) say(lang, KaiMood.SUCCESS, null,
            ta = "சரி ஓனர் ✅ $name ஸ்டாக்-ல $n $u சேர்த்துட்டேன்." + (after?.let { " இப்போ ஸ்டாக் $it." } ?: ""),
            tl = "Done Owner ✅ $name stock-la $n $u add panniten." + (after?.let { " Ippo stock $it." } ?: ""),
            en = "Done Owner ✅ Added $n $u to $name stock." + (after?.let { " Stock now $it." } ?: ""))
        else say(lang, KaiMood.SUCCESS, null,
            ta = "சரி ஓனர் ✅ $name ஸ்டாக்-ல இருந்து $n $u குறைச்சுட்டேன்." + (after?.let { " மீதம் $it." } ?: ""),
            tl = "Done Owner ✅ $name stock-la irundhu $n $u kuraichiten." + (after?.let { " Meedham $it." } ?: ""),
            en = "Done Owner ✅ Took $n $u out of $name stock." + (after?.let { " $it left." } ?: ""))
    }

    private suspend fun confirmStock(key: String, lang: KaiLang): KaiTurn? {
        val plan = stockPlans.remove(key) ?: return null
        val intent = if (plan.incoming) com.shopai.app.brain.tools.KaiIntents.STOCK_IN else com.shopai.app.brain.tools.KaiIntents.STOCK_OUT
        // The inventory gets the base-unit quantity shown on the draft; the history keeps what the owner said and the conversion.
        val input = plan.conv?.let { com.shopai.app.brain.tools.KaiUnits.describe(it.input, plan.product.unit) }
        val total = com.shopai.app.brain.tools.KaiStock.shown(plan.qty, plan.product.unit)
        val note = if (input != null) "$input = $total · ${plan.said}" else plan.said
        return when (val outcome = tools.changeStock(plan.product, plan.qty, plan.incoming, note)) {
            is ActionOutcome.Done -> {
                tools.log(intent, "inventory engine", "${plan.product.name} ${input?.let { "$it = " }.orEmpty()}${qty(plan.qty, plan.unit)} saved", ActionStatus.CONFIRMED, null, plan.said)
                val now = outcome.balanceAfter?.let { com.shopai.app.brain.tools.KaiStock.shown(it, plan.product.unit) }
                if (input != null) {
                    val sign = if (plan.incoming) "+" else "−"
                    say(lang, KaiMood.SUCCESS, null,
                        ta = "சரி ஓனர் ✅ ${plan.product.name} — $input " + (if (plan.incoming) "சேர்த்துட்டேன்." else "குறைச்சுட்டேன்.") + " ஸ்டாக் $sign$total." + (now?.let { " இப்போ $it." } ?: ""),
                        tl = "Done Owner ✅ ${plan.product.name} — $input " + (if (plan.incoming) "added." else "out.") + " Inventory $sign$total." + (now?.let { " Ippo stock $it." } ?: ""),
                        en = "Done Owner ✅ ${plan.product.name} — $input " + (if (plan.incoming) "added." else "taken out.") + " Inventory $sign$total." + (now?.let { " Stock now $it." } ?: ""))
                } else stockDone(plan.product.name, plan.qty, plan.unit, plan.incoming, now, lang)
            }
            is ActionOutcome.Failed -> {
                tools.log(intent, "inventory engine", outcome.reason, ActionStatus.FAILED, null, plan.said)
                say(lang, KaiMood.ERROR, null,
                    ta = "சேமிக்க முடியல ஓனர்: ${outcome.reason}", tl = "Owner, save aagala: ${outcome.reason}", en = "It wasn't saved, Owner: ${outcome.reason}")
            }
        }
    }

    // ------------------------------------------------------------ Morning Work (MORNING_WORK)

    /**
     * The morning brief from the one MorningWorkEngine, over the current
     * business's records. Read only: it never records a payment, sale,
     * purchase or stock change — every follow-up still asks the owner.
     */
    private suspend fun morningWork(ask: com.shopai.app.brain.morning.MorningCommand, text: String, said: String, lang: KaiLang, people: List<String>): KaiTurn {
        val brief = morning?.let { m -> runCatching { m.brief(lang) }.getOrNull() }
        if (brief == null) {
            tools.log(com.shopai.app.brain.tools.KaiIntents.MORNING_WORK, "morning work engine", "records unavailable", ActionStatus.FAILED, null, said)
            return say(lang, KaiMood.CONCERNED, null,
                ta = "இதை உங்க கணக்கு பதிவுல சரிபார்க்க முடியல ஓனர்.",
                tl = "Owner, idha unga business records-la verify panna mudiyala.",
                en = "I couldn't verify that from your business records.")
        }
        lastBrief = brief
        briefJustShown = true
        tools.log(com.shopai.app.brain.tools.KaiIntents.MORNING_WORK, "morning work engine", "${brief.openCount} open tasks", ActionStatus.ANSWERED, null, said)
        // "Morning work ready panni Kumar-ku call pannu": the brief plus the call the owner asked for — a button, Kai never dials by itself.
        val callName = if (Regex("""(?i)(?<![\p{L}])(call|phone)(?![\p{L}])""").containsMatchIn(text)) KaiCommands.personIn(text, people) else null
        val callButton = callName?.let { name ->
            val p = runCatching { tools.parties(name) }.getOrNull()?.firstOrNull { it.name.equals(name, true) } ?: runCatching { tools.parties(name) }.getOrNull()?.firstOrNull()
            KaiButton(pick(lang, ta = "${p?.name ?: name}-க்கு call", tl = "Call ${p?.name ?: name}", en = "Call ${p?.name ?: name}"), KaiAction.Dial(p?.name ?: name, p?.phone), primary = true)
        }
        val follow = morningFollowUp(brief, 0, lang)
        val start = KaiButton(pick(lang, ta = "காலை வேலை ஆரம்பி", tl = "Start Morning Work", en = "Start Morning Work"), KaiAction.OpenMorningWork(start = true))
        return KaiTurn(
            ChatReply(brief.text, if (brief.empty) KaiMood.HAPPY else KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(follow?.lines.orEmpty(), listOfNotNull(callButton) + follow?.buttons.orEmpty() + if (brief.empty) emptyList() else listOf(start)),
            // "Morning work start pannu": straight into the guided Morning Work.
            direct = if (ask == com.shopai.app.brain.morning.MorningCommand.Start && !brief.empty) KaiAction.OpenMorningWork(start = true) else null,
        )
    }

    /** "Owner, first Kumar collection follow-up pannalama?" [View Kumar] [Remind Me] [Call Kumar] [Skip] — nothing happens without a tap. */
    private fun morningFollowUp(brief: com.shopai.app.brain.morning.MorningBrief, index: Int, lang: KaiLang): KaiCard? {
        val t = brief.queue.getOrNull(index) ?: return null
        val what = com.shopai.app.brain.morning.MorningBriefs.firstText(t, lang)
        val question = if (index == 0) pick(lang, ta = "ஓனர், முதல்ல $what பண்ணலாமா?", tl = "Owner, first $what pannalama?", en = "Owner, shall we start with: $what?")
        else pick(lang, ta = "அடுத்து: $what பண்ணலாமா?", tl = "Adutha: $what pannalama?", en = "Next: $what?")
        val skip = KaiButton(pick(lang, ta = "அடுத்து", tl = "Skip", en = "Skip"), KaiAction.MorningNext(index + 1))
        val remind = KaiButton(pick(lang, ta = "நினைவூட்டு", tl = "Remind Me", en = "Remind Me"), KaiAction.RemindAbout(remindText(t)))
        val name = t.title
        val buttons = when (t.taskType) {
            com.shopai.app.brain.morning.MorningTaskType.COLLECT_PAYMENT, com.shopai.app.brain.morning.MorningTaskType.PAYMENT_FOLLOWUP,
            com.shopai.app.brain.morning.MorningTaskType.SUPPLIER_PAYMENT -> {
                val kind = if (t.taskType == com.shopai.app.brain.morning.MorningTaskType.SUPPLIER_PAYMENT) "SUPPLIER" else "CUSTOMER"
                listOf(
                    KaiButton(pick(lang, ta = "$name பார்", tl = "View $name", en = "View $name"), KaiAction.OpenRecord(kind, t.sourceId), primary = true),
                    remind,
                    KaiButton(pick(lang, ta = "$name-க்கு call", tl = "Call $name", en = "Call $name"), KaiAction.Dial(name, t.phone)),
                    skip,
                )
            }
            com.shopai.app.brain.morning.MorningTaskType.LOW_STOCK -> listOf(
                KaiButton(pick(lang, ta = "ஸ்டாக் பார்", tl = "View Stock", en = "View Stock"), KaiAction.OpenRecord("PRODUCT", t.sourceId), primary = true),
                KaiButton(pick(lang, ta = "ஸ்டாக் சேர்", tl = "Add Stock", en = "Add Stock"), KaiAction.AddStockFor(t.sourceId)),
                remind,
                skip,
            )
            com.shopai.app.brain.morning.MorningTaskType.EXPIRY -> listOf(
                KaiButton(pick(lang, ta = "ஸ்டாக் பார்", tl = "View Stock", en = "View Stock"), KaiAction.OpenRecord("INVENTORY"), primary = true), remind, skip,
            )
            com.shopai.app.brain.morning.MorningTaskType.REMINDER -> listOf(
                KaiButton(pick(lang, ta = "திற", tl = "Open", en = "Open"), KaiAction.OpenRecord("REMINDERS"), primary = true), skip,
            )
            com.shopai.app.brain.morning.MorningTaskType.PENDING_DRAFT -> listOf(
                KaiButton(pick(lang, ta = "திற", tl = "Open", en = "Open"), KaiAction.OpenMorningWork(start = false), primary = true), skip,
            )
        }
        return KaiCard(listOf(question), buttons)
    }

    private fun remindText(t: com.shopai.app.brain.morning.MorningTask): String = when (t.taskType) {
        com.shopai.app.brain.morning.MorningTaskType.COLLECT_PAYMENT, com.shopai.app.brain.morning.MorningTaskType.PAYMENT_FOLLOWUP -> "${t.title}-ku collection follow-up"
        com.shopai.app.brain.morning.MorningTaskType.SUPPLIER_PAYMENT -> "${t.title}-ku payment"
        com.shopai.app.brain.morning.MorningTaskType.LOW_STOCK -> "${t.title} stock order"
        com.shopai.app.brain.morning.MorningTaskType.EXPIRY -> "${t.title} expiry check"
        else -> t.title
    }

    /** A button was tapped. */
    suspend fun act(action: KaiAction, lang: KaiLang): KaiTurn? = when (action) {
        is KaiAction.LearnMeaning, is KaiAction.LearnEntity, is KaiAction.NotThis, is KaiAction.OnlyNow, is KaiAction.LearnAlias, is KaiAction.LearnWord,
        is KaiAction.PickUnit, is KaiAction.ManageMemory ->
            when (val step = learner?.act(action, lang)) {
                is MemoryStep.Reply -> step.turn
                is MemoryStep.Rerun -> withPrefix(step.prefix, ask(step.text))
                null -> null
            }
        is KaiAction.ConfirmStock -> confirmStock(action.key, lang)
        is KaiAction.SaveRoutine, is KaiAction.RoutineNotNow -> routine?.act(action, lang)
        is KaiAction.SaveUnitConversion -> {
            val f = action.perUnit.toBigDecimalOrNull()
            val rule = f?.let { com.shopai.app.brain.tools.KaiUnits.rule(action.unit, it, action.baseUnit) }
            when (if (f == null) com.shopai.app.brain.tools.ConversionSave.UNAVAILABLE else tools.saveUnitConversion(action.productId, action.unit, f)) {
                com.shopai.app.brain.tools.ConversionSave.SAVED -> {
                    sessionUnits[action.productId]?.remove(action.unit)
                    tools.log(com.shopai.app.brain.tools.KaiIntents.STOCK_IN, "product units", "${action.productName}: $rule saved", ActionStatus.CONFIRMED, action.productId, null)
                    say(lang, KaiMood.SUCCESS, null,
                        ta = "சரி ஓனர் 👍 ${action.productName}-க்கு $rule-னு சேமிச்சுட்டேன். இனிமே கேட்க மாட்டேன்.",
                        tl = "Done Owner 👍 ${action.productName}-ku $rule save panniten. Inime kekka maatten.",
                        en = "Done Owner 👍 Saved $rule for ${action.productName}. I won't ask again.")
                }
                com.shopai.app.brain.tools.ConversionSave.OTHER_UNIT_SET -> say(lang, KaiMood.CLARIFY, null,
                    ta = "ஓனர், ${action.productName}-க்கு வேற ஒரு unit ஏற்கனவே set ஆகியிருக்கு. இந்த conversion இந்த பேச்சுக்கு மட்டும் பயன்படுத்தறேன் — product screen-ல மாத்தலாம்.",
                    tl = "Owner, ${action.productName}-ku vera oru unit already set aagirukku. Indha conversion indha conversation-ku mattum use panren — product screen-la maathalaam.",
                    en = "Owner, ${action.productName} already has another unit set up. I'll use this conversion only in this chat — you can change it on the product screen.")
                com.shopai.app.brain.tools.ConversionSave.UNAVAILABLE -> say(lang, KaiMood.CLARIFY, null,
                    ta = "ஓனர், இப்போ சேமிக்க முடியல — இந்த பேச்சுக்கு மட்டும் பயன்படுத்தறேன்.",
                    tl = "Owner, ippo save panna mudiyala — indha conversation-ku mattum use panren.",
                    en = "Owner, I couldn't save it right now — I'll use it only in this chat.")
            }
        }
        is KaiAction.MorningNext -> {
            val b = lastBrief
            val card = b?.let { morningFollowUp(it, action.index, lang) }
            if (card == null) say(lang, KaiMood.HAPPY, null,
                ta = "இன்னைக்கு list முடிஞ்சது ஓனர் 👍", tl = "Innaiku list mudinjiduchu Owner 👍", en = "That's today's list, Owner 👍")
            else KaiTurn(ChatReply(card.lines.first(), KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY), KaiCard(emptyList(), card.buttons))
        }
        // Only after the owner tapped "Remind Me": the reminder flow asks when (nothing is set by itself).
        is KaiAction.RemindAbout -> {
            val people = runCatching { books.snapshot()?.people.orEmpty() }.getOrDefault(emptyList())
            reminders.handle(com.shopai.app.brain.tools.ReminderRequest.Create(com.shopai.app.brain.tools.KaiReminderUnderstanding.draft(action.task, null, people)), lang)
        }
        is KaiAction.AddStockFor -> runCatching { tools.products() }.getOrNull()?.firstOrNull { it.id == action.productId }
            ?.let { askQuantity(it, incoming = true, said = "morning work: add stock", lang = lang) }
            ?: unverified(lang, "add stock")
        is KaiAction.CancelStock -> {
            stockPlans.remove(action.key)?.let { tools.log(if (it.incoming) com.shopai.app.brain.tools.KaiIntents.STOCK_IN else com.shopai.app.brain.tools.KaiIntents.STOCK_OUT, "draft", "cancelled", ActionStatus.CANCELLED, null, it.said) }
            stockQuestion = null
            unitQuestion = null
            say(lang, KaiMood.NEUTRAL, null, ta = "சரி, ரத்து பண்ணிட்டேன். எதுவும் சேமிக்கல.", tl = "Seri Owner, cancel pannitten. Edhuvum save aagala.", en = "Cancelled, Owner. Nothing was saved.")
        }
        is KaiAction.ConfirmPlan -> confirm(action.key, lang)
        is KaiAction.CancelPlan -> cancelPlan(action.key, lang)
        is KaiAction.ChoosePlan -> requests.remove(action.requestKey)?.let { r ->
            val turn = prepared(r, action.kind, action.partyName, action.partyId, lang)
            // "Kumar anna" → the owner picked Kumar Traders: offer to remember the nickname (this shop only).
            val alias = action.partyId?.let { id ->
                val customer = action.kind == PlanKind.PAYMENT_IN || action.kind == PlanKind.CREDIT_GIVEN
                learner?.aliasButton(r.name, com.shopai.app.brain.memory.KnownEntity(id, action.partyName,
                    if (customer) com.shopai.app.brain.memory.MemoryType.CUSTOMER_ALIAS else com.shopai.app.brain.memory.MemoryType.SUPPLIER_ALIAS), lang)
            }
            if (alias != null && turn.card != null) turn.copy(card = turn.card.copy(buttons = turn.card.buttons + alias)) else turn
        }
        is KaiAction.CancelRequest -> if (requests.remove(action.requestKey) != null) {
            tools.log("payment", "draft", "cancelled before a draft", ActionStatus.CANCELLED)
            say(lang, KaiMood.NEUTRAL, null, ta = "சரி, எதுவும் சேமிக்கல ஓனர்.", tl = "Seri Owner, edhuvum save pannala.", en = "Okay, nothing was saved, Owner.")
        } else reminders.act(action, lang)
        // Reminder buttons go to the reminder conversation; phone actions (dialer, scanner, settings) and Edit to the screen.
        else -> reminders.act(action, lang)
    }

    /** Edit: the owner changed the draft's details; it is prepared again (the old draft is discarded). */
    suspend fun revise(key: String, name: String, amount: BigDecimal, mode: PaymentMode, outgoing: Boolean, lang: KaiLang): KaiTurn {
        plans.remove(key)?.let { tools.discard(it) }
        return payment(PaymentRequest(newKey(), name.trim(), amount, outgoing, mode, "edited"), lang)
    }

    fun reset() {
        plans.clear()
        requests.clear()
        incompletePayment = null
        reminders.reset()
        brain.reset()
        stockPlans.clear()
        stockQuestion = null
        unitQuestion = null
        sessionUnits.clear()
        lastBrief = null
        briefJustShown = false
        lastProduct = null
        conversationState.clear()
        learner?.reset()
        routine?.reset()
    }

    // ------------------------------------------------------------ calculator

    private fun calculate(a: KaiCalculator.Answer, lang: KaiLang, said: String): KaiTurn {
        fun f(label: KaiCalculator.Label) = a.figures.first { it.label == label }.let(KaiCalculator::format)
        val text = when (a.kind) {
            KaiCalculator.Kind.EXPRESSION, KaiCalculator.Kind.PERCENT_OF, KaiCalculator.Kind.CONVERSION -> f(KaiCalculator.Label.RESULT)
            KaiCalculator.Kind.GST_ADD -> pick(lang, ta = "GST ${f(KaiCalculator.Label.GST)}. மொத்தம் ${f(KaiCalculator.Label.TOTAL)}.",
                tl = "GST ${f(KaiCalculator.Label.GST)}. Total ${f(KaiCalculator.Label.TOTAL)}.", en = "GST ${f(KaiCalculator.Label.GST)}. Total ${f(KaiCalculator.Label.TOTAL)}.")
            KaiCalculator.Kind.GST_INCLUDED -> pick(lang, ta = "GST இல்லாம ${f(KaiCalculator.Label.BASE)}, GST ${f(KaiCalculator.Label.GST)}.",
                tl = "GST illama ${f(KaiCalculator.Label.BASE)}, GST ${f(KaiCalculator.Label.GST)}.", en = "Before GST ${f(KaiCalculator.Label.BASE)}, GST ${f(KaiCalculator.Label.GST)}.")
            KaiCalculator.Kind.DISCOUNT -> pick(lang, ta = "தள்ளுபடி ${f(KaiCalculator.Label.DISCOUNT)}. கட்ட வேண்டியது ${f(KaiCalculator.Label.FINAL)}.",
                tl = "Discount ${f(KaiCalculator.Label.DISCOUNT)}. Final ${f(KaiCalculator.Label.FINAL)}.", en = "Discount ${f(KaiCalculator.Label.DISCOUNT)}. Final ${f(KaiCalculator.Label.FINAL)}.")
            KaiCalculator.Kind.PROFIT -> {
                val loss = a.figures.any { it.label == KaiCalculator.Label.LOSS }
                val amt = f(if (loss) KaiCalculator.Label.LOSS else KaiCalculator.Label.PROFIT)
                val pct = f(KaiCalculator.Label.MARGIN_PERCENT)
                if (loss) pick(lang, ta = "நஷ்டம் $amt ($pct).", tl = "Loss $amt ($pct).", en = "Loss $amt ($pct).")
                else pick(lang, ta = "லாபம் $amt (cost மேல $pct).", tl = "Profit $amt (cost mela $pct).", en = "Profit $amt ($pct on cost).")
            }
        }
        tools.log("calculation", "calculator", "$said = $text", ActionStatus.ANSWERED)
        return KaiTurn(ChatReply(text, KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY))
    }

    // ------------------------------------------------------------ payments (draft → confirm → engine)

    private suspend fun payment(r: PaymentRequest, lang: KaiLang): KaiTurn {
        conversationState.currentIntent = "PAYMENT"
        conversationState.currentAction = "DRAFT_PAYMENT"
        conversationState.lastPerson = r.name
        conversationState.lastRelevantEntity = r.name
        conversationState.lastAmount = r.amount
        conversationState.lastPaymentDirection = if (r.outgoing) "OUT" else "IN"
        conversationState.lastPaymentMode = r.mode
        conversationState.lastQuestion = r.said
        if (r.amount == null || r.amount.signum() <= 0) {
            incompletePayment = r
            conversationState.pendingCorrection = true
            return say(lang, KaiMood.CLARIFY, "payment: amount missing",
                ta = "எவ்வளவு தொகை ஓனர்?", tl = "Evlo amount Owner?", en = "How much was it, Owner?")
        }
        if (r.name.isNullOrBlank()) {
            incompletePayment = r
            conversationState.pendingCorrection = true
            return say(lang, KaiMood.CLARIFY, "payment: person missing",
                ta = "யாருக்கு / யார்கிட்ட ஓனர்?", tl = if (r.outgoing) "Yaarukku kuduthinga Owner?" else "Yaar kitta vaanguninga Owner?",
                en = if (r.outgoing) "Who did you give it to, Owner?" else "Who did you receive it from, Owner?")
        }
        val matches = tools.parties(r.name) ?: return say(lang, KaiMood.ERROR, "payment: books unavailable",
            ta = "உங்க கணக்கு புத்தகம் இன்னும் ரெடி ஆகல ஓனர் — இப்போ இதை சேமிக்க முடியாது.",
            tl = "Owner, books innum ready aagala — idha ippo save panna mudiyadhu.",
            en = "I couldn't reach your business records, Owner — I can't save this right now.")
        val key = r.name.trim().lowercase(Locale.ROOT)
        val exact = matches.filter { it.name.trim().lowercase(Locale.ROOT) == key || com.shopai.app.util.NameSound.same(it.name, r.name) }
        val candidates = (exact.ifEmpty { matches }).take(6)
        // Who they are decides what the money means.
        fun kindFor(p: PartyMatch) = when {
            r.outgoing && !p.customer -> PlanKind.PAYMENT_OUT
            r.outgoing && p.customer -> PlanKind.CREDIT_GIVEN
            !r.outgoing && p.customer -> PlanKind.PAYMENT_IN
            else -> PlanKind.DEBIT_TAKEN
        }
        if (candidates.size == 1 && exact.size == 1) {
            val p = candidates.single()
            return prepared(r, kindFor(p), p.name, p.id, lang)
        }
        requests[r.key] = r
        val amount = KaiFormat.rupees(r.amount.toDouble())
        if (candidates.isEmpty()) {
            // Not in the books: never guessed — the owner says what it is.
            val buttons = if (r.outgoing) listOf(
                KaiButton(pick(lang, ta = "Credit — ${r.name} எனக்கு தரணும்", tl = "Credit — ${r.name} enakku tharanum", en = "Credit — ${r.name} owes me"),
                    KaiAction.ChoosePlan(r.key, PlanKind.CREDIT_GIVEN, null, r.name), primary = true),
            ) else listOf(
                KaiButton(pick(lang, ta = "Debit — நான் ${r.name}-க்கு தரணும்", tl = "Debit — naan ${r.name}-ku tharanum", en = "Debit — I owe ${r.name}"),
                    KaiAction.ChoosePlan(r.key, PlanKind.DEBIT_TAKEN, null, r.name), primary = true),
            )
            return KaiTurn(
                ChatReply(pick(lang,
                    ta = "${r.name} பதிவுல இல்ல ஓனர். $amount ${if (r.outgoing) "கொடுத்தது" else "வாங்கியது"} — இது என்ன?",
                    tl = "Owner, ${r.name} records-la illa. $amount ${if (r.outgoing) "kuduthadhu" else "vaangunadhu"} — idhu enna?",
                    en = "${r.name} isn't in your records, Owner. What is this $amount?"), KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY),
                KaiCard(emptyList(), buttons + KaiButton(cancelLabel(lang), KaiAction.CancelRequest(r.key))),
            )
        }
        // Several people with this name (or a customer and a supplier): the owner picks.
        val buttons = candidates.map { p ->
            val role = if (p.customer) pick(lang, ta = "வாடிக்கையாளர்", tl = "Customer", en = "Customer") else pick(lang, ta = "சப்ளையர்", tl = "Supplier", en = "Supplier")
            KaiButton("${p.name} · $role · ${KaiFormat.rupees(p.balance.toDouble())}", KaiAction.ChoosePlan(r.key, kindFor(p), p.id, p.name))
        }
        return KaiTurn(
            ChatReply(pick(lang, ta = "எந்த ${r.name} ஓனர்?", tl = "Endha ${r.name} Owner?", en = "Which ${r.name}, Owner?"), KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(emptyList(), buttons + KaiButton(cancelLabel(lang), KaiAction.CancelRequest(r.key))),
        )
    }

    private suspend fun prepared(r: PaymentRequest, kind: PlanKind, name: String, partyId: String?, lang: KaiLang): KaiTurn {
        val plan = tools.prepare(kind, name, partyId, r.amount!!, r.mode, r.said)
            ?: return say(lang, KaiMood.ERROR, "draft failed", ta = "இதை இப்போ தயார் பண்ண முடியல ஓனர்.", tl = "Owner, idha ippo ready panna mudiyala.", en = "I couldn't prepare this right now, Owner.")
        // A short reference for the owner (the draft's internal id stays in the log result).
        val ref = tools.log("${kind.name.lowercase()} ${plan.partyName}", "draft", "${KaiFormat.rupees(plan.amount.toDouble())} ${plan.mode} draft=${plan.key}", ActionStatus.DRAFT, null)
        plans[plan.key] = plan.copy(reference = ref)
        conversationState.pendingDraft = plans[plan.key]
        conversationState.pendingConfirmation = true
        conversationState.pendingCorrection = false
        conversationState.currentIntent = "PAYMENT_DRAFT"
        conversationState.currentAction = plan.kind.name
        conversationState.lastPerson = plan.partyName
        conversationState.lastRelevantEntity = plan.partyName
        conversationState.lastAmount = plan.amount
        conversationState.lastPaymentDirection = if (plan.kind == PlanKind.PAYMENT_IN || plan.kind == PlanKind.DEBIT_TAKEN) "IN" else "OUT"
        conversationState.lastPaymentMode = plan.mode
        lastDraft = r.said to "PAYMENT"
        val a = KaiFormat.rupees(plan.amount.toDouble())
        val what = when (kind) {
            PlanKind.PAYMENT_OUT -> pick(lang, ta = "பணம் கொடுத்தது (payment out)", tl = "Payment out", en = "Payment out")
            PlanKind.PAYMENT_IN -> pick(lang, ta = "பணம் வந்தது (payment in)", tl = "Payment in", en = "Payment in")
            PlanKind.CREDIT_GIVEN -> pick(lang, ta = "Credit — அவர் உங்களுக்கு தரணும்", tl = "Credit — avar ungalukku tharanum", en = "Credit — they owe you")
            PlanKind.DEBIT_TAKEN -> pick(lang, ta = "Debit — நீங்க அவருக்கு தரணும்", tl = "Debit — neenga avarukku tharanum", en = "Debit — you owe them")
        }
        val lines = buildList {
            add("${plan.partyName} — $a")
            add(what)
            add(pick(lang, ta = "முறை: ${modeName(plan.mode, lang)}", tl = "Mode: ${modeName(plan.mode, lang)}", en = "Mode: ${modeName(plan.mode, lang)}"))
            if (plan.settles.isNotEmpty()) add(pick(lang, ta = "சரி செய்வது: ", tl = "Settles: ", en = "Settles: ") +
                plan.settles.joinToString(", ") { (n, v) -> "$n ${KaiFormat.rupees(v.toDouble())}" })
            if (plan.advance.signum() > 0) add(pick(lang, ta = "${KaiFormat.rupees(plan.advance.toDouble())} advance-ஆ சேமிக்கப்படும்",
                tl = "${KaiFormat.rupees(plan.advance.toDouble())} advance-ah save aagum", en = "${KaiFormat.rupees(plan.advance.toDouble())} will be kept as advance"))
            if (plan.balanceBefore != null && plan.balanceAfter != null) add(pick(lang, ta = "பாக்கி: ", tl = "Balance: ", en = "Balance: ") +
                "${KaiFormat.rupees(plan.balanceBefore.toDouble())} → ${KaiFormat.rupees(plan.balanceAfter.toDouble())}")
            add(pick(lang, ta = "குறிப்பு எண்: $ref", tl = "Ref: $ref", en = "Ref: $ref"))
        }
        val blocked = plan.problems.isNotEmpty()
        val intro = pick(lang,
            ta = "நான் புரிஞ்சுகிட்டது இது ஓனர். சேர்க்கட்டுமா?",
            tl = "Owner, naan purinjukittadhu idhu. Add pannalama?",
            en = "Here's what I understood, Owner. Add this transaction?")
        return KaiTurn(
            ChatReply(intro, if (kind == PlanKind.PAYMENT_IN || kind == PlanKind.CREDIT_GIVEN) KaiMood.CREDIT else KaiMood.DEBIT, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(
                lines,
                listOf(
                    KaiButton(pick(lang, ta = "உறுதி செய்", tl = "Confirm", en = "Confirm"), KaiAction.ConfirmPlan(plan.key), primary = true, enabled = !blocked),
                    KaiButton(pick(lang, ta = "மாற்று", tl = "Edit", en = "Edit"), KaiAction.EditPlan(plan.key)),
                    KaiButton(cancelLabel(lang), KaiAction.CancelPlan(plan.key)),
                ),
                warning = plan.problems.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            ),
            plans[plan.key],
        )
    }

    private suspend fun confirm(key: String, lang: KaiLang): KaiTurn? {
        val plan = plans.remove(key) ?: return null
        conversationState.pendingDraft = null
        conversationState.pendingConfirmation = false
        return when (val outcome = tools.confirm(plan)) {
            is ActionOutcome.Done -> {
                tools.log("${plan.kind.name.lowercase()} ${plan.partyName}", "transaction engine", "saved ${outcome.reference}", ActionStatus.CONFIRMED, plan.reference ?: plan.key)
                val a = KaiFormat.rupees(plan.amount.toDouble())
                val after = outcome.balanceAfter?.let { KaiFormat.rupees(it.toDouble()) }
                say(lang, KaiMood.SUCCESS, null,
                    ta = "சேமிச்சுட்டேன் ஓனர். ${plan.partyName} — $a (${outcome.reference})." + (after?.let { " இப்போ பாக்கி $it." } ?: ""),
                    tl = "Save aagiduchu Owner. ${plan.partyName} — $a (${outcome.reference})." + (after?.let { " Ippo balance $it." } ?: ""),
                    en = "Saved, Owner. ${plan.partyName} — $a (${outcome.reference})." + (after?.let { " Balance now $it." } ?: ""))
            }
            is ActionOutcome.Failed -> {
                tools.log("${plan.kind.name.lowercase()} ${plan.partyName}", "transaction engine", outcome.reason, ActionStatus.FAILED, plan.reference ?: plan.key)
                say(lang, KaiMood.ERROR, null,
                    ta = "சேமிக்க முடியல ஓனர்: ${outcome.reason}", tl = "Owner, save aagala: ${outcome.reason}", en = "It wasn't saved, Owner: ${outcome.reason}")
            }
        }
    }

    private suspend fun cancelPlan(key: String, lang: KaiLang): KaiTurn {
        plans.remove(key)?.let {
            conversationState.pendingDraft = null
            conversationState.pendingConfirmation = false
            conversationState.pendingCorrection = false
            tools.discard(it)
            tools.log("${it.kind.name.lowercase()} ${it.partyName}", "draft", "discarded", ActionStatus.CANCELLED, it.reference ?: it.key)
        }
        return say(lang, KaiMood.NEUTRAL, null, ta = "சரி, ரத்து பண்ணிட்டேன். எதுவும் சேமிக்கல.", tl = "Seri Owner, cancel pannitten. Edhuvum save aagala.", en = "Cancelled, Owner. Nothing was saved.")
    }

    // ------------------------------------------------------------ phone / documents

    private suspend fun call(name: String, lang: KaiLang, said: String = ""): KaiTurn {
        val match = runCatching { tools.parties(name) }.getOrNull()?.firstOrNull { it.name.equals(name, true) } ?: runCatching { tools.parties(name) }.getOrNull()?.firstOrNull()
        val phone = match?.phone
        val shown = match?.name ?: name
        tools.log(com.shopai.app.brain.tools.KaiIntents.CALL_CONTACT, "dialer", "$shown: " + if (phone != null) "number found" else "no number", ActionStatus.OPENED, null, said)
        val text = if (phone != null) pick(lang, ta = "$shown-க்கு call பண்ணலாமா ஓனர்?", tl = "Owner, $shown-ku call pannalama?", en = "Call $shown, Owner?")
        else pick(lang, ta = "$shown நம்பர் பதிவுல இல்ல ஓனர். Dialer திறக்கட்டுமா?", tl = "Owner, $shown number records-la illa. Dialer open pannava?", en = "$shown's number isn't in your records, Owner. Open the dialer?")
        return KaiTurn(
            ChatReply(text, KaiMood.NEUTRAL, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(listOfNotNull(phone?.let { "$shown · $it" }), listOf(KaiButton(pick(lang, ta = "$shown-க்கு call", tl = "Call $shown", en = "Call $shown"), KaiAction.Dial(shown, phone), primary = true))),
        )
    }

    /** "Bill scan pannu" — the camera opens straight away (OPEN_BILL_SCANNER); the bill becomes a draft the owner checks. */
    private fun scan(classifyOnly: Boolean, lang: KaiLang, said: String = ""): KaiTurn {
        tools.log(com.shopai.app.brain.tools.KaiIntents.OPEN_BILL_SCANNER, "bill scanner", if (classifyOnly) "camera opened (purchase / sale check)" else "camera opened", ActionStatus.OPENED, null, said)
        val text = if (classifyOnly) pick(lang,
            ta = "சரி ஓனர், பில் ஸ்கேன் பண்ணலாம் 📷 இது purchase-ஆ sale-ஆ என்று பார்த்து சொல்லுவேன் — நீங்க உறுதி செய்த பிறகுதான் சேமிப்பு.",
            tl = "Sure Owner, bill scan pannalam 📷 Idhu purchase-ah sale-ah nu paathu solluven — neenga confirm pannina apram dhaan save.",
            en = "Sure Owner, let's scan the bill 📷 I'll tell you if it's a purchase or a sale — nothing is saved until you confirm.")
        else pick(lang,
            ta = "சரி ஓனர், பில் ஸ்கேன் பண்ணலாம் 📷",
            tl = "Sure Owner, bill scan pannalam 📷",
            en = "Sure Owner, let's scan the bill 📷")
        return KaiTurn(
            ChatReply(text, KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(emptyList(), listOf(KaiButton(pick(lang, ta = "பில் ஸ்கேன்", tl = "Scan bill", en = "Scan bill"), KaiAction.OpenScanner, primary = true))),
            direct = KaiAction.OpenScanner,
        )
    }

    // ------------------------------------------------------------ business data (books)

    private fun unverified(lang: KaiLang, what: String): KaiTurn {
        tools.log(what, "books", "unavailable", ActionStatus.FAILED)
        return say(lang, KaiMood.CONCERNED, null,
            ta = "இதை உங்க கணக்கு பதிவுல சரிபார்க்க முடியல ஓனர்.",
            tl = "Owner, idha unga business records-la verify panna mudiyala.",
            en = "I couldn't verify that from your business records, Owner.")
    }

    private suspend fun stock(product: String?, lang: KaiLang): KaiTurn {
        product?.let { rememberProduct(it) }
        val facts = tools.stock(product) ?: return unverified(lang, "stock")
        if (facts.isEmpty()) return say(lang, KaiMood.CLARIFY, null,
            ta = "${product ?: "அந்த"} பொருள் inventory-ல இல்ல ஓனர்.", tl = "Owner, ${product ?: "andha"} product inventory-la illa.", en = "${product ?: "That product"} isn't in your inventory, Owner.")
        tools.log("stock ${product ?: "all"}", "inventory", "${facts.size} items", ActionStatus.ANSWERED)
        if (product != null && facts.size == 1) {
            val f = facts.single()
            // "Colgate stock low ah?" … "20 add pannu": the product talked about.
            lastProduct = runCatching { tools.products() }.getOrNull()?.firstOrNull { it.name.equals(f.name, ignoreCase = true) } ?: lastProduct
            val q = qty(f.qty, f.unit)
            return say(lang, KaiMood.EXPLAINING, null, ta = "${f.name} stock $q இருக்கு ஓனர்.", tl = "Owner, ${f.name} stock $q irukku.", en = "${f.name}: $q in stock, Owner.")
        }
        return KaiTurn(
            ChatReply(pick(lang, ta = "Stock விவரம் ஓனர்:", tl = "Owner, stock details:", en = "Stock, Owner:"), KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(facts.take(10).map { "${it.name} — ${qty(it.qty, it.unit)}" }, emptyList()),
        )
    }

    private suspend fun lowStock(lang: KaiLang): KaiTurn {
        val low = tools.lowStock() ?: return unverified(lang, "low stock")
        tools.log("low stock", "inventory", "${low.size} items", ActionStatus.ANSWERED)
        if (low.isEmpty()) return say(lang, KaiMood.HAPPY, null,
            ta = "எந்த பொருளும் reorder அளவுக்கு கீழ இல்ல ஓனர்.", tl = "Owner, endha product-um reorder level-ku keezha illa.", en = "Nothing is below its reorder level, Owner.")
        return KaiTurn(
            ChatReply(pick(lang, ta = "குறைவான stock ஓனர் (${low.size}):", tl = "Owner, low stock (${low.size}):", en = "Low stock, Owner (${low.size}):"), KaiMood.CONCERNED, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(low.take(10).map { f -> "${f.name} — ${qty(f.qty, f.unit)}" + (f.reorderAt?.let { " (reorder ${qty(it, f.unit)})" } ?: "") }, emptyList()),
        )
    }

    private suspend fun money(kind: MoneyKind, lang: KaiLang): KaiTurn {
        val all = tools.moneyBalances() ?: return unverified(lang, "money balance")
        val list = if (kind == MoneyKind.ALL) all else all.filter { it.kind == kind }
        if (list.isEmpty()) return unverified(lang, "money balance ${kind.name}")
        tools.log("balance ${kind.name.lowercase()}", "ledger", list.joinToString { "${it.name} ${it.amount}" }, ActionStatus.ANSWERED)
        val total = list.fold(BigDecimal.ZERO) { s, b -> s + b.amount }
        val a = KaiFormat.rupees(total.toDouble())
        val label = when (kind) {
            MoneyKind.CASH -> pick(lang, ta = "கையில cash", tl = "Cash", en = "Cash in hand")
            MoneyKind.BANK -> pick(lang, ta = "Bank-ல", tl = "Bank-la", en = "Bank")
            MoneyKind.UPI -> "UPI"
            MoneyKind.ALL -> pick(lang, ta = "மொத்த பணம்", tl = "Total money", en = "Total money")
        }
        return KaiTurn(
            ChatReply(pick(lang, ta = "ஓனர், $label $a.", tl = "Owner, $label $a.", en = "Owner, $label: $a."), KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
            if (list.size > 1) KaiCard(list.map { "${it.name} — ${KaiFormat.rupees(it.amount.toDouble())}" }, emptyList()) else null,
        )
    }

    private suspend fun topProducts(text: String, lang: KaiLang, today: LocalDate, people: List<String>): KaiTurn {
        val q = KaiChatUnderstanding.understand(text, today, people)
        val period = q.period ?: ChatPeriod(today.withDayOfMonth(1), today, ChatPeriod.Kind.THIS_MONTH)
        val top = tools.topProducts(period.from, minOf(period.to, today), 5) ?: return unverified(lang, "top products")
        tools.log("top products", "sales ledger", top.joinToString { it.name }, ActionStatus.ANSWERED)
        if (top.isEmpty()) return say(lang, KaiMood.CLARIFY, null,
            ta = "இந்த காலத்துல item sales பதிவுல இல்ல ஓனர்.", tl = "Owner, indha period-la item sales record illa.", en = "There are no item sales recorded for that period, Owner.")
        val best = top.first()
        return KaiTurn(
            ChatReply(pick(lang,
                ta = "அதிகம் விற்றது ${best.name} — ${KaiFormat.rupees(best.value.toDouble())} ஓனர்.",
                tl = "Owner, adhigama vithadhu ${best.name} — ${KaiFormat.rupees(best.value.toDouble())}.",
                en = "Your best seller is ${best.name} — ${KaiFormat.rupees(best.value.toDouble())}, Owner."), KaiMood.HAPPY, ChatIntent.MONTHLY_SALES),
            KaiCard(top.mapIndexed { i, p -> "${i + 1}. ${p.name} — ${KaiFormat.rupees(p.value.toDouble())}" }, emptyList()),
        )
    }

    /** Sales / purchases / expenses from the books engine; every other question goes to the Business Brain. */
    private suspend fun question(text: String, lang: KaiLang, today: LocalDate, people: List<String>): KaiTurn {
        val q = KaiChatUnderstanding.understand(text, today, people)
        if (q.intent in setOf(ChatIntent.MONTHLY_SALES, ChatIntent.MONTHLY_PURCHASES, ChatIntent.EXPENSE_SUMMARY)) {
            val period = q.period ?: if (has(text, "inniku", "innaikku", "today", "இன்னைக்கு")) ChatPeriod(today, today, ChatPeriod.Kind.TODAY)
            else ChatPeriod(today.withDayOfMonth(1), today, ChatPeriod.Kind.THIS_MONTH)
            val to = minOf(period.to, today)
            val value = when (q.intent) {
                ChatIntent.MONTHLY_SALES -> tools.sales(period.from, to)
                ChatIntent.MONTHLY_PURCHASES -> tools.purchases(period.from, to)
                else -> tools.expenses(period.from, to)
            }
            if (value != null) {
                val a = KaiFormat.rupees(value.toDouble())
                val label = periodName(period, lang, today)
                tools.log(q.intent.name.lowercase(), "ledger", "$label $a", ActionStatus.ANSWERED)
                val text2 = when (q.intent) {
                    ChatIntent.MONTHLY_SALES -> pick(lang, ta = "$label sales $a ஓனர்.", tl = "Owner, $label sales $a.", en = "$label sales: $a, Owner.")
                    ChatIntent.MONTHLY_PURCHASES -> pick(lang, ta = "$label purchase $a ஓனர்.", tl = "Owner, $label purchase $a.", en = "$label purchases: $a, Owner.")
                    else -> pick(lang, ta = "$label செலவு $a ஓனர்.", tl = "Owner, $label selavu $a.", en = "$label expenses: $a, Owner.")
                }
                return KaiTurn(ChatReply(text2, KaiMood.EXPLAINING, q.intent))
            }
        }
        val reply = brain.ask(text)
        tools.log(reply.intent.name.lowercase(), "business brain", "answered", ActionStatus.ANSWERED)
        return KaiTurn(reply)
    }

    // ------------------------------------------------------------ wording

    private fun say(lang: KaiLang, mood: KaiMood, log: String?, ta: String, tl: String, en: String): KaiTurn {
        log?.let { tools.log(it, "kai", "asked", ActionStatus.ANSWERED) }
        return KaiTurn(ChatReply(pick(lang, ta, tl, en), mood, ChatIntent.GENERAL_BUSINESS_QUERY))
    }

    private fun pick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) { KaiLang.TAMIL -> ta; KaiLang.TANGLISH -> tl; KaiLang.ENGLISH -> en }

    private fun cancelLabel(lang: KaiLang) = pick(lang, ta = "ரத்து", tl = "Cancel", en = "Cancel")

    private fun modeName(mode: PaymentMode, lang: KaiLang) = when (mode) {
        PaymentMode.CASH -> pick(lang, ta = "ரொக்கம் (Cash)", tl = "Cash", en = "Cash")
        PaymentMode.UPI -> "UPI"
        PaymentMode.BANK_TRANSFER -> "Bank"
        PaymentMode.CHEQUE -> "Cheque"
        PaymentMode.CARD -> "Card"
        else -> mode.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun clock(t: LocalDateTime) = t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))

    private fun periodName(p: ChatPeriod, lang: KaiLang, today: LocalDate): String = when (p.kind) {
        ChatPeriod.Kind.TODAY -> pick(lang, ta = "இன்னைக்கு", tl = "Innaikku", en = "Today's")
        ChatPeriod.Kind.YESTERDAY -> pick(lang, ta = "நேத்து", tl = "Nethu", en = "Yesterday's")
        ChatPeriod.Kind.THIS_WEEK -> pick(lang, ta = "இந்த வாரம்", tl = "Indha week", en = "This week's")
        ChatPeriod.Kind.THIS_MONTH -> pick(lang, ta = "இந்த மாசம்", tl = "Indha month", en = "This month's")
        ChatPeriod.Kind.LAST_MONTH -> pick(lang, ta = "போன மாசம்", tl = "Pona month", en = "Last month's")
        else -> if (p.from == p.to) KaiFormat.date(p.from, lang, today) else "${KaiFormat.date(p.from, lang, today)} – ${KaiFormat.date(p.to, lang, today)}"
    }

    private fun qty(q: BigDecimal, unit: String): String {
        val v = q.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
        return "$v $unit"
    }

    private fun has(text: String, vararg words: String) = words.any { text.lowercase(Locale.ROOT).contains(it) }

    private fun newKey() = UUID.randomUUID().toString().take(8)
}
