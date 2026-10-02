package com.shopai.app.brain.chat

import com.shopai.app.brain.KaiUnderstanding
import com.shopai.app.util.DocumentDates
import com.shopai.app.util.YearlessPolicy
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * What the owner is asking Kai. Extensible: add a value, a rule in
 * [KaiChatUnderstanding] and an answer in [KaiBusinessBrain].
 */
enum class ChatIntent {
    CUSTOMER_BALANCE,
    CUSTOMER_DUE_DATE,
    CUSTOMER_HISTORY,
    CUSTOMER_LAST_PAYMENT,
    /** "Avan already edhavadhu kuduthana?" — payments made so far. */
    CUSTOMER_PAYMENTS,
    TODAY_COLLECTIONS,
    UPCOMING_COLLECTIONS,
    OVERDUE_COLLECTIONS,
    SUPPLIER_BALANCE,
    SUPPLIER_DUE_DATE,
    SUPPLIER_HISTORY,
    TOTAL_RECEIVABLE,
    TOTAL_PAYABLE,
    TODAY_TRANSACTIONS,
    MONTHLY_SALES,
    MONTHLY_PURCHASES,
    CREDIT_SUMMARY,
    DEBIT_SUMMARY,
    EXPENSE_SUMMARY,
    BUSINESS_SUMMARY,
    REMINDER_QUERY,
    /** Today's Daily Cash Note, read only (which figure: [ChatQuery.cashAsk]). */
    DAILY_CASH,
    GENERAL_BUSINESS_QUERY,
    UNKNOWN,
}

/** Which Daily Cash Note figure the owner is asking about. */
enum class CashAsk { FLOW, CASH_IN, CASH_OUT, UPI, UPI_IN, UPI_OUT, TOTAL_IN, TOTAL_OUT, NET, KALLAPETTI, OPENING, DAY_CLOSE, IMPORTANT }

/** A span of days the owner means ("innaikku", "next week", "adutha maasam 10th"). */
data class ChatPeriod(val from: LocalDate, val to: LocalDate, val kind: Kind) {
    enum class Kind { TODAY, TOMORROW, YESTERDAY, THIS_WEEK, NEXT_WEEK, THIS_MONTH, NEXT_MONTH, LAST_MONTH, DATE }

    operator fun contains(date: LocalDate) = !date.isBefore(from) && !date.isAfter(to)
}

/** Who the owner means: a name said now, a pronoun ("avan"), or nothing (a follow-up). */
sealed interface PersonRef {
    data class Named(val name: String) : PersonRef
    /** "avan / aval / avar / he / she" — the person talked about just before. */
    data object Pronoun : PersonRef
    data object None : PersonRef
}

/** The owner's question, understood: intent + extracted entities. Nothing is looked up yet. */
data class ChatQuery(
    val intent: ChatIntent,
    val person: PersonRef = PersonRef.None,
    /** An amount the owner mentioned ("Kumar 3000 eppo tharanum?"): checked against the records, never used as a fact. */
    val amount: Double? = null,
    val period: ChatPeriod? = null,
    /** Words that only make sense about a person ("eppo?", "already kuduthana?"): a follow-up. */
    val personQuestion: Boolean = false,
    /** For [ChatIntent.DAILY_CASH]: which figure. */
    val cashAsk: CashAsk? = null,
)

/**
 * Kai Chat's own language understanding — no AI: Tamil, Tanglish and
 * English business phrases mapped to intents by rules, entities pulled out
 * deterministically. "Kumar evlo tharanum?", "Kumar balance enna?" and
 * "Kumar pending evlo?" all become CUSTOMER_BALANCE for Kumar.
 */
object KaiChatUnderstanding {

