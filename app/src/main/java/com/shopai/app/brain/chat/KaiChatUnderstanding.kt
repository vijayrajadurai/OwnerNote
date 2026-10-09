package com.shopai.app.brain.chat

import com.shopai.app.brain.Direction
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
    /** "ellaa pending customers list pannu", "due date illama pending yaar?", "highest pending yaar kitta?": a list from the ledger ([ChatQuery.listScope]). */
    PENDING_LIST,
    /** "last 7 days la yaar yaar payment pannanga?", "pona maasam collection evlo?": payments actually made in a past period. */
    RECEIVED_PAYMENTS,
    /** "Kumar correct date-la tharuvaara, illa late-aa?", "yaarlam late-aa pay pannuvaanga?": how people paid against their due dates. */
    PAYMENT_BEHAVIOUR,
    UNKNOWN,
}

/** Which Daily Cash Note figure the owner is asking about. */
enum class CashAsk { FLOW, CASH_IN, CASH_OUT, UPI, UPI_IN, UPI_OUT, TOTAL_IN, TOTAL_OUT, NET, KALLAPETTI, OPENING, DAY_CLOSE, IMPORTANT }

/** A span of days the owner means ("innaikku", "next week", "adutha maasam 10th"). */
data class ChatPeriod(val from: LocalDate, val to: LocalDate, val kind: Kind) {
    enum class Kind { TODAY, TOMORROW, YESTERDAY, THIS_WEEK, NEXT_WEEK, THIS_MONTH, NEXT_MONTH, LAST_MONTH, DATE, LAST_DAYS, LAST_WEEK }

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
    /**
     * Which side the owner asked about: "Kumar enakku evlo tharanum?" (they owe the owner) vs "naan Kumar-ku evlo
     * tharanum?" (the owner owes them). Null: either side ("Kumar balance evlo?").
     */
    val side: com.shopai.app.brain.Direction? = null,
    /** For [ChatIntent.PENDING_LIST]: which people. */
    val listScope: ListScope? = null,
    /** "list pannu", "details sollu", "ellaa…": every record, not the top three. */
    val fullList: Boolean = false,
    /** "today due + overdue rendu list-um": both sets, kept apart. */
    val withOverdue: Boolean = false,
)

