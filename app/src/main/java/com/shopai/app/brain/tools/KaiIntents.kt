package com.shopai.app.brain.tools

import com.shopai.app.brain.chat.KaiSmallTalk
import java.time.LocalDateTime

/**
 * Kai's central action router: what the owner wants. Voice, typed text, Tamil
 * script, Tanglish, English and mixed sentences all reach the same intent.
 * An explicit button tap is handled before any words.
 */
enum class KaiIntentKind {
    LEARN_SLANG,
    /** The owner's own Morning Work order ("Stock first, collection next") — saved only after [Save]. */
    MORNING_ROUTINE,
    MORNING_WORK,
    CREATE_REMINDER,
    LIST_REMINDERS,
    CANCEL_REMINDER,
    UPDATE_REMINDER,
    STOCK_IN,
    STOCK_OUT,
    SCAN_STOCK,
    SCAN_BILL,
    CALL_CONTACT,
    /** A money entry ("Ramesh ku 5000 kuduthen") — always draft → confirm. */
    FINANCIAL_ENTRY,
    PAYMENT_QUERY,
    CUSTOMER_QUERY,
    SUPPLIER_QUERY,
    BUSINESS_QUERY,
    CALCULATE,
    CHAT,
    UNKNOWN,
}

/**
 * One brain for voice and text: the same words — typed Tanglish, English,
 * spoken Tamil script, or mixed — reach the same intent. The order is the
 * priority: reminder → stock in → stock out → bill scanner → call →
 * financial transaction → business question → calculator → conversation →
 * unknown (Kai asks / learns). Pipeline: input → language → Tamil/Tanglish
 * normalization ([KaiSpokenWords]) → context (Kai's pending question) →
 * intent → entities → validation → action (draft / confirm) → reply → voice
 * or text (the same result either way).
 */
object KaiIntents {
    // Action-log intent names.
    const val STOCK_IN = "STOCK_IN"
    const val STOCK_OUT = "STOCK_OUT"
    const val STOCK_IN_CAMERA = "STOCK_IN_CAMERA"
    const val SCAN_STOCK = "SCAN_STOCK"
    const val CREATE_PRODUCT = "CREATE_PRODUCT"
    const val OPEN_BILL_SCANNER = "OPEN_BILL_SCANNER"
    const val CREATE_REMINDER = "CREATE_REMINDER"
    const val CALL_CONTACT = "CALL_CONTACT"
    const val LEARN_SLANG = "LEARN_SLANG"
    const val SMALL_TALK = "SMALL_TALK"
    const val MORNING_WORK = "MORNING_WORK"
    const val MORNING_ROUTINE = "MORNING_ROUTINE"
    const val LEARN_PERSONAL_TERM = "LEARN_PERSONAL_TERM"

    /** "bill scan pannu", "bill ah scan pannu", "indha bill add pannu", "purchase bill scan pannu", "bill photo edu", "bill camera open pannu". */
    private val billWords = Regex("""(?i)(?<![\p{L}])(bill|bills|invoice|receipt)(?![\p{L}])|பில்|ரசீது""")
    private val billDo = Regex(
        """(?i)(?<![\p{L}])(scan|add|enter|podu|potu|poodu|eduthu|edu|edunga|photo|pic|picture|camera|cam|open|upload|serthu|sethu|save|click)(?![\p{L}])|சேர்|ஸ்கேன்""",
    )

    fun isBillScan(text: String): Boolean {
        val t = KaiSpokenWords.normalize(text)
        if (!billWords.containsMatchIn(t) || !billDo.containsMatchIn(t)) return false
        // "bill evlo?", "bill-ku remind pannu" are not the scanner.
        return !Regex("""(?i)(?<![\p{L}])(evlo|evvalavu|how much|remind|reminder|pending|baaki|due)(?![\p{L}])""").containsMatchIn(t)
    }

