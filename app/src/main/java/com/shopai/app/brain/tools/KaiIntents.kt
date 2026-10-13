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
    private val billWords = Regex("""(?i)(?<![\p{L}])(bill|bills|invoice|invoices|receipt|receipts)(?![\p{L}])|பில்|ரசீது|இன்வாய்ஸ்""")
    private val billDo = Regex(
        """(?i)(?<![\p{L}])(scan|add|enter|podu|potu|poodu|eduthu|edu|edunga|photo|pic|picture|camera|cam|open|upload|serthu|sethu|save|click)(?![\p{L}])|சேர்|ஸ்கேன்""",
    )

    /** "intha bill la irukura stock ah add pannu / seru / seathuko", "… kali / less / kammi pannu", "purchase invoice-na stock add pannidu". */
    data class BillStockAsk(val way: com.shopai.app.util.BillStock.Way?, val rule: Boolean, val unclear: Boolean = false)
    private val stockThing = Regex("""(?i)(?<![\p{L}])(stock|stocks|inventory|item|items|saamaan|saaman|saman|porul|maal)(?![\p{L}])|ஸ்டாக்|சாமான்|பொருள்""")
    private val stockUp = Regex("""(?i)(?<![\p{L}])(add|seru|sethu|serthu|seathu|serthuko|sethuko|seathuko|serthukko|sethukko|seathukko|serkka|koottu|kootu|increase|yeathu|yethu)(?![\p{L}])|சேர்|கூட்டு""")
    private val stockDown = Regex("""(?i)(?<![\p{L}])(kali|kazhi|kalichu|kazhichu|kalichuko|kalichiko|kazhichuko|less|kammi|kurai|kuraichu|kuraichuko|minus|reduce|decrease|edunga)(?![\p{L}])|கழி|கம்மி|குறை|லெஸ்""")
    private val purchaseSale = Regex("""(?i)(?<![\p{L}])(purchase|purchases|sale|sales|selling|vaangina|vitha)(?![\p{L}])|பர்சேஸ்|சேல்ஸ்""")
    private val ruleWord = Regex("""(?i)(?<![\p{L}])(iruntha|irundha|irunthaa|irundhaa|irunthal|aana|aanaa|na)(?![\p{L}])|இருந்தா""")

    fun billStock(text: String): BillStockAsk? {
        val t = KaiSpokenWords.normalize(text)
        if (!billWords.containsMatchIn(t) || !stockThing.containsMatchIn(t)) return null
        val up = stockUp.containsMatchIn(t)
        val down = stockDown.containsMatchIn(t)
        // "Purchase invoice-ah iruntha stock add pannidu, sales-ah iruntha less panniko": the rule itself — by the bill.
        if (purchaseSale.containsMatchIn(t) && ruleWord.containsMatchIn(t) && (up || down)) return BillStockAsk(null, rule = true)
        return when {
            up && !down -> BillStockAsk(com.shopai.app.util.BillStock.Way.IN, rule = false)
            down && !up -> BillStockAsk(com.shopai.app.util.BillStock.Way.OUT, rule = false)
            // "intha bill la irukura stock ah check pannu": the bill's stock, but which way isn't said — Kai asks.
            Regex("""(?i)(?<![\p{L}])(intha|indha|inthe|this)(?![\p{L}])|இந்த""").containsMatchIn(t) && !Regex("""(?i)(?<![\p{L}])(evlo|evvalavu|how\s+much)(?![\p{L}])""").containsMatchIn(t) ->
                BillStockAsk(null, rule = false, unclear = true)
            else -> null
        }
    }

    /** The answer to "add pannanuma, illa kammi pannanuma?": in ("add", "seru", "purchase"), out ("kammi", "less", "sales"), or null. */
    fun stockWay(text: String): com.shopai.app.util.BillStock.Way? {
        val t = KaiSpokenWords.normalize(text)
        val inWord = stockUp.containsMatchIn(t) || Regex("""(?i)(?<![\p{L}])(purchase|vaangina|vandhadhu|vandhuchu|in)(?![\p{L}])|பர்சேஸ்""").containsMatchIn(t)
        val outWord = stockDown.containsMatchIn(t) || Regex("""(?i)(?<![\p{L}])(sale|sales|vitha|vithadhu|vithuchu|out)(?![\p{L}])|சேல்ஸ்""").containsMatchIn(t)
        return when {
            inWord && !outWord -> com.shopai.app.util.BillStock.Way.IN
            outWord && !inWord -> com.shopai.app.util.BillStock.Way.OUT
            else -> null
        }
    }

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
            is KaiCommand.Stock, KaiCommand.LowStock, is KaiCommand.MoneyBalance, KaiCommand.TopProducts, KaiCommand.SlowStock,
            KaiCommand.StockValue, KaiCommand.StockReport -> KaiIntentKind.BUSINESS_QUERY
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