    fun understand(text: String, today: LocalDate, knownPeople: List<String>): ChatQuery {
        val clean = text.trim().replace(Regex("""\s+"""), " ")
        if (clean.isEmpty()) return ChatQuery(ChatIntent.UNKNOWN)
        val lower = " " + clean.lowercase(Locale.ROOT).replace(Regex("""[?!.,;:]"""), " ").replace(Regex("""\s+"""), " ") + " "
        fun has(vararg words: String) = words.any { lower.contains(it) }

        val named = KaiUnderstanding.knownPerson(clean, knownPeople)
        val pronoun = has(" avan ", " aval ", " avar ", " avanga ", " avaru ", " avangalukku ", " avanukku ", " he ", " she ", " him ", " her ", " they ", " அவன் ", " அவர் ", " அவங்க ")
        // An unknown name said in a person question ("Muthu evlo tharanum?").
        val unknownName = if (named == null) unknownPersonIn(clean) else null
        val person = when {
            named != null -> PersonRef.Named(named)
            unknownName != null -> PersonRef.Named(unknownName)
            pronoun -> PersonRef.Pronoun
            else -> PersonRef.None
        }
        val amount = KaiUnderstanding.amountsIn(clean, today).singleOrNull()?.takeIf { it > 0 }
        val period = period(clean, lower, today)

        // ---- the Daily Cash Note (read only) ----
        if (named == null) cashAsk(lower)?.let { return ChatQuery(ChatIntent.DAILY_CASH, PersonRef.None, amount, period, cashAsk = it) }

        // ---- about one person ----
        val paidAlready = has(" already ", " kuduthana", " kuduthaana", " kuduthaan", " kuduthaara", " koduthana", " kuduthiruk", " katti", " kattina",
            " vandhucha", " vanthucha", " paid ", " has paid", " pay pannana", " ஏற்கனவே ", " கொடுத்தான", " கொடுத்தார")
        val lastPayment = has(" last payment", " kadaisi", " kadaisiya", " recent payment", " latest payment", " கடைசி")
        val history = has(" history", " details", " statement", " transactions", " varalaaru", " kanakku ", " full kanakku", " விவரம்", " கணக்கு ")
        val due = has(" eppo", " eppa ", " when ", " due ", " date ", " எப்போ", " எப்ப ")
        val balance = has(" evlo", " evvalavu", " how much", " balance", " pending", " baaki", " bakki", " tharanum", " kudukkanum", " kodukkanum",
            " varanum", " owe", " outstanding", " எவ்வளவு", " பாக்கி", " தரணும்", " கொடுக்கணும்")
        val personWords = paidAlready || lastPayment || history || due
        if (person != PersonRef.None || (personWords && !has(" yaar", " who ", " யார்", " yaarukku"))) {
            if (person != PersonRef.None || isShortFollowUp(lower)) {
                val intent = when {
                    paidAlready -> ChatIntent.CUSTOMER_PAYMENTS
                    lastPayment -> ChatIntent.CUSTOMER_LAST_PAYMENT
                    history -> ChatIntent.CUSTOMER_HISTORY
                    due -> ChatIntent.CUSTOMER_DUE_DATE
                    balance || person != PersonRef.None -> ChatIntent.CUSTOMER_BALANCE
                    else -> ChatIntent.UNKNOWN
                }
                return ChatQuery(intent, person, amount, period, personQuestion = personWords)
            }
        }

        // ---- about the business ----
        val payWords = has(" kudukkanum", " kodukkanum", " kudukka ", " pay ", " payable", " supplier", " i owe", " do i owe", " கொடுக்க")
        val collectWords = has(" collect", " vasool", " tharanum", " varanum", " vanganum", " vaanganum", " receive", " receivable", " owes me",
            " owe me", " payment", " cash tharanum", " வசூல்", " தரணும்", " வரணும்", " வாங்கணும்")
        val intent = when {
            has(" reminder", " remind", " ninaivu", " நினைவூட்ட", " ரிமைண்டர்") -> ChatIntent.REMINDER_QUERY
            has(" overdue", " late ", " thaandi", " thandi", " kadandhu", " miss aa", " தாண்டி") -> ChatIntent.OVERDUE_COLLECTIONS
            has(" total", " motham", " mothama", " overall", " மொத்தம்") && payWords && !collectWords -> ChatIntent.TOTAL_PAYABLE
            has(" total", " motham", " mothama", " overall", " மொத்தம்") && (collectWords || has(" pending", " baaki", " bakki", " பாக்கி", " outstanding")) -> ChatIntent.TOTAL_RECEIVABLE
            has(" sales", " sale ", " vikkal", " viyabaram", " vitradhu", " vithadhu", " vitrathu", " வியாபாரம்", " விற்பனை") -> ChatIntent.MONTHLY_SALES
            has(" purchase", " vaangunadhu", " vaanginadhu", " kolmudhal", " stock vaang", " கொள்முதல்") -> ChatIntent.MONTHLY_PURCHASES
            has(" expense", " selavu", " spent", " spend", " செலவு") -> ChatIntent.EXPENSE_SUMMARY
            has(" credit") && !payWords -> ChatIntent.CREDIT_SUMMARY
            has(" debit") -> ChatIntent.DEBIT_SUMMARY
            has(" transaction", " entries", " entry", " enna nadandhu", " enna nadanthu", " நடந்துச்சு") ->
                if (period == null || period.kind == ChatPeriod.Kind.TODAY) ChatIntent.TODAY_TRANSACTIONS else ChatIntent.BUSINESS_SUMMARY
            payWords && has(" yaar", " yaarukku", " who", " whom", " யாருக்கு", " யார்") -> ChatIntent.TOTAL_PAYABLE
            collectWords || has(" yaar", " who ", " யார்") && has(" payment", " money", " cash", " panam", " kaasu") -> when {
                period?.kind == ChatPeriod.Kind.TODAY -> ChatIntent.TODAY_COLLECTIONS
                else -> ChatIntent.UPCOMING_COLLECTIONS
            }
            payWords -> ChatIntent.TOTAL_PAYABLE
            has(" summary", " eppadi pogudhu", " epdi pogudhu", " epdi irukku", " eppadi irukku", " how is", " how's", " report", " nilamai", " status",
                " important", " mukkiyam", " முக்கியம்", " எப்படி") -> ChatIntent.BUSINESS_SUMMARY
            has(" business", " kadai", " shop", " kanakku", " money", " panam", " kaasu", " cash", " வியாபார", " கடை") -> ChatIntent.GENERAL_BUSINESS_QUERY
            else -> ChatIntent.UNKNOWN
        }
        return ChatQuery(intent, PersonRef.None, amount, period)
    }