/** Which pending people a list question means. */
enum class ListScope { ALL, NO_DUE, HIGHEST, LOWEST }

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

        // ---- how someone pays: on the due date, or after it (read from their past entries) ----
        if (amount == null && !has(" reminder", " remind", " ninaivu") && (lateTalk.containsMatchIn(lower) && habit.containsMatchIn(lower) ||
                has(" eppadi pay", " yeppadi pay", " epdi pay", " payment habit", " paying habit"))) {
            val group = groupWords.containsMatchIn(lower)
            val side = if (has(" supplier", " suppliers", " naan yaarukku", " nan yaruku", " naan yaruku")) Direction.PAYABLE else null
            val who = if (person == PersonRef.Pronoun && group && !has(" avan ", " aval ", " avar ", " avaru ", " he ", " she ")) PersonRef.Pronoun else person
            return ChatQuery(ChatIntent.PAYMENT_BEHAVIOUR, who, null, period, side = side, fullList = group)
        }

        // ---- about one person ----
        val paidAlready = has(" already ", " kuduthana", " kuduthaana", " kuduthaan", " kuduthaara", " koduthana", " kuduthiruk", " katti", " kattina",
            " vandhucha", " vanthucha", " paid ", " has paid", " pay pannana", " ஏற்கனவே ", " கொடுத்தான", " கொடுத்தார",
            " pannirukk", " koduthiruk", " kuduthen", " koduthen", " kuduthirukken", " கொடுத்திருக்")
        val lastPayment = has(" last payment", " kadaisi", " kadaisiya", " recent payment", " latest payment", " கடைசி") ||
            (has(" last ", " last-a", " last-ah", " last ah ", " last a ", " last aa ", " last time") && has(" kuduth", " koduth", " pay ", " paid", " thandh", " anupp", " pannan", " pannaan"))
        val history = has(" history", " details", " statement", " transactions", " transaction ", " varalaaru", " kanakku ", " full kanakku", " விவரம்", " கணக்கு ",
            " vanginen", " vaanginen", " vangirukk", " vaangirukk", " வாங்கினேன்")
        val due = has(" eppo", " eppa ", " yeppa ", " yeppo ", " yepo ", " when ", " due ", " date ", " date-la", " date la", " thethi", " எப்போ", " எப்ப ", " தேதி")
        val balance = has(" evlo", " evvalavu", " how much", " balance", " pending", " baaki", " bakki", " tharanum", " kudukkanum", " kodukkanum",
            " varanum", " owe", " outstanding", " எவ்வளவு", " பாக்கி", " தரணும்", " கொடுக்கணும்")
        val personWords = paidAlready || lastPayment || history || due
        // "yaar / yaaru / yar / who" — asking WHO (a list from the records), never one person.
        val asksWho = has(" yaar", " yar ", " yaru ", " yaroda", " yaaroda", " yarukku", " yaruku", " who ", " whom", " whose", " யார்", " யாரு", " யாருக்கு")
        // "yaarukku / yarukku / to whom / யாருக்கு" — the owner pays them.
        val toWhom = has(" yaarukku", " yarukku", " yaruku", " to whom", " whom", " யாருக்கு")
        // "overdue customers details sollu", "ellaa pending list": about the ledger's people, not a person follow-up.
        val ledgerScope = has(" overdue", " thaandi", " thandi", " customers", " suppliers", " payable", " receivable", " list", " ellaa", " ellarum",
            " ellaarum", " all ", " highest", " lowest", " smallest", " illama", " illaama", " தாண்டி", " எல்லா")
        if (person != PersonRef.None || (personWords && !asksWho && !ledgerScope)) {
            if (person != PersonRef.None || isShortFollowUp(lower)) {
                val intent = when {
                    paidAlready -> ChatIntent.CUSTOMER_PAYMENTS
                    lastPayment -> ChatIntent.CUSTOMER_LAST_PAYMENT
                    history -> ChatIntent.CUSTOMER_HISTORY
                    due -> ChatIntent.CUSTOMER_DUE_DATE
                    balance || person != PersonRef.None -> ChatIntent.CUSTOMER_BALANCE
                    else -> ChatIntent.UNKNOWN
                }
                // The same grammar as a payment decides the side asked about — never the verb alone.
                val side = if (intent == ChatIntent.CUSTOMER_BALANCE) when (com.shopai.app.brain.tools.KaiPaymentDirection.explicitOf(lower)) {
                    com.shopai.app.brain.tools.OwedDirection.RECEIVABLE -> com.shopai.app.brain.Direction.RECEIVABLE
                    com.shopai.app.brain.tools.OwedDirection.PAYABLE -> com.shopai.app.brain.Direction.PAYABLE
                    null -> null
                } else if (ownerDid(lower)) Direction.PAYABLE else null
                return ChatQuery(intent, person, amount, period, personQuestion = personWords, side = side)
            }
        }

        // ---- about the business ----
        // "Who has to pay me?" is money coming in, not the owner paying.
        // "Yaaru kudukkanum?" (who has to give — me): the person asked about gives; "yaarukku kudukkanum?" (to whom) is the owner paying.
        val whoGives = has(" yaar ", " yaaru ", " yar ", " yaru ") && !toWhom && !has(" naan ", " nan ", " neenga ")
        val paysMe = has(" pay me", " pays me", " paying me", " pay us") || whoGives && has(" kudukkanum", " kodukkanum", " kudukanum", " kodukanum")
        val payWords = !paysMe && has(" kudukkanum", " kodukkanum", " kudukanum", " kodukanum", " kudukanu", " kudukka ", " pay ", " payable", " supplier", " i owe", " do i owe", " கொடுக்க", " குடுக்க")
        val collectWords = paysMe || has(" collect", " vasool", " tharanum", " varanum", " vanganum", " vaanganum", " receive", " receivable", " owes me",
            " owe me", " payment", " cash tharanum", " vanganu", " vaanganu", " வசூல்", " தரணும்", " வரணும்", " வாங்கணும்")
        // ---- one ledger question class each: which people, which side, which dates ----
        val listWords = has(" list", " details", " detail ", " ellaa", " ella ", " ellarum", " ellaarum", " ellaroda", " ellaaroda", " all ", " full ",
            " விவரம்", " எல்லா", " லிஸ்ட்")
        val dueWords = has(" due", " date", " thethi", " தேதி")
        val passed = has(" poiduchu", " poyiduchu", " pochu", " ponadhu", " mudinjiduchu", " mudinjudhu", " kadandhiduchu", " passed", " crossed",
            " போயிடுச்சு", " போச்சு", " முடிஞ்சிடுச்சு")
        val overdueAsk = has(" overdue", " late ", " thaandi", " thandi", " kadandhu", " miss aa", " தாண்டி") || (dueWords && passed)
        val pendingTalk = has(" pending", " baaki", " bakki", " tharanum", " kudukkanum", " amount", " payable", " receivable", " collection",
            " customers", " suppliers", " பாக்கி", " தரணும்")
        val payableSide = payWords && !collectWords || has(" suppliers", " payable", " naan yaarukku", " yaarukku naan")
        val side = if (payableSide) Direction.PAYABLE else Direction.RECEIVABLE
        val pastPeriod = period?.kind in setOf(ChatPeriod.Kind.YESTERDAY, ChatPeriod.Kind.LAST_DAYS, ChatPeriod.Kind.LAST_WEEK, ChatPeriod.Kind.LAST_MONTH)
        val paidWords = has(" pannanga", " pannaanga", " pannaga", " kuduthaanga", " kuduthanga", " kuduthaan", " kuduthaar", " vandhadhu", " vandhuchu",
            " vanthathu", " received", " collection", " vasool", " paid", " payment", " pannen", " kuduthen", " koduthen", " வந்தது", " கொடுத்தாங்க")
        val listScope = when {
            dueWords && has(" illama", " illaama", " illamal", " without", " no due", " இல்லாம") -> ListScope.NO_DUE
            has(" highest", " adhigama", " athigama", " adhigam ", " biggest", " maximum", " max ", " most ", " periya", " அதிகம்") -> ListScope.HIGHEST
            has(" smallest", " lowest", " kammiya", " kammi ", " kuraivaa", " kuraiva", " least", " minimum", " min ", " chinna", " குறைவ") -> ListScope.LOWEST
            listWords && (pendingTalk || asksWho) && period == null -> ListScope.ALL
            else -> null
        }
        val ledgerList: ChatQuery? = when {
            has(" reminder", " remind", " ninaivu", " நினைவூட்ட", " ரிமைண்டர்") -> null
            // "today due + overdue rendu list-um sollu": both sets, kept apart.
            overdueAsk && period?.kind == ChatPeriod.Kind.TODAY && has(" rendu", " both", " + ", " and ", " um ", "-um ", " ரெண்டு") ->
                ChatQuery(ChatIntent.TODAY_COLLECTIONS, PersonRef.None, amount, period, side = side, fullList = true, withOverdue = true)
            overdueAsk -> ChatQuery(ChatIntent.OVERDUE_COLLECTIONS, PersonRef.None, amount, period, side = side, fullList = listWords)
            pastPeriod && paidWords && !has(" sales", " vikkal", " selavu", " expense") ->
                ChatQuery(ChatIntent.RECEIVED_PAYMENTS, PersonRef.None, amount, period,
                    side = if (ownerDid(lower) || has(" naan ", " nan ")) Direction.PAYABLE else Direction.RECEIVABLE, fullList = true)
            listScope != null && (pendingTalk || asksWho || listScope != ListScope.ALL) ->
                ChatQuery(ChatIntent.PENDING_LIST, PersonRef.None, amount, period, side = side, listScope = listScope, fullList = true)
            else -> null
        }
        if (ledgerList != null) return ledgerList

        val intent = when {
            has(" reminder", " remind", " ninaivu", " நினைவூட்ட", " ரிமைண்டர்") -> ChatIntent.REMINDER_QUERY
            overdueAsk -> ChatIntent.OVERDUE_COLLECTIONS
            has(" total", " motham", " mothama", " overall", " மொத்தம்") && payWords && !collectWords -> ChatIntent.TOTAL_PAYABLE
            has(" total", " motham", " mothama", " overall", " மொத்தம்") && (collectWords || has(" pending", " baaki", " bakki", " பாக்கி", " outstanding")) -> ChatIntent.TOTAL_RECEIVABLE
            has(" sales", " sale ", " vikkal", " viyabaram", " vitradhu", " vithadhu", " vitrathu", " வியாபாரம்", " விற்பனை") -> ChatIntent.MONTHLY_SALES
            has(" purchase", " vaangunadhu", " vaanginadhu", " kolmudhal", " stock vaang", " கொள்முதல்") -> ChatIntent.MONTHLY_PURCHASES
            has(" expense", " selavu", " spent", " spend", " செலவு") -> ChatIntent.EXPENSE_SUMMARY
            has(" credit") && !payWords -> ChatIntent.CREDIT_SUMMARY
            has(" debit") -> ChatIntent.DEBIT_SUMMARY
            has(" transaction", " entries", " entry", " enna nadandhu", " enna nadanthu", " நடந்துச்சு") ->
                if (period == null || period.kind == ChatPeriod.Kind.TODAY) ChatIntent.TODAY_TRANSACTIONS else ChatIntent.BUSINESS_SUMMARY
            payWords && asksWho -> ChatIntent.TOTAL_PAYABLE
            // "innaikku yaarukku payment pannanum?", "today payment yaarukku?": the owner pays — to whom.
            toWhom && has(" payment", " pannanum", " cash", " panam", " kaasu", " money", " pay") -> ChatIntent.TOTAL_PAYABLE
            // "yaroda due irukku?", "yaroda payment pending?": whose money is still to come.
            asksWho && has(" due", " pending", " baaki", " bakki", " பாக்கி") -> ChatIntent.UPCOMING_COLLECTIONS
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
        return ChatQuery(intent, PersonRef.None, amount, period, side = if (intent == ChatIntent.TOTAL_PAYABLE) Direction.PAYABLE else null, fullList = listWords)
    }

    /** "late-aa", "correct date-la", "time-ku", "தாமதம்": paying after or on the due date. */
    private val lateTalk = Regex("""(?<![\p{L}])(late|delay|thaamadham|thamadham|thaamadhama|(?:correct|sariyana|sariyaana|right)\s*-?\s*(?:date|time|thethi)|on\s*time|time\s*-?\s*(?:ku|kku))(?![\p{L}])|தாமதம|லேட்|சரியான\s*(?:தேதி|நேரம்)""")
    /** A habit, not one payment: "pannuvaanga", "tharuvaara", "usually", "general-aa". */
    private val habit = Regex("""(?<![\p{L}])(pannuv\p{L}*|tharuv\p{L}*|kudupp\p{L}*|kudupaa\p{L}*|koduppa\p{L}*|tharanga|tharaanga|tharaanga|tharraanga|""" +
        """kudukkuranga|kudukuranga|kudukkuraanga|kodukkuranga|pannuranga|pannraanga|pannranga|pannuraanga|""" +
        """usually|generally|general|generala|eppavum|eppavume|always|pays?|paying)(?![\p{L}])|பண்ணுவா|தருவா|கொடுப்பா""")
    /** More than one person: "avanga", "yaarlam", "general-aa", "ellaarum". */
    private val groupWords = Regex("""(?<![\p{L}])(avanga|ivanga|avangaloda|ellarum|ellaarum|yarlam|yaarlam|yaarellam|yarellam|yaar|yar|yaaru|yaru|general|generala|everyone|who|customers|suppliers)(?![\p{L}])|அவங்க|யார்|எல்லா""")

    /** "naan Kumar-ku kuduthen", "Kumar kitta naan vanginen": the owner paid / bought — the supplier side of the books. */
    private fun ownerDid(lower: String): Boolean =
        Regex("""(?<![\p{L}])(kuduthen|koduthen|kuduthirukken|vanginen|vaanginen|vangirukken|vaangirukken|pannen|kattinen)(?![\p{L}])|கொடுத்தேன்|வாங்கினேன்""").containsMatchIn(lower)

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
        // "last 7 days", "kadandha 10 naal": a span ending today.
        Regex("""(?:last|past|kadandha|kadantha|pona|கடந்த)\s+(\d{1,3})\s*(?:days?|naal|naala|naatkal|naalla|நாள்)""").find(lower)?.let { m ->
            val n = m.groupValues[1].toLong().coerceIn(1, 366)
            return ChatPeriod(today.minusDays(n - 1), today, ChatPeriod.Kind.LAST_DAYS)
        }
        if (has(" last week", " pona vaaram", " poona vaaram", " pona week", " போன வாரம்")) return ChatPeriod(monday.minusWeeks(1), monday.minusDays(1), ChatPeriod.Kind.LAST_WEEK)
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
        "yar", "yaru", "yaroda", "yaaroda", "yarukku", "yaruku", "vanganu", "vaanganu", "kudukanum", "kodukanum",
        // List / ranking words said first ("Highest pending yaar kitta?", "Ellaa pending customers list pannu").
        "highest", "smallest", "lowest", "biggest", "maximum", "minimum", "ellaa", "ella", "ellarum", "ellaarum", "ellaaroda", "ellaroda",
        "all", "list", "which", "whose", "show", "their", "payments", "collections", "pending", "dues",
        // "Nan yaruku…", "Yeppa tharanum?", "General-ah yaarlam…": the owner, when / how words — never a name.
        "nan", "na", "naa", "yeppa", "yeppo", "yepo", "epo", "yeppadi", "yepdi", "general", "generala", "yarlam", "yaarlam", "yaarellam",
        "innai", "inni", "avnanga", "ivanga", "usually", "correct", "naalaiku", "nalaiku", "nalaikku", "naalai", "nalai", "endha", "yentha", "entha",
        "phone", "number", "mobile", "contact",
    ) + com.shopai.app.brain.tools.KaiFeed.notNames

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