    /** The intent of [raw] (after the owner's private words were applied). [products] = the inventory. */
    fun classify(raw: String, now: LocalDateTime, people: List<String>, products: List<ProductRef>): KaiIntentKind {
        val text = KaiSpokenWords.normalize(raw.trim())
        if (text.isEmpty()) return KaiIntentKind.UNKNOWN
        if (com.shopai.app.brain.memory.KaiTeaching.wordQuestion(text) != null ||
            com.shopai.app.brain.memory.KaiTeaching.parse(text, emptyList()) != null ||
            com.shopai.app.brain.memory.KaiTeaching.wordTeach(text, emptyList()) != null ||
            com.shopai.app.brain.memory.KaiPersonalTeaching.parse(raw, emptyList()) != null
        ) return KaiIntentKind.LEARN_SLANG
        // The owner's morning order ("Reminders-a last-la podu" is about the brief, not a new reminder).
        if (com.shopai.app.brain.morning.MorningRoutineParser.parse(raw, null, people = people) != null ||
            com.shopai.app.brain.morning.MorningRoutineParser.parse(text, null, people = people) != null
        ) return KaiIntentKind.MORNING_ROUTINE
        // Morning Work — unless the owner explicitly asked for a reminder ("tomorrow morning remind me to call Kumar").
        val morning = com.shopai.app.brain.morning.MorningCommands.morningRequest(raw) ?: com.shopai.app.brain.morning.MorningCommands.morningRequest(text)
        if (morning != null && !KaiReminderUnderstanding.mentionsReminder(text)) return KaiIntentKind.MORNING_WORK
        KaiReminderUnderstanding.understand(text, now, people)?.let { return reminderKind(it) }
        if (KaiStock.scanRequest(text, products) != null) return KaiIntentKind.SCAN_STOCK
        KaiStock.understand(text, products)?.let { req ->
            if (req.product != null || KaiCommands.personIn(text, people) == null) return if (req.incoming) KaiIntentKind.STOCK_IN else KaiIntentKind.STOCK_OUT
        }
        if (isBillScan(text)) return KaiIntentKind.SCAN_BILL
        return when (val cmd = KaiCommands.route(text, now, people)) {
            is KaiCommand.Call -> KaiIntentKind.CALL_CONTACT
            is KaiCommand.Payment -> KaiIntentKind.FINANCIAL_ENTRY
            is KaiCommand.Calculate -> KaiIntentKind.CALCULATE
            is KaiCommand.Reminder -> reminderKind(cmd.request)
            is KaiCommand.ScanBill -> KaiIntentKind.SCAN_BILL
            is KaiCommand.Stock, KaiCommand.LowStock, is KaiCommand.MoneyBalance, KaiCommand.TopProducts -> KaiIntentKind.BUSINESS_QUERY
            KaiCommand.Question -> when {
                KaiSmallTalk.kindOf(text) != null -> KaiIntentKind.CHAT
                else -> when (com.shopai.app.brain.chat.KaiChatUnderstanding.understand(raw, now.toLocalDate(), people).intent) {
                    com.shopai.app.brain.chat.ChatIntent.CUSTOMER_DUE_DATE, com.shopai.app.brain.chat.ChatIntent.SUPPLIER_DUE_DATE,
                    com.shopai.app.brain.chat.ChatIntent.CUSTOMER_PAYMENTS, com.shopai.app.brain.chat.ChatIntent.CUSTOMER_LAST_PAYMENT,
                    com.shopai.app.brain.chat.ChatIntent.TODAY_COLLECTIONS, com.shopai.app.brain.chat.ChatIntent.UPCOMING_COLLECTIONS,
                    com.shopai.app.brain.chat.ChatIntent.OVERDUE_COLLECTIONS, com.shopai.app.brain.chat.ChatIntent.TOTAL_RECEIVABLE,
                    com.shopai.app.brain.chat.ChatIntent.TOTAL_PAYABLE -> KaiIntentKind.PAYMENT_QUERY
                    com.shopai.app.brain.chat.ChatIntent.CUSTOMER_BALANCE, com.shopai.app.brain.chat.ChatIntent.CUSTOMER_HISTORY -> KaiIntentKind.CUSTOMER_QUERY
                    com.shopai.app.brain.chat.ChatIntent.SUPPLIER_BALANCE, com.shopai.app.brain.chat.ChatIntent.SUPPLIER_HISTORY -> KaiIntentKind.SUPPLIER_QUERY
                    com.shopai.app.brain.chat.ChatIntent.UNKNOWN -> KaiIntentKind.UNKNOWN
                    else -> KaiIntentKind.BUSINESS_QUERY
                }
            }
        }
    }

    private fun reminderKind(r: ReminderRequest) = when (r) {
        is ReminderRequest.Create -> KaiIntentKind.CREATE_REMINDER
        is ReminderRequest.ListAll -> KaiIntentKind.LIST_REMINDERS
        is ReminderRequest.Cancel -> KaiIntentKind.CANCEL_REMINDER
        else -> KaiIntentKind.UPDATE_REMINDER
    }

    /**
     * Intents Kai Chat handles for the voice screen too: ALL of them. Pesunga's words — money entries, ledger questions,
     * the calculator, calls, unknown words — go through the same KaiAgent as Kai Chat (one understanding, one draft →
     * confirm → ledger path, one conversation). A money draft is confirmed by voice ("seri") or on its Kai Chat card.
     */
    @Suppress("UNUSED_PARAMETER")
    fun handledByKai(kind: KaiIntentKind) = true
}