    /**
     * A question about the Daily Cash Note (cash / UPI in and out, net, the
     * Kallapetti, the opening, day close) — or null. "Yaaru cash tharanum?"
     * is about customers, not the cash note, so plain "cash" isn't enough.
     */
    private fun cashAsk(lower: String): CashAsk? {
        fun has(vararg w: String) = w.any { lower.contains(it) }
        val kalla = has(" kalla", "kallapetti", "kallappetti", " petti", "cash box", "cashbox", "கல்லா", "பெட்டி")
        val opening = has(" opening", "opening balance", "thodakka", "தொடக்க")
        val dayClose = has("day close", " close ", " closing", " submit", " mudichu", " முடி")
        val upi = has(" upi", " gpay", " phonepe", " paytm", "யுபிஐ")
        val cashLa = has(" cash la", " cash-la", " cashla", " cash in", " cash out", " cash vandh", " cash vanth", " cash poch", " cash pona", " cash ponadhu",
            " evlo cash", " cash evlo", " cash-a ", " ரொக்கம்")
        val flow = has("cash flow", "cashflow", "cash situation", "cash position", "cash nilamai", "cash status", "daily cash", "cash note", "panam epdi", "kaasu epdi")
        val totalIn = has("total in", "mothama vandh", "mothama in", "motham vandh")
        val totalOut = has("total out", "mothama pon", "mothama out", "motham pon")
        val net = has(" net ", " net-", " net amount", " nikara", " லாபம்")
        val inWords = has(" in ", " in?", " vandh", " vanth", " received", " came", " varavu", " வந்த")
        val outWords = has(" out", " poch", " pona", " ponadhu", " sent", " paid", " selavu", " spent", " போன", " செலவு")
        val important = has(" important", " mukkiyam", " முக்கியம்")
        val today = has(" inniku", " innikku", " innaikku", " innaiku", " inniki", " today", " இன்னைக்கு", " இன்று")
        return when {
            kalla -> CashAsk.KALLAPETTI
            opening -> CashAsk.OPENING
            dayClose && (today || has(" day ")) -> CashAsk.DAY_CLOSE
            upi -> when { inWords && !outWords -> CashAsk.UPI_IN; outWords && !inWords -> CashAsk.UPI_OUT; else -> CashAsk.UPI }
            totalIn -> CashAsk.TOTAL_IN
            totalOut -> CashAsk.TOTAL_OUT
            net -> CashAsk.NET
            flow -> CashAsk.FLOW
            cashLa -> when { outWords && !inWords -> CashAsk.CASH_OUT; else -> CashAsk.CASH_IN }
            important && today -> CashAsk.IMPORTANT
            else -> null
        }
    }

