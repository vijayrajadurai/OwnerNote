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
    /** "Kadaiku saman vandhuruku": the list of goods the owner checked one by one — saved together, changed, or dropped. */
    data object ConfirmGoods : KaiAction
    data object EditGoods : KaiAction
    data object CancelGoods : KaiAction
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
    /**
     * A person's statement as a PDF, made on the phone from the books ([from] null: the whole history). [whatsapp]: WhatsApp opens
     * with it in that person's chat — the owner presses send; otherwise the phone's share sheet.
     */
    data class ShareStatement(val partyId: String, val name: String, val customer: Boolean, val from: LocalDate?, val to: LocalDate, val whatsapp: Boolean) : KaiAction
    /** "Endha Kumar?" for a statement: this record's. */
    data class StatementFor(val partyId: String, val customer: Boolean, val from: LocalDate?, val to: LocalDate) : KaiAction
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
/** What Kai asked and is waiting for: the due date of a stated payment, or its amount ("Kumar-ku cash kudukanum"). */
internal enum class KaiPendingQuestion { DUE_DATE, AMOUNT }
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
    /** "10" said for a due date without a month: Kai asks this month or next (never guesses). */
    var pendingDay: Int? = null,
    /** "adutha maasam" said without a day: 0 = this month, 1 = next month; Kai asks the day. */
    var pendingMonthOffset: Int? = null,
    /** The language the payment was stated in — short answers ("10", "next month") keep it. */
    var pendingLang: com.shopai.app.brain.KaiLang? = null,
    /** The turn in which Kai asked for the amount / date / month: a bare "10" answers only the very next turn. */
    var pendingAskedTurn: Long = -1,
    var pendingDraft: ActionPlan? = null,
    var pendingConfirmation: Boolean = false,
    var pendingCorrection: Boolean = false,
    var lastBusinessTopic: String? = null,
    var previousBusinessContext: String? = null,
    var previousTopicBeforeCalculator: String? = null,
    var conversationTurn: Long = 0,
    /** A payment the owner stated in conversation ("Mahesh enaku 2000 tharanum") and has not saved yet. */
    var stated: KaiStatedPayment? = null,
    /** "Kumar enakku 3000" then "naan Kumar-ku 3000": the earlier, opposite-side statement — still unsaved, still answerable. */
    var statedOtherSide: KaiStatedPayment? = null,
    /** The turn Kai asked "already ₹2,000 records-la irukku — adhey-aa, pudhusaa?" for [stated] (-1: not asked). */
    var duplicateAskedTurn: Long = -1,
    /** "September 30" said just after it passed: that day this year — Kai asks it or next year's (null: not asked). */
    var pendingPastDate: LocalDate? = null,
    /** The draft card made from [stated] ("save panniko"); confirming it saves the stated payment. */
    var statedDraftKey: String? = null,
    /** The last payment the books confirmed as saved in this conversation (never set without the engine's Done). */
    var lastSaved: KaiSavedPayment? = null,
    /** The turn whose answer showed (or pointed at) the open payment draft: a bare "ama" / "seri" confirms it only right after. */
    var draftShownTurn: Long = -1,
    /** The exact record being talked about ("Nagapattinam Lokesh"), when the name alone is not unique. */
    var focusPartyId: String? = null,
    /** People named together in one message ("Kumar and Ramesh …"): a later "avan" is then asked about, never guessed. */
    var mentionedPeople: List<String> = emptyList(),
    var mentionedTurn: Long = -1,
    /** "Lokesh-nu rendu records irukku — Chennai-aa, Nagapattinam-aa?": waiting for the owner to pick. */
    var entityChoice: KaiEntityChoice? = null,
    /** "avan eppa?" with two people just named: the question, kept until the owner says which one. */
    var referentQuestion: String? = null,
    /** Pieces said one at a time ("Mahesh" … "3000" … "enakku tharanum"). */
    var fragmentPerson: String? = null,
    var fragmentAmount: BigDecimal? = null,
    var fragmentTurn: Long = -1,
) {
    fun clear() {
        currentIntent = null; currentAction = null; lastRelevantEntity = null
        lastPerson = null; lastCustomer = null; lastSupplier = null; lastProduct = null
        lastAmount = null; lastUnit = null; lastDate = null; lastTime = null
        lastPaymentDirection = null; lastPaymentMode = null; lastQuestion = null
        pendingQuestion = null; pendingEntity = null; pendingAmount = null; pendingPaymentDirection = null
        pendingDay = null; pendingMonthOffset = null; pendingLang = null; pendingAskedTurn = -1
        pendingDraft = null; pendingConfirmation = false; pendingCorrection = false
        lastBusinessTopic = null; previousBusinessContext = null; previousTopicBeforeCalculator = null
        conversationTurn = 0
        stated = null; statedOtherSide = null; duplicateAskedTurn = -1; pendingPastDate = null; statedDraftKey = null; lastSaved = null; draftShownTurn = -1
        focusPartyId = null; mentionedPeople = emptyList(); mentionedTurn = -1; entityChoice = null; referentQuestion = null
        fragmentPerson = null; fragmentAmount = null; fragmentTurn = -1
    }
}

/** "Mahesh enaku 2000 tharanum": who, how much (null: not said yet), which way, and the due date once given. Session only — not a ledger entry. */
internal data class KaiStatedPayment(
    val person: String,
    val amount: BigDecimal?,
    val direction: KaiConversationPaymentDirection,
    val dueDate: LocalDate? = null,
    /** The record it belongs to, once resolved (a phone / place / the owner's pick told same-named people apart). */
    val partyId: String? = null,
    /** How Kai says who it is ("Nagapattinam Lokesh"); the name when it is unique. */
    val label: String? = null,
    /** The owner said it is a NEW amount even though the same amount is already in the books. */
    val newConfirmed: Boolean = false,
    /** The owner said this is a NEW person (not one of the same-named records): never re-asked "is it X?". */
    val newPerson: Boolean = false,
)

/** Same-named records the owner must pick from, and what to continue with once picked. */
internal data class KaiEntityChoice(
    val name: String,
    val candidates: List<com.shopai.app.brain.tools.PartyMatch>,
    val purpose: Purpose,
    /** The owner's own words that led to the question ("Kumar Shop enaku 300 tharanum"). */
    val said: String = "",
    /** An owner reference to remember for the record picked ("Kumar Anna" = this Kumar) — set only when the owner taught / corrected it. */
    val reference: String? = null,
    /** "Trichy Murugan" when the owner named a place none of the records has: offered as a NEW person. */
    val newName: String? = null,
) {
    enum class Purpose { STATED, DRAFT, FOCUS }
}

/** A payment the books saved (the engine returned Done with [reference]). */
internal data class KaiSavedPayment(val person: String, val amount: BigDecimal, val reference: String, val dueDate: LocalDate?, val receivable: Boolean = true,
                                     val partyId: String? = null, val kind: PlanKind? = null, val turn: Long = 0)

/** Context-aware safety language for an already open action draft. */
internal object KaiConversationSemantics {
    // "2k" is 2000; "2000 kuduthaanga" is never "2000 k" (owner's test, 11 Oct 2026: a draft of ₹20,00,000).
    private val amountPattern = Regex("""(?i)(?:₹|rs\.?\s*)?([0-9][0-9,]*(?:\.[0-9]+)?(?:\s*k(?![\p{L}]))?)""")

    fun correctionAmount(text: String): BigDecimal? {
        val n = text.trim().lowercase(Locale.ROOT)
        // Whole words only ("kuduthaanga" holds "thaan" but corrects nothing); "sorry 1500", "1500 nu maathu", "1500 தான்" correct too.
        val correctionCue = Regex("""(?<![\p{L}])(illai|illa|actually|wrong|thappu|thapu|tappu|no|amount|only|dhaan|thaan|than|daan|received|sorry|maathu|maatru|change|correct)(?![\p{L}])|மட்டும்|இல்லை|இல்ல|தான்|மாத்து|தப்பு""")
            .containsMatchIn(n)
        // "2000 illa 1500": the amount said after "illa" is the right one.
        val all = amountPattern.findAll(n).map { it.groupValues[1].replace(",", "").replace(Regex("\\s+"), "") }.toList()
        val raw = if (all.size == 2 && Regex("""\d\s*(?:illa|illai|இல்ல|இல்லை|not|no)\s*,?\s*\d""").containsMatchIn(n)) listOf(all.last()) else all
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

    private fun plain(text: String) = text.lowercase(Locale.ROOT).trim()
        .replace(Regex("""[.!?,;:]+"""), " ")
        .replace(Regex("""(?<![\p{L}])(owner|kai|anna|ji|please|pls)(?![\p{L}])"""), " ")
        .replace(Regex("""\s+"""), " ").trim()

    /** "note panniko", "save pannu", "kanakkula podu", "serthu vidu": the owner asks for the stated payment to go into the books. */
    private val saveWords = Regex(
        """(?<![\p{L}])(?:note|save|add|record|entry)\s*-?\s*(?:pannu|panniko|pannikko|pannikkonga|pannunga|pannikonga|pannidu|panniru|pannirunga|podu|pottudu)(?![\p{L}])|""" +
            """(?<![\p{L}])(?:kanakku\s*-?\s*la|kanakkula|kanakkil|account\s*-?\s*la|accountla|books\s*-?\s*la|ledger\s*-?\s*la)\s*(?:podu|pottudu|pottu\s*vai|ezhudhu|add\s*pannu|serthudu|serthu\s*vidu)(?![\p{L}])|""" +
            """(?<![\p{L}])(?:serthu\s*vidu|serthudu|saerthu\s*vidu)(?![\p{L}])|(?<![\p{L}])(?:save|add)\s+it(?![\p{L}])|""" +
            // "confirm" said to a stated payment: show its card (the card's Confirm still does the save).
            """(?<![\p{L}])confirm(?:\s*-?\s*(?:pannu|panniko|pannikko|pannunga|pannidu))?(?![\p{L}])|""" +
            """சேவ்\s*பண்ணு|சேமி|சேர்த்து\s*விடு|சேர்த்துடு|கணக்குல\s*போடு|கணக்கில்\s*போடு|நோட்\s*பண்ணிக்கோ|பதிவு\s*பண்ணு""",
    )
    /** "add pannitiya?", "save aagiducha?": the owner asks whether it was saved — never itself a request to save. */
    private val savedQuestion = Regex(
        """(?<![\p{L}])(?:(?:add|save|note|record|entry)\s*-?\s*)?(?:pannitiya|pannittiya|panitiya|pannitiyaa|pannitteengala|panniteengala|pannitingala|pannittingala|pannittaya)(?![\p{L}])|""" +
            """(?<![\p{L}])save\s*(?:aagiducha|agiducha|aachaa|aacha|achaa|acha|aayiducha)(?![\p{L}])|(?<![\p{L}])(?:did\s+you\s+(?:save|add)|is\s+it\s+saved)(?![\p{L}])|""" +
            """சேமிச்சியா|சேர்த்தியா|பண்ணிட்டியா""",
    )
    private val yesWords = setOf(
        "ama", "aama", "aamaa", "amaa", "aam", "yes", "yeah", "yep", "seri", "sari", "correct", "correct dhaan", "ok", "okay", "okey", "ok seri", "seri ok",
        "confirm", "confirm pannu", "confirm pannunga", "podu", "done", "ஆமா", "ஆம்", "சரி", "ஓகே", "உறுதி",
    )

    fun savesDraft(text: String): Boolean = !asksIfSaved(text) && saveWords.containsMatchIn(plain(text))
    private val filler = Regex("""(?<![\p{L}])(seri|sari|ok|okay|idha|idhai|adha|adhai|atha|athai|ithu|idhu|athu|adhu|this|that|ama|aama|yes|ippo|now|ellam|motham|பண்ணு|அதை|இதை|சரி)(?![\p{L}])""")
    /** Nothing but the save words ("save panniko", "seri, kanakkula podu") — or with [person] named — not "Colgate stock add pannu". */
    fun onlySave(text: String, person: String? = null): Boolean {
        if (!savesDraft(text)) return false
        var rest = saveWords.replace(plain(text), " ")
        if (person != null) rest = rest.replace(Regex("""(?i)(?<![\p{L}])${Regex.escape(person.lowercase(Locale.ROOT))}\S*"""), " ")
        return filler.replace(rest, " ").isBlank()
    }
    /** The language a short Tanglish phrase is in ("add pannitiya?" reads as English to a word counter). */
    fun phraseLang(text: String, lang: com.shopai.app.brain.KaiLang): com.shopai.app.brain.KaiLang =
        if (lang == com.shopai.app.brain.KaiLang.ENGLISH && Regex("""(?i)pann|podu|aagi|achaa|aacha|serthu|kanakku|(?<![a-z])(adhu|athu|idhu|ithu|athula|adhula|avan|avar|eppa|eppo|irukka|irukku|tharanum|kudukkanum|vaanganum)(?![a-z])|-?(aa|ah)\?""").containsMatchIn(text)) com.shopai.app.brain.KaiLang.TANGLISH else lang
    fun asksIfSaved(text: String): Boolean = savedQuestion.containsMatchIn(plain(text))
    /** A bare "ama" / "seri" / "ok" / "yes" — or "seri add pannu" — said to a draft Kai just showed. */
    fun confirmsDraft(text: String): Boolean = plain(text) in yesWords || savesDraft(text)
    /** "due venam", "due date vendaam", "date venam", "no due date": the payment stays — only its due date is not wanted. */
    private val noDueDate = Regex(
        """^(?:(?:seri|ok|okay|illa|no)\s+)?(?:due\s*date|due\s*thethi|due|date|thethi|deadline)\s*(?:-?\s*(?:um|lam|ellam))?\s*""" +
            """(?:venam|venaam|vendam|vendaam|venda|vendaa|veenam|illa|illai|thevai\s*illa|theva\s*illa|thevaiyilla|no|none|not\s*needed|skip|வேண்டாம்|இல்லை)$|""" +
            """^(?:no|without)\s+(?:due\s*date|due|date)$|^(?:தேதி|டியூ)\s*(?:வேண்டாம்|இல்லை)$|""" +
            // "theriyala" / "apram solren" to "due date eppa?": not known yet — the entry with no due date (owner, 12 Oct 2026).
            """^(?:(?:due\s*date|date|thethi)\s+)?(?:theriyala|theriyalai|teriyala|therila|therla|terla|theriyadhu|theriyaadhu|theriyathu|sollala|solla\s*mudiyala|""" +
            """apram\s+solren|aprom\s+solren|appuram\s+solren|later|no\s+idea|don'?t\s+know|not\s+sure|தெரியல|தெரியாது|அப்புறம்\s+சொல்றேன்)$""",
    )
    fun dropsDueDate(text: String): Boolean = noDueDate.matches(plain(text))

    /** "venam", "cancel", "vidunga": dropping a stated payment that has no draft yet ("illa" / "no" alone may answer a question). */
    fun dropsStated(text: String): Boolean = cancelsDraft(text) && plain(text) !in setOf("no", "illa", "illai")
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
    /** "athula", "adhu", "idhula" — the product just talked about, when no product is named. */
    private val productReference = Regex("""(?i)(?<![\p{L}])(?:athula|adhula|athila|adhila|athil|adhil|idhula|ithula|athu|adhu|idhu|ithu|adhoda|athoda|innum)(?![\p{L}])|அதுல|அதில்|இதுல|அதோட|இன்னும்""")
    /** "Ramesh dhaan", "aal maathu Ramesh": with a draft open, the person is corrected (owner's test, 11 Oct 2026). */
    private val personFix = Regex("""(?i)(?<![\p{L}])(dhaan|thaan|than|daan|maathu|maatru|change)(?![\p{L}])|தான்|மாத்து""")
    private var stockQuestion: StockQuestion? = null
    /** A new product being added by chat ("Colgate 5 box add pannu"): what is known, and the one detail Kai just asked (null = the summary is shown). */
    private data class NewItem(val key: String, val spec: com.shopai.app.brain.tools.ItemSpec, val said: String, val asked: com.shopai.app.brain.tools.ItemField?, val turn: Long, val lang: KaiLang)
    private var newItem: NewItem? = null
    /** "new stock add pannu" with no product: the turn Kai asked which product and how much. */
    private var stockLineAskedTurn = -10L
    /** "Colgate 5" for a product kept in pieces and boxes: Kai asked which unit — never guessed. */
    private data class UnitPick(val product: com.shopai.app.brain.tools.ProductRef, val qty: BigDecimal, val incoming: Boolean, val said: String, val turn: Long)
    private var unitPick: UnitPick? = null
    /** "Colgate selling price 38 aakku": a product change waiting for Confirm (nothing changes before it). */
    private data class ProductEdit(val key: String, val product: com.shopai.app.brain.tools.ProductRef, val change: com.shopai.app.brain.tools.ProductChange, val said: String, val lang: KaiLang, val turn: Long)
    private var productEdit: ProductEdit? = null
    /** Goods bought from a supplier / sold to a customer, being completed one question at a time (null fields are asked). */
    private data class BillItem(
        val key: String, val purchase: Boolean, val party: com.shopai.app.brain.tools.PartyMatch, val product: com.shopai.app.brain.tools.ProductRef,
        val parts: List<com.shopai.app.brain.tools.QtyPart>, val total: BigDecimal?, val rate: BigDecimal?, val credit: Boolean?,
        val mode: PaymentMode, val said: String, val lang: KaiLang, val turn: Long, val asked: String?,
    )
    private var billItem: BillItem? = null
    /** The last morning brief (Skip → its next task). */
    private var lastBrief: com.shopai.app.brain.morning.MorningBrief? = null
    private data class PaymentRequest(
        val key: String, val name: String?, val amount: BigDecimal?, val outgoing: Boolean,
        val mode: PaymentMode, val said: String, val directionKnown: Boolean = true,
        /** A stated payment's due date ("adutha maasam 5"): kept on the Credit / Debit entry. */
        val dueDate: LocalDate? = null,
        /** Drafted from the payment the owner stated in conversation ("Mahesh enaku 2000 tharanum" … "save panniko"). */
        val stated: Boolean = false,
        /** "Lokesh-ku 500 add pannu": a new entry on the side the person's record is on (customer → credit, supplier → debit). */
        val addEntry: Boolean = false,
        /** "Kumar 500 gpay": no give / take verb — the record decides (a customer paid in, the owner paid a supplier out). */
        val bySide: Boolean = false,
    )
    /** "Endha Lokesh Owner?" for a payment: the choices, so a typed / spoken "Chennai" picks one like its button. */
    private var planChoice: List<Pair<PartyMatch, KaiAction.ChoosePlan>>? = null

    private val plans = LinkedHashMap<String, ActionPlan>()

    /**
     * A line the mic cut off half way — a name and "pay" / "kitta" / "ku" and nothing else (owner, 11 Oct 2026: "Dinesh pay").
     * Kai asks who pays whom ([incoming] null), then how much and when; the full line then goes through as if said at once
     * (a draft for Confirm — nothing is written here).
     */
    private data class HalfSaid(val name: String, val said: String, val turn: Long, val lang: KaiLang, val incoming: Boolean? = null)
    private var halfSaid: HalfSaid? = null
    private val halfSaidLine = Regex("""(?i)^\s*([\p{L}\p{M}][\p{L}\p{M}\s.]*?)(?:\s*-\s*|\s+)(pay|payment|panam|paisa|kaasu|kitta|ku|kku|ukku|oda|kitte)\s*[?.!]*\s*$""")
    private val halfSaidAsks = Regex("""(?i)(?<![\p{L}])(yaar\p{L}*|yar\p{L}*|evlo|enna|eppo|eppa|who|how|what|avan|aval|avar|avanga|ivan|ivar)(?![\p{L}])|யார்|எவ்வளவு""")
    /** "enakku pay pannum", "enakku": to the owner. "naan pay pannanum", "naan", "avanukku": the owner pays. The person first, then the verb. */
    private val toOwnerWho = Regex("""(?i)(?<![\p{L}])(enakku|enaku|ennaku|yenakku|yenaku|me|us|engalukku)(?![\p{L}])|எனக்கு""")
    private val fromOwnerWho = Regex("""(?i)(?<![\p{L}])(naan|nan|naa|i|we|naanga|avanukku|avarukku|avangalukku)(?![\p{L}])|நான்""")
    private val toOwnerVerb = Regex("""(?i)(?<![\p{L}])(vaanganum|vanganum|varanum|collect|tharanum|tharuvan|tharuvaan|tharuvaanga|tharuvanga|tharuvaar|kudupan|kudupaan|kuduppaan|kudupaanga|kudupaaru)(?![\p{L}])""")
    private val fromOwnerVerb = Regex("""(?i)(?<![\p{L}])(kudukkanum|kodukkanum|kudukanum|kattanum)(?![\p{L}])""")
    private val payVerbs = Regex("""(?i)(?<![\p{L}])(pay|panni|pannum|pannanum|pannuvan|pannuvaan|pannuvaanga|tharanum|tharuvan|tharuvaan|tharuvaanga|kudukkanum|kodukkanum|kudupan|kudupaan|enakku|enaku|naan|nan|ku)(?![\p{L}])""")

    private suspend fun halfSaidStep(said: String, raw: String, lang0: KaiLang, people: List<String>): KaiTurn? {
        // "dinesh pay", "10000 nalaiku": too few words to read as English — the owner's Tanglish (or the Tamil already being spoken).
        val lang = if (lang0 == KaiLang.ENGLISH && !Regex("""(?i)(?<![\p{L}])(has|have|to|me|i|you|will|the|owes?)(?![\p{L}])""").containsMatchIn(said))
            halfSaid?.lang ?: KaiLang.TANGLISH else lang0
        halfSaid?.let { h ->
            halfSaid = null
            if (conversationState.conversationTurn - h.turn > 2) return@let
            if (KaiConversationSemantics.cancelsDraft(said) || KaiConversationCare.notNow(said)) return say(lang, KaiMood.NEUTRAL, "half said: dropped",
                ta = "சரி ஓனர், விட்டுடறேன்.", tl = "Seri Owner, vittudren.", en = "Okay Owner, I'll leave it.")
            val amount = com.shopai.app.brain.KaiUnderstanding.amountsIn(said, now().toLocalDate()).singleOrNull()
            val incoming = h.incoming ?: when {
                toOwnerWho.containsMatchIn(said) -> true
                fromOwnerWho.containsMatchIn(said) -> false
                toOwnerVerb.containsMatchIn(said) -> true
                fromOwnerVerb.containsMatchIn(said) -> false
                else -> null
            }
            if (incoming == null) {
                // Neither side said: "hmm", "enna?" asks again; anything else is a new line and goes its own way.
                if (said.trim().split(Regex("""\s+""")).size > 2 || amount != null || !notAName(said)) return@let
                halfSaid = h.copy(turn = conversationState.conversationTurn)
                return askHalfSide(h, lang)
            }
            if (amount == null) {
                if (h.incoming != null) return@let
                halfSaid = h.copy(turn = conversationState.conversationTurn, incoming = incoming)
                return say(lang, KaiMood.CLARIFY, "half said: amount",
                    ta = if (incoming) "சரி ஓனர், ${h.name} உங்களுக்கு பே பண்ணணும். எவ்வளவு, எப்போ பண்ணணும்?" else "சரி ஓனர், நீங்க ${h.name}-க்கு பே பண்ணணும். எவ்வளவு, எப்போ கொடுக்கணும்?",
                    tl = if (incoming) "Seri Owner, ${h.name} ungalukku pay pannanum. Evlo, eppa pannanum?" else "Seri Owner, neenga ${h.name}-ku pay pannanum. Evlo, eppa kudukkanum?",
                    en = if (incoming) "Okay Owner, ${h.name} has to pay you. How much, and when?" else "Okay Owner, you have to pay ${h.name}. How much, and when?")
            }
            // The whole line, as if the owner had said it at once: "Dinesh enakku 10000 naalaiku tharanum".
            val rest = payVerbs.replace(said, " ").replace(Regex("""\s+"""), " ").trim()
            val full = if (incoming) "${h.name} enakku $rest tharanum" else "naan ${h.name}-ku $rest tharanum"
            tools.log("half said", "kai", full, ActionStatus.ANSWERED)
            // Answered in the language the owner is speaking, whatever the rebuilt line looks like.
            val keep = forcedLang
            forcedLang = lang
            return try { answerOwner(full) } finally { forcedLang = keep }
        }
        // An answer to what Kai just asked ("full amount" to "Full ₹1,000-aa?") is never a new cut-off line.
        if (said.any(Char::isDigit) || kaiAskedAfterDraft || incompletePayment != null || stockQuestion != null || unitQuestion != null || newItem != null ||
            planChoice != null || relationChoice != null || conversationState.entityChoice != null) return null
        val m = halfSaidLine.matchEntire(said.trim()) ?: return null
        val name0 = m.groupValues[1].trim().trimEnd('-', '.').trim()
        val words = name0.split(Regex("""\s+"""))
        if (words.size > 3 || name0.length < 3 || notAName(name0) || productReference.containsMatchIn(name0) || halfSaidAsks.containsMatchIn(name0) ||
            words.any { it.lowercase(Locale.ROOT) in com.shopai.app.brain.tools.KaiFeed.notNames || it in com.shopai.app.brain.tools.KaiFeed.notNames }) return null
        // "Ramesh kitta?" right after a person's balance: the same question for Ramesh (a follow-up, not a cut-off line).
        if (conversationState.conversationTurn - conversationState.mentionedTurn <= 1 && conversationState.lastPerson != null) return null
        if (runCatching { tools.products() }.getOrNull().orEmpty().any { it.name.equals(name0, ignoreCase = true) }) return null
        val name = com.shopai.app.brain.KaiUnderstanding.knownPerson(name0, people)
            ?: name0.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
        val h = HalfSaid(name, raw.trim().trimEnd('.', '?', '!'), conversationState.conversationTurn, lang)
        halfSaid = h
        return askHalfSide(h, lang)
    }

    private fun askHalfSide(h: HalfSaid, lang: KaiLang) = say(lang, KaiMood.CLARIFY, "half said: which side",
        ta = "ஓனர், \"${h.said}\"-னு சொன்னீங்க. ${h.name} உங்களுக்கு பே பண்ணணுமா, இல்ல நீங்க ${h.name}-க்கு பே பண்ணணுமா?",
        tl = "Owner, \"${h.said}\"-nu sonneenga. ${h.name} ungalukku pay pannanuma, illa neenga ${h.name}-ku pay pannanuma?",
        en = "Owner, you said \"${h.said}\". Does ${h.name} have to pay you, or do you have to pay ${h.name}?")
    private val requests = LinkedHashMap<String, PaymentRequest>()
    /** A payment still missing its amount or person; the next message can complete it. */
    private var incompletePayment: PaymentRequest? = null

    /** "adhoda rate enna", "adhu evlo rate", "adhu stock evlo": a product word — the product just talked about (owner's test, 11 Oct 2026). */
    private val productPronoun = Regex("""(?i)(?<![\p{L}])(adhoda|athoda|adhu|athu|adha|atha|idhoda|idhu|ithu)(?![\p{L}])|அதோட|அது|இது""")
    private val productAsk = Regex("""(?i)(?<![\p{L}])(rate|price|vilai|selling|purchase|mrp|stock|iruppu|expiry|margin|profit|labam)(?![\p{L}])|விலை|ரேட்|ஸ்டாக்|லாபம்""")
    private val priceFollow = Regex("""(?i)(?<![\p{L}])(rate|price|vilai|selling|purchase|mrp|margin|profit|labam|laabam)(?![\p{L}])|விலை|ரேட்|லாபம்""")
    /** The turn a product's price / details were last answered: "labam evlo?" right after "Colgate rate enna?" is about Colgate. */
    private var productTalkTurn = -10L
    /** "Vengayathula" = Vengayam + "-la", "Ricela" = Rice + "-la": a shop product with a case ending, written apart ("Vengayam la"). */
    private fun productCase(text: String, productNames: List<String>): String {
        if (productNames.isEmpty()) return text
        val words = productNames.flatMap { it.split(Regex("""\s+""")) }.filter { it.length >= 3 && it.any(Char::isLetter) }
        fun product(w: String) = words.firstOrNull { it.equals(w, ignoreCase = true) }
        return Regex("""(?<![\p{L}])\p{L}{4,}(?![\p{L}])""").replace(text) { m ->
            val w = m.value
            if (product(w) != null) return@replace w
            Regex("""(?i)^(\p{L}{2,}?)a(?:tth|th|t)(?:ula|la|il|ila|oda|ode|ukku|uku)$""").find(w)?.let { c -> product(c.groupValues[1] + "am")?.let { return@replace "$it la" } }
            Regex("""(?i)^(\p{L}{3,}?)(?:ula|la|le|oda)$""").find(w)?.let { c -> product(c.groupValues[1])?.let { return@replace "$it la" } }
            w
        }
    }

    /** "வெங்காயத்துல" = "வெங்காயம்" + "-ல": with the product heard in it, written apart so the sound match finds it. */
    private fun tamilProductCase(text: String, productNames: List<String>, people: List<String>): String {
        if (productNames.isEmpty()) return text
        fun isProduct(w: String) = com.shopai.app.brain.tools.KaiProductSound.withProducts(w, productNames, people) != w ||
            com.shopai.app.brain.tools.KaiSpokenWords.withNames(w, productNames).let { n -> productNames.any { it.equals(n.trim(), ignoreCase = true) } }
        val am = Regex("""([\u0B80-\u0BFF]+?)த்(?:துல|துலே|துக்கு|தில்|தோட)(?![\u0B80-\u0BFF])""").replace(text) { m ->
            val whole = m.groupValues[1] + "ம்"
            if (isProduct(whole)) whole else m.value
        }
        // "ரைஸ்ல", "கோல்கேட்டுல": the product name with "-ல" glued on.
        return Regex("""(?<![\u0B80-\u0BFF])([\u0B80-\u0BFF]{2,}?)(்டுல|ுல|ல|லே|ோட)(?![\u0B80-\u0BFF])""").replace(am) { m ->
            // "கோல்கேட்டுல" = "கோல்கேட்" + "டுல", "ரைஸுல" = "ரைஸ்" + "ுல": the vowel sign took the place of the "்".
            val stem = m.groupValues[1] + if (m.groupValues[2] == "்டுல" || m.groupValues[2] == "ுல") "்" else ""
            if (!isProduct(m.value) && isProduct(stem)) stem else m.value
        }
    }

    private fun withLastProduct(text: String): String {
        val product = conversationState.lastProduct ?: return text
        if (!productAsk.containsMatchIn(text) || text.contains(product, ignoreCase = true)) return text
        productPronoun.find(text)?.let { m -> return text.replaceRange(m.range, product) }
        // "Colgate rate enna?" … "labam evlo?": a short price question with no product, right after one — the same product.
        val short = text.trim().split(Regex("""\s+""")).size <= 4
        return if (short && priceFollow.containsMatchIn(text) && text.none(Char::isDigit) && conversationState.conversationTurn - productTalkTurn <= 2) "$product $text" else text
    }
    /** "enakku", "naan", "ippo", "seri": words that are never a person's name (KaiFeed.notNames) — an answer made only of them names no one. */
    private fun notAName(text: String): Boolean = text.trim().split(Regex("""[\s,.!?]+""")).filter { it.isNotEmpty() }
        .let { ws -> ws.isNotEmpty() && ws.all { it.lowercase(Locale.ROOT) in com.shopai.app.brain.tools.KaiFeed.notNames || it in com.shopai.app.brain.tools.KaiFeed.notNames } }
    /** "enakku" / "எனக்கு": to the owner. */
    private val toOwner = Regex("""(?i)(?<![\p{L}])(enaku|enakku|ennaku|yenaku|yenakku|naan|nan|me)(?![\p{L}])|எனக்கு|நான்""")
    internal val conversationState = KaiConversationState()
    private var conversationScope: String? = null
    /** Reminders: the one reminder conversation (also used by the Speak screen). */
    private val reminders = KaiReminderAssistant(tools, books, now)

    fun plan(key: String): ActionPlan? = plans[key]

    /** Kai is waiting for the owner's answer about their own words (voice answers go to the same conversation). */
    val waitingForLearningAnswer: Boolean get() = learner?.isAsking == true || routine?.waiting == true

    /** A reminder rang and the owner opened it: its message with Call / Snooze / Done. */
    fun rang(id: String, lang: KaiLang): KaiTurn? = reminders.rang(id, lang)

    /**
     * Kai's last reply asked the owner something other than the open draft (a choice, a missing time, Save for a word,
     * "neenga saptingala?"): a bare "seri" / "ama" answers that question, not the draft.
     */
    private var kaiAskedAfterDraft = false

    /** The owner's last questions, as asked ("naan munnadi enna ketten?"). */
    private val recentAsks = ArrayDeque<String>()
    private var askDepth = 0

    suspend fun ask(raw: String): KaiTurn = run {
        if (askDepth == 0 && !KaiConversationCare.asksWhatWasAsked(raw)) { recentAsks.addLast(raw.trim()); while (recentAsks.size > 6) recentAsks.removeFirst() }
        askDepth++
        try { answerOwner(raw) } finally { askDepth-- }
    }.let { t ->
        // "Akash account clear" right after Akash paid ₹1,000 more than he owed: the advance is said too.
        val adv = advanceTold?.takeIf { (p, _) -> p == personOnTopic?.first && t.card == null && t.reply.text.contains(p) &&
            Regex("""(?i)clear|pending\s*illa|baaki\s*illa|பாக்கி எதுவும் இல்ல|nothing\s*(is\s*)?(due|pending)|no\s*balance""").containsMatchIn(t.reply.text) }
        if (adv == null) t else t.copy(reply = t.reply.copy(text = t.reply.text + pick(turnLang, ta = " ${adv.second} advance-ஆ இருக்கு.",
            tl = " ${adv.second} advance-aa irukku.", en = " ${adv.second} is kept as advance.")))
    }.also { t ->
        kaiAskedAfterDraft = conversationState.draftShownTurn != conversationState.conversationTurn &&
            (t.reply.mood == KaiMood.CLARIFY || t.card?.buttons?.isNotEmpty() == true || t.reply.text.trimEnd().endsWith("?"))
        // An answer with nothing to confirm: "puriyala" after it gets the same answer again, in Tamil.
        lastPlainAnswer = if (t.card == null && t.reply.mood != KaiMood.CLARIFY && !KaiConversationCare.ownerDidNotFollow(raw)) raw to turnLang else null
    }

    /**
     * Right after "edha pathi sollureenga? Payment-aa, stock-aa, reminder-aa?", or said alone: "payment", "kadan", "stock",
     * "balance" — the question for that, with the way to say it, so the owner's next line goes straight in (owner, 12 Oct 2026:
     * "if Kai doesn't understand, let it ask, and let the flow go on").
     */
    /** The language Kai's last "what is this about?" was asked in — a one-word answer ("stock") keeps it. */
    private var clarifyLang: KaiLang? = null

    private fun guidedTopic(said: String, saidLang: KaiLang): KaiTurn? {
        val t = said.lowercase(Locale.ROOT).trim().trimEnd('?', '.', '!').replace(Regex("""\s+(?:pathi|paththi|patthi|about|vishayam|பத்தி)$"""), "")
        val afterClarify = conversationState.conversationTurn - nothingOpenTurn == 1L
        val lang = if (afterClarify && saidLang == KaiLang.ENGLISH && forcedLang == null && !t.contains(" ")) clarifyLang ?: saidLang else saidLang
        val payment = t in setOf("payment", "payments", "panam", "kaasu", "paisa", "pana vishayam", "entry", "kanakku entry", "பேமெண்ட்", "பணம்", "காசு")
        val credit = t in setOf("kadan", "credit", "udhaar", "udhar", "கடன்", "கிரெடிட்")
        val stock = afterClarify && t in setOf("stock", "saamaan", "saaman", "product", "products", "item", "items", "ஸ்டாக்", "சாமான்")
        val balance = afterClarify && t in setOf("balance", "baaki", "bakki", "pending", "kanakku", "பாக்கி", "பேலன்ஸ்", "கணக்கு")
        return when {
            payment -> say(lang, KaiMood.CLARIFY, "guided: payment",
                ta = "சரி ஓனர், payment. யாரு, எவ்வளவு? வந்ததுனா “Kumar 500 குடுத்தான்”, நீங்க குடுத்ததுனா “ABC Traders-க்கு 500 குடுத்தேன்”-னு சொல்லுங்க.",
                tl = "Seri Owner, payment. Yaaru, evlo? Vandhadhu-na “Kumar 500 kuduthan”, neenga kuduthadhu-na “ABC Traders ku 500 kuduthen”-nu sollunga.",
                en = "Okay Owner, a payment. Who, and how much? Received: “Kumar paid 500”; paid: “paid ABC Traders 500”.")
            credit -> say(lang, KaiMood.CLARIFY, "guided: credit",
                ta = "சரி ஓனர், கடன். யாருக்கு, எவ்வளவு? “Kumar-க்கு 500 கடன்” இல்ல “நான் ABC Traders-க்கு 500 தரணும்”-னு சொல்லுங்க.",
                tl = "Seri Owner, kadan. Yaarukku, evlo? “Kumar ku 500 credit” illa “naan ABC Traders ku 500 tharanum”-nu sollunga.",
                en = "Okay Owner, credit. Who, and how much? “Kumar 500 credit”, or “I owe ABC Traders 500”.")
            stock -> say(lang, KaiMood.CLARIFY, "guided: stock",
                ta = "சரி ஓனர், stock. எந்த product, எவ்வளவு, வந்துச்சா வித்துச்சா? “Colgate 2 box வந்துச்சு”, “Rice 10 கிலோ வித்துச்சு”, “Colgate stock எவ்வளவு”.",
                tl = "Seri Owner, stock. Endha product, evlo, vandhucha vithucha? “Colgate 2 box vandhuchu”, “Rice 10 kg vithuchu”, “Colgate stock evlo”.",
                en = "Okay Owner, stock. Which product, how many, in or out? “Colgate 2 boxes came in”, “Rice 10 kg sold”, “Colgate stock”.")
            balance -> say(lang, KaiMood.CLARIFY, "guided: balance",
                ta = "சரி ஓனர், யாரோட பாக்கி? பேர் சொல்லுங்க (“Kumar எவ்வளவு தரணும்”), இல்ல “மொத்தம் எவ்வளவு வரணும்”.",
                tl = "Seri Owner, yaaroda baaki? Peru sollunga (“Kumar evlo tharanum”), illa “motham evlo varanum”.",
                en = "Okay Owner, whose balance? Say the name (“how much does Kumar owe”), or “total receivable”.")
            else -> null
        }
    }

    private val expenseWord = Regex("""(?i)(?<![\p{L}])(?:rent|vaadagai|vadagai|current\s*bill|eb\s*bill|electricity|salary|sambalam|petrol|diesel|gas\s*bill|gas|kooli|transport|selavu|selavaachu|selavu\s*aachu|expense)(?![\p{L}])|செலவு|செலவாச்சு|வாடகை|கரண்ட்\s*பில்""")

    /** "current bill 1200 kattinen", "rent kattanum", "petrol 500 selavu": an expense — never a person called "Current" / "Rent". */
    private fun expenseLine(text: String, lang: KaiLang, people: List<String>): KaiTurn? {
        if (!expenseWord.containsMatchIn(text) || KaiEntityResolver.peopleIn(text, people).isNotEmpty() ||
            Regex("""(?i)remind|nyabagam|ninaivu|alarm|booking|ரிமைண்ட்|ஞாபக|நினைவு""").containsMatchIn(text)) return null
        val what = expenseWord.find(text)!!.value.trim()
        val amount = Regex("""\d[\d,]*""").find(text)?.value?.let { " ₹$it" }.orEmpty()
        return say(lang, KaiMood.CLARIFY, "expense",
            ta = "ஓனர், இது செலவு மாதிரி இருக்கு ($what$amount). செலவை Daily Cash-ல OUT-ஆ போடுங்க — Kai கஸ்டமர் / சப்ளையர் கணக்கு மட்டும் போடும். ஒருத்தருக்கு குடுத்ததுனா அவங்க பேர் சொல்லுங்க.",
            tl = "Owner, idhu selavu maadhiri irukku ($what$amount). Selavu-a Daily Cash-la OUT-a podunga — Kai customer / supplier kanakku mattum podum. Oruthar-ku kuduthadhu-na avanga peru sollunga.",
            en = "Owner, this looks like an expense ($what$amount). Add expenses as OUT in Daily Cash — Kai keeps customer / supplier accounts. If you paid a person, tell me their name.")
    }

    /** "Colgate 2" waiting for which way the stock went: (product name, the count said, the turn). */
    private var productHalf: Triple<String, String, Long>? = null
    private val stockCame = Regex("""(?i)(?<![\p{L}])(?:vandhuchu|vanthuchu|vandhudhu|vandhirukku|vaanginen|vanginen|add|in|came|irakkinen|வந்துச்சு|வந்திருக்கு|வாங்கினேன்)(?![\p{L}])""")
    private val stockWent = Regex("""(?i)(?<![\p{L}])(?:vithuchu|vithudhu|vithen|vithuten|sold|pochu|poachu|out|damage|kettupochu|minus|வித்துச்சு|வித்தேன்|போச்சு)(?![\p{L}])""")

    private suspend fun productHalfStep(text: String, lang: KaiLang, products: List<com.shopai.app.brain.tools.ProductRef>): KaiTurn? {
        productHalf?.let { (name, qty, turn) ->
            productHalf = null
            if (conversationState.conversationTurn - turn <= 2 && text.trim().split(Regex("""\s+""")).size <= 3) {
                val line = when {
                    stockCame.containsMatchIn(text) -> "$name $qty vandhuchu"
                    stockWent.containsMatchIn(text) -> "$name $qty vithuchu"
                    else -> null
                }
                if (line != null) {
                    val keep = forcedLang
                    forcedLang = lang
                    return try { answerOwner(line) } finally { forcedLang = keep }
                }
            }
        }
        val product = productIn(text, products) ?: return null
        val rest = text.replace(Regex("""(?i)(?<![\p{L}])${Regex.escape(product.name)}(?![\p{L}])"""), " ").trim().trimEnd('?', '.', '!')
        if (rest.isEmpty()) return productDetails(product, lang)
        if (!Regex("""(?i)^\d+(?:\.\d+)?\s*(?:box|boxes|pcs|pieces|piece|kg|kilo|bag|bags|bottle|bottles|packet|packets|litre|liter|dozen)?$""").matches(rest)) return null
        productHalf = Triple(product.name, rest, conversationState.conversationTurn)
        return say(lang, KaiMood.CLARIFY, "product half: which way",
            ta = "ஓனர், ${product.name} $rest — வந்துச்சா (stock-ல add), இல்ல வித்துச்சா (stock-ல குறைக்கணுமா)?",
            tl = "Owner, ${product.name} $rest — vandhucha (stock-la add), illa vithucha (stock-la minus)?",
            en = "Owner, ${product.name} $rest — did it come in (add to stock), or go out (sold)?")
    }

    /** The owner's answer to "money to come in, or money you pay?": the question for that side, or null (they moved on). */
    private fun sideAnswered(said: String): String? {
        val t = " ${said.lowercase(Locale.ROOT).replace(Regex("""[?!.,]"""), " ")} "
        if (t.trim().split(Regex("""\s+""")).size > 4) return null
        val pays = Regex("""\s(?:naan|nan|neenga|naa|nanga|naanga|supplier|suppliers|kudukanum|kudukkanum|kudukka|pay|payable|i\s+owe|i\s+pay|நான்|சப்ளையர்\S*|கொடுக்க\S*)\s""")
        val gets = Regex("""\s(?:enakku|enaku|ennaku|yenaku|customer|customers|vara|varanum|vaanganum|receivable|owes\s+me|they|எனக்கு|கஸ்டமர்\S*|வர\S*)\s""")
        return when {
            gets.containsMatchIn(t) && !pays.containsMatchIn(t) -> "enakku yaaru tharanum"
            pays.containsMatchIn(t) && !gets.containsMatchIn(t) -> "naan yaarukku tharanum"
            else -> null
        }
    }

    /** The owner's last question that got a plain answer, and the language it was answered in. */
    private var lastPlainAnswer: Pair<String, KaiLang>? = null
    /** This turn's language; [forcedLang] when the owner asked for it ("puriyala" → Tamil). */
    private var turnLang: KaiLang = KaiLang.TANGLISH
    private var forcedLang: KaiLang? = null

    /** The owner's spellings of Kai's words, as the feed knows them ("totel ah" = "total ah"). */
    private fun spelled(text: String): String = Regex("""[\p{L}]+""").replace(text) { m ->
        com.shopai.app.brain.tools.KaiFeed.spellings[m.value.lowercase(Locale.ROOT)] ?: com.shopai.app.brain.tools.KaiFeed.verbSpelling(m.value) ?: m.value
    }

    // ------------------------------------------------------------ push-back and what Kai can't do

    private suspend fun care(said: String, lang: KaiLang): KaiTurn? {
        val people = runCatching { books.snapshot()?.people.orEmpty() }.getOrDefault(emptyList())
        val named = com.shopai.app.brain.KaiUnderstanding.knownPerson(said, people)
        // Right after an entry was saved: "adha cancel pannu", "1500 illa 1000, maathu", "nee thappa pottutta".
        // A reminder set since then is what "adha" means — not the entry.
        conversationState.lastSaved?.takeIf { conversationState.conversationTurn - it.turn <= 2 && lastReminderTurn < it.turn && (named == null || samePerson(named, it.person)) }?.let { saved ->
            val amount = saved.amount.stripTrailingZeros().toPlainString()
            when (KaiConversationCare.takeback(said)) {
                KaiConversationCare.Takeback.UNDO -> return takeBack(saved, null, lang)
                KaiConversationCare.Takeback.CHANGE -> if (Regex("""(?i)(maath|change|edit|thiruth|correct)""").containsMatchIn(said) || said.contains(amount))
                    return takeBack(saved, Regex("""\d[\d,]*""").findAll(said).map { it.value.replace(",", "") }.firstOrNull { it != amount }, lang)
                null -> Unit
            }
            if (KaiConversationCare.kaiGotItWrong(said)) return gotItWrong(saved, lang)
        }
        // "adha cancel pannu" a few turns after the save (a stock or small-talk question in between): still that entry,
        // when it is pointed at and no reminder was set since.
        conversationState.lastSaved?.takeIf { s -> conversationState.conversationTurn - s.turn in 3..12 && lastReminderTurn < s.turn &&
            KaiConversationCare.pointsAtEntry(said) && (named == null || samePerson(named, s.person)) }?.let { saved ->
            val amount = saved.amount.stripTrailingZeros().toPlainString()
            when (KaiConversationCare.takeback(said)) {
                KaiConversationCare.Takeback.UNDO -> return takeBack(saved, null, lang)
                KaiConversationCare.Takeback.CHANGE -> if (Regex("""(?i)(maath|change|edit|thiruth|correct)""").containsMatchIn(said))
                    return takeBack(saved, Regex("""\d[\d,]*""").findAll(said).map { it.value.replace(",", "") }.firstOrNull { it != amount }, lang)
                null -> Unit
            }
        }
        // "innoru 500 kuduthan" after Kumar's ₹2,000: Kumar again — a new draft, Confirm first.
        if (named == null && KaiConversationCare.anotherPayment(said)) {
            val who = conversationState.lastSaved?.takeIf { conversationState.conversationTurn - it.turn <= 12 }?.person ?: personOnTopic?.first ?: brain.lastPerson()
            if (who != null) return askAs(lang, "$who " + Regex("""(?i)(?<![\p{L}])(innoru|innum\s+oru|innonu|innum|marupadi|thirumba|again|another|also)(?![\p{L}])|இன்னொரு|இன்னும்|மறுபடி""").replace(said, " ").trim())
        }
        // "Kumar 500": paid, or owed? One short question with what the records say (principle 3) — never a guess.
        if (named != null) bareNameAmount.matchEntire(said.trim())?.takeIf { m ->
            // Only the name and the amount — "ராஜா kuduthaan 750" has a verb (one word per word of the name).
            val said1 = m.groupValues[1].trim()
            said1.split(Regex("""\s+""")).size == named.trim().split(Regex("""\s+""")).size && samePerson(said1, named) }?.let { m ->
            val amount = m.groupValues[2].replace(",", "").toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@let
            val p = runCatching { books.snapshot() }.getOrNull()?.find(named)?.distinctBy { it.id }?.singleOrNull() ?: return@let
            bareAmount = Triple(p.name, amount, conversationState.conversationTurn)
            val a = KaiFormat.rupees(amount.toDouble()); val owed = KaiFormat.rupees(p.pending)
            val supplier = p.side == com.shopai.app.brain.Direction.PAYABLE
            // What the records say first, then the one short question.
            return say(lang, KaiMood.CLARIFY, "bare name and amount", ta = if (supplier)
                    "Records-ல நீங்க ${p.name}-க்கு $owed கொடுக்கணும். ${p.name} $a — நீங்க $a கொடுத்தீங்களா, இல்ல புதுசா $a வாங்கினீங்களா (debit) ஓனர்?"
                else "Records-ல ${p.name} $owed தரணும். ${p.name} $a — ${p.name} $a கொடுத்தாரா, இல்ல $a தரணுமா (credit) ஓனர்?",
                tl = if (supplier) "Records-la neenga ${p.name}-ku $owed kudukkanum. ${p.name} $a — neenga $a kudutheengala, illa pudhusaa $a vaangineengala (debit) Owner?"
                else "Records-la ${p.name} $owed tharanum. ${p.name} $a — ${p.name} $a kuduthaara, illa $a tharanum-aa (credit) Owner?",
                en = if (supplier) "The records say you owe ${p.name} $owed. ${p.name} $a — did you pay $a, or buy $a more on credit, Owner?"
                else "The records say ${p.name} owes $owed. ${p.name} $a — did ${p.name} pay $a, or does ${p.name} owe $a more (credit), Owner?")
        }
        // "call pannu" right after Selvam's balance: call Selvam (owner, 12 Oct 2026: "idhu Selvam pathiyaa?").
        if (named == null && bareCall.matches(said.trim())) recentPerson()?.let { who -> return askAs(lang, "$who ku call pannu") }
        // "naalaiku 10 manikku remind pannu" after Selvam's balance / "order panna remind pannu" after Maggi's stock: about them.
        if (named == null) reminderAbout(said)?.let { return askAs(lang, it) }
        // "nethu?" right after "innaiku sales evlo": the same question for that day.
        periodFollowUp(said)?.let { return askAs(lang, it) }
        // "athukuda 100 add panniko" right after Madhan's ₹2,000 was saved: ₹100 more on Madhan — a new draft (Confirm first).
        if (named == null) onTopOf.find(said)?.let { m ->
            val who = conversationState.lastSaved?.takeIf { conversationState.conversationTurn - it.turn <= 12 }?.person
                ?: conversationState.stated?.person ?: personOnTopic?.first ?: brain.lastPerson()
            if (who != null) return askAs(lang, "$who-ku " + said.substring(m.range.last + 1).trim())
        }
        // "Ramesh um adhe amount kuduthan": the amount just said.
        (conversationState.lastSaved?.takeIf { conversationState.conversationTurn - it.turn <= 12 }?.amount ?: conversationState.lastAmount)?.let { last ->
            KaiConversationCare.withSameAmount(said, last.stripTrailingZeros().toPlainString())?.let { return askAs(lang, it) }
        }
        // "avan paadhi kuduthan": half of what that person owes — said, then drafted (Confirm first).
        if (KaiConversationCare.paysHalf(said)) {
            val who = named ?: personOnTopic?.first ?: brain.lastPerson()
            val party = who?.let { w -> runCatching { books.snapshot()?.parties?.filter { it.name.equals(w, ignoreCase = true) } }.getOrNull()?.singleOrNull() }
            if (who != null && party != null && party.pending > 0.5) {
                val halfAmount = BigDecimal.valueOf(party.pending / 2).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
                val whole = KaiFormat.rupees(party.pending); val h = KaiFormat.rupees(halfAmount.toDouble())
                val text = KaiConversationCare.withoutHalf(said, halfAmount.toPlainString()).let { if (named == null) "$who $it" else it }
                return withPrefix(pick(lang, ta = "$who $whole-ல பாதி = $h.", tl = "$who $whole-la paadhi = $h.", en = "Half of $who's $whole is $h."), askAs(lang, text))
            }
        }
        // "naan munnadi enna ketten": the last questions, as asked.
        if (KaiConversationCare.asksWhatWasAsked(said)) {
            val asked = recentAsks.toList().takeLast(3)
            return if (asked.isEmpty()) say(lang, KaiMood.NEUTRAL, null, ta = "ஓனர், இந்த பேச்சுல இன்னும் எதுவும் கேக்கல.", tl = "Owner, indha pechula innum edhuvum kekkala.",
                en = "Owner, you haven't asked anything yet in this chat.")
            else say(lang, KaiMood.EXPLAINING, "what was asked",
                ta = "ஓனர், நீங்க கடைசியா கேட்டது:\n" + asked.mapIndexed { i, q -> "${i + 1}. $q" }.joinToString("\n"),
                tl = "Owner, neenga kadaisiya kettadhu:\n" + asked.mapIndexed { i, q -> "${i + 1}. $q" }.joinToString("\n"),
                en = "Owner, you last asked:\n" + asked.mapIndexed { i, q -> "${i + 1}. $q" }.joinToString("\n"))
        }
        if (KaiConversationCare.kaiGotItWrong(said)) return gotItWrong(null, lang, recordFact(lang))
        if (KaiConversationCare.ownerDidNotFollow(said)) explainAgain(lang)?.let { return it }
        if (KaiConversationCare.disputesBalance(said)) disputed(said, named, lang)?.let { return it }
        // "Kumar balance 0 dhaan, adha nyabagam vechuko": a balance is never learned from chat (principles 4 and 7) — the records,
        // and how to put a real entry. Anything else to "keep in mind" (not a word's meaning): said plainly what Kai can keep.
        // A task to do ("Kumar-ku call pannanum nu nyabagam vechuko") is a reminder — that goes on as before.
        if (keepInMind.containsMatchIn(said) && !Regex("""(?i)\p{L}+(?:anum|aanum|ணும்)(?![\p{L}])|(?<![\p{L}])(?:remind\p{L}*|call)(?![\p{L}])""").containsMatchIn(said)) {
            val fact = keepInMind.replace(said, " ").replace(Regex("""(?i)[\s,]*(?:adha|atha|idha|itha|adhai|idhai|nu|-nu)\s*$"""), "").trim().trimEnd(',', '.')
            if (named != null && fact.any(Char::isDigit)) disputed(fact, named, lang)?.let { return it }
            if (named == null || !owedTalk.containsMatchIn(fact)) return say(lang, KaiMood.NEUTRAL, "keep in mind: not a word",
                ta = "ஓனர், இதை நினைவில் வைக்க எனக்கு வசதி இல்ல. ஒரு வார்த்தையோட அர்த்தம் (உதா: “petti னா box”), ஒருத்தரோட செல்லப் பேர் மட்டும் சேமிப்பேன்; பணம் / stock எல்லாம் records-ல இருந்து தான். நினைவூட்டணும்னா “நாளைக்கு 10 மணிக்கு remind பண்ணு”-னு சொல்லுங்க.",
                tl = "Owner, idha nyabagam vechukka enakku vasadhi illa. Oru vaarthai artham (eg: “petti na box”), oruthar nickname mattum save pannuven; panam / stock ellam records-la irundhu dhaan. Remind pannanumna “naalaiku 10 manikku remind pannu”-nu sollunga.",
                en = "Owner, I can't keep that in memory. I save a word's meaning (e.g. “petti means box”) or someone's nickname; money and stock always come from the records. For a reminder, say “remind me tomorrow at 10”.")
            return say(lang, KaiMood.NEUTRAL, "keep in mind: a balance", ta = "ஓனர், பாக்கியை chat-ல சொன்னதை வெச்சு நான் மாத்தவோ நினைவில் வைக்கவோ மாட்டேன் — அது records-ல இருந்து தான் வரும். பணம் வந்திருந்தா / கொடுத்திருந்தா அதை entry-ஆ சொல்லுங்க, draft பண்றேன்.",
                tl = "Owner, balance-a chat-la sonnadha vechu naan maathavo nyabagam vechukkavo maatten — adhu records-la irundhu dhaan varum. Panam vandhirundhaa / kuduthirundhaa adha entry-aa sollunga, draft panren.",
                en = "Owner, I don't change or memorise a balance from a chat message — it always comes from the records. If money came in or went out, tell me the entry and I'll draft it.")
        }
        if (KaiConversationCare.makesBill(said)) return noBillHere(said, named, lang)
        // "Kumar statement WhatsApp-la anuppu" is the statement PDF the owner sends (statementAsk), not a message Kai can't send.
        // "Idha WhatsApp-la anuppu" with nobody talked about reaches here: what Kai can't send, said as before.
        if (KaiConversationCare.sendsMessage(said) && (!KaiStatement.asks(said) || KaiStatement.onlyByReference(said))) {
            val note = pick(lang, ta = "ஓனர், WhatsApp / SMS-ல நான் அனுப்ப முடியாது.", tl = "Owner, WhatsApp / SMS-la naan anuppa mudiyaadhu.",
                en = "Owner, I can't send WhatsApp / SMS messages.")
            val rest = KaiConversationCare.withoutMessageApp(said)
            return if (named != null || com.shopai.app.brain.tools.KaiReminderUnderstanding.mentionsReminder(rest)) withPrefix(
                note + pick(lang, ta = " உங்களுக்கு reminder வைக்கலாம்:", tl = " Ungalukku reminder vekkalaam:", en = " I can set a reminder for you:"), ask(rest))
            else say(lang, KaiMood.NEUTRAL, "beyond: message app", ta = "$note உங்களுக்கு call / message பண்ண reminder வேணும்னா சொல்லுங்க.",
                tl = "$note Ungalukku call / message panna reminder venumna sollunga.", en = "$note If you want, I can remind you to call or message them.")
        }
        if (named == null) KaiConversationCare.beyondBooks(said)?.let { return beyond(it, lang) }
        // "4773.13 yar tharanum": who has that balance — the exact one, or the nearest said as nearest; never a guess.
        if (named == null) KaiConversationCare.amountWho(said)?.let { amount -> whoHasAmount(amount, lang)?.let { return it } }
        // "2500 ku gst evlo": the rate isn't said — each common rate worked out, never one guessed.
        KaiConversationCare.gstWithoutRate(said)?.let { amount ->
            val rows = listOf(5, 12, 18, 28).joinToString(" / ") { r -> "$r% = ${KaiFormat.rupees(amount * r / 100)}" }
            val a = KaiFormat.rupees(amount)
            return say(lang, KaiMood.EXPLAINING, "gst without a rate", ta = "ஓனர், $a-க்கு GST எத்தனை % -னு சொல்லலை. $rows. Rate சொன்னா total-உம் சொல்றேன் (உதா: “$a la 18% GST”).",
                tl = "Owner, $a-ku GST evlo %-nu sollala. $rows. Rate sonna total-um solren (eg: “${amount.toLong()} la 18% GST”).",
                en = "Owner, the GST rate wasn't said. On $a: $rows. Tell me the rate for the total (e.g. “18% GST on ${amount.toLong()}”).")
        }
        // "date sollu" right after talking about Kumar: Kumar's due date (below), not today's date (owner's test, 11 Oct 2026).
        val aboutSomeone = conversationState.lastPerson != null && conversationState.conversationTurn - conversationState.mentionedTurn <= 3 &&
            conversationState.mentionedPeople.size < 2 && bareDueQuestion.matches(said.trim())
        if (named == null && !aboutSomeone) KaiConversationCare.dateAsked(said, now().toLocalDate())?.let { d ->
            val ta = KaiConversationCare.dateText(d, tamil = true)
            val en = KaiConversationCare.dateText(d, tamil = false)
            return say(lang, KaiMood.EXPLAINING, "date asked", ta = "ஓனர், அது $ta.", tl = "Owner, adhu $en.", en = "Owner, that's $en.")
        }
        // "ippo evlo irukku" after talking about Colgate: Colgate's stock again.
        if (KaiConversationCare.asksSameProductAgain(said)) lastProduct?.let { p -> return stock(p.name, lang) }
        return null
    }

    private suspend fun whoHasAmount(amount: Double, lang: KaiLang): KaiTurn? {
        val parties = runCatching { books.snapshot()?.parties }.getOrNull()?.filter { it.pending > 0.004 } ?: return null
        val a = KaiFormat.rupees(amount)
        fun line(p: com.shopai.app.brain.PartyFacts, ta: Boolean) = if (p.side == com.shopai.app.brain.Direction.PAYABLE)
            (if (ta) "${p.name}-க்கு ${KaiFormat.rupees(p.pending)} குடுக்கணும்" else "${p.name}-ku ${KaiFormat.rupees(p.pending)} kudukkanum")
            else (if (ta) "${p.name} ${KaiFormat.rupees(p.pending)} தரணும்" else "${p.name} ${KaiFormat.rupees(p.pending)} tharanum")
        val exact = parties.filter { kotlin.math.abs(it.pending - amount) < 0.005 }
        tools.log("amount lookup", "ledger", "$a: ${exact.size} exact", ActionStatus.ANSWERED)
        if (exact.isNotEmpty()) return say(lang, KaiMood.EXPLAINING, "who has an amount",
            ta = "ஓனர், $a: " + exact.joinToString(", ") { line(it, true) } + ".",
            tl = "Owner, $a: " + exact.joinToString(", ") { line(it, false) } + ".",
            en = "Owner, $a: " + exact.joinToString(", ") { line(it, false) } + ".")
        val near = parties.filter { kotlin.math.abs(it.pending - amount) <= maxOf(10.0, amount * 0.01) }.sortedBy { kotlin.math.abs(it.pending - amount) }.take(3)
        if (near.isNotEmpty()) return say(lang, KaiMood.EXPLAINING, "who has an amount: nearest",
            ta = "ஓனர், சரியா $a யாருக்கும் இல்ல. பக்கத்துல: " + near.joinToString(", ") { line(it, true) } + ".",
            tl = "Owner, correct-aa $a yaarukkum illa. Pakkathula: " + near.joinToString(", ") { line(it, false) } + ".",
            en = "Owner, no one has exactly $a. Closest: " + near.joinToString(", ") { line(it, false) } + ".")
        return say(lang, KaiMood.NEUTRAL, "who has an amount: none",
            ta = "ஓனர், $a பாக்கி யாருக்கும் இல்ல. எல்லாரோட list-க்கு “யார் யார் தரணும்”-னு கேளுங்க.",
            tl = "Owner, $a baaki yaarukkum illa. Ellaaroda list-ku “yaar yaar tharanum”-nu kelunga.",
            en = "Owner, no one has a balance of $a. Ask “who owes me” for the full list.")
    }

    /** The owner's words with a name / amount filled in, answered in the language the owner used (spoken Tamil gets Tamil). */
    private val modeWord = Regex("""(?i)(?<![\p{L}])(?:upi|gpay|g\s*pay|phonepe|phone\s*pe|paytm|bank|neft|imps|card|cash|online)(?![\p{L}])|ஜிபே|ரொக்கம்|கேஷ்""")
    private val bareCall = Regex("""(?i)^(?:avanukku|avarukku|avangalukku|avalukku|avan\s*ku|ivanukku)?\s*(?:call|phone|kal|ring)\s*(?:pannu|pannunga|panni\s*kudu|podu|pottu\s*kudu|pannanum|pannu\s*da|panniru|pannidu)?\s*[.!?]*$""")
    private val remindWord = Regex("""(?i)(?<![\p{L}])(?:remind\p{L}*|reminder|nyabagam\s*paduthu|ninaivu\s*paduthu|alarm)(?![\p{L}])""")
    private val timeOrFiller = Regex(
        """(?i)(?<![\p{L}\p{N}])(?:naalaiku|naalaikku|nalaiku|naalai|innaiku|innaikku|indru|kaalaila|kaalai|morning|madhiyam|afternoon|saayangalam|evening|""" +
            """raathiri|night|monday|tuesday|wednesday|thursday|friday|saturday|sunday|adutha|next|week|vaaram|maasam|month|\d+(?::\d+)?|manikku|mani|am|pm|""" +
            """minutes?|la|ku|kku|enakku|oru|pannu|pannunga|panna|pannanum|panniko|vai|vei|vechu|podu|set|seri|please|sollu)(?![\p{L}])""",
    )

    /**
     * A reminder asked with nothing to remind: "naalaiku 10 manikku remind pannu" right after Selvam's balance is about Selvam's
     * payment; "call panna remind pannu" after Kumar's entry is a call to Kumar; "order panna remind pannu" after Maggi's stock is
     * Maggi's order. The line said again with them in it; null when the owner said what to remind (or nothing is on the topic).
     */
    private suspend fun reminderAbout(said: String): String? {
        if (!remindWord.containsMatchIn(said) || said.contains('?')) return null
        val rest = timeOrFiller.replace(remindWord.replace(said.lowercase(Locale.ROOT), " "), " ").replace(Regex("""\s+"""), " ").trim()
        val product = recentProduct()
        val what = when {
            product != null && (rest.isEmpty() || Regex("""^(?:order|vaanga|vaanganum|purchase|stock|re-?order)(?:\s+.*)?$""").matches(rest)) -> "$product order panna"
            rest.isEmpty() -> recentPerson()?.let { who ->
                val supplier = runCatching { books.snapshot() }.getOrNull()?.find(who)?.singleOrNull()?.side == com.shopai.app.brain.Direction.PAYABLE
                if (supplier) "$who-ku payment kudukka" else "$who kitta payment vaanga"
            }
            Regex("""^(?:call|phone|kal)$""").matches(rest) -> recentPerson()?.let { "$it-ku call panna" }
            else -> null
        } ?: return null
        val m = remindWord.find(said) ?: return null
        return said.substring(0, m.range.first).replace(Regex("""(?i)(?<![\p{L}])(?:call|phone|order)\s+panna\s*$"""), "").trimEnd() + " $what " + said.substring(m.range.first)
    }

    private val periodWords = Regex("""(?i)(?<![\p{L}])(?:innaiku|innaikku|today|indru|nethu|nethiku|neththu|nethaiku|yesterday|pona\s+vaaram|last\s+week|indha\s+vaaram|this\s+week|""" +
        """pona\s+maasam|last\s+month|indha\s+maasam|this\s+month|indha\s+month)(?![\p{L}])|இன்னைக்கு|இன்று|நேத்து|நேற்று|(?:இந்த|போன)\s*(?:வாரம்|மாசம்|மாதம்)""")

    /** "nethu?" / "pona vaaram?" right after "innaiku sales evlo": that question again for the new day — null when it isn't one. */
    private fun periodFollowUp(said: String): String? {
        val t = said.trim().trimEnd('?', '!', '.', ' ')
        val newPeriod = periodWords.find(t)?.takeIf { it.value.length >= t.replace(Regex("""(?i)^(?:appo|apo|and|um|sari)\s+"""), "").replace(Regex("""(?i)\s*(?:um|evlo|enna)$"""), "").length - 1 }
            ?: return null
        val last = recentAsks.toList().dropLast(1).lastOrNull() ?: return null
        val old = periodWords.find(last) ?: return null
        if (old.value.equals(newPeriod.value, ignoreCase = true)) return null
        return last.replaceRange(old.range, newPeriod.value)
    }

    /** "athukuda / adhoda / adhu kooda / extra / mela 100 add panniko": more on the entry just made. */
    private val onTopOf = Regex("""(?i)^\s*(?:athukuda|athukooda|adhukuda|adhukooda|athu\s+kooda|adhu\s+kooda|athoda|adhoda|athodu|adhodu|extra|mela|melum)\s+(?=\d)""")

    private suspend fun askAs(lang: KaiLang, text: String): KaiTurn {
        val before = forcedLang
        forcedLang = lang
        return try { ask(text) } finally { forcedLang = before }
    }

    /** "TXN-1 — Kumar ₹2,000 save aagi irukku": saved entries are never deleted from chat — the owner cancels it on its page. */
    private fun takeBack(saved: KaiSavedPayment, newAmount: String?, lang: KaiLang): KaiTurn {
        val a = KaiFormat.rupees(saved.amount.toDouble())
        val p = saved.person
        val again = newAmount?.let { n ->
            val nr = KaiFormat.rupees(n.toDouble())
            when (saved.kind) {
                PlanKind.PAYMENT_OUT -> Triple("“$p-க்கு $n குடுத்தேன்”", "“$p-ku $n kuduthen”", "“paid $p $n”")
                PlanKind.CREDIT_GIVEN -> Triple("“$p $n கடன்”", "“$p $n credit”", "“$p $n credit”")
                PlanKind.DEBIT_TAKEN -> Triple("“$p-க்கு $n குடுக்கணும்”", "“$p-ku $n kudukkanum”", "“I owe $p $n”")
                else -> Triple("“$p $n குடுத்தான்”", "“$p $n kuduthan”", "“$p paid $n”")
            }.let { (ta, tl, en) -> Triple(" அப்புறம் $ta-னு சொல்லுங்க — $nr-க்கு புது entry draft பண்றேன்.",
                " Appuram $tl-nu sollunga — $nr-ku pudhu entry draft panren.", " Then say $en and I'll draft the $nr entry.") }
        }
        tools.log("take back ${saved.reference}", "kai", "pointed to the entry's page (not deleted from chat)", ActionStatus.ANSWERED, saved.reference)
        val turn = say(lang, KaiMood.EXPLAINING, null,
            ta = "ஓனர், ${saved.reference} — $p $a books-ல சேமிச்சாச்சு. சேமிச்ச entry-ஐ நான் chat-ல இருந்து அழிக்க மாட்டேன் (கணக்கு பத்திரமா இருக்கணும்). " +
                "$p page-ல அந்த entry-ஐ திறந்து “Cancel” பண்ணுங்க." + (again?.first ?: ""),
            tl = "Owner, ${saved.reference} — $p $a books-la save aagi irukku. Save aana entry-ai naan chat-la irundhu azhikka maatten (kanakku safe-aa irukkanum). " +
                "$p page-la andha entry-ai thirandhu “Cancel” pannunga." + (again?.second ?: ""),
            en = "Owner, ${saved.reference} — $p $a is saved in the books. I don't delete saved entries from chat (the books must stay safe). " +
                "Open that entry on $p's page and tap “Cancel”." + (again?.third ?: ""))
        return saved.partyId?.let { id ->
            val kind = if (saved.kind == PlanKind.PAYMENT_OUT || saved.kind == PlanKind.DEBIT_TAKEN) "SUPPLIER" else "CUSTOMER"
            turn.copy(card = KaiCard(emptyList(), listOf(KaiButton(pick(lang, ta = "$p பார்", tl = "View $p", en = "View $p"), KaiAction.OpenRecord(kind, id), primary = true))))
        } ?: turn
    }

    /** "nee sonnadhu thappu": sorry, and what to say to put it right — nothing changes by itself. */
    /**
     * What the records say about the person just answered about — given with the apology for "nee sonnadhu thappu", so the
     * owner sees the verified figure (principle 6: apologize, correct, explain the verified result). Null: nobody on topic.
     */
    private suspend fun recordFact(lang: KaiLang): String? {
        val who = personOnTopic?.takeIf { conversationState.conversationTurn - it.second <= 2 }?.first ?: return null
        val p = runCatching { books.snapshot() }.getOrNull()?.find(who)?.distinctBy { it.id }?.singleOrNull() ?: return null
        val a = KaiFormat.rupees(p.pending)
        val supplier = p.side == com.shopai.app.brain.Direction.PAYABLE
        return pick(lang,
            ta = if (supplier) " Records-ல ${p.name}-க்கு நீங்க $a கொடுக்கணும்-னு தான் இருக்கு." else " Records-ல ${p.name} $a தரணும்-னு தான் இருக்கு.",
            tl = if (supplier) " Records-la ${p.name}-ku neenga $a kudukkanum-nu dhaan irukku." else " Records-la ${p.name} $a tharanum-nu dhaan irukku.",
            en = if (supplier) " The records say you owe ${p.name} $a." else " The records say ${p.name} owes $a.")
    }

    private fun gotItWrong(saved: KaiSavedPayment?, lang: KaiLang, fact: String? = null): KaiTurn {
        val last = saved?.let { s ->
            val a = KaiFormat.rupees(s.amount.toDouble())
            Triple(" கடைசியா சேமிச்சது: ${s.reference} — ${s.person} $a. அது தப்புன்னா “$a இல்ல, மாத்து”-னு சொல்லுங்க.",
                " Kadaisiyaa save aanadhu: ${s.reference} — ${s.person} $a. Adhu thappunaa “$a illa, maathu”-nu sollunga.",
                " The last entry saved: ${s.reference} — ${s.person} $a. If that's wrong, say “not $a, change it”.")
        }
        return say(lang, KaiMood.CLARIFY, "owner: Kai got it wrong",
            ta = "மன்னிச்சுக்கோங்க ஓனர் 🙏" + (fact ?: "") + " எது தப்பு-னு சொல்லுங்க — பேரா, தொகையா, இல்ல வேற விஷயமா? உதா: “Kumar இல்ல, Ramesh” / “2000 இல்ல 1500”." + (last?.first ?: ""),
            tl = "Mannichikonga Owner 🙏" + (fact ?: "") + " Edhu thappu-nu sollunga — peru-aa, amount-aa, illa vera vishayam-aa? Eg: “Kumar illa, Ramesh” / “2000 illa 1500”." + (last?.second ?: ""),
            en = "Sorry, Owner 🙏" + (fact ?: "") + " Tell me what's wrong — the name, the amount, or something else? E.g. “not Kumar, Ramesh” / “not 2000, 1500”." + (last?.third ?: ""))
    }

    /** "puriyala" after a plain answer: the same answer again, in Tamil (said again when it already was — the mic's case). */
    private suspend fun explainAgain(lang: KaiLang): KaiTurn? {
        val (question, answeredIn) = lastPlainAnswer ?: return say(lang, KaiMood.CLARIFY, "owner: not clear",
            ta = "மன்னிச்சுக்கோங்க ஓனர் 🙏 எதை மறுபடி சொல்லணும்? பேர், தொகை, தேதி — எது புரியல?",
            tl = "Mannichikonga Owner 🙏 Edhai marubadi sollanum? Peru, amount, date — edhu puriyala?",
            en = "Sorry, Owner 🙏 What should I say again — the name, the amount, or the date?")
        forcedLang = KaiLang.TAMIL
        val again = try { answerOwner(question) } finally { forcedLang = null }
        if (answeredIn != KaiLang.TAMIL) return withPrefix("சரி ஓனர், தமிழ்ல சொல்றேன்:", again)
        return withPrefix("சரி ஓனர், மறுபடி சொல்றேன்:", again).let { t ->
            t.copy(reply = t.reply.copy(text = t.reply.text + "\n\nஎந்த பகுதி புரியல-னு சொன்னா (பேரா, தொகையா, தேதியா) அதை மட்டும் சுலபமா சொல்றேன்.")) }
    }

    /** "illa avan 3000 dhaan tharanum": the records say otherwise — said plainly, never changed by itself. */
    private suspend fun disputed(said: String, named: String?, lang: KaiLang): KaiTurn? {
        val who = named ?: brain.lastPerson() ?: return null
        val party = books.snapshot()?.parties?.filter { it.name.equals(who, ignoreCase = true) }?.singleOrNull() ?: return null
        val claimed = Regex("""\d[\d,]*""").find(said)?.value?.replace(",", "")?.toDoubleOrNull() ?: return null
        val pay = party.side == com.shopai.app.brain.Direction.PAYABLE
        val recorded = KaiFormat.rupees(party.pending)
        val c = KaiFormat.rupees(claimed)
        val diff = kotlin.math.abs(party.pending - claimed)
        if (diff < 0.5) return say(lang, KaiMood.HAPPY, null, ta = "ஆமா ஓனர், records-லயும் $who $recorded தான்.", tl = "Aama Owner, records-layum $who $recorded dhaan.",
            en = "Yes, Owner — the records also say $recorded for $who.")
        val d = KaiFormat.rupees(diff)
        val n = diff.toLong()
        val (ta, tl, en) = when {
            claimed < party.pending && !pay -> Triple("$who $d குடுத்திருந்தா “$who $n குடுத்தான்”-னு சொல்லுங்க", "$who $d kuduthirundhaa “$who $n kuduthan”-nu sollunga",
                "if $who paid $d, say “$who paid $n”")
            claimed < party.pending -> Triple("நீங்க $who-க்கு $d குடுத்திருந்தா “$who-க்கு $n குடுத்தேன்”-னு சொல்லுங்க", "neenga $who-ku $d kuduthirundhaa “$who-ku $n kuduthen”-nu sollunga",
                "if you paid $who $d, say “paid $who $n”")
            !pay -> Triple("$d இன்னும் கடன் குடுத்திருந்தா “$who $n கடன்”-னு சொல்லுங்க", "$d innum credit kuduthirundhaa “$who $n credit”-nu sollunga",
                "if you gave $d more credit, say “$who $n credit”")
            else -> Triple("இன்னும் $d வாங்கியிருந்தா “$who-க்கு $n குடுக்கணும்”-னு சொல்லுங்க", "innum $d vaangirundhaa “$who-ku $n kudukkanum”-nu sollunga",
                "if you owe $who $d more, say “I owe $who $n”")
        }
        val turn = say(lang, KaiMood.EXPLAINING, "owner disputes a balance",
            ta = "ஓனர், records-ல $who $recorded ${if (pay) "கொடுக்கணும்" else "தரணும்"}-னு இருக்கு; நீங்க $c-னு சொல்றீங்க — $d வித்தியாசம். $ta, entry draft பண்றேன். Records-ஐ நான் தானா மாத்த மாட்டேன்.",
            tl = "Owner, records-la $who $recorded ${if (pay) "kudukkanum" else "tharanum"}-nu irukku; neenga $c-nu solreenga — $d vithiyaasam. $tl, entry draft panren. Records-ai naan thaana maatha maatten.",
            en = "Owner, the records say $recorded ${if (pay) "to pay" else "to collect"} for $who; you say $c — a $d difference. $en and I'll draft the entry. I never change the records by myself.")
        return turn.copy(card = KaiCard(emptyList(), listOf(KaiButton(pick(lang, ta = "$who பார்", tl = "View $who", en = "View $who"),
            KaiAction.OpenRecord(if (pay) "SUPPLIER" else "CUSTOMER", party.id), primary = true))))
    }

    /** "GST bill podu": Kai chat doesn't make bills — the Billing screen does; a credit entry Kai can draft. */
    private suspend fun noBillHere(said: String, named: String?, lang: KaiLang): KaiTurn {
        val partyId = named?.let { n -> books.snapshot()?.parties?.filter { it.name.equals(n, ignoreCase = true) }?.singleOrNull()?.id }
        val amount = Regex("""\d[\d,]*""").find(said)?.value?.replace(",", "")
        val credit = if (named != null && amount != null) Triple(" இல்ல, “$named $amount கடன்”-னு சொன்னா credit entry-ஆ போடறேன்.",
            " Illa, “$named $amount credit”-nu sonna credit entry-aa podren.", " Or say “$named $amount credit” and I'll draft a credit entry.") else null
        val turn = say(lang, KaiMood.EXPLAINING, "beyond: make a bill",
            ta = "ஓனர், Kai chat-ல GST bill / invoice போடற வசதி இன்னும் இல்ல. Billing screen-ல போடலாம் — கீழ இருக்கற button-ஐ தொடுங்க." + (credit?.first ?: ""),
            tl = "Owner, Kai chat-la GST bill / invoice podra vasadhi innum illa. Billing screen-la podalaam — keezha irukkura button-ai thottunga." + (credit?.second ?: ""),
            en = "Owner, Kai chat can't make GST bills / invoices yet. The Billing screen can — tap the button below." + (credit?.third ?: ""))
        return turn.copy(card = KaiCard(emptyList(), listOf(KaiButton(pick(lang, ta = "🧾 Bill போடுங்க", tl = "🧾 Bill podunga", en = "🧾 Make a bill"),
            KaiAction.OpenRecord("SALE_BILL", partyId.orEmpty()), primary = true))))
    }

    /** Weather, cricket, news, jokes, online orders, loans, staff salary: said plainly — not something Kai does. */
    private fun beyond(topic: KaiConversationCare.Beyond, lang: KaiLang): KaiTurn {
        val can = Triple(" நான் உங்க கடை கணக்கு பார்க்கிறேன்: payment, credit, stock, reminder. உதா: “Kumar எவ்வளவு தரணும்?”",
            " Naan unga kadai kanakku paakuren: payment, credit, stock, reminder. Eg: “Kumar evlo tharanum?”",
            " I look after your shop's accounts: payments, credit, stock, reminders. E.g. “How much does Kumar owe?”")
        val (ta, tl, en) = when (topic) {
            KaiConversationCare.Beyond.WEATHER -> Triple("ஓனர், வானிலை பத்தி எனக்கு தெரியாது.", "Owner, weather / mazhai pathi enakku theriyaadhu.", "Owner, I can't tell the weather.")
            KaiConversationCare.Beyond.SPORTS -> Triple("ஓனர், cricket / score பத்தி எனக்கு தெரியாது.", "Owner, cricket / score pathi enakku theriyaadhu.", "Owner, I don't follow cricket or scores.")
            KaiConversationCare.Beyond.NEWS -> Triple("ஓனர், news / அரசியல் பத்தி எனக்கு தெரியாது.", "Owner, news / politics pathi enakku theriyaadhu.", "Owner, I don't know news or politics.")
            KaiConversationCare.Beyond.FUN -> Triple("ஓனர், joke / கதை சொல்ல எனக்கு வராது 😄", "Owner, joke / kadhai solla enakku varaadhu 😄", "Owner, jokes and stories aren't my thing 😄")
            KaiConversationCare.Beyond.ONLINE_ORDER -> Triple("ஓனர், Amazon / online-ல order பண்ண என்னால முடியாது. சரக்கு வந்ததும் “Rice 10 kg வந்துச்சு”-னு சொல்லுங்க, stock-ல சேர்க்கறேன்.",
                "Owner, Amazon / online-la order panna ennaala mudiyaadhu. Maal vandhadhum “Rice 10 kg vandhuchu”-nu sollunga, stock-la serkkuren.",
                "Owner, I can't place Amazon / online orders. When the goods come, say “Rice 10 kg came” and I'll add them to stock.")
            KaiConversationCare.Beyond.LOAN -> Triple("ஓனர், loan apply பண்ற வசதி Kai-ல இல்ல.", "Owner, loan apply panra vasadhi Kai-la illa.", "Owner, Kai can't apply for loans.")
            KaiConversationCare.Beyond.MARKET_PRICE -> Triple("ஓனர், gold / petrol / dollar மாதிரி வெளி விலை எனக்கு தெரியாது.", "Owner, gold / petrol / dollar maadhiri veliya irukka vilai enakku theriyaadhu.",
                "Owner, I don't know outside prices like gold, petrol or the dollar.")
            KaiConversationCare.Beyond.TRAVEL -> Triple("ஓனர், train / bus / ticket பத்தி எனக்கு தெரியாது.", "Owner, train / bus / ticket pathi enakku theriyaadhu.", "Owner, I don't know train, bus or ticket details.")
            KaiConversationCare.Beyond.ASTROLOGY -> Triple("ஓனர், ராசி பலன் / நல்ல நேரம் பத்தி எனக்கு தெரியாது.", "Owner, rasi palan / nalla neram pathi enakku theriyaadhu.", "Owner, I don't know horoscopes or auspicious times.")
            KaiConversationCare.Beyond.RECIPE -> Triple("ஓனர், சமையல் recipe எனக்கு தெரியாது 😄", "Owner, samayal recipe enakku theriyaadhu 😄", "Owner, I don't know recipes 😄")
            KaiConversationCare.Beyond.STAFF -> Triple("ஓனர், staff salary / attendance கணக்கு Kai-ல இன்னும் இல்ல.", "Owner, staff salary / attendance kanakku Kai-la innum illa.",
                "Owner, staff salary / attendance isn't in Kai yet.")
        }
        val tail = topic != KaiConversationCare.Beyond.ONLINE_ORDER
        return say(lang, KaiMood.NEUTRAL, "beyond: ${topic.name.lowercase()}", ta = ta + (if (tail) can.first else ""), tl = tl + (if (tail) can.second else ""),
            en = en + (if (tail) can.third else ""))
    }

    /** What [partyId] owes / is owed in the books now (null: unknown). */
    private suspend fun pendingOf(partyId: String?): BigDecimal? = partyId?.let { id ->
        runCatching { books.snapshot()?.parties?.firstOrNull { it.id == id }?.pending }.getOrNull()?.let { BigDecimal.valueOf(it).setScale(2, java.math.RoundingMode.HALF_UP) }
    }

    /** The advance the last save left (person, amount) — said again when their balance is asked in this conversation. */
    private var advanceTold: Pair<String, String>? = null

    /** The person this conversation is on, and the turn they came up ("Akash evlo" → "avan 6000 kuduthutan" → "ippa evlo tharanum"). */
    private var personOnTopic: Pair<String, Long>? = null
    /**
     * "meedhi eppo tharuvaan", "eppo tharuvaanga", "baaki eppa kudupaan": when the person talked about pays — asked with no name.
     * "avan eppa tharuvaan" is not taken here: the pronoun has its own reading (two people just named → Kai asks which).
     */
    private val asksWhenAgain = Regex("""(?i)^\s*(?:meedhi|meethi|meedi|baaki|bakki|balance|pending|remaining)?\s*(?:eppo|eppa|yeppa|yeppo|epo|when)\s+""" +
        """(?:tharuv\p{L}*|tharuvaan\p{L}*|tharanum|kudupp\p{L}*|kudupaan\p{L}*|kudukkanum|pay\s+pannuv\p{L}*|varum|kattuv\p{L}*)\s*[?!.]*\s*$""")

    private val loneBalance = Regex("""(?i)(?:balance|baaki|bakki|baki|meedhi|meethi|pending|பாக்கி|மீதி|பேலன்ஸ்)\s*[?!.]*""")

    /** The people and products of this shop, by name (for reading spoken Tamil). */
    private suspend fun knownNames(): List<String> =
        runCatching { books.snapshot()?.people.orEmpty() }.getOrDefault(emptyList()) +
            runCatching { tools.products()?.map { it.name } }.getOrNull().orEmpty()

    private suspend fun answerOwner(raw: String): KaiTurn {
        // Tamil script (the phone's ta-IN speech-to-text, or typed): its words in the Tanglish Kai's rules read, the shop's own
        // names in their letters ("குமார் 2000 குடுத்தான்" = "Kumar 2000 kuduthaan") — one brain for voice and text. Kai still
        // answers in Tamil.
        val tamil = com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(raw)
        // A product named in Tamil letters ("பிரிட்டானியா பிஸ்கட்", "டூர் தால்") is the shop's product it sounds like, as a whole
        // phrase — any product name, no list per name (KaiProductSound).
        // "Vengayathula evlo labam", "வெங்காயத்துல": the product with "-la" on it — Vengayam la (any product, by its own name).
        val heard = if (!tamil) productCase(raw.trim(), runCatching { tools.products()?.map { it.name } }.getOrNull().orEmpty()) else {
            val people = runCatching { books.snapshot()?.people.orEmpty() }.getOrDefault(emptyList())
            val productNames = runCatching { tools.products()?.map { it.name } }.getOrNull().orEmpty()
            com.shopai.app.brain.tools.KaiSpokenWords.withNames(com.shopai.app.brain.tools.KaiSpokenWords.normalize(
                com.shopai.app.brain.tools.KaiProductSound.withProducts(tamilProductCase(productCase(raw.trim(), productNames), productNames, people), productNames, people)), people + productNames)
        }
        // "first ketta aal evlo tharanum", "munnadi ketta aal": the person this conversation was about, by name.
        // "mudhal aal ku call pannu": the person at that place in the list just shown.
        val listed = (brain.withListPerson(heard) ?: heard).let(::withLastProduct)
        val earlier = KaiConversationCare.namedFromEarlier(listed, brain.peopleTalkedAbout()) ?: listed
        // "ippa evlo tharanum" right after talking about Akash: Akash's balance now — not everyone's list.
        val people0 = runCatching { books.snapshot()?.people.orEmpty() }.getOrDefault(emptyList())
        val onTopic = personOnTopic?.takeIf { conversationState.conversationTurn - it.second <= 3 }?.first
            ?: conversationState.lastSaved?.takeIf { conversationState.conversationTurn - it.turn <= 3 }?.person
        // "meedhi eppo tharuvaan" right after Kumar's entry: Kumar's due date — never a person called "Meedhi".
        val read = if (onTopic != null && (KaiConversationCare.asksBalanceAgain(earlier) || asksWhenAgain.containsMatchIn(earlier) ||
                KaiChatUnderstanding.bareOwe(earlier)) &&
            com.shopai.app.brain.KaiUnderstanding.knownPerson(earlier, people0) == null) "$onTopic $earlier"
            // "Kumar" → "balance": Kumar's balance — not "is this about Kumar?" again.
            else if (onTopic != null && loneBalance.matches(earlier.trim())) "$onTopic evlo baaki" else earlier
        com.shopai.app.brain.KaiUnderstanding.knownPerson(read, people0)?.let { personOnTopic = it to conversationState.conversationTurn + 1 }
        // "Power bank" is a product, not a bank: product names that hold a money word are joined first. "totel" = total (KaiFeed).
        // "Kumar innum 500 rooba tharala", "Kumarku 500 baaki iruku": an entry said in other words, read as Kai reads entries (KaiLedgerPhrases).
        val phrased = com.shopai.app.brain.tools.KaiLedgerPhrases.canonical(com.shopai.app.brain.tools.KaiInventory.joinProductWords(spelled(read)))
        // "naan" / "enakku" / "suppliers" / "customers" right after Kai asked whose money it is: the full question, that side.
        val said = (if (brain.sideAsked) { brain.sideAsked = false; sideAnswered(phrased) } else null) ?: phrased
        ownerWords = said
        val lang = forcedLang ?: if (tamil) KaiLang.TAMIL else KaiLanguage.forChat(said)
        turnLang = lang
        brain.spokenLang = forcedLang ?: if (tamil) KaiLang.TAMIL else null
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
                // A stated payment's draft keeps its person, direction and due date; only the amount changes.
                if (openPayment.key == conversationState.statedDraftKey) return redraftStated(openPayment, correctedAmount, openPayment.dueDate, conversationState.pendingLang ?: lang)
                    .also { conversationState.pendingCorrection = false }
                val outgoing = openPayment.kind == PlanKind.PAYMENT_OUT || openPayment.kind == PlanKind.CREDIT_GIVEN
                return revise(openPayment.key, openPayment.partyName, correctedAmount, openPayment.mode, outgoing, lang)
                    .also { conversationState.pendingCorrection = false }
            }
            // "gpay la kuduthan" / "cash dhaan" to the open draft: the same draft, paid that way (owner, 12 Oct 2026: "Evlo amount?").
            if (correctedAmount == null && said.none(Char::isDigit) && modeWord.containsMatchIn(said) &&
                (openPayment.kind == PlanKind.PAYMENT_IN || openPayment.kind == PlanKind.PAYMENT_OUT)) {
                val mode = com.shopai.app.brain.tools.KaiInventory.modeOf(said)
                if (mode != openPayment.mode) return revise(openPayment.key, openPayment.partyName, openPayment.amount, mode, openPayment.kind == PlanKind.PAYMENT_OUT, lang)
            }
            // "due venam" with the draft on screen: the same draft, with no due date — never a cancel, never "paid".
            if (openPayment.key == conversationState.statedDraftKey && KaiConversationSemantics.dropsDueDate(said)) {
                val l = shortLang(said, lang)
                return withText(redraftStated(openPayment, openPayment.amount, null, l), noDueDateText(conversationState.stated, l))
            }
            // "illa thappu, Ramesh dhaan kuduthan": the same amount, for the person the owner names now — drafted again for Confirm.
            if ((KaiConversationCare.negates(said) || personFix.containsMatchIn(said)) && KaiConversationSemantics.correctionAmount(said) == null && said.none(Char::isDigit)) {
                val people = runCatching { books.snapshot()?.people.orEmpty() }.getOrDefault(emptyList())
                com.shopai.app.brain.KaiUnderstanding.knownPerson(said, people)?.takeIf { !samePerson(it, openPayment.partyName) }?.let { other ->
                    val outgoing = openPayment.kind == PlanKind.PAYMENT_OUT || openPayment.kind == PlanKind.CREDIT_GIVEN
                    return revise(openPayment.key, other, openPayment.amount, openPayment.mode, outgoing, lang)
                }
            }
            // "illa" / "vera vishayam" to "idhu Kumar pathiyaa?": not about Kumar — never a cancel of the draft on screen.
            if (conversationState.conversationTurn == topicAskedTurn + 1 && notAboutIt.matches(said.trim()))
                topicAnswered(said, lang, openPayment)?.let { return it }
            if (KaiConversationSemantics.cancelsDraft(said) || KaiConversationCare.notNow(said)) {
                return act(KaiAction.CancelPlan(openPayment.key), lang)!!
            }
            // "ama", "seri", "save pannu", "seri add pannu" to the open draft: the owner's confirmation — saved by the engine.
            // Also after a detour whose answer asked nothing (calculator, a balance answer): the draft is still the open question.
            // Not when Kai's last reply asked something else (a choice, a missing time, Save for a word) — "seri" answers that.
            val draftJustShown = conversationState.conversationTurn == conversationState.draftShownTurn + 1
            if (said.none(Char::isDigit) && !waitingForLearningAnswer &&
                (KaiConversationSemantics.onlySave(said, openPayment.partyName) || ((draftJustShown || !kaiAskedAfterDraft) && KaiConversationSemantics.confirmsDraft(said)))) {
                if (openPayment.problems.isNotEmpty()) return say(lang, KaiMood.CLARIFY, null,
                    ta = "ஓனர், இதை இப்போ சேமிக்க முடியாது: ${openPayment.problems.joinToString("; ")}. நான் எதுவும் சேமிக்கல.",
                    tl = "Owner, idha ippo save panna mudiyadhu: ${openPayment.problems.joinToString("; ")}. Naan edhuvum save pannala.",
                    en = "Owner, this can't be saved right now: ${openPayment.problems.joinToString("; ")}. Nothing was saved.")
                return confirm(openPayment.key, shortLang(said, lang))!!
            }
            if (KaiConversationSemantics.asksIfSaved(said)) {
                // "add pannitiya?" with a draft open: not saved — said plainly, with the same draft card again to Confirm.
                val l = shortLang(said, lang)
                conversationState.draftShownTurn = conversationState.conversationTurn
                return draftTurns[openPayment.key]?.let { withText(it.copy(plan = plans[openPayment.key]), notSavedYet(l).reply.text) } ?: notSavedYet(l)
            }
            // "next month 5", "July 6" while a stated payment's draft is open: the draft gets that due date (still waiting for Confirm).
            // "next friday tharuvaan" to a credit / debit draft: when it will be paid — that draft's due date too.
            val owedDraft = openPayment.kind == PlanKind.CREDIT_GIVEN || openPayment.kind == PlanKind.DEBIT_TAKEN
            if (openPayment.key == conversationState.statedDraftKey || owedDraft && promisesPayDate(said)) {
                // "anjaam thethi" / "அஞ்சாம் தேதி" (= "5-aam thethi"): the 5th, read as "5th" is.
                KaiTime.parse(ordinalDay.replace(said) { "${it.groupValues[1]}th" }, now())?.takeIf { it.daySpecified }?.let { t ->
                    return redraftStated(openPayment, openPayment.amount, t.at.toLocalDate(), conversationState.pendingLang ?: lang)
                }
            }
        }

        // "add pannitiya?": answered from what the books confirmed — never "saved" unless the engine said Done.
        if (openPayment == null && KaiConversationSemantics.asksIfSaved(said)) return savedAnswer(KaiConversationSemantics.phraseLang(said, lang))

        // "Dinesh pay" / "Dinesh kitta" — the mic cut the owner off: who pays whom, then how much and when.
        if (openPayment == null) halfSaidStep(said, raw, lang, people0)?.let { return it }

        // Push-back ("adha cancel pannu", "avan 3000 dhaan tharanum", "nee sonnadhu thappu", "puriyala") and what Kai can't do
        // (GST bill, WhatsApp, weather …): answered plainly — nothing is written here.
        // "Kumar oda credit debit history PDF kudu", "Kumar statement WhatsApp-la anuppu", "itha PDF-a kudu": a person's statement
        // as a PDF — before the conversation care that would ask what "itha" means.
        topicAnswered(said, lang)?.let { return it }
        // The answer to "intha bill-oda stock-a add pannanuma, illa kammi pannanuma?" — before "add pannu" reads as a save.
        if (openPayment == null) billStockAnswered(said, lang)?.let { return it }
        if (openPayment == null) bareAmountAnswered(said, lang)?.let { return it }
        if (openPayment == null) statementAsk(said, lang)?.let { return it }
        if (openPayment == null) care(said, lang)?.let { return it }

        // "rendu perum serthu motham" right after two people: their total — never "2" of a product called "Perum Motham".
        if (openPayment == null && KaiConversationCare.asksBoth(said) && KaiConversationCare.withoutBoth(said).none(Char::isDigit) && brain.peopleTalkedAbout().size >= 2)
            return question(said, lang, now().toLocalDate(), people0)

        // "Praba dhaan" / "vera aal" after "'Praba thambi' — Praba dhaan-aa, illa Praba-oda thambi-aa?".
        if (relationChoice != null) relationAnswered(said, lang)?.let { return it }
        // "Chennai" after "Endha Lokesh Owner?" [Chennai Lokesh] [Nagapattinam Lokesh]: the same as tapping that choice.
        planChoice?.let { options ->
            planChoice = null
            KaiEntityResolver.pick(com.shopai.app.brain.tools.KaiSpokenWords.normalize(said), options.map { it.first })?.let { picked ->
                act(options.first { it.first.id == picked.id }.second, lang)?.let { return it }
            }
        }
        // "Nagapattinam" / "rendavadhu" / a phone number after "Lokesh-nu rendu records irukku…": the owner's pick.
        conversationState.entityChoice?.let { choice ->
            conversationState.entityChoice = null
            KaiEntityResolver.pick(com.shopai.app.brain.tools.KaiSpokenWords.normalize(said), choice.candidates)?.let { picked -> return entityPicked(choice, picked, said, lang) }
            newPersonPicked(choice, said, lang)?.let { return it }
        }

        // A new product by chat: "48" / "200 gram" / "28" / "skip" / "illa 3 box dhaan" / "aama" to Kai's one question.
        if (openPayment == null) newItemAnswer(said, lang)?.let { return it }
        // "illa 3 box dhaan" right after a stock draft: the same draft, corrected; "venam" / "cancel" / "வேண்டாம்" drops it.
        if (openPayment == null) stockDraftCorrection(said, lang)?.let { return it }
        // "Kadaiku saman vandhuruku", "saraku vandhachu", "stock add pannalama": goods came, none named — which? asked.
        // Then "Colgate 2 box, Rice 3 moota": each one a stock-in draft (Confirm first) until "avlo dhaan" (owner, 10 Oct 2026).
        // "Colgate 2 box, Rice 3 moota vandhuchu" with no question before: the same, one draft at a time.
        if (openPayment == null) goods(said, lang)?.let { return it }
        if (openPayment == null) stockDraftCancel(said, lang)?.let { return it }
        // "box" / "pieces" to "Colgate 5 — 5 pieces-aa, 5 boxes-aa?".
        if (openPayment == null) unitPicked(said, lang)?.let { return it }
        // "aama" / "venam" to a product change; "credit" / "28" / "box" to a bill with goods.
        if (openPayment == null) productEditAnswer(said)?.let { return it }
        if (openPayment == null) billAnswer(said)?.let { return it }

        // "pudhu" / "adhey" after "already ₹2,000 records-la irukku — adhey-aa, pudhusaa?".
        if (openPayment == null && conversationState.duplicateAskedTurn >= 0) duplicateAnswer(said, lang)?.let { return it }

        // "due venam" after "Due date eppa?": the stated payment has no due date — it is drafted for Confirm, still pending.
        if (openPayment == null && KaiConversationSemantics.dropsDueDate(said)) conversationState.stated?.let { st -> return withoutDueDate(st, said, lang) }

        pendingDueDateAnswer(said, lang)?.let { return it }

        // "note panniko", "save pannu", "kanakkula podu" — the stated payment goes to a draft with Confirm (never saved by itself).
        // "confirm" to "…reminder set pannalama?": the reminder — never an older payment's "already saved".
        if (reminders.answersConfirm(said)) reminders.continueWith(said, lang)?.let { return it }
        if (openPayment == null) statedSave(said, lang)?.let { return it }
        // "venam" right after Kai talked about the stated payment (no draft yet): it is dropped — nothing was or will be saved.
        if (openPayment == null && conversationState.stated != null && KaiConversationSemantics.dropsStated(said) &&
            conversationState.currentIntent in setOf("RECEIVABLE_CONTEXT", "DUE_DATE_ANSWER", "RECEIVABLE_FOLLOW_UP")) {
            val l = shortLang(said, lang)
            conversationState.stated = null
            conversationState.pendingQuestion = null
            conversationState.pendingDay = null
            conversationState.pendingMonthOffset = null
            conversationState.currentIntent = "STATED_PAYMENT_DROPPED"
            tools.log("payment", "conversation", "stated payment dropped by the owner", ActionStatus.CANCELLED, null, said)
            return say(l, KaiMood.NEUTRAL, null, ta = "சரி Owner, எதுவும் சேமிக்கல.", tl = "Seri Owner, edhuvum save pannala.", en = "Okay Owner, nothing was saved.")
        }

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
        // "Kumar Anna enaku 500 tharanum" / "Kumar Anna vera Kumar": the owner teaches or corrects who a reference means.
        referenceTeaching(said, lang)?.let { return it }
        // The owner's confirmed words → words the global Kai core understands.
        val applied = learner?.apply(said) ?: said
        // "Kadaiku saman vandhuruku", "saraku vandhachu", "stock add pannalama": goods came, none named — Kai asks which (owner,
        // 10 Oct 2026). After the owner's own words ("maal" = stock), so a word they taught keeps its meaning.
        if (plans.isEmpty()) goodsAnnounced(applied, lang)?.let { return it }
        // Their references keep the exact record they mean ("Kumar House" → c2), even though the words become the name.
        turnRefs = learner?.lastReferences.orEmpty().groupBy { it.second.name.trim().lowercase(Locale.ROOT) }
            .filterValues { refs -> refs.map { it.second.id }.distinct().size == 1 }.mapValues { it.value.first().second.id }
        // How the owner names each record — this message's own words first ("Kumar Bro" said now beats an older "Kumar Anna").
        refLabels = runCatching { learner?.referenceLabels() }.getOrNull().orEmpty() +
            learner?.lastReferences.orEmpty().associate { (phrase, e) ->
                e.id to (Regex(Regex.escape(phrase), RegexOption.IGNORE_CASE).find(said)?.value ?: phrase)
            }
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
        val spoken = com.shopai.app.brain.tools.KaiSpokenWords.normalize(applied)
        val at = now()
        val people = runCatching { books.snapshot()?.people.orEmpty() }.getOrDefault(emptyList())
        val products = runCatching { tools.products() }.getOrNull()

        // Who is being talked about: "Kumar-aa Ramesh-aa?" answered, people named together, "avan" → the person in the conversation.
        // "details sollu", "avanga total evlo?", "12 பேருடைய details": the people of Kai's last list answer, re-read from the
        // ledger — never "yaar pathi?", and never the last single person (a stock or small-talk detour in between is fine).
        // "Nagapattinam" / "rendavadhu" to the Brain's "which Lokesh?": that record's answer.
        // "Colgate 5 box, boxku 48 pieces, purchase 28, selling 35": a stock line with its details — before the calculator
        // reads "28 … 35" as a margin sum. Only with a unit and product details (or right after Kai asked which product).
        // "Intha bill la irukura stock ah add pannu / kammi pannu": the bill scanner, its items going into / out of stock (owner, 13 Oct 2026).
        if (plans.isEmpty()) com.shopai.app.brain.tools.KaiIntents.billStock(spoken)?.let { return billStockScan(it, lang, said) }
        // Not understood, then one word ("payment", "kadan", "stock", "balance"): the question for that, so the flow goes on.
        if (plans.isEmpty()) guidedTopic(said, lang)?.let { return it }
        // "current bill 1200 kattinen", "rent kattanum": an expense, not a person's entry — said plainly, with where it goes.
        if (plans.isEmpty() && products?.let { productIn(spoken, it) } == null) expenseLine(spoken, lang, people)?.let { return it }
        // "Colgate", "Colgate 2": a product of the shop alone, or with a count — its details, or which way the stock went.
        if (plans.isEmpty() && products != null) productHalfStep(spoken, lang, products)?.let { return it }
        if (plans.isEmpty() && products != null) newStockLine(spoken, said, lang, products, people)?.let { return it }
        if (plans.isEmpty() && products != null) newProductAdd(spoken, said, lang, products, people)?.let { return it }
        // "Colgate nalla pogudhu?", "Colgate nikkudhu": how that one product sold lately — from the item-wise sales.
        if (plans.isEmpty() && products != null && spoken.none(Char::isDigit)) KaiCommands.movement(spoken)?.let { _ ->
            productIn(spoken, products)?.let { p -> return productMovement(p, lang, now().toLocalDate()) }
        }
        // "Stock report", "stock value evlo", "stock-la evlo panam mudangi irukku": the report / the money in stock — not the item list.
        if (plans.isEmpty() && products != null && KaiCommands.asksStockReport(spoken)) return stockReport(lang, now().toLocalDate())
        if (plans.isEmpty() && products != null && KaiCommands.asksStockValue(spoken)) return stockValue(spoken, lang, products)
        // Inventory by chat: bills with goods, product changes / details, the inventory summary — before reminders and payments.
        if (plans.isEmpty() && products != null) inventoryChat(spoken, said, lang, products, people)?.let { return it }
        // "Murukku evlo irukku?", "How much Phone Cover is left?": a product of this shop named in full in a stock question is
        // its stock — even when the name ends like "-ku" (not a person) or holds a word Kai knows for something else ("phone").
        // Only a person who really is in the books stops it ("Murukku" is not "Muruk-ku").
        if (plans.isEmpty() && products != null && stockAsk.containsMatchIn(spoken) && spoken.none(Char::isDigit) &&
            KaiEntityResolver.peopleIn(spoken, people).isEmpty() &&
            !com.shopai.app.brain.tools.KaiInventory.buys(spoken) && !com.shopai.app.brain.tools.KaiInventory.sells(spoken)) {
            productNamed(spoken, products)?.let { p ->
                rememberProduct(p)
                return stock(p, lang)
            }
        }
        if (plans.isEmpty()) brain.answerChoice(spoken)?.let { reply -> return KaiTurn(reply) }
        if (plans.isEmpty()) brain.listFollowUp(spoken)?.let { reply ->
            conversationState.currentIntent = "BUSINESS_QUERY"
            return KaiTurn(reply)
        }
        val named = KaiEntityResolver.peopleIn(spoken, people)
        conversationState.referentQuestion?.let { q ->
            conversationState.referentQuestion = null
            named.singleOrNull()?.takeIf { it in conversationState.mentionedPeople }?.let { who ->
                conversationState.mentionedPeople = listOf(who)
                conversationState.lastPerson = who
                return ask(KaiEntityResolver.withName(q, who))
            }
        }
        if (named.isNotEmpty()) {
            conversationState.mentionedPeople = named
            conversationState.mentionedTurn = conversationState.conversationTurn
            if (named.size == 1 && named.single() != conversationState.lastPerson) conversationState.focusPartyId = null
        }
        if (named.size >= 2 && spoken.none(Char::isDigit) && peopleTogether.containsMatchIn(spoken)) return severalPeople(named, lang)
        val text = referenceResolved(spoken, named) ?: return askWhichPerson(spoken, lang)

        // "Madurai" to the reminder's "Endha Lokesh? Lokesh-aa, Madurai Lokesh-aa?": that contact — not a new fragment.
        if (reminders.awaitingContact()) reminders.continueWith(text, lang)?.let { return it }
        // Pieces said one message at a time: "Mahesh" … "3000" … "enakku tharanum".
        fragment(said, text, people, lang)?.let { return it }
        // "Nagapattinam Lokesh pathi pesuren": the exact record the next "avan" / "avanukku" means.
        aboutPerson(text, named, lang)?.let { return it }
        if (named.isEmpty()) dueOnDay(text, lang)?.let { return it }
        // "same", "again", "innoru thadava": vague on their own — asked about against what is being discussed.
        if (vague.matches(text.trim())) return clarify(text, lang)
        // "Colgate 20 pieces irukku": the owner states what is on the shelf — compared with the books, never a stock-in.
        // "Colgate actual stock 230 pieces, system 240": the shelf count — the difference is drafted as an adjustment (never overwritten).
        if (products != null) physicalCount(text, said, products, lang)?.let { return it }
        if (products != null) stockStatement(text, products, lang)?.let { return it }

        // A named financial action with no amount is a missing-field prompt,
        // not a balance lookup and never an implicit transaction.
        if (KaiCommands.personIn(text, people) != null && isAmountAddRequest(text) &&
            com.shopai.app.brain.KaiUnderstanding.amountsIn(text, at.toLocalDate()).none { it > 0 }) {
            val person = KaiCommands.personIn(text, people)!!
            return payment(PaymentRequest(newKey(), person, null, outgoing = false, mode = PaymentMode.CASH, said = text, directionKnown = false), lang)
        }
        // "Lokesh-ku 500 add pannu": a new entry of 500 on Lokesh's record (no give / take verb) — drafted, saved only on Confirm.
        // "Min vilakku 5 stock add pannu" names a product the shop keeps ("vilakku" is not "to Vilak"): stock, never money.
        if (isAmountAddRequest(text) && com.shopai.app.brain.tools.KaiPaymentDirection.of(text) == null && KaiCommands.route(text, at, people) !is KaiCommand.Payment &&
            products?.let { com.shopai.app.brain.tools.KaiStock.understand(text, it)?.product } == null)
            // "madhan ku 100 add panniko" with "மதன்" in the books: the person kept in Tamil letters (owner's phone, 12 Oct 2026).
            (KaiCommands.personIn(text, people) ?: com.shopai.app.brain.tools.KaiSpokenWords.bookNameIn(text, people))?.let { person ->
                com.shopai.app.brain.KaiUnderstanding.amountsIn(text, at.toLocalDate()).filter { it > 0 }.singleOrNull()
                    ?.takeUnless { Regex("""(?<![\p{L}\p{N}])-\s*\d""").containsMatchIn(text) }
                    ?.let { a -> return payment(PaymentRequest(newKey(), person, BigDecimal.valueOf(a).setScale(2, java.math.RoundingMode.HALF_UP), outgoing = true,
                        mode = PaymentMode.CASH, said = text, addEntry = true), lang) }
            }

        // "Kumar 1000 credit", "Ravi ku 2500 kadan", "Kumar 1000 ku saamaan credit la eduthutu ponaan": an entry on that person's record
        // (credit for a customer, debit for a supplier, asked for a new name) — drafted, saved only on Confirm.
        if (creditWord.containsMatchIn(text) && KaiCommands.route(text, at, people) !is KaiCommand.Payment && !questionLike.containsMatchIn(text))
            // A new name the line starts with ("Sujith 2000 kadan", "சுஜித் 2000 கடன்") too.
            (KaiCommands.personIn(text, people) ?: com.shopai.app.brain.KaiUnderstanding.personPhraseIn(text)
                ?.takeIf { p -> text.trimStart().startsWith(p, ignoreCase = true) && Regex("""[\p{L}\p{M} ]+""").matches(p) })?.let { person ->
                com.shopai.app.brain.KaiUnderstanding.amountsIn(text, at.toLocalDate()).filter { it > 0 }.singleOrNull()
                    ?.takeUnless { Regex("""(?<![\p{L}\p{N}])-\s*\d""").containsMatchIn(text) }
                    ?.let { a -> return payment(PaymentRequest(newKey(), person, BigDecimal.valueOf(a).setScale(2, java.math.RoundingMode.HALF_UP), outgoing = true,
                        mode = PaymentMode.CASH, said = text, addEntry = true), lang) }
            }
        // "Kumar 500 gpay", "ABC Traders 2000 cash": a person, an amount and how it was paid, no verb — a payment whose way the
        // record decides (customer → in, supplier → out); a new name is asked. Drafted only, saved on Confirm.
        if (modeOnly.matches(text.trim()))
            KaiCommands.personIn(text, people)?.let { person ->
                com.shopai.app.brain.KaiUnderstanding.amountsIn(text, at.toLocalDate()).filter { it > 0 }.singleOrNull()?.let { a ->
                    val mode = when {
                        Regex("""(?i)gpay|g pay|upi|phonepe|paytm|online""").containsMatchIn(text) -> PaymentMode.UPI
                        Regex("""(?i)bank|neft|imps""").containsMatchIn(text) -> PaymentMode.BANK_TRANSFER
                        else -> PaymentMode.CASH
                    }
                    return payment(PaymentRequest(newKey(), person, BigDecimal.valueOf(a).setScale(2, java.math.RoundingMode.HALF_UP), outgoing = false,
                        mode = mode, said = text, bySide = true), lang)
                }
            }
        // "Kumar phone number": the number the books have for that person — never their balance.
        phoneNumber(text, people, lang)?.let { return it }

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
            // "full" / "motham" to "Full ₹1,000-aa, illa konjam mattum-aa?": the pending amount from the books.
            if (p.amount == null && fullAmount.containsMatchIn(text) && com.shopai.app.brain.KaiUnderstanding.amountsIn(text, at.toLocalDate()).isEmpty())
                pendingOf(p)?.let { a -> incompletePayment = null; return payment(p.copy(amount = a), lang) }
            val cmd = KaiCommands.route(text, at, people)
            if (!p.directionKnown && cmd is KaiCommand.Payment) {
                incompletePayment = null
                val amount = p.amount ?: cmd.amount
                val name = p.name ?: cmd.name
                if (amount != null && !name.isNullOrBlank()) return payment(p.copy(amount = amount, name = name, outgoing = cmd.outgoing,
                    mode = if (cmd.modeSaid) cmd.mode else p.mode, directionKnown = true), lang)
            }
            if (cmd == KaiCommand.Question || cmd is KaiCommand.Calculate) {
                // "எனக்கு" / "enakku" to "Yaar kitta?": the money came to the owner — still not who gave it; never a person called
                // "enakku" (owner, 11 Oct 2026). Kai asks for the name.
                if (p.name == null && notAName(text)) {
                    if (toOwner.containsMatchIn(text)) incompletePayment = p.copy(outgoing = false, directionKnown = true)
                    return say(lang, KaiMood.CLARIFY, "payment: person missing",
                        ta = "சரி ஓனர், யார் கொடுத்தாங்க? பேர் சொல்லுங்க.", tl = "Seri Owner, yaar kuduthaanga? Peru sollunga.", en = "Okay Owner, who gave it? Tell me the name.")
                }
                val amount = p.amount ?: com.shopai.app.brain.KaiUnderstanding.amountsIn(text, at.toLocalDate()).singleOrNull()?.let { BigDecimal.valueOf(it) }
                val name = p.name ?: KaiCommands.personIn(text, people) ?: text.takeIf { it.split(' ').size <= 3 && it.none(Char::isDigit) && !notAName(it) }
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

        // "pakkathula hardware kadai irukka?", "supermarket enga irukku?": a place nearby, not the books (and not a reminder).
        if (!com.shopai.app.brain.tools.KaiReminderUnderstanding.mentionsReminder(text)) KaiLocalDiscovery.request(text, people + products?.map { it.name }.orEmpty())?.let { return localDiscovery(it, lang, said) }

        // Priority: pending action/context → reminder → stock → bill scan → call → money → calculator → questions → memory → conversation.
        com.shopai.app.brain.tools.KaiReminderUnderstanding.understand(text, at, people)?.let { req ->
            // Said aloud: the reminder keeps the owner's own Tamil words, not Kai's Tanglish reading of them (owner, 11 Oct 2026).
            val kept = if (req is com.shopai.app.brain.tools.ReminderRequest.Create && com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(raw)) req.draft.let { d ->
                val task = com.shopai.app.brain.tools.KaiSpokenWords.asSaid(d.task, raw)
                req.copy(draft = d.copy(task = task, title = if (d.title.equals(d.task, ignoreCase = true)) task.replaceFirstChar { it.titlecase(Locale.ROOT) } else d.title))
            } else req
            return reminders.handle(kept, lang)
        }
        if (products != null) {
            // "Colgate 2 petti vandhiruku" with a unit word Kai doesn't know: ask (box? packet?) — never guess the quantity's unit.
            learner?.unknownUnit(text, said, lang, products)?.let { return it }
            // SCAN_STOCK: "Colgate photo edu", "stock photo edu" → the product camera at once (the owner asked for a photo).
            // "new stock add pannu" (no product, no photo): Kai asks which product and how much — the camera is only a button.
            com.shopai.app.brain.tools.KaiStock.scanRequest(text, products)?.let { name ->
                return if (name.isEmpty() && !photoAsked.containsMatchIn(text)) askStockLine(said, lang) else stockCamera(name, said, lang, products)
            }
            stockChange(text, said, lang, products, people)?.let { return it }
        }
        if (com.shopai.app.brain.tools.KaiIntents.isBillScan(text)) {
            val cmd = KaiCommands.route(text, at, people)
            return scan((cmd as? KaiCommand.ScanBill)?.classifyOnly == true, lang, said)
        }
        // "avan" already became the name: route and ask the records with the resolved words.
        val routed = if (text != spoken) text else applied
        val cmd = KaiCommands.route(routed, at, people).let { c -> if (c == KaiCommand.Question && text != routed) KaiCommands.route(text, at, people) else c }
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
                // "cigarette stock evlo", "pipe stock": every product with that word — not "product illa".
                if (cmd.product != null && productNamed(text, products) == null && KaiCommands.personIn(text, people) == null) stockByWords(text, lang)?.let { return it }
                // "How much Colgate in stock?": the product the books know by that name, not the words around it.
                val product = cmd.product?.let { p -> productNamed(text, products) ?: p }
                product?.let { rememberProduct(it) }
                stock(product, lang)
            }
            KaiCommand.LowStock -> lowStock(lang)
            is KaiCommand.MoneyBalance -> money(cmd.kind, lang)
            KaiCommand.TopProducts -> topProducts(text, lang, at.toLocalDate(), people)
            KaiCommand.SlowStock -> slowStock(text, lang, at.toLocalDate(), people)
            KaiCommand.StockValue -> stockValue(text, lang, products)
            KaiCommand.StockReport -> stockReport(lang, at.toLocalDate())
            KaiCommand.Question -> {
                // "Cigarette evlo irukku?", "pipe evlo iruku": every product with that word, each with its stock (owner, 10 Oct 2026).
                if (stockAsk.containsMatchIn(text) && KaiCommands.personIn(text, people) == null && productNamed(text, products) == null) stockByWords(text, lang)?.let { return it }
                // "Colgate evlo irukku?": a product of this shop and no person — its stock, never a customer lookup.
                if (stockAsk.containsMatchIn(text) && KaiCommands.personIn(text, people) == null) productNamed(text, products)?.let { p ->
                    rememberProduct(p)
                    return stock(p, lang)
                }
                // "Dettol evlo irukku?": neither a product nor a person in the books — said plainly, not "customer record illa".
                if (KaiCommands.personIn(text, people) == null) unknownThing.find(text.trim())?.groupValues?.get(1)?.trim()
                    ?.takeIf { it.isNotEmpty() && it.split(Regex("\\s+")).size <= 3 && !com.shopai.app.brain.tools.KaiPaymentDirection.mentionsMoneyOwed(text) &&
                        !notAThing.containsMatchIn(it) }
                    ?.let { thing ->
                        val name = thing.replaceFirstChar { it.titlecase(Locale.ROOT) }
                        return say(lang, KaiMood.CLARIFY, "unknown: $name",
                            ta = "ஓனர், $name-னு product-உம் இல்ல, customer-உம் இல்ல. Product-ஆ இருந்தா முதல்ல add பண்ணுங்க.",
                            tl = "Owner, $name-nu product-um illa, customer-um illa. Product-na mudhalla add pannunga.",
                            en = "Owner, there's no product or customer called $name. If it's a product, add it first.")
                    }
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
                        val answer = questionOrLearn(routed, text, said, lang, at.toLocalDate(), people, products.orEmpty())
                        if (answer.reply.intent != ChatIntent.UNKNOWN) conversationState.currentIntent = "BUSINESS_QUERY"
                        if (answer.reply.intent == ChatIntent.UNKNOWN && unclearReply.containsMatchIn(answer.reply.text)) clarify(text, lang) else withUnsaved(answer, text, people, lang)
                    }
            }
        }
    }

    /** "Dettol evlo irukku?", "Dettol stock evlo?", "how much Dettol left": the thing asked about. */
    private val unknownThing = Regex("""(?i)^(?:how\s+much\s+|how\s+many\s+)?([\p{L}][\p{L}\p{M}\s]*?)\s+(?:stock\s+)?(?:evlo|evvalavu|ewlo|eppadi)?\s*(?:irukku|iruku|irukka|irukkaa|left|in\s+stock)\s*[?.!]*$""")

    /** Words that make "… irukku?" a question about people, dues or money — never a product name. */
    private val notAThing = Regex("""(?i)(?<![\p{L}])(adhu|athu|idhu|ithu|andha|antha|indha|intha|yaar\w*|yar\w*|who|due|dues|collection|payment|payments|balance|pending|baaki|bakki|kitta|cash|panam|kaasu|bank|upi|gpay|sales|profit|kadan|enna|ethana|evlo|yevlo|innaikku|today|indha|this|month|week|reminder|reminders|kanakku|account|ellam|ellaam|total|motham|labam|laabam|gst|tax|loss|advance|discount|return|vyabaram|viyabaram|business|customers|suppliers)(?![\p{L}])""")

    private val stockAsk = Regex("""(?i)(?<![\p{L}])(evlo|evvalavu|ewlo|how\s+much|how\s+many|irukk?a|irukku|iruku)(?![\p{L}])|எவ்வளவு|இருக்கா|இருக்கு""")

    /** The one product of this shop named in [text] (a whole-word match), else null. */
    private fun productNamed(text: String, products: List<com.shopai.app.brain.tools.ProductRef>?): String? =
        products.orEmpty().filter { p -> Regex("""(?i)(?<![\p{L}\p{M}])${Regex.escape(p.name)}(?![\p{L}\p{M}])""").containsMatchIn(text) }
            .map { it.name }.distinct().singleOrNull()
            // "Arisi stock evlo" with Rice in the shop, "Lux evlo irukku" with Lux Soap: the one product those words name.
            ?: products?.let { com.shopai.app.brain.tools.KaiStock.productNamedIn(text, it)?.name }

    /**
     * Save a receivable / payable mentioned in conversation as session context only; it is not a ledger entry.
     * Who owes whom comes from the one direction model ([com.shopai.app.brain.tools.KaiPaymentDirection]):
     * "Kumar enakku 3000 tharanum" (Kumar owes the owner) vs "naan Kumar-ku 3000 tharanum" (the owner owes Kumar).
     */
    private suspend fun contextualReceivable(text: String, lang: KaiLang, people: List<String>): KaiTurn? {
        val cmd = KaiCommands.route(text, now(), people)
        val owed = com.shopai.app.brain.tools.KaiPaymentDirection.of(text)
        val hasReceivableMeaning = owed != null || Regex("(?i)\\b(tharanum|tharanum|pending|baaki|bakki|owe|owes|collect|varanum)\\b|தரணும்|பாக்கி")
            .containsMatchIn(text)
        // The person: one in the books, or — for a stated debt — the name the sentence starts with ("Kumaran enaku 3000 tharanum").
        val person = KaiCommands.personIn(text, people)
            // A Tamil-script name too ("சுஜித் எனக்கு 2000 தரணும்").
            ?: com.shopai.app.brain.KaiUnderstanding.personPhraseIn(text)
            ?.takeIf { owed != null && it.length >= 3 && Regex("""[\p{L}\p{M} ]+""").matches(it) }
        // "Kumar enakku 3000 tharanum, next month 10-ku": the 10 belongs to the date, not the amount.
        val saidDate = datePhrase.find(text)?.value
            ?.replace(Regex("""(?i)\s*-?\s*(?:ku|kku|m|aam|am|thethi|date)$"""), "")
            ?.let { KaiTime.parse(it, now()) }?.takeIf { it.daySpecified }?.at?.toLocalDate()
        val amountText = if (saidDate != null) datePhrase.replace(text, " ") else text
        val amount = com.shopai.app.brain.KaiUnderstanding.amountsIn(amountText, now().toLocalDate())
            .filter { it > 0 }.singleOrNull()?.let { BigDecimal.valueOf(it).setScale(2, java.math.RoundingMode.HALF_UP) }
        // "Kumar 3000 eppo tharanum?", "Kumar evlo tharanum?" ask the records — they are questions, not statements.
        val asking = Regex("(?i)(?<![\\p{L}])(evlo|evvalavu|eppo|eppa|epo|yeppa|yeppo|yepo|eppadi|yeppadi|epdi|how much|when|yaar|yaaru|yar|yaru|yaruku|yarukku|yaaruku|yaarukku|yarukita|yarukitta|yaarukitta|yaarukita|yarkitta|yaarkitta|yarlam|yaarlam|yaarellam|who)(?![\\p{L}])|(?<![\\p{L}])enna(?!\\s*(?:₹|rs\\.?)?\\s*\\d)(?![\\p{L}])|\\?|எவ்வளவு|எப்போ|யார்")
            .containsMatchIn(text)
        // "Selvam enaku already 3000 tharanum, ippa oru 2000 tharanum": what is already owed, and a NEW amount on top.
        if (person != null && owed != null && !asking) existingAndNew(amountText)?.let { (said, new) ->
            return newOnTopOfExisting(person, said, new, owed, saidDate, text, lang)
        }
        if (hasReceivableMeaning && person != null && amount != null && cmd == KaiCommand.Question && !asking) {
            dropStatedDraft()
            // "Praba thambi enakku 6000 tharanum": Praba himself, or Praba's younger brother? A relation word is asked, never merged.
            relationQuestion(person, text, amount, owed != com.shopai.app.brain.tools.OwedDirection.PAYABLE && !(owed == null &&
                !Regex("(?i)\\b(enakku|enaku|to me|for me)\\b").containsMatchIn(text) && Regex("(?i)\\btharanum\\b|தரணும்").containsMatchIn(text)), saidDate, lang)?.let { return it }
            val ownerIsRecipient = Regex("(?i)\\b(enakku|enaku|to me|for me)\\b").containsMatchIn(text)
            val direction = when (owed) {
                com.shopai.app.brain.tools.OwedDirection.RECEIVABLE -> KaiConversationPaymentDirection.PAYMENT_IN
                com.shopai.app.brain.tools.OwedDirection.PAYABLE -> KaiConversationPaymentDirection.PAYMENT_OUT
                null -> if (ownerIsRecipient || !Regex("(?i)\\btharanum\\b|தரணும்").containsMatchIn(text)) KaiConversationPaymentDirection.PAYMENT_IN
                    else KaiConversationPaymentDirection.PAYMENT_OUT
            }
            remember(person, amount, direction, text, lang)
            // Which "Lokesh": a phone / place / shop word in the sentence, or the one being talked about — else Kai asks.
            identify(person, text, lang)?.let { return it }
            if (saidDate != null) conversationState.stated = conversationState.stated?.copy(dueDate = saidDate)
            // "Mahesh enaku 2000 tharanum, save panniko": straight to the draft (Confirm still saves it).
            if (KaiConversationSemantics.savesDraft(text)) return draftStated(conversationState.stated!!, lang, text)
            if (saidDate != null) {
                justPassed(saidDate, text, now().toLocalDate(), amount)?.let { past ->
                    conversationState.stated = conversationState.stated?.copy(dueDate = null)
                    conversationState.pendingQuestion = KaiPendingQuestion.DUE_DATE
                    conversationState.pendingAskedTurn = conversationState.conversationTurn
                    return askWhichYear(past, lang)
                }
                return dueDateResolved(saidDate, person, amount, direction, lang, text)
            }
            conversationState.pendingQuestion = KaiPendingQuestion.DUE_DATE
            return askDueDate(conversationState.stated?.label ?: person, amount, direction, lang)
        }
        // "Kumar-ku cash kudukanum": who and which way, but no amount — ask only the amount.
        if (owed != null && person != null && amount == null && cmd == KaiCommand.Question && !asking) {
            dropStatedDraft()
            val direction = if (owed == com.shopai.app.brain.tools.OwedDirection.PAYABLE) KaiConversationPaymentDirection.PAYMENT_OUT else KaiConversationPaymentDirection.PAYMENT_IN
            remember(person, null, direction, text, lang)
            identify(person, text, lang)?.let { return it }
            conversationState.pendingQuestion = KaiPendingQuestion.AMOUNT
            conversationState.pendingAskedTurn = conversationState.conversationTurn
            return if (direction == KaiConversationPaymentDirection.PAYMENT_OUT) say(lang, KaiMood.CLARIFY, null,
                ta = "$person-க்கு எவ்வளவு கொடுக்கணும் Owner?", tl = "Owner, $person-ku evlo kudukkanum?", en = "How much do you need to pay $person, Owner?")
            else say(lang, KaiMood.CLARIFY, null,
                ta = "$person கிட்ட எவ்வளவு வாங்கணும் Owner?", tl = "Owner, $person kitta evlo vaanganum?", en = "How much should you collect from $person, Owner?")
        }

        // "Kumar eppa tharuvaan?" after talking about Colgate: the owner returns to the payment stated earlier.
        returnToStated(text, people, lang)?.let { return it }
        if (conversationState.lastBusinessTopic != "RECEIVABLE_CONTEXT" || conversationState.lastPerson == null || conversationState.lastAmount == null) return null
        val followUp = Regex("(?i)\\b(eppa|eppo|when|collect|varum|tharuvaan|tharuvar|adha|adhu|athu|avan|avar|amount)\\b|எப்ப|அத")
            .containsMatchIn(text)
        if (!followUp) return null
        val personName = KaiCommands.personIn(text, people) ?: conversationState.lastPerson!!
        // The payment being talked about keeps its own direction and date (a draft card's cash-flow direction is not it).
        val st = conversationState.stated?.takeIf { it.person.equals(personName, ignoreCase = true) }
        val saved = conversationState.lastSaved?.takeIf { it.person.equals(personName, ignoreCase = true) }
        val out = when {
            st != null -> st.direction == KaiConversationPaymentDirection.PAYMENT_OUT
            saved != null -> !saved.receivable
            else -> conversationState.lastPaymentDirection == "OUT"
        }
        val value = KaiFormat.rupees((st?.amount ?: saved?.amount ?: conversationState.lastAmount!!).toDouble())
        val shown = st?.label ?: personName
        conversationState.currentIntent = "RECEIVABLE_FOLLOW_UP"
        conversationState.currentAction = "ASK_DUE_DATE"
        conversationState.lastQuestion = text
        conversationState.lastPerson = personName
        conversationState.lastCustomer = personName
        conversationState.lastRelevantEntity = personName
        val l = conversationState.pendingLang ?: lang
        // The date was already given in this conversation: said back — with whether it is saved yet.
        (st?.dueDate ?: saved?.dueDate)?.let { due ->
            val whenText = dueText(due, l)
            val note = when {
                saved != null && st == null -> ""
                else -> pickLang(l, ta = " இன்னும் சேமிக்கல Owner.", tl = " Innum save pannala Owner.", en = " Not saved yet, Owner.")
            }
            return if (out) say(l, KaiMood.EXPLAINING, null,
                ta = "Owner, $shown-க்கு $value $whenText கொடுக்கணும்.$note", tl = "Owner, $shown-ku $value $whenText kudukkanum.$note", en = "Owner, you pay $value to $shown $whenText.$note")
            else say(l, KaiMood.EXPLAINING, null,
                ta = "Owner, $shown $value $whenText தருவார்.$note", tl = "Owner, $shown $value $whenText tharuvaar.$note", en = "Owner, $shown pays $value $whenText.$note")
        }
        if (conversationState.pendingQuestion == null) conversationState.pendingQuestion = KaiPendingQuestion.DUE_DATE.also {
            conversationState.pendingEntity = personName
            conversationState.pendingAmount = st?.amount ?: conversationState.lastAmount
            conversationState.pendingPaymentDirection = if (out) KaiConversationPaymentDirection.PAYMENT_OUT else KaiConversationPaymentDirection.PAYMENT_IN
        }
        conversationState.pendingAskedTurn = conversationState.conversationTurn
        return if (out) say(l, KaiMood.CLARIFY, null,
            ta = "$shown-க்கு $value எப்போ கொடுக்கணும்-னு கேக்குறீங்க Owner. அந்த due date record-ல இல்லை — date சொல்லுங்க.",
            tl = "Owner, $shown-ku $value eppo kudukkanum-nu kekkureenga. Andha due date record-la illa — date sollunga.",
            en = "You're asking when to pay $value to $shown, Owner. I don't have that due date recorded — tell me the date.")
        else say(l, KaiMood.CLARIFY, null,
            ta = "$shown $value எப்போ தரணும்-னு கேக்குறீங்க Owner. அந்த due date record-ல இல்லை — date சொல்லுங்க.",
            tl = "Owner, $shown $value eppo tharanum-nu kekkureenga. Andha due date record-la illa — date sollunga.",
            en = "You're asking when $shown pays the $value, Owner. I don't have that due date recorded — tell me the date.")
    }

    /**
     * "Mahesh enaku evlo tharanum?" while the owner's own "Mahesh ₹2,000" is still a draft / unsaved: the records'
     * answer, plus a plain note that the new amount is not in them yet (so the owner isn't misled either way).
     */
    private suspend fun withUnsaved(answer: KaiTurn, text: String, people: List<String>, lang: KaiLang): KaiTurn {
        // "Endha Kumar?" stays the answer: an unsaved amount is never shown as if the person were known.
        if (brain.awaitingChoice()) return answer
        // The person the owner stated a payment for is "asked" even when the books don't know them yet.
        val spokenOf = (listOfNotNull(conversationState.stated?.person, conversationState.statedOtherSide?.person) + plans.values.map { it.partyName })
            .firstOrNull { n -> Regex("""(?i)(?<![\p{L}])${Regex.escape(n)}""").containsMatchIn(text) }
        val asked = spokenOf ?: KaiCommands.personIn(text, people) ?: com.shopai.app.brain.KaiUnderstanding.personIn(text) ?: return answer
        // "naan Kumar-ku evlo tharanum?" asks the owner's side: an unsaved "Kumar enakku 2000" is not part of it.
        val side = com.shopai.app.brain.tools.KaiPaymentDirection.explicitOf(text)?.let {
            if (it == com.shopai.app.brain.tools.OwedDirection.PAYABLE) KaiConversationPaymentDirection.PAYMENT_OUT else KaiConversationPaymentDirection.PAYMENT_IN
        }
        fun planSide(p: ActionPlan) = if (p.kind == PlanKind.CREDIT_GIVEN || p.kind == PlanKind.PAYMENT_IN) KaiConversationPaymentDirection.PAYMENT_IN else KaiConversationPaymentDirection.PAYMENT_OUT
        val draft = plans.values.firstOrNull { it.partyName.equals(asked, ignoreCase = true) && (side == null || planSide(it) == side) }
        val st = listOfNotNull(conversationState.stated, conversationState.statedOtherSide)
            .firstOrNull { it.person.equals(asked, ignoreCase = true) && it.amount != null && (side == null || it.direction == side) }
        val earlier = st != null && st === conversationState.statedOtherSide
        val l = KaiConversationSemantics.phraseLang(text, lang)
        val inBooks = people.any { it.equals(asked, ignoreCase = true) }
        val amount = draft?.amount ?: st?.amount ?: run {
            // "na avarukku evlo tharanum?" about someone only stated (not in the books) on the other side: nothing owed that way.
            val statedOther = spokenOf != null && side != null && !inBooks
            return if (statedOther) answer.copy(reply = answer.reply.copy(text = noneOnSide(asked, side == KaiConversationPaymentDirection.PAYMENT_OUT, l), mood = KaiMood.NEUTRAL))
            else answer
        }
        // "due eppa?" about an unsaved stated payment: its own due date (or none) — the records' answer is not about it.
        val dueAsked = answer.reply.intent == ChatIntent.CUSTOMER_DUE_DATE || answer.reply.intent == ChatIntent.SUPPLIER_DUE_DATE
        if (dueAsked) {
            val due = st?.dueDate ?: draft?.dueDate
            val a = KaiFormat.rupees(amount.toDouble())
            val about = if (due != null) pickLang(l,
                ta = "நீங்க சொன்ன $asked $a-க்கு due ${KaiFormat.date(due, l, now().toLocalDate())}",
                tl = "Neenga sonna $asked $a-ku due ${KaiFormat.date(due, l, now().toLocalDate())}",
                en = "The $a you mentioned for $asked is due ${KaiFormat.date(due, l, now().toLocalDate())}")
            else pickLang(l, ta = "நீங்க சொன்ன $asked $a-க்கு due date இல்ல", tl = "Neenga sonna $asked $a-ku due date illa", en = "The $a you mentioned for $asked has no due date")
            val pending = pickLang(l, ta = " — இன்னும் சேமிக்கல.", tl = " — innum save aagala.", en = " — not saved yet.")
            val recorded = st?.let { recordedPending(it) }
            val text2 = if (recorded == null || recorded.signum() == 0) "Owner, $about$pending" else answer.reply.text + " " + about + pending
            return answer.copy(reply = answer.reply.copy(text = text2))
        }
        val a = KaiFormat.rupees(amount.toDouble())
        // Nothing on that side in the records yet: the owner's own (unsaved) amount leads, with where it stands.
        val ownSide = side ?: st?.direction ?: draft?.let(::planSide)
        val recorded = st?.let { recordedPending(it.copy(direction = ownSide ?: it.direction)) }
        if (st != null && (recorded == null || recorded.signum() == 0)) {
            val out = ownSide == KaiConversationPaymentDirection.PAYMENT_OUT
            val said = if (out) pickLang(l,
                ta = "Owner, $asked-க்கு $a கொடுக்கணும்-னு நீங்க சொன்னீங்க — இன்னும் சேமிக்கல",
                tl = "Owner, $asked-ku $a kudukkanum-nu neenga sonneenga — innum save aagala",
                en = "Owner, you said you owe $asked $a — not saved yet")
            else pickLang(l,
                ta = "Owner, $asked உங்களுக்கு $a தரணும்-னு நீங்க சொன்னீங்க — இன்னும் சேமிக்கல",
                tl = "Owner, $asked ungalukku $a tharanum-nu neenga sonneenga — innum save aagala",
                en = "Owner, you said $asked owes you $a — not saved yet")
            val confirm = if (draft != null) pickLang(l, ta = " — Confirm பண்ணுங்க", tl = " — Confirm pannunga", en = " — tap Confirm") else ""
            val records = if (inBooks) pickLang(l, ta = " Records-ல $asked பாக்கி இல்ல.", tl = " Records-la $asked pending illa.", en = " The records show nothing pending for $asked.")
            else pickLang(l, ta = " Records-ல $asked பதிவு கிடைக்கல.", tl = " Records-la $asked record kidaikala.", en = " There's no record for $asked yet.")
            val lead = "$said$confirm.$records"
            return answer.copy(reply = answer.reply.copy(text = lead))
        }
        val note = if (draft != null) pickLang(l,
            ta = " நீங்க சொன்ன $asked $a இன்னும் சேமிக்கல — Confirm பண்ணுங்க.",
            tl = " Neenga sonna $asked $a innum save aagala — Confirm pannunga.",
            en = " The $a you mentioned for $asked isn't saved yet — tap Confirm.")
        else if (earlier) pickLang(l,
            ta = " நீங்க சொன்ன $asked $a இன்னும் சேமிக்கல.", tl = " Neenga sonna $asked $a innum save aagala.", en = " The $a you mentioned for $asked isn't saved yet.")
        else pickLang(l,
            ta = " நீங்க சொன்ன $asked $a இன்னும் சேமிக்கல — சேமிக்க 'save pannu'-னு சொல்லுங்க.",
            tl = " Neenga sonna $asked $a innum save aagala — save panna 'save pannu'-nu sollunga.",
            en = " The $a you mentioned for $asked isn't saved yet — say 'save it' to save it.")
        return answer.copy(reply = answer.reply.copy(text = answer.reply.text + note))
    }

    private fun noneOnSide(name: String, payable: Boolean, lang: KaiLang): String =
        if (payable) pickLang(lang, ta = "ஓனர், $name-க்கு நீங்க கொடுக்கணும்-னு பாக்கி எதுவும் இல்ல.",
            tl = "Owner, $name-ku neenga kudukkanum-nu pending amount illa.", en = "Owner, you don't owe $name anything.")
        else pickLang(lang, ta = "ஓனர், $name உங்களுக்கு தரணும்-னு பாக்கி எதுவும் இல்ல.",
            tl = "Owner, $name ungalukku tharanum-nu pending amount illa.", en = "Owner, $name doesn't owe you anything.")

    private val amountAsk = Regex("""(?i)(?<![\p{L}])(evlo|evvalavu|how\s+much|enna\s+amount|total|mothama|motham)(?![\p{L}])|எவ்வளவு""")

    /** "September 30-ku evlo?" — the amounts the records say fall due on that day (asked, never assumed, when none). */
    private suspend fun dueOnDay(text: String, rawLang: KaiLang): KaiTurn? {
        if (!amountAsk.containsMatchIn(text) || com.shopai.app.brain.tools.KaiPaymentDirection.mentionsMoneyOwed(text) && Regex("""\d{3,}""").containsMatchIn(text)) return null
        val phrase = datePhrase.find(text)?.value ?: return null
        val day = KaiTime.parse(phrase.replace(Regex("""(?i)\s*-?\s*(?:ku|kku|m|aam|am|thethi|date)$"""), ""), now())?.takeIf { it.daySpecified }?.at?.toLocalDate() ?: return null
        val lang = KaiConversationSemantics.phraseLang(text, rawLang).let { if (it == KaiLang.ENGLISH && Regex("""(?i)-ku|evlo""").containsMatchIn(text)) KaiLang.TANGLISH else it }
        val parties = runCatching { books.snapshot() }.getOrNull()?.parties ?: return unverified(lang, "due")
        val due = parties.filter { p -> p.pending > 0.005 && p.nextDue?.let { it.monthValue == day.monthValue && it.dayOfMonth == day.dayOfMonth } == true }
        val shown = KaiFormat.date(due.firstOrNull()?.nextDue ?: day, lang, now().toLocalDate())
        if (due.isEmpty()) return say(lang, KaiMood.CLARIFY, null,
            ta = "Owner, $shown-க்கு records-ல யாருக்கும் due இல்ல. யார் பத்தி கேக்குறீங்க?",
            tl = "Owner, $shown-ku records-la yaarukkum due illa. Yaar pathi kekkureenga?",
            en = "Owner, nothing in the records falls due on $shown. Who do you mean?")
        val list = due.joinToString(", ") { p ->
            val a = KaiFormat.rupees(p.pending)
            if (p.side == com.shopai.app.brain.Direction.RECEIVABLE) pickLang(lang, ta = "${p.name} $a தரணும்", tl = "${p.name} $a tharanum", en = "${p.name} owes you $a")
            else pickLang(lang, ta = "${p.name}-க்கு $a கொடுக்கணும்", tl = "${p.name}-ku $a kudukkanum", en = "you owe ${p.name} $a")
        }
        if (due.size == 1) { conversationState.lastPerson = due.single().name; conversationState.mentionedPeople = listOf(due.single().name); conversationState.mentionedTurn = conversationState.conversationTurn }
        return say(lang, KaiMood.EXPLAINING, null, ta = "Owner, $shown due: $list.", tl = "Owner, $shown due: $list.", en = "Owner, due on $shown: $list.")
    }

    // ------------------------------------------------------------ an amount already owed + a new one; no due date

    private val numberToken = Regex("""(?i)(?<![\d\p{L}])(?:₹|rs\.?\s*)?\d[\d,]*(?:\.\d+)?\s*k?(?![\d\p{L}])""")
    /** Words that put a second amount on top of the first: "ippa / ippo / innum / innoru / oru / pudhusa / another". */
    private val newAmountWords = Regex("""(?i)(?<![\p{L}])(ippa|ippo|ipo|ipa|innum|inum|innoru|inoru|innonnu|puthusa|pudhusa|puthu|pudhu|new|another|additional|extra|more|again|meendum|marubadiyum|thirumba|aprom|apram)(?![\p{L}])|இப்போ|இன்னும்|இன்னொரு|புதுசா""")

    /**
     * (amount already owed, new amount) when the owner says both — "already 3000 … ippa oru 2000", "3000 pending
     * irukku, innum 2000", "3000 tharanum, innoru 2000". The second is the new entry; the first is never edited.
     */
    private fun existingAndNew(text: String): Pair<BigDecimal, BigDecimal>? {
        val tokens = numberToken.findAll(text).filter { m -> m.value.filter(Char::isDigit).length in 1..8 }.toList()
        if (tokens.size != 2) return null
        if (!newAmountWords.containsMatchIn(text.substring(tokens[0].range.last + 1, tokens[1].range.first))) return null
        val amounts = com.shopai.app.brain.KaiUnderstanding.amountsIn(text, now().toLocalDate()).filter { it > 0 }
        fun money(v: Double) = BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP)
        // "already 2000 … ippa oru 2000": the same number twice is still two amounts.
        if (amounts.size == 1 && tokens.map { t -> t.value.filter(Char::isDigit) }.distinct().size == 1) return money(amounts[0]) to money(amounts[0])
        if (amounts.size != 2) return null
        return money(amounts[0]) to money(amounts[1])
    }

    /** What the books say this person owes / is owed now (null: not in the books, or unreadable). */
    private suspend fun recordedPending(st: KaiStatedPayment): BigDecimal? {
        val receivable = st.direction == KaiConversationPaymentDirection.PAYMENT_IN
        val parties = runCatching { books.snapshot() }.getOrNull()?.parties ?: return null
        val side = if (receivable) com.shopai.app.brain.Direction.RECEIVABLE else com.shopai.app.brain.Direction.PAYABLE
        val p = parties.firstOrNull { it.id == st.partyId && it.side == side } ?: parties.firstOrNull { it.side == side && it.name.equals(st.person, ignoreCase = true) }
        return p?.pending?.let { BigDecimal.valueOf(it).setScale(2, java.math.RoundingMode.HALF_UP) }
    }

    /**
     * "Selvam enaku already 3000 tharanum ippa oru 2000 tharanum": a NEW ₹2,000 draft (Confirm saves it as its own
     * entry); the ₹3,000 already in the books is left as it is, and the card shows the total after Confirm.
     */
    private suspend fun newOnTopOfExisting(
        person: String, existingSaid: BigDecimal, newAmount: BigDecimal, owed: com.shopai.app.brain.tools.OwedDirection,
        saidDate: LocalDate?, text: String, lang: KaiLang,
    ): KaiTurn {
        dropStatedDraft()
        val direction = if (owed == com.shopai.app.brain.tools.OwedDirection.PAYABLE) KaiConversationPaymentDirection.PAYMENT_OUT else KaiConversationPaymentDirection.PAYMENT_IN
        remember(person, newAmount, direction, text, lang)
        identify(person, text, lang)?.let { return it }
        if (saidDate != null) conversationState.stated = conversationState.stated?.copy(dueDate = saidDate)
        val st = conversationState.stated!!.copy(newConfirmed = true).also { conversationState.stated = it }
        val draft = draftStated(st, lang, text)
        if (draft.plan == null) return draft
        val who = st.label ?: st.person
        val recorded = recordedPending(st) ?: BigDecimal.ZERO
        val out = direction == KaiConversationPaymentDirection.PAYMENT_OUT
        val n = KaiFormat.rupees(newAmount.toDouble())
        val total = KaiFormat.rupees((recorded + newAmount).toDouble())
        val old = KaiFormat.rupees(recorded.toDouble())
        val oldLine = if (recorded.compareTo(existingSaid) == 0) pickLang(lang,
            ta = "$who-ஓட ஏற்கனவே இருக்கிற $old அப்படியே இருக்கும் — இது புது $n entry.",
            tl = "$who-oda already irukkura $old apdiye irukkum — idhu pudhu $n entry.",
            en = "The $old $who already has stays as it is — this is a new $n entry.")
        else pickLang(lang,
            ta = "Records-ல $who பாக்கி $old தான் இருக்கு (நீங்க சொன்ன ${KaiFormat.rupees(existingSaid.toDouble())} இல்ல) — இது புது $n entry.",
            tl = "Records-la $who pending $old dhaan irukku (neenga sonna ${KaiFormat.rupees(existingSaid.toDouble())} illa) — idhu pudhu $n entry.",
            en = "The records show $old for $who (not the ${KaiFormat.rupees(existingSaid.toDouble())} you said) — this is a new $n entry.")
        val ask = if (out) pickLang(lang, ta = " Confirm பண்ணா மொத்தம் $total கொடுக்கணும். Due date வேணும்னா சொல்லுங்க, இல்லனா Confirm பண்ணுங்க.",
            tl = " Confirm pannina mothama $total kudukkanum. Due date venumna sollunga, illana Confirm pannunga.",
            en = " After Confirm you owe $total in all. Tell me a due date if you want one, or Confirm.")
        else pickLang(lang, ta = " Confirm பண்ணா மொத்தம் $total வரணும். Due date வேணும்னா சொல்லுங்க, இல்லனா Confirm பண்ணுங்க.",
            tl = " Confirm pannina mothama $total varanum. Due date venumna sollunga, illana Confirm pannunga.",
            en = " After Confirm, $total in all is due to you. Tell me a due date if you want one, or Confirm.")
        return withText(draft, pickLang(lang, ta = "சரி Owner. ", tl = "Seri Owner. ", en = "Okay Owner. ") + oldLine + ask)
    }

    private fun withText(turn: KaiTurn, text: String) = turn.copy(reply = turn.reply.copy(text = text))

    /** "Seri Owner 👍 Due date illa. Selvam kitta ₹5,000 collect panna vendiyadhu. Save pannava?" */
    private fun noDueDateText(st: KaiStatedPayment?, lang: KaiLang): String {
        val who = st?.let { it.label ?: it.person } ?: ""
        val a = st?.amount?.let { KaiFormat.rupees(it.toDouble()) } ?: ""
        val out = st?.direction == KaiConversationPaymentDirection.PAYMENT_OUT
        return if (out) pickLang(lang, ta = "சரி Owner 👍 Due date இல்ல. $who-க்கு $a கொடுக்க வேண்டியது. சேமிக்கட்டுமா?",
            tl = "Seri Owner 👍 Due date illa. $who-ku $a kudukka vendiyadhu. Save pannava?",
            en = "Okay Owner 👍 No due date. You owe $who $a. Save it?")
        else pickLang(lang, ta = "சரி Owner 👍 Due date இல்ல. $who கிட்ட $a collect பண்ண வேண்டியது. சேமிக்கட்டுமா?",
            tl = "Seri Owner 👍 Due date illa. $who kitta $a collect panna vendiyadhu. Save pannava?",
            en = "Okay Owner 👍 No due date. $a is to be collected from $who. Save it?")
    }

    /** "due venam" to "Due date eppa?": the stated payment keeps everything but its date, and goes to the draft card. */
    private suspend fun withoutDueDate(st: KaiStatedPayment, said: String, rawLang: KaiLang): KaiTurn {
        val lang = shortLang(said, rawLang)
        val now = st.copy(dueDate = null)
        conversationState.stated = now
        conversationState.pendingPastDate = null
        conversationState.pendingDay = null
        conversationState.pendingMonthOffset = null
        conversationState.lastDate = null
        tools.log("payment", "conversation", "no due date for the stated payment", ActionStatus.ANSWERED, null, said)
        val draft = draftStated(now, lang, said)
        return if (draft.plan != null) withText(draft, noDueDateText(now, lang)) else draft
    }

    // ------------------------------------------------------------ returning to a topic, clarifying against it

    private fun statedTopic(st: KaiStatedPayment, lang: KaiLang): String {
        val who = st.label ?: st.person
        val a = st.amount?.let { " " + KaiFormat.rupees(it.toDouble()) }.orEmpty()
        return pickLang(lang, ta = "$who$a payment", tl = "$who$a payment", en = "the $who$a payment")
    }

    /** The topic Kai would ask about: the stated payment, the product, or the person just discussed. */
    private fun activeTopic(lang: KaiLang): String? {
        conversationState.stated?.let { return statedTopic(it, lang) }
        if (conversationState.lastBusinessTopic == "STOCK_QUERY") conversationState.lastProduct?.let { return pickLang(lang, ta = "$it stock", tl = "$it stock", en = "$it stock") }
        return conversationState.lastPerson
    }

    /** "Kumar 500" asked about: (person, amount, turn) — the owner's "kuduthan" / "credit" next makes the draft. */
    private var bareAmount: Triple<String, BigDecimal, Long>? = null
    private val bareNameAmount = Regex("""(?i)^([\p{L}\p{M}][\p{L}\p{M} .-]{1,40}?)\s*-?\s*(?:ku|kku|kitta)?\s+(?:₹|rs\.?\s*)?(\d[\d,]*)\s*(?:rs|rupees|ruba)?\s*[?.!]*$""")
    private val paidWord = Regex("""(?i)(?<![\p{L}])(?:kuduth\p{L}*|koduth\p{L}*|thandh\p{L}*|vandhuchu|vanthuchu|paid|pay\s+pannit\p{L}*|anupp\p{L}*|katt\p{L}*|vaanginen|vaangitten)(?![\p{L}])""")
    private val owedWord = Regex("""(?i)(?<![\p{L}])(?:credit|kadan|tharanum|tharuvaan\p{L}*|baaki|udhaar|udhar|debit|kudukkanum|kudukanum)(?![\p{L}])""")

    /** The answer to "Kumar ₹500 — Kumar kuduthaara, illa Kumar ₹500 tharanum-aa?": that entry's draft (Confirm first). */
    private suspend fun bareAmountAnswered(said: String, lang: KaiLang): KaiTurn? {
        val (who, amount, turn) = bareAmount ?: return null
        if (conversationState.conversationTurn != turn + 1) { bareAmount = null; return null }
        val a = amount.stripTrailingZeros().toPlainString()
        val t = said.trim()
        val supplier = runCatching { books.snapshot() }.getOrNull()?.find(who)?.singleOrNull()?.side == com.shopai.app.brain.Direction.PAYABLE
        val line = when {
            t.any(Char::isDigit) || t.split(Regex("""\s+""")).size > 4 -> null
            owedWord.containsMatchIn(t) -> if (supplier) "naan $who ku $a kudukkanum" else "$who enakku $a tharanum"
            paidWord.containsMatchIn(t) -> if (supplier) "$who ku $a kuduthen" else "$who $a kuduthan"
            else -> null
        } ?: return null
        bareAmount = null
        return askAs(lang, line)
    }

    /** "nyabagam vechuko", "remember pannu", "ninaivil vai": keep this in mind (not "nyabagam paduthu" — remind me). */
    private val keepInMind = Regex("""(?i)[\s,]*(?:-?\s*nu\s+)?(?:nyabagam\s+vechuko|nyabagam\s+vechukko|gnabagam\s+vechuko|nyabagam\s+vechukonga|remember\s+(?:pannu|panniko|pannikko|this|it)|ninaivil\s+vai|நினைவில்\s+வை|ஞாபகம்\s+வச்சுக்கோ|ஞாபகம்\s+வெச்சுக்கோ)\s*[.!]?\s*""")
    private val owedTalk = Regex("""(?i)(?<![\p{L}])(?:balance|pending|baaki|bakki|tharanum|kudukkanum|kadan|credit|debit|owes?)(?![\p{L}])""")

    /** The turn Kai last said "edha pathi sollureenga?" with nothing open. */
    private var nothingOpenTurn = -10L
    /** The turn Kai asked "idhu <topic> pathiyaa, illa vera vishayam pathiyaa?" — its "ama" picks the thread up again. */
    private var topicAskedTurn = -10L
    private val aboutIt = Regex("""(?i)(?<![\p{L}])(?:adhu|athu|adhe|athe|adhey)\s*(?:dhaan|than|thaan|dhan)|(?<![\p{L}])(?:payment|panam|kaasu|due|date)\s*(?:ah|a|aa|-a)?\s*(?:pathi|patthi|pathiye)?\s*(?:dhaan|than|thaan|dhan)(?![\p{L}])|பத்தி\s*தான்|அது\s*தான்""")
    private val notAboutIt = Regex("""(?i)^\s*(?:illa|illai|illey|no|nope|vera|vera\s+vishayam|vera\s+onnu|இல்ல|இல்லை|வேற)(?:\s+.*)?$""")

    /**
     * "ama" / "payment ah pathi than" to "idhu Kumar ₹3,000 payment pathiyaa?": the same thread — the question that was open is
     * asked again (owner's phone, 12 Oct 2026: Kai asked the same thing twice, then listed everyone). "illa": the owner says what.
     */
    private suspend fun topicAnswered(said: String, lang: KaiLang, openDraft: ActionPlan? = null): KaiTurn? {
        if (conversationState.conversationTurn != topicAskedTurn + 1) return null
        topicAskedTurn = -10L
        val l = conversationState.pendingLang ?: lang
        // "vera vishayam" / "illa": that person or product is dropped — the next line is read on its own, and Kai asks what it is
        // about with the ways to say it (the next one-word answer gets its guided question).
        if (notAboutIt.matches(said.trim())) {
            personOnTopic = null
            conversationState.lastPerson = null
            conversationState.lastCustomer = null
            conversationState.lastSupplier = null
            conversationState.mentionedPeople = emptyList()
            conversationState.lastRelevantEntity = null
            conversationState.focusPartyId = null
            conversationState.fragmentPerson = null
            conversationState.fragmentAmount = null
            conversationState.lastProduct = null
            lastProduct = null
            brain.forgetLastPerson()
            nothingOpenTurn = conversationState.conversationTurn
            clarifyLang = l
            val kept = openDraft?.let { d ->
                val a = KaiFormat.rupees(d.amount.toDouble())
                pickLang(l, ta = " (${d.partyName} $a இன்னும் சேவ் ஆகல — வேணும்னா Confirm பண்ணுங்க.)",
                    tl = " (${d.partyName} $a innum save aagala — venumna Confirm pannunga.)", en = " (${d.partyName} $a is not saved yet — Confirm it if you want it.)")
            }.orEmpty()
            return say(l, KaiMood.CLARIFY, "topic: something else",
                ta = "சரி Owner, வேற விஷயம். எதைப் பத்தி — payment-ஆ, stock-ஆ, reminder-ஆ? இல்ல நேரா சொல்லுங்க: “Ramesh 300 குடுத்தான்”, “Colgate stock எவ்வளவு”.$kept",
                tl = "Seri Owner, vera vishayam. Edha pathi — payment-aa, stock-aa, reminder-aa? Illa nera sollunga: “Ramesh 300 kuduthan”, “Colgate stock evlo”.$kept",
                en = "Okay Owner, something else. Is it a payment, stock, or a reminder? Or just say it: “Ramesh paid 300”, “Colgate stock”.$kept")
        }
        if (!KaiConversationSemantics.confirmsDraft(said) && !aboutIt.containsMatchIn(said)) return null
        val st = conversationState.stated
        val entity = conversationState.pendingEntity
        val amount = conversationState.pendingAmount
        val direction = conversationState.pendingPaymentDirection
        if (st != null && entity != null && direction != null) when (conversationState.pendingQuestion) {
            KaiPendingQuestion.DUE_DATE -> if (amount != null) return askDueDate(entity, amount, direction, l)
            KaiPendingQuestion.AMOUNT -> {
                conversationState.pendingAskedTurn = conversationState.conversationTurn
                return say(l, KaiMood.CLARIFY, null, ta = "$entity எவ்வளவு Owner?", tl = "$entity evlo Owner?", en = "How much for $entity, Owner?")
            }
            null -> Unit
        }
        // "aamaa" with nothing half-said: what Kai knows about that product / person, and the ways to go on with it.
        if (st == null) {
            val product = conversationState.lastProduct?.takeIf { conversationState.lastBusinessTopic == "STOCK_QUERY" }
            val who = conversationState.lastPerson
            if (product != null) return withHint(askAs(l, "$product stock evlo"), pickLang(l,
                ta = "$product பத்தி வேற என்ன? “$product 2 box வந்துச்சு”, “$product 5 வித்துச்சு”-னு சொல்லுங்க.",
                tl = "$product pathi vera enna? “$product 2 box vandhuchu”, “$product 5 vithuchu”-nu sollunga.",
                en = "Anything else on $product? Say “$product 2 boxes came in” or “$product 5 sold”."))
            if (who != null) return withHint(askAs(l, "$who evlo baaki"), pickLang(l,
                ta = "$who பத்தி என்ன பண்ணணும்? “$who 500 குடுத்தான்”, “$who-க்கு 500 கடன்”, “நாளைக்கு $who-க்கு call பண்ண remind பண்ணு”-னு சொல்லுங்க.",
                tl = "$who pathi enna pannanum? “$who 500 kuduthan”, “$who ku 500 credit”, “naalaiku $who ku call panna remind pannu”-nu sollunga.",
                en = "What should I do for $who? Say “$who paid 500”, “$who 500 credit”, or “remind me to call $who tomorrow”."))
        }
        return null
    }

    private fun withHint(turn: KaiTurn, hint: String) = turn.copy(reply = turn.reply.copy(text = "${turn.reply.text}\n$hint"))

    private val vague = Regex("""(?i)(same|same\s+thing|again|one\s+more|innoru|innoru\s+thadava|marubadiyum|thirumba|adhae|adhe|adhey|athe|repeat)[.!?]*""")
    private val unclearReply = Regex("""clear-ah sollunga|தெளிவா சொல்லுங்க|more clearly""")

    /**
     * A message Kai can't place: never a blank "clear-ah sollunga". With a topic open it asks whether this is about
     * it; a lone number, "adhu", or nothing at all gets a question that says what is missing.
     */
    private fun clarify(text: String, rawLang: KaiLang): KaiTurn {
        val lang = conversationState.pendingLang ?: KaiConversationSemantics.phraseLang(text, rawLang)
        clarifyLang = lang
        val t = text.trim().trimEnd('.', '?', '!')
        activeTopic(lang)?.let { topic ->
            topicAskedTurn = conversationState.conversationTurn
            return say(lang, KaiMood.CLARIFY, "clarify: active topic",
            ta = "Owner, இது $topic பத்தியா, இல்ல வேற விஷயம் பத்தியா?",
            tl = "Owner, idhu $topic pathiyaa, illa vera vishayam pathiyaa?",
            en = "Owner, is this about $topic, or something else?")
        }
        if (amountOnly.matches(t)) return say(lang, KaiMood.CLARIFY, "clarify: lone number",
            ta = "Owner, $t — தொகையா, தேதியா? யார் பத்தி-னு சொல்லுங்க.",
            tl = "Owner, $t — amount-aa, date-aa? Yaar pathi-nu sollunga.",
            en = "Owner, $t — an amount or a date? Who is it about?")
        if (Regex("""(?i)(?<![\p{L}])(adhu|athu|idhu|ithu|athula|idhula|adha|idha)(?![\p{L}])""").containsMatchIn(t)) return say(lang, KaiMood.CLARIFY, "clarify: no referent",
            ta = "Owner, எதைப் பத்தி கேக்குறீங்க? Product பேர் சொல்லுங்க.",
            tl = "Owner, edha pathi kekkureenga? Product per sollunga.",
            en = "Owner, which one do you mean? Tell me the product.")
        // Not understood twice in a row: never the same generic line again — what Kai can read, with examples (principle 6).
        if (conversationState.conversationTurn - nothingOpenTurn <= 1) {
            nothingOpenTurn = conversationState.conversationTurn
            return say(lang, KaiMood.CLARIFY, "clarify: nothing open, again",
                ta = "மன்னிச்சுக்கோங்க ஓனர், இது எனக்கு புரியல 🙏 இப்படி சொல்லுங்க: “Kumar 2000 குடுத்தான்”, “Colgate 2 box வந்துச்சு”, “நாளைக்கு 10 மணிக்கு Ramesh-க்கு call பண்ண remind பண்ணு”, “Kumar எவ்வளவு தரணும்”.",
                tl = "Mannichikonga Owner, idhu enakku puriyala 🙏 Ippadi sollunga: “Kumar 2000 kuduthan”, “Colgate 2 box vandhuchu”, “naalaiku 10 manikku Ramesh ku call panna remind pannu”, “Kumar evlo tharanum”.",
                en = "Sorry Owner, I didn't understand that 🙏 You can say: “Kumar paid 2000”, “Colgate 2 boxes came in”, “remind me to call Ramesh tomorrow at 10”, “how much does Kumar owe”.")
        }
        nothingOpenTurn = conversationState.conversationTurn
        return say(lang, KaiMood.CLARIFY, "clarify: nothing open",
            ta = "Owner, எதைப் பத்தி சொல்றீங்க? Payment-ஆ, stock-ஆ, reminder-ஆ?",
            tl = "Owner, edha pathi sollureenga? Payment-aa, stock-aa, reminder-aa?",
            en = "Owner, what is this about — a payment, stock, or a reminder?")
    }

    /**
     * "Kumar eppa tharuvaan?" / "Kumar payment eppa?" after the conversation moved to Colgate: the payment the owner
     * stated about Kumar comes back (with what the records already say about Kumar, kept apart from it).
     */
    private suspend fun returnToStated(text: String, people: List<String>, lang: KaiLang): KaiTurn? {
        val st = conversationState.stated ?: return null
        if (conversationState.lastBusinessTopic == "RECEIVABLE_CONTEXT" || st.amount == null) return null
        val named = KaiCommands.personIn(text, people) ?: com.shopai.app.brain.KaiUnderstanding.personIn(text)
        if (!named.equals(st.person, ignoreCase = true)) return null
        if (!Regex("""(?i)(?<![\p{L}])(eppa|eppo|epo|when|tharuvaan|tharuvar|tharuvaanga|kudukkanum|kudukanum|vaanganum|payment)(?![\p{L}])|எப்ப""").containsMatchIn(text)) return null
        conversationState.lastBusinessTopic = "RECEIVABLE_CONTEXT"
        conversationState.lastPerson = st.person
        conversationState.lastAmount = st.amount
        conversationState.lastPaymentDirection = if (st.direction == KaiConversationPaymentDirection.PAYMENT_OUT) "OUT" else "IN"
        val l = conversationState.pendingLang ?: lang
        val who = st.label ?: st.person
        val value = KaiFormat.rupees(st.amount.toDouble())
        val out = st.direction == KaiConversationPaymentDirection.PAYMENT_OUT
        // What the books already hold for this person — said separately, never mixed into the new amount.
        val books = runCatching { books.snapshot() }.getOrNull()?.parties
            ?.firstOrNull { it.name.equals(st.person, ignoreCase = true) && it.pending > 0.005 }
        val recordNote = books?.let { p ->
            val due = p.nextDue?.let { " " + KaiFormat.date(it, l, now().toLocalDate()) + " due" }.orEmpty()
            pickLang(l, ta = " (Records-ல ${p.name} ${KaiFormat.rupees(p.pending)}$due ஏற்கனவே இருக்கு.)",
                tl = " (Records-la ${p.name} ${KaiFormat.rupees(p.pending)}$due already irukku.)",
                en = " (The records already show ${p.name} at ${KaiFormat.rupees(p.pending)}$due.)")
        }.orEmpty()
        st.dueDate?.let { due ->
            val w = dueText(due, l)
            return if (out) say(l, KaiMood.EXPLAINING, null, ta = "Owner, நீங்க சொன்ன $who-க்கு $value $w கொடுக்கணும். இன்னும் சேமிக்கல.$recordNote",
                tl = "Owner, neenga sonna $who-ku $value $w kudukkanum. Innum save pannala.$recordNote", en = "Owner, the $value you said you owe $who is due $w. Not saved yet.$recordNote")
            else say(l, KaiMood.EXPLAINING, null, ta = "Owner, நீங்க சொன்ன $who $value $w தருவார். இன்னும் சேமிக்கல.$recordNote",
                tl = "Owner, neenga sonna $who $value $w tharuvaar. Innum save pannala.$recordNote", en = "Owner, the $value from $who you mentioned is due $w. Not saved yet.$recordNote")
        }
        conversationState.pendingQuestion = KaiPendingQuestion.DUE_DATE
        conversationState.pendingEntity = st.person
        conversationState.pendingAmount = st.amount
        conversationState.pendingPaymentDirection = st.direction
        conversationState.pendingAskedTurn = conversationState.conversationTurn
        return if (out) say(l, KaiMood.CLARIFY, null, ta = "Owner, நீங்க சொன்ன $who-க்கு $value — due date இன்னும் சொல்லல. எப்போ கொடுக்கணும்?$recordNote",
            tl = "Owner, neenga sonna $who-ku $value — due date innum sollala. Eppa kudukkanum?$recordNote", en = "Owner, the $value you owe $who — no due date yet. When is it due?$recordNote")
        else say(l, KaiMood.CLARIFY, null, ta = "Owner, நீங்க சொன்ன $who $value — due date இன்னும் சொல்லல. எப்போ வாங்கணும்?$recordNote",
            tl = "Owner, neenga sonna $who $value — due date innum sollala. Eppa vaanganum?$recordNote", en = "Owner, the $value from $who you mentioned — no due date yet. When should you collect it?$recordNote")
    }

    // ------------------------------------------------------------ who / what the owner means

    /**
     * Which record the stated payment's [person] is. One fits (phone, place, shop word, the one being talked
     * about, or the only one): it is kept on the stated payment. Several fit: Kai asks (null = nothing to ask).
     */
    /** This message's owner references, resolved to records (record name, lower-case → id): "Kumar House" → c2. */
    private var turnRefs: Map<String, String> = emptyMap()
    /** How the owner names records they gave a reference to (id → "Kumar House"), for "which one?" choices. */
    private var refLabels: Map<String, String> = emptyMap()

    /** The owner's own words this message, before "avan" became a name: did they say the bare name themselves? */
    private var ownerWords: String = ""

    private fun pinned(name: String): String? = turnRefs[name.trim().lowercase(Locale.ROOT)]

    /**
     * The ONE identity step for every write and question: the owner's established reference first, then phone,
     * place / shop words, the record being talked about — else AMBIGUOUS (asked) or UNKNOWN. Never a guess.
     */
    private fun resolveParty(name: String, said: String, candidates: List<PartyMatch>, contextId: String?): KaiEntityResolver.Result {
        // The owner keeps these same-named records apart by their own references ("Kumar Anna" / "Kumar House"):
        // a bare "Kumar" then does not say which one — the last one talked about is not a reason to pick, Kai asks.
        // ("Avan innum 200" names no one: that still is the person being talked about.)
        val same = KaiEntityResolver.sameName(name, candidates)
        val referenced = same.count { it.id in refLabels }
        // "Lokesh" with "Lokesh" and "Madurai Lokesh": the bare name fits both — asked, even right after one was talked about.
        val longer = same.any { !it.name.trim().equals(name.trim(), ignoreCase = true) && !com.shopai.app.util.NameSound.same(it.name, name) }
        val saidBare = Regex("""(?<![\p{L}\p{M}])""" + Regex.escape(name.trim()), RegexOption.IGNORE_CASE).containsMatchIn(ownerWords)
        val context = contextId.takeIf { referenced < 2 && !longer || !saidBare }
        return KaiEntityResolver.resolve(name, said, candidates, context, referenceId = pinned(name))
    }

    /** Same-named records Kai told apart in this conversation ("Chennai Lokesh"), so a draft / save line says which one. */
    private val chosenLabels = HashMap<String, String>()

    private val relationWords = setOf(
        "thambi", "thamby", "anna", "annan", "akka", "amma", "appa", "paiyan", "payyan", "magan", "ponnu", "magal", "wife", "husband",
        "purushan", "pondatti", "mama", "maama", "machan", "machaan", "mami", "athai", "chithappa", "periyappa", "chithi", "periyamma",
        "thatha", "paati", "thangachi", "thangai", "brother", "sister", "son", "daughter", "father", "mother", "uncle", "aunty",
        "தம்பி", "அண்ணா", "அண்ணன்", "அக்கா", "அம்மா", "அப்பா", "மகன்", "மகள்", "மாமா", "மச்சான்", "தங்கச்சி", "மனைவி",
    )
    /** "Praba thambi …" asked: the answer picks the record or a new person (typed / spoken, or its button). */
    private data class RelationChoice(val key: String, val record: PartyMatch, val phrase: String, val kind: PlanKind, val word: String)
    private var relationChoice: RelationChoice? = null

    /**
     * "Praba thambi enakku 6000 tharanum" / "Madurai Lokesh enakku 500 tharanum" with one Praba / Lokesh in the books and no
     * owner reference for those words: the same person, or someone else (Praba's brother, a Lokesh from Madurai)? Asked once —
     * never merged, never guessed. A place / shop word the record itself carries ("Chennai" for Chennai Lokesh) is not asked.
     */
    private suspend fun relationQuestion(
        person: String, text: String, amount: BigDecimal, receivable: Boolean, due: LocalDate?, lang: KaiLang,
        kind: PlanKind = if (receivable) PlanKind.CREDIT_GIVEN else PlanKind.DEBIT_TAKEN, outgoing: Boolean = receivable, mode: PaymentMode = PaymentMode.CASH,
    ): KaiTurn? {
        val all = runCatching { tools.parties(person) }.getOrNull() ?: return null
        val record = KaiEntityResolver.sameName(person, all).filter { it.customer == receivable }.distinctBy { it.id }.singleOrNull() ?: return null
        val relation = Regex("""(?i)(?<![\p{L}\p{M}])${Regex.escape(person)}\s+([\p{L}\p{M}]+)""").find(text)
            ?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.replace(Regex("""(?i)(kitta|kita|ukku|kku|ku|oda|-)+$"""), "")?.takeIf { it in relationWords }
        val (word, phrase) = relation?.let { it to "$person ${it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) }}" }
            ?: qualifierIn(text, person, listOf(record))?.takeIf { (_, p) -> all.none { it.name.equals(p, ignoreCase = true) } } ?: return null
        val r = PaymentRequest(newKey(), person, amount, outgoing = outgoing, mode = mode, said = text, dueDate = due)
        requests[r.key] = r
        relationChoice = RelationChoice(r.key, record, phrase, kind, word.lowercase(Locale.ROOT))
        val role = if (receivable) pick(lang, ta = "customer", tl = "customer", en = "customer") else pick(lang, ta = "supplier", tl = "supplier", en = "supplier")
        val bal = KaiFormat.rupees(record.balance.toDouble())
        val reply = if (relation != null) pick(lang,
            ta = "ஓனர், '$phrase'-னு சொன்னீங்க — ${record.name} தானா (records-ல $bal), இல்ல ${record.name}-ஓட $word (வேற ஆள், புது $role)-ஆ?",
            tl = "Owner, '$phrase'-nu sonneenga — ${record.name} dhaan-aa (records-la $bal), illa ${record.name}-oda $word (vera aal, puthu $role)-aa?",
            en = "Owner, you said '$phrase' — is that ${record.name} ($bal in the records), or ${record.name}'s $word (someone else, a new $role)?")
        else pick(lang,
            ta = "ஓனர், records-ல '${record.name}' ($bal) இருக்காங்க. '$phrase' — அதே ${record.name}-ஆ, இல்ல புது $role-ஆ?",
            tl = "Owner, records-la '${record.name}' ($bal) irukkaanga. '$phrase' — adhey ${record.name}-aa, illa puthu $role-aa?",
            en = "Owner, '${record.name}' ($bal) is in your records. '$phrase' — the same ${record.name}, or a new $role?")
        return KaiTurn(
            ChatReply(reply, KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(emptyList(), listOf(
                KaiButton("${record.name} · $bal", KaiAction.ChoosePlan(r.key, kind, record.id, record.name)),
                KaiButton(pick(lang, ta = "$phrase — புது $role", tl = "$phrase — puthu $role", en = "$phrase — new $role"), KaiAction.ChoosePlan(r.key, kind, null, phrase)),
                KaiButton(cancelLabel(lang), KaiAction.CancelRequest(r.key)),
            )),
        )
    }

    /** Words said next to a name that are never who it is: time, money ways, fillers, the owner, roles. */
    private val notQualifiers = setOf(
        "hmm", "hmmm", "umm", "ahh", "aah", "haan", "ohh", "ayyo", "ayyoo", "illa", "illai", "thappu", "wrong", "late", "lateaa", "latea", "correct", "early", "seekiram", "sikkiram", "usually", "eppavum", "eppavume",
        "inniku", "innikku", "innaiku", "innaikku", "indru", "naalaikku", "nalaikku", "naalaiku", "nalaiku", "nethu", "nethiku", "today", "tomorrow",
        "yesterday", "ippo", "ipo", "ippa", "ipa", "appo", "seri", "sari", "ok", "okay", "bro", "boss", "owner", "sir", "madam", "kanakku", "kanakkula",
        "account", "entry", "credit", "debit", "gpay", "phonepe", "paytm", "upi", "online", "bank", "card", "cheque", "mattum", "dhaan", "than",
        "thaan", "pudhu", "puthu", "new", "old", "pazhaya", "total", "full", "fulla", "motham", "amount", "rupa", "rupai", "rooba", "ruba", "roobai",
        "advance", "bill", "kadan", "baaki", "bakki", "paakki", "settle", "clear", "reminder", "phone", "message", "whatsapp", "kai", "hello", "please",
        "pls", "also", "innoru", "marupadi", "again", "ini", "inimel", "yen", "what", "who", "when", "where", "which", "endha", "entha", "andha",
        "indha", "intha", "rendu", "moonu", "customer", "supplier", "oda", "ode", "kooda", "kitta", "irukku", "iruku", "gave", "given", "give",
        "returned", "sent", "send", "transferred", "will", "should", "needs", "need", "must", "took", "bought", "borrowed", "lent", "received",
        "collected", "settled", "cleared", "owed", "for", "with", "by", "at", "on", "in", "of", "this", "that", "money", "loan", "eduthaan",
        "return", "refund", "ivlo", "ivvalavu", "evalo", "evvalo", "ewlo", "yevlo", "ethana", "evlavu", "full", "partial", "konjam", "mothama",
        "எனக்கு", "நான்", "கிட்ட", "இருந்து", "இன்னைக்கு", "நாளைக்கு", "ரூபாய்", "பணம்", "பாக்கி", "கணக்கு", "புது", "பழைய",
    )
    private val verbEnding = Regex("""(aan|an|aaru|aru|aanga|anga|nga|inen|en|anum|num|chu|dhu|thu|tha|dha|ttu|tten|ten|rom|vom|kku|ணும்|ான்|ார்|ாங்க|ேன்|து|சு|க்கு)$""")

    /**
     * "Madurai Lokesh" / "lokesh madurai" / "Lokesh Madurai-ku": the word said right next to the name that is not grammar,
     * money, a day, a verb or anything [records] already carry (their name / place / shop words). (word, the phrase in the
     * order said, title-cased) — or null when only the name was said.
     */
    private fun qualifierIn(text: String, person: String, records: List<PartyMatch>): Pair<String, String>? {
        if (person.contains(' ')) return null
        val found = Regex("""[\p{L}\p{M}]+(?:-[\p{L}\p{M}]+)*""").findAll(text).toList()
        val tokens = found.map { it.value }
        val i = tokens.indexOfFirst { t -> t.substringBefore('-').let { it.equals(person, ignoreCase = true) || com.shopai.app.util.NameSound.same(it, person) } }
        if (i < 0) return null
        // Only a word right next to the name ("Kumar 500 return" — the 500 keeps "return" away from Kumar).
        fun nextTo(a: Int, b: Int) = a in found.indices && b in found.indices && text.substring(found[a].range.last + 1, found[b].range.first).isBlank()
        val known = records.flatMap { listOfNotNull(it.name, it.city, it.details) }
            .flatMap { it.lowercase(Locale.ROOT).split(Regex("""[^\p{L}\p{M}]+""")) }.filter { it.isNotEmpty() }.toSet()
        val today = now().toLocalDate()
        fun usable(raw: String): String? {
            val w = raw.substringBefore('-')
            val k = w.lowercase(Locale.ROOT)
            if (w.length < 3 || k in known || k in notQualifiers || k in relationWords || KaiEntityResolver.isFunctionWord(k) ||
                k in com.shopai.app.brain.tools.KaiFeed.notNames) return null
            if (verbEnding.containsMatchIn(k) || samePerson(k, person) || com.shopai.app.brain.KaiUnderstanding.isNumberWord(k)) return null
            // "Karthik kuduthar 5000", "Divya pay pannitaan": the verb, never a second name ("Karthik Kuduthar").
            if (giveVerb.matches(k) || k in com.shopai.app.brain.tools.KaiFeed.receivedWords || k in com.shopai.app.brain.tools.KaiFeed.paidWords) return null
            if (com.shopai.app.brain.tools.KaiPaymentDirection.of(w) != null) return null
            if (runCatching { com.shopai.app.brain.KaiUnderstanding.amountsIn(w, today) }.getOrDefault(emptyList()).isNotEmpty()) return null
            if (runCatching { KaiTime.parse(w, now()) }.getOrNull() != null) return null
            return w.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
        val name = tokens[i].substringBefore('-').let { if (it.equals(person, ignoreCase = true)) person else it }
        tokens.getOrNull(i - 1)?.takeIf { !it.contains('-') && nextTo(i - 1, i) }?.let(::usable)?.let { return it to "$it $name" }
        if (tokens[i].contains('-')) return null // "Lokesh-ku 500": the name alone
        tokens.getOrNull(i + 1)?.takeIf { nextTo(i, i + 1) }?.let(::usable)?.let { return it to "$name $it" }
        return null
    }

    private fun samePerson(word: String, person: String) = word.equals(person, ignoreCase = true) || com.shopai.app.util.NameSound.same(word, person)

    /**
     * The typed / spoken answer to that question: "Praba" / "1" / "adhey" / "avare" → the record; "pudhu" / "vera" / "2" /
     * "thambi" / "Madurai" → a new person. A new question (amount, "evlo", "?") is not an answer.
     */
    private suspend fun relationAnswered(said: String, lang: KaiLang): KaiTurn? {
        val (key, record, phrase, kind, word) = relationChoice ?: return null
        relationChoice = null
        if (requests[key] == null) return null
        val t = com.shopai.app.brain.tools.KaiSpokenWords.normalize(said).lowercase(Locale.ROOT).trim()
        if (t.contains('?') || Regex("""\d{2,}""").containsMatchIn(t) || t.split(Regex("""\s+""")).size > 5) return null
        fun has(words: String) = Regex("""(?<![\p{L}\p{M}])($words)(?![\p{L}\p{M}])""").containsMatchIn(t)
        val newOne = has("2|rendu|rendavadhu|second|vera|veru|pudhu|pudhusu|puthu|new|different|${Regex.escape(word)}")
        val sameOne = has("1|onnu|mudhal|first|adhey|adhe|athey|athe|ade|same|pazhaya|palaya|old|avare|avar\\s*dhaan|avan\\s*dhaan|aama|ama|yes|correct")
        val no = has("illa|illai|no")
        val saidName = has(Regex.escape(record.name.lowercase(Locale.ROOT)))
        return when {
            has(Regex.escape(word)) || newOne && !sameOne || no && !sameOne && !saidName -> act(KaiAction.ChoosePlan(key, kind, null, phrase), lang)
            sameOne && !newOne || saidName && !no -> act(KaiAction.ChoosePlan(key, kind, record.id, record.name), lang)
            else -> null
        }
    }

    /** The owner's own name for a record ("Kumar House") when they gave it one, else which of the same-named ones it is, else its name. */
    private fun ownerName(partyId: String?, name: String) = partyId?.let { refLabels[it] ?: chosenLabels[it] } ?: name

    /** "Kumar House" when the owner gave that record a reference; else its place / shop / phone. */
    private fun labelOf(p: PartyMatch): String = refLabels[p.id] ?: KaiEntityResolver.label(p)

    private fun whichQuestion(name: String, candidates: List<PartyMatch>, lang: KaiLang) = KaiEntityResolver.question(name, candidates, lang, ::labelOf)

    /** After the owner picked a record for words like "Kumar Shop": offer to remember them (stored only on the owner's tap). */
    private suspend fun withLearnOffer(turn: KaiTurn, said: String, name: String, picked: PartyMatch, lang: KaiLang): KaiTurn {
        val reference = KaiEntityResolver.referenceIn(said, name) ?: return turn
        if (refLabels[picked.id].equals(reference, ignoreCase = true)) return turn
        val button = learner?.aliasButton(reference, com.shopai.app.brain.memory.KnownEntity(picked.id, picked.name,
            if (picked.customer) com.shopai.app.brain.memory.MemoryType.CUSTOMER_ALIAS else com.shopai.app.brain.memory.MemoryType.SUPPLIER_ALIAS), lang) ?: return turn
        val card = turn.card?.let { it.copy(buttons = it.buttons + button) } ?: KaiCard(emptyList(), listOf(button))
        return turn.copy(card = card)
    }

    private val referenceMeans = Regex("""(?i)^\s*(?:kai[, ]+)?(.{2,40}?)\s*-?\s*(?:na|nna|naa|means|endral|enral)\s+(.{2,60}?)\s*[.!]?\s*$|^\s*(.{2,40}?)\s*(?:=|என்றால்|-னா|னா)\s*(.{2,60}?)\s*[.!]?\s*$""")
    private val referenceWrong = Regex("""(?i)^\s*(?:(?:illa|illai|illey|no|nope)[\s,]+)?(?:(?:bro|owner|sir|kai)[\s,]+)?(.{2,40}?)\s+vera\s+([\p{L}\p{M}]{2,})(?:\s*(?:aal|person|aalu))?\s*[.!]?\s*$""")

    /**
     * "Kumar Anna-na Sri Stores Kumar": the owner says which record a reference means. Used only when the name in it is
     * shared by several records (a single record is taught by the normal teaching flow). The record is resolved from the
     * owner's own words (shop / place / phone); if still several, Kai asks — and remembers the owner's pick.
     * "Illai, Kumar Anna vera Kumar": the old reference is dropped and Kai asks who it is.
     */
    private suspend fun referenceTeaching(said: String, lang: KaiLang): KaiTurn? {
        val l = learner ?: return null
        if (said.any(Char::isDigit)) return null
        referenceWrong.find(said)?.let { m ->
            val ref = m.groupValues[1].trim()
            val name = m.groupValues[2].trim()
            val records = runCatching { tools.parties(name) }.getOrNull()?.let { KaiEntityResolver.sameName(name, it).distinctBy { p -> p.id } }.orEmpty()
            if (l.dropReference(ref) == null && records.size < 2) return null
            if (records.size < 2) return say(lang, KaiMood.NEUTRAL, null,
                ta = "சரி Owner, `$ref` இனிமே யாருன்னு நான் நினைக்க மாட்டேன். யாருன்னு சொல்லுங்க.",
                tl = "Seri Owner, `$ref` yaaru-nu ini naan ninaikka maatten. Yaaru-nu sollunga.",
                en = "Okay Owner, I've dropped what `$ref` meant. Tell me who it is.")
            refLabels = refLabels.filterValues { !it.equals(ref, ignoreCase = true) }
            conversationState.entityChoice = KaiEntityChoice(name, records, KaiEntityChoice.Purpose.FOCUS, said, reference = ref)
            return say(lang, KaiMood.CLARIFY, "reference corrected: $ref",
                ta = "சரி Owner, `$ref` எந்த $name? " + records.mapIndexed { i, p -> "${i + 1}. ${KaiEntityResolver.label(p)}" }.joinToString("; "),
                tl = "Seri Owner, `$ref` endha $name? " + records.mapIndexed { i, p -> "${i + 1}. ${KaiEntityResolver.label(p)}" }.joinToString("; "),
                en = "Okay Owner, which $name is `$ref`? " + records.mapIndexed { i, p -> "${i + 1}. ${KaiEntityResolver.label(p)}" }.joinToString("; "))
        }
        val m = referenceMeans.find(said) ?: return null
        val ref = (m.groupValues[1].ifBlank { m.groupValues[3] }).trim().trimEnd('-')
        val meaning = (m.groupValues[2].ifBlank { m.groupValues[4] }).trim()
        if (ref.split(Regex("""\s+""")).size > 3) return null
        val people = runCatching { books.snapshot()?.people.orEmpty() }.getOrDefault(emptyList())
        val name = com.shopai.app.brain.KaiUnderstanding.knownPerson(meaning, people) ?: return null
        val records = runCatching { tools.parties(name) }.getOrNull()?.let { KaiEntityResolver.sameName(name, it).distinctBy { p -> p.id } }.orEmpty()
        if (records.size < 2) return null
        return when (val r = KaiEntityResolver.resolve(name, meaning, records, null)) {
            is KaiEntityResolver.Result.One -> {
                l.learnReference(ref, r.party.id) ?: return null
                refLabels = refLabels + (r.party.id to ref)
                tools.log(com.shopai.app.brain.tools.KaiIntents.LEARN_PERSONAL_TERM, "kai memory", "$ref = ${KaiEntityResolver.label(r.party)}", ActionStatus.CONFIRMED, r.party.id, said)
                say(lang, KaiMood.SUCCESS, null,
                    ta = "சரி Owner 👍 `$ref` = ${KaiEntityResolver.label(r.party)}. இனிமே அப்படியே புரிஞ்சுக்குவேன்.",
                    tl = "Seri Owner 👍 `$ref` = ${KaiEntityResolver.label(r.party)}. Ini adhey maadhiri purinjukuven.",
                    en = "Okay Owner 👍 `$ref` = ${KaiEntityResolver.label(r.party)}. I'll understand it that way from now on.")
            }
            else -> {
                val options = (r as? KaiEntityResolver.Result.Many)?.candidates ?: records
                conversationState.entityChoice = KaiEntityChoice(name, options, KaiEntityChoice.Purpose.FOCUS, said, reference = ref)
                say(lang, KaiMood.CLARIFY, "reference: which $name",
                    ta = "Owner, `$ref` எந்த $name? " + options.mapIndexed { i, p -> "${i + 1}. ${KaiEntityResolver.label(p)}" }.joinToString("; "),
                    tl = "Owner, `$ref` endha $name? " + options.mapIndexed { i, p -> "${i + 1}. ${KaiEntityResolver.label(p)}" }.joinToString("; "),
                    en = "Owner, which $name is `$ref`? " + options.mapIndexed { i, p -> "${i + 1}. ${KaiEntityResolver.label(p)}" }.joinToString("; "))
            }
        }
    }

    private suspend fun identify(person: String, text: String, lang: KaiLang): KaiTurn? {
        val records = runCatching { tools.parties(person) }.getOrNull().orEmpty()
        val focus = conversationState.focusPartyId?.takeIf { conversationState.lastPerson.equals(person, ignoreCase = true) }
        return when (val r = resolveParty(person, text, records, focus)) {
            is KaiEntityResolver.Result.One -> {
                val several = KaiEntityResolver.sameName(person, records).size > 1
                conversationState.stated = conversationState.stated?.copy(partyId = r.party.id, label = if (several) labelOf(r.party) else null)
                conversationState.focusPartyId = r.party.id
                null
            }
            is KaiEntityResolver.Result.Many -> {
                val newName = newPersonIn(text, person, r.candidates)
                conversationState.entityChoice = KaiEntityChoice(person, r.candidates, KaiEntityChoice.Purpose.STATED, text, newName = newName)
                conversationState.pendingAskedTurn = conversationState.conversationTurn
                val out = conversationState.stated?.direction == KaiConversationPaymentDirection.PAYMENT_OUT
                fun q(l: KaiLang) = whichQuestion(person, r.candidates, l) + (newName?.let { n ->
                    pick(l, ta = " இல்ல புது ${if (out) "supplier" else "customer"} '$n'-ஆ?", tl = " Illa puthu ${if (out) "supplier" else "customer"} '$n'-aa?",
                        en = " Or a new ${if (out) "supplier" else "customer"}, '$n'?")
                } ?: "")
                say(lang, KaiMood.CLARIFY, "entity: ${r.candidates.size} records named $person", q(KaiLang.TAMIL), q(KaiLang.TANGLISH), q(KaiLang.ENGLISH))
            }
            KaiEntityResolver.Result.None -> null
        }
    }

    /**
     * "Trichy Murugan" with Murugans in Chennai and Madurai only: a capitalised word next to the name that is none of
     * the records' place / shop words — maybe a new person. Null when nothing like that was said.
     */
    private fun newPersonIn(text: String, person: String, candidates: List<PartyMatch>): String? =
        qualifierIn(text, person, candidates)?.second

    /** "pudhu" / "Trichy" / "new" after "Chennai Murugan-aa, Madurai Murugan-aa, illa puthu customer 'Trichy Murugan'-aa?". */
    private suspend fun newPersonPicked(choice: KaiEntityChoice, said: String, lang: KaiLang): KaiTurn? {
        val newName = choice.newName ?: return null
        val t = com.shopai.app.brain.tools.KaiSpokenWords.normalize(said).lowercase(Locale.ROOT)
        val firstWord = Regex.escape(newName.substringBefore(' ').lowercase(Locale.ROOT))
        if (!Regex("""(?<![\p{L}])(pudhu|pudhusu|puthu|new|vera|veru|$firstWord)(?![\p{L}])""").containsMatchIn(t)) return null
        val st = conversationState.stated?.takeIf { it.person.equals(choice.name, ignoreCase = true) } ?: return null
        val l = conversationState.pendingLang ?: KaiConversationSemantics.phraseLang(said, lang)
        val fresh = st.copy(person = newName, partyId = null, label = null, newPerson = true)
        conversationState.stated = fresh
        conversationState.lastPerson = newName
        conversationState.pendingEntity = newName
        if (choice.purpose == KaiEntityChoice.Purpose.DRAFT) return draftStated(fresh, l, said)
        val amount = fresh.amount ?: return null
        conversationState.pendingQuestion = KaiPendingQuestion.DUE_DATE
        return askDueDate(newName, amount, fresh.direction, l)
    }

    /** The owner said which record ("Nagapattinam"): the conversation continues with it. */
    private suspend fun entityPicked(choice: KaiEntityChoice, picked: PartyMatch, said: String, lang: KaiLang): KaiTurn {
        // The owner's pick for a reference they taught / corrected: that is who it means now.
        choice.reference?.let { ref ->
            val which = KaiEntityResolver.label(picked)
            learner?.learnReference(ref, picked.id)
            refLabels = refLabels + (picked.id to ref)
            conversationState.lastPerson = picked.name
            conversationState.focusPartyId = picked.id
            val l = KaiConversationSemantics.phraseLang(said, lang)
            return say(l, KaiMood.SUCCESS, null,
                ta = "சரி Owner 👍 `$ref` = $which.", tl = "Seri Owner 👍 `$ref` = $which.",
                en = "Okay Owner 👍 `$ref` = $which.")
        }
        return withLearnOffer(entityPickedTurn(choice, picked, said, lang), choice.said, choice.name, picked, lang)
    }

    private suspend fun entityPickedTurn(choice: KaiEntityChoice, picked: PartyMatch, said: String, lang: KaiLang): KaiTurn {
        val label = labelOf(picked)
        conversationState.lastPerson = picked.name
        conversationState.lastRelevantEntity = picked.name
        conversationState.focusPartyId = picked.id
        conversationState.mentionedPeople = listOf(picked.name)
        val l = conversationState.pendingLang ?: KaiConversationSemantics.phraseLang(said, lang)
        val st = conversationState.stated?.takeIf { it.person.equals(choice.name, ignoreCase = true) }
        if (choice.purpose == KaiEntityChoice.Purpose.FOCUS || st == null) return say(l, KaiMood.HAPPY, null,
            ta = "சரி Owner, $label பத்தி சொல்லுங்க.", tl = "Seri Owner, $label pathi sollunga.", en = "Okay Owner, tell me about $label.")
        val resolved = st.copy(partyId = picked.id, label = label)
        chosenLabels[picked.id] = label
        conversationState.stated = resolved
        if (choice.purpose == KaiEntityChoice.Purpose.DRAFT) return draftStated(resolved, l, said)
        val amount = resolved.amount ?: run {
            conversationState.pendingQuestion = KaiPendingQuestion.AMOUNT
            conversationState.pendingAskedTurn = conversationState.conversationTurn
            return if (resolved.direction == KaiConversationPaymentDirection.PAYMENT_OUT) say(l, KaiMood.CLARIFY, null,
                ta = "$label-க்கு எவ்வளவு கொடுக்கணும் Owner?", tl = "Owner, $label-ku evlo kudukkanum?", en = "How much do you need to pay $label, Owner?")
            else say(l, KaiMood.CLARIFY, null,
                ta = "$label கிட்ட எவ்வளவு வாங்கணும் Owner?", tl = "Owner, $label kitta evlo vaanganum?", en = "How much should you collect from $label, Owner?")
        }
        conversationState.pendingQuestion = KaiPendingQuestion.DUE_DATE
        return askDueDate(label, amount, resolved.direction, l)
    }

    /** "Kumar and Ramesh rendu perum payment pending": two or more people in one breath. */
    private val peopleTogether = Regex("""(?i)(?<![\p{L}])(payment|pending|balance|baaki|bakki|tharanum|kudukanum|kudukkanum|due|evlo|kanakku)(?![\p{L}])|பாக்கி|தரணும்""")

    /** Each one's balance from the records; a later "avan" is asked about (two people were named). */
    private suspend fun severalPeople(named: List<String>, lang: KaiLang): KaiTurn {
        val snap = runCatching { books.snapshot() }.getOrNull() ?: return unverified(lang, "balance")
        conversationState.lastBusinessTopic = "PARTY_QUERY"
        conversationState.currentIntent = "BUSINESS_QUERY"
        val parts = named.mapNotNull { n -> snap.parties.firstOrNull { it.name.equals(n, ignoreCase = true) } }.map { p ->
            val a = KaiFormat.rupees(p.pending)
            val due = p.nextDue?.let { " (${KaiFormat.date(it, lang, now().toLocalDate())})" }.orEmpty()
            if (p.side == com.shopai.app.brain.Direction.RECEIVABLE) pickLang(lang, ta = "${p.name} $a தரணும்$due", tl = "${p.name} $a tharanum$due", en = "${p.name} owes you $a$due")
            else pickLang(lang, ta = "${p.name}-க்கு $a கொடுக்கணும்$due", tl = "${p.name}-ku $a kudukkanum$due", en = "you owe ${p.name} $a$due")
        }
        if (parts.isEmpty()) return unverified(lang, "balance")
        val joined = parts.joinToString("; ")
        return say(lang, KaiMood.EXPLAINING, null, ta = "Owner, $joined.", tl = "Owner, $joined.", en = "Owner, $joined.")
    }

    /**
     * "avan" / "avanukku" / "andha customer" → the person in the conversation, written as the name so every later
     * step (payment, question, reminder, call) reads one sentence. Null: two people were just named — Kai asks.
     */
    private val bareAmountQuestion = Regex("""(?i)^\s*(?:evlo|evvalavu|ewlo|how\s+much|amount\s+evlo|balance\s+evlo|evlo\s+(?:pending|balance|amount)|எவ்வளவு)\s*[?.!]*\s*$""")

    /** "due eppa?" / "eppa due?" with no name: the due date of the person just talked about. */
    private val bareDueQuestion = Regex("""(?i)^\s*(?:due\s*(?:date\s*)?(?:eppa|eppo|eppadi|enna|when|sollu|\?)|(?:eppa|eppo|when)\s*due(?:\s*date)?|""" +
        """(?:date|thethi|due\s*date)\s*(?:sollu|sollunga|enna)|(?:endha|entha|yentha|which)\s*(?:date|thethi|naal|days?)\s*(?:tharanum|kudukkanum|tharuvaan|tharuvan|tharuvanga|tharuvaaru)?|""" +
        """(?:kadaisi|last)\s*(?:date|thethi)\s*(?:enna|eppo|eppa|sollu)?)\s*[?.!]*\s*$""")

    private fun referenceResolved(spoken: String, named: List<String>): String? {
        // "ivanga rendu perum evlo": both people talked about, not the last one.
        if (named.isEmpty() && KaiConversationCare.asksBoth(spoken)) return spoken
        if (named.isEmpty() && bareDueQuestion.matches(spoken)) {
            val fresh = conversationState.conversationTurn - conversationState.mentionedTurn <= 4
            if (fresh && conversationState.mentionedPeople.size >= 2) return null
            return conversationState.lastPerson?.let { "$it due eppa?" } ?: spoken
        }
        // "evlo?" right after talking about someone: their balance (either side) — the amount, never the due date.
        if (named.isEmpty() && bareAmountQuestion.matches(spoken)) {
            val fresh = conversationState.conversationTurn - conversationState.mentionedTurn <= 4
            if (fresh && conversationState.mentionedPeople.size >= 2) return null
            return conversationState.lastPerson?.let { "$it balance evlo?" } ?: spoken
        }
        if (!KaiEntityResolver.mentionsPerson(spoken) || named.isNotEmpty()) return spoken
        val fresh = conversationState.conversationTurn - conversationState.mentionedTurn <= 4
        if (fresh && conversationState.mentionedPeople.size >= 2) return null
        val who = conversationState.lastPerson ?: return spoken
        return KaiEntityResolver.withName(spoken, who)
    }

    private fun askWhichPerson(spoken: String, lang: KaiLang): KaiTurn {
        conversationState.referentQuestion = spoken
        val names = conversationState.mentionedPeople
        val l = conversationState.pendingLang ?: KaiConversationSemantics.phraseLang(spoken, lang)
        return say(l, KaiMood.CLARIFY, "reference: ${names.size} people",
            ta = names.joinToString("-ஆ ", postfix = "-ஆ Owner?"),
            tl = names.joinToString("-aa ", postfix = "-aa Owner?"),
            en = names.joinToString(" or ", postfix = ", Owner?"))
    }

    private val amountOnly = Regex("""(?i)^\s*(?:₹|rs\.?)?\s*\d[\d,]*(?:\.\d+)?\s*k?\s*(?:rupees?|rubai|ruba|rs)?\s*[.!]?\s*$""")

    /**
     * "Mahesh" … "3000" … "enakku tharanum": the owner gives the payment in pieces. A typed name on its own is
     * kept, then an amount, then who pays whom — together they are one stated payment (nothing saved).
     */
    private suspend fun fragment(said: String, text: String, people: List<String>, lang: KaiLang): KaiTurn? {
        val st = conversationState
        val turn = st.conversationTurn
        val clean = text.trim().trimEnd('.', '!', '?')
        if (st.pendingQuestion != null || clean.isEmpty()) return null
        if (!clean.contains(' ') && clean.none(Char::isDigit)) {
            val known = people.firstOrNull { it.equals(clean, ignoreCase = true) }
            val products = runCatching { tools.products() }.getOrNull().orEmpty()
            val looksLikeName = known != null || (said.trim().first().isUpperCase() && clean.length >= 3 && clean.all { it.isLetter() } &&
                !KaiLexicon.knows(clean) && !KaiEntityResolver.mentionsPerson(clean) && products.none { it.name.equals(clean, ignoreCase = true) } &&
                KaiSmallTalk.reply(clean, lang, now().hour) == null)
            if (!looksLikeName) return null
            st.fragmentPerson = known ?: clean.replaceFirstChar { it.titlecase(Locale.ROOT) }
            st.fragmentAmount = null
            st.fragmentTurn = turn
            if (known != null) return null // a customer / supplier on its own: the records answer about them
            val n = st.fragmentPerson!!
            val l = if (lang == KaiLang.ENGLISH) KaiLang.TANGLISH else lang
            return say(l, KaiMood.CLARIFY, null,
                ta = "Owner, $n பத்தி என்ன? எவ்வளவு, யார் யாருக்கு தரணும்-னு சொல்லுங்க.",
                tl = "Owner, $n pathi enna? Evlo amount, yaar yaarukku tharanum-nu sollunga.",
                en = "Owner, what about $n? Tell me the amount and who owes whom.")
        }
        val person = st.fragmentPerson?.takeIf { turn - st.fragmentTurn <= 3 } ?: return null
        if (amountOnly.matches(clean)) {
            val amount = KaiConversationSemantics.correctionAmount(clean) ?: return null
            st.fragmentAmount = amount
            st.fragmentTurn = turn
            val a = KaiFormat.rupees(amount.toDouble())
            val l = if (lang == KaiLang.ENGLISH) KaiLang.TANGLISH else lang
            return say(l, KaiMood.CLARIFY, null,
                ta = "$person $a — $person உங்களுக்கு தரணுமா, நீங்க $person-க்கு கொடுக்கணுமா Owner?",
                tl = "$person $a — $person ungalukku tharanum-aa, illa neenga $person-ku kudukkanum-aa Owner?",
                en = "$person $a — does $person owe you, or do you owe $person, Owner?")
        }
        val owed = com.shopai.app.brain.tools.KaiPaymentDirection.of(text) ?: return null
        if (KaiCommands.personIn(text, people) != null || text.any(Char::isDigit)) return null
        val amount = st.fragmentAmount
        st.fragmentPerson = null
        st.fragmentAmount = null
        val direction = if (owed == com.shopai.app.brain.tools.OwedDirection.PAYABLE) KaiConversationPaymentDirection.PAYMENT_OUT else KaiConversationPaymentDirection.PAYMENT_IN
        val l = if (lang == KaiLang.ENGLISH) KaiConversationSemantics.phraseLang(text, lang) else lang
        remember(person, amount, direction, "$person ${amount ?: ""} $text", l)
        if (amount == null) {
            conversationState.pendingQuestion = KaiPendingQuestion.AMOUNT
            conversationState.pendingAskedTurn = conversationState.conversationTurn
            return if (direction == KaiConversationPaymentDirection.PAYMENT_OUT) say(l, KaiMood.CLARIFY, null,
                ta = "$person-க்கு எவ்வளவு கொடுக்கணும் Owner?", tl = "Owner, $person-ku evlo kudukkanum?", en = "How much do you need to pay $person, Owner?")
            else say(l, KaiMood.CLARIFY, null,
                ta = "$person கிட்ட எவ்வளவு வாங்கணும் Owner?", tl = "Owner, $person kitta evlo vaanganum?", en = "How much should you collect from $person, Owner?")
        }
        conversationState.pendingQuestion = KaiPendingQuestion.DUE_DATE
        return askDueDate(person, amount, direction, l)
    }

    private val aboutWords = Regex("""(?i)(?<![\p{L}])pathi\s*(pesuren|pesalaam|pesalam|pesanum|sollren|solren|solluren|kekkuren|kekuren)(?![\p{L}])|(?<![\p{L}])(talking|asking)\s+about(?![\p{L}])|பத்தி\s*பேசுறேன்""")

    /** "Nagapattinam Lokesh pathi pesuren": that exact record becomes the one "avan" means. */
    private suspend fun aboutPerson(text: String, named: List<String>, lang: KaiLang): KaiTurn? {
        if (!aboutWords.containsMatchIn(text) || text.any(Char::isDigit)) return null
        val name = named.singleOrNull() ?: return null
        val records = runCatching { tools.parties(name) }.getOrNull().orEmpty()
        return when (val r = resolveParty(name, text, records, null)) {
            is KaiEntityResolver.Result.One -> entityPicked(KaiEntityChoice(name, listOf(r.party), KaiEntityChoice.Purpose.FOCUS), r.party, text, lang)
            is KaiEntityResolver.Result.Many -> {
                conversationState.entityChoice = KaiEntityChoice(name, r.candidates, KaiEntityChoice.Purpose.FOCUS, text)
                conversationState.lastPerson = name
                say(lang, KaiMood.CLARIFY, null, whichQuestion(name, r.candidates, KaiLang.TAMIL),
                    whichQuestion(name, r.candidates, KaiLang.TANGLISH), whichQuestion(name, r.candidates, KaiLang.ENGLISH))
            }
            KaiEntityResolver.Result.None -> null
        }
    }

    private val shelfWords = Regex("""(?i)(?<![\p{L}])(irukku|iruku|irukkudhu|irukkuthu|irukkiradhu|irukkirathu|left)(?![\p{L}])|இருக்கு""")
    private val stockMoveWords = Regex("""(?i)vand|vanth|pochu|poch|pogudhu|pogum|add|sold|vitt|vith|stock\s*in|stock\s*out|evlo|evvalavu|venum|order|\?|(?:aa|ah)\s*$""")

    private val physicalNote = "Physical count · "

    /**
     * "Colgate actual stock 230 pieces, system 240" / "Rice actual 105 kg": what the owner counted on the shelf. The books'
     * own figure is the "system" one; the difference becomes an ADJUSTMENT draft (Confirm saves it, reason: physical count).
     */
    private fun physicalCount(text: String, said: String, products: List<com.shopai.app.brain.tools.ProductRef>, lang: KaiLang): KaiTurn? {
        val m = Regex("""(?i)(?<![\p{L}])(?:actual|actually|physical|real|counted|count|enniten|ennitten|ennunen|ennuna)(?:\s+(?:stock|count|aa|ah|la|-la))?\s*(?:=|:)?\s*(\d+(?:\.\d+)?)\s*([\p{L}]+)?""").find(text) ?: return null
        val product = products.sortedByDescending { it.name.length }
            .firstOrNull { Regex("""(?i)(?<![\p{L}])${Regex.escape(it.name)}""").containsMatchIn(text) } ?: return null
        val counted = m.groupValues[1].toBigDecimal()
        val unit = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(com.shopai.app.brain.tools.KaiInventory::unitOf)
        val factor = com.shopai.app.brain.tools.KaiUnits.factor(product, unit, sessionUnits[product.id].orEmpty()) ?: return say(lang, KaiMood.CLARIFY, null,
            ta = "ஓனர், 1 ${com.shopai.app.brain.tools.KaiStock.unitWord(unit, BigDecimal.ONE)}-ல எத்தனை ${com.shopai.app.brain.tools.KaiStock.unitWord(product.unit)}?",
            tl = "Owner, 1 ${com.shopai.app.brain.tools.KaiStock.unitWord(unit, BigDecimal.ONE)}-la evlo ${com.shopai.app.brain.tools.KaiStock.unitWord(product.unit)}?",
            en = "Owner, how many ${com.shopai.app.brain.tools.KaiStock.unitWord(product.unit)} in 1 ${com.shopai.app.brain.tools.KaiStock.unitWord(unit, BigDecimal.ONE)}?")
        val actual = com.shopai.app.brain.tools.KaiUnits.clean(counted.multiply(factor))
        val diff = actual - product.stock
        val shownBooks = com.shopai.app.brain.tools.KaiStock.shown(product.stock, product.unit)
        lastProduct = product
        if (diff.signum() == 0) return say(lang, KaiMood.HAPPY, null,
            ta = "சரி Owner, records-லயும் ${product.name} $shownBooks தான் — adjustment தேவையில்ல.",
            tl = "Seri Owner, records-la-um ${product.name} $shownBooks dhaan — adjustment thevai illa.",
            en = "Right Owner, the records also show ${product.name} at $shownBooks — no adjustment needed.")
        val turn = stockDraft(product, diff.abs(), product.unit, diff.signum() > 0, "$physicalNote$said", lang)
        val n = com.shopai.app.brain.tools.KaiStock.shown(diff.abs(), product.unit)
        val sign = if (diff.signum() > 0) "+" else "−"
        return withText(turn, pick(lang,
            ta = "Owner, records-ல ${product.name} $shownBooks, நீங்க எண்ணினது ${com.shopai.app.brain.tools.KaiStock.shown(actual, product.unit)}. Adjustment $sign$n (physical count) — உறுதி செய்யவா?",
            tl = "Owner, records-la ${product.name} $shownBooks, neenga ennunadhu ${com.shopai.app.brain.tools.KaiStock.shown(actual, product.unit)}. Adjustment $sign$n (physical count) — confirm pannava?",
            en = "Owner, the records show ${product.name} at $shownBooks; you counted ${com.shopai.app.brain.tools.KaiStock.shown(actual, product.unit)}. Adjustment $sign$n (physical count) — confirm?"))
    }

    /** "Colgate 20 pieces irukku": what the owner sees on the shelf, answered with the books' count (nothing written). */
    private suspend fun stockStatement(text: String, products: List<com.shopai.app.brain.tools.ProductRef>, lang: KaiLang): KaiTurn? {
        if (!shelfWords.containsMatchIn(text) || stockMoveWords.containsMatchIn(text.trim()) || text.none(Char::isDigit)) return null
        val product = products.sortedByDescending { it.name.length }
            .firstOrNull { Regex("""(?i)(?<![\p{L}])${Regex.escape(it.name)}(?![\p{L}])""").containsMatchIn(text) } ?: return null
        rememberProduct(product.name)
        val l = if (lang == KaiLang.ENGLISH) KaiConversationSemantics.phraseLang(text, lang) else lang
        val fact = runCatching { tools.stock(product.name) }.getOrNull()?.singleOrNull() ?: return say(l, KaiMood.CLARIFY, null,
            ta = "சரி Owner, ${product.name} பத்தி பேசுறோம். Records-ல ${product.name} stock இல்ல.",
            tl = "Seri Owner, ${product.name} pathi pesuroam. Records-la ${product.name} stock illa.",
            en = "Okay Owner, ${product.name}. There's no stock record for it.")
        val count = Regex("""\d+(?:\.\d+)?""").find(text)?.value?.toBigDecimalOrNull()
        val q = qty(fact.qty, fact.unit)
        return if (count != null && count.compareTo(fact.qty) == 0) say(l, KaiMood.HAPPY, null,
            ta = "சரி Owner, records-லயும் ${product.name} $q தான் இருக்கு.", tl = "Seri Owner, records-la-um ${product.name} $q dhaan irukku.",
            en = "Right Owner, the records also show ${product.name} at $q.")
        else say(l, KaiMood.EXPLAINING, null,
            ta = "Owner, records-ல ${product.name} $q இருக்கு. மாறியிருந்தா stock in / out-ஆ சொல்லுங்க.",
            tl = "Owner, records-la ${product.name} $q irukku. Maaririndha stock in / out-nu sollunga.",
            en = "Owner, the records show ${product.name} at $q. Tell me a stock in / out if it changed.")
    }

    /** A day with its month in a sentence: "next month 10-ku", "adutha maasam 5", "July 6", "6 July", "அடுத்த மாதம் 10". */
    private val datePhrase = Regex(
        """(?i)(?<![\p{L}])(?:next|adutha|aduththa|indha|intha|this)\s*(?:month|maasam|masam)\s*\d{1,2}(?:\s*(?:st|nd|rd|th))?(?:\s*-?\s*(?:ku|kku|m|aam|am|thethi|date))?(?![\p{L}\d])|""" +
            """(?<![\p{L}\d])\d{1,2}(?:\s*(?:st|nd|rd|th))?\s*(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*(?![\p{L}])|""" +
            """(?<![\p{L}])(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\s*\d{1,2}(?:\s*(?:st|nd|rd|th))?(?![\p{L}\d])|""" +
            // "naalaiku", "tomorrow", "next week", "Friday": a day said in words.
            """(?<![\p{L}])(?:naalaiku|naalaikku|nalaiku|nalaikku|tomorrow|naalai|nalai|day\s+after\s+tomorrow|naalanniku|naalanaikku)(?![\p{L}])|""" +
            // "next monday", "friday", "adutha vaaram" (owner, 11 Oct 2026: "2000 next monday" asked for the due date again).
            """(?<![\p{L}])(?:(?:next|this|indha|intha|adutha|aduththa|varra|vara|varum)\s+)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?![\p{L}])|""" +
            """(?<![\p{L}])(?:next|adutha|aduththa|varra|vara)\s+(?:week|vaaram)(?![\p{L}])|அடுத்த\s+வாரம்|""" +
            // "15th", "15th-ku", "15-aam thethi": a day of this month (or the next, once it has passed).
            """(?<![\p{L}\d₹.,])\d{1,2}\s*(?:st|nd|rd|th)(?:\s*-?\s*(?:ku|kku|thethi|date))?(?![\p{L}\d])|(?<![\p{L}\d₹.,])\d{1,2}\s*-?\s*(?:aam|am|m)?\s*thethi(?![\p{L}])|""" +
            """(?:அடுத்த|இந்த)\s*(?:மாதம்|மாசம்)\s*\d{1,2}""",
    )

    /** A new payment stated while the previous one's draft is still open: that draft is discarded (never saved by a later "seri"). */
    private suspend fun dropStatedDraft() {
        val key = conversationState.statedDraftKey ?: return
        plans.remove(key)?.let { runCatching { tools.discard(it) } }
        conversationState.statedDraftKey = null
        conversationState.pendingDraft = null
        conversationState.pendingConfirmation = false
    }

    /** The stated payment, kept for the short answers that complete it ("10", "next month", "5000"). */
    private fun remember(person: String, amount: BigDecimal?, direction: KaiConversationPaymentDirection, text: String, lang: KaiLang) {
        val out = direction == KaiConversationPaymentDirection.PAYMENT_OUT
        conversationState.currentIntent = "RECEIVABLE_CONTEXT"
        conversationState.currentAction = if (out) "PAY" else "COLLECT"
        conversationState.lastBusinessTopic = "RECEIVABLE_CONTEXT"
        conversationState.previousBusinessContext = "RECEIVABLE_CONTEXT"
        conversationState.lastPerson = person
        conversationState.lastCustomer = person
        conversationState.lastRelevantEntity = person
        conversationState.lastAmount = amount
        conversationState.lastPaymentDirection = if (out) "OUT" else "IN"
        conversationState.lastQuestion = text
        conversationState.pendingEntity = person
        conversationState.pendingAmount = amount
        conversationState.pendingPaymentDirection = direction
        conversationState.pendingDay = null
        conversationState.pendingMonthOffset = null
        conversationState.pendingLang = lang
        conversationState.lastDate = null
        val before = conversationState.stated
        conversationState.statedOtherSide = listOfNotNull(before, conversationState.statedOtherSide)
            .firstOrNull { it.person.equals(person, ignoreCase = true) && it.direction != direction && it.amount != null }
        conversationState.stated = KaiStatedPayment(person, amount, direction)
        conversationState.duplicateAskedTurn = -1
        conversationState.pendingPastDate = null
    }

    private fun askDueDate(person: String, amount: BigDecimal, direction: KaiConversationPaymentDirection, lang: KaiLang): KaiTurn {
        conversationState.pendingAskedTurn = conversationState.conversationTurn
        val value = KaiFormat.rupees(amount.toDouble())
        return say(lang, KaiMood.EXPLAINING, null,
            ta = if (direction == KaiConversationPaymentDirection.PAYMENT_OUT) "சரி Owner. $person-க்கு $value pay பண்ணணும். Due date எந்த தேதி?" else "சரி Owner. $person கிட்ட இருந்து $value collect பண்ணணும். Due date எந்த தேதி?",
            tl = if (direction == KaiConversationPaymentDirection.PAYMENT_OUT) "Seri Owner, $person-ku $value pay pannanum. Due date eppa?" else "Seri Owner, $person kitta irundhu $value collect pannanum. Due date eppa?",
            en = if (direction == KaiConversationPaymentDirection.PAYMENT_OUT) "Got it, Owner. You need to pay $value to $person. When is the due date?" else "Got it, Owner. $person owes you $value. When should you collect it — what due date?")
    }

    /** "10", "10th", "10 தேதி", "10-ம் தேதி": a day of the month with no month. */
    private val dayOnly = Regex("""(?i)^\s*(\d{1,2})\s*(?:st|nd|rd|th)?\s*(?:-?\s*(?:aam|am|m|ம்))?\s*(?:thethi|thedhi|date|தேதி)?\s*[.?!]*\s*$""")
    private val nextMonthWords = Regex("""(?i)(?<![\p{L}])(next|adutha|aduththa|adhutha)\s*(month|maasam|masam|maasathula)?(?![\p{L}])|அடுத்த\s*(மாதம்|மாசம்)?""")
    private val thisMonthWords = Regex("""(?i)(?<![\p{L}])(this|indha|intha|inda)\s*(month|maasam|masam|maasathula)?(?![\p{L}])|இந்த\s*(மாதம்|மாசம்)?""")
    private val monthWord = Regex("""(?i)(?<![\p{L}])(month|maasam|masam|maasathula)(?![\p{L}])|மாதம்|மாசம்""")

    /** "next month" / "indha maasam" (and, once a day was given, just "next" / "indha"): 1, 0, or null. */
    private fun monthChoice(text: String, dayKnown: Boolean): Int? {
        if (text.any(Char::isDigit)) return null
        val words = text.trim().split(Regex("""\s+""")).size
        val hasMonth = monthWord.containsMatchIn(text)
        if (!hasMonth && !(dayKnown && words <= 3)) return null
        return when {
            nextMonthWords.containsMatchIn(text) -> 1
            thisMonthWords.containsMatchIn(text) -> 0
            else -> null
        }
    }

    /**
     * Completes the stated payment from a short answer: its amount ("5000"), or its due date — "next month 10"
     * (KaiTime), "10" (then this month or next is asked, never guessed), "adutha maasam" (then the day is asked).
     */
    /** "innaikku yaar tharanum?", "Murugan evlo?" while a due date is awaited: a new question, not the date. */
    private val newQuestion = Regex("""(?i)(?<![\p{L}])(yaar|yaaru|yar|yaru|who|evlo|evvalavu|ewlo|how\s+much|enna|list|details)(?![\p{L}])|\?\s*$|யார்|எவ்வளவு""")

    private val monthNames = listOf(
        listOf("january", "jan", "ஜனவரி"), listOf("february", "feb", "பிப்ரவரி"), listOf("march", "mar", "மார்ச்"), listOf("april", "apr", "ஏப்ரல்"),
        listOf("may", "மே"), listOf("june", "jun", "ஜூன்"), listOf("july", "jul", "ஜூலை"), listOf("august", "aug", "ஆகஸ்ட்"),
        listOf("september", "sep", "sept", "செப்டம்பர்"), listOf("october", "oct", "அக்டோபர்"), listOf("november", "nov", "நவம்பர்"), listOf("december", "dec", "டிசம்பர்"),
    )
    /** The month a short reply names ("november", "nov", "நவம்பர்"), 1–12 — or null. */
    private fun monthNamed(text: String): Int? {
        val words = text.lowercase(Locale.ROOT).split(Regex("""[^\p{L}\p{M}]+""")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 4) return null
        return monthNames.indexOfFirst { names -> words.any { w -> w in names || names.any { n -> n.length > 3 && w.startsWith(n) } } }.takeIf { it >= 0 }?.plus(1)
    }

    private suspend fun pendingDueDateAnswer(text: String, chatLang: KaiLang): KaiTurn? {
        if (newQuestion.containsMatchIn(text) && !Regex("""(?i)\b(eppa|eppo|when)\b""").containsMatchIn(text)) return null
        val pending = conversationState.pendingQuestion ?: return null
        val entity = conversationState.pendingEntity ?: return null
        val direction = conversationState.pendingPaymentDirection ?: return null
        val at = now()
        val today = at.toLocalDate()
        // A short reply keeps the language the payment was said in ("10" is no language at all).
        val lang = if (text.trim().split(Regex("""\s+""")).size <= 4) conversationState.pendingLang ?: chatLang else chatLang
        // A bare "10" / "next month" / "5000" answers Kai's question only right after it was asked —
        // later it may answer something else (a stock count, a reminder time). A full date always works.
        val justAsked = conversationState.conversationTurn == conversationState.pendingAskedTurn + 1

        if (pending == KaiPendingQuestion.AMOUNT) {
            if (!justAsked) return null
            val amount = KaiConversationSemantics.correctionAmount(text)
                ?: com.shopai.app.brain.KaiUnderstanding.amountsIn(text, today).filter { it > 0 }.singleOrNull()?.let { BigDecimal.valueOf(it).setScale(2, java.math.RoundingMode.HALF_UP) }
                ?: return null
            conversationState.pendingAmount = amount
            conversationState.lastAmount = amount
            conversationState.stated = conversationState.stated?.copy(amount = amount)
            conversationState.pendingQuestion = KaiPendingQuestion.DUE_DATE
            return askDueDate(entity, amount, direction, lang)
        }
        val amount = conversationState.pendingAmount ?: return null

        // "2026" / "adutha varusham" after "September 30 2026 already thaandiduchu — andha date-aa, adutha varusham-aa?".
        conversationState.pendingPastDate?.let { past ->
            yearPicked(text, past)?.let { picked ->
                conversationState.pendingPastDate = null
                return dueDateResolved(picked, entity, amount, direction, lang, text)
            }
            if (justAsked && KaiConversationSemantics.confirmsDraft(text)) {
                conversationState.pendingAskedTurn = conversationState.conversationTurn
                return askWhichYear(past, lang)
            }
        }

        // "10": which month? asked, never guessed.
        if (justAsked) dayOnly.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..31 }?.let { day ->
            conversationState.pendingMonthOffset?.let { offset -> return dueDateResolved(monthDay(today, offset, day), entity, amount, direction, lang, text) }
            conversationState.pendingDay = day
            conversationState.pendingAskedTurn = conversationState.conversationTurn
            conversationState.currentAction = "ASK_DUE_MONTH"
            return say(lang, KaiMood.CLARIFY, null,
                ta = "இந்த மாதம் $day-ஆ Owner, அடுத்த மாதம் $day-ஆ?",
                tl = "Owner, indha maasam $day-aa, illa adutha maasam $day-aa?",
                en = "The ${day}${ordinalSuffix(day)} of this month or next month, Owner?")
        }
        // "november" / "நவம்பர்" to "indha maasam 7-aa, illa adutha maasam 7-aa?": that month's 7th (owner's phone, 10 Oct 2026).
        conversationState.pendingDay?.takeIf { justAsked || conversationState.conversationTurn - conversationState.pendingAskedTurn in 1..4 }?.let { day ->
            // Only the month: "July 6" is a whole date, read below.
            monthNamed(text)?.takeIf { text.none(Char::isDigit) }?.let { month ->
                val first = java.time.LocalDate.of(today.year, month, 1)
                val date = first.withDayOfMonth(minOf(day, first.lengthOfMonth()))
                justPassed(date, text, today, amount)?.let { past ->
                    conversationState.pendingAskedTurn = conversationState.conversationTurn
                    return askWhichYear(past, lang)
                }
                // A month already gone ("september" in October) is next year's, as "September 5" is read.
                return dueDateResolved(if (date.isBefore(today)) date.plusYears(1) else date, entity, amount, direction, lang, text)
            }
        }
        // "ama" / "seri" alone to "indha maasam 5-aa, illa adutha maasam 5-aa?" says yes to neither: the two choices again, short.
        if (justAsked && conversationState.pendingDay != null && text.none(Char::isDigit) && !monthWord.containsMatchIn(text) &&
            !nextMonthWords.containsMatchIn(text) && !thisMonthWords.containsMatchIn(text) && KaiConversationSemantics.confirmsDraft(text) && !KaiConversationSemantics.savesDraft(text)) {
            val day = conversationState.pendingDay!!
            conversationState.pendingAskedTurn = conversationState.conversationTurn
            return say(lang, KaiMood.CLARIFY, null,
                ta = "இந்த மாதம் $day-ஆ, அடுத்த மாதம் $day-ஆ Owner?",
                tl = "Owner, indha maasam $day-aa, adutha maasam $day-aa?",
                en = "This month's ${day}${ordinalSuffix(day)} or next month's, Owner?")
        }
        // "next month" / "ama next month" / "indha maasam": the day was given before, or is asked now. Said with the
        // month word it still answers a few turns later ("saptiya?" in between); a bare "next" only right after.
        val recent = conversationState.conversationTurn - conversationState.pendingAskedTurn in 1..4
        monthChoice(text, conversationState.pendingDay != null)?.takeIf { justAsked || (recent && monthWord.containsMatchIn(text)) }?.let { offset ->
            conversationState.pendingDay?.let { day -> return dueDateResolved(monthDay(today, offset, day), entity, amount, direction, lang, text) }
            conversationState.pendingMonthOffset = offset
            conversationState.pendingAskedTurn = conversationState.conversationTurn
            conversationState.currentAction = "ASK_DUE_DAY"
            return if (offset == 1) say(lang, KaiMood.CLARIFY, null, ta = "அடுத்த மாதம் எந்த தேதி Owner?", tl = "Owner, adutha maasam endha thethi?", en = "Which day next month, Owner?")
            else say(lang, KaiMood.CLARIFY, null, ta = "இந்த மாதம் எந்த தேதி Owner?", tl = "Owner, indha maasam endha thethi?", en = "Which day this month, Owner?")
        }
        // A full date: "next month 10", "indha month 10", "October 10", "naalaikku", "Friday" (KaiTime).
        KaiTime.parse(text, at)?.takeIf { it.daySpecified }?.let { t ->
            val date = t.at.toLocalDate()
            justPassed(date, text, today, amount)?.let { past ->
                conversationState.pendingAskedTurn = conversationState.conversationTurn
                return askWhichYear(past, lang)
            }
            return dueDateResolved(date, entity, amount, direction, lang, text)
        }
        // "amount 5000", "500 dhaan": the amount was wrong — corrected, the date is still asked.
        if (justAsked) KaiConversationSemantics.correctionAmount(text)?.takeIf { it.compareTo(amount) != 0 && it > BigDecimal(31) }?.let { corrected ->
            conversationState.pendingAmount = corrected
            conversationState.lastAmount = corrected
            conversationState.stated = conversationState.stated?.copy(amount = corrected)
            return askDueDate(entity, corrected, direction, lang)
        }
        return null
    }

    private val yearWords = Regex("""(?i)varusham|varusam|varusa|year|வருஷ|வருட""")
    private val yearNumber = Regex("""(?<![\d,.])20[2-9]\d(?![\d,.])""")

    /**
     * "September 30" said on 8 October: the next September 30 is a year away, and this year's has just passed.
     * That day this year (passed within the last month) is returned so Kai can ask which one — a far-off one is clear.
     */
    private fun justPassed(date: LocalDate, text: String, today: LocalDate, amount: BigDecimal? = null): LocalDate? {
        // "2026" said is a year — unless it is the amount itself ("Kumar enakku 2000 tharanum September 30").
        val yearSaid = yearWords.containsMatchIn(text) || yearNumber.findAll(text).any { amount == null || it.value.toBigDecimal().compareTo(amount) != 0 }
        if (date.year <= today.year || yearSaid) return null
        val past = runCatching { LocalDate.of(today.year, date.month, date.dayOfMonth) }.getOrNull() ?: return null
        return past.takeIf { it.isBefore(today) && java.time.temporal.ChronoUnit.DAYS.between(it, today) <= 31 }
    }

    private fun askWhichYear(past: LocalDate, lang: KaiLang): KaiTurn {
        conversationState.pendingPastDate = past
        conversationState.currentAction = "ASK_DUE_YEAR"
        val today = now().toLocalDate()
        val next = past.plusYears(1)
        val p = "${KaiFormat.date(past, lang, today)} ${past.year}"
        val n = KaiFormat.date(next, lang, today).let { if (it.contains(next.year.toString())) it else "$it ${next.year}" }
        return say(lang, KaiMood.CLARIFY, null,
            ta = "Owner, $p ஏற்கனவே தாண்டிடுச்சு. அந்த தேதியா (${past.year}), இல்ல அடுத்த வருஷம் $n-ஆ?",
            tl = "Owner, $p already thaandiduchu. Andha date-aa (${past.year}), illa adutha varusham $n-aa?",
            en = "Owner, $p has already passed. That date (${past.year}), or next year's $n?")
    }

    private val nextYearWords = Regex("""(?i)adutha\s*(?:varusham|varusam|year)|next\s*year|varra\s*varusham|vara\s*varusham|அடுத்த\s*வருஷ|அடுத்த\s*வருட""")
    private val thisYearWords = Regex("""(?i)indha\s*(?:varusham|varusam|year)|this\s*year|andha\s*date|adhey\s*date|adhe\s*date|pona|ponadhu|pochu|thaandi|already|munnaadi|பழைய|இந்த\s*வருஷ|அந்த\s*தேதி|போன""")

    /** "2026" / "andha date" → that (passed) day; "2027" / "adutha varusham" → next year's. Null: not an answer to it. */
    private fun yearPicked(text: String, past: LocalDate): LocalDate? {
        val next = past.plusYears(1)
        val saysPast = text.contains(past.year.toString()) || thisYearWords.containsMatchIn(text)
        val saysNext = text.contains(next.year.toString()) || nextYearWords.containsMatchIn(text)
        return when {
            saysNext && !saysPast -> next
            saysPast && !saysNext -> past
            else -> null
        }
    }

    private fun monthDay(today: LocalDate, offset: Int, day: Int): LocalDate {
        val month = today.withDayOfMonth(1).plusMonths(offset.toLong())
        return month.withDayOfMonth(minOf(day, month.lengthOfMonth()))
    }

    private fun ordinalSuffix(day: Int) = when {
        day in 11..13 -> "th"; day % 10 == 1 -> "st"; day % 10 == 2 -> "nd"; day % 10 == 3 -> "rd"; else -> "th"
    }

    /** "Seri Owner, Kumar kitta irundhu ₹3,000 adutha maasam 10-m thethi vaanganum." — the same payment, now with its date. */
    private suspend fun dueDateResolved(date: LocalDate, entity: String, amount: BigDecimal, direction: KaiConversationPaymentDirection, lang: KaiLang, text: String): KaiTurn {
        val today = now().toLocalDate()
        val out = direction == KaiConversationPaymentDirection.PAYMENT_OUT
        val amountText = KaiFormat.rupees(amount.toDouble())
        conversationState.pendingAskedTurn = -1
        val whenText = dueText(date, lang)
        val shown = conversationState.stated?.takeIf { it.person == entity }?.label ?: entity

        conversationState.pendingQuestion = null
        conversationState.pendingEntity = null
        conversationState.pendingAmount = null
        conversationState.pendingPaymentDirection = null
        conversationState.pendingDay = null
        conversationState.pendingMonthOffset = null
        conversationState.pendingPastDate = null
        conversationState.lastDate = date
        conversationState.stated = conversationState.stated?.takeIf { it.person == entity }?.copy(amount = amount, dueDate = date)
        conversationState.lastPerson = entity
        conversationState.lastRelevantEntity = entity
        conversationState.lastCustomer = entity
        conversationState.lastAmount = amount
        conversationState.lastPaymentDirection = if (out) "OUT" else "IN"
        conversationState.currentIntent = "DUE_DATE_ANSWER"
        conversationState.currentAction = "DUE_DATE_CAPTURED"
        conversationState.lastQuestion = text

        val said = pick(lang,
            ta = if (out) "சரி Owner, $shown-க்கு $amountText $whenText கொடுக்கணும்." else "சரி Owner, $shown கிட்ட இருந்து $amountText $whenText வாங்கணும்.",
            tl = if (out) "Seri Owner, $shown-ku $amountText $whenText kudukkanum." else "Seri Owner, $shown kitta irundhu $amountText $whenText vaanganum.",
            en = if (out) "Okay Owner, pay $amountText to $shown $whenText." else "Okay Owner, collect $amountText from $shown $whenText.")
        // Who, how much, which way and when are all known: the draft comes now (one step) — saved only on Confirm / "seri".
        val st = conversationState.stated ?: return say(lang, KaiMood.EXPLAINING, null, ta = said, tl = said, en = said)
        val draft = draftStated(st, lang, text)
        return if (draft.card != null && plans.isNotEmpty() && draft.reply.mood != KaiMood.CLARIFY)
            withText(draft, said + " " + pick(lang, ta = "சேர்க்கலாமா?", tl = "Add pannalama?", en = "Shall I add it?"))
        else withText(draft, said + " " + draft.reply.text)
    }

    /** "adutha maasam 10-m thethi", "indha maasam 5-m thethi", "naalaikku", "July 6th 2027". */
    private fun dueText(date: LocalDate, lang: KaiLang): String {
        val today = now().toLocalDate()
        val month = today.withDayOfMonth(1)
        return when {
            date == today || date == today.plusDays(1) -> KaiFormat.date(date, lang, today)
            date.withDayOfMonth(1) == month -> pickLang(lang, ta = "இந்த மாதம் ${date.dayOfMonth}-ம் தேதி", tl = "indha maasam ${date.dayOfMonth}-m thethi", en = "on ${KaiFormat.date(date, lang, today)}")
            date.withDayOfMonth(1) == month.plusMonths(1) -> pickLang(lang, ta = "அடுத்த மாதம் ${date.dayOfMonth}-ம் தேதி", tl = "adutha maasam ${date.dayOfMonth}-m thethi", en = "on ${KaiFormat.date(date, lang, today)}")
            else -> if (lang == KaiLang.ENGLISH) "on ${KaiFormat.date(date, lang, today)}" else KaiFormat.date(date, lang, today)
        }
    }

    /** A short reply ("ok", "add panniko") keeps the language the payment was said in. */
    private fun shortLang(said: String, lang: KaiLang): KaiLang =
        if (said.trim().split(Regex("""\s+""")).size <= 4) conversationState.pendingLang ?: KaiConversationSemantics.phraseLang(said, lang)
        else KaiConversationSemantics.phraseLang(said, lang)

    private fun pickLang(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) {
        KaiLang.TAMIL -> ta; KaiLang.TANGLISH -> tl; KaiLang.ENGLISH -> en
    }

    // ------------------------------------------------------------ a stated payment → draft → confirm → books

    /**
     * "note panniko" / "save pannu" / "kanakkula podu" after "Mahesh enaku 2000 tharanum": the stated payment
     * becomes a draft card (Credit: they owe the owner; Debit: the owner owes them) — written only on Confirm.
     * Said again after it was saved: never a second entry.
     */
    private suspend fun statedSave(said: String, lang: KaiLang): KaiTurn? {
        val st = conversationState.stated
        if (said.any(Char::isDigit) || !KaiConversationSemantics.onlySave(said, st?.person ?: conversationState.lastSaved?.person)) return null
        val l = conversationState.pendingLang ?: KaiConversationSemantics.phraseLang(said, lang)
        if (st == null) {
            val saved = conversationState.lastSaved ?: return say(l, KaiMood.CLARIFY, null,
                ta = "ஓனர், சேமிக்க இப்போ எதுவும் இல்ல. யார், எவ்வளவு-னு சொல்லுங்க — உதா: “Mahesh எனக்கு 2000 தரணும்”.",
                tl = "Owner, save panna ippo edhuvum illa. Yaar, evlo-nu sollunga — eg: “Mahesh enaku 2000 tharanum”.",
                en = "Owner, there's nothing to save yet. Tell me who and how much — e.g. “Mahesh owes me 2000”.")
            return say(l, KaiMood.NEUTRAL, null,
                ta = "ஓனர், அது ஏற்கனவே சேமிச்சாச்சு — ${saved.person} ${KaiFormat.rupees(saved.amount.toDouble())} (${saved.reference}). மறுபடி சேர்க்கல.",
                tl = "Owner, adhu already save aagiduchu — ${saved.person} ${KaiFormat.rupees(saved.amount.toDouble())} (${saved.reference}). Thirumba add pannala.",
                en = "Owner, that's already saved — ${saved.person} ${KaiFormat.rupees(saved.amount.toDouble())} (${saved.reference}). I didn't add it again.")
        }
        return draftStated(st, l, said)
    }

    private fun askIfDuplicate(st: KaiStatedPayment, amount: BigDecimal, lang: KaiLang): KaiTurn {
        conversationState.duplicateAskedTurn = conversationState.conversationTurn
        val who = st.label ?: st.person
        val a = KaiFormat.rupees(amount.toDouble())
        return if (st.direction == KaiConversationPaymentDirection.PAYMENT_OUT) say(lang, KaiMood.CLARIFY, null,
            ta = "Owner, Records-ல ஏற்கனவே $who-க்கு $a கொடுக்கணும்-னு இருக்கு. அதே $a-ஆ, இல்ல புது $a-ஆ?",
            tl = "Owner, records-la already $who-ku $a kudukkanum-nu irukku. Adhey $a-aa, illa pudhu $a-aa?",
            en = "Owner, the records already show you owe $who $a. Is it the same $a, or a new $a?")
        else say(lang, KaiMood.CLARIFY, null,
            ta = "Owner, Records-ல ஏற்கனவே $who $a தரணும்-னு இருக்கு. அதே $a-ஆ, இல்ல புது $a-ஆ?",
            tl = "Owner, records-la already $who $a tharanum-nu irukku. Adhey $a-aa, illa pudhu $a-aa?",
            en = "Owner, the records already show $who owes you $a. Is it the same $a, or a new $a?")
    }

    private val newDebtWords = Regex("""(?i)(?<![\p{L}])(pudhu|pudhusu|pudhusa|pudhusaa|puthu|puthusu|puthusa|new|innoru|another|vera|veru|extra|separate|thaniya|thaniyaa)(?![\p{L}])|புது|புதுசு|இன்னொரு|வேற""")
    private val sameAmountWords = Regex("""(?i)(?<![\p{L}])(adhey|adhe|athey|athe|same|already|pazhaya|pazhasu|old|adhu\s*dhaan|adhu\s*than|athu\s*than|athu\s*dhaan)(?![\p{L}])|அதே|அது\s*தான்|பழைய|ஏற்கனவே""")

    /** The answer to [askIfDuplicate]: a new amount goes to the draft; the same one adds nothing. */
    private suspend fun duplicateAnswer(said: String, lang: KaiLang): KaiTurn? {
        val asked = conversationState.duplicateAskedTurn
        val st = conversationState.stated
        if (asked < 0 || st == null) { conversationState.duplicateAskedTurn = -1; return null }
        val l = shortLang(said, lang)
        val isNew = newDebtWords.containsMatchIn(said)
        val isSame = sameAmountWords.containsMatchIn(said)
        if (isNew && !isSame) {
            conversationState.duplicateAskedTurn = -1
            val confirmed = st.copy(newConfirmed = true)
            conversationState.stated = confirmed
            return draftStated(confirmed, l, said)
        }
        if (isSame && !isNew) {
            conversationState.duplicateAskedTurn = -1
            conversationState.stated = null
            val who = st.label ?: st.person
            val a = st.amount?.let { KaiFormat.rupees(it.toDouble()) }.orEmpty()
            tools.log("payment", "conversation", "same amount as the books — nothing added", ActionStatus.ANSWERED, null, said)
            return say(l, KaiMood.HAPPY, null,
                ta = "சரி Owner, புதுசா எதுவும் சேர்க்கல. Records-ல $who $a அப்படியே இருக்கு.",
                tl = "Seri Owner, pudhusa edhuvum add pannala. Records-la $who $a apdiye irukku.",
                en = "Okay Owner, nothing new added. $who's $a stays as it is in the records.")
        }
        // "ama" / "seri" says neither: the two choices again — never a guess.
        if (conversationState.conversationTurn == asked + 1 && (KaiConversationSemantics.confirmsDraft(said) || isNew && isSame)) {
            conversationState.duplicateAskedTurn = conversationState.conversationTurn
            val a = st.amount?.let { KaiFormat.rupees(it.toDouble()) }.orEmpty()
            return say(l, KaiMood.CLARIFY, null, ta = "அதே $a-ஆ, புது $a-ஆ Owner?", tl = "Owner, adhey $a-aa, pudhu $a-aa?", en = "The same $a or a new $a, Owner?")
        }
        // Anything else moves on; the stated amount stays unsaved (asked again on the next save).
        conversationState.duplicateAskedTurn = -1
        return null
    }

    private suspend fun draftStated(st: KaiStatedPayment, lang: KaiLang, said: String): KaiTurn {
        val amount = st.amount ?: run {
            conversationState.pendingQuestion = KaiPendingQuestion.AMOUNT
            conversationState.pendingAskedTurn = conversationState.conversationTurn
            return if (st.direction == KaiConversationPaymentDirection.PAYMENT_OUT) say(lang, KaiMood.CLARIFY, null,
                ta = "${st.person}-க்கு எவ்வளவு கொடுக்கணும் Owner? தொகை இல்லாம சேமிக்க மாட்டேன்.",
                tl = "Owner, ${st.person}-ku evlo kudukkanum? Amount illama save panna maatten.",
                en = "How much do you owe ${st.person}, Owner? I won't save it without the amount.")
            else say(lang, KaiMood.CLARIFY, null,
                ta = "${st.person} கிட்ட எவ்வளவு வாங்கணும் Owner? தொகை இல்லாம சேமிக்க மாட்டேன்.",
                tl = "Owner, ${st.person} kitta evlo vaanganum? Amount illama save panna maatten.",
                en = "How much should you collect from ${st.person}, Owner? I won't save it without the amount.")
        }
        // The date question is answered by the draft now (a date said next goes onto the draft).
        conversationState.pendingQuestion = null
        conversationState.pendingDay = null
        conversationState.pendingMonthOffset = null
        // The very same amount is already in the books for them: the same debt said again, or a new one? Asked, never assumed.
        if (!st.newConfirmed) recordedPending(st)?.takeIf { it.compareTo(amount) == 0 }?.let { return askIfDuplicate(st, amount, lang) }
        val receivable = st.direction == KaiConversationPaymentDirection.PAYMENT_IN
        val kind = if (receivable) PlanKind.CREDIT_GIVEN else PlanKind.DEBIT_TAKEN
        val r = PaymentRequest(newKey(), st.person, amount, outgoing = receivable, mode = PaymentMode.CASH, said = said, dueDate = st.dueDate, stated = true)
        val matches = tools.parties(st.person) ?: return notSaved(lang, "books unavailable")
        // A receivable is a customer's Credit entry, a payable a supplier's Debit entry: only that side's people.
        val sameSide = matches.filter { it.customer == receivable }
        // The record already picked in the conversation ("Nagapattinam Lokesh") — never re-guessed by name.
        st.partyId?.let { id -> sameSide.firstOrNull { it.id == id }?.let { p ->
            st.label?.let { chosenLabels[p.id] = it }
            return prepared(r, kind, p.name, p.id, lang)
        } }
        if (st.newPerson) return prepared(r, kind, st.person, null, lang)
        val exact = KaiEntityResolver.sameName(st.person, sameSide)
        if (exact.size == 1) return prepared(r, kind, exact.single().name, exact.single().id, lang)
        val role = if (receivable) pick(lang, ta = "வாடிக்கையாளர்", tl = "customer", en = "customer") else pick(lang, ta = "சப்ளையர்", tl = "supplier", en = "supplier")
        if (exact.size > 1) {
            when (val one = resolveParty(st.person, "$said ${st.label.orEmpty()}", exact, conversationState.focusPartyId)) {
                is KaiEntityResolver.Result.One -> return prepared(r, kind, one.party.name, one.party.id, lang)
                else -> {}
            }
            requests[r.key] = r
            conversationState.entityChoice = KaiEntityChoice(st.person, exact.take(6), KaiEntityChoice.Purpose.DRAFT, conversationState.lastQuestion ?: said)
            return KaiTurn(
                ChatReply(whichQuestion(st.person, exact.take(6), lang), KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY),
                KaiCard(emptyList(), exact.take(6).map { p -> KaiButton("${labelOf(p)} · ${KaiFormat.rupees(p.balance.toDouble())}", KaiAction.ChoosePlan(r.key, kind, p.id, p.name)) } +
                    KaiButton(cancelLabel(lang), KaiAction.CancelRequest(r.key))),
            )
        }
        requests[r.key] = r
        // "Kumaran" is not "Kumar": a similar name in the books is asked about, never used silently.
        val similar = similarParties(st.person, sameSide, receivable)
        if (similar.isNotEmpty()) {
            val names = similar.joinToString(pick(lang, ta = "-ஆ, ", tl = "-a, ", en = " or ")) { it.name }
            return KaiTurn(
                ChatReply(pick(lang,
                    ta = "ஓனர், ${st.person} தனி $role-ஆ? $names-ஆ?",
                    tl = "Owner, ${st.person}-nu separate $role-aa? $names-a?",
                    en = "Owner, is ${st.person} a separate $role, or $names?"), KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY),
                KaiCard(emptyList(),
                    listOf(KaiButton(pick(lang, ta = "${st.person} — புது $role", tl = "${st.person} — puthu $role", en = "${st.person} — new $role"),
                        KaiAction.ChoosePlan(r.key, kind, null, st.person), primary = true)) +
                        similar.map { p -> KaiButton("${p.name} · ${KaiFormat.rupees(p.balance.toDouble())}", KaiAction.ChoosePlan(r.key, kind, p.id, p.name)) } +
                        KaiButton(cancelLabel(lang), KaiAction.CancelRequest(r.key))),
            )
        }
        requests.remove(r.key)
        // Not in the books yet: the draft says it will be a new customer / supplier.
        return prepared(r, kind, st.person, null, lang)
    }

    /** Customers (or suppliers) whose name contains the stated one or is contained in it: "Kumar" for "Kumaran", "Siva" for "Sivakumar". */
    private suspend fun similarParties(name: String, sameSide: List<PartyMatch>, receivable: Boolean): List<PartyMatch> {
        val n = name.trim().lowercase(Locale.ROOT)
        fun near(other: String): Boolean {
            val o = other.trim().lowercase(Locale.ROOT)
            return o != n && minOf(o.length, n.length) >= 3 && (o.startsWith(n) || n.startsWith(o))
        }
        val fromSearch = sameSide.filter { near(it.name) }
        val people = runCatching { books.snapshot()?.people.orEmpty() }.getOrDefault(emptyList()).filter(::near)
        val fromBooks = people.flatMap { p -> runCatching { tools.parties(p) }.getOrNull().orEmpty() }
            .filter { it.customer == receivable && near(it.name) }
        return (fromSearch + fromBooks).distinctBy { it.id }.take(5)
    }

    /** "next friday tharuvaan", "15th kudukkuren", "naalaikku": a pay date for the open draft — not a reminder or a new entry. */
    /** "5-aam thethi", "5 thethi", "5-am date", "5ம் தேதி": a day of the month said with its ordinal / date word. */
    private val ordinalDay = Regex("""(?i)(?<![\p{L}\p{N}])(\d{1,2})\s*-?\s*(?:aam|am|ம்)?\s*(?:thethi|thedhi|date|தேதி)(?![\p{L}])""")

    private fun promisesPayDate(said: String): Boolean {
        val lower = said.lowercase(Locale.ROOT)
        if (Regex("""(?<![\p{L}])(remind|reminder|ninaivu|nyabagam|call|phone|alarm)(?![\p{L}])|நினைவூட்ட""").containsMatchIn(lower)) return false
        return Regex("""(?<![\p{L}])(tharuv\p{L}*|tharen|tharean|kudupp\p{L}*|kudukkuren|kodukkuren|kudukuren|tharanum|kudukkanum|kudukanum|due|varum|pay\s*pannuv\p{L}*|will\s+pay|pays?)(?![\p{L}])|தருவ|தரேன்|கொடுப்ப|கொடுக்குறேன்""")
            .containsMatchIn(lower) || lower.trim().split(Regex("""\s+""")).size <= 3
    }

    /** The Kai draft for a stated payment, prepared again: a new amount, or the due date said while it is open. */
    private suspend fun redraftStated(plan: ActionPlan, amount: BigDecimal, dueDate: LocalDate?, lang: KaiLang): KaiTurn {
        plans.remove(plan.key)?.let { runCatching { tools.discard(it) } }
        conversationState.stated = conversationState.stated?.copy(amount = amount, dueDate = dueDate)
        val r = PaymentRequest(newKey(), plan.partyName, amount, outgoing = plan.kind == PlanKind.CREDIT_GIVEN, mode = plan.mode, said = plan.said, dueDate = dueDate, stated = true)
        return prepared(r, plan.kind, plan.partyName, plan.partyId, lang)
    }

    /** "add pannitiya?" with a draft still open: not saved — Confirm saves it. */
    private fun notSavedYet(lang: KaiLang): KaiTurn = say(conversationState.pendingLang ?: lang, KaiMood.CLARIFY, null,
        ta = "இன்னும் சேமிக்கல Owner. Confirm பண்ணுங்க, சேமிச்சிடறேன்.",
        tl = "Innum save pannala Owner. Confirm pannunga, save pannidren.",
        en = "Not saved yet, Owner. Confirm it and I'll save it.")

    /** "add pannitiya?" with no draft open: yes only when the books confirmed the save; else the stated payment's draft is shown. */
    private suspend fun savedAnswer(lang: KaiLang): KaiTurn {
        val l = conversationState.pendingLang ?: lang
        conversationState.stated?.takeIf { it.amount != null }?.let { st ->
            val draft = draftStated(st, l, "add pannitiya?")
            return if (draft.plan != null) draft.copy(reply = draft.reply.copy(text = notSavedYet(l).reply.text)) else draft
        }
        conversationState.lastSaved?.let { saved ->
            val a = KaiFormat.rupees(saved.amount.toDouble())
            return say(l, KaiMood.SUCCESS, null,
                ta = "ஆம் Owner, சேர்த்துட்டேன். ${saved.person} — $a (${saved.reference}).",
                tl = "ஆம் Owner, add pannitten. ${saved.person} — $a (${saved.reference}).",
                en = "Yes Owner, it's added. ${saved.person} — $a (${saved.reference}).")
        }
        if (stockPlans.isNotEmpty() || plans.isNotEmpty()) return notSavedYet(l)
        return say(l, KaiMood.NEUTRAL, null,
            ta = "ஓனர், இன்னும் எதுவும் சேமிக்கல.", tl = "Owner, innum edhuvum save pannala.", en = "Owner, nothing has been saved yet.")
    }

    /** The books did not take it: said plainly — the amount is not in the books. */
    private fun notSaved(lang: KaiLang, reason: String): KaiTurn {
        tools.log("payment", "transaction engine", "not saved: $reason", ActionStatus.FAILED)
        return say(lang, KaiMood.ERROR, null,
            ta = "ஓனர், சேமிக்க முடியல. நான் தொகையை சேமிக்கல.",
            tl = "Owner, save aagala. Naan amount-a save pannala.",
            en = "Owner, it wasn't saved. I did not save the amount.")
    }

    /**
     * "pakkathula hardware kadai irukka?", "supermarket enga irukku?": finding a place nearby. Kai has no
     * nearby-search, so it says so plainly — never a ledger answer, never an invented shop.
     */
    private fun localDiscovery(place: KaiLocalDiscovery.Place, lang: KaiLang, said: String): KaiTurn {
        tools.log("local discovery", "none", "nearby ${place.english}: not available", ActionStatus.ANSWERED, null, said)
        conversationState.currentIntent = "LOCAL_BUSINESS_DISCOVERY"
        return say(lang, KaiMood.NEUTRAL, null,
            ta = "Owner, பக்கத்துல இருக்கிற ${place.tamil} தேடிக் குடுக்கிற வசதி Kai-க்கு இன்னும் இல்ல. Google Maps-ல “${place.english} near me”-னு தேடுங்க. நான் கடை பேர் எதுவும் ஊகிச்சு சொல்ல மாட்டேன்.",
            tl = "Owner, pakkathula irukka ${place.tanglish} thedi kudukkura vasathi Kai-kku innum illa. Google Maps-la “${place.english} near me”-nu thedunga. Naan kadai per edhuvum guess panni solla maatten.",
            en = "Owner, I can't search for nearby places yet. Try “${place.english} near me” in Google Maps — I won't guess shop names.")
    }

    /** Resolve a stock pronoun against the last product mentioned, while reading quantity only from inventory. */
    private suspend fun contextualProductFollowUp(text: String, rawLang: KaiLang): KaiTurn? {
        val name = conversationState.lastProduct ?: return null
        val lang = KaiConversationSemantics.phraseLang(text, rawLang)
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

    private val modeOnly = Regex("""(?i)^[\p{L}][\p{L}\s.'-]{1,40}?\s+(?:₹|rs\.?\s*)?\d[\d,]*(?:\.\d+)?\s*(?:rs|rupees|ruba|rubai)?\s*(?:gpay|g\s*pay|upi|phonepe|phone\s*pe|paytm|cash|online|bank)\s*[.!]?$""")
    private val giveVerb = Regex("""(?i)(kuduth|koduth|kudupp|thandh|thanth|anupp|anup|katt|vaang|vang|pann|vandh|vanth|pay|gpay|settle|clear)\p{L}*""")
    private val creditWord = Regex("""(?i)(?<![\p{L}])(credit|kadan|kadana|kadanaa|udhaar|udhar)(?![\p{L}])|கடன்""")
    private val questionLike = Regex("""(?i)(?<![\p{L}])(evlo|evvalavu|eppo|eppa|yaar|yaaru|enna|how|what|when|who|ah|aa)(?![\p{L}])|\?""")

    /** "Kumar phone number", "Kumar number enna", "Ramesh mobile": the number on that record (or that the books have none). */
    private suspend fun phoneNumber(text: String, people: List<String>, lang: KaiLang): KaiTurn? {
        if (!Regex("""(?i)(?<![\p{L}])(phone\s*(?:number|no|num)|mobile|number|contact|cell\s*number)(?![\p{L}])|நம்பர்|போன் நம்பர்""").containsMatchIn(text)) return null
        if (Regex("""(?i)(?<![\p{L}])(call|remind|reminder|save|add|maathu|change|update)(?![\p{L}])""").containsMatchIn(text)) return null
        val person = com.shopai.app.brain.KaiUnderstanding.knownPerson(text, people) ?: return null
        val matches = runCatching { tools.parties(person) }.getOrNull()?.let { KaiEntityResolver.sameName(person, it) }?.distinctBy { it.id } ?: return null
        val p = matches.singleOrNull() ?: return null
        val phone = p.phone?.takeIf { it.isNotBlank() }
        return if (phone != null) say(lang, KaiMood.EXPLAINING, null,
            ta = "ஓனர், ${p.name} நம்பர்: $phone.", tl = "Owner, ${p.name} number: $phone.", en = "Owner, ${p.name}'s number is $phone.")
        else say(lang, KaiMood.CLARIFY, null,
            ta = "ஓனர், ${p.name}-க்கு phone number records-ல இல்ல.", tl = "Owner, ${p.name}-ku phone number records-la illa.", en = "Owner, there's no phone number for ${p.name} in your records.")
    }

    private fun isAmountAddRequest(text: String): Boolean {
        val n = text.lowercase(Locale.ROOT)
        // "add pannu" and how it is said: "add panniko", "add pannikko", "add pannunga", "add pannidu" (owner's phone, 12 Oct 2026).
        return Regex("(?i)\\b(amount\\s+add|add\\s+(?:the\\s+)?amount|amount\\s+add\\s+pannu|add\\s+(?:pannu|panniko|pannikko|pannunga|pannidu|pannikonga|pannikkonga)|account[- ]?la\\s+podu|account[- ]?la\\s+add|record\\s+(?:this|it))\\b|தொகை.*சேர்|கணக்கில்.*போடு")
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
        // "rate evlo" right after "Colgate evlo irukku": Colgate's rate.
        productTalkTurn = conversationState.conversationTurn
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

    private val stockSaid = Regex("""(?i)(?<![\p{L}])(stock|inventory|iruppu|saamaan|saman|maal|items?|pieces?|pcs|kg|kilo|box|packet)(?![\p{L}])|இருப்பு|ஸ்டாக்""")

    /** "2 TV vaanginen", "frog 5 vaangi irukken": goods the owner bought — stock in (with no money said and no one named). */
    private val boughtGoods = Regex("""(?i)(?<![\p{L}])(?:vaang|vang)(?:inen|ineen|itten|iten|itaen|i\s*irukk?en|i\s*irukk?een|irukk?en|iyachu|iyaachu|unen|unaen|unan|inan|anen|i\s*i?rukk?aa?n|i\s*i?rukk?aanga|i\s*i?rukk?anga|iirukk?en)(?![\p{L}])""")
    private val moneySaid = Regex("""(?i)₹|(?<![\p{L}])(rs|rupees?|rupa|ruba|rubai|roobai|amount|panam|kaasu|cash|gpay|upi|aayiram|payment|kadan|credit)(?![\p{L}])""")

    private suspend fun stockChange(raw: String, said: String, lang: KaiLang, products: List<com.shopai.app.brain.tools.ProductRef>, people: List<String>): KaiTurn? {
        val text = if (boughtGoods.containsMatchIn(raw) && !moneySaid.containsMatchIn(raw) && KaiCommands.personIn(raw, people) == null &&
            KaiEntityResolver.peopleIn(raw, people).isEmpty()) boughtGoods.replace(raw, "vandhuchu") else raw
        val direct = com.shopai.app.brain.tools.KaiStock.understand(text, products)
        // "athula 5 pochu" after talking about Colgate: "athula" is Colgate, not a new product — only when no product is named.
        val referred = if (direct?.product != null) null else lastProduct
            ?.takeIf { KaiCommands.personIn(text, people) == null && productReference.containsMatchIn(text) }
            ?.let { p -> com.shopai.app.brain.tools.KaiStock.understand(productReference.replace(text, p.name), products)?.takeIf { it.product?.id == p.id } }
        val req = referred ?: direct
            // "20 add pannu" after talking about Colgate — never when someone else is named (that is a payment).
            ?: lastProduct?.takeIf { KaiCommands.personIn(text, people) == null }
                ?.let { p -> com.shopai.app.brain.tools.KaiStock.understand("${p.name} $text", products)?.takeIf { it.product?.id == p.id } }
            ?: return null
        // "Kumar kitta irundhu 500 vandhuchu", "Received 500 from Kumar": a person in the books, money with no stock word
        // and no unit — that is money coming in, for the payment path (not a product called "Kumar Kitta Irundhu").
        if (req.product == null && namesPerson(text, req.spokenName, people) && req.unit == null && !stockSaid.containsMatchIn(text)) return null
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
        // "Colgate 5" with Colgate kept in pieces and boxes: which one? (never taken as pieces by itself)
        if (product.conversions.isNotEmpty() && parts.size == 1 && parts.single().unit == null &&
            text.lowercase(Locale.ROOT).split(Regex("""[\s,.!?]+""")).none { com.shopai.app.brain.tools.KaiInventory.unitOf(it) != null })
            return askUnitPick(product, parts.single().qty, req.incoming, said, lang)
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
        // A new product: added right here in the chat — Kai asks only what is missing, one detail at a time (no camera on its own).
        val line = com.shopai.app.brain.tools.KaiInventory.stockLine(said)
        val spokenWords = req.spokenName.lowercase(Locale.ROOT).split(' ').toSet()
        val name = line?.first?.split(' ')?.filter { it.lowercase(Locale.ROOT) in spokenWords }?.joinToString(" ")?.takeIf { it.isNotBlank() } ?: req.spokenName
        val unit = com.shopai.app.brain.tools.KaiUnits.canon(req.parts.firstOrNull()?.unit ?: req.unit)
        val qty = req.parts.firstOrNull()?.qty ?: req.qty
        // "5inch tape 10": the size said in the name is the product's size too (Kai doesn't ask it again).
        val spec = com.shopai.app.brain.tools.ItemSpec(name, com.shopai.app.brain.tools.KaiInventory.kindOf(name, unit), qty, unit, size = req.size)
        tools.log(com.shopai.app.brain.tools.KaiIntents.STOCK_IN, "kai", "$name: new product by chat", ActionStatus.ANSWERED, null, said)
        return itemStep(newKey(), com.shopai.app.brain.tools.KaiInventory.withDetails(spec, said), said, lang)
    }

    private val photoAsked = Regex("""(?i)(?<![\p{L}])(photo|foto|camera|cam|pic|picture|scan|click)(?![\p{L}])|போட்டோ|கேமரா""")

    /** "new stock add pannu": which product and how much? (a photo is a button, never opened by itself). */
    private fun askStockLine(said: String, lang: KaiLang): KaiTurn {
        stockLineAskedTurn = conversationState.conversationTurn
        tools.log(com.shopai.app.brain.tools.KaiIntents.STOCK_IN, "kai", "which product asked", ActionStatus.ANSWERED, null, said)
        return KaiTurn(
            ChatReply(pick(lang,
                ta = "சரி Owner 👍 என்ன product, எவ்வளவு வந்திருக்கு? (உதா: Colgate 5 box)",
                tl = "Seri Owner 👍 Enna product, evlo vandhirukku? (eg: Colgate 5 box)",
                en = "Sure Owner 👍 Which product, and how much came in? (e.g. Colgate 5 boxes)"), KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(emptyList(), listOf(
                KaiButton(pick(lang, ta = "📷 போட்டோ", tl = "📷 Photo", en = "📷 Photo"), KaiAction.OpenStockCamera(StockPrefill("", null, null, said))),
                KaiButton(cancelLabel(lang), KaiAction.CancelStock("ask")),
            )),
        )
    }

    /**
     * "Colgate 5 box, boxக்கு 48 pieces, piece 200 gram, purchase 28, selling 35" (or "Colgate 5 box" right after Kai asked
     * which product): a known product gets its stock draft; a new one goes to the chat entry with every detail already said.
     */
    private suspend fun newStockLine(text: String, said: String, lang: KaiLang, products: List<com.shopai.app.brain.tools.ProductRef>, people: List<String>): KaiTurn? {
        val (name, qty, unit) = com.shopai.app.brain.tools.KaiInventory.stockLine(text) ?: return null
        // "adhu 1 box vandhirukku" right after Colgate: "adhu" is Colgate (the stock path below), never a new product called "Adhu".
        if (lastProduct != null && productReference.matches(name.trim())) return null
        val asked = conversationState.conversationTurn - stockLineAskedTurn <= 2
        if (unit == null && !asked) return null
        // A customer / supplier in the books named here makes it money, not stock ("boxக்கு" is a unit, not a person).
        if (com.shopai.app.brain.KaiUnderstanding.knownPerson(text, people) != null || namesPerson(text, name, people)) return null
        val kind = com.shopai.app.brain.tools.KaiInventory.kindOf(name, unit)
        val spec = com.shopai.app.brain.tools.ItemSpec(name, kind, qty, unit)
        val product = products.firstOrNull { KaiPrivateMemory.normalize(it.name) == KaiPrivateMemory.normalize(name) }
            // "அரிசி 2 மூட்டை வந்தது" with Rice in the shop: the product by its Tamil name / sound / the one name it starts.
            ?: com.shopai.app.brain.tools.KaiStock.productNamedIn(name, products)
        // "Moong dal 3 moota" — only a new product's name, a quantity and a unit: it can only be stock coming in, so Kai starts
        // asking (nothing is saved before Confirm). For a product the books have, in or out isn't said, so it is not taken here.
        // "Rice actual 255 kg" names Rice, which the books have — a count, not a new product called "Rice Actual".
        val namesKnown = products.any { p -> p.name.isNotBlank() && Regex("""(?i)(?<![\p{L}])${Regex.escape(p.name)}(?![\p{L}])""").containsMatchIn(text) }
        // "500 kattu" at the end is "pay 500" (kattu = pay / bundle): with no stock word it is never taken as a bundle.
        val endsWithPay = Regex("""(?i)(?:kattu|கட்டு)\s*[.!]?$""").containsMatchIn(text.trim())
        val bareNewLine = product == null && !namesKnown && unit != null && !endsWithPay &&
            !com.shopai.app.brain.tools.KaiStock.moneyOrQuestion(text) && bareStockLine.matches(text.trim())
        if (!asked && !bareNewLine && !com.shopai.app.brain.tools.KaiInventory.hasDetails(text, spec)) return null
        stockLineAskedTurn = -10L
        if (product != null) {
            // A product the books have: its own units and prices — nothing asked again. A box size said now is used for this draft.
            val detailed = com.shopai.app.brain.tools.KaiInventory.withDetails(spec, text)
            // "packetku 500 gram" for Rava kept in kg: 0.5 kg a packet — the pack size in the product's own unit, never 500 kg.
            val perPack = detailed.perPack?.let { com.shopai.app.brain.tools.KaiUnits.inProductUnit(product, it, detailed.inner) }
            if (unit != null && perPack != null && com.shopai.app.brain.tools.KaiUnits.factor(product, unit) == null)
                sessionUnits.getOrPut(product.id) { HashMap() }[unit] = perPack
            lastProduct = product
            return draftParts(product, listOf(com.shopai.app.brain.tools.QtyPart(qty, unit)), true, said, lang)
        }
        tools.log(com.shopai.app.brain.tools.KaiIntents.STOCK_IN, "kai", "$name: new product by chat", ActionStatus.ANSWERED, null, said)
        return itemStep(newKey(), com.shopai.app.brain.tools.KaiInventory.withDetails(spec, text), said, lang)
    }

    /** "Cinthol soap add pannu", "pudhu product Hamam soap serthudu": a product name and "add" — no quantity, no money. */
    private val addOnly = Regex("""(?i)^(?:(?:pudhu|puthu|new)\s+(?:product|item|porul)\s+)?([\p{L}\p{M}][\p{L}\p{M}\s.'-]{1,40}?)\s+(?:stock\s+)?(?:add|serthu|sethu|serthudu|sethudu|serthuko|sethuko|serthukko)(?:\s*(?:pannu|panniko|pannikko|pannunga|pannidu|pannudu|pannikonga|pannikkonga|pannu\s+da))?\s*[.!]?$""")
    private val quantityWords = setOf("oru", "onnu", "rendu", "moonu", "naalu", "anju", "aaru", "ezhu", "ettu", "ombadhu", "pathu", "konjam", "half", "arai")
    private val notAProduct = Regex("""(?i)^(?:adhu|athu|idhu|ithu|adha|idha|itha|atha|stock|product|item|porul|ellam|ellaam|pudhu|puthu|new|innum|innoru|kanakku|entry|payment|reminder|bill|customer|supplier)$""")

    /**
     * "Cinthol soap add pannu" (owner's phone, 12 Oct 2026: answered with everyone's pending total): a new product named with no
     * quantity — Kai asks how much came in and goes on as for "Cinthol soap 50" (nothing saved before Confirm). A product the
     * shop has, a person, money or a question is not taken here.
     */
    private suspend fun newProductAdd(text: String, said: String, lang: KaiLang, products: List<com.shopai.app.brain.tools.ProductRef>, people: List<String>): KaiTurn? {
        val name = addOnly.find(text.trim())?.groupValues?.get(1)?.trim() ?: return null
        if (name.any(Char::isDigit) || name.split(Regex("""\s+""")).size > 4 || notAProduct.matches(name)) return null
        // "Colgate anju box add pannu", "Pepsodent stock vandhiruku": a quantity or a stock word is said — the stock path's line.
        if (name.lowercase(Locale.ROOT).split(Regex("""\s+""")).any { w -> com.shopai.app.brain.tools.KaiStock.unitOf(w) != null || w in quantityWords ||
                com.shopai.app.brain.tools.KaiStock.isStockWord(w) }) return null
        if (com.shopai.app.brain.tools.KaiStock.moneyOrQuestion(text)) return null
        if (com.shopai.app.brain.KaiUnderstanding.knownPerson(text, people) != null || namesPerson(text, name, people)) return null
        if (lastProduct != null && productReference.matches(name)) return null
        if (products.any { KaiPrivateMemory.normalize(it.name) == KaiPrivateMemory.normalize(name) } ||
            com.shopai.app.brain.tools.KaiStock.productNamedIn(name, products) != null) return null
        val shown = com.shopai.app.brain.tools.KaiInventory.display(name)
        val spec = com.shopai.app.brain.tools.ItemSpec(shown, com.shopai.app.brain.tools.KaiInventory.kindOf(shown, null), null, null)
        tools.log(com.shopai.app.brain.tools.KaiIntents.STOCK_IN, "kai", "$shown: new product by chat (quantity asked)", ActionStatus.ANSWERED, null, said)
        return itemStep(newKey(), spec, said, lang)
    }

    private val bareStockLine = Regex("""^[\p{L}\p{M}][\p{L}\p{M}\s.'-]{1,40}?\s+\d+(?:\.\d+)?\s*[\p{L}\p{M}]+\s*[.!]?$""")

    /** The next question for a new product — or, when nothing is missing, the summary with Confirm. Nothing is saved here. */
    private fun itemStep(key: String, spec: com.shopai.app.brain.tools.ItemSpec, said: String, lang: KaiLang): KaiTurn {
        val inv = com.shopai.app.brain.tools.KaiInventory
        val field = inv.next(spec)
        newItem = NewItem(key, spec, said, field, conversationState.conversationTurn, lang)
        if (field != null) return KaiTurn(
            ChatReply(inv.question(spec, field, lang), KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(emptyList(), listOf(KaiButton(cancelLabel(lang), KaiAction.CancelStock(key)))),
        )
        val t = inv.totals(spec) ?: run {
            newItem = null
            return say(lang, KaiMood.CLARIFY, null, ta = "ஓனர், அளவு புரியல — மறுபடி சொல்லுங்க.", tl = "Owner, alavu puriyala — thirumba sollunga.", en = "Owner, I couldn't work out the quantity — please say it again.")
        }
        lastDraft = said to "STOCK"
        tools.log(com.shopai.app.brain.tools.KaiIntents.STOCK_IN, "draft", "${spec.name} ${inv.shown(t.baseQty, t.baseUnit)} new product draft=$key", ActionStatus.DRAFT, null, said)
        return KaiTurn(
            ChatReply(inv.summaryText(spec, t, lang), KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(listOf(spec.name) + inv.summaryLines(spec, t, lang), listOf(
                KaiButton(pick(lang, ta = "உறுதி செய்", tl = "Confirm", en = "Confirm"), KaiAction.ConfirmStock(key), primary = true),
                KaiButton(cancelLabel(lang), KaiAction.CancelStock(key)),
            )),
        )
    }

    private val oldThenNew = Regex("""(?i)^\s*(\d+(?:\.\d+)?)\s*([\p{L}]+)?\s*(?:illa|illai|no|இல்ல)[\s,]+(\d+(?:\.\d+)?)\s*([\p{L}]+)?\s*(?:dhaan|than|thaan|dhan|தான்)?\s*[.!]?\s*$""")
    private val itemCorrection = Regex("""(?i)^\s*(?:(?:illa|illai|no|இல்ல)[\s,]+)?(\d+(?:\.\d+)?)\s*([\p{L}]+)?\s*(?:dhaan|than|thaan|dhan|தான்|only|mattum)\s*[.!]?\s*$|^\s*(?:illa|illai|no|இல்ல)[\s,]+(\d+(?:\.\d+)?)\s*([\p{L}]+)?\s*[.!]?\s*$""")

    /** The owner's reply while a new product is open: an answer, a correction, Confirm / cancel — null when it is something else. */
    private suspend fun newItemAnswer(said: String, lang: KaiLang): KaiTurn? {
        val item = newItem ?: return null
        // An answer comes right after the question; much later the entry is let go (nothing was saved).
        if (conversationState.conversationTurn - item.turn > 3) { newItem = null; return null }
        val inv = com.shopai.app.brain.tools.KaiInventory
        val text = com.shopai.app.brain.tools.KaiSpokenWords.normalize(said)
        val l = item.lang
        // "cancel" ends it; "venam" / "illa" ends it too — except where it means "skip this detail" (size / rates are optional).
        val optional = item.asked in setOf(com.shopai.app.brain.tools.ItemField.SIZE, com.shopai.app.brain.tools.ItemField.BATCH,
            com.shopai.app.brain.tools.ItemField.EXPIRY, com.shopai.app.brain.tools.ItemField.PURCHASE, com.shopai.app.brain.tools.ItemField.SELLING)
        if (Regex("""(?i)(?<![\p{L}])cancel(?![\p{L}])|ரத்து""").containsMatchIn(said) || (KaiConversationSemantics.cancelsDraft(said) && !optional)) {
            return act(KaiAction.CancelStock(item.key), l)
        }
        // "illa 3 box dhaan": the quantity is corrected — the same entry, never a second one.
        itemCorrection.find(text.trim())?.let { m ->
            val q = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@let
            val u = (m.groupValues[2].ifEmpty { m.groupValues[4] }).takeIf { it.isNotEmpty() }?.let(inv::unitOf)
            if (item.asked == com.shopai.app.brain.tools.ItemField.PER_PACK && u == null && !Regex("""(?i)illa|illai|no|இல்ல""").containsMatchIn(text)) return@let
            return itemStep(item.key, item.spec.copy(qty = q, unit = u ?: item.spec.unit), item.said, l)
        }
        val asked = item.asked
        if (asked == null) {
            // The summary is on screen: "aama" / "seri" / "add pannu" saves it; a detail said now updates the summary.
            if (said.none(Char::isDigit) && (KaiConversationSemantics.confirmsDraft(said) || KaiConversationSemantics.onlySave(said))) return confirmNewItem(item.key, l)
            val more = inv.withDetails(item.spec, text)
            return if (more != item.spec) itemStep(item.key, more, item.said, l) else null
        }
        val answered = inv.answer(item.spec, asked, text, now().toLocalDate()) ?: return null
        return itemStep(item.key, inv.withDetails(answered, text).let { if (asked == com.shopai.app.brain.tools.ItemField.PURCHASE || asked == com.shopai.app.brain.tools.ItemField.SELLING) answered else it }, item.said, l)
    }

    /**
     * "illa 3 box dhaan" / "3 box dhaan" right after a stock draft on a product the books have: the quantity is corrected in the
     * same draft (same key, nothing written) — never a second entry. The unit said first is kept when none is said now.
     */
    /** "venam" / "cancel" / "வேண்டாம்" right after a stock draft: the draft is dropped and nothing is saved. */
    private suspend fun stockDraftCancel(said: String, lang: KaiLang): KaiTurn? {
        val last = lastDraft?.takeIf { it.second == "STOCK" } ?: return null
        val plan = stockPlans.values.lastOrNull { it.said == last.first } ?: return null
        if (!KaiConversationSemantics.cancelsDraft(said)) return null
        lastDraft = null
        return act(KaiAction.CancelStock(plan.key), lang)
    }

    private fun stockDraftCorrection(said: String, lang: KaiLang): KaiTurn? {
        val last = lastDraft?.takeIf { it.second == "STOCK" } ?: return null
        val plan = stockPlans.values.lastOrNull { it.said == last.first } ?: return null
        val text = com.shopai.app.brain.tools.KaiSpokenWords.normalize(said).trim()
        // "10 illa 15": the old quantity, then the new one (owner, 12 Oct 2026).
        oldThenNew.find(text)?.let { m ->
            val q = m.groupValues[3].toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
            val unit = (m.groupValues[4].ifEmpty { m.groupValues[2] }).takeIf { it.isNotEmpty() }?.let(com.shopai.app.brain.tools.KaiInventory::unitOf)
                ?: plan.conv?.input?.singleOrNull()?.unit ?: plan.unit
            stockPlans.remove(plan.key)
            return draftParts(plan.product, listOf(com.shopai.app.brain.tools.QtyPart(q, unit)), plan.incoming, plan.said, lang, plan.key)
        }
        val m = itemCorrection.find(text) ?: return null
        val q = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val unit = (m.groupValues[2].ifEmpty { m.groupValues[4] }).takeIf { it.isNotEmpty() }?.let(com.shopai.app.brain.tools.KaiInventory::unitOf)
            ?: plan.conv?.input?.singleOrNull()?.unit ?: plan.unit
        stockPlans.remove(plan.key)
        return draftParts(plan.product, listOf(com.shopai.app.brain.tools.QtyPart(q, unit)), plan.incoming, plan.said, lang, plan.key)
    }

    /**
     * Confirm on a new product's summary: the product (units, conversion, purchase and selling price) and its opening stock
     * are saved in ONE write — then read back from the books. A product with that name already in the books gets a normal
     * stock draft instead (never a second copy).
     */
    private suspend fun confirmNewItem(key: String, lang: KaiLang): KaiTurn? {
        val item = newItem?.takeIf { it.key == key } ?: return null
        newItem = null
        val inv = com.shopai.app.brain.tools.KaiInventory
        val spec = item.spec
        val t = inv.totals(spec) ?: return null
        val existing = runCatching { tools.products() }.getOrNull()?.firstOrNull { KaiPrivateMemory.normalize(it.name) == KaiPrivateMemory.normalize(spec.name) }
        if (existing != null) {
            val u = spec.unit
            val perPack = spec.perPack?.let { com.shopai.app.brain.tools.KaiUnits.inProductUnit(existing, it, spec.inner) }
            if (u != null && perPack != null && com.shopai.app.brain.tools.KaiUnits.factor(existing, u) == null)
                sessionUnits.getOrPut(existing.id) { HashMap() }[u] = perPack
            return draftParts(existing, listOf(com.shopai.app.brain.tools.QtyPart(spec.qty!!, u)), true, item.said, lang)
        }
        val pack = com.shopai.app.brain.tools.KaiUnits.canon(spec.unit)?.takeIf { it != t.baseUnit && spec.perPack != null }
        val created = tools.createProduct(com.shopai.app.brain.tools.NewProduct(
            name = spec.name, category = spec.kind.category, unit = t.baseUnit,
            weight = spec.size?.takeIf { spec.sizeQty != null }, packSize = spec.size?.takeIf { spec.sizeQty == null },
            secondaryUnit = pack, perSecondary = pack?.let { spec.perPack },
            purchasePrice = t.purchasePerBase, sellingPrice = t.sellingPerBase, openingQty = t.baseQty,
            batchNo = spec.batch, expiry = spec.expiry,
        )) ?: run {
            tools.log(com.shopai.app.brain.tools.KaiIntents.CREATE_PRODUCT, "inventory engine", "${spec.name} not created", ActionStatus.FAILED, null, item.said)
            return say(lang, KaiMood.ERROR, null,
                ta = "ஓனர், ${spec.name} சேமிக்க முடியல — எதுவும் சேமிக்கல.", tl = "Owner, ${spec.name} save aagala — edhuvum save pannala.",
                en = "Owner, ${spec.name} couldn't be saved — nothing was saved.")
        }
        // Read back: the stock the books now hold for it.
        val now = runCatching { tools.products() }.getOrNull()?.firstOrNull { it.id == created.id }?.stock ?: created.stock
        lastProduct = created.copy(stock = now)
        tools.log(com.shopai.app.brain.tools.KaiIntents.CREATE_PRODUCT, "inventory engine", "${created.name} created", ActionStatus.CONFIRMED, null, item.said)
        val said = inv.shown(spec.qty!!, spec.unit)
        val total = inv.shown(t.baseQty, t.baseUnit)
        if (now.compareTo(t.baseQty) != 0) {
            tools.log(com.shopai.app.brain.tools.KaiIntents.STOCK_IN, "inventory engine", "${created.name}: stock read back ${now.toPlainString()} ≠ ${t.baseQty.toPlainString()}", ActionStatus.FAILED, null, item.said)
            return say(lang, KaiMood.CONCERNED, null,
                ta = "ஓனர், ${created.name} product சேர்ந்துச்சு, ஆனா stock ${inv.shown(now, t.baseUnit)} தான் காட்டுது — Inventory-ல ஒரு தடவை பாருங்க.",
                tl = "Owner, ${created.name} product create aachu, aana stock ${inv.shown(now, t.baseUnit)} dhaan kaatudhu — Inventory-la oru thadava check pannunga.",
                en = "Owner, ${created.name} was created, but its stock shows ${inv.shown(now, t.baseUnit)} — please check it in Inventory.")
        }
        tools.log(com.shopai.app.brain.tools.KaiIntents.STOCK_IN, "inventory engine", "${created.name} +$total (opening) saved", ActionStatus.CONFIRMED, null, item.said)
        val qtyText = if (said == total) said else "$said ($total)"
        val done = say(lang, KaiMood.SUCCESS, null,
            ta = "சரி ஓனர் ✅ ${created.name} — $qtyText stock-ல சேர்த்துட்டேன். இப்போ stock $total.",
            tl = "Done Owner ✅ ${created.name} — $qtyText stock-la add panniten. Ippo stock $total.",
            en = "Done Owner ✅ ${created.name} — $qtyText added to stock. Stock now $total.")
        // The new product is in Inventory / Stock now: one tap to see it there.
        return done.copy(card = KaiCard(emptyList(), listOf(
            KaiButton(pick(lang, ta = "📦 Inventory-ல பாருங்க", tl = "📦 Inventory-la paarunga", en = "📦 See it in Inventory"), KaiAction.OpenRecord("INVENTORY")),
        )))
    }

    // ------------------------------------------------------------ inventory by chat: edits, details, summary, bills

    private fun productIn(text: String, products: List<com.shopai.app.brain.tools.ProductRef>) = products.filter { it.name.isNotBlank() }
        .sortedByDescending { it.name.length }
        .firstOrNull { Regex("""(?i)(?<![\p{L}])${Regex.escape(it.name)}(?![\p{L}])""").containsMatchIn(text) }

    private suspend fun inventoryChat(text: String, said: String, lang: KaiLang, products: List<com.shopai.app.brain.tools.ProductRef>, people: List<String>): KaiTurn? {
        val inv = com.shopai.app.brain.tools.KaiInventory
        if (inv.asksSummary(text)) return inventorySummary(products, lang, list = false)
        if (inv.asksList(text)) return inventorySummary(products, lang, list = true)
        val product = productIn(text, products) ?: return null
        // Goods with a customer / supplier of the books: one bill (ledger + stock together).
        stockBillLine(text, said, lang, product, people)?.let { return it }
        return when (val ask = inv.productAsk(text)) {
            null -> null
            com.shopai.app.brain.tools.KaiInventory.ProductAsk.Details -> productDetails(product, lang)
            is com.shopai.app.brain.tools.KaiInventory.ProductAsk.Rate -> productRate(product, ask.kind, lang)
            is com.shopai.app.brain.tools.KaiInventory.ProductAsk.Price -> {
                val f = com.shopai.app.brain.tools.KaiUnits.factor(product, ask.per, sessionUnits[product.id].orEmpty()) ?: return null
                val perBase = ask.price.divide(f, 4, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
                val change = if (ask.selling) com.shopai.app.brain.tools.ProductChange(sellingPrice = perBase) else com.shopai.app.brain.tools.ProductChange(purchasePrice = perBase)
                val old = if (ask.selling) product.sellingPrice else product.purchasePrice
                val what = if (ask.selling) "selling price" else "purchase rate"
                val per = com.shopai.app.brain.tools.KaiInventory.one(ask.per ?: product.unit)
                val newText = "${inv.rupees(ask.price)}/$per" + (if (ask.per != null && com.shopai.app.brain.tools.KaiUnits.canon(ask.per) != com.shopai.app.brain.tools.KaiUnits.canon(product.unit)) " (${inv.rupees(perBase)}/${inv.one(product.unit)})" else "")
                val oldText = old?.let { "${inv.rupees(it)}/${inv.one(product.unit)} → " }.orEmpty()
                editDraft(product, change, said, lang,
                    ta = "${product.name} $what: $oldText$newText — மாத்தவா?",
                    tl = "${product.name} $what: $oldText$newText — maathava?",
                    en = "${product.name} $what: $oldText$newText — change it?")
            }
            is com.shopai.app.brain.tools.KaiInventory.ProductAsk.Minimum -> {
                val f = com.shopai.app.brain.tools.KaiUnits.factor(product, ask.unit, sessionUnits[product.id].orEmpty()) ?: return null
                val base = com.shopai.app.brain.tools.KaiUnits.clean(ask.qty.multiply(f))
                val shown = inv.shown(ask.qty, ask.unit ?: product.unit) + (if (ask.unit != null && com.shopai.app.brain.tools.KaiUnits.canon(ask.unit) != com.shopai.app.brain.tools.KaiUnits.canon(product.unit)) " (${inv.shown(base, product.unit)})" else "")
                editDraft(product, com.shopai.app.brain.tools.ProductChange(minStock = base), said, lang,
                    ta = "${product.name} minimum stock: $shown. அதுக்கு கீழ போனா low stock-னு சொல்லுவேன் — set பண்ணவா?",
                    tl = "${product.name} minimum stock: $shown. Adhukku keezha pona low stock-nu solluven — set pannava?",
                    en = "${product.name} minimum stock: $shown. I'll warn you when it goes at or below that — set it?")
            }
        }
    }

    private fun editDraft(product: com.shopai.app.brain.tools.ProductRef, change: com.shopai.app.brain.tools.ProductChange, said: String, lang: KaiLang, ta: String, tl: String, en: String): KaiTurn {
        val key = newKey()
        productEdit = ProductEdit(key, product, change, said, lang, conversationState.conversationTurn)
        tools.log("inventory edit", "draft", "${product.name}: $change", ActionStatus.DRAFT, null, said)
        return KaiTurn(
            ChatReply(pick(lang, ta = ta, tl = tl, en = en), KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(emptyList(), listOf(
                KaiButton(pick(lang, ta = "உறுதி செய்", tl = "Confirm", en = "Confirm"), KaiAction.ConfirmStock(key), primary = true),
                KaiButton(cancelLabel(lang), KaiAction.CancelStock(key)),
            )),
        )
    }

    private suspend fun productEditAnswer(said: String): KaiTurn? {
        val e = productEdit ?: return null
        if (conversationState.conversationTurn - e.turn > 2) { productEdit = null; return null }
        if (KaiConversationSemantics.cancelsDraft(said)) return act(KaiAction.CancelStock(e.key), e.lang)
        if (said.none(Char::isDigit) && (KaiConversationSemantics.confirmsDraft(said) || KaiConversationSemantics.onlySave(said))) return applyProductEdit(e.key)
        return null
    }

    private suspend fun applyProductEdit(key: String): KaiTurn? {
        val e = productEdit?.takeIf { it.key == key } ?: return null
        productEdit = null
        return when (val r = tools.updateProduct(e.product.id, e.change)) {
            is ActionOutcome.Done -> {
                tools.log("inventory edit", "inventory engine", "${e.product.name}: ${e.change}", ActionStatus.CONFIRMED, null, e.said)
                lastProduct = runCatching { tools.products() }.getOrNull()?.firstOrNull { it.id == e.product.id } ?: e.product
                say(e.lang, KaiMood.SUCCESS, null,
                    ta = "சரி ஓனர் ✅ ${e.product.name} மாத்திட்டேன்.", tl = "Done Owner ✅ ${e.product.name} update panniten.", en = "Done Owner ✅ ${e.product.name} is updated.")
            }
            is ActionOutcome.Failed -> {
                tools.log("inventory edit", "inventory engine", r.reason, ActionStatus.FAILED, null, e.said)
                say(e.lang, KaiMood.ERROR, null, ta = "மாத்த முடியல ஓனர்: ${r.reason}", tl = "Owner, update aagala: ${r.reason}", en = "It wasn't updated, Owner: ${r.reason}")
            }
        }
    }

    /**
     * "Rice purchase rate enna?", "Oil selling price?", "Colgate evlo labam?": the product's own prices from the books, per its
     * stock unit. A price the books don't hold is said plainly (and how to set it) — never guessed, never another figure.
     */
    private suspend fun productRate(p: com.shopai.app.brain.tools.ProductRef, kind: com.shopai.app.brain.tools.KaiInventory.RateKind, lang: KaiLang): KaiTurn {
        val inv = com.shopai.app.brain.tools.KaiInventory
        lastProduct = p
        conversationState.lastProduct = p.name; productTalkTurn = conversationState.conversationTurn
        val per = inv.one(p.unit)
        val buy = p.purchasePrice?.let { "${inv.rupees(it)}/$per" }
        val sell = p.sellingPrice?.let { "${inv.rupees(it)}/$per" }
        tools.log("inventory rate ${kind.name.lowercase()}", "inventory", p.name, ActionStatus.ANSWERED)
        fun missing(ta: String, tl: String, en: String, example: String) = say(lang, KaiMood.CLARIFY, null,
            ta = "ஓனர், ${p.name}-க்கு $ta இன்னும் போடல. \"${p.name} $example\"-னு சொன்னா மாத்தி வைக்கிறேன்.",
            tl = "Owner, ${p.name}-ku $tl innum podala. \"${p.name} $example\"-nu sonna maathi vaikkiren.",
            en = "Owner, ${p.name} has no $en yet. Say \"${p.name} $example\" and I'll set it.")
        return when (kind) {
            com.shopai.app.brain.tools.KaiInventory.RateKind.PURCHASE -> buy?.let {
                say(lang, KaiMood.EXPLAINING, null, ta = "ஓனர், ${p.name} purchase rate $it.", tl = "Owner, ${p.name} purchase rate $it.", en = "Owner, ${p.name}'s purchase rate is $it.")
            } ?: missing("purchase rate", "purchase rate", "purchase rate", "purchase rate 40")
            com.shopai.app.brain.tools.KaiInventory.RateKind.SELLING -> sell?.let {
                say(lang, KaiMood.EXPLAINING, null, ta = "ஓனர், ${p.name} selling price $it.", tl = "Owner, ${p.name} selling price $it.", en = "Owner, ${p.name}'s selling price is $it.")
            } ?: missing("selling price", "selling price", "selling price", "selling price 45")
            com.shopai.app.brain.tools.KaiInventory.RateKind.BOTH -> when {
                buy == null && sell == null -> missing("rate", "rate", "price", "selling price 45")
                else -> {
                    val parts = listOfNotNull(buy?.let { "purchase $it" }, sell?.let { "selling $it" }).joinToString(", ")
                    say(lang, KaiMood.EXPLAINING, null, ta = "ஓனர், ${p.name} — $parts.", tl = "Owner, ${p.name} — $parts.", en = "Owner, ${p.name} — $parts.")
                }
            }
            com.shopai.app.brain.tools.KaiInventory.RateKind.MARGIN -> {
                val b = p.purchasePrice; val s = p.sellingPrice
                when {
                    b == null && s == null -> missing("purchase rate-உம் selling price-உம்", "purchase rate-um selling price-um", "purchase rate or selling price", "purchase rate 40")
                    b == null -> missing("purchase rate", "purchase rate", "purchase rate", "purchase rate 40")
                    s == null -> missing("selling price", "selling price", "selling price", "selling price 45")
                    else -> {
                        val gain = s - b
                        val pct = if (b.signum() > 0) gain.multiply(BigDecimal(100)).divide(b, 1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() else null
                        val pctText = pct?.let { " ($it%)" }.orEmpty()
                        if (gain.signum() <= 0) say(lang, KaiMood.CONCERNED, null,
                            ta = "ஓனர், ${p.name}-ல லாபம் இல்ல: purchase $buy, selling $sell.",
                            tl = "Owner, ${p.name}-la labam illa: purchase $buy, selling $sell.",
                            en = "Owner, ${p.name} makes no margin: purchase $buy, selling $sell.")
                        else {
                            // "Vengayathula evlo labam": also what this month's sales of it earned, at today's rates (an estimate, said so).
                            val today = now().toLocalDate()
                            val sold = runCatching { tools.topProducts(today.withDayOfMonth(1), today, 10_000) }.getOrNull()
                                ?.firstOrNull { it.name.equals(p.name, ignoreCase = true) }?.qty?.takeIf { it.signum() > 0 }
                            val month = sold?.let { q ->
                                val earned = inv.rupees(gain.multiply(q))
                                val shown = inv.shown(q, p.unit)
                                pickLang(lang, ta = " இந்த மாசம் $shown வித்தது — லாபம் சுமார் $earned (இன்னைய rate படி மதிப்பீடு).",
                                    tl = " Indha month $shown vithadhu — labam sumaar $earned (innaiya rate padi mathippeedu).",
                                    en = " This month $shown sold — about $earned profit (an estimate at today's rates).")
                            }.orEmpty()
                            say(lang, KaiMood.EXPLAINING, null,
                                ta = "ஓனர், ${p.name} ஒரு $per-க்கு லாபம் ${inv.rupees(gain)}$pctText — purchase $buy, selling $sell.$month",
                                tl = "Owner, ${p.name} oru $per-ku labam ${inv.rupees(gain)}$pctText — purchase $buy, selling $sell.$month",
                                en = "Owner, ${p.name} earns ${inv.rupees(gain)} per $per$pctText — purchase $buy, selling $sell.$month")
                        }
                    }
                }
            }
        }
    }

    /** "Colgate details kaattu": stock (in packs too), pack size, prices, minimum and stock value — from the books. */
    private fun productDetails(p: com.shopai.app.brain.tools.ProductRef, lang: KaiLang): KaiTurn {
        val inv = com.shopai.app.brain.tools.KaiInventory
        lastProduct = p
        conversationState.lastProduct = p.name; productTalkTurn = conversationState.conversationTurn
        val stock = inv.shown(p.stock, p.unit) + (inv.packView(p, p.stock)?.let { " ($it)" } ?: "")
        val lines = listOfNotNull(
            pick(lang, ta = "Stock: $stock", tl = "Stock: $stock", en = "Stock: $stock"),
            p.conversions.entries.firstOrNull()?.let { (u, f) -> com.shopai.app.brain.tools.KaiUnits.rule(u, f, p.unit) },
            p.purchasePrice?.let { "Purchase ${inv.rupees(it)}/${inv.one(p.unit)}" },
            p.sellingPrice?.let { "Selling ${inv.rupees(it)}/${inv.one(p.unit)}" },
            p.minStock?.let { "Minimum: ${inv.packView(p, it) ?: inv.shown(it, p.unit)}" + if (p.stock <= it) " ⚠️" else "" },
            inv.stockValue(p)?.let { pick(lang, ta = "Stock value ${inv.rupees(it)}", tl = "Stock value ${inv.rupees(it)}", en = "Stock value ${inv.rupees(it)}") },
            p.category?.let { "Category: $it" },
        )
        tools.log("inventory details", "inventory", p.name, ActionStatus.ANSWERED)
        return KaiTurn(
            ChatReply(pick(lang, ta = "${p.name} விவரம் ஓனர்:", tl = "Owner, ${p.name} details:", en = "${p.name} details, Owner:"), KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(lines, emptyList()),
        )
    }

    /** "En inventory summary kaattu": items, stock value at purchase price, low stock — every figure from the books. */
    private fun inventorySummary(products: List<com.shopai.app.brain.tools.ProductRef>, lang: KaiLang, list: Boolean): KaiTurn {
        val inv = com.shopai.app.brain.tools.KaiInventory
        if (products.isEmpty()) return say(lang, KaiMood.NEUTRAL, null,
            ta = "ஓனர், inventory-ல இன்னும் எந்த product-உம் இல்ல.", tl = "Owner, inventory-la innum endha product-um illa.", en = "Owner, there are no products in your inventory yet.")
        val value = products.mapNotNull { inv.stockValue(it) }.fold(BigDecimal.ZERO, BigDecimal::add)
        val unpriced = products.count { it.purchasePrice == null && it.stock.signum() > 0 }
        val low = products.filter { p -> p.minStock?.let { p.stock <= it } == true }
        val lines = products.sortedBy { it.name.lowercase(Locale.ROOT) }.take(if (list) 50 else 10).map { p ->
            "${p.name} — ${inv.shown(p.stock, p.unit)}" + (inv.packView(p, p.stock)?.let { " ($it)" } ?: "") + if (p in low) " ⚠️" else ""
        }
        tools.log("inventory summary", "inventory", "${products.size} items", ActionStatus.ANSWERED)
        val head = pick(lang,
            ta = "ஓனர், ${products.size} items. Stock value ${inv.rupees(value)}" + (if (unpriced > 0) " ($unpriced items-க்கு purchase rate இல்ல)" else "") + ". Low stock: ${low.size}.",
            tl = "Owner, ${products.size} items. Stock value ${inv.rupees(value)}" + (if (unpriced > 0) " ($unpriced items-ku purchase rate illa)" else "") + ". Low stock: ${low.size}.",
            en = "Owner, ${products.size} items. Stock value ${inv.rupees(value)}" + (if (unpriced > 0) " ($unpriced items have no purchase rate)" else "") + ". Low stock: ${low.size}.")
        return KaiTurn(ChatReply(head, KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY), KaiCard(lines, emptyList()))
    }

    /**
     * "ABC Traders kitta 5 box Colgate vaanginen, credit" / "Ramesh-ku 5 Colgate credit sale": goods with a supplier / customer
     * of the books → one bill. Who / what / how much / rate / credit-or-cash are completed one question at a time.
     */
    private suspend fun stockBillLine(text: String, said: String, lang: KaiLang, product: com.shopai.app.brain.tools.ProductRef, people: List<String>): KaiTurn? {
        val inv = com.shopai.app.brain.tools.KaiInventory
        val name = com.shopai.app.brain.KaiUnderstanding.knownPerson(text, people) ?: return null
        val buying = inv.buys(text)
        val selling = !buying && inv.sells(text)
        if (!buying && !selling) return null
        val party = runCatching { tools.parties(name) }.getOrNull()?.let { KaiEntityResolver.sameName(name, it) }?.distinctBy { it.id }
            ?.filter { it.customer == selling }?.singleOrNull() ?: return null
        val goods = inv.withoutMoney(text).replace(Regex("""(?i)(?<![\p{L}])${Regex.escape(name)}\S*"""), " ").replace(Regex("""(?i)(?<![\p{L}])${Regex.escape(product.name)}(?![\p{L}])"""), " ")
        val parts = com.shopai.app.brain.tools.KaiStock.partsIn(goods)
        if (parts.isEmpty()) return null
        if (selling && inv.isFree(text)) {
            // Given free: only the stock goes out (no bill, no balance).
            lastProduct = product
            return draftParts(product, parts, false, "Free · $said", lang)
        }
        val b = BillItem(newKey(), buying, party, product, parts, inv.moneyIn(text), null, inv.creditOf(text), inv.modeOf(text), said, lang, conversationState.conversationTurn, null)
        return billStep(b)
    }

    private fun billStep(b0: BillItem): KaiTurn {
        val inv = com.shopai.app.brain.tools.KaiInventory
        val lang = b0.lang
        var b = b0.copy(turn = conversationState.conversationTurn)
        val p = b.product
        fun ask(field: String, ta: String, tl: String, en: String): KaiTurn {
            billItem = b.copy(asked = field)
            return KaiTurn(ChatReply(pick(lang, ta = ta, tl = tl, en = en), KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY),
                KaiCard(emptyList(), listOf(KaiButton(cancelLabel(lang), KaiAction.CancelStock(b.key)))))
        }
        // Quantity in the product's own unit — "5" alone for a product kept in pieces and boxes is asked.
        if (p.conversions.isNotEmpty() && b.parts.size == 1 && b.parts.single().unit == null) {
            val q = b.parts.single().qty
            val options = (listOf(p.unit) + p.conversions.keys).map { inv.shown(q, it) }.distinct()
            val opt = options.joinToString(pick(lang, ta = "-ஆ, ", tl = "-aa, ", en = " or ")) + pick(lang, ta = "-ஆ", tl = "-aa", en = "")
            return ask("unit", ta = "${p.name} — $opt ஓனர்?", tl = "${p.name} — $opt Owner?", en = "${p.name} — $opt, Owner?")
        }
        val resolved = com.shopai.app.brain.tools.KaiUnits.resolve(p, b.parts, sessionUnits[p.id].orEmpty())
        if (resolved is com.shopai.app.brain.tools.UnitResolution.Missing) {
            val one = inv.one(resolved.unit)
            return ask("per:${resolved.unit}", ta = "1 $one-ல எத்தனை ${inv.many(p.unit)}?", tl = "1 $one-la evlo ${inv.many(p.unit)}?", en = "How many ${inv.many(p.unit)} in 1 $one?")
        }
        val qty = (resolved as com.shopai.app.brain.tools.UnitResolution.Ok).baseQty
        if (qty.signum() <= 0) { billItem = null; return say(lang, KaiMood.CLARIFY, null, ta = "அளவு சரியா இல்ல ஓனர்.", tl = "Owner, alavu sariyaa illa.", en = "That quantity isn't right, Owner.") }
        val rate = b.rate ?: b.total?.let { it.divide(qty, 4, java.math.RoundingMode.HALF_UP).stripTrailingZeros() }
            ?: (if (b.purchase) p.purchasePrice else p.sellingPrice)
        if (rate == null) return ask("rate",
            ta = "ஒரு ${inv.one(p.unit)} ${if (b.purchase) "purchase" else "selling"} rate எவ்வளவு?",
            tl = "Oru ${inv.one(p.unit)} ${if (b.purchase) "purchase" else "selling"} rate evlo?",
            en = "${if (b.purchase) "Purchase" else "Selling"} rate for 1 ${inv.one(p.unit)}?")
        b = b.copy(rate = rate)
        if (b.credit == null) return ask("credit",
            ta = "Credit-ஆ, cash-ஆ ஓனர்?", tl = "Credit-aa, cash-aa Owner?", en = "On credit, or paid now, Owner?")
        // The summary: ledger and stock together, saved only on Confirm.
        billItem = b.copy(asked = null)
        val bill = com.shopai.app.brain.tools.StockBill(b.purchase, b.party.id, b.party.name, p, qty, rate, b.credit!!, b.mode, b.said)
        val said = com.shopai.app.brain.tools.KaiUnits.describe(b.parts, p.unit)
        val total = inv.shown(qty, p.unit)
        val qtyText = if (said == total) said else "$said ($total)"
        val money = inv.rupees(bill.total)
        val how = if (b.credit == true) "credit" else b.mode.name.lowercase(Locale.ROOT).replace('_', ' ')
        val after = if (b.purchase) p.stock + qty else p.stock - qty
        val short = !b.purchase && after.signum() < 0
        lastDraft = b.said to "STOCK"
        tools.log(if (b.purchase) "purchase" else "sale", "draft", "${b.party.name} ${p.name} $total $money $how", ActionStatus.DRAFT, null, b.said)
        val reply = if (b.purchase) pick(lang,
            ta = "${b.party.name} கிட்ட ${p.name} $qtyText purchase — $money ($how). Stock +$total" + (if (b.credit == true) ", ${b.party.name}-க்கு $money கொடுக்கணும்" else "") + ". உறுதி செய்யவா?",
            tl = "${b.party.name} kitta ${p.name} $qtyText purchase — $money ($how). Stock +$total" + (if (b.credit == true) ", ${b.party.name}-ku $money kudukkanum" else "") + ". Confirm pannava?",
            en = "${p.name} $qtyText bought from ${b.party.name} — $money ($how). Stock +$total" + (if (b.credit == true) ", you owe ${b.party.name} $money" else "") + ". Confirm?")
        else pick(lang,
            ta = "${b.party.name}-க்கு ${p.name} $qtyText sale — $money ($how). Stock −$total" + (if (b.credit == true) ", ${b.party.name} $money தரணும்" else "") + ". உறுதி செய்யவா?",
            tl = "${b.party.name}-ku ${p.name} $qtyText sale — $money ($how). Stock −$total" + (if (b.credit == true) ", ${b.party.name} $money tharanum" else "") + ". Confirm pannava?",
            en = "${p.name} $qtyText sold to ${b.party.name} — $money ($how). Stock −$total" + (if (b.credit == true) ", ${b.party.name} owes you $money" else "") + ". Confirm?")
        return KaiTurn(
            ChatReply(reply, KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(listOf(
                "${p.name} — $qtyText",
                "Rate ${inv.rupees(rate)}/${inv.one(p.unit)} → $money",
                pick(lang, ta = "Stock: ", tl = "Stock: ", en = "Stock: ") + "${inv.shown(p.stock, p.unit)} → " + if (short) "—" else inv.shown(after, p.unit),
            ), listOf(
                KaiButton(pick(lang, ta = "உறுதி செய்", tl = "Confirm", en = "Confirm"), KaiAction.ConfirmStock(b.key), primary = true, enabled = !short),
                KaiButton(cancelLabel(lang), KaiAction.CancelStock(b.key)),
            ), warning = if (short) pick(lang, ta = "அவ்வளவு ஸ்டாக் இல்ல ஓனர்.", tl = "Owner, avlo stock illa.", en = "There isn't that much stock, Owner.") else null),
        )
    }

    /** The owner's answer while a bill with goods is open: unit / pack size / rate / credit-or-cash / Confirm / cancel. */
    private suspend fun billAnswer(said: String): KaiTurn? {
        val b = billItem ?: return null
        if (conversationState.conversationTurn - b.turn > 3) { billItem = null; return null }
        val inv = com.shopai.app.brain.tools.KaiInventory
        val text = com.shopai.app.brain.tools.KaiSpokenWords.normalize(said)
        if (KaiConversationSemantics.cancelsDraft(said)) return act(KaiAction.CancelStock(b.key), b.lang)
        val asked = b.asked
        if (asked == null) {
            if (said.none(Char::isDigit) && (KaiConversationSemantics.confirmsDraft(said) || KaiConversationSemantics.onlySave(said))) return confirmBill(b.key)
            return null
        }
        return when {
            asked == "unit" -> {
                val u = text.lowercase(Locale.ROOT).split(Regex("""\s+""")).firstNotNullOfOrNull(inv::unitOf) ?: return null
                billStep(b.copy(parts = listOf(com.shopai.app.brain.tools.QtyPart(b.parts.single().qty, u))))
            }
            asked.startsWith("per:") -> {
                val f = inv.numberIn(text)?.takeIf { it.signum() > 0 } ?: return null
                sessionUnits.getOrPut(b.product.id) { HashMap() }[asked.removePrefix("per:")] = f
                billStep(b)
            }
            asked == "rate" -> {
                val r = inv.numberIn(text)?.takeIf { it.signum() >= 0 } ?: return null
                billStep(b.copy(rate = r))
            }
            asked == "credit" -> {
                val c = inv.creditOf(text) ?: return null
                billStep(b.copy(credit = c, mode = if (c) PaymentMode.CREDIT else inv.modeOf(text)))
            }
            else -> null
        }
    }

    /** Confirm on a bill with goods: ONE engine write (party balance + stock + any payment), then read back. */
    private suspend fun confirmBill(key: String): KaiTurn? {
        val b = billItem?.takeIf { it.key == key && it.asked == null } ?: return null
        billItem = null
        val inv = com.shopai.app.brain.tools.KaiInventory
        val p = b.product
        val qty = (com.shopai.app.brain.tools.KaiUnits.resolve(p, b.parts, sessionUnits[p.id].orEmpty()) as? com.shopai.app.brain.tools.UnitResolution.Ok)?.baseQty ?: return null
        val bill = com.shopai.app.brain.tools.StockBill(b.purchase, b.party.id, b.party.name, p, qty, b.rate!!, b.credit == true, b.mode, b.said)
        val intent = if (b.purchase) "purchase" else "sale"
        return when (val r = tools.stockBill(bill)) {
            is ActionOutcome.Done -> {
                tools.log(intent, "books engine", "${b.party.name} ${p.name} ${inv.shown(qty, p.unit)} ${inv.rupees(bill.total)} saved (${r.reference})", ActionStatus.CONFIRMED, null, b.said)
                lastProduct = runCatching { tools.products() }.getOrNull()?.firstOrNull { it.id == p.id } ?: p
                val stockNow = r.balanceAfter?.let { inv.shown(it, p.unit) }
                // The party's balance, read back from the books.
                val balance = runCatching { tools.parties(b.party.name) }.getOrNull()?.firstOrNull { it.id == b.party.id }?.balance
                val low = r.balanceAfter?.let { lowStockAfter(p, it, b.lang) }
                val bal = balance?.let { inv.rupees(it) }
                say(b.lang, KaiMood.SUCCESS, null,
                    ta = "சரி ஓனர் ✅ ${if (b.purchase) "Purchase" else "Sale"} save ஆச்சு (${r.reference})." + (stockNow?.let { " ${p.name} stock $it." } ?: "") +
                        (bal?.let { if (b.purchase) " ${b.party.name}-க்கு இப்போ $it கொடுக்கணும்." else " ${b.party.name} இப்போ $it தரணும்." } ?: "") + (low?.let { "\n$it" } ?: ""),
                    tl = "Done Owner ✅ ${if (b.purchase) "Purchase" else "Sale"} save aagiduchu (${r.reference})." + (stockNow?.let { " ${p.name} stock $it." } ?: "") +
                        (bal?.let { if (b.purchase) " ${b.party.name}-ku ippo $it kudukkanum." else " ${b.party.name} ippo $it tharanum." } ?: "") + (low?.let { "\n$it" } ?: ""),
                    en = "Done Owner ✅ The ${if (b.purchase) "purchase" else "sale"} is saved (${r.reference})." + (stockNow?.let { " ${p.name} stock: $it." } ?: "") +
                        (bal?.let { if (b.purchase) " You now owe ${b.party.name} $it." else " ${b.party.name} now owes you $it." } ?: "") + (low?.let { "\n$it" } ?: ""))
            }
            is ActionOutcome.Failed -> {
                tools.log(intent, "books engine", r.reason, ActionStatus.FAILED, null, b.said)
                say(b.lang, KaiMood.ERROR, null,
                    ta = "சேமிக்க முடியல ஓனர்: ${r.reason}. எதுவும் சேமிக்கல.", tl = "Owner, save aagala: ${r.reason}. Edhuvum save aagala.", en = "It wasn't saved, Owner: ${r.reason}. Nothing was saved.")
            }
        }
    }

    /** "Colgate 5" for a product kept in pieces and boxes: "5 pieces-aa, 5 boxes-aa?" — never guessed. */
    private fun askUnitPick(product: com.shopai.app.brain.tools.ProductRef, qty: BigDecimal, incoming: Boolean, said: String, lang: KaiLang): KaiTurn {
        unitPick = UnitPick(product, qty, incoming, said, conversationState.conversationTurn)
        lastProduct = product
        val options = (listOf(product.unit) + product.conversions.keys).map { com.shopai.app.brain.tools.KaiStock.shown(qty, it) }.distinct()
        val q = options.joinToString(pick(lang, ta = "-ஆ, ", tl = "-aa, ", en = " or ")) + pick(lang, ta = "-ஆ", tl = "-aa", en = "")
        return say(lang, KaiMood.CLARIFY, "${product.name}: unit asked",
            ta = "${product.name} — $q ஓனர்?", tl = "${product.name} — $q Owner?", en = "${product.name} — $q, Owner?")
    }

    private fun unitPicked(said: String, lang: KaiLang): KaiTurn? {
        val p = unitPick ?: return null
        unitPick = null
        if (conversationState.conversationTurn - p.turn > 2) return null
        val text = com.shopai.app.brain.tools.KaiSpokenWords.normalize(said).lowercase(Locale.ROOT)
        val unit = text.split(Regex("""\s+""")).firstNotNullOfOrNull(com.shopai.app.brain.tools.KaiInventory::unitOf) ?: return null
        val q = com.shopai.app.brain.tools.KaiInventory.numberIn(text)?.takeIf { it.signum() > 0 } ?: p.qty
        return draftParts(p.product, listOf(com.shopai.app.brain.tools.QtyPart(q, unit)), p.incoming, "${p.said} · $said", lang)
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
        // "Oil 3 bottle damage", "Colgate actual 230": why it changed — kept with the movement (the books' adjustment reason).
        val why = com.shopai.app.brain.tools.KaiStock.reasonOf(said) ?: "Physical count".takeIf { said.startsWith(physicalNote) }
        val reply = if (why != null && !short) pick(lang,
            ta = "சரி ஓனர். ${product.name} — $n ${if (incoming) "stock-in" else "stock-out"} ($why). ${if (incoming) "இப்போ" else "மீதம்"}: ${shown(after, product.unit)}.",
            tl = "Seri Owner. ${product.name} — $n ${if (incoming) "stock-in" else "stock-out"} ($why). ${if (incoming) "Ippo stock" else "Remaining"}: ${shown(after, product.unit)}.",
            en = "Okay Owner. ${product.name} — $n ${if (incoming) "stock-in" else "stock-out"} ($why). ${if (incoming) "Stock now" else "Remaining"}: ${shown(after, product.unit)}.")
        else if (incoming) pick(lang,
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
        ) + listOfNotNull(why?.let { pick(lang, ta = "காரணம்: $it", tl = "Reason: $it", en = "Reason: $it") })
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
        // "Colgate 1 box supplier return": why it changed, said in the reply and kept with the movement.
        val why = com.shopai.app.brain.tools.KaiStock.reasonOf(said) ?: "Physical count".takeIf { said.startsWith(physicalNote) }
        val lines = listOfNotNull(
            product.name,
            pick(lang, ta = "நீங்க சொன்னது: ", tl = "Input: ", en = "Input: ") + input,
            why?.let { pick(lang, ta = "காரணம்: $it", tl = "Reason: $it", en = "Reason: $it") },
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
        val reasoned = if (why != null && !short) body.replace(" stock-out = $total.", " stock-out = $total ($why).").replace(" stock-in draft", " ($why) stock-in draft") else body
        return KaiTurn(
            ChatReply(head + reasoned, KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
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
        val u = com.shopai.app.brain.tools.KaiStock.unitWord(unit, q)
        return if (incoming) say(lang, KaiMood.SUCCESS, null,
            ta = "சரி ஓனர் ✅ $name ஸ்டாக்-ல $n $u சேர்த்துட்டேன்." + (after?.let { " இப்போ ஸ்டாக் $it." } ?: ""),
            tl = "Done Owner ✅ $name stock-la $n $u add panniten." + (after?.let { " Ippo stock $it." } ?: ""),
            en = "Done Owner ✅ Added $n $u to $name stock." + (after?.let { " Stock now $it." } ?: ""))
        else say(lang, KaiMood.SUCCESS, null,
            ta = "சரி ஓனர் ✅ $name ஸ்டாக்-ல இருந்து $n $u குறைச்சுட்டேன்." + (after?.let { " மீதம் $it." } ?: ""),
            tl = "Done Owner ✅ $name stock-la irundhu $n $u kuraichiten." + (after?.let { " Meedham $it." } ?: ""),
            en = "Done Owner ✅ Took $n $u out of $name stock." + (after?.let { " $it left." } ?: ""))
    }

    /** "⚠️ Colgate low stock …" for the reply that just took stock out (null when not low). */
    private var lowStockNote: String? = null

    private fun lowStockAfter(p: com.shopai.app.brain.tools.ProductRef, after: BigDecimal, lang: KaiLang): String? {
        val min = p.minStock ?: return null
        if (after > min) return null
        val inv = com.shopai.app.brain.tools.KaiInventory
        val now = inv.packView(p, after)?.let { "$it (${inv.shown(after, p.unit)})" } ?: inv.shown(after, p.unit)
        val minText = inv.packView(p, min) ?: inv.shown(min, p.unit)
        return pick(lang,
            ta = "⚠️ ${p.name} low stock: $now (minimum $minText).",
            tl = "⚠️ ${p.name} low stock: $now (minimum $minText).",
            en = "⚠️ ${p.name} is low: $now (minimum $minText).")
    }

    private suspend fun confirmStock(key: String, lang: KaiLang): KaiTurn? = confirmStockOnly(key, lang)?.let { t ->
        val note = lowStockNote.also { lowStockNote = null } ?: return@let t
        withText(t, t.reply.text + "\n" + note)
    }

    private suspend fun confirmStockOnly(key: String, lang: KaiLang): KaiTurn? {
        if (newItem?.key == key) return confirmNewItem(key, lang)
        if (productEdit?.key == key) return applyProductEdit(key)
        if (billItem?.key == key) return confirmBill(key)
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
                lowStockNote = lowStockAfter(plan.product, outcome.balanceAfter ?: if (plan.incoming) plan.product.stock + plan.qty else plan.product.stock - plan.qty, lang)
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
    /** The turn Kai asked "enna saaman vandhuruku?" (or took the last item): the owner's goods lines follow; -1 = not waiting. */
    private var goodsTurn = -1L
    /** Goods said together, drafted one at a time ("Rice 3 moota" after Colgate's Confirm). */
    private val goodsQueue = ArrayDeque<String>()
    /** Each goods draft the owner confirmed — not saved yet: the whole list is shown and saved on one Confirm (owner, 11 Oct 2026). */
    private sealed interface GoodsEntry { val name: String }
    private data class GoodsStock(val plan: StockPlan) : GoodsEntry { override val name get() = plan.product.name }
    private data class GoodsNew(val item: NewItem) : GoodsEntry { override val name get() = item.spec.name }
    private val goodsList = mutableListOf<GoodsEntry>()
    /** "Edit" on the list: the owner's next line changes or drops one item. */
    private var goodsEditing = false

    /** Waiting for the owner's goods; with a list started it waits longer (it is never lost by a few other questions). */
    private fun goodsOpen() = goodsTurn >= 0 && conversationState.conversationTurn - goodsTurn <= if (goodsList.isEmpty()) 6 else 20

    private fun goodsClear() { goodsTurn = -1L; goodsQueue.clear(); goodsList.clear(); goodsEditing = false }

    /** One list line: "Colgate — 2 boxes = 96 pieces (240 pieces → 336 pieces)". */
    private fun goodsLine(e: GoodsEntry, lang: KaiLang): String = when (e) {
        is GoodsStock -> {
            val p = e.plan
            val input = p.conv?.let { com.shopai.app.brain.tools.KaiUnits.describe(it.input, p.product.unit) }
            val total = com.shopai.app.brain.tools.KaiStock.shown(p.qty, p.product.unit)
            "${p.product.name} — ${input?.let { "$it = " }.orEmpty()}$total (${com.shopai.app.brain.tools.KaiStock.shown(p.product.stock, p.product.unit)} → " +
                "${com.shopai.app.brain.tools.KaiStock.shown(p.product.stock + p.qty, p.product.unit)})"
        }
        is GoodsNew -> {
            val spec = e.item.spec
            "${spec.name} — ${spec.qty?.let { com.shopai.app.brain.tools.KaiInventory.shown(it, spec.unit) }.orEmpty()} (" +
                pick(lang, ta = "புது product", tl = "pudhu product", en = "new product") + ")"
        }
    }

    /** A goods draft confirmed: kept in the list (nothing saved yet), then "aduthu sollunga". */
    private fun goodsKeep(key: String, lang: KaiLang): KaiTurn? {
        val entry: GoodsEntry = stockPlans[key]?.takeIf { it.incoming }?.let { stockPlans.remove(key); GoodsStock(it) }
            ?: newItem?.takeIf { it.key == key && it.asked == null }?.let { newItem = null; GoodsNew(it) }
            ?: return null
        lastDraft = null
        // "Edit" on the list, then "Rice 4 moota": that item is replaced, and the list comes again.
        if (goodsEditing) {
            goodsList.indexOfFirst { KaiPrivateMemory.normalize(it.name) == KaiPrivateMemory.normalize(entry.name) }
                .let { i -> if (i >= 0) goodsList[i] = entry else goodsList += entry }
            goodsEditing = false
            goodsTurn = conversationState.conversationTurn
            return goodsListTurn(lang)
        }
        goodsList += entry
        goodsTurn = conversationState.conversationTurn
        tools.log("goods arrived", "inventory", "${entry.name}: in the list (not saved)", ActionStatus.ANSWERED)
        val line = goodsLine(entry, lang)
        // Items said together still waiting: the next draft follows (nextGoods), so "aduthu sollunga" only after the last one.
        val next = goodsQueue.isEmpty()
        return say(lang, KaiMood.HAPPY, null,
            ta = "சரி ஓனர் 👍 $line — list-ல சேர்த்துட்டேன் (இன்னும் சேமிக்கல)." + if (next) " அடுத்து சொல்லுங்க ஓனர். முடிஞ்சா 'அவ்ளோ தான்' சொல்லுங்க." else "",
            tl = "Seri Owner 👍 $line — list-la serthutten (innum save pannala)." + if (next) " Aduthu sollunga Owner. Mudinjaa 'avlo dhaan' nu sollunga." else "",
            en = "Okay Owner 👍 $line — added to the list (not saved yet)." + if (next) " Tell me the next one. Say 'that's all' when you're done." else "")
    }

    /** "Avlo dhaan": every item in one list, to check once — Confirm saves them all, Edit changes one, Cancel drops the list. */
    private fun goodsListTurn(lang: KaiLang, note: String? = null): KaiTurn {
        goodsTurn = conversationState.conversationTurn
        val lines = goodsList.mapIndexed { i, e -> "${i + 1}. ${goodsLine(e, lang)}" }
        val head = pick(lang,
            ta = "சரி ஓனர் 👍 நாம add பண்ண stock list இதோ. ஒரு தடவை பார்த்து உறுதி செய்யுங்க ஓனர்.",
            tl = "Seri Owner 👍 Namma add panna stock list idho. Oru thadava paathu confirm pannunga Owner.",
            en = "Okay Owner 👍 Here's the stock list we made. Please check it once and confirm.")
        return KaiTurn(
            ChatReply(listOfNotNull(note, head).joinToString("\n"), KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(lines, listOf(
                KaiButton(pick(lang, ta = "உறுதி செய்", tl = "Confirm", en = "Confirm"), KaiAction.ConfirmGoods, primary = true),
                KaiButton(pick(lang, ta = "மாற்று", tl = "Edit", en = "Edit"), KaiAction.EditGoods),
                KaiButton(cancelLabel(lang), KaiAction.CancelGoods),
            )),
        )
    }

    /** Confirm on the list: each item saved by the same engine as before (one stock-in each, a new product with its opening stock). */
    private suspend fun confirmGoods(lang: KaiLang): KaiTurn {
        val entries = goodsList.toList()
        goodsClear()
        if (entries.isEmpty()) return say(lang, KaiMood.NEUTRAL, null, ta = "List காலியா இருக்கு ஓனர் — எதுவும் சேமிக்கல.",
            tl = "Owner, list kaaliyaa irukku — edhuvum save pannala.", en = "The list is empty, Owner — nothing was saved.")
        var saved = 0
        val results = entries.map { e ->
            val t = when (e) {
                is GoodsStock -> { stockPlans[e.plan.key] = e.plan; confirmStockOnly(e.plan.key, lang) }
                is GoodsNew -> { newItem = e.item; confirmNewItem(e.item.key, lang) }
            }
            if (t?.reply?.mood == KaiMood.SUCCESS) saved++
            t?.reply?.text ?: pick(lang, ta = "${e.name}: சேமிக்க முடியல.", tl = "${e.name}: save aagala.", en = "${e.name}: not saved.")
        }
        lowStockNote = null
        val head = if (saved == entries.size) pick(lang,
            ta = "முடிஞ்சது ஓனர் ✅ ${entries.size} item stock-ல சேர்ந்துச்சு:", tl = "Mudinjudhu Owner ✅ ${entries.size} item${if (entries.size == 1) "" else "s"} stock-la add aagiduchu:",
            en = "Done Owner ✅ ${entries.size} item${if (entries.size == 1) "" else "s"} added to stock:")
        else pick(lang,
            ta = "ஓனர், ${entries.size}-ல $saved தான் சேமிச்சேன் — கீழ பாருங்க:", tl = "Owner, ${entries.size}-la $saved dhaan save aachu — keezha paarunga:",
            en = "Owner, only $saved of ${entries.size} were saved — see below:")
        return KaiTurn(ChatReply(head + "\n" + results.joinToString("\n") { "• $it" }, if (saved == entries.size) KaiMood.SUCCESS else KaiMood.CONCERNED,
            ChatIntent.GENERAL_BUSINESS_QUERY), KaiCard(emptyList(), listOf(
            KaiButton(pick(lang, ta = "📦 Inventory-ல பாருங்க", tl = "📦 Inventory-la paarunga", en = "📦 See it in Inventory"), KaiAction.OpenRecord("INVENTORY")))))
    }

    /** After "Edit" on the list: "Rice 4 moota" changes Rice (a draft to confirm); "Rice venam" takes it out of the list. */
    private suspend fun goodsEdit(text: String, lang: KaiLang): KaiTurn? {
        if (!goodsEditing) return null
        val hit = goodsList.filter { e -> KaiPrivateMemory.normalize(text).contains(KaiPrivateMemory.normalize(e.name)) }.maxByOrNull { it.name.length }
        if (hit != null && goodsDrop.containsMatchIn(text) && !text.any(Char::isDigit)) {
            goodsList.remove(hit)
            goodsEditing = false
            if (goodsList.isEmpty()) {
                goodsClear()
                return say(lang, KaiMood.NEUTRAL, null, ta = "சரி ஓனர், ${hit.name} எடுத்துட்டேன். List காலி — எதுவும் சேமிக்கல.",
                    tl = "Seri Owner, ${hit.name} eduthutten. List kaali — edhuvum save pannala.", en = "Okay Owner, removed ${hit.name}. The list is empty — nothing was saved.")
            }
            return goodsListTurn(lang, pick(lang, ta = "சரி ஓனர், ${hit.name} list-ல இருந்து எடுத்துட்டேன்.", tl = "Seri Owner, ${hit.name} list-la irundhu eduthutten.",
                en = "Okay Owner, ${hit.name} is out of the list."))
        }
        return null
    }
    private val goodsDrop = Regex("""(?i)(?<![\p{L}])(venam|vendam|vendaam|venaam|eduthudu|eduthuru|eduthudunga|remove|delete|cancel|vidu|vidunga)(?![\p{L}])|வேண்டாம்|வேணாம்|எடுத்துடு""")

    /** A stock draft is on screen, waiting for Confirm. */
    private fun stockDraftOpen() = lastDraft?.takeIf { it.second == "STOCK" }?.let { last -> stockPlans.values.any { it.said == last.first } } == true

    /** "vandhuruku", "vandhuchu", "irakkinom", "came": goods arrived (said, not asked for). */
    private val cameIn = Regex("""(?i)(?<![\p{L}])(van[dt]h?[iu]\p{L}*|irakk\p{L}*|eranguchu|iranguchu|came|arrived|received)(?![\p{L}])|வந்""")

    /** Only the question — no buttons: Photo / Cancel are not shown here; the drafts carry Confirm / Edit / Cancel (owner, 11 Oct 2026). */
    private suspend fun goodsAnnounced(said: String, lang: KaiLang): KaiTurn? {
        if (newItem != null || unitQuestion != null || stockQuestion != null) return null
        val products = runCatching { tools.products() }.getOrNull() ?: return null
        val people = runCatching { books.snapshot()?.people.orEmpty() }.getOrDefault(emptyList())
        // "new stock add pannu", "Colgate photo edu": the stock line's own question (with its Photo button) — goods mode opens there too.
        // "Kadaiku stock vandhuruku" says goods came — the goods question, as "saman / saraku vandhuruku" (owner, 11 Oct 2026);
        // "new stock add pannu" (a command, nothing came) keeps the one-product question, and a photo asked opens the camera.
        if (com.shopai.app.brain.tools.KaiStock.scanRequest(said, products) != null && (photoAsked.containsMatchIn(said) || !cameIn.containsMatchIn(said))) return null
        if (!KaiGoodsArrival.announces(said, products.map { it.name }) ||
            KaiCommands.personIn(KaiGoodsArrival.withoutGoodsWords(said), people) != null) return null
        goodsClear()
        goodsTurn = conversationState.conversationTurn
        personOnTopic = null
        tools.log("goods arrived", "inventory", "asked which", ActionStatus.ANSWERED)
        return KaiTurn(
            ChatReply(pick(lang,
                ta = "சரி ஓனர் 👍 என்ன என்ன சாமான் வந்திருக்கு? சொல்லுங்க, add பண்ணிடலாம் — ஒண்ணு ஒண்ணா சொல்லுங்க. (உதா: ஆசிர்வாத் ரவா அரைக்கிலோ 50 பாக்கெட்)",
                tl = "Seri Owner 👍 Enna enna saaman vandhuruku? Sollunga, add panniruvom — onnu onna sollunga. (eg: Ashirvad rava arakilo 50 packet)",
                en = "Sure, Owner 👍 What came in? Tell me one by one and I'll add it. (e.g. Ashirvad rava half kilo 50 packets)"), KaiMood.HAPPY, ChatIntent.GENERAL_BUSINESS_QUERY),
        )
    }

    /** A line after "enna saaman vandhuruku?": "Colgate 2 box, Rice 3 moota" — each a stock-in draft; "avlo dhaan" ends it. */
    private suspend fun goods(text: String, lang: KaiLang): KaiTurn? {
        goodsEdit(text, lang)?.let { return it }
        if (goodsOpen() && KaiGoodsArrival.ends(text)) {
            // Kai's own question (a new product's price, a unit) is answered first; "venam" to an open stock draft drops that
            // draft as before — then the next item said together comes (owner, 11 Oct 2026: the old stock flow stays as it is).
            if (newItem != null || unitQuestion != null || stockQuestion != null) return null
            if (stockDraftOpen() && KaiConversationSemantics.cancelsDraft(text)) return null
            // "Avlo dhaan" with items kept: the whole list, to check once (a draft not confirmed is left out of it).
            if (goodsList.isNotEmpty()) {
                val open = lastDraft?.takeIf { it.second == "STOCK" }?.let { last -> stockPlans.values.firstOrNull { it.said == last.first } }
                open?.let { stockPlans.remove(it.key); lastDraft = null }
                goodsQueue.clear(); goodsEditing = false
                return goodsListTurn(lang, open?.let { pick(lang, ta = "(${it.product.name} உறுதி செய்யல — list-ல இல்ல.)",
                    tl = "(${it.product.name} confirm pannala — list-la illa.)", en = "(${it.product.name} wasn't confirmed — it's not in the list.)") })
            }
            goodsTurn = -1; goodsQueue.clear()
            return say(lang, KaiMood.HAPPY, "goods: done", ta = "சரி ஓனர் ✅ வேற சாமான் வந்தா சொல்லுங்க.", tl = "Seri Owner ✅ Vera saaman vandha sollunga.",
                en = "Done, Owner ✅ Tell me when more comes in.")
        }
        // "Ashirvad rava arakilo 50 packet": one product, its packet size and how many — never two items (owner, 11 Oct 2026).
        val sized = if (goodsOpen()) KaiGoodsArrival.sized(text) else null
        val items = if (sized != null) listOf(sized) else KaiGoodsArrival.items(text).map { it.replaceFirstChar(Char::uppercaseChar) }
        if (items.isEmpty() || !goodsOpen() && items.size < 2) return null
        // "Colgate 5 box, boxக்கு 48 pieces, purchase 28, selling 35": one product with its details — the old stock line, not a list.
        val inv = com.shopai.app.brain.tools.KaiInventory
        if (sized == null) inv.stockLine(text)?.let { (name, qty, unit) ->
            if (inv.hasDetails(text, com.shopai.app.brain.tools.ItemSpec(name, inv.kindOf(name, unit), qty, unit))) return null }
        // Kai is asking a product's details, or a person / money is said: not a goods line.
        if (newItem != null || unitQuestion != null || stockQuestion != null || planChoice != null || relationChoice != null ||
            conversationState.entityChoice != null) return null
        val products = runCatching { tools.products() }.getOrNull() ?: return null
        val people = runCatching { books.snapshot()?.people.orEmpty() }.getOrDefault(emptyList())
        if (KaiCommands.personIn(text, people) != null) return null
        // Not asked: only two or more known products, all coming in ("vandhuchu"), none going out.
        if (!goodsOpen()) {
            val stock = com.shopai.app.brain.tools.KaiStock
            if (items.size < 2 || stock.understand(text, products)?.incoming != true || items.any { item ->
                    stock.understand(item, products)?.incoming == false || stock.understand("$item vandhuchu", products)?.product == null }) return null
        }
        goodsTurn = conversationState.conversationTurn
        goodsQueue.clear()
        // "Ashirvad Rava 50 packet, oru packet 0.5 kg": the stock line with its packet size (a new product, or packets of a known one).
        if (sized != null) return newStockLine(sized, sized, lang, products, people)
        goodsQueue.addAll(items.drop(1))
        val first = goodsDraft(items.first(), lang, products, people) ?: return null
        return if (goodsQueue.isEmpty()) first else withText(first, first.reply.text + "\n\n" + pick(lang,
            ta = "இது ஆனதும் அடுத்து: ${goodsQueue.joinToString(", ")}.", tl = "Idhu aanadhum adutha: ${goodsQueue.joinToString(", ")}.",
            en = "After this: ${goodsQueue.joinToString(", ")}."))
    }

    /** One goods line as a stock-in draft ("Colgate 2 box" → "Colgate 2 box vandhuchu"). */
    private suspend fun goodsDraft(item: String, lang: KaiLang, products: List<com.shopai.app.brain.tools.ProductRef>, people: List<String>): KaiTurn? {
        val line = if (com.shopai.app.brain.tools.KaiStock.understand(item, products)?.incoming == true) item else "$item vandhuchu"
        return stockChange(line, item, lang, products, people)
    }

    /** After a goods draft is confirmed or dropped: the next one said together, if any. */
    private suspend fun nextGoods(done: KaiTurn?, lang: KaiLang): KaiTurn? {
        if (!goodsOpen() || newItem != null) return done
        if (goodsQueue.isEmpty()) return done
        val next = goodsQueue.removeFirst()
        goodsTurn = conversationState.conversationTurn
        val products = runCatching { tools.products() }.getOrNull() ?: return done
        val people = runCatching { books.snapshot()?.people.orEmpty() }.getOrDefault(emptyList())
        val draft = goodsDraft(next, lang, products, people) ?: return done
        val head = done?.reply?.text?.let { "$it\n\n" }.orEmpty()
        return withText(draft, head + pick(lang, ta = "அடுத்து: ", tl = "Adutha item: ", en = "Next: ") + draft.reply.text +
            if (goodsQueue.isEmpty()) "" else "\n\n" + pick(lang, ta = "இன்னும்: ${goodsQueue.joinToString(", ")}.", tl = "Innum: ${goodsQueue.joinToString(", ")}.", en = "Still to do: ${goodsQueue.joinToString(", ")}."))
    }

    /** The turn a reminder was last set: "adha cancel pannu" after it means the reminder, not an earlier entry. */
    private var lastReminderTurn = -1L

    suspend fun act(action: KaiAction, lang: KaiLang): KaiTurn? {
        if (action is KaiAction.ConfirmReminder) lastReminderTurn = conversationState.conversationTurn
        // In goods mode a confirmed draft goes into the list (saved only on the list's Confirm), then the next one.
        if (action is KaiAction.ConfirmStock && goodsOpen()) goodsKeep(action.key, lang)?.let { return nextGoods(it, lang) }
        when (action) {
            is KaiAction.StatementFor -> {
                val p = runCatching { books.snapshot() }.getOrNull()?.parties
                    ?.firstOrNull { it.id == action.partyId && (it.side == com.shopai.app.brain.Direction.RECEIVABLE) == action.customer } ?: return null
                return statementCard(p, action.from, action.to, lang)
            }
            KaiAction.ConfirmGoods -> return confirmGoods(lang)
            KaiAction.EditGoods -> {
                if (goodsList.isEmpty()) return null
                goodsEditing = true
                goodsTurn = conversationState.conversationTurn
                return say(lang, KaiMood.CLARIFY, null,
                    ta = "எந்த item மாத்தணும் ஓனர்? சொல்லுங்க — உதா: 'Rice 4 மூட்டை' (மாத்தும்), 'Rice வேணாம்' (list-ல இருந்து எடுக்கும்).",
                    tl = "Endha item maathanum Owner? Sollunga — eg: 'Rice 4 moota' (maathum), 'Rice venam' (list-la irundhu edukkum).",
                    en = "Which item should I change, Owner? e.g. 'Rice 4 bags' (changes it), 'remove Rice' (takes it out of the list).")
            }
            KaiAction.CancelGoods -> {
                val n = goodsList.size
                goodsClear()
                tools.log("goods arrived", "inventory", "list of $n cancelled", ActionStatus.CANCELLED)
                return say(lang, KaiMood.NEUTRAL, null, ta = "சரி ஓனர், list ரத்து பண்ணிட்டேன். எதுவும் சேமிக்கல.",
                    tl = "Seri Owner, list cancel pannitten. Edhuvum save aagala.", en = "Okay Owner, the list is cancelled. Nothing was saved.")
            }
            else -> Unit
        }
        val done = actOn(action, lang)
        // "Colgate 2 box, Rice 3 moota": Colgate dropped → Rice's draft next; the last one dropped → "aduthu sollunga".
        if (action is KaiAction.CancelStock && goodsOpen()) {
            if (goodsQueue.isNotEmpty() && newItem == null) return nextGoods(done, lang)
            if (goodsList.isNotEmpty() || goodsTurn >= 0) return done?.let { withText(it, it.reply.text + " " + pick(lang,
                ta = "அடுத்து சொல்லுங்க ஓனர்.", tl = "Aduthu sollunga Owner.", en = "Tell me the next one, Owner.")) }
        }
        return done
    }

    private suspend fun actOn(action: KaiAction, lang: KaiLang): KaiTurn? = when (action) {
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
            unitPick = null
            stockLineAskedTurn = -10L
            productEdit?.takeIf { it.key == action.key }?.let { productEdit = null }
            billItem?.takeIf { it.key == action.key }?.let { billItem = null }
            newItem?.takeIf { it.key == action.key || action.key == "new" }?.let { newItem = null
                tools.log(com.shopai.app.brain.tools.KaiIntents.STOCK_IN, "draft", "${it.spec.name}: new product cancelled", ActionStatus.CANCELLED, null, it.said) }
            say(lang, KaiMood.NEUTRAL, null, ta = "சரி, ரத்து பண்ணிட்டேன். எதுவும் சேமிக்கல.", tl = "Seri Owner, cancel pannitten. Edhuvum save aagala.", en = "Cancelled, Owner. Nothing was saved.")
        }
        is KaiAction.ConfirmPlan -> confirm(action.key, lang)
        is KaiAction.CancelPlan -> cancelPlan(action.key, lang)
        is KaiAction.ChoosePlan -> requests.remove(action.requestKey)?.let { r ->
            val turn = prepared(r, action.kind, action.partyName, action.partyId, lang)
            // "Kumar anna" → the owner picked Kumar Traders: offer to remember the nickname (this shop only).
            val alias = action.partyId?.let { id ->
                val customer = action.kind == PlanKind.PAYMENT_IN || action.kind == PlanKind.CREDIT_GIVEN
                learner?.aliasButton(r.name?.let { n -> KaiEntityResolver.referenceIn(r.said, n) } ?: r.name, com.shopai.app.brain.memory.KnownEntity(id, action.partyName,
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
        goodsClear()
        halfSaid = null
        recentAsks.clear()
        lastReminderTurn = -1L
        kaiAskedAfterDraft = false
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

    /** A Tamil-script record name ("அபி") as the owner typed it in English letters ("abi" → "Abi"); else the name itself. */
    private fun asWritten(name: String, said: String): String {
        if (!Regex("""[\u0B80-\u0BFF]""").containsMatchIn(name)) return name
        val typed = Regex("""[A-Za-z]{2,}""").findAll(said).map { it.value }
            .firstOrNull { w -> w.lowercase(Locale.ROOT) !in notSpellings && !KaiEntityResolver.isFunctionWord(w) && com.shopai.app.util.NameSound.same(w, name) }
            ?.replaceFirstChar { it.titlecase(Locale.ROOT) }
        // "avar pay pannitaru" names no one: the spelling the owner used for them earlier in this chat.
        typed?.let { spellings[name] = it }
        return typed ?: spellings[name] ?: name
    }
    private val spellings = HashMap<String, String>()
    private val notSpellings = setOf("pay", "gpay", "upi", "cash", "full", "paid", "bill", "avar", "avan", "aval", "avanga", "ava", "owner", "bro", "sir", "kai")

    private val fullAmount = Regex("""(?i)(?<![\p{L}])(full|fulla|fullah|full-aa|full\s+amount|full\s+payment|settle|settled|motham|mothama|muzhusa|muzhusaa|muzhu|clear|whole|all|ellam|ellaam|ellathaiyum|ellathayum|ellathaium|ellaathaiyum|ellathiyum|ellathum|yellathaium|yellathaiyum|yellathayum|yellam|""" +
        """fullaa|fullah|muzhusaaga|mothamaa)(?![\p{L}])|முழுசா|மொத்தம்|ஃபுல்|எல்லாத்தையும்|எல்லாத்தயும்|எல்லாமே|எல்லாம்""")

    /** What the books say is pending with the one record [r] names, on the side the payment is for — null when unclear. */
    private suspend fun pendingOf(r: PaymentRequest): BigDecimal? {
        val name = r.name?.takeIf { it.isNotBlank() } ?: return null
        val records = runCatching { tools.parties(name) }.getOrNull()?.let { KaiEntityResolver.sameName(name, it) }?.distinctBy { it.id }.orEmpty()
        val one = pinned(name)?.let { id -> records.firstOrNull { it.id == id } }
            ?: records.filter { it.customer != r.outgoing }.singleOrNull() ?: return null
        return one.balance.takeIf { it.signum() > 0 }
    }

    private suspend fun payment(r0: PaymentRequest, lang: KaiLang): KaiTurn {
        // "Kumar evlo tharanum" → "2000 kuduthan": the one person just talked about (never two; the draft names them for Confirm).
        val onTopic = conversationState.lastPerson?.takeIf {
            conversationState.conversationTurn - conversationState.mentionedTurn <= 3 && conversationState.mentionedPeople.size < 2 }
        // "Dinesh evlo tharanum" → "ellathaiyum kuduthutan" / "settle pannitan": the same person, the whole pending (Confirm first).
        val r = if (r0.name.isNullOrBlank() && (r0.amount != null || fullAmount.containsMatchIn(r0.said)) && onTopic != null) r0.copy(name = onTopic) else r0
        conversationState.currentIntent = "PAYMENT"
        conversationState.currentAction = "DRAFT_PAYMENT"
        conversationState.lastPerson = r.name
        conversationState.lastRelevantEntity = r.name
        conversationState.lastAmount = r.amount
        conversationState.lastPaymentDirection = if (r.outgoing) "OUT" else "IN"
        conversationState.lastPaymentMode = r.mode
        conversationState.lastQuestion = r.said
        if (r.amount == null || r.amount.signum() <= 0) {
            // "Abi full-aa pay pannitaru" / "settle pannitaru": the whole pending amount, from the books (Confirm still saves it).
            // Only when who paid whom is known ("Kumar-ku amount add pannu" says neither): else just "Evlo amount?".
            val pending = if (r.amount == null && r.directionKnown && !r.addEntry) pendingOf(r) else null
            if (pending != null && fullAmount.containsMatchIn(r.said)) return payment(r.copy(amount = pending), lang)
            incompletePayment = r
            conversationState.pendingCorrection = true
            // "Abi pay pannitaru": never guessed — asked, with what the books say is pending.
            if (pending != null) {
                val who = asWritten(r.name!!, r.said)
                val a = KaiFormat.rupees(pending.toDouble())
                return say(lang, KaiMood.CLARIFY, "payment: amount missing",
                    ta = if (r.outgoing) "$who-க்கு எவ்வளவு கொடுத்தீங்க ஓனர்? Full $a-ஆ, இல்ல கொஞ்சம் மட்டும்-ஆ?" else "$who எவ்வளவு கொடுத்தாங்க ஓனர்? Full $a-ஆ, இல்ல கொஞ்சம் மட்டும்-ஆ?",
                    tl = if (r.outgoing) "$who-ku evlo kuduthinga Owner? Full $a-aa, illa konjam mattum-aa?" else "$who evlo kuduthaanga Owner? Full $a-aa, illa konjam mattum-aa?",
                    en = if (r.outgoing) "How much did you pay $who, Owner? The full $a, or part of it?" else "How much did $who pay, Owner? The full $a, or part of it?")
            }
            return say(lang, KaiMood.CLARIFY, "payment: amount missing",
                ta = "எவ்வளவு தொகை ஓனர்?", tl = "Evlo amount Owner?", en = "How much was it, Owner?")
        }
        // "Suresh 2000 kuduthan" with no Suresh in the books: Suresh, asked about as a new name — never "Yaar kitta vaanguninga?".
        if (r.name.isNullOrBlank()) com.shopai.app.brain.KaiUnderstanding.personPhraseIn(r.said)
            ?.takeIf { p -> r.said.trimStart().startsWith(p, ignoreCase = true) && Regex("""[\p{L}\p{M} ]+""").matches(p) && !notAName(p) && p.split(' ').size <= 3 }
            ?.let { return payment(r.copy(name = it), lang) }
        if (r.name.isNullOrBlank()) {
            incompletePayment = r
            conversationState.pendingCorrection = true
            return say(lang, KaiMood.CLARIFY, "payment: person missing",
                ta = if (!r.directionKnown) "யாருக்கு / யார்கிட்ட ஓனர்?" else if (r.outgoing) "யாருக்கு கொடுத்தீங்க ஓனர்?" else "யார் கொடுத்தாங்க ஓனர்?",
                tl = if (r.outgoing) "Yaarukku kuduthinga Owner?" else "Yaar kitta vaanguninga Owner?",
                en = if (r.outgoing) "Who did you give it to, Owner?" else "Who did you receive it from, Owner?")
        }
        if (!r.addEntry && !r.stated && pinned(r.name) == null) {
            val customerSide = tools.parties(r.name)?.let { KaiEntityResolver.sameName(r.name, it) }?.distinctBy { it.id }?.singleOrNull()?.customer
            if (customerSide != null) {
                val kind = when {
                    r.outgoing && !customerSide -> PlanKind.PAYMENT_OUT
                    r.outgoing -> PlanKind.CREDIT_GIVEN
                    customerSide -> PlanKind.PAYMENT_IN
                    else -> PlanKind.DEBIT_TAKEN
                }
                relationQuestion(r.name, r.said, r.amount, customerSide, r.dueDate, lang, kind, r.outgoing, r.mode)?.let { return it }
            }
        }
        val matches = tools.parties(r.name) ?: return say(lang, KaiMood.ERROR, "payment: books unavailable",
            ta = "உங்க கணக்கு புத்தகம் இன்னும் ரெடி ஆகல ஓனர் — இப்போ இதை சேமிக்க முடியாது.",
            tl = "Owner, books innum ready aagala — idha ippo save panna mudiyadhu.",
            en = "I couldn't reach your business records, Owner — I can't save this right now.")
        // "Lokesh" with "Lokesh" and "Madurai Lokesh" in the books: both — the owner says which.
        val exact = KaiEntityResolver.sameName(r.name, matches).distinctBy { it.id }
        val candidates = (exact.ifEmpty { matches }).take(6)
        // Who they are decides what the money means.
        fun kindFor(p: PartyMatch) = when {
            r.addEntry -> if (p.customer) PlanKind.CREDIT_GIVEN else PlanKind.DEBIT_TAKEN
            r.bySide -> if (p.customer) PlanKind.PAYMENT_IN else PlanKind.PAYMENT_OUT
            r.outgoing && !p.customer -> PlanKind.PAYMENT_OUT
            r.outgoing && p.customer -> PlanKind.CREDIT_GIVEN
            !r.outgoing && p.customer -> PlanKind.PAYMENT_IN
            else -> PlanKind.DEBIT_TAKEN
        }
        if (candidates.size == 1 && exact.size == 1) {
            val p = candidates.single()
            return prepared(r, kindFor(p), p.name, p.id, lang)
        }
        // Same name, several records: a phone / place / shop word in the sentence, or the one being talked about, picks one.
        if (exact.size > 1) (resolveParty(r.name, r.said, exact, conversationState.focusPartyId) as? KaiEntityResolver.Result.One)?.let { one ->
            chosenLabels[one.party.id] = labelOf(one.party)
            return prepared(r, kindFor(one.party), one.party.name, one.party.id, lang)
        }
        requests[r.key] = r
        val amount = KaiFormat.rupees(r.amount.toDouble())
        if (candidates.isEmpty() && r.bySide) { requests.remove(r.key); return askPaymentDirection(lang, r.name) }
        if (candidates.isEmpty()) {
            // "Muthu ku 1500 credit kuduthen" / "Muthu kitta kadan-la vaanginen": the owner already said what it is — a draft for a
            // new person straight away (Confirm still saves it). Only a bare "kuduthen" / "vaanginen" is asked.
            if (Regex("""(?i)(?<![\p{L}])(credit|kadan|kadanaa|kadana|udhaar|udhar)(?![\p{L}])|கடன்""").containsMatchIn(r.said))
                return prepared(r, if (r.outgoing) PlanKind.CREDIT_GIVEN else PlanKind.DEBIT_TAKEN, r.name, null, lang)
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
        // Several people with this name (or a customer and a supplier): the owner picks — by button, or by saying which.
        planChoice = candidates.map { p -> p to KaiAction.ChoosePlan(r.key, kindFor(p), p.id, p.name) }
        val buttons = candidates.map { p ->
            val role = if (p.customer) pick(lang, ta = "வாடிக்கையாளர்", tl = "Customer", en = "Customer") else pick(lang, ta = "சப்ளையர்", tl = "Supplier", en = "Supplier")
            KaiButton("${labelOf(p)} · $role · ${KaiFormat.rupees(p.balance.toDouble())}", KaiAction.ChoosePlan(r.key, kindFor(p), p.id, p.name))
        }
        candidates.forEach { p -> chosenLabels[p.id] = labelOf(p) }
        // The choices are said in the words too — a voice owner hears them (the buttons are only on the screen).
        val choices = candidates.joinToString(" / ") { p -> labelOf(p) + if (p.customer) "" else " (${pick(lang, ta = "சப்ளையர்", tl = "Supplier", en = "Supplier")})" }
        return KaiTurn(
            ChatReply(pick(lang, ta = "எந்த ${r.name} ஓனர்? $choices", tl = "Endha ${r.name} Owner? $choices", en = "Which ${r.name}, Owner? $choices"), KaiMood.CLARIFY, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(emptyList(), buttons + KaiButton(cancelLabel(lang), KaiAction.CancelRequest(r.key))),
        )
    }

    private suspend fun prepared(r: PaymentRequest, kind: PlanKind, name: String, partyId: String?, lang: KaiLang): KaiTurn {
        val prepared = tools.prepare(kind, name, partyId, r.amount!!, r.mode, r.said)
            ?: return say(lang, KaiMood.ERROR, "draft failed", ta = "இதை இப்போ தயார் பண்ண முடியல ஓனர்.", tl = "Owner, idha ippo ready panna mudiyala.", en = "I couldn't prepare this right now, Owner.")
        // Above the ledger's limit (₹1 crore): never confirmable — a slip of the tongue or an extra zero (principle 5).
        val tooBig = com.shopai.app.util.exceedsMaxLedgerAmount(prepared.amount.toDouble())
        val plan = if (tooBig) prepared.copy(problems = prepared.problems + pick(lang,
            ta = "₹1 கோடிக்கு மேல ஒரு entry போட முடியாது — சரியான தொகை சொல்லுங்க.",
            tl = "₹1 crore-ku mela oru entry poda mudiyaadhu — sariyaana amount sollunga.",
            en = "An entry can't be above ₹1 crore — please say the right amount.")) else prepared
        // The same entry as the one just saved (same person, amount and kind): said before Confirm — a second one only if meant.
        val repeat = conversationState.lastSaved?.takeIf { s -> conversationState.conversationTurn - s.turn <= 5 && s.kind == kind &&
            s.amount.compareTo(plan.amount) == 0 && samePerson(s.person, plan.partyName) }
        // A short reference for the owner (the draft's internal id stays in the log result).
        val ref = tools.log("${kind.name.lowercase()} ${plan.partyName}", "draft", "${KaiFormat.rupees(plan.amount.toDouble())} ${plan.mode} draft=${plan.key}", ActionStatus.DRAFT, null)
        plans[plan.key] = plan.copy(reference = ref, dueDate = r.dueDate ?: plan.dueDate)
        if (r.stated) conversationState.statedDraftKey = plan.key
        conversationState.draftShownTurn = conversationState.conversationTurn
        conversationState.pendingDraft = plans[plan.key]
        conversationState.pendingConfirmation = true
        conversationState.pendingCorrection = false
        conversationState.currentIntent = "PAYMENT_DRAFT"
        conversationState.currentAction = plan.kind.name
        conversationState.lastPerson = plan.partyName
        conversationState.lastRelevantEntity = plan.partyName
        conversationState.lastAmount = plan.amount
        // One meaning everywhere: "IN" = money the owner receives (credit given, payment in), "OUT" = money the owner pays.
        conversationState.lastPaymentDirection = if (plan.kind == PlanKind.PAYMENT_IN || plan.kind == PlanKind.CREDIT_GIVEN) "IN" else "OUT"
        conversationState.lastPaymentMode = plan.mode
        lastDraft = r.said to "PAYMENT"
        val a = KaiFormat.rupees(plan.amount.toDouble())
        val what = when (kind) {
            PlanKind.PAYMENT_OUT -> pick(lang, ta = "பணம் கொடுத்தது (payment out)", tl = "Payment out", en = "Payment out")
            PlanKind.PAYMENT_IN -> pick(lang, ta = "பணம் வந்தது (payment in)", tl = "Payment in", en = "Payment in")
            PlanKind.CREDIT_GIVEN -> pick(lang, ta = "Credit — அவர் உங்களுக்கு தரணும்", tl = "Credit — avar ungalukku tharanum", en = "Credit — they owe you")
            PlanKind.DEBIT_TAKEN -> pick(lang, ta = "Debit — நீங்க அவருக்கு தரணும்", tl = "Debit — neenga avarukku tharanum", en = "Debit — you owe them")
        }
        // "Akash 6000 kuduthutan" with ₹5,000 owed: the ₹1,000 more is said before Confirm — never lost in silence.
        val owed = if (kind == PlanKind.PAYMENT_IN || kind == PlanKind.PAYMENT_OUT) plan.balanceBefore ?: pendingOf(partyId) else null
        val extra = owed?.let { plan.amount - it }?.takeIf { it.signum() > 0 && owed.signum() > 0 }
        val lines = buildList {
            // "Nagapattinam Lokesh — ₹500": which of the same-named records this is.
            add("${conversationState.stated?.takeIf { r.stated && it.partyId != null && it.partyId == partyId }?.label ?: ownerName(plan.partyId, plan.partyName)} — $a")
            add(what)
            add(pick(lang, ta = "முறை: ${modeName(plan.mode, lang)}", tl = "Mode: ${modeName(plan.mode, lang)}", en = "Mode: ${modeName(plan.mode, lang)}"))
            // A stated payment ("Mahesh enaku 2000 tharanum"): its due date, and whether the person is new to the books.
            if (r.stated) {
                // Already owed something: this is a separate NEW entry — the old amount is not edited.
                val before = plan.balanceBefore ?: conversationState.stated?.takeIf { partyId != null }?.let { recordedPending(it) }
                if (before != null && before.signum() > 0) {
                    add(pick(lang, ta = "புது entry: $a (பழைய ${KaiFormat.rupees(before.toDouble())} மாறாது)",
                        tl = "Pudhu entry: $a (pazhaya ${KaiFormat.rupees(before.toDouble())} maaraadhu)",
                        en = "New entry: $a (the existing ${KaiFormat.rupees(before.toDouble())} is unchanged)"))
                    if (plan.balanceBefore == null) add(pick(lang, ta = "பாக்கி: ", tl = "Balance: ", en = "Balance: ") +
                        "${KaiFormat.rupees(before.toDouble())} → ${KaiFormat.rupees((before + plan.amount).toDouble())}")
                }
                val due = plans[plan.key]?.dueDate
                add(if (due != null) pick(lang, ta = "Due date: ${KaiFormat.date(due, lang, now().toLocalDate())}", tl = "Due date: ${KaiFormat.date(due, lang, now().toLocalDate())}", en = "Due date: ${KaiFormat.date(due, lang, now().toLocalDate())}")
                    else pick(lang, ta = "Due date: இல்லை", tl = "Due date: illa", en = "Due date: not set"))
                if (partyId == null) add(if (kind == PlanKind.CREDIT_GIVEN) pick(lang, ta = "புது வாடிக்கையாளர்-ஆ சேரும்", tl = "Puthu customer-ah add aagum", en = "Will be added as a new customer")
                    else pick(lang, ta = "புது சப்ளையர்-ஆ சேரும்", tl = "Puthu supplier-ah add aagum", en = "Will be added as a new supplier"))
            }
            if (plan.settles.isNotEmpty()) add(pick(lang, ta = "சரி செய்வது: ", tl = "Settles: ", en = "Settles: ") +
                plan.settles.joinToString(", ") { (n, v) -> "$n ${KaiFormat.rupees(v.toDouble())}" })
            if (plan.advance.signum() == 0 && extra != null) add(pick(lang, ta = "${KaiFormat.rupees(extra.toDouble())} advance-ஆ சேமிக்கப்படும்",
                tl = "${KaiFormat.rupees(extra.toDouble())} advance-ah save aagum", en = "${KaiFormat.rupees(extra.toDouble())} will be kept as advance"))
            if (plan.advance.signum() > 0) add(pick(lang, ta = "${KaiFormat.rupees(plan.advance.toDouble())} advance-ஆ சேமிக்கப்படும்",
                tl = "${KaiFormat.rupees(plan.advance.toDouble())} advance-ah save aagum", en = "${KaiFormat.rupees(plan.advance.toDouble())} will be kept as advance"))
            if (plan.balanceBefore != null && plan.balanceAfter != null) add(pick(lang, ta = "பாக்கி: ", tl = "Balance: ", en = "Balance: ") +
                "${KaiFormat.rupees(plan.balanceBefore.toDouble())} → ${KaiFormat.rupees(plan.balanceAfter.toDouble())}")
            add(pick(lang, ta = "குறிப்பு எண்: $ref", tl = "Ref: $ref", en = "Ref: $ref"))
        }
        val blocked = plan.problems.isNotEmpty()
        val repeatNote = repeat?.let { s ->
            val a2 = KaiFormat.rupees(s.amount.toDouble())
            pick(lang,
                ta = "ஓனர், இப்போ தான் ${s.person} $a2 சேமிச்சோம் (${s.reference}). இது இன்னொரு $a2-ஆ? ஆமா-னா உறுதி செய்யுங்க. ",
                tl = "Owner, ippo dhaan ${s.person} $a2 save pannom (${s.reference}). Idhu innoru $a2-aa? Aama-na Confirm pannunga. ",
                en = "Owner, ${s.person} $a2 was just saved (${s.reference}). Is this another $a2? If so, confirm. ")
        }
        val intro = (repeatNote ?: "") + if (extra != null && owed != null && !tooBig) {
            val o = KaiFormat.rupees(owed.toDouble()); val a2 = KaiFormat.rupees(plan.amount.toDouble()); val e = KaiFormat.rupees(extra.toDouble())
            val who = ownerName(plan.partyId, plan.partyName)
            if (kind == PlanKind.PAYMENT_IN) pick(lang,
                ta = "ஓனர், $who $o தான் தரணும்; $a2 வந்தா $e கூடுதல் — advance-ஆ சேமிக்கப்படும். சேர்க்கட்டுமா?",
                tl = "Owner, $who $o dhaan tharanum; $a2 vandha $e extra — advance-ah save aagum. Add pannalama?",
                en = "Owner, $who owes only $o; $a2 received is $e more — it will be kept as advance. Add this?")
            else pick(lang,
                ta = "ஓனர், $who-க்கு $o தான் கொடுக்கணும்; $a2 கொடுத்தா $e கூடுதல் — advance-ஆ சேமிக்கப்படும். சேர்க்கட்டுமா?",
                tl = "Owner, $who-ku $o dhaan kudukkanum; $a2 kuduthaa $e extra — advance-ah save aagum. Add pannalama?",
                en = "Owner, you owe $who only $o; paying $a2 is $e more — it will be kept as advance. Add this?")
        } else pick(lang,
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
                warning = (plan.problems + listOfNotNull(repeat?.let { s -> pick(lang, ta = "ஏற்கனவே சேமிச்சது: ${s.reference}", tl = "Already saved: ${s.reference}", en = "Already saved: ${s.reference}") }))
                    .takeIf { it.isNotEmpty() }?.joinToString("\n"),
            ),
            plans[plan.key],
        ).also { draftTurns[plan.key] = it }
    }

    /** The card each open draft was shown with — shown again (with its Confirm) when the owner asks "add pannitiya?". */
    private val draftTurns = HashMap<String, KaiTurn>()

    private suspend fun confirm(key: String, lang: KaiLang): KaiTurn? {
        val plan = plans.remove(key) ?: return null
        // What was owed before this payment: more than that is an advance, said with the save.
        val owedBefore = if (plan.kind == PlanKind.PAYMENT_IN || plan.kind == PlanKind.PAYMENT_OUT) plan.balanceBefore ?: pendingOf(plan.partyId) else null
        val advance = owedBefore?.let { plan.amount - it }?.takeIf { it.signum() > 0 && owedBefore.signum() > 0 }
        val adv = advance?.let { KaiFormat.rupees(it.toDouble()) }
        conversationState.pendingDraft = null
        conversationState.pendingConfirmation = false
        return when (val outcome = tools.confirm(plan)) {
            is ActionOutcome.Done -> {
                tools.log("${plan.kind.name.lowercase()} ${plan.partyName}", "transaction engine", "saved ${outcome.reference}", ActionStatus.CONFIRMED, plan.reference ?: plan.key)
                // The same stated payment is never drafted again, whatever the read-back says (no duplicate entry).
                if (plan.key == conversationState.statedDraftKey) {
                    conversationState.stated = null
                    conversationState.statedDraftKey = null
                }
                // Read the books back before saying it is saved: the entry's party, on the right side, with the balance the engine reported.
                books.changed()
                if (!readBack(plan, outcome)) {
                    tools.log("${plan.kind.name.lowercase()} ${plan.partyName}", "read-back", "not verified ${outcome.reference}", ActionStatus.FAILED, plan.reference ?: plan.key)
                    return say(lang, KaiMood.CONCERNED, null,
                        ta = "ஓனர், entry அனுப்பிட்டேன் (${outcome.reference}), ஆனா books-ல திரும்ப படிச்சு சரிபார்க்க முடியல. Collect / Pay screen-ல பாருங்க — திரும்ப save பண்ணாதீங்க.",
                        tl = "Owner, entry anuppitten (${outcome.reference}), aanaa books-la thirumba padichu check panna mudiyala. Collect / Pay screen-la paarunga — thirumba save pannaadheenga.",
                        en = "Owner, I sent the entry (${outcome.reference}) but couldn't read it back from the books to verify. Please check the Collect / Pay screen — don't save it again.")
                }
                // Only the engine's Done, read back from the books, makes "add pannitten" true.
                personOnTopic = plan.partyName to conversationState.conversationTurn
                advanceTold = adv?.let { plan.partyName to it }
                conversationState.lastSaved = KaiSavedPayment(plan.partyName, plan.amount, outcome.reference, plan.dueDate,
                    receivable = plan.kind == PlanKind.CREDIT_GIVEN || plan.kind == PlanKind.PAYMENT_IN,
                    partyId = plan.partyId, kind = plan.kind, turn = conversationState.conversationTurn)
                val a = KaiFormat.rupees(plan.amount.toDouble())
                val after = outcome.balanceAfter?.let { KaiFormat.rupees(it.toDouble()) }
                val due = plan.dueDate?.let { KaiFormat.date(it, lang, now().toLocalDate()) }
                say(lang, KaiMood.SUCCESS, null,
                    ta = "சேமிச்சுட்டேன் ஓனர். ${ownerName(plan.partyId, plan.partyName)} — $a (${outcome.reference})." + (due?.let { " Due: $it." } ?: "") + (after?.let { " இப்போ பாக்கி $it." } ?: "") +
                        (adv?.let { " $it advance-ஆ இருக்கு." } ?: ""),
                    tl = "Save aagiduchu Owner. ${ownerName(plan.partyId, plan.partyName)} — $a (${outcome.reference})." + (due?.let { " Due: $it." } ?: "") + (after?.let { " Ippo balance $it." } ?: "") +
                        (adv?.let { " $it advance-aa irukku." } ?: ""),
                    en = "Saved, Owner. ${ownerName(plan.partyId, plan.partyName)} — $a (${outcome.reference})." + (due?.let { " Due: $it." } ?: "") + (after?.let { " Balance now $it." } ?: "") +
                        (adv?.let { " $it is kept as advance." } ?: ""))
            }
            is ActionOutcome.Failed -> {
                tools.log("${plan.kind.name.lowercase()} ${plan.partyName}", "transaction engine", outcome.reason, ActionStatus.FAILED, plan.reference ?: plan.key)
                // Not saved: the stated payment stays (the owner can try again) and nothing claims it was added.
                if (plan.key == conversationState.statedDraftKey) conversationState.statedDraftKey = null
                say(lang, KaiMood.ERROR, null,
                    ta = "ஓனர், சேமிக்க முடியல. நான் தொகையை சேமிக்கல. (${outcome.reason})",
                    tl = "Owner, save aagala. Naan amount-a save pannala. (${outcome.reason})",
                    en = "Owner, it wasn't saved. I did not save the amount. (${outcome.reason})")
            }
        }
    }

    /**
     * The saved entry, read back from the ledger (not a cached copy): the party is there on the entry's side, and its
     * balance is the one the engine reported. False when the books can't be read or disagree.
     */
    private suspend fun readBack(plan: ActionPlan, outcome: ActionOutcome.Done): Boolean {
        val snapshot = runCatching { books.snapshot() }.getOrNull() ?: return false
        val side = if (plan.kind == PlanKind.CREDIT_GIVEN || plan.kind == PlanKind.PAYMENT_IN) com.shopai.app.brain.Direction.RECEIVABLE
            else com.shopai.app.brain.Direction.PAYABLE
        val onSide = snapshot.parties.filter { it.side == side }
        val party = plan.partyId?.let { id -> onSide.firstOrNull { it.id == id } }
            ?: onSide.filter { it.name.equals(plan.partyName, ignoreCase = true) }.let { same ->
                // A new party saved by name: the one whose balance is the engine's.
                same.singleOrNull() ?: outcome.balanceAfter?.let { b -> same.firstOrNull { p -> kotlin.math.abs(p.pending - b.toDouble()) < 0.005 } }
            }
            ?: return false
        val expected = outcome.balanceAfter ?: return true
        return kotlin.math.abs(party.pending - expected.toDouble()) < 0.005
    }

    private suspend fun cancelPlan(key: String, lang: KaiLang): KaiTurn {
        plans.remove(key)?.let {
            conversationState.pendingDraft = null
            conversationState.pendingConfirmation = false
            conversationState.pendingCorrection = false
            if (it.key == conversationState.statedDraftKey) {
                conversationState.stated = null
                conversationState.statedDraftKey = null
            }
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
    /**
     * "Intha bill la irukura stock ah add pannu" (in), "… kammi pannu" (out), "purchase-na add, sales-na less" (by the bill):
     * the scanner opens; the bill's credit / debit entry goes as always, and its items are ticked to move the stock that way.
     * Nothing is saved until the owner confirms the bill.
     */
    /** The turn Kai asked "intha bill-oda stock-a add pannanuma, illa kammi pannanuma?", and the language it asked in. */
    private var billStockAskTurn = -10L
    private var billStockAskLang = KaiLang.TANGLISH

    /** "add pannu" / "kammi" / "sales bill" → that way; "theriyala" → by the bill (purchase in, sale out); anything else moves on. */
    private fun billStockAnswered(said: String, lang: KaiLang): KaiTurn? {
        if (conversationState.conversationTurn != billStockAskTurn + 1) return null
        billStockAskTurn = -10L
        val l = if (lang == KaiLang.ENGLISH && said.trim().split(Regex("""\s+""")).size <= 3) billStockAskLang else lang
        com.shopai.app.brain.tools.KaiIntents.stockWay(said)?.let { way ->
            return billStockScan(com.shopai.app.brain.tools.KaiIntents.BillStockAsk(way, rule = false), l, said)
        }
        if (Regex("""(?i)(?<![\p{L}])(theriyala|therila|teriyala|theriyalai|don'?t\s+know|not\s+sure|nee\s+paaru|neeye\s+paaru)(?![\p{L}])|தெரியல""").containsMatchIn(said))
            return billStockScan(com.shopai.app.brain.tools.KaiIntents.BillStockAsk(null, rule = false), l, said)
        return null
    }

    private fun billStockScan(ask: com.shopai.app.brain.tools.KaiIntents.BillStockAsk, lang: KaiLang, said: String): KaiTurn {
        if (ask.unclear) {
            billStockAskTurn = conversationState.conversationTurn
            billStockAskLang = lang
            return say(lang, KaiMood.CLARIFY, "bill stock: which way",
                ta = "ஓனர், இந்த பில்லில் இருக்குற stock-ஐ சேர்க்கணுமா (purchase பில்), இல்ல குறைக்கணுமா (sales பில்)?",
                tl = "Owner, intha bill-la irukra stock-a add pannanuma (purchase bill), illa kammi pannanuma (sales bill)?",
                en = "Owner, should this bill's stock be added (a purchase bill) or taken out (a sales bill)?")
        }
        com.shopai.app.util.BillStock.Request.ask(ask.way)
        tools.log(com.shopai.app.brain.tools.KaiIntents.OPEN_BILL_SCANNER, "bill scanner", "camera opened (stock ${ask.way ?: "by bill"})", ActionStatus.OPENED, null, said)
        val text = when (ask.way) {
            com.shopai.app.util.BillStock.Way.IN -> pick(lang,
                ta = "சரி ஓனர், பில்லை ஸ்கேன் பண்ணுங்க 📷 அதுல இருக்குற items stock-ல சேரும். Credit / debit entry எப்பவும் போல வரும் — நீங்க Confirm பண்ணின பிறகுதான் ரெண்டும் சேமிக்கும்.",
                tl = "Seri Owner, bill-a scan pannunga 📷 Adhula irukra items stock-la serum. Credit / debit entry eppavum pola varum — neenga Confirm pannina apram dhaan rendum save aagum.",
                en = "Okay Owner, scan the bill 📷 Its items will be added to stock. The credit / debit entry goes as always — both are saved only after you confirm.")
            com.shopai.app.util.BillStock.Way.OUT -> pick(lang,
                ta = "சரி ஓனர், பில்லை ஸ்கேன் பண்ணுங்க 📷 அதுல இருக்குற items stock-ல இருந்து குறையும். Credit / debit entry எப்பவும் போல வரும் — நீங்க Confirm பண்ணின பிறகுதான் ரெண்டும் சேமிக்கும்.",
                tl = "Seri Owner, bill-a scan pannunga 📷 Adhula irukra items stock-la irundhu kuraiyum. Credit / debit entry eppavum pola varum — neenga Confirm pannina apram dhaan rendum save aagum.",
                en = "Okay Owner, scan the bill 📷 Its items will be taken out of stock. The credit / debit entry goes as always — both are saved only after you confirm.")
            null -> pick(lang,
                ta = "சரி ஓனர் — purchase பில் ஸ்கேன் பண்ணினா அதுல இருக்குற stock சேரும், sales பில்னா stock குறையும். Credit / debit entry எப்பவும் போல. Confirm பண்ணின பிறகுதான் சேமிக்கும்.",
                tl = "Seri Owner — purchase bill scan pannina adhula irukra stock serum, sales bill-na stock kuraiyum. Credit / debit entry eppavum pola. Confirm pannina apram dhaan save aagum.",
                en = "Okay Owner — scan a purchase bill and its stock is added; a sales bill takes it out. The credit / debit entry goes as always. Saved only after you confirm.")
        }
        val button = KaiButton(pick(lang, ta = "பில் ஸ்கேன்", tl = "Scan bill", en = "Scan bill"), KaiAction.OpenScanner, primary = true)
        return KaiTurn(ChatReply(text, KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY), KaiCard(emptyList(), listOf(button)),
            // The rule said ("purchase-na add pannidu"): the button, not the camera at once.
            direct = if (ask.rule) null else KaiAction.OpenScanner)
    }

    private fun scan(classifyOnly: Boolean, lang: KaiLang, said: String = ""): KaiTurn {
        // A plain "bill scan pannu": the stock goes by the bill (purchase in, sale out).
        com.shopai.app.util.BillStock.Request.ask(null)
        tools.log(com.shopai.app.brain.tools.KaiIntents.OPEN_BILL_SCANNER, "bill scanner", if (classifyOnly) "camera opened (purchase / sale check)" else "camera opened", ActionStatus.OPENED, null, said)
        val text = if (classifyOnly) pick(lang,
            ta = "சரி ஓனர், பில் ஸ்கேன் பண்ணலாம் 📷 இது purchase-ஆ sale-ஆ என்று பார்த்து சொல்லுவேன் — நீங்க உறுதி செய்த பிறகுதான் சேமிப்பு.",
            tl = "Sure Owner, bill scan pannalam 📷 Idhu purchase-ah sale-ah nu paathu solluven — neenga confirm pannina apram dhaan save.",
            en = "Sure Owner, let's scan the bill 📷 I'll tell you if it's a purchase or a sale — nothing is saved until you confirm.")
        else pick(lang,
            ta = "சரி ஓனர், பில் ஸ்கேன் பண்ணலாம் 📷 Purchase பில்னா அதுல இருக்குற stock சேரும், sales பில்னா குறையும் — Confirm பண்ணின பிறகுதான்.",
            tl = "Sure Owner, bill scan pannalam 📷 Purchase bill-na adhula irukra stock serum, sales bill-na kuraiyum — Confirm pannina apram dhaan.",
            en = "Sure Owner, let's scan the bill 📷 A purchase bill adds its stock, a sales bill takes it out — only after you confirm.")
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
            // "Colgate 240 pieces — 5 boxes": the product's own pack size, when it fits exactly.
            val packs = lastProduct?.takeIf { it.name.equals(f.name, ignoreCase = true) }
                ?.let { com.shopai.app.brain.tools.KaiInventory.packView(it, f.qty) }?.takeIf { !it.contains('+') }?.let { " ($it)" }.orEmpty()
            return say(lang, KaiMood.EXPLAINING, null, ta = "${f.name} stock $q$packs இருக்கு ஓனர்.", tl = "Owner, ${f.name} stock $q$packs irukku.", en = "${f.name}: $q$packs in stock, Owner.")
        }
        return KaiTurn(
            ChatReply(pick(lang, ta = "Stock விவரம் ஓனர்:", tl = "Owner, stock details:", en = "Stock, Owner:"), KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(facts.take(10).map { "${it.name} — ${qty(it.qty, it.unit)}" }, emptyList()),
        )
    }

    /** Words around a product name in a stock question — never part of the name. */
    private val stockFiller = setOf("stock", "evlo", "evvalavu", "ewlo", "yevlo", "irukku", "iruku", "irukka", "irukkaa", "iruka", "irukunu", "irukkunu",
        "irukanu", "irukkanu", "irukaa", "enna", "ennenna", "ippo", "ippa", "ipo", "namma", "namba", "kitta", "la", "ah", "aa", "nu", "sollu", "sollunga",
        "kelu", "how", "much", "many", "left", "in", "the", "of", "is", "what", "total", "motham", "mothama", "ellaa", "ella", "all", "inventory", "kadaila",
        "shop", "kai", "owner", "current", "now", "count", "available", "quantity", "qty", "irukkunu", "theriyuma", "paaru", "paru",
        "எவ்வளவு", "இருக்கு", "இருக்கா", "ஸ்டாக்", "என்ன", "இப்ப", "இப்போ", "நம்ம", "கிட்ட", "மொத்தம்", "சொல்லு")

    /** A word of the question names a word of the product: same, a spelling short of it ("flak"/"flake"), one letter off, or said in Tamil. */
    private fun wordFits(q: String, p: String): Boolean {
        if (q.equals(p, ignoreCase = true)) return true
        if (q.any(Char::isDigit) || p.any(Char::isDigit)) return false
        val a = q.lowercase(Locale.ROOT); val b = p.lowercase(Locale.ROOT)
        if (minOf(a.length, b.length) >= 3 && (a.startsWith(b) || b.startsWith(a)) && kotlin.math.abs(a.length - b.length) <= 2) return true
        if (minOf(a.length, b.length) >= 4 && editDistance(a, b) <= 1) return true
        // "லைட்ஸ்" is "Lights": the "gh" of "-ght" isn't said.
        return com.shopai.app.util.NameSound.same(q, p) || com.shopai.app.util.NameSound.same(q, p.replace(Regex("""(?i)gh(?=t|$)"""), ""))
    }

    private fun editDistance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = t }
        }
        return d[b.length]
    }

    /**
     * "pipe evlo iruku" with "5 inch pipe" and "10 inch pipe": each one and the total — the products whose names hold every word
     * asked (one product: its stock as usual). Null when the words name no product.
     */
    private suspend fun stockByWords(text: String, lang: KaiLang): KaiTurn? {
        val words = text.split(Regex("""[^\p{L}\p{M}\d]+""")).filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in stockFiller }
        if (words.isEmpty() || words.none { w -> w.any(Char::isLetter) } || words.size > 4) return null
        val all = tools.stock(null) ?: return null
        val found = all.filter { f -> val names = f.name.split(Regex("""[^\p{L}\p{M}\d]+""")).filter { it.isNotBlank() }
            words.all { w -> names.any { n -> wordFits(w, n) } } }
        if (found.isEmpty()) return null
        if (found.size == 1) return stock(found.single().name, lang)
        tools.log("stock by word ${words.joinToString(" ")}", "inventory", "${found.size} items", ActionStatus.ANSWERED)
        val asked = words.joinToString(" ")
        val lines = found.take(15).joinToString("\n") { "${it.name} — ${qty(it.qty, it.unit)}" }
        val unit = found.map { it.unit.uppercase(Locale.ROOT) }.distinct().singleOrNull()
        val total = unit?.let { qty(found.fold(BigDecimal.ZERO) { s, f -> s + f.qty }, found.first().unit) }
        return say(lang, KaiMood.EXPLAINING, "stock by word",
            ta = "ஓனர், $asked — ${found.size} பொருள்:\n$lines" + (total?.let { "\nமொத்தம் $it." } ?: ""),
            tl = "Owner, $asked — ${found.size} product:\n$lines" + (total?.let { "\nMothama $it." } ?: ""),
            en = "Owner, $asked — ${found.size} products:\n$lines" + (total?.let { "\nIn all: $it." } ?: ""))
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

    /** "fast move aguthu" / "not moving": how far back the sales are read — the period said, else the last 30 days. */
    private fun movementPeriod(text: String, today: LocalDate, people: List<String>): ChatPeriod =
        KaiChatUnderstanding.understand(text, today, people).period?.takeIf { !it.from.isAfter(today) }
            ?: ChatPeriod(today.minusDays(29), today, ChatPeriod.Kind.LAST_DAYS)

    private fun movementLabel(period: ChatPeriod, lang: KaiLang, today: LocalDate): String =
        if (period.kind == ChatPeriod.Kind.LAST_DAYS) {
            val n = java.time.temporal.ChronoUnit.DAYS.between(period.from, minOf(period.to, today)) + 1
            pick(lang, ta = "கடந்த $n நாள்ல", tl = "kadandha $n naal-la", en = "in the last $n days")
        } else periodName(period, lang, today)

    private suspend fun topProducts(text: String, lang: KaiLang, today: LocalDate, people: List<String>): KaiTurn {
        val q = KaiChatUnderstanding.understand(text, today, people)
        // "Yentha stock fast move aguthu?": the last 30 days of sales; "indha maasam adhigama vithadhu": this month.
        val moving = Regex("""(?i)(?<![\p{L}])(fast|vegama|seekiram|sikkiram|moving|move)(?![\p{L}])|வேகமா""").containsMatchIn(text)
        val period = q.period ?: if (moving) movementPeriod(text, today, people) else ChatPeriod(today.withDayOfMonth(1), today, ChatPeriod.Kind.THIS_MONTH)
        val top = tools.topProducts(period.from, minOf(period.to, today), 5) ?: return unverified(lang, "top products")
        tools.log("top products", "sales ledger", top.joinToString { it.name }, ActionStatus.ANSWERED)
        if (top.isEmpty()) return say(lang, KaiMood.CLARIFY, null,
            ta = "இந்த காலத்துல item sales பதிவுல இல்ல ஓனர்.", tl = "Owner, indha period-la item sales record illa.", en = "There are no item sales recorded for that period, Owner.")
        val best = top.first()
        val units = if (moving) runCatching { tools.stock(null) }.getOrNull().orEmpty().associate { it.name.lowercase(Locale.ROOT) to it.unit } else emptyMap()
        fun sold(p: com.shopai.app.brain.tools.ProductSalesFact) = units[p.name.lowercase(Locale.ROOT)]?.let { u -> qty(p.qty, u) + " — " }.orEmpty()
        val reply = if (moving) {
            val w = movementLabel(period, lang, today)
            pick(lang,
                ta = "ஓனர், $w வேகமா move ஆனது ${best.name} — ${sold(best)}${KaiFormat.rupees(best.value.toDouble())}.",
                tl = "Owner, $w fast-aa move aanadhu ${best.name} — ${sold(best)}${KaiFormat.rupees(best.value.toDouble())}.",
                en = "Owner, your fastest mover $w is ${best.name} — ${sold(best)}${KaiFormat.rupees(best.value.toDouble())}.")
        } else pick(lang,
            ta = "அதிகம் விற்றது ${best.name} — ${KaiFormat.rupees(best.value.toDouble())} ஓனர்.",
            tl = "Owner, adhigama vithadhu ${best.name} — ${KaiFormat.rupees(best.value.toDouble())}.",
            en = "Your best seller is ${best.name} — ${KaiFormat.rupees(best.value.toDouble())}, Owner.")
        return KaiTurn(
            ChatReply(reply, KaiMood.HAPPY, ChatIntent.MONTHLY_SALES),
            KaiCard(top.mapIndexed { i, p -> "${i + 1}. ${p.name} — ${sold(p)}${KaiFormat.rupees(p.value.toDouble())}" }, emptyList()),
        )
    }

    /**
     * "Yentha stock move agala?": products with stock on hand and no sale in the period (the last 30 days unless said) —
     * read from the same item-sales records as the best sellers. With no item sales recorded at all, Kai says it can't tell
     * (every product would otherwise look unsold).
     */
    private suspend fun slowStock(text: String, lang: KaiLang, today: LocalDate, people: List<String>): KaiTurn {
        val period = movementPeriod(text, today, people)
        val w = movementLabel(period, lang, today)
        val stock = tools.stock(null) ?: return unverified(lang, "stock")
        val sold = tools.topProducts(period.from, minOf(period.to, today), 10_000) ?: return unverified(lang, "item sales")
        tools.log("slow stock", "inventory + sales ledger", "${stock.size} items, ${sold.size} sold", ActionStatus.ANSWERED)
        if (sold.isEmpty()) return say(lang, KaiMood.CLARIFY, null,
            ta = "ஓனர், $w item-wise sales பதிவு எதுவும் இல்ல — அதனால எந்த stock move ஆகல-னு சொல்ல முடியாது. Sales bill-ல item போட்டா சொல்லுவேன்.",
            tl = "Owner, $w item-wise sales record edhuvum illa — adhanaala endha stock move aagala-nu solla mudiyadhu. Sales bill-la item pottaa solluven.",
            en = "Owner, there are no item-wise sales recorded $w, so I can't tell which stock isn't moving. Record items on sales bills and I'll tell you.")
        val soldNames = sold.map { it.name.lowercase(Locale.ROOT) }.toSet()
        val idle = stock.filter { it.qty.signum() > 0 && it.name.lowercase(Locale.ROOT) !in soldNames }.sortedByDescending { it.qty }
        if (idle.isEmpty()) {
            val least = sold.last()
            return say(lang, KaiMood.HAPPY, null,
                ta = "ஓனர், $w stock-ல இருக்குற எல்லா product-உம் கொஞ்சமாவது வித்திருக்கு. கம்மியா வித்தது ${least.name} — ${KaiFormat.rupees(least.value.toDouble())}.",
                tl = "Owner, $w stock-la irukura ellaa product-um konjamaavadhu vithirukku. Kammiya vithadhu ${least.name} — ${KaiFormat.rupees(least.value.toDouble())}.",
                en = "Owner, every product in stock sold at least once $w. The slowest was ${least.name} — ${KaiFormat.rupees(least.value.toDouble())}.")
        }
        return KaiTurn(
            ChatReply(pick(lang,
                ta = "ஓனர், sales bill படி $w ${idle.size} product ஒண்ணு கூட விக்கல (stock-ல இருக்கு):",
                tl = "Owner, sales bill padi $w ${idle.size} product onnu kooda vikkala (stock-la irukku):",
                en = "Owner, by your sales bills, ${idle.size} product${if (idle.size == 1) "" else "s"} in stock didn't sell $w:"), KaiMood.CONCERNED, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(idle.take(10).map { "${it.name} — ${qty(it.qty, it.unit)}" } +
                (if (idle.size > 10) listOf(pick(lang, ta = "+ இன்னும் ${idle.size - 10}", tl = "+ innum ${idle.size - 10}", en = "+ ${idle.size - 10} more")) else emptyList()), emptyList()),
        )
    }

    private val tamilMonths = listOf("ஜனவரி", "பிப்ரவரி", "மார்ச்", "ஏப்ரல்", "மே", "ஜூன்", "ஜூலை", "ஆகஸ்ட்", "செப்டம்பர்", "அக்டோபர்", "நவம்பர்", "டிசம்பர்")
    private fun monthName(d: LocalDate, lang: KaiLang) = if (lang == KaiLang.TAMIL) tamilMonths[d.monthValue - 1]
        else d.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)

    /** One period's profit from the books: sales, the cost of what was sold, shop expenses. */
    private class ProfitFigures(val sales: BigDecimal, val cost: BigDecimal, val costFromPurchases: Boolean, val noPrice: List<String>, val expenses: BigDecimal) {
        val gross: BigDecimal get() = sales - cost
        val net: BigDecimal get() = gross - expenses
        val empty: Boolean get() = sales.signum() == 0 && cost.signum() == 0 && expenses.signum() == 0
    }

    /**
     * The cost of the goods sold is each sold item's quantity × its purchase price (the item-wise sales records); with no
     * item-wise sales, the period's purchases stand in (said so). Null: the books can't be read.
     */
    private suspend fun profitFigures(from: LocalDate, to: LocalDate): ProfitFigures? {
        val sales = runCatching { tools.sales(from, to) }.getOrNull() ?: return null
        val expenses = runCatching { tools.expenses(from, to) }.getOrNull() ?: BigDecimal.ZERO
        val sold = runCatching { tools.topProducts(from, to, 10_000) }.getOrNull().orEmpty().filter { it.qty.signum() > 0 }
        val prices = runCatching { tools.products() }.getOrNull().orEmpty().associateBy { it.name.lowercase(Locale.ROOT) }
        if (sold.isNotEmpty() && prices.isNotEmpty()) {
            val noPrice = sold.filter { prices[it.name.lowercase(Locale.ROOT)]?.purchasePrice == null }.map { it.name }
            val cost = sold.fold(BigDecimal.ZERO) { acc, f -> acc + (prices[f.name.lowercase(Locale.ROOT)]?.purchasePrice?.multiply(f.qty) ?: BigDecimal.ZERO) }
            return ProfitFigures(sales, cost, false, noPrice, expenses)
        }
        val bought = runCatching { tools.purchases(from, to) }.getOrNull() ?: BigDecimal.ZERO
        return ProfitFigures(sales, bought, true, emptyList(), expenses)
    }

    /**
     * "Indha maasam labam evlo", "profit and loss report", "P&L kaattu": an estimate from the books — sales, the cost of the
     * goods sold, shop expenses, gross and net profit, and last month beside it. Every figure is from the records and the
     * answer says it is an estimate (principle 3). Null: the books can't be read (the plain "not kept" answer follows).
     */
    private suspend fun profitReport(lang: KaiLang, today: LocalDate, period: ChatPeriod): KaiTurn? {
        val to = minOf(period.to, today)
        val now = profitFigures(period.from, to) ?: return null
        val label = periodName(period, lang, today)
        fun r(a: BigDecimal) = KaiFormat.rupees(a.toDouble())
        tools.log("profit report", "books", "sales ${now.sales} cost ${now.cost} expenses ${now.expenses}", ActionStatus.ANSWERED)
        if (now.empty) return say(lang, KaiMood.CLARIFY, "profit: no figures",
            ta = "ஓனர், $label sales, purchase, செலவு எதுவும் பதிவுல இல்ல — அதனால லாபம் கணக்கு போட முடியல.",
            tl = "Owner, $label sales, purchase, selavu edhuvum record-la illa — adhanaala labam kanakku poda mudiyala.",
            en = "Owner, there are no sales, purchases or expenses recorded for ${label.lowercase(Locale.ROOT)}, so I can't work out a profit.")
        // Last month beside a month (the whole of it); other periods stand alone.
        val monthly = period.kind == ChatPeriod.Kind.THIS_MONTH || period.kind == ChatPeriod.Kind.LAST_MONTH
        val prevFrom = period.from.minusMonths(1).withDayOfMonth(1)
        val before = if (monthly) profitFigures(prevFrom, period.from.minusDays(1))?.takeIf { !it.empty } else null
        val costLine = if (now.costFromPurchases) pickLang(lang, ta = "வாங்கின பொருட்கள் (purchase)", tl = "Vaangina porul (purchase)", en = "Goods bought (purchases)")
            else pickLang(lang, ta = "பொருட்களின் கொள்முதல் செலவு", tl = "Vitha porul-oda purchase vilai", en = "Cost of goods sold")
        val lines = mutableListOf(
            pickLang(lang, ta = "மொத்த விற்பனை", tl = "Mothha sales", en = "Total sales") + " — " + r(now.sales),
            "$costLine — ${r(now.cost)}",
            pickLang(lang, ta = "கடைச் செலவுகள்", tl = "Kadai selavu", en = "Shop expenses") + " — " + r(now.expenses),
            pickLang(lang, ta = "மொத்த லாபம் (gross)", tl = "Mothha labam (gross)", en = "Gross profit") + " — " + r(now.gross),
            pickLang(lang, ta = "நிகர லாபம் (மதிப்பீடு)", tl = "Nigara labam (mathippeedu)", en = "Net profit (estimate)") + " — " + r(now.net),
        )
        before?.let { b ->
            val m = monthName(prevFrom, lang)
            val d = now.net - b.net
            val change = when (d.signum()) {
                0 -> pickLang(lang, ta = "அதே அளவு", tl = "adhey alavu", en = "the same")
                1 -> pickLang(lang, ta = "${r(d)} அதிகம்", tl = "${r(d)} adhigam", en = "${r(d)} more")
                else -> pickLang(lang, ta = "${r(d.negate())} குறைவு", tl = "${r(d.negate())} kammi", en = "${r(d.negate())} less")
            }
            lines += pickLang(lang, ta = "$m நிகர லாபம் — ${r(b.net)} (இப்போ $change)", tl = "$m nigara labam — ${r(b.net)} (ippo $change)",
                en = "$m net profit — ${r(b.net)} (now $change)")
        }
        val notes = buildList {
            if (now.costFromPurchases) add(pickLang(lang, ta = "Item-wise sales பதிவு இல்ல, அதனால இந்த காலத்துல வாங்கினதை செலவா எடுத்தேன் — stock-ல இருக்கிறதும் இதுல சேரும்.",
                tl = "Item-wise sales record illa, adhanaala indha period-la vaanginadha cost-a eduthen — stock-la irukradhum idhula serum.",
                en = "There are no item-wise sales, so I took this period's purchases as the cost — stock still on the shelf is in it."))
            if (now.noPrice.isNotEmpty()) add(pickLang(lang, ta = "${now.noPrice.size} product-க்கு purchase price இல்ல (${now.noPrice.take(3).joinToString()}) — அதோட cost சேரல, லாபம் கொஞ்சம் அதிகமா தெரியும்.",
                tl = "${now.noPrice.size} product-ku purchase price illa (${now.noPrice.take(3).joinToString()}) — adhoda cost seraala, labam konjam adhigama theriyum.",
                en = "${now.noPrice.size} product${if (now.noPrice.size == 1) "" else "s"} have no purchase price (${now.noPrice.take(3).joinToString()}) — their cost isn't counted, so the profit looks a little high."))
        }
        val net = r(now.net)
        val head = if (now.net.signum() >= 0) pickLang(lang, ta = "ஓனர், $label லாபம் சுமார் $net (மதிப்பீடு).", tl = "Owner, $label labam sumaar $net (mathippeedu).",
                en = "Owner, ${label.lowercase(Locale.ROOT)} profit is about $net (an estimate).")
            else pickLang(lang, ta = "ஓனர், $label நஷ்டம் சுமார் ${r(now.net.negate())} (மதிப்பீடு).", tl = "Owner, $label nashtam sumaar ${r(now.net.negate())} (mathippeedu).",
                en = "Owner, ${label.lowercase(Locale.ROOT)} shows a loss of about ${r(now.net.negate())} (an estimate).")
        return KaiTurn(ChatReply((listOf(head) + notes).joinToString("\n"), KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY), KaiCard(lines, emptyList()))
    }

    /** "Colgate nalla pogudhu?": what it sold in the last 30 days and where it stands among the sellers, or that it didn't sell. */
    private suspend fun productMovement(p: com.shopai.app.brain.tools.ProductRef, lang: KaiLang, today: LocalDate): KaiTurn {
        val inv = com.shopai.app.brain.tools.KaiInventory
        val sold = tools.topProducts(today.minusDays(29), today, 10_000)?.filter { it.qty.signum() > 0 }?.sortedByDescending { it.value }
            ?: return unverified(lang, "item sales")
        lastProduct = p
        conversationState.lastProduct = p.name; productTalkTurn = conversationState.conversationTurn
        tools.log("product movement ${p.name}", "sales ledger", "${sold.size} sold", ActionStatus.ANSWERED)
        if (sold.isEmpty()) return say(lang, KaiMood.CLARIFY, "movement: no sales",
            ta = "ஓனர், கடந்த 30 நாள்ல item-wise sales பதிவு இல்ல — ${p.name} எப்படி போகுது-னு சொல்ல முடியாது.",
            tl = "Owner, kadandha 30 naal-la item-wise sales record illa — ${p.name} eppadi pogudhu-nu solla mudiyadhu.",
            en = "Owner, there are no item-wise sales in the last 30 days, so I can't tell how ${p.name} is selling.")
        val i = sold.indexOfFirst { it.name.equals(p.name, ignoreCase = true) }
        val left = inv.shown(p.stock, p.unit)
        if (i < 0) return say(lang, KaiMood.CONCERNED, "movement: ${p.name} unsold",
            ta = "ஓனர், ${p.name} கடந்த 30 நாள்ல ஒண்ணு கூட விக்கல — stock-ல $left நிக்குது.",
            tl = "Owner, ${p.name} kadandha 30 naal-la onnu kooda vikkala — stock-la $left nikkudhu.",
            en = "Owner, ${p.name} hasn't sold at all in the last 30 days — $left is sitting in stock.")
        val f = sold[i]
        val q = inv.shown(f.qty, p.unit); val v = KaiFormat.rupees(f.value.toDouble()); val n = i + 1
        return say(lang, if (n <= 3) KaiMood.HAPPY else KaiMood.EXPLAINING, "movement: ${p.name} #$n",
            ta = "ஓனர், ${p.name} கடந்த 30 நாள்ல $q வித்தது — $v. விக்கிற ${sold.size} product-ல $n-வது. Stock-ல $left இருக்கு.",
            tl = "Owner, ${p.name} kadandha 30 naal-la $q vithadhu — $v. Vikkura ${sold.size} product-la $n-vadhu. Stock-la $left irukku.",
            en = "Owner, ${p.name} sold $q in the last 30 days — $v, number $n of ${sold.size} products sold. $left in stock.")
    }

    /** Each product's money in stock at its purchase price (null price: left out, and named). */
    private fun stockMoney(products: List<com.shopai.app.brain.tools.ProductRef>) =
        products.filter { it.stock.signum() > 0 }.map { it to it.purchasePrice?.multiply(it.stock) }

    /** "Stock-la evlo panam mudangi irukku?": the money in stock at purchase prices — the product named, else all, biggest first. */
    private suspend fun stockValue(text: String, lang: KaiLang, products0: List<com.shopai.app.brain.tools.ProductRef>?): KaiTurn {
        val products = products0 ?: runCatching { tools.products() }.getOrNull() ?: return unverified(lang, "stock value")
        fun r(a: BigDecimal) = KaiFormat.rupees(a.toDouble())
        val inv = com.shopai.app.brain.tools.KaiInventory
        productIn(text, products)?.let { p ->
            val price = p.purchasePrice ?: return say(lang, KaiMood.CLARIFY, "stock value: no price",
                ta = "ஓனர், ${p.name}-க்கு purchase price இல்ல — “${p.name} purchase rate 40”-னு சொன்னா மதிப்பு சொல்லுவேன்.",
                tl = "Owner, ${p.name}-ku purchase price illa — “${p.name} purchase rate 40”-nu sonna mathippu solluven.",
                en = "Owner, ${p.name} has no purchase price — say “${p.name} purchase rate 40” and I'll tell you its value.")
            val v = r(price.multiply(p.stock.max(BigDecimal.ZERO)))
            return say(lang, KaiMood.EXPLAINING, "stock value: ${p.name}",
                ta = "ஓனர், ${p.name} ${inv.shown(p.stock, p.unit)} — purchase விலை படி $v stock-ல இருக்கு.",
                tl = "Owner, ${p.name} ${inv.shown(p.stock, p.unit)} — purchase vilai padi $v stock-la irukku.",
                en = "Owner, ${p.name}: ${inv.shown(p.stock, p.unit)} — $v in stock at purchase price.")
        }
        val held = stockMoney(products)
        val priced = held.filter { it.second != null }.sortedByDescending { it.second }
        val noPrice = held.filter { it.second == null }.map { it.first.name }
        val total = priced.fold(BigDecimal.ZERO) { a, (_, v) -> a + v!! }
        tools.log("stock value", "inventory", "total $total, ${noPrice.size} unpriced", ActionStatus.ANSWERED)
        if (priced.isEmpty()) return say(lang, KaiMood.CLARIFY, "stock value: no prices",
            ta = "ஓனர், stock-ல இருக்கிற product-க்கு purchase price இல்ல — அதனால எவ்வளவு பணம் முடங்கியிருக்கு-னு சொல்ல முடியல.",
            tl = "Owner, stock-la irukra product-ku purchase price illa — adhanaala evlo panam mudangi irukku-nu solla mudiyala.",
            en = "Owner, the products in stock have no purchase prices, so I can't tell how much money is in stock.")
        val missing = if (noPrice.isEmpty()) "" else pickLang(lang,
            ta = " ${noPrice.size} product-க்கு purchase price இல்ல (${noPrice.take(3).joinToString()}) — அது இதுல சேரல.",
            tl = " ${noPrice.size} product-ku purchase price illa (${noPrice.take(3).joinToString()}) — adhu idhula seraala.",
            en = " ${noPrice.size} product${if (noPrice.size == 1) "" else "s"} have no purchase price (${noPrice.take(3).joinToString()}) — not counted.")
        return KaiTurn(
            ChatReply(pickLang(lang, ta = "ஓனர், stock-ல ${r(total)} முடங்கியிருக்கு (purchase விலை படி).$missing",
                tl = "Owner, stock-la ${r(total)} mudangi irukku (purchase vilai padi).$missing",
                en = "Owner, ${r(total)} is held in stock (at purchase prices).$missing"), KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY),
            KaiCard(priced.take(8).map { (p, v) -> "${p.name} — ${inv.shown(p.stock, p.unit)} — ${r(v!!)}" }, emptyList()),
        )
    }

    /**
     * "Stock performance report": what sells most and least (the last 30 days), stock unsold for 60 days and the money in it,
     * low stock, and the money held in stock — all from the item-wise sales and the inventory. What the books lack is said.
     */
    private suspend fun stockReport(lang: KaiLang, today: LocalDate): KaiTurn {
        val products = runCatching { tools.products() }.getOrNull() ?: return unverified(lang, "stock report")
        val inv = com.shopai.app.brain.tools.KaiInventory
        fun r(a: BigDecimal) = KaiFormat.rupees(a.toDouble())
        val sold30 = runCatching { tools.topProducts(today.minusDays(29), today, 10_000) }.getOrNull().orEmpty().filter { it.qty.signum() > 0 }.sortedByDescending { it.value }
        val sold60 = runCatching { tools.topProducts(today.minusDays(59), today, 10_000) }.getOrNull().orEmpty().filter { it.qty.signum() > 0 }
        val low = runCatching { tools.lowStock() }.getOrNull().orEmpty()
        val held = stockMoney(products)
        val total = held.mapNotNull { it.second }.fold(BigDecimal.ZERO) { a, v -> a + v }
        val noPrice = held.count { it.second == null }
        val unit = products.associate { it.name.lowercase(Locale.ROOT) to it.unit }
        fun sold(f: com.shopai.app.brain.tools.ProductSalesFact) = (unit[f.name.lowercase(Locale.ROOT)]?.let { "${inv.shown(f.qty, it)} — " }.orEmpty()) + r(f.value)
        val lines = mutableListOf<String>()
        fun section(ta: String, tl: String, en: String) { lines += "— ${pickLang(lang, ta = ta, tl = tl, en = en)} —" }
        val headParts = mutableListOf<String>()
        if (sold30.isEmpty()) {
            section("விற்பனை", "Sales", "Sales")
            lines += pickLang(lang, ta = "கடந்த 30 நாள்ல item-wise sales பதிவு இல்ல — எது அதிகமா / மெதுவா விக்குது-னு சொல்ல முடியாது.",
                tl = "Kadandha 30 naal-la item-wise sales record illa — edhu adhigama / medhuva vikkudhu-nu solla mudiyadhu.",
                en = "No item-wise sales in the last 30 days — I can't tell what sells fast or slow.")
        } else {
            section("அதிகமா விக்கிறது (30 நாள்)", "Adhigama vikkuradhu (30 naal)", "Best sellers (30 days)")
            sold30.take(3).forEachIndexed { i, f -> lines += "${i + 1}. ${f.name} — ${sold(f)}" }
            headParts += pickLang(lang, ta = "அதிகமா விக்கிறது ${sold30.first().name}", tl = "adhigama vikkuradhu ${sold30.first().name}", en = "best seller ${sold30.first().name}")
            if (sold30.size > 3) {
                section("மெதுவா விக்கிறது (30 நாள்)", "Medhuva vikkuradhu (30 naal)", "Slow sellers (30 days)")
                sold30.takeLast(minOf(3, sold30.size - 3)).reversed().forEach { f -> lines += "${f.name} — ${sold(f)}" }
            }
        }
        if (sold60.isNotEmpty()) {
            val names = sold60.map { it.name.lowercase(Locale.ROOT) }.toSet()
            val idle = held.filter { it.first.name.lowercase(Locale.ROOT) !in names }.sortedByDescending { it.second ?: BigDecimal.ZERO }
            section("60 நாளா விக்காத stock", "60 naal-a vikkaadha stock", "Unsold for 60 days")
            if (idle.isEmpty()) lines += pickLang(lang, ta = "எதுவும் இல்ல", tl = "Edhuvum illa", en = "None")
            else {
                idle.take(5).forEach { (p, v) -> lines += "${p.name} — ${inv.shown(p.stock, p.unit)}" + (v?.let { " — ${r(it)}" }.orEmpty()) }
                val idleMoney = idle.mapNotNull { it.second }.fold(BigDecimal.ZERO) { a, v -> a + v }
                headParts += pickLang(lang, ta = "60 நாளா விக்காதது ${idle.size} product (${r(idleMoney)})", tl = "60 naal-a vikkaadhadhu ${idle.size} product (${r(idleMoney)})",
                    en = "${idle.size} unsold for 60 days (${r(idleMoney)})")
            }
        }
        section("குறைந்த stock", "Low stock", "Low stock")
        if (low.isEmpty()) lines += pickLang(lang, ta = "எதுவும் இல்ல", tl = "Edhuvum illa", en = "None")
        else low.take(5).forEach { f -> lines += "${f.name} — ${inv.shown(f.qty, f.unit)}" + (f.reorderAt?.let { " (reorder ${inv.shown(it, f.unit)})" }.orEmpty()) }
        section("Stock-ல முடங்கியிருக்கும் பணம்", "Stock-la mudangi irukra panam", "Money held in stock")
        lines += r(total) + if (noPrice > 0) pickLang(lang, ta = " ($noPrice product-க்கு purchase price இல்ல)", tl = " ($noPrice product-ku purchase price illa)",
            en = " ($noPrice without a purchase price)") else ""
        tools.log("stock report", "inventory + sales ledger", "${sold30.size} sold, ${low.size} low, total $total", ActionStatus.ANSWERED)
        val head = pickLang(lang, ta = "ஓனர், stock-ல ${r(total)} முடங்கியிருக்கு", tl = "Owner, stock-la ${r(total)} mudangi irukku",
            en = "Owner, ${r(total)} is held in stock") + headParts.joinToString("") { "; $it" } + "."
        return KaiTurn(ChatReply(head, KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY), KaiCard(lines, emptyList()))
    }

    // An accountant's questions Kai has no figure for (owner, 11 Oct 2026): "profit evlo", "GST evlo kattanum", "balance sheet kudu".
    private val profitAsk = Regex("""(?i)(?<![\p{L}])(?:profit|labam|laabam|labama|labamaa|nashtam|nashtama|nashtamaa|loss|p\s*&\s*l|p\s*and\s*l|pnl)(?![\p{L}])|லாபம்|நஷ்டம்""")
    private val taxAsk = Regex("""(?i)(?<![\p{L}])(?:gst|tax|vari|itr|tds)(?![\p{L}])|ஜிஎஸ்டி|வரி""")
    private val sheetAsk = Regex("""(?i)(?<![\p{L}])(?:balance\s*sheet|trial\s*balance|audit|auditor)(?![\p{L}])""")
    private val untrackedAsk = Regex("""(?i)(?<![\p{L}])(advance|return|returns|discount)(?![\p{L}])|அட்வான்ஸ்|டிஸ்கவுண்ட்|ரிட்டர்ன்""")
    private val countAsk = Regex("""(?i)(?<![\p{L}])(?:evlo|evvalavu|ethana|ethanai|eththana|how\s+many|count|number\s+of|mothama|total)(?![\p{L}])|எத்தனை|எவ்வளவு""")
    private val partyWord = Regex("""(?i)(?<![\p{L}])(customers?|suppliers?|kastamars?|aalunga|peru|per)(?![\p{L}])|கஸ்டமர்|சப்ளையர்""")
    private val newWord = Regex("""(?i)(?<![\p{L}])(pudhu|pudhiya|puthu|puthiya|new)(?![\p{L}])|புது|புதிய""")

    /**
     * Profit / GST / balance sheet / returns: the books don't keep these figures, so Kai says so plainly and shows what they do keep
     * (sales, purchases, expenses for the period) — never a made-up figure, never "which customer is Labam?".
     */
    private suspend fun accountsQuestion(text: String, lang: KaiLang, today: LocalDate, q: ChatQuery): KaiTurn? {
        if (q.person != PersonRef.None) return null
        val profit = profitAsk.containsMatchIn(text); val tax = taxAsk.containsMatchIn(text); val sheet = sheetAsk.containsMatchIn(text)
        val untracked = untrackedAsk.find(text)?.value
        if (!profit && !tax && !sheet && untracked == null) return null
        val period = q.period ?: if (has(text, "inniku", "innaikku", "innaiku", "today", "இன்னைக்கு")) ChatPeriod(today, today, ChatPeriod.Kind.TODAY)
            else ChatPeriod(today.withDayOfMonth(1), today, ChatPeriod.Kind.THIS_MONTH)
        // "Indha maasam labam evlo", "P&L report": the estimate from the books.
        if (profit && !tax && !sheet && untracked == null) profitReport(lang, today, period)?.let { return it }
        val to = minOf(period.to, today)
        val sales = runCatching { tools.sales(period.from, to) }.getOrNull()
        val bought = runCatching { tools.purchases(period.from, to) }.getOrNull()
        val spent = runCatching { tools.expenses(period.from, to) }.getOrNull()
        val label = periodName(period, lang, today)
        fun r(a: BigDecimal) = KaiFormat.rupees(a.toDouble())
        val known = listOfNotNull(sales?.let { "sales ${r(it)}" }, bought?.let { "purchase ${r(it)}" },
            spent?.let { pick(lang, ta = "செலவு ${r(it)}", tl = "selavu ${r(it)}", en = "expenses ${r(it)}") }).joinToString(", ")
        val figures = if (known.isEmpty()) pick(lang, ta = " $label sales, purchase, செலவு எதுவும் பதிவுல கிடைக்கல.", tl = " $label sales, purchase, selavu edhuvum record-la kidaikala.",
            en = " No sales, purchases or expenses are recorded for ${label.lowercase(Locale.ROOT)} either.")
            else pick(lang, ta = " பதிவுல இருக்கிறது — $label: $known.", tl = " Record-la irukradhu — $label: $known.", en = " What the books have — $label: $known.")
        // Sales − purchases − expenses, said for what it is: the stock still on the shelf isn't in it.
        val rough = if (profit && sales != null && bought != null && spent != null) (sales - bought - spent).let { d ->
            pick(lang, ta = " Sales − purchase − செலவு = ${r(d)} (கடையில இருக்கிற stock இதுல சேரல, அதனால இது சரியான லாபம் இல்ல).",
                tl = " Sales − purchase − selavu = ${r(d)} (kadaila irukra stock idhula seraala, adhanaala idhu sariyaana labam illa).",
                en = " Sales − purchases − expenses = ${r(d)} (the stock still in the shop isn't counted, so this isn't the true profit).")
        }.orEmpty() else ""
        tools.log("accounts ${listOfNotNull("profit".takeIf { profit }, "tax".takeIf { tax }, "sheet".takeIf { sheet }, untracked).joinToString("+")}", "books", known.ifEmpty { "no figures" }, ActionStatus.ANSWERED)
        val head = when {
            tax -> pick(lang, ta = "ஓனர், GST / tax கணக்கு Kai-ல இன்னும் இல்ல — அதுக்கு உங்க auditor-அ கேளுங்க.", tl = "Owner, GST / tax kanakku Kai-la innum illa — adhukku unga auditor-a kelunga.",
                en = "Owner, Kai doesn't keep GST / tax accounts yet — please ask your auditor.")
            sheet -> pick(lang, ta = "ஓனர், balance sheet Kai-ல இன்னும் இல்ல.", tl = "Owner, balance sheet Kai-la innum illa.", en = "Owner, Kai doesn't make a balance sheet yet.")
            profit -> pick(lang, ta = "ஓனர், லாபம் (profit & loss) கணக்கு Kai-ல இன்னும் இல்ல.", tl = "Owner, labam (profit & loss) kanakku Kai-la innum illa.",
                en = "Owner, Kai doesn't keep a profit & loss account yet.")
            else -> pick(lang, ta = "ஓனர், $untracked தனியா Kai பதிவுல இல்ல.", tl = "Owner, $untracked thaniyaa Kai record-la illa.", en = "Owner, $untracked isn't recorded separately in Kai.")
        }
        return KaiTurn(ChatReply(head + figures + rough, KaiMood.EXPLAINING, ChatIntent.GENERAL_BUSINESS_QUERY))
    }

    /** "evlo customers irukkanga?", "ethana suppliers?": counted from the books (a new-this-month count isn't kept — said so). */
    private suspend fun partyCount(text: String, lang: KaiLang, q: ChatQuery): KaiTurn? {
        if (q.person != PersonRef.None || !countAsk.containsMatchIn(text) || !partyWord.containsMatchIn(text)) return null
        if (!Regex("""(?i)customer|supplier|kastamar|கஸ்டமர்|சப்ளையர்""").containsMatchIn(text) &&
            !Regex("""(?i)(?<![\p{L}])(irukkanga|irukaanga|irukanga|irukaanga|record)(?![\p{L}])|இருக்காங்க""").containsMatchIn(text)) return null
        // "customers kitta evlo baaki": money, not a head count.
        if (com.shopai.app.brain.tools.KaiPaymentDirection.mentionsMoneyOwed(text) || has(text, "baaki", "bakki", "pending", "tharanum", "kudukkanum", "kadan", "பாக்கி")) return null
        val snap = runCatching { books.snapshot() }.getOrNull() ?: return unverified(lang, "party count")
        val c = snap.customers.distinctBy { it.id }.size; val sp = snap.suppliers.distinctBy { it.id }.size
        val owing = snap.customers.count { it.pendingTotal > 0.0 }
        tools.log("party count", "books", "$c customers, $sp suppliers", ActionStatus.ANSWERED)
        val fresh = if (newWord.containsMatchIn(text)) pick(lang, ta = " எப்போ சேர்ந்தாங்க-னு Kai கணக்கு வைக்கல, அதனால புது customer எத்தனை-னு சொல்ல முடியாது.",
            tl = " Eppo serndhaanga-nu Kai kanakku vaikkala, adhanaala pudhu customer ethana-nu solla mudiyadhu.",
            en = " Kai doesn't keep when each one joined, so it can't say how many are new.") else ""
        return say(lang, KaiMood.EXPLAINING, null,
            ta = "ஓனர், record-ல $c customer, $sp supplier இருக்காங்க. $owing customer பாக்கி தரணும்.$fresh",
            tl = "Owner, record-la $c customer, $sp supplier irukkaanga. $owing customer baaki tharanum.$fresh",
            en = "Owner, your records have $c customers and $sp suppliers. $owing customers owe you money.$fresh")
    }

    /** Sales / purchases / expenses from the books engine; every other question goes to the Business Brain. */
    private suspend fun question(text: String, lang: KaiLang, today: LocalDate, people: List<String>): KaiTurn {
        val q = KaiChatUnderstanding.understand(text, today, people)
        accountsQuestion(text, lang, today, q)?.let { return it }
        partyCount(text, lang, q)?.let { return it }
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
        hintSameNames(text, people)
        val reply = brain.ask(text)
        tools.log(reply.intent.name.lowercase(), "business brain", "answered", ActionStatus.ANSWERED)
        return KaiTurn(reply)
    }

    /**
     * Several records share the name in the question ("Lokesh" in Chennai and in Nagapattinam): the existing entity
     * resolver picks the one the words or the conversation point to, and names each one for the Brain's "which one?".
     */
    private suspend fun hintSameNames(text: String, people: List<String>) {
        // Every name the books hold more than once gets its label, so a list line says which Lokesh it is.
        val snapshot = runCatching { books.snapshot() }.getOrNull()?.parties.orEmpty()
        val repeated = snapshot
            .groupBy { it.name.trim().lowercase(Locale.ROOT) }.filterValues { same -> same.distinctBy { it.id }.size > 1 }.values.map { it.first().name }
        // "abi evlo tharanum" for a record saved as "அபி": the answer says "Abi", as the owner wrote it (the record keeps its name).
        val spelled = snapshot.mapNotNull { p -> asWritten(p.name, text).takeIf { it != p.name }?.let { p.id to it } }.toMap()
        val labels = spelled + repeated.flatMap { n -> runCatching { tools.parties(n) }.getOrNull()?.let { KaiEntityResolver.sameName(n, it) }.orEmpty() }
            .distinctBy { it.id }.associate { it.id to labelOf(it) }
        val name = com.shopai.app.brain.KaiUnderstanding.knownPerson(text, people)
        val exact = name?.let { n -> runCatching { tools.parties(n) }.getOrNull()?.let { KaiEntityResolver.sameName(n, it).distinctBy { p -> p.id } } }.orEmpty()
        if (name == null || exact.size < 2) return brain.hint(null, labels)
        val focus = conversationState.focusPartyId?.takeIf { conversationState.lastPerson.equals(name, ignoreCase = true) }
        val one = resolveParty(name, text, exact, focus) as? KaiEntityResolver.Result.One
        one?.let { conversationState.focusPartyId = it.party.id }
        brain.hint(one?.party?.id, labels + exact.associate { it.id to labelOf(it) })
    }

    // ------------------------------------------------------------ wording

    // ------------------------------------------------------------------ a person's statement (PDF / WhatsApp)

    /**
     * "Kumar oda credit debit history PDF kudu", "Kumar statement WhatsApp-la anuppu", "குமார் கணக்கு பிடிஎஃப் வேணும்": that person's
     * statement — what the records hold, in short — with the WhatsApp and PDF buttons. Kai only reads the books; the PDF is made
     * on the phone and WhatsApp opens with it in the person's chat: the owner presses send. Never a guessed person.
     */
    private suspend fun statementAsk(said: String, lang: KaiLang): KaiTurn? {
        if (!KaiStatement.asks(said)) return null
        val ledger = runCatching { books.snapshot() }.getOrNull() ?: return null
        val today = now().toLocalDate()
        val q = KaiChatUnderstanding.understand(said, today, ledger.people)
        // "pona maasam", "September", "last 3 months": that period; nothing said: the whole history.
        val period = q.period?.takeIf { !it.from.isAfter(today) }
        val from = period?.from
        val to = period?.to?.let { minOf(it, today) } ?: today
        val name = (q.person as? PersonRef.Named)?.name ?: com.shopai.app.brain.KaiUnderstanding.knownPerson(said, ledger.people)
            // "Suresh statement PDF" with no Suresh in the books: said plainly, never another person's statement.
            ?: com.shopai.app.brain.KaiUnderstanding.personIn(said)?.takeUnless { KaiStatement.isAskWord(it) || KaiStatement.pointsBack(it) || it.lowercase(Locale.ROOT) in statementFillers }
            // "Avan statement PDF anuppu", "itha PDF-a kudu", "pdf kudu" right after Kumar's entry: the person just talked about.
            ?: recentPerson()
        if (name == null) {
            // "Idha WhatsApp-la anuppu" with nobody talked about: not a statement (Kai says what it can't send).
            if (KaiStatement.onlyByReference(said)) return null
            return say(lang, KaiMood.CLARIFY, null,
                ta = "யாரோட கணக்கு PDF வேணும் ஓனர்? பேர் சொல்லுங்க.",
                tl = "Yaaroda statement PDF venum Owner? Per sollunga.",
                en = "Whose statement do you want as a PDF, Owner?")
        }
        val parties = ledger.find(name).distinctBy { it.side to it.id }
        if (parties.isEmpty()) return say(lang, KaiMood.CLARIFY, null,
            ta = "ஓனர், $name records-ல இல்ல.", tl = "Owner, $name records-la illa.", en = "Owner, $name isn't in your records.")
        if (parties.size > 1) {
            fun label(p: com.shopai.app.brain.PartyFacts) = "${p.name} · " + if (p.side == com.shopai.app.brain.Direction.RECEIVABLE)
                "customer · " + pick(lang, ta = "பாக்கி", tl = "pending", en = "pending") + " " + KaiFormat.rupees(p.pending)
            else "supplier · " + pick(lang, ta = "கொடுக்கணும்", tl = "kudukkanum", en = "to pay") + " " + KaiFormat.rupees(p.pending)
            return KaiTurn(
                ChatReply(pick(lang, ta = "எந்த $name ஓனர்?", tl = "Endha $name Owner?", en = "Which $name, Owner?"), KaiMood.CLARIFY, ChatIntent.CUSTOMER_HISTORY),
                KaiCard(emptyList(), parties.map { p -> KaiButton(label(p), KaiAction.StatementFor(p.id, p.side == com.shopai.app.brain.Direction.RECEIVABLE, from, to)) }),
            )
        }
        return statementCard(parties.single(), from, to, lang)
    }

    /** The person this conversation is about now: the entry just saved or the person just named (the latest), else the one Kai answered about. */
    private fun recentPerson(): String? {
        val saved = conversationState.lastSaved?.takeIf { conversationState.conversationTurn - it.turn <= 6 }?.let { it.person to it.turn }
        val topic = personOnTopic?.takeIf { conversationState.conversationTurn - it.second <= 4 }
        return listOfNotNull(saved, topic).maxByOrNull { it.second }?.first ?: brain.lastPerson()
    }

    /** The product this conversation is about now (a stock / price answer a few turns ago), when the talk is about stock. */
    private fun recentProduct(): String? = conversationState.lastProduct
        ?.takeIf { conversationState.lastBusinessTopic == "STOCK_QUERY" && conversationState.conversationTurn - productTalkTurn <= 4 }

    private val statementFillers = setOf("give", "send", "share", "me", "the", "my", "kudu", "kudunga", "venum")

    private suspend fun statementCard(p: com.shopai.app.brain.PartyFacts, from: LocalDate?, to: LocalDate, lang: KaiLang): KaiTurn {
        val customer = p.side == com.shopai.app.brain.Direction.RECEIVABLE
        val ledger = runCatching { books.snapshot() }.getOrNull()
        val phone = (if (customer) ledger?.customers else ledger?.suppliers)?.firstOrNull { it.id == p.id }?.phone?.takeIf { it.isNotBlank() }
        val h = books.history(p) ?: return say(lang, KaiMood.ERROR, "statement: books unavailable",
            ta = "உங்க கணக்கு புத்தகத்தை இப்போ படிக்க முடியல ஓனர்.", tl = "Owner, books-a ippo padikka mudiyala.", en = "I couldn't read your records right now, Owner.")
        if (h.entries.isEmpty()) return say(lang, KaiMood.NEUTRAL, null,
            ta = "ஓனர், ${p.name}-க்கு records-ல எந்த entry-யும் இல்ல — PDF-ல போட எதுவும் இல்ல.",
            tl = "Owner, ${p.name}-ku records-la entry edhuvum illa — PDF-la poda onnum illa.",
            en = "Owner, there are no entries for ${p.name} yet — nothing to put in a PDF.")
        val st = com.shopai.app.util.partyStatement(p.name, phone, customer, h.entries.map { e ->
            com.shopai.app.util.StatementTxn(e.amount, e.paid, null, e.createdAt, e.dueDate, e.payments.map { com.shopai.app.util.StatementPayment(it.amount, it.date, null) })
        }, from, to, now().toLocalDate())
        val period = KaiStatement.periodText(from, to, lang)
        val given = KaiFormat.rupees(st.given)
        val paid = KaiFormat.rupees(st.paid)
        val balance = KaiFormat.rupees(maxOf(0.0, st.closing))
        val n = p.name
        val sums = if (customer) pick(lang, ta = "கடன் $given · வந்தது $paid · பாக்கி $balance", tl = "Credit $given · Vandhadhu $paid · Pending $balance",
            en = "Credit $given · Received $paid · Balance due $balance")
        else pick(lang, ta = "வாங்கியது $given · கொடுத்தது $paid · கொடுக்கணும் $balance", tl = "Vaangiyadhu $given · Kuduthadhu $paid · Kudukkanum $balance",
            en = "Bought $given · Paid $paid · To pay $balance")
        val how = if (phone != null) pick(lang,
            ta = "WhatsApp-ஐ தொட்டா $n chat-ல PDF-ஓட WhatsApp திறக்கும் — நீங்க Send அமுக்குங்க.",
            tl = "WhatsApp-a thottaa $n chat-la PDF-oda WhatsApp open aagum — neenga Send amuthunga.",
            en = "Tap WhatsApp: it opens $n's chat with the PDF attached — you press Send.")
        else pick(lang,
            ta = "$n phone number records-ல இல்ல — WhatsApp-ல contact-ஐ நீங்க தேர்ந்தெடுங்க.",
            tl = "$n phone number records-la illa — WhatsApp-la contact-a neenga select pannunga.",
            en = "$n has no phone number in your records — pick the contact in WhatsApp.")
        val reply = pick(lang,
            ta = "சரி ஓனர் — $n-ஓட கணக்கு PDF ரெடி ($period): $sums. $how",
            tl = "Seri Owner — $n-oda statement PDF ready ($period): $sums. $how",
            en = "Okay Owner — $n's statement PDF is ready ($period): $sums. $how")
        return KaiTurn(
            ChatReply(reply, KaiMood.EXPLAINING, ChatIntent.CUSTOMER_HISTORY),
            KaiCard(
                listOfNotNull(
                    "$n · " + (if (customer) "customer" else "supplier") + (phone?.let { " · $it" } ?: ""),
                    period,
                    sums,
                ),
                listOf(
                    KaiButton(pick(lang, ta = "WhatsApp-ல $n-க்கு அனுப்பு", tl = "WhatsApp-la $n-ku anuppu", en = "Send to $n on WhatsApp"),
                        KaiAction.ShareStatement(p.id, n, customer, from, to, whatsapp = true), primary = true),
                    KaiButton(pick(lang, ta = "PDF share / save", tl = "PDF share / save", en = "Share / save PDF"),
                        KaiAction.ShareStatement(p.id, n, customer, from, to, whatsapp = false)),
                ),
            ),
        )
    }

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
