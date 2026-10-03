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
    memory: KaiMemoryAccess? = null,
    /** Morning Work (MORNING_WORK intent); null: not available here. */
    private val morning: KaiMorningAccess? = null,
) {
    private data class StockPlan(val key: String, val product: com.shopai.app.brain.tools.ProductRef, val qty: BigDecimal, val incoming: Boolean, val said: String, val unit: String)
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
    private data class PaymentRequest(val key: String, val name: String?, val amount: BigDecimal?, val outgoing: Boolean, val mode: PaymentMode, val said: String)

    private val plans = LinkedHashMap<String, ActionPlan>()
    private val requests = LinkedHashMap<String, PaymentRequest>()
    /** A payment still missing its amount or person; the next message can complete it. */
    private var incompletePayment: PaymentRequest? = null
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
        val afterBrief = briefJustShown
        briefJustShown = false
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
        // The owner's own language first: answers to Kai's question, teaching, forgetting, "'X' nu enna meaning?".
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

        // Completing what Kai just asked for (a reminder's time, a stock quantity, a payment's amount / name).
        reminders.continueWith(text, lang)?.let { return it }
        stockQuestion?.let { q ->
            stockQuestion = null
            com.shopai.app.brain.tools.KaiStock.quantityIn(text)?.takeIf { KaiCommands.route(text, at, people).let { c -> c == KaiCommand.Question || c is KaiCommand.Calculate } }
                ?.let { (qty, unit) -> return stockDraft(q.product, qty, unit ?: q.product.unit, q.incoming, "${q.said} · $text", lang) }
        }
        incompletePayment?.let { p ->
            incompletePayment = null
            val cmd = KaiCommands.route(text, at, people)
            if (cmd == KaiCommand.Question || cmd is KaiCommand.Calculate) {
                val amount = p.amount ?: com.shopai.app.brain.KaiUnderstanding.amountsIn(text, at.toLocalDate()).singleOrNull()?.let { BigDecimal.valueOf(it) }
                val name = p.name ?: KaiCommands.personIn(text, people) ?: text.takeIf { it.split(' ').size <= 3 && it.none(Char::isDigit) }
                if (amount != p.amount || name != p.name) return payment(p.copy(amount = amount, name = name), lang)
            }
        }

        // The owner's Morning Routine ("Stock first, collection next") — proposed, saved only on [Save].
        routine?.handle(said, text, lang, afterBrief, people)?.let { return it }

        // MORNING_WORK is a core intent: chat, voice and a scheduled morning brief all use the one MorningWorkEngine.
        // An explicit reminder ("tomorrow morning remind me …", "morning 10 manikku … remind pannu") stays a reminder.
        val morningAsk = com.shopai.app.brain.morning.MorningCommands.morningRequest(said) ?: com.shopai.app.brain.morning.MorningCommands.morningRequest(text)
        if (morningAsk != null && !com.shopai.app.brain.tools.KaiReminderUnderstanding.mentionsReminder(text)) return morningWork(morningAsk, text, said, lang, people)

        // Priority: reminder → stock in → stock out → bill scanner → call → money → questions → calculator → conversation → learn.
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
            is KaiCommand.Calculate -> calculate(cmd.answer, lang, text)
            is KaiCommand.Payment -> payment(PaymentRequest(newKey(), cmd.name, cmd.amount, cmd.outgoing, cmd.mode, text), lang)
            is KaiCommand.Reminder -> reminders.handle(cmd.request, lang)
            is KaiCommand.Call -> call(cmd.name, lang, said)
            is KaiCommand.ScanBill -> scan(cmd.classifyOnly, lang, said)
            is KaiCommand.Stock -> stock(cmd.product, lang)
            KaiCommand.LowStock -> lowStock(lang)
            is KaiCommand.MoneyBalance -> money(cmd.kind, lang)
            KaiCommand.TopProducts -> topProducts(text, lang, at.toLocalDate(), people)
            KaiCommand.Question -> conversation(text, said, lang, at)
                ?: learner?.unknown(text, said, lang, people, products.orEmpty().map { it.name })
                ?: questionOrLearn(applied, text, said, lang, at.toLocalDate(), people, products.orEmpty())
        }
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
        val qty = req.qty ?: return askQuantity(product, req.incoming, said, lang)
        return stockDraft(product, qty, req.unit ?: product.unit, req.incoming, said, lang)
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
    private fun stockDraft(product: com.shopai.app.brain.tools.ProductRef, qty: BigDecimal, unit: String, incoming: Boolean, said: String, lang: KaiLang, key: String = newKey()): KaiTurn {
        lastProduct = product
        stockPlans[key] = StockPlan(key, product, qty, incoming, said, unit)
        lastDraft = said to "STOCK"
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
    fun stockDraftOf(key: String): Triple<String, BigDecimal, String>? = stockPlans[key]?.let { Triple(it.product.name, it.qty, it.unit) }

    /** Edit: a new quantity / unit for the draft — shown again for Confirm. */
    fun reviseStock(key: String, qty: BigDecimal, unit: String, lang: KaiLang): KaiTurn? {
        val plan = stockPlans.remove(key) ?: return null
        if (qty.signum() <= 0) return null
        return stockDraft(plan.product, qty, unit.ifBlank { plan.unit }, plan.incoming, plan.said, lang)
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
        return when (val outcome = tools.changeStock(plan.product, plan.qty, plan.incoming, plan.said)) {
            is ActionOutcome.Done -> {
                tools.log(intent, "inventory engine", "${plan.product.name} ${qty(plan.qty, plan.unit)} saved", ActionStatus.CONFIRMED, null, plan.said)
                stockDone(plan.product.name, plan.qty, plan.unit, plan.incoming, outcome.balanceAfter?.let { com.shopai.app.brain.tools.KaiStock.shown(it, plan.product.unit) }, lang)
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
        lastBrief = null
        briefJustShown = false
        lastProduct = null
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
        if (r.amount == null || r.amount.signum() <= 0) {
            incompletePayment = r
            return say(lang, KaiMood.CLARIFY, "payment: amount missing",
                ta = "எவ்வளவு தொகை ஓனர்?", tl = "Evlo amount Owner?", en = "How much was it, Owner?")
        }
        if (r.name.isNullOrBlank()) {
            incompletePayment = r
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