    // "Eppo?", "Eppo tharanum?", "Already kuduthana?": short, no subject — about the last person.
    private fun isShortFollowUp(lower: String) = lower.trim().split(' ').size <= 4

    // ------------------------------------------------------------ period

    private fun period(text: String, lower: String, today: LocalDate): ChatPeriod? {
        fun has(vararg words: String) = words.any { lower.contains(it) }
        val monday = today.with(DayOfWeek.MONDAY)
        val nextMonthStart = today.withDayOfMonth(1).plusMonths(1)

        // "next month 10th" / "adutha maasam 10".
        Regex("""(?:next month|adutha maasam|aduththa maasam|adutha month|next maasam)\s+(\d{1,2})(?:st|nd|rd|th)?""").find(lower)?.let { m ->
            val day = m.groupValues[1].toInt()
            runCatching { nextMonthStart.withDayOfMonth(day) }.getOrNull()?.let { return ChatPeriod(it, it, ChatPeriod.Kind.DATE) }
        }
        when {
            has(" innaikku", " innaiku", " inniki", " inniku ", " innikku", " indru ", " today", " இன்னைக்கு", " இன்று") -> return ChatPeriod(today, today, ChatPeriod.Kind.TODAY)
            has(" naalaikku", " nalaiku", " naalaiku", " tomorrow", " நாளைக்கு", " நாளை") -> return today.plusDays(1).let { ChatPeriod(it, it, ChatPeriod.Kind.TOMORROW) }
            has(" nethu", " netru", " yesterday", " நேத்து", " நேற்று") -> return today.minusDays(1).let { ChatPeriod(it, it, ChatPeriod.Kind.YESTERDAY) }
            has(" next week", " adutha vaaram", " aduththa vaaram", " adutha week", " next vaaram", " அடுத்த வாரம்") ->
                return ChatPeriod(monday.plusWeeks(1), monday.plusWeeks(1).plusDays(6), ChatPeriod.Kind.NEXT_WEEK)
            has(" this week", " indha week", " intha week", " indha vaaram", " intha vaaram", " இந்த வாரம்") ->
                return ChatPeriod(monday, monday.plusDays(6), ChatPeriod.Kind.THIS_WEEK)
            has(" next month", " adutha maasam", " aduththa maasam", " adutha month", " next maasam", " அடுத்த மாசம்", " அடுத்த மாதம்") ->
                return ChatPeriod(nextMonthStart, nextMonthStart.plusMonths(1).minusDays(1), ChatPeriod.Kind.NEXT_MONTH)
            has(" last month", " pona maasam", " poona maasam", " pona month", " போன மாசம்") ->
                return today.withDayOfMonth(1).minusMonths(1).let { ChatPeriod(it, it.plusMonths(1).minusDays(1), ChatPeriod.Kind.LAST_MONTH) }
            has(" this month", " indha month", " intha month", " indha maasam", " intha maasam", " month ", " maasam", " இந்த மாசம்", " மாதம்") ->
                return today.withDayOfMonth(1).let { ChatPeriod(it, it.plusMonths(1).minusDays(1), ChatPeriod.Kind.THIS_MONTH) }
        }
        // A written date: 10.10.2026, 10/10/2026, October 10, October 10th.
        DocumentDates.findMatch(text, today, allowYearless = true, yearless = YearlessPolicy.CURRENT_YEAR)?.let { m ->
            val date = if (m.yearAssumed && m.date.isBefore(today)) m.date.plusYears(1) else m.date
            return ChatPeriod(date, date, ChatPeriod.Kind.DATE)
        }
        // "10th" alone: this month's, or next month's if it has passed.
        Regex("""(?<![\d/.\-])(\d{1,2})(st|nd|rd|th)\b""").find(lower)?.let { m ->
            val day = m.groupValues[1].toInt()
            val thisMonth = runCatching { today.withDayOfMonth(day) }.getOrNull()
            val date = if (thisMonth != null && !thisMonth.isBefore(today)) thisMonth else runCatching { nextMonthStart.withDayOfMonth(day) }.getOrNull()
            date?.let { return ChatPeriod(it, it, ChatPeriod.Kind.DATE) }
        }
        return null
    }

    // ------------------------------------------------------------ names

    private val notNames = setOf(
        "evlo", "evvalavu", "tharanum", "kudukkanum", "kodukkanum", "varanum", "balance", "enna", "pending", "eppo", "eppa", "last", "payment",
        "yaaru", "yaar", "yaarukku", "enakku", "ennaku", "total", "innaikku", "innaiku", "inniki", "inniku", "innikku", "indru", "naalaikku", "next", "week", "month", "maasam",
        "vaaram", "adutha", "indha", "intha", "avan", "aval", "avar", "avanga", "already", "edhavadhu", "kuduthana", "history", "sollu", "sollunga",
        "cash", "collection", "collect", "kitta", "kita", "irukku", "iruku", "ungalukku", "naan", "owner", "sir", "how", "much", "what", "when",
        "who", "does", "did", "is", "the", "a", "an", "to", "me", "my", "i", "owe", "owes", "due", "date", "today", "tomorrow", "yesterday",
        "summary", "business", "sales", "expense", "expenses", "selavu", "credit", "debit", "reminder", "reminders", "kanakku", "details",
        "status", "report", "overall", "motham", "mothama", "important", "transactions", "transaction", "entries", "purchase", "purchases",
        "supplier", "suppliers", "customer", "customers", "money", "panam", "kaasu", "rs", "rupees", "please", "pls", "kadaisi", "recent",
        "paid", "pay", "has", "have", "this", "that", "and", "or", "for", "from", "with", "of", "on", "in", "at", "by", "ku", "kku", "ukku",
        "nethu", "netru", "overdue", "late", "thaandi", "hello", "hi", "vanakkam", "ok", "okay", "seri", "romba", "konjam", "epdi", "eppadi",
        "pogudhu", "nalla", "illa", "venum", "theriyanum", "kattina", "katti", "vandhucha", "statement",
    )

    /**
     * A name the owner said that isn't in the records ("Muthu evlo tharanum?"),
     * so Kai can say it has no record instead of answering about someone else.
     * Only a capitalised word, or the first word before a person question.
     */
    private fun unknownPersonIn(text: String): String? {
        val words = Regex("""[\p{L}][\p{L}\p{M}'.]*""").findAll(text).map { it.value.trim('.', '\'') }.toList()
        val first = words.firstOrNull() ?: return null
        val lower = text.lowercase(Locale.ROOT)
        val asksAboutSomeone = listOf("evlo", "tharanum", "kudukkanum", "balance", "pending", "eppo", "history", "last payment", "kuduthana", "owe")
            .any { lower.contains(it) }
        if (!asksAboutSomeone) return null
        val base = first.lowercase(Locale.ROOT).removeSuffix("-kitta").removeSuffix("kitta").removeSuffix("-ku").trimEnd('-')
        if (base in notNames || base.length < 3 || first.any { it.isDigit() }) return null
        return first.take(base.length).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}
