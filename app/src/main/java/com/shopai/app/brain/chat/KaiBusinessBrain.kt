package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.Direction
import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiLanguage
import com.shopai.app.brain.KaiMood
import com.shopai.app.brain.KaiUnderstanding
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import java.time.LocalDate
import java.util.Locale
import kotlin.random.Random

/**
 * One day of the Daily Cash Note exactly as the app already has it (read
 * only): its entries' totals from the existing calculation, the Kallapetti
 * opening and current amount from the existing cash-box snapshot, and
 * whether the day is closed. Kai never changes any of it.
 */
data class CashNoteView(
    val date: LocalDate,
    val entries: Int,
    val totals: com.shopai.app.data.model.DailyCashTotals,
    /** Null when the owner hasn't set the opening Kallapetti amount. */
    val opening: Double?,
    /** Opening + cash movement (the existing Kallapetti figure); null without an opening. */
    val kallapetti: Double?,
    val dayClosed: Boolean,
)

/** Daily Cash Note totals for a span of days (cash + UPI). */
data class CashBookTotals(val totalIn: Double, val totalOut: Double, val entries: Int)

/**
 * Read-only access to the owner's records — the only source of facts Kai
 * Chat has. Implemented over the existing repositories (backend ledger +
 * the phone's Daily Cash Note); faked in tests.
 */
interface KaiBooks {
    /** Customers, suppliers (with balances calculated by the backend), reminders, cash flow. */
    suspend fun snapshot(): BusinessSnapshot?
    /** One party's entries and payments. */
    suspend fun history(party: PartyFacts): PartyHistory?
    /** Daily Cash Note in/out between two dates (inclusive), or null when nothing is recorded. */
    suspend fun cashBook(from: LocalDate, to: LocalDate): CashBookTotals?
    /** One day of the Daily Cash Note (read only), or null when it can't be read. */
    suspend fun cashNote(day: LocalDate): CashNoteView? = null
    /** Something was just saved: drop any cached copy, so the next read is the ledger itself (never a stale balance). */
    fun changed() {}
}

/** One reply in the chat: text only (no voice), and Kai's mood for his animation. */
data class ChatReply(val text: String, val mood: KaiMood, val intent: ChatIntent)

/**
 * KAI's own Business Brain for Kai Chat — no AI service, no per-message cost.
 *
 *   text → intent + entities (KaiChatUnderstanding) → the right person
 *   (never guessed) → the records (KaiBooks: the database is the truth) →
 *   plain-code arithmetic → a reply from safe templates.
 *
 * It keeps a short conversation context, so "Eppo?" and "Avan already
 * edhavadhu kuduthana?" are about the person asked about just before.
 * Future inputs (photos of bills and notes) can feed structured data into
 * the same books and brain.
 */
class KaiBusinessBrain(
    private val books: KaiBooks,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val random: Random = Random.Default,
) {
    /** Short conversation memory. */
    private var lastParty: PartyFacts? = null
    /** Parties the owner must choose between ("Kumar-nu rendu customer irukku"). */
    private var choices: List<PartyFacts> = emptyList()
    private var choiceQuery: ChatQuery? = null

    /**
     * The last list answer ("Innaikku 12 per tharanum"), as structured rows from the ledger — so "details sollu",
     * "avanga total evlo?" and "due date-um sollu" mean those same people, even after a stock or small-talk detour.
     */
    private data class LedgerList(val kind: ListKind, val side: Direction, val period: ChatPeriod?, val rows: List<Row>) {
        data class Row(val party: PartyFacts, val amount: Double, val date: LocalDate?)
        val total: Double get() = rows.fold(java.math.BigDecimal.ZERO) { t, r -> t + java.math.BigDecimal.valueOf(r.amount) }.toDouble()
    }
    private enum class ListKind { DUE, OVERDUE, PENDING, NO_DUE, RANKED, PAID }
    private var lastList: LedgerList? = null
    /** The latest ledger answer was [lastList] (a person answer since then makes a bare "details sollu" about them). */
    private var listIsLatest = false

    /**
     * From the agent's entity resolver, before a question: the record the owner's words point to among same-named ones
     * (place, shop, phone, the one being talked about) and how to name each ("Nagapattinam Lokesh"). Never a guess.
     */
    private var hintId: String? = null
    private var hintLabels: Map<String, String> = emptyMap()
    fun hint(partyId: String?, labels: Map<String, String>) {
        hintId = partyId
        if (labels.isNotEmpty() || choices.isEmpty()) hintLabels = labels
    }

    /** Kai asked "which one?" and waits for the owner's pick. */
    fun awaitingChoice(): Boolean = choices.isNotEmpty()

    /** "rendu perum", "both", "ellaarum", "all Kumar": every record of that name, each shown on its own. */
    private val everyOne = Regex("""(?i)(?<![\p{L}])(rendu\s*perum|rendu\s*per|both|ellaarum|ellarum|ellaa|ella|all|everyone|moonu\s*perum)(?![\p{L}])|ரெண்டு|எல்லா""")
    private val combined = Regex("""(?i)(?<![\p{L}])(total|motham|mothama|serthu|together|combined)(?![\p{L}])|மொத்தம்|சேர்த்து""")

    /** Each same-named record on its own line; a combined total only when the owner asked for one. Never merged silently. */
    private fun everyRecord(name: String, records: List<PartyFacts>, intent: ChatIntent, lang: KaiLang, text: String): ChatReply {
        val lines = records.mapIndexed { i, p ->
            val a = KaiFormat.rupees(p.pending)
            val pay = p.side == Direction.PAYABLE
            "${i + 1}. ${label(p)} — $a " + when (lang) {
                KaiLang.TAMIL -> if (pay) "கொடுக்கணும்" else "தரணும்"
                KaiLang.TANGLISH -> if (pay) "kudukkanum" else "tharanum"
                KaiLang.ENGLISH -> if (pay) "to pay" else "to collect"
            }
        }
        val head = when (lang) {
            KaiLang.TAMIL -> "ஓனர், $name-னு ${records.size} பேர்:"
            KaiLang.TANGLISH -> "Owner, $name-nu ${records.size} per:"
            KaiLang.ENGLISH -> "Owner, ${records.size} named $name:"
        }
        val total = if (combined.containsMatchIn(text)) {
            val t = KaiFormat.rupees(records.fold(java.math.BigDecimal.ZERO) { s, p -> s + java.math.BigDecimal.valueOf(p.pending) }.toDouble())
            "\n" + when (lang) {
                KaiLang.TAMIL -> "${records.size} பேரும் சேர்த்து $t."
                KaiLang.TANGLISH -> "${records.size} perum serthu $t."
                KaiLang.ENGLISH -> "Together: $t."
            }
        } else ""
        listIsLatest = false
        return reply(intent, KaiMood.EXPLAINING, lang, head + "\n" + lines.joinToString("\n") + total)
    }

    /** The answer to "which Lokesh?" ("Nagapattinam", "rendavadhu"), or null when the words don't pick one (the question is dropped). */
    suspend fun answerChoice(text: String): ChatReply? {
        if (choices.isEmpty()) return null
        // "Rendu Murugan-oda sollu" to "which Murugan?": both, not the second one.
        if (everyOne.containsMatchIn(text) || bothOf(choices.first().name).containsMatchIn(text)) {
            val all = choices
            val q = choiceQuery ?: ChatQuery(ChatIntent.CUSTOMER_BALANCE)
            choices = emptyList()
            choiceQuery = null
            return everyRecord(all.first().name, all, q.intent, chatLanguage(text), text)
        }
        val chosen = pick(text, choices)
        val q = choiceQuery ?: ChatQuery(ChatIntent.CUSTOMER_BALANCE)
        choices = emptyList()
        choiceQuery = null
        chosen ?: return null
        lastParty = chosen
        listIsLatest = false
        return answerAbout(named(chosen), q, chatLanguage(text), today())
    }

    /** The words of the question being answered (for "rendu perum" / "total" checks). */
    private var rawText = ""

    suspend fun ask(text: String): ChatReply {
        rawText = text
        val day = today()
        val lang = chatLanguage(text)
        if (text.isBlank()) return reply(ChatIntent.UNKNOWN, KaiMood.CLARIFY, lang, unclear(lang))
        val ledger = books.snapshot()

        // Answering "which Kumar?".
        if (choices.isNotEmpty()) {
            pick(text, choices)?.let { chosen ->
                val q = choiceQuery ?: ChatQuery(ChatIntent.CUSTOMER_BALANCE)
                choices = emptyList()
                choiceQuery = null
                lastParty = chosen
                return answerAbout(chosen, q, lang, day)
            }
            choices = emptyList()
            choiceQuery = null
        }

        val query = KaiChatUnderstanding.understand(text, day, ledger?.people.orEmpty())
        // The Daily Cash Note is on the phone: it answers even when the ledger can't be reached.
        if (query.intent == ChatIntent.DAILY_CASH) return dailyCash(query, ledger, lang, day)
        if (ledger == null) return reply(ChatIntent.UNKNOWN, KaiMood.ERROR, lang, noRecords(lang))
        val listWasLatest = listIsLatest
        listIsLatest = false
        return when {
            query.intent == ChatIntent.UNKNOWN -> reply(query.intent, KaiMood.CLARIFY, lang, unclear(lang))
            // "Kumar correct date-la tharuvaara?", "avar late-aa tharuvaara?": one person's habit.
            query.intent == ChatIntent.PAYMENT_BEHAVIOUR && (query.person is PersonRef.Named || query.person == PersonRef.Pronoun && !query.fullList) ->
                personAnswer(query, ledger, lang, day)
            query.intent == ChatIntent.PAYMENT_BEHAVIOUR -> groupHabits(query, ledger, lang, day, listWasLatest)
            isPersonIntent(query.intent) -> personAnswer(query, ledger, lang, day)
            else -> businessAnswer(query, ledger, lang, day)
        }
    }

    // ------------------------------------------------------- list follow-ups

    /** "avanga", "ellaarum", "12 per", "அவங்க", "12 பேருடைய": the people of the last list. */
    private val listReference = Regex(
        """(?i)(?<![\p{L}])(avanga|avangaloda|avangalukku|ivanga|ellaa|ellaarum|ellarum|ellaaroda|ellaroda|ellaarudaya|everyone|them|those|andha\s+list)(?![\p{L}])|""" +
            """\d+\s*(?:per|peru|perudaya|peroda|people|names?)(?![\p{L}])|அவங்க|எல்லா|\d+\s*பேர""",
    )
    /** Without a reference word, only these short asks follow a list ("details sollu", "yaar yaar?", "due date-um sollu"). */
    private val listAsk = Regex(
        """(?i)(?<![\p{L}])(details?|list|yaar\s*yaar|yaaru|names?|due\s*date|thethi|amount|total|motham|mothama|eppa|yeppa|eppo|yeppo|epo|when)(?![\p{L}])|விவரம்|யார்\s*யார்|தேதி|மொத்தம்|லிஸ்ட்|எப்போ|எப்ப""",
    )
    /** "Yeppa tharanum?" just after a list: when each of those people pays — the list's own dates, not a new question. */
    private val whenOnly = Regex("""(?i)^\s*(eppa|yeppa|eppo|yeppo|epo|when|எப்போ|எப்ப)\s*(tharanum|tharuvaanga|tharuvanga|kudukkanum|kudukanum|varum|due)?\s*\??\s*$""")
    /** Words that set a new scope ("overdue customers", "suppliers", "innaikku", "pending list"): a fresh question, not the old list. */
    private val newScope = Regex(
        """(?i)(?<![\p{L}])(overdue|thaandi|thandi|thaandina|poiduchu|pochu|customers?|suppliers?|payable|receivable|pending|innaikku|innaiku|inniku|today|naalaikku|tomorrow|""" +
            """week|month|maasam|vaaram|highest|lowest|smallest|biggest|illama|illaama|kudukkanum|tharanum|collection)(?![\p{L}])|இன்னைக்கு|தாண்டி|பாக்கி|தரணும்""",
    )
    private val totalAsk = Regex("""(?i)(?<![\p{L}])(total|motham|mothama|evlo)(?![\p{L}])|மொத்தம்|எவ்வளவு""")
    private val detailAsk = Regex("""(?i)(?<![\p{L}])(details?|list|yaar|yaaru|names?|due|date|thethi|sollu|kaattu|show)(?![\p{L}])|விவரம்|யார்|தேதி|சொல்லு""")

    /**
     * A follow-up to the last list answer — the same people, re-read from the ledger now — or null when the words are
     * about something else (a named person, no list yet, a bare "details" after a person answer).
     */
    suspend fun listFollowUp(text: String): ChatReply? {
        val list = lastList ?: return null
        val lower = text.lowercase(Locale.ROOT)
        val referenced = listReference.containsMatchIn(lower)
        val plural = referenced && !Regex("""(?i)(?<![\p{L}])(avanga|avangaloda|avangalukku|ivanga)(?![\p{L}])|அவங்க""").matches(lower.trim()) &&
            Regex("""(?i)(?<![\p{L}])(ellaa|ellaarum|ellarum|ellaaroda|ellaroda|ellaarudaya|everyone|andha\s+list)(?![\p{L}])|\d+\s*(?:per|peru|perudaya|peroda|people|names?)|எல்லா|\d+\s*பேர""").containsMatchIn(lower)
        val words = lower.trim().split(Regex("""\s+""")).size
        val asks = listAsk.containsMatchIn(lower) || totalAsk.containsMatchIn(lower) || detailAsk.containsMatchIn(lower)
        val follows = when {
            plural -> asks
            referenced -> listIsLatest && asks
            else -> listIsLatest && words <= 6 && listAsk.containsMatchIn(lower)
        }
        if (!follows) return null
        // "avanga total evlo?" keeps the list; "overdue customers details sollu" asks a new one.
        if (!whenOnly.matches(lower) && newScope.containsMatchIn(lower) && !(plural && !Regex("""(?i)(?<![\p{L}])(overdue|thaandi|thandi|customers?|suppliers?|payable|innaikku|today|naalaikku|tomorrow)(?![\p{L}])|இன்னைக்கு|தாண்டி""").containsMatchIn(lower))) return null
        val ledger = books.snapshot() ?: return null
        // "Kumar details sollu" is about Kumar, not the list.
        if (KaiUnderstanding.knownPerson(text, ledger.people) != null) return null
        val day = today()
        // A whole question of its own ("yaroda due date innaikku?", "ellaa pending list pannu") is asked fresh, not read off the old list.
        val own = KaiChatUnderstanding.understand(text, day, ledger.people)
        if (!isPersonIntent(own.intent) && own.intent != ChatIntent.UNKNOWN && own.intent != ChatIntent.GENERAL_BUSINESS_QUERY) return null
        val lang = chatLanguage(text)
        // The same people, with what the ledger says now (a payment since the list shows).
        val fresh = if (list.kind == ListKind.PAID) list else list.copy(rows = list.rows.map { r ->
            ledger.parties.firstOrNull { it.id == r.party.id && it.side == r.party.side }?.let { p -> LedgerList.Row(p, p.pending, if (r.date == null) null else p.nextDue) } ?: r
        })
        lastList = fresh
        listIsLatest = true
        val totalOnly = totalAsk.containsMatchIn(lower) && !Regex("""(?i)(?<![\p{L}])(details?|list|yaar|yaaru|names?|due|date)(?![\p{L}])|விவரம்|யார்|தேதி""").containsMatchIn(lower)
        val intent = listIntent(fresh)
        if (fresh.rows.isEmpty()) return reply(intent, KaiMood.NEUTRAL, lang, when (lang) {
            KaiLang.TAMIL -> "ஓனர், அந்த list-ல யாரும் இல்ல."
            KaiLang.TANGLISH -> "Owner, andha list-la yaarum illa."
            KaiLang.ENGLISH -> "Owner, that list has no one in it."
        })
        if (totalOnly) {
            val n = fresh.rows.size
            val t = KaiFormat.rupees(fresh.total)
            return reply(intent, KaiMood.EXPLAINING, lang, when (lang) {
                KaiLang.TAMIL -> "ஓனர், அந்த $n பேர் மொத்தம் $t."
                KaiLang.TANGLISH -> "Owner, andha $n per mothama $t."
                KaiLang.ENGLISH -> "Owner, those $n come to $t in all."
            })
        }
        return reply(intent, KaiMood.EXPLAINING, lang, listText(fresh, lang, day))
    }

    private fun listIntent(list: LedgerList) = when (list.kind) {
        ListKind.DUE -> if (list.side == Direction.PAYABLE) ChatIntent.TOTAL_PAYABLE else ChatIntent.TODAY_COLLECTIONS
        ListKind.OVERDUE -> ChatIntent.OVERDUE_COLLECTIONS
        ListKind.PAID -> ChatIntent.RECEIVED_PAYMENTS
        else -> ChatIntent.PENDING_LIST
    }

    private suspend fun keepList(kind: ListKind, side: Direction, period: ChatPeriod?, parties: List<PartyFacts>): LedgerList =
        LedgerList(kind, side, period, rowsOf(parties)).also { lastList = it; listIsLatest = true }

    /**
     * One row per person with the date the ledger really has: the summary dates an entry with no due date by its bill
     * date, so the entries are read (bounded) and such a row says "Due date illa" instead of inventing one.
     */
    private suspend fun rowsOf(parties: List<PartyFacts>): List<LedgerList.Row> = parties.mapIndexed { i, p ->
        val noDue = i < 40 && p.nextDue != null && books.history(p)?.entries
            ?.filter { it.amount - it.paid > 0.005 }?.let { open -> open.isNotEmpty() && open.all { it.dueDate == null } } == true
        LedgerList.Row(p, p.pending, if (noDue) null else p.nextDue)
    }

    /** "Owner, innaikku 3 per tharanum — mothama ₹X:" then one numbered line per person: name — amount — due date / status. */
    private fun listText(list: LedgerList, lang: KaiLang, day: LocalDate): String = listTitle(list, lang, day) + "\n" + listRows(list, lang, day)

    private fun listRows(list: LedgerList, lang: KaiLang, day: LocalDate): String = list.rows.mapIndexed { i, r ->
        val amount = KaiFormat.rupees(r.amount)
        val date = r.date
        val status = when {
            list.kind == ListKind.PAID -> date?.let { KaiFormat.date(it, lang, day) } ?: ""
            date == null -> when (lang) { KaiLang.TAMIL -> "Due date இல்லை"; KaiLang.TANGLISH -> "Due date illa"; KaiLang.ENGLISH -> "no due date" }
            date.isBefore(day) -> {
                val late = java.time.temporal.ChronoUnit.DAYS.between(date, day)
                when (lang) {
                    KaiLang.TAMIL -> "${KaiFormat.date(date, lang, day)} due — $late நாள் தாண்டிடுச்சு"
                    KaiLang.TANGLISH -> "due ${KaiFormat.date(date, lang, day)} — $late naal thaandiduchu"
                    KaiLang.ENGLISH -> "due ${KaiFormat.date(date, lang, day)} — $late day${if (late == 1L) "" else "s"} overdue"
                }
            }
            else -> when (lang) {
                KaiLang.TAMIL -> "${KaiFormat.date(date, lang, day)} due"
                KaiLang.TANGLISH, KaiLang.ENGLISH -> "due ${KaiFormat.date(date, lang, day)}"
            }
        }
        "${i + 1}. ${label(r.party)} — $amount" + if (status.isEmpty()) "" else " — $status"
    }.joinToString("\n")

    private fun listTitle(list: LedgerList, lang: KaiLang, day: LocalDate): String {
        val n = list.rows.size
        val t = KaiFormat.rupees(list.total)
        val pay = list.side == Direction.PAYABLE
        val w = list.period?.let { periodLabel(it, lang, day) }
        return when (lang) {
            KaiLang.TAMIL -> "ஓனர், " + when (list.kind) {
                ListKind.DUE -> if (pay) "${w ?: ""} $n பேருக்கு கொடுக்கணும்" else "${w ?: ""} $n பேர் தரணும்"
                ListKind.OVERDUE -> if (pay) "நீங்க கொடுக்க வேண்டிய $n payment தேதி தாண்டிடுச்சு" else "$n பேரோட தேதி தாண்டிடுச்சு"
                ListKind.NO_DUE -> if (pay) "Due date இல்லாம $n பேருக்கு கொடுக்கணும்" else "Due date இல்லாம $n பேர் தரணும்"
                ListKind.PAID -> if (pay) "${w ?: ""} நீங்க $n பேருக்கு கொடுத்தீங்க" else "${w ?: ""} $n பேர் payment பண்ணாங்க"
                else -> if (pay) "நீங்க $n பேருக்கு கொடுக்கணும்" else "$n பேர் தரணும்"
            }.trim() + " — மொத்தம் $t:"
            KaiLang.TANGLISH -> "Owner, " + when (list.kind) {
                ListKind.DUE -> if (pay) "${w ?: ""} $n per-ukku kudukkanum" else "${w ?: ""} $n per tharanum"
                ListKind.OVERDUE -> if (pay) "neenga kudukka vendiya $n payment due date thaandiduchu" else "$n per-oda due date thaandiduchu"
                ListKind.NO_DUE -> if (pay) "due date illaama $n per-ukku kudukkanum" else "due date illaama $n per tharanum"
                ListKind.PAID -> if (pay) "${w ?: ""} neenga $n per-ukku pay panneenga" else "${w ?: ""} $n per payment pannanga"
                else -> if (pay) "neenga $n per-ukku kudukkanum" else "$n per tharanum"
            }.trim().replace(Regex("""\s+"""), " ") + " — mothama $t:"
            KaiLang.ENGLISH -> "Owner, " + when (list.kind) {
                ListKind.DUE -> if (pay) "${w ?: ""} you pay $n" else "${w ?: ""} $n to collect"
                ListKind.OVERDUE -> if (pay) "$n of your payments are overdue" else "$n are overdue"
                ListKind.NO_DUE -> if (pay) "$n to pay with no due date" else "$n to collect with no due date"
                ListKind.PAID -> if (pay) "${w ?: ""} you paid $n" else "${w ?: ""} $n paid you"
                else -> if (pay) "you owe $n" else "$n owe you"
            }.trim().replace(Regex("""\s+"""), " ") + " — $t in all:"
        }.replace(Regex("""\s+"""), " ").replace("Owner, ", "Owner, ")
    }

    // ------------------------------------------------------------ people

    private fun isPersonIntent(i: ChatIntent) = i in setOf(
        ChatIntent.CUSTOMER_BALANCE, ChatIntent.CUSTOMER_DUE_DATE, ChatIntent.CUSTOMER_HISTORY,
        ChatIntent.CUSTOMER_LAST_PAYMENT, ChatIntent.CUSTOMER_PAYMENTS,
    )

    private suspend fun personAnswer(query: ChatQuery, ledger: BusinessSnapshot, lang: KaiLang, day: LocalDate): ChatReply {
        val party: PartyFacts = when (val ref = query.person) {
            is PersonRef.Named -> {
                val all = ledger.find(ref.name).distinctBy { it.id }
                // "naan Kumar-ku evlo tharanum?" asks what the owner owes Kumar: only that side of the books answers it.
                val matches = query.side?.let { side -> all.filter { it.side == side } } ?: all
                // The record the owner's own words (or the conversation) point to among same-named ones.
                val hinted = hintId?.let { id -> matches.firstOrNull { it.id == id } }
                when {
                    // "Rendu Murugan-oda balance sollu": each record on its own, even when one was just talked about.
                    matches.size > 1 && (everyOne.containsMatchIn(rawText) || bothOf(ref.name).containsMatchIn(rawText)) -> return everyRecord(ref.name, matches, query.intent, lang, rawText)
                    hinted != null -> hinted
                    all.isEmpty() -> return reply(query.intent, KaiMood.CLARIFY, lang, notFound(ref.name, lang))
                    matches.isEmpty() -> {
                        // Still the person being talked about: "due eppa?" next is about them.
                        lastParty = all.first()
                        return noneOnThatSide(all.first().name, query.side!!, query.intent, lang)
                    }
                    // "Rendu Kumar-oda balance sollu": each Kumar on its own (a total only when asked).
                    matches.size > 1 && (everyOne.containsMatchIn(rawText) || bothOf(ref.name).containsMatchIn(rawText)) -> return everyRecord(ref.name, matches, query.intent, lang, rawText)
                    matches.size > 1 -> {
                        choices = matches
                        choiceQuery = query
                        return reply(query.intent, KaiMood.CLARIFY, lang, whichOne(ref.name, matches, lang))
                    }
                    else -> matches.single()
                }
            }
            // "avan" / a follow-up with no name: the person just talked about (refreshed from the ledger).
            PersonRef.Pronoun, PersonRef.None -> {
                val last = lastParty?.let { p -> ledger.parties.firstOrNull { it.id == p.id } ?: p }
                    ?: return reply(query.intent, KaiMood.CLARIFY, lang, whoDoYouMean(lang))
                val side = query.side
                if (side == null || last.side == side) last
                else ledger.find(last.name).firstOrNull { it.side == side } ?: return noneOnThatSide(last.name, side, query.intent, lang)
            }
        }
        lastParty = party
        return answerAbout(named(party), query, lang, day)
    }

    /** "Rendu Kumar-oda" / "both Kumars" / "ரெண்டு குமார்": every record with that name. */
    private fun bothOf(name: String) = Regex("""(?:rendu|both|ரெண்டு)\s+""" + Regex.escape(name), RegexOption.IGNORE_CASE)

    /** A person's name in a list: "Nagapattinam Lokesh" when the books hold more than one Lokesh. */
    private fun label(p: PartyFacts): String = hintLabels[p.id] ?: p.name

    /** "Nagapattinam Lokesh" when other records share the name — so the answer says which one it is about. */
    private fun named(p: PartyFacts): PartyFacts = hintLabels[p.id]?.takeIf { it != p.name }?.let { p.copy(name = it) } ?: p

    private suspend fun answerAbout(p: PartyFacts, query: ChatQuery, lang: KaiLang, day: LocalDate): ChatReply {
        val supplier = p.side == Direction.PAYABLE
        val intent = when (query.intent) {
            ChatIntent.CUSTOMER_BALANCE -> if (supplier) ChatIntent.SUPPLIER_BALANCE else ChatIntent.CUSTOMER_BALANCE
            ChatIntent.CUSTOMER_DUE_DATE -> if (supplier) ChatIntent.SUPPLIER_DUE_DATE else ChatIntent.CUSTOMER_DUE_DATE
            ChatIntent.CUSTOMER_HISTORY -> if (supplier) ChatIntent.SUPPLIER_HISTORY else ChatIntent.CUSTOMER_HISTORY
            else -> query.intent
        }
        return when (query.intent) {
            ChatIntent.CUSTOMER_BALANCE -> balance(p, query.amount, lang, day, intent)
            ChatIntent.CUSTOMER_DUE_DATE -> dueDate(p, query.amount, lang, day, intent)
            ChatIntent.CUSTOMER_HISTORY -> history(p, lang, day, intent)
            ChatIntent.CUSTOMER_LAST_PAYMENT -> lastPayment(p, lang, day, intent)
            ChatIntent.CUSTOMER_PAYMENTS -> payments(p, lang, day, intent)
            ChatIntent.PAYMENT_BEHAVIOUR -> habitAnswer(p, lang, day)
            else -> reply(intent, KaiMood.CLARIFY, lang, unclear(lang))
        }
    }

    /** The side the owner asked about has nothing pending — said for that side only, never the other side's amount. */
    private fun noneOnThatSide(name: String, side: Direction, intent: ChatIntent, lang: KaiLang): ChatReply =
        // "Kumar kitta naan evlo vanginen?" with no supplier Kumar: said as a missing record, not as a balance.
        if (intent != ChatIntent.CUSTOMER_BALANCE) reply(intent, KaiMood.CLARIFY, lang, when (lang) {
            KaiLang.TAMIL -> if (side == Direction.PAYABLE) "ஓனர், $name supplier-ஆ records-ல இல்ல." else "ஓனர், $name customer-ஆ records-ல இல்ல."
            KaiLang.TANGLISH -> if (side == Direction.PAYABLE) "Owner, $name supplier-aa records-la illa." else "Owner, $name customer-aa records-la illa."
            KaiLang.ENGLISH -> if (side == Direction.PAYABLE) "Owner, $name isn't in your records as a supplier." else "Owner, $name isn't in your records as a customer."
        })
        else if (side == Direction.PAYABLE) reply(intent, KaiMood.HAPPY, lang, pick3(lang,
            ta = listOf("ஓனர், $name-க்கு நீங்க கொடுக்கணும்-னு பாக்கி எதுவும் இல்ல."),
            tl = listOf("Owner, $name-ku neenga kudukkanum-nu pending amount illa."),
            en = listOf("Owner, you don't owe $name anything."),
        ))
        else reply(intent, KaiMood.NEUTRAL, lang, pick3(lang,
            ta = listOf("ஓனர், $name உங்களுக்கு தரணும்-னு பாக்கி எதுவும் இல்ல."),
            tl = listOf("Owner, $name ungalukku tharanum-nu pending amount illa."),
            en = listOf("Owner, $name doesn't owe you anything."),
        ))

    private fun balance(p: PartyFacts, said: Double?, lang: KaiLang, day: LocalDate, intent: ChatIntent): ChatReply {
        val n = p.name
        val a = KaiFormat.rupees(p.pending)
        if (p.pending <= 0.005) {
            return reply(intent, KaiMood.HAPPY, lang, pick3(lang,
                ta = listOf("$n கிட்ட இப்போ பாக்கி எதுவும் இல்ல ஓனர்."),
                tl = listOf("$n kitta ippo pending edhuvum illa owner.", "Owner, $n account clear-ah irukku. Pending illa."),
                en = listOf("Nothing is pending with $n, Owner."),
            ))
        }
        // An amount question gets the amount; the due date / overdue is for "eppa?" (never "account clear" for a passed date).
        val base = if (p.side == Direction.RECEIVABLE) pick3(lang,
            ta = listOf("$n உங்களுக்கு $a தரணும் ஓனர்.", "ஓனர், $n பாக்கி $a இருக்கு."),
            tl = listOf("$n ungalukku $a tharanum owner.", "Owner, $n balance $a pending irukku.", "$n kitta $a receive panna vendiyirukku owner."),
            en = listOf("$n owes you $a, Owner.", "Owner, $n has $a pending."),
        ) else pick3(lang,
            ta = listOf("நீங்க $n-க்கு $a கொடுக்கணும் ஓனர்."),
            tl = listOf("Neenga $n-ku $a kudukkanum owner.", "Owner, $n-ku $a pending payment irukku."),
            en = listOf("You owe $n $a, Owner."),
        )
        return reply(intent, if (p.side == Direction.RECEIVABLE) KaiMood.CREDIT else KaiMood.DEBIT, lang,
            base + mismatch(said, p.pending, lang))
    }

    private suspend fun dueDate(p: PartyFacts, said: Double?, lang: KaiLang, day: LocalDate, intent: ChatIntent): ChatReply {
        val n = p.name
        if (p.pending <= 0.005) return balance(p, said, lang, day, intent)
        // Every pending entry with its own due date, from the records; else the next due date.
        val entries = books.history(p)?.entries
            ?.filter { it.amount - it.paid > 0.005 && it.dueDate != null }
            ?.sortedBy { it.dueDate }
            .orEmpty()
        if (entries.isEmpty() && p.nextDue == null) {
            val a = KaiFormat.rupees(p.pending)
            return reply(intent, KaiMood.CONCERNED, lang, pick3(lang,
                ta = listOf("$n $a ${if (p.side == Direction.RECEIVABLE) "தரணும்" else "கொடுக்கணும்"}, ஆனா due date பதிவுல இல்ல ஓனர்."),
                tl = listOf("$n $a ${if (p.side == Direction.RECEIVABLE) "tharanum" else "kudukkanum"}, aana due date record-la illa owner."),
                en = listOf("$n has $a pending, but no due date is recorded, Owner."),
            ))
        }
        val lines = if (entries.isNotEmpty()) {
            entries.take(3).map { e -> KaiFormat.rupees(e.amount - e.paid) to e.dueDate!! }
        } else {
            listOf(KaiFormat.rupees(p.pending) to p.nextDue!!)
        }
        val verbTl = if (p.side == Direction.RECEIVABLE) "tharanum" else "kudukkanum"
        val verbTa = if (p.side == Direction.RECEIVABLE) "தரணும்" else "கொடுக்கணும்"
        val overdue = lines.any { it.second.isBefore(day) }
        val text = when (lang) {
            KaiLang.TAMIL -> "$n " + lines.joinToString(", ") { (a, d) -> "$a ${KaiFormat.date(d, lang, day)}" } + " $verbTa ஓனர்."
            KaiLang.TANGLISH -> pick(listOf(
                "$n " + lines.joinToString(", ") { (a, d) -> "$a ${KaiFormat.date(d, lang, day)}" } + " $verbTl owner.",
                "Owner, $n " + lines.joinToString(", ") { (a, d) -> "${KaiFormat.date(d, lang, day)} $a" } + " $verbTl.",
            ))
            KaiLang.ENGLISH -> "$n: " + lines.joinToString(", ") { (a, d) -> "$a due ${KaiFormat.date(d, lang, day)}" } + ", Owner."
        } + if (overdue) when (lang) {
            KaiLang.TAMIL -> " தேதி தாண்டிடுச்சு — follow up பண்ணுங்க."
            KaiLang.TANGLISH -> " Date thaandiduchu — konjam follow up pannunga."
            KaiLang.ENGLISH -> " It's overdue — please follow up."
        } else ""
        return reply(intent, if (overdue) KaiMood.SERIOUS else KaiMood.REMINDER, lang, text + mismatch(said, p.pending, lang))
    }

    private suspend fun history(p: PartyFacts, lang: KaiLang, day: LocalDate, intent: ChatIntent): ChatReply {
        val h = books.history(p) ?: return reply(intent, KaiMood.ERROR, lang, noRecords(lang))
        if (h.entries.isEmpty()) return reply(intent, KaiMood.NEUTRAL, lang, missing(lang))
        val n = p.name
        val total = KaiFormat.rupees(h.totalBilled)
        val paid = KaiFormat.rupees(h.totalPaid)
        val pending = KaiFormat.rupees(p.pending)
        val last = h.lastPayment
        if (p.side == Direction.PAYABLE) {
            // A supplier: what the owner bought from them, paid them, and still owes.
            return reply(intent, KaiMood.EXPLAINING, lang, when (lang) {
                KaiLang.TAMIL -> "$n கிட்ட ${h.entries.size} entry, மொத்தம் $total வாங்கியிருக்கீங்க. நீங்க கொடுத்தது $paid, இன்னும் $pending கொடுக்கணும்."
                KaiLang.TANGLISH -> "$n kitta ${h.entries.size} entry, mothama $total vaangirukkeenga. Neenga kuduthadhu $paid, innum $pending kudukkanum."
                KaiLang.ENGLISH -> "$n: ${h.entries.size} entries, $total bought in all. You paid $paid; $pending still to pay."
            } + historyLines(h, lang, day))
        }
        val text = when (lang) {
            KaiLang.TAMIL -> "$n: ${h.entries.size} entry, மொத்தம் $total. வந்தது $paid, பாக்கி $pending." +
                (last?.let { " கடைசி பேமெண்ட் ${KaiFormat.rupees(it.amount)}, ${KaiFormat.date(it.date!!, lang, day)}." } ?: "")
            KaiLang.TANGLISH -> "$n: ${h.entries.size} entry, mothama $total. Vandhadhu $paid, pending $pending." +
                (last?.let { " Last payment ${KaiFormat.rupees(it.amount)}, ${KaiFormat.date(it.date!!, lang, day)}." } ?: "")
            KaiLang.ENGLISH -> "$n: ${h.entries.size} entries totalling $total. Paid $paid, pending $pending." +
                (last?.let { " Last payment ${KaiFormat.rupees(it.amount)} on ${KaiFormat.date(it.date!!, lang, day)}." } ?: "")
        }
        return reply(intent, KaiMood.EXPLAINING, lang, text + historyLines(h, lang, day))
    }

    /** Each entry (date, amount, paid) and each payment, as the ledger has them — oldest first. */
    private fun historyLines(h: PartyHistory, lang: KaiLang, day: LocalDate): String {
        val entries = h.entries.sortedBy { it.createdAt ?: LocalDate.MIN }
        if (entries.isEmpty()) return ""
        fun d(x: LocalDate?) = x?.let { KaiFormat.date(it, lang, day) } ?: when (lang) { KaiLang.TAMIL -> "தேதி இல்லை"; KaiLang.TANGLISH -> "date illa"; KaiLang.ENGLISH -> "no date" }
        val lines = entries.mapIndexed { i, e ->
            val paidPart = when (lang) {
                KaiLang.TAMIL -> "வந்தது ${KaiFormat.rupees(e.paid)}"
                KaiLang.TANGLISH -> "paid ${KaiFormat.rupees(e.paid)}"
                KaiLang.ENGLISH -> "paid ${KaiFormat.rupees(e.paid)}"
            }
            "${i + 1}. ${d(e.createdAt)} — ${KaiFormat.rupees(e.amount)} ($paidPart)"
        }
        val payments = entries.flatMap { it.payments }.filter { it.amount > 0.005 }.sortedBy { it.date ?: LocalDate.MIN }
        val payLine = if (payments.isEmpty()) "" else "\n" + when (lang) {
            KaiLang.TAMIL -> "பேமெண்ட்: "
            KaiLang.TANGLISH -> "Payments: "
            KaiLang.ENGLISH -> "Payments: "
        } + payments.joinToString(", ") { "${d(it.date)} ${KaiFormat.rupees(it.amount)}" }
        return "\n" + lines.joinToString("\n") + payLine
    }

    private suspend fun lastPayment(p: PartyFacts, lang: KaiLang, day: LocalDate, intent: ChatIntent): ChatReply {
        val h = books.history(p) ?: return reply(intent, KaiMood.ERROR, lang, noRecords(lang))
        val last = h.lastPayment
        val n = p.name
        if (last == null) {
            return reply(intent, KaiMood.CONCERNED, lang, pick3(lang,
                ta = listOf("$n இதுவரை எந்த பேமெண்ட்டும் பதிவுல இல்ல ஓனர்."),
                tl = listOf("$n idhuvarai endha payment-um record-la illa owner."),
                en = listOf("No payment from $n is recorded yet, Owner."),
            ))
        }
        val a = KaiFormat.rupees(last.amount)
        val d = KaiFormat.date(last.date!!, lang, day)
        return reply(intent, KaiMood.EXPLAINING, lang, pick3(lang,
            ta = listOf("$n கடைசியா $d அன்னைக்கு $a கொடுத்தாங்க ஓனர்."),
            tl = listOf("$n last payment $d, $a owner.", "Owner, $n kadaisiya $d $a kuduthaanga."),
            en = listOf("$n's last payment was $a on $d, Owner."),
        ))
    }

    private suspend fun payments(p: PartyFacts, lang: KaiLang, day: LocalDate, intent: ChatIntent): ChatReply {
        val h = books.history(p) ?: return reply(intent, KaiMood.ERROR, lang, noRecords(lang))
        val n = p.name
        if (p.side == Direction.PAYABLE) {
            // A supplier: what the owner has paid them, never "they paid you".
            val paidOut = KaiFormat.rupees(h.totalPaid)
            val left = KaiFormat.rupees(p.pending)
            return reply(intent, KaiMood.DEBIT, lang, if (h.totalPaid <= 0.005) when (lang) {
                KaiLang.TAMIL -> "ஓனர், நீங்க $n-க்கு இன்னும் எதுவும் கொடுக்கல. பாக்கி $left."
                KaiLang.TANGLISH -> "Owner, neenga $n-ku innum edhuvum kudukkala. Pending $left."
                KaiLang.ENGLISH -> "Owner, you haven't paid $n anything yet. $left pending."
            } else when (lang) {
                KaiLang.TAMIL -> "ஓனர், நீங்க $n-க்கு $paidOut கொடுத்திருக்கீங்க. இன்னும் $left கொடுக்கணும்."
                KaiLang.TANGLISH -> "Owner, neenga $n-ku $paidOut kuduthirukkeenga. Innum $left kudukkanum."
                KaiLang.ENGLISH -> "Owner, you've paid $n $paidOut. $left still to pay."
            })
        }
        if (h.totalPaid <= 0.005) {
            return reply(intent, KaiMood.CONCERNED, lang, pick3(lang,
                ta = listOf("இல்ல ஓனர், $n இதுவரை எதுவும் கொடுக்கல. பாக்கி ${KaiFormat.rupees(p.pending)}."),
                tl = listOf("Illa owner, $n idhuvarai edhuvum kudukkala. Pending ${KaiFormat.rupees(p.pending)}."),
                en = listOf("No, Owner — $n hasn't paid anything yet. ${KaiFormat.rupees(p.pending)} pending."),
            ))
        }
        val paid = KaiFormat.rupees(h.totalPaid)
        val last = h.lastPayment
        val lastTl = last?.let { " Last-a ${KaiFormat.rupees(it.amount)}, ${KaiFormat.date(it.date!!, lang, day)}." }.orEmpty()
        val lastTa = last?.let { " கடைசியா ${KaiFormat.rupees(it.amount)}, ${KaiFormat.date(it.date!!, lang, day)}." }.orEmpty()
        val lastEn = last?.let { " Last: ${KaiFormat.rupees(it.amount)} on ${KaiFormat.date(it.date!!, lang, day)}." }.orEmpty()
        val pending = KaiFormat.rupees(p.pending)
        return reply(intent, KaiMood.HAPPY, lang, pick3(lang,
            ta = listOf("ஆமா ஓனர், $n $paid ஏற்கனவே கொடுத்திருக்காங்க.$lastTa இன்னும் $pending பாக்கி."),
            tl = listOf("Aamaa owner, $n $paid already kuduthirukkaanga.$lastTl Innum $pending pending.", "Owner, $n ippo varai $paid kuduthirukkaanga.$lastTl Balance $pending."),
            en = listOf("Yes, Owner — $n has paid $paid so far.$lastEn $pending still pending."),
        ))
    }

    // ------------------------------------------------- payment habit (history)

    /**
     * How a person paid against their due dates, counted from the ledger's own entries — never guessed:
     * a settled entry with a due date is on time when its last payment came on or before that date, late otherwise;
     * an open entry whose due date has passed is late now. Entries without a due date say nothing about it.
     */
    private data class Habit(val party: PartyFacts, val onTime: Int, val lateDays: List<Long>, val overdueDays: Long?) {
        val late: Int get() = lateDays.size
        val counted: Int get() = onTime + late
        val avgLate: Long get() = if (lateDays.isEmpty()) 0 else Math.round(lateDays.average())
        val isLate: Boolean get() = late > 0 || overdueDays != null
        val known: Boolean get() = counted > 0 || overdueDays != null
    }

    private suspend fun habitOf(p: PartyFacts, day: LocalDate): Habit? {
        val h = books.history(p) ?: return null
        var onTime = 0
        val late = mutableListOf<Long>()
        var overdue: Long? = null
        for (e in h.entries) {
            val due = e.dueDate ?: continue
            if (e.amount - e.paid > 0.005) {
                if (due.isBefore(day)) overdue = maxOf(overdue ?: 0L, java.time.temporal.ChronoUnit.DAYS.between(due, day))
                continue
            }
            val settled = e.payments.mapNotNull { it.date }.maxOrNull() ?: continue
            val days = java.time.temporal.ChronoUnit.DAYS.between(due, settled)
            if (days <= 0) onTime++ else late += days
        }
        return Habit(p, onTime, late, overdue)
    }

    private fun times(n: Int, lang: KaiLang) = when (lang) {
        KaiLang.TAMIL -> "$n தடவை"; KaiLang.TANGLISH -> "$n thadava"; KaiLang.ENGLISH -> if (n == 1) "once" else "$n times"
    }

    private fun days(n: Long, lang: KaiLang) = when (lang) {
        KaiLang.TAMIL -> "$n நாள்"; KaiLang.TANGLISH -> "$n naal"; KaiLang.ENGLISH -> if (n == 1L) "1 day" else "$n days"
    }

    /** "2 thadava-la 2 thadava late (10, 6 naal; sarasari 8 naal)" — one short line of what the books show. */
    private fun habitLine(h: Habit, lang: KaiLang): String {
        val parts = mutableListOf<String>()
        if (h.late > 0) parts += when (lang) {
            KaiLang.TAMIL -> "${h.counted}-ல ${times(h.late, lang)} late (" + (if (h.late == 1) days(h.avgLate, lang) else "சராசரி ${days(h.avgLate, lang)}") + ")"
            KaiLang.TANGLISH -> "${h.counted}-la ${times(h.late, lang)} late (" + (if (h.late == 1) days(h.avgLate, lang) else "sarasari ${days(h.avgLate, lang)}") + ")"
            KaiLang.ENGLISH -> "late ${h.late} of ${h.counted} (" + (if (h.late == 1) days(h.avgLate, lang) else "${days(h.avgLate, lang)} on average") + ")"
        }
        if (h.onTime > 0 && h.late == 0) parts += when (lang) {
            KaiLang.TAMIL -> "${times(h.onTime, lang)}-உம் சரியான தேதியில"
            KaiLang.TANGLISH -> "${times(h.onTime, lang)}-um correct date-la"
            KaiLang.ENGLISH -> "on time ${times(h.onTime, lang)}"
        }
        h.overdueDays?.let { od -> parts += when (lang) {
            KaiLang.TAMIL -> "இப்போ ${days(od, lang)} தாண்டி ${KaiFormat.rupees(h.party.pending)} pending"
            KaiLang.TANGLISH -> "ippo ${days(od, lang)} thaandi ${KaiFormat.rupees(h.party.pending)} pending"
            KaiLang.ENGLISH -> "${KaiFormat.rupees(h.party.pending)} now ${days(od, lang)} overdue"
        } }
        return parts.joinToString("; ")
    }

    /** One person: on time, sometimes late, or usually late — with the days, from their entries. */
    private suspend fun habitAnswer(p: PartyFacts, lang: KaiLang, day: LocalDate): ChatReply {
        val intent = ChatIntent.PAYMENT_BEHAVIOUR
        val h = habitOf(p, day) ?: return reply(intent, KaiMood.ERROR, lang, noRecords(lang))
        val n = p.name
        val now = if (p.pending > 0.005 && p.nextDue != null && h.overdueDays == null) when (lang) {
            KaiLang.TAMIL -> " இப்போ ${KaiFormat.rupees(p.pending)} — ${KaiFormat.date(p.nextDue, lang, day)} due."
            KaiLang.TANGLISH -> " Ippo ${KaiFormat.rupees(p.pending)} — due ${KaiFormat.date(p.nextDue, lang, day)}."
            KaiLang.ENGLISH -> " Now ${KaiFormat.rupees(p.pending)} is due ${KaiFormat.date(p.nextDue, lang, day)}."
        } else ""
        val overdueNow = h.overdueDays?.let { od -> when (lang) {
            KaiLang.TAMIL -> " இப்போவும் ${KaiFormat.rupees(p.pending)} due date தாண்டி ${days(od, lang)} ஆச்சு."
            KaiLang.TANGLISH -> " Ippovum ${KaiFormat.rupees(p.pending)} due date thaandi ${days(od, lang)} aachu."
            KaiLang.ENGLISH -> " Right now ${KaiFormat.rupees(p.pending)} is ${days(od, lang)} past its due date."
        } }.orEmpty()
        val lateList = h.lateDays.joinToString(", ")
        val text = when {
            !h.known -> when (lang) {
                KaiLang.TAMIL -> "ஓனர், $n-ஓட due date வெச்சு முடிஞ்ச payment எதுவும் records-ல இல்ல — அதனால correct-ஆ தருவாங்களா-னு இப்போ சொல்ல முடியாது."
                KaiLang.TANGLISH -> "Owner, $n-oda due date vechu mudinja payment edhuvum records-la illa — adhanaala correct-aa tharuvaangala-nu ippo solla mudiyadhu."
                KaiLang.ENGLISH -> "Owner, there's no settled entry with a due date for $n in your records yet, so I can't say whether they pay on time."
            } + now
            !h.isLate -> when (lang) {
                KaiLang.TAMIL -> "ஓனர், $n சரியான தேதியில தான் தருவாங்க: ${times(h.onTime, lang)}-உம் due date-க்குள்ள கொடுத்திருக்காங்க."
                KaiLang.TANGLISH -> "Owner, $n correct date-la dhaan tharuvaanga: ${times(h.onTime, lang)}-um due date-kulla kuduthirukkaanga."
                KaiLang.ENGLISH -> "Owner, $n pays on time: all ${h.onTime} by the due date."
            } + now
            h.onTime == 0 -> when (lang) {
                KaiLang.TAMIL -> "ஓனர், $n பெரும்பாலும் late-ஆ தான் தருவாங்க" + when {
                    h.late == 1 -> ": 1 தடவை due date தாண்டி கொடுத்தாங்க (${days(h.lateDays.single(), lang)} late)."
                    h.late > 1 -> ": ${times(h.late, lang)}-உம் due date தாண்டி கொடுத்தாங்க ($lateList நாள் late; சராசரி ${days(h.avgLate, lang)})."
                    else -> "."
                }
                KaiLang.TANGLISH -> "Owner, $n usually late-aa dhaan tharuvaanga" + when {
                    h.late == 1 -> ": 1 thadava due date thaandi kuduthaanga (${days(h.lateDays.single(), lang)} late)."
                    h.late > 1 -> ": ${times(h.late, lang)}-um due date thaandi kuduthaanga ($lateList naal late; sarasari ${days(h.avgLate, lang)})."
                    else -> "."
                }
                KaiLang.ENGLISH -> "Owner, $n usually pays late" + when {
                    h.late == 1 -> ": paid once after the due date (${days(h.lateDays.single(), lang)} late)."
                    h.late > 1 -> ": all ${h.late} after the due date ($lateList days late; ${days(h.avgLate, lang)} on average)."
                    else -> "."
                }
            } + overdueNow + now
            else -> when (lang) {
                KaiLang.TAMIL -> "ஓனர், $n சில தடவை late: ${h.counted}-ல ${times(h.late, lang)} due date தாண்டி (சராசரி ${days(h.avgLate, lang)}), ${times(h.onTime, lang)} சரியான தேதியில."
                KaiLang.TANGLISH -> "Owner, $n sila thadava late: ${h.counted}-la ${times(h.late, lang)} due date thaandi (sarasari ${days(h.avgLate, lang)}), ${times(h.onTime, lang)} correct date-la."
                KaiLang.ENGLISH -> "Owner, $n is sometimes late: ${h.late} of ${h.counted} after the due date (${days(h.avgLate, lang)} on average), ${h.onTime} on time."
            } + overdueNow + now
        }
        return reply(intent, if (h.isLate) KaiMood.CONCERNED else if (h.known) KaiMood.HAPPY else KaiMood.NEUTRAL, lang, text)
    }

    /** "avanga…" (the last list), or "yaarlam late-aa pay pannuvaanga?" (everyone on that side): who pays late, who on time. */
    private suspend fun groupHabits(q: ChatQuery, s: BusinessSnapshot, lang: KaiLang, day: LocalDate, listWasLatest: Boolean): ChatReply {
        val intent = ChatIntent.PAYMENT_BEHAVIOUR
        val fromList = q.person == PersonRef.Pronoun && lastList != null
        if (q.person == PersonRef.Pronoun && lastList == null && lastParty != null) {
            val p = s.parties.firstOrNull { it.id == lastParty!!.id } ?: lastParty!!
            return habitAnswer(named(p), lang, day)
        }
        if (q.person == PersonRef.Pronoun && !fromList) return reply(intent, KaiMood.CLARIFY, lang, whoDoYouMean(lang))
        val side = q.side ?: Direction.RECEIVABLE
        val people = if (fromList) lastList!!.rows.map { r -> s.parties.firstOrNull { it.id == r.party.id && it.side == r.party.side } ?: r.party }
            else s.parties.filter { it.side == side }.sortedByDescending { it.pending }.take(80)
        if (fromList) listIsLatest = listWasLatest
        val habits = people.mapNotNull { habitOf(it, day) }.filter { it.known }
        val late = habits.filter { it.isLate }.sortedWith(compareByDescending<Habit> { it.late.toDouble() / maxOf(1, it.counted) }
            .thenByDescending { it.overdueDays ?: 0L }.thenByDescending { it.avgLate }.thenBy { it.party.name })
        val onTime = habits.filter { !it.isLate }
        val unknown = people.size - habits.size
        if (habits.isEmpty()) return reply(intent, KaiMood.NEUTRAL, lang, when (lang) {
            KaiLang.TAMIL -> "ஓனர், due date வெச்சு முடிஞ்ச payment history இன்னும் records-ல இல்ல — யார் late-னு இப்போ சொல்ல முடியாது."
            KaiLang.TANGLISH -> "Owner, due date vechu mudinja payment history innum records-la illa — yaar late-nu ippo solla mudiyadhu."
            KaiLang.ENGLISH -> "Owner, your records have no settled entries with due dates yet, so I can't tell who pays late."
        })
        val sb = StringBuilder()
        sb.append(when (lang) {
            KaiLang.TAMIL -> if (fromList) "ஓனர், அந்த ${people.size} பேரோட history:" else "ஓனர், records-ல history பார்த்தா:"
            KaiLang.TANGLISH -> if (fromList) "Owner, andha ${people.size} per-oda history:" else "Owner, records-la history paartha:"
            KaiLang.ENGLISH -> if (fromList) "Owner, the history of those ${people.size}:" else "Owner, from the history in your records:"
        })
        if (late.isNotEmpty()) {
            sb.append("\n").append(when (lang) {
                KaiLang.TAMIL -> "Late-ஆ தருவாங்க (${late.size}):"; KaiLang.TANGLISH -> "Late-aa tharuvaanga (${late.size}):"; KaiLang.ENGLISH -> "Pay late (${late.size}):"
            })
            late.forEachIndexed { i, h -> sb.append("\n${i + 1}. ${label(h.party)} — ${habitLine(h, lang)}") }
        } else sb.append(" ").append(when (lang) {
            KaiLang.TAMIL -> "யாரும் late இல்ல."; KaiLang.TANGLISH -> "yaarum late illa."; KaiLang.ENGLISH -> "no one has paid late."
        })
        if (onTime.isNotEmpty()) sb.append("\n").append(when (lang) {
            KaiLang.TAMIL -> "சரியான தேதியில தருவாங்க: "; KaiLang.TANGLISH -> "Correct date-la tharuvaanga: "; KaiLang.ENGLISH -> "Pay on time: "
        }).append(onTime.joinToString(", ") { label(it.party) }).append(".")
        if (unknown > 0) sb.append("\n").append(when (lang) {
            KaiLang.TAMIL -> "$unknown பேருக்கு இன்னும் history இல்ல."; KaiLang.TANGLISH -> "$unknown per-ukku innum history illa."; KaiLang.ENGLISH -> "$unknown have no history yet."
        })
        return reply(intent, if (late.isNotEmpty()) KaiMood.CONCERNED else KaiMood.HAPPY, lang, sb.toString())
    }

    // ---------------------------------------------------------- business

    private suspend fun businessAnswer(q: ChatQuery, s: BusinessSnapshot, lang: KaiLang, day: LocalDate): ChatReply = when (q.intent) {
        ChatIntent.TODAY_COLLECTIONS -> {
            val side = q.side ?: Direction.RECEIVABLE
            val todayList = s.pendingOn(side).filter { it.nextDue == day }
            val todayPeriod = ChatPeriod(day, day, ChatPeriod.Kind.TODAY)
            if (q.withOverdue) {
                // Both sets, clearly apart: today's first, then the ones whose date has passed.
                val today = LedgerList(ListKind.DUE, side, todayPeriod, rowsOf(todayList))
                val od = keepList(ListKind.OVERDUE, side, null, s.overdue(day).filter { it.side == side }
                    .sortedWith(compareBy<PartyFacts, LocalDate?>(nullsLast()) { it.nextDue }.thenByDescending { it.pending }.thenBy { it.name }))
                val todayText = if (today.rows.isEmpty()) when (lang) {
                    KaiLang.TAMIL -> "ஓனர், இன்னைக்கு due எதுவும் இல்ல."
                    KaiLang.TANGLISH -> "Owner, innaikku due edhuvum illa."
                    KaiLang.ENGLISH -> "Owner, nothing is due today."
                } else listText(today, lang, day)
                val odText = if (od.rows.isEmpty()) when (lang) {
                    KaiLang.TAMIL -> "தேதி தாண்டினது எதுவும் இல்ல."
                    KaiLang.TANGLISH -> "Due date thaandinadhu edhuvum illa."
                    KaiLang.ENGLISH -> "Nothing is overdue."
                } else listText(od, lang, day).removePrefix("Owner, ").removePrefix("ஓனர், ").replaceFirstChar { it.titlecase(Locale.ROOT) }
                return reply(q.intent, if (od.rows.isEmpty()) KaiMood.CREDIT else KaiMood.CONCERNED, lang, todayText + "\n\n" + odText)
            }
            val kept = keepList(ListKind.DUE, side, todayPeriod, todayList)
            if (q.fullList && todayList.isNotEmpty()) reply(q.intent, KaiMood.CREDIT, lang, listText(kept, lang, day))
            else collections(todayList, ChatIntent.TODAY_COLLECTIONS, lang, day, when (lang) {
                KaiLang.TAMIL -> "இன்னைக்கு"; KaiLang.TANGLISH -> "Innaikku"; KaiLang.ENGLISH -> "Today"
            }, overdue = s.overdue(day).filter { it.side == Direction.RECEIVABLE })
        }
        ChatIntent.UPCOMING_COLLECTIONS -> {
            val period = q.period
            val list = s.owesMe().filter { p ->
                val due = p.nextDue
                if (period == null) true else due != null && due in period
            }
            val label = period?.let { periodLabel(it, lang, day) } ?: when (lang) {
                KaiLang.TAMIL -> "அடுத்து"; KaiLang.TANGLISH -> "Next"; KaiLang.ENGLISH -> "Next"
            }
            val kept = keepList(if (period == null) ListKind.PENDING else ListKind.DUE, Direction.RECEIVABLE, period, list)
            if ((q.fullList || period == null) && list.isNotEmpty()) reply(q.intent, KaiMood.CREDIT, lang, listText(kept, lang, day))
            else collections(list, ChatIntent.UPCOMING_COLLECTIONS, lang, day, label, overdue = emptyList())
        }
        ChatIntent.OVERDUE_COLLECTIONS -> {
            val side = q.side ?: Direction.RECEIVABLE
            val list = s.overdue(day).filter { it.side == side }
                .sortedWith(compareBy<PartyFacts, LocalDate?>(nullsLast()) { it.nextDue }.thenByDescending { it.pending }.thenBy { it.name })
            val kept = keepList(ListKind.OVERDUE, side, null, list)
            if (side == Direction.PAYABLE && list.isEmpty()) reply(q.intent, KaiMood.HAPPY, lang, when (lang) {
                KaiLang.TAMIL -> "நீங்க கொடுக்க வேண்டியதுல தேதி தாண்டினது எதுவும் இல்ல ஓனர்."
                KaiLang.TANGLISH -> "Neenga kudukka vendiyadhula due date thaandinadhu edhuvum illa owner."
                KaiLang.ENGLISH -> "None of your payments are overdue, Owner."
            })
            else if (side == Direction.PAYABLE || q.fullList && list.isNotEmpty()) reply(q.intent, KaiMood.SERIOUS, lang, listText(kept, lang, day))
            else if (list.isEmpty()) reply(q.intent, KaiMood.HAPPY, lang, pick3(lang,
                ta = listOf("தேதி தாண்டின வசூல் எதுவும் இல்ல ஓனர்."),
                tl = listOf("Date thaandina collection edhuvum illa owner.", "Owner, overdue edhuvum illa. Ellam on time."),
                en = listOf("No overdue collections, Owner."),
            )) else reply(q.intent, KaiMood.SERIOUS, lang, when (lang) {
                KaiLang.TAMIL -> "ஓனர், ${list.size} வசூல் தேதி தாண்டிடுச்சு: " + list.take(3).joinToString(", ") { "${it.name} ${KaiFormat.rupees(it.pending)}" } + ". Follow up பண்ணுங்க."
                KaiLang.TANGLISH -> "Owner, ${list.size} payment date thaandiduchu: " + list.take(3).joinToString(", ") { "${it.name} ${KaiFormat.rupees(it.pending)}" } + ". Konjam follow up pannunga."
                KaiLang.ENGLISH -> "Owner, ${list.size} collection(s) are overdue: " + list.take(3).joinToString(", ") { "${it.name} ${KaiFormat.rupees(it.pending)}" } + ". Please follow up."
            })
        }
        // "innaikku collect panna vendiya total evlo?": the total for that period's due dates.
        ChatIntent.TOTAL_RECEIVABLE -> if (q.period != null) {
            val period = q.period
            val list = s.owesMe().filter { p -> p.nextDue?.let { it in period } == true }
            keepList(ListKind.DUE, Direction.RECEIVABLE, period, list)
            collections(list, ChatIntent.TOTAL_RECEIVABLE, lang, day, periodLabel(period, lang, day), overdue = emptyList())
        } else {
            val r = KaiFormat.rupees(s.totalReceivable())
            val p = KaiFormat.rupees(s.totalPayable())
            reply(q.intent, KaiMood.EXPLAINING, lang, pick3(lang,
                ta = listOf("ஓனர், நீங்க வாங்க வேண்டியது மொத்தம் $r. கொடுக்க வேண்டியது $p."),
                tl = listOf("Owner, neenga vaanga vendiyadhu mothama $r. Kudukka vendiyadhu $p.", "Mothama $r ungalukku varanum owner. Neenga $p kudukkanum."),
                en = listOf("Owner, you have $r to collect in all, and $p to pay."),
            ))
        }
        // "Innaikku yaarukku payment pannanum?": only what falls due in that period, from the records.
        ChatIntent.TOTAL_PAYABLE -> if (q.period != null) {
            val period = q.period
            val list = s.iOwe().filter { p -> p.nextDue?.let { it in period } == true }
            val label = periodLabel(period, lang, day)
            val kept = keepList(ListKind.DUE, Direction.PAYABLE, period, list)
            if (q.fullList && list.isNotEmpty()) reply(q.intent, KaiMood.DEBIT, lang, listText(kept, lang, day))
            else if (list.isEmpty()) reply(q.intent, KaiMood.NEUTRAL, lang, when (lang) {
                KaiLang.TAMIL -> "$label யாருக்கும் கொடுக்க வேண்டிய payment record இல்ல ஓனர்."
                KaiLang.TANGLISH -> "$label yaarukkum kudukka vendiya payment record illa owner."
                KaiLang.ENGLISH -> "$label: no payments due to anyone in your records, Owner."
            }) else {
                val total = KaiFormat.rupees(list.sumOf { it.pending })
                val top = list.take(3).joinToString(", ") { "${it.name} ${KaiFormat.rupees(it.pending)}" }
                reply(q.intent, KaiMood.DEBIT, lang, when (lang) {
                    KaiLang.TAMIL -> "$label ${list.size} பேருக்கு கொடுக்கணும் ஓனர் (மொத்தம் $total): $top."
                    KaiLang.TANGLISH -> "$label ${list.size} per-ukku kudukkanum owner (mothama $total): $top."
                    KaiLang.ENGLISH -> "$label, you have ${list.size} to pay ($total), Owner: $top."
                })
            }
        } else {
            val list = s.iOwe()
            val kept = keepList(ListKind.PENDING, Direction.PAYABLE, null, list)
            if (q.fullList && list.isNotEmpty()) reply(q.intent, KaiMood.DEBIT, lang, listText(kept, lang, day))
            else if (list.isEmpty()) reply(q.intent, KaiMood.HAPPY, lang, pick3(lang,
                ta = listOf("நீங்க யாருக்கும் கொடுக்க வேண்டியது இல்ல ஓனர்."),
                tl = listOf("Neenga yaarukkum kudukka vendiyadhu illa owner."),
                en = listOf("You don't owe anyone right now, Owner."),
            )) else reply(q.intent, KaiMood.DEBIT, lang, when (lang) {
                KaiLang.TAMIL -> "ஓனர், மொத்தம் ${KaiFormat.rupees(s.totalPayable())} கொடுக்கணும்: " + list.take(3).joinToString(", ") { "${it.name} ${KaiFormat.rupees(it.pending)}${dueSuffix(it, lang, day)}" } + "."
                KaiLang.TANGLISH -> "Owner, mothama ${KaiFormat.rupees(s.totalPayable())} kudukkanum: " + list.take(3).joinToString(", ") { "${it.name} ${KaiFormat.rupees(it.pending)}${dueSuffix(it, lang, day)}" } + "."
                KaiLang.ENGLISH -> "Owner, you owe ${KaiFormat.rupees(s.totalPayable())} in all: " + list.take(3).joinToString(", ") { "${it.name} ${KaiFormat.rupees(it.pending)}${dueSuffix(it, lang, day)}" } + "."
            })
        }
        ChatIntent.TODAY_TRANSACTIONS -> {
            val c = books.cashBook(day, day)
            if (c == null || c.entries == 0) reply(q.intent, KaiMood.NEUTRAL, lang, pick3(lang,
                ta = listOf("இன்னைக்கு Daily Cash Note-ல எதுவும் பதிவு ஆகல ஓனர்."),
                tl = listOf("Innaikku Daily Cash Note-la edhuvum record aagala owner."),
                en = listOf("Nothing is recorded in today's Daily Cash Note yet, Owner."),
            )) else reply(q.intent, KaiMood.EXPLAINING, lang, pick3(lang,
                ta = listOf("இன்னைக்கு ${c.entries} entry: வந்தது ${KaiFormat.rupees(c.totalIn)}, போனது ${KaiFormat.rupees(c.totalOut)} ஓனர்."),
                tl = listOf("Innaikku ${c.entries} entry: vandhadhu ${KaiFormat.rupees(c.totalIn)}, ponadhu ${KaiFormat.rupees(c.totalOut)} owner."),
                en = listOf("Today: ${c.entries} entries — ${KaiFormat.rupees(c.totalIn)} in, ${KaiFormat.rupees(c.totalOut)} out, Owner."),
            ))
        }
        ChatIntent.MONTHLY_SALES, ChatIntent.EXPENSE_SUMMARY -> {
            val period = q.period ?: monthOf(day)
            val c = books.cashBook(period.from, minOf(period.to, day))
            val label = periodLabel(period, lang, day)
            if (c == null || c.entries == 0) reply(q.intent, KaiMood.NEUTRAL, lang, missing(lang))
            else if (q.intent == ChatIntent.MONTHLY_SALES) reply(q.intent, KaiMood.HAPPY, lang, pick3(lang,
                ta = listOf("$label வந்த பணம் (cash + UPI) ${KaiFormat.rupees(c.totalIn)} ஓனர்."),
                tl = listOf("$label vandha panam (cash + UPI) ${KaiFormat.rupees(c.totalIn)} owner.", "Owner, $label collection-um sales-um serthu ${KaiFormat.rupees(c.totalIn)} vandhirukku."),
                en = listOf("$label, ${KaiFormat.rupees(c.totalIn)} came in (cash + UPI), Owner."),
            )) else reply(q.intent, KaiMood.EXPLAINING, lang, pick3(lang,
                ta = listOf("$label செலவு (போன பணம்) ${KaiFormat.rupees(c.totalOut)} ஓனர்."),
                tl = listOf("$label selavu ${KaiFormat.rupees(c.totalOut)} owner.", "Owner, $label ${KaiFormat.rupees(c.totalOut)} selavu aagirukku."),
                en = listOf("$label, ${KaiFormat.rupees(c.totalOut)} went out, Owner."),
            ))
        }
        ChatIntent.MONTHLY_PURCHASES, ChatIntent.DEBIT_SUMMARY -> entriesInPeriod(Direction.PAYABLE, q, s, lang, day)
        ChatIntent.CREDIT_SUMMARY -> entriesInPeriod(Direction.RECEIVABLE, q, s, lang, day)
        ChatIntent.REMINDER_QUERY -> {
            val open = s.reminders.filter { !it.isDone }
                .mapNotNull { r -> com.shopai.app.util.parseIsoToLocalDate(r.dueDate)?.let { r to it } }
                .filter { (_, d) -> q.period?.let { d in it } ?: !d.isBefore(day) }
                .sortedBy { it.second }
            if (open.isEmpty()) reply(q.intent, KaiMood.NEUTRAL, lang, pick3(lang,
                ta = listOf("வரப்போற ரிமைண்டர் எதுவும் இல்ல ஓனர்."),
                tl = listOf("Varappora reminder edhuvum illa owner."),
                en = listOf("No upcoming reminders, Owner."),
            )) else reply(q.intent, KaiMood.REMINDER, lang, when (lang) {
                KaiLang.TAMIL -> "ஓனர், ${open.size} ரிமைண்டர்: "
                KaiLang.TANGLISH -> "Owner, ${open.size} reminder irukku: "
                KaiLang.ENGLISH -> "Owner, ${open.size} reminder(s): "
            } + open.take(3).joinToString(", ") { (r, d) -> "${r.title} — ${KaiFormat.date(d, lang, day)}" } + ".")
        }
        ChatIntent.BUSINESS_SUMMARY, ChatIntent.GENERAL_BUSINESS_QUERY -> summary(s, lang, day, q.intent)
        ChatIntent.PENDING_LIST -> pendingList(q, s, lang, day)
        ChatIntent.RECEIVED_PAYMENTS -> paymentsInPeriod(q, s, lang, day)
        else -> reply(q.intent, KaiMood.CLARIFY, lang, unclear(lang))
    }

    // ------------------------------------------------- Daily Cash Note (read only)

    /**
     * Explains the Daily Cash Note for the day asked (today by default) from
     * the figures the app already calculates — never changes them.
     */
    private suspend fun dailyCash(q: ChatQuery, ledger: BusinessSnapshot?, lang: KaiLang, day: LocalDate): ChatReply {
        val date = q.period?.takeIf { it.from == it.to }?.from ?: day
        val note = books.cashNote(date) ?: return reply(q.intent, KaiMood.CLARIFY, lang, cashNoteMissing(lang))
        val t = note.totals
        fun r(v: Double) = KaiFormat.rupees(kotlin.math.abs(v))
        val whenTl = if (date == day) "Inniku" else KaiFormat.date(date, lang, day).replaceFirstChar { it.titlecase(Locale.ROOT) }
        val whenTa = if (date == day) "இன்னைக்கு" else KaiFormat.date(date, lang, day)
        val whenEn = if (date == day) "Today" else KaiFormat.date(date, lang, day).replaceFirstChar { it.titlecase(Locale.ROOT) }
        val empty = note.entries == 0
        fun noEntries() = reply(q.intent, KaiMood.NEUTRAL, lang, pick3(lang,
            ta = listOf("ஓனர், $whenTa Daily Cash Note-ல இன்னும் entry எதுவும் இல்ல."),
            tl = listOf("Owner, $whenTl Daily Cash Note-la innum entry edhuvum illa."),
            en = listOf("Owner, there are no Daily Cash Note entries for ${whenEn.lowercase(Locale.ROOT)} yet."),
        ))
        // What the net means: more came in than went out, or the other way.
        fun netSentence(): String = when {
            t.net > 0.005 -> when (lang) {
                KaiLang.TAMIL -> "மொத்தத்துல net ${r(t.net)} plus — போனதை விட வந்தது அதிகம்."
                KaiLang.TANGLISH -> "Overall net ${r(t.net)} plus-a irukku — out-a vida in adhigam."
                KaiLang.ENGLISH -> "Overall net is +${r(t.net)} — more came in than went out."
            }
            t.net < -0.005 -> when (lang) {
                KaiLang.TAMIL -> "மொத்தத்துல net ${r(t.net)} negative — வந்ததை விட போனது அதிகம்."
                KaiLang.TANGLISH -> "Overall net ${r(t.net)} negative-a irukku — in-a vida out adhigam."
                KaiLang.ENGLISH -> "Overall net is −${r(t.net)} — more went out than came in."
            }
            else -> when (lang) {
                KaiLang.TAMIL -> "வந்ததும் போனதும் சமம் — net ₹0."
                KaiLang.TANGLISH -> "In-um out-um samam — net ₹0."
                KaiLang.ENGLISH -> "In and out are equal — net ₹0."
            }
        }
        fun parts(): String {
            val bits = buildList {
                if (t.cashIn > 0.005) add(when (lang) { KaiLang.TAMIL -> "${r(t.cashIn)} cash வந்திருக்கு"; KaiLang.TANGLISH -> "${r(t.cashIn)} cash in vandhirukku"; KaiLang.ENGLISH -> "${r(t.cashIn)} cash in" })
                if (t.cashOut > 0.005) add(when (lang) { KaiLang.TAMIL -> "${r(t.cashOut)} cash போயிருக்கு"; KaiLang.TANGLISH -> "${r(t.cashOut)} cash out"; KaiLang.ENGLISH -> "${r(t.cashOut)} cash out" })
                if (t.upiIn > 0.005) add(when (lang) { KaiLang.TAMIL -> "UPI-ல ${r(t.upiIn)} வந்திருக்கு"; KaiLang.TANGLISH -> "UPI-la ${r(t.upiIn)} in"; KaiLang.ENGLISH -> "${r(t.upiIn)} UPI in" })
                if (t.upiOut > 0.005) add(when (lang) { KaiLang.TAMIL -> "UPI-ல ${r(t.upiOut)} போயிருக்கு"; KaiLang.TANGLISH -> "UPI-la ${r(t.upiOut)} out irukku"; KaiLang.ENGLISH -> "${r(t.upiOut)} UPI out" })
            }
            return bits.joinToString(". ").replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
        val mood = when { t.net < -0.005 -> KaiMood.CONCERNED; t.net > 0.005 -> KaiMood.HAPPY; else -> KaiMood.EXPLAINING }

        return when (q.cashAsk ?: CashAsk.FLOW) {
            CashAsk.FLOW -> if (empty) noEntries() else reply(q.intent, mood, lang, when (lang) {
                KaiLang.TAMIL -> "ஓனர், $whenTa ${parts()}. ${netSentence()}"
                KaiLang.TANGLISH -> "Owner, ${whenTl.lowercase(Locale.ROOT)} ${parts()}. ${netSentence()}"
                KaiLang.ENGLISH -> "Owner, ${whenEn.lowercase(Locale.ROOT)}: ${parts()}. ${netSentence()}"
            })
            CashAsk.CASH_IN -> if (t.cashIn <= 0.005) reply(q.intent, KaiMood.NEUTRAL, lang, pick3(lang,
                ta = listOf("$whenTa cash-ஆ எதுவும் வரல ஓனர்."), tl = listOf("$whenTl cash in edhuvum record aagala owner."), en = listOf("No cash came in ${whenEn.lowercase(Locale.ROOT)}, Owner."),
            )) else reply(q.intent, KaiMood.HAPPY, lang, pick3(lang,
                ta = listOf("$whenTa ${r(t.cashIn)} cash வந்திருக்கு ஓனர்."),
                tl = listOf("${r(t.cashIn)} cash in vandhirukku owner.", "Owner, ${whenTl.lowercase(Locale.ROOT)} cash-la ${r(t.cashIn)} in."),
                en = listOf("${r(t.cashIn)} came in as cash ${whenEn.lowercase(Locale.ROOT)}, Owner."),
            ))
            CashAsk.CASH_OUT -> if (t.cashOut <= 0.005) reply(q.intent, KaiMood.NEUTRAL, lang, pick3(lang,
                ta = listOf("$whenTa cash-ஆ எதுவும் போகல ஓனர்."), tl = listOf("$whenTl cash out edhuvum illa owner."), en = listOf("No cash went out ${whenEn.lowercase(Locale.ROOT)}, Owner."),
            )) else reply(q.intent, KaiMood.EXPLAINING, lang, pick3(lang,
                ta = listOf("$whenTa ${r(t.cashOut)} cash போயிருக்கு ஓனர்."),
                tl = listOf("$whenTl cash-la ${r(t.cashOut)} out owner."),
                en = listOf("${r(t.cashOut)} went out in cash ${whenEn.lowercase(Locale.ROOT)}, Owner."),
            ))
            CashAsk.UPI, CashAsk.UPI_IN, CashAsk.UPI_OUT -> {
                if (t.upiIn <= 0.005 && t.upiOut <= 0.005) reply(q.intent, KaiMood.NEUTRAL, lang, pick3(lang,
                    ta = listOf("$whenTa UPI-ல எதுவும் நடக்கல ஓனர்."), tl = listOf("$whenTl UPI-la edhuvum nadakkala owner."), en = listOf("No UPI activity ${whenEn.lowercase(Locale.ROOT)}, Owner."),
                )) else {
                    val ask = q.cashAsk
                    val inPart = when (lang) { KaiLang.TAMIL -> "${r(t.upiIn)} வந்திருக்கு"; KaiLang.TANGLISH -> "${r(t.upiIn)} in"; KaiLang.ENGLISH -> "${r(t.upiIn)} in" }
                    val outPart = when (lang) { KaiLang.TAMIL -> "${r(t.upiOut)} போயிருக்கு"; KaiLang.TANGLISH -> "${r(t.upiOut)} out"; KaiLang.ENGLISH -> "${r(t.upiOut)} out" }
                    val shown = when {
                        ask == CashAsk.UPI_IN -> inPart
                        ask == CashAsk.UPI_OUT -> outPart
                        t.upiIn > 0.005 && t.upiOut > 0.005 -> "$inPart, $outPart"
                        t.upiIn > 0.005 -> inPart
                        else -> outPart
                    }
                    reply(q.intent, if (t.upiOut > t.upiIn) KaiMood.CONCERNED else KaiMood.EXPLAINING, lang, when (lang) {
                        KaiLang.TAMIL -> "$whenTa UPI-ல $shown ஓனர்."
                        KaiLang.TANGLISH -> "$whenTl UPI-la $shown irukku owner."
                        KaiLang.ENGLISH -> "${whenEn}, UPI: $shown, Owner."
                    })
                }
            }
            CashAsk.TOTAL_IN -> if (empty) noEntries() else reply(q.intent, KaiMood.HAPPY, lang, when (lang) {
                KaiLang.TAMIL -> "$whenTa மொத்தம் ${r(t.totalIn)} வந்திருக்கு (cash ${r(t.cashIn)} + UPI ${r(t.upiIn)}) ஓனர்."
                KaiLang.TANGLISH -> "$whenTl total in ${r(t.totalIn)} owner (cash ${r(t.cashIn)} + UPI ${r(t.upiIn)})."
                KaiLang.ENGLISH -> "$whenEn, total in is ${r(t.totalIn)} (cash ${r(t.cashIn)} + UPI ${r(t.upiIn)}), Owner."
            })
            CashAsk.TOTAL_OUT -> if (empty) noEntries() else reply(q.intent, KaiMood.EXPLAINING, lang, when (lang) {
                KaiLang.TAMIL -> "$whenTa மொத்தம் ${r(t.totalOut)} போயிருக்கு (cash ${r(t.cashOut)} + UPI ${r(t.upiOut)}) ஓனர்."
                KaiLang.TANGLISH -> "$whenTl total out ${r(t.totalOut)} owner (cash ${r(t.cashOut)} + UPI ${r(t.upiOut)})."
                KaiLang.ENGLISH -> "$whenEn, total out is ${r(t.totalOut)} (cash ${r(t.cashOut)} + UPI ${r(t.upiOut)}), Owner."
            })
            CashAsk.NET -> if (empty) noEntries() else reply(q.intent, mood, lang, when (lang) {
                KaiLang.TAMIL -> "ஓனர், in ${r(t.totalIn)}, out ${r(t.totalOut)}. ${netSentence()}"
                KaiLang.TANGLISH -> "Owner, in ${r(t.totalIn)}, out ${r(t.totalOut)}. ${netSentence()}"
                KaiLang.ENGLISH -> "Owner, in ${r(t.totalIn)}, out ${r(t.totalOut)}. ${netSentence()}"
            })
            CashAsk.OPENING -> {
                val opening = note.opening ?: return reply(q.intent, KaiMood.CLARIFY, lang, pick3(lang,
                    ta = listOf("ஓனர், $whenTa Kallapetti opening balance இன்னும் set பண்ணல."),
                    tl = listOf("Owner, $whenTl Kallapetti opening balance innum set pannala."),
                    en = listOf("Owner, the opening Kallapetti amount isn't set for ${whenEn.lowercase(Locale.ROOT)}."),
                ))
                reply(q.intent, KaiMood.EXPLAINING, lang, pick3(lang,
                    ta = listOf("$whenTa Kallapetti opening ${r(opening)} ஓனர்."),
                    tl = listOf("$whenTl Kallapetti opening ${r(opening)} owner."),
                    en = listOf("The opening Kallapetti amount is ${r(opening)}, Owner."),
                ))
            }
            CashAsk.KALLAPETTI -> {
                val opening = note.opening
                val current = note.kallapetti
                if (opening == null || current == null) return reply(q.intent, KaiMood.CLARIFY, lang, pick3(lang,
                    ta = listOf("ஓனர், Kallapetti opening balance இன்னும் set பண்ணல — அதனால இப்போ எவ்வளவுன்னு Daily Cash Note-ல இல்ல."),
                    tl = listOf("Owner, Kallapetti opening balance innum set pannala — adhanaala ippo evlo-nu Daily Cash Note-la available illa."),
                    en = listOf("Owner, the opening Kallapetti amount isn't set, so the current amount isn't available in the Daily Cash Note."),
                ))
                val cashNet = t.cashNet
                val move = when {
                    cashNet > 0.005 -> when (lang) { KaiLang.TAMIL -> "cash ${r(cashNet)} கூடியிருக்கு"; KaiLang.TANGLISH -> "cash ${r(cashNet)} koodi irukku"; KaiLang.ENGLISH -> "cash is up ${r(cashNet)}" }
                    cashNet < -0.005 -> when (lang) { KaiLang.TAMIL -> "cash ${r(cashNet)} குறைஞ்சிருக்கு"; KaiLang.TANGLISH -> "cash ${r(cashNet)} kuranjirukku"; KaiLang.ENGLISH -> "cash is down ${r(cashNet)}" }
                    else -> when (lang) { KaiLang.TAMIL -> "cash மாற்றம் இல்ல"; KaiLang.TANGLISH -> "cash maatram illa"; KaiLang.ENGLISH -> "no cash movement" }
                }
                reply(q.intent, KaiMood.EXPLAINING, lang, when (lang) {
                    KaiLang.TAMIL -> "ஓனர், opening ${r(opening)}. $whenTa $move — இப்போ Kallapetti-ல ${r(current)} இருக்கணும். (UPI பெட்டிக்குள்ள வராது.)"
                    KaiLang.TANGLISH -> "Owner, opening ${r(opening)}. $whenTl $move — ippo Kallapetti-la ${r(current)} irukkanum. (UPI petti-kulla varadhu.)"
                    KaiLang.ENGLISH -> "Owner, opening ${r(opening)}; $move — the Kallapetti should now hold ${r(current)}. (UPI doesn't go into the box.)"
                })
            }
            CashAsk.DAY_CLOSE -> if (note.dayClosed) reply(q.intent, KaiMood.SUCCESS, lang, when (lang) {
                KaiLang.TAMIL -> "$whenTa day close ஆயிடுச்சு ஓனர். In ${r(t.totalIn)}, out ${r(t.totalOut)}. ${netSentence()}"
                KaiLang.TANGLISH -> "$whenTl day close aayiduchu owner. In ${r(t.totalIn)}, out ${r(t.totalOut)}. ${netSentence()}"
                KaiLang.ENGLISH -> "$whenEn is closed, Owner. In ${r(t.totalIn)}, out ${r(t.totalOut)}. ${netSentence()}"
            }) else reply(q.intent, KaiMood.REMINDER, lang, when (lang) {
                KaiLang.TAMIL -> "$whenTa இன்னும் day close பண்ணல ஓனர். இதுவரை in ${r(t.totalIn)}, out ${r(t.totalOut)}." + (note.kallapetti?.let { " Kallapetti ${r(it)}." } ?: "")
                KaiLang.TANGLISH -> "$whenTl innum day close pannala owner. Ippo varai in ${r(t.totalIn)}, out ${r(t.totalOut)}." + (note.kallapetti?.let { " Kallapetti ${r(it)}." } ?: "")
                KaiLang.ENGLISH -> "$whenEn isn't closed yet, Owner. So far in ${r(t.totalIn)}, out ${r(t.totalOut)}." + (note.kallapetti?.let { " Kallapetti ${r(it)}." } ?: "")
            })
            CashAsk.IMPORTANT -> {
                val dueToday = ledger?.owesMe()?.filter { it.nextDue == date }.orEmpty()
                val lines = buildList {
                    if (!empty) add(when (lang) {
                        KaiLang.TAMIL -> "cash note: in ${r(t.totalIn)}, out ${r(t.totalOut)}"
                        KaiLang.TANGLISH -> "Cash note: in ${r(t.totalIn)}, out ${r(t.totalOut)}"
                        KaiLang.ENGLISH -> "cash note: ${r(t.totalIn)} in, ${r(t.totalOut)} out"
                    })
                    if (t.net < -0.005) add(when (lang) {
                        KaiLang.TAMIL -> "net ${r(t.net)} negative — செலவை கவனிங்க"
                        KaiLang.TANGLISH -> "net ${r(t.net)} negative — out-a konjam kavaninga"
                        KaiLang.ENGLISH -> "net is −${r(t.net)} — watch the outflow"
                    })
                    if (dueToday.isNotEmpty()) add(when (lang) {
                        KaiLang.TAMIL -> "${dueToday.size} வசூல் இன்னைக்கு due (${KaiFormat.rupees(dueToday.sumOf { it.pending })})"
                        KaiLang.TANGLISH -> "${dueToday.size} collection innaikku due (${KaiFormat.rupees(dueToday.sumOf { it.pending })})"
                        KaiLang.ENGLISH -> "${dueToday.size} collection(s) due today (${KaiFormat.rupees(dueToday.sumOf { it.pending })})"
                    })
                    if (!note.dayClosed && date == day && !empty) add(when (lang) {
                        KaiLang.TAMIL -> "day close இன்னும் பண்ணல"
                        KaiLang.TANGLISH -> "day close innum pannala"
                        KaiLang.ENGLISH -> "the day isn't closed yet"
                    })
                }
                if (lines.isEmpty()) noEntries()
                else reply(q.intent, if (t.net < -0.005 || dueToday.isNotEmpty()) KaiMood.CONCERNED else KaiMood.EXPLAINING, lang, when (lang) {
                    KaiLang.TAMIL -> "ஓனர், இன்னைக்கு முக்கியம்: " + lines.joinToString("; ") + "."
                    KaiLang.TANGLISH -> "Owner, innaikku important: " + lines.joinToString("; ") + "."
                    KaiLang.ENGLISH -> "Owner, today's important points: " + lines.joinToString("; ") + "."
                })
            }
        }
    }

    private fun cashNoteMissing(lang: KaiLang) = when (lang) {
        KaiLang.TAMIL -> "ஓனர், இந்த தகவல் Daily Cash Note-ல இல்ல."
        KaiLang.TANGLISH -> "Owner, indha information Daily Cash Note-la available illa."
        KaiLang.ENGLISH -> "Owner, this information isn't available in the Daily Cash Note."
    }

    private fun collections(list: List<PartyFacts>, intent: ChatIntent, lang: KaiLang, day: LocalDate, label: String, overdue: List<PartyFacts>): ChatReply {
        val od = overdue.filter { o -> list.none { it.id == o.id } }
        if (list.isEmpty()) {
            val base = when (lang) {
                KaiLang.TAMIL -> "$label யாரும் தர வேண்டியது இல்ல ஓனர்."
                KaiLang.TANGLISH -> "$label yaarum tharavendiyadhu illa owner."
                KaiLang.ENGLISH -> "$label: no collections due, Owner."
            }
            return reply(intent, if (od.isEmpty()) KaiMood.NEUTRAL else KaiMood.CONCERNED, lang, base + overdueNote(od, lang))
        }
        val total = KaiFormat.rupees(list.sumOf { it.pending })
        val top = list.take(3).joinToString(", ") { "${label(it)} ${KaiFormat.rupees(it.pending)}${dueSuffix(it, lang, day)}" }
        val text = when (lang) {
            KaiLang.TAMIL -> "$label ${list.size} பேர் தரணும் (மொத்தம் $total): $top."
            KaiLang.TANGLISH -> pick(listOf(
                "$label ${list.size} per tharanum owner (mothama $total): $top.",
                "Owner, $label $total varanum — $top.",
            ))
            KaiLang.ENGLISH -> "$label, ${list.size} to collect ($total): $top."
        }
        return reply(intent, KaiMood.CREDIT, lang, text + overdueNote(od, lang))
    }

    private fun overdueNote(od: List<PartyFacts>, lang: KaiLang) = if (od.isEmpty()) "" else when (lang) {
        KaiLang.TAMIL -> " ${od.size} பேரோட தேதி தாண்டிடுச்சு."
        KaiLang.TANGLISH -> " Innum ${od.size} per date thaandiduchu."
        KaiLang.ENGLISH -> " ${od.size} more are overdue."
    }

    /** Credit given / supplier debits recorded in a period, from each party's entries. */
    private suspend fun entriesInPeriod(side: Direction, q: ChatQuery, s: BusinessSnapshot, lang: KaiLang, day: LocalDate): ChatReply {
        val period = q.period ?: monthOf(day)
        val parties = s.parties.filter { it.side == side }
        if (parties.isEmpty()) return reply(q.intent, KaiMood.NEUTRAL, lang, missing(lang))
        var count = 0
        var total = 0.0
        for (p in parties) {
            val h = books.history(p) ?: continue
            h.entries.filter { e -> e.createdAt != null && e.createdAt in period }.forEach { count++; total += it.amount }
        }
        val label = periodLabel(period, lang, day)
        if (count == 0) {
            return reply(q.intent, KaiMood.NEUTRAL, lang, when (lang) {
                KaiLang.TAMIL -> "$label ${if (side == Direction.RECEIVABLE) "கடன் (credit)" else "சப்ளையர் debit"} entry எதுவும் இல்ல ஓனர்."
                KaiLang.TANGLISH -> "$label ${if (side == Direction.RECEIVABLE) "credit" else "supplier debit"} entry edhuvum illa owner."
                KaiLang.ENGLISH -> "$label: no ${if (side == Direction.RECEIVABLE) "credit" else "supplier debit"} entries, Owner."
            })
        }
        val t = KaiFormat.rupees(total)
        return reply(q.intent, if (side == Direction.RECEIVABLE) KaiMood.CREDIT else KaiMood.DEBIT, lang, when (lang) {
            KaiLang.TAMIL -> if (side == Direction.RECEIVABLE) "$label $count credit entry, மொத்தம் $t கொடுத்திருக்கீங்க ஓனர்." else "$label சப்ளையர் கிட்ட $count entry, மொத்தம் $t வாங்கியிருக்கீங்க ஓனர்."
            KaiLang.TANGLISH -> if (side == Direction.RECEIVABLE) "$label $count credit entry, mothama $t credit kuduthirukkeenga owner." else "$label supplier kitta $count entry, mothama $t purchase/debit owner."
            KaiLang.ENGLISH -> if (side == Direction.RECEIVABLE) "$label: $count credit entries totalling $t, Owner." else "$label: $count supplier entries totalling $t, Owner."
        })
    }

    /** Every pending person on a side, those with no due date, or the highest / lowest — straight from the ledger. */
    private suspend fun pendingList(q: ChatQuery, s: BusinessSnapshot, lang: KaiLang, day: LocalDate): ChatReply {
        val side = q.side ?: Direction.RECEIVABLE
        val all = s.pendingOn(side)
        val scope = q.listScope ?: ListScope.ALL
        val rows = when (scope) {
            ListScope.ALL -> all
            // The summary dates an entry with no due date by its bill date, so the entries themselves say which have none.
            ListScope.NO_DUE -> {
                val read = all.associateWith { books.history(it) }
                if (all.isNotEmpty() && read.values.all { it == null }) return reply(q.intent, KaiMood.ERROR, lang, noRecords(lang))
                all.filter { p -> read[p]?.entries?.filter { it.amount - it.paid > 0.005 }?.let { open -> open.isNotEmpty() && open.all { it.dueDate == null } } == true }
                    .map { it.copy(nextDue = null) }
            }
            ListScope.HIGHEST -> all.sortedWith(compareByDescending<PartyFacts> { it.pending }.thenBy { it.name })
            ListScope.LOWEST -> all.sortedWith(compareBy<PartyFacts> { it.pending }.thenBy { it.name })
        }
        val kept = keepList(if (scope == ListScope.NO_DUE) ListKind.NO_DUE else if (scope == ListScope.ALL) ListKind.PENDING else ListKind.RANKED, side, null, rows)
        val pay = side == Direction.PAYABLE
        if (rows.isEmpty()) return reply(q.intent, KaiMood.NEUTRAL, lang, when (lang) {
            KaiLang.TAMIL -> if (scope == ListScope.NO_DUE) "Due date இல்லாம pending யாரும் இல்ல ஓனர்." else if (pay) "நீங்க யாருக்கும் கொடுக்க வேண்டியது இல்ல ஓனர்." else "யாரும் தர வேண்டியது இல்ல ஓனர்."
            KaiLang.TANGLISH -> if (scope == ListScope.NO_DUE) "Due date illaama pending yaarum illa owner." else if (pay) "Neenga yaarukkum kudukka vendiyadhu illa owner." else "Yaarum tharavendiyadhu illa owner."
            KaiLang.ENGLISH -> if (scope == ListScope.NO_DUE) "No one is pending without a due date, Owner." else if (pay) "You don't owe anyone, Owner." else "No one owes you anything, Owner."
        })
        if (scope == ListScope.HIGHEST || scope == ListScope.LOWEST) {
            val top = rows.first()
            val a = KaiFormat.rupees(top.pending)
            val high = scope == ListScope.HIGHEST
            return reply(q.intent, if (pay) KaiMood.DEBIT else KaiMood.CREDIT, lang, when (lang) {
                KaiLang.TAMIL -> "ஓனர், ${if (high) "அதிகமான" else "குறைவான"} பாக்கி ${label(top)} — $a${dueSuffix(top, lang, day)}."
                KaiLang.TANGLISH -> "Owner, ${if (high) "adhigama" else "kammiya"} pending ${label(top)} ${if (pay) "-ku" else "kitta"} — $a${dueSuffix(top, lang, day)}."
                KaiLang.ENGLISH -> "Owner, the ${if (high) "highest" else "lowest"} pending is ${label(top)} — $a${dueSuffix(top, lang, day)}."
            }.replace(" -ku", "-ku"))
        }
        return reply(q.intent, if (pay) KaiMood.DEBIT else KaiMood.CREDIT, lang, listText(kept, lang, day))
    }

    /** Payments actually made in a past period (each party's ledger history), by person — never a guess. */
    private suspend fun paymentsInPeriod(q: ChatQuery, s: BusinessSnapshot, lang: KaiLang, day: LocalDate): ChatReply {
        val period = q.period ?: monthOf(day)
        val side = q.side ?: Direction.RECEIVABLE
        val parties = s.parties.filter { it.side == side }
        var read = 0
        val rows = parties.mapNotNull { p ->
            val h = books.history(p) ?: return@mapNotNull null
            read++
            val paid = h.entries.flatMap { it.payments }.filter { it.date != null && it.date in period }
            if (paid.isEmpty()) null else LedgerList.Row(p, paid.fold(java.math.BigDecimal.ZERO) { t, x -> t + java.math.BigDecimal.valueOf(x.amount) }.toDouble(), paid.maxOf { it.date!! })
        }.sortedWith(compareByDescending<LedgerList.Row> { it.date }.thenBy { it.party.name })
        if (parties.isNotEmpty() && read == 0) return reply(q.intent, KaiMood.ERROR, lang, noRecords(lang))
        val list = LedgerList(ListKind.PAID, side, period, rows).also { lastList = it; listIsLatest = true }
        val label = periodLabel(period, lang, day)
        if (rows.isEmpty()) return reply(q.intent, KaiMood.NEUTRAL, lang, when (lang) {
            KaiLang.TAMIL -> if (side == Direction.PAYABLE) "$label நீங்க யாருக்கும் payment பண்ணது பதிவுல இல்ல ஓனர்." else "$label யாரும் payment பண்ணது பதிவுல இல்ல ஓனர்."
            KaiLang.TANGLISH -> if (side == Direction.PAYABLE) "$label neenga yaarukkum pay pannadhu record-la illa owner." else "$label yaarum payment pannadhu record-la illa owner."
            KaiLang.ENGLISH -> if (side == Direction.PAYABLE) "$label: no payments from you are recorded, Owner." else "$label: no payments received are recorded, Owner."
        })
        return reply(q.intent, if (side == Direction.PAYABLE) KaiMood.DEBIT else KaiMood.HAPPY, lang, listText(list, lang, day))
    }

    private suspend fun summary(s: BusinessSnapshot, lang: KaiLang, day: LocalDate, intent: ChatIntent): ChatReply {
        val r = KaiFormat.rupees(s.totalReceivable())
        val p = KaiFormat.rupees(s.totalPayable())
        val od = s.overdue(day).filter { it.side == Direction.RECEIVABLE }
        val todayDue = s.owesMe().filter { it.nextDue == day }
        val cash = books.cashBook(day, day)
        val text = when (lang) {
            KaiLang.TAMIL -> "ஓனர், வாங்க வேண்டியது $r, கொடுக்க வேண்டியது $p." +
                (if (todayDue.isNotEmpty()) " இன்னைக்கு ${todayDue.size} வசூல் இருக்கு." else "") +
                (if (od.isNotEmpty()) " ${od.size} வசூல் தேதி தாண்டிடுச்சு." else "") +
                (cash?.takeIf { it.entries > 0 }?.let { " இன்னைக்கு வந்தது ${KaiFormat.rupees(it.totalIn)}." } ?: "")
            KaiLang.TANGLISH -> "Owner, vaanga vendiyadhu $r, kudukka vendiyadhu $p." +
                (if (todayDue.isNotEmpty()) " Innaikku ${todayDue.size} collection irukku." else "") +
                (if (od.isNotEmpty()) " ${od.size} collection date thaandiduchu." else "") +
                (cash?.takeIf { it.entries > 0 }?.let { " Innaikku vandhadhu ${KaiFormat.rupees(it.totalIn)}." } ?: "")
            KaiLang.ENGLISH -> "Owner, $r to collect and $p to pay." +
                (if (todayDue.isNotEmpty()) " ${todayDue.size} collection(s) due today." else "") +
                (if (od.isNotEmpty()) " ${od.size} overdue." else "") +
                (cash?.takeIf { it.entries > 0 }?.let { " ${KaiFormat.rupees(it.totalIn)} came in today." } ?: "")
        }
        return reply(intent, if (od.isNotEmpty()) KaiMood.CONCERNED else KaiMood.EXPLAINING, lang, text)
    }

    // ------------------------------------------------------------ helpers

    private fun dueSuffix(p: PartyFacts, lang: KaiLang, day: LocalDate) =
        p.nextDue?.let { if (lang == KaiLang.ENGLISH) " (due ${KaiFormat.date(it, lang, day)})" else " — ${KaiFormat.date(it, lang, day)}" }.orEmpty()

    /** The owner said an amount that differs from the records: the records win, and Kai says so. */
    private fun mismatch(said: Double?, recorded: Double, lang: KaiLang): String {
        if (said == null || kotlin.math.abs(said - recorded) < 0.01) return ""
        val r = KaiFormat.rupees(recorded)
        return when (lang) {
            KaiLang.TAMIL -> " (பதிவுல $r தான் இருக்கு.)"
            KaiLang.TANGLISH -> " (Record-la $r dhaan irukku.)"
            KaiLang.ENGLISH -> " (The records show $r.)"
        }
    }

    private fun periodLabel(p: ChatPeriod, lang: KaiLang, day: LocalDate): String = when (p.kind) {
        ChatPeriod.Kind.TODAY -> when (lang) { KaiLang.TAMIL -> "இன்னைக்கு"; KaiLang.TANGLISH -> "Innaikku"; KaiLang.ENGLISH -> "Today" }
        ChatPeriod.Kind.TOMORROW -> when (lang) { KaiLang.TAMIL -> "நாளைக்கு"; KaiLang.TANGLISH -> "Naalaikku"; KaiLang.ENGLISH -> "Tomorrow" }
        ChatPeriod.Kind.YESTERDAY -> when (lang) { KaiLang.TAMIL -> "நேத்து"; KaiLang.TANGLISH -> "Nethu"; KaiLang.ENGLISH -> "Yesterday" }
        ChatPeriod.Kind.THIS_WEEK -> when (lang) { KaiLang.TAMIL -> "இந்த வாரம்"; KaiLang.TANGLISH -> "Indha week"; KaiLang.ENGLISH -> "This week" }
        ChatPeriod.Kind.NEXT_WEEK -> when (lang) { KaiLang.TAMIL -> "அடுத்த வாரம்"; KaiLang.TANGLISH -> "Next week"; KaiLang.ENGLISH -> "Next week" }
        ChatPeriod.Kind.THIS_MONTH -> when (lang) { KaiLang.TAMIL -> "இந்த மாசம்"; KaiLang.TANGLISH -> "Indha maasam"; KaiLang.ENGLISH -> "This month" }
        ChatPeriod.Kind.NEXT_MONTH -> when (lang) { KaiLang.TAMIL -> "அடுத்த மாசம்"; KaiLang.TANGLISH -> "Next month"; KaiLang.ENGLISH -> "Next month" }
        ChatPeriod.Kind.LAST_MONTH -> when (lang) { KaiLang.TAMIL -> "போன மாசம்"; KaiLang.TANGLISH -> "Pona maasam"; KaiLang.ENGLISH -> "Last month" }
        ChatPeriod.Kind.DATE -> KaiFormat.date(p.from, lang, day).replaceFirstChar { it.titlecase(Locale.ROOT) }
        ChatPeriod.Kind.LAST_DAYS -> {
            val n = java.time.temporal.ChronoUnit.DAYS.between(p.from, p.to) + 1
            when (lang) { KaiLang.TAMIL -> "கடந்த $n நாள்ல"; KaiLang.TANGLISH -> "Kadandha $n naal-la"; KaiLang.ENGLISH -> "In the last $n days" }
        }
        ChatPeriod.Kind.LAST_WEEK -> when (lang) { KaiLang.TAMIL -> "போன வாரம்"; KaiLang.TANGLISH -> "Pona vaaram"; KaiLang.ENGLISH -> "Last week" }
    }

    private fun monthOf(day: LocalDate) = day.withDayOfMonth(1).let { ChatPeriod(it, it.plusMonths(1).minusDays(1), ChatPeriod.Kind.THIS_MONTH) }

    /** The owner's answer to "which one?": a name, or "first"/"second"/"1"/"2". */
    private fun pick(text: String, options: List<PartyFacts>): PartyFacts? {
        val lower = text.lowercase(Locale.ROOT)
        if (everyOne.containsMatchIn(lower)) return null
        val ordinal = when {
            Regex("""\b(1|first|mudhal|modhal|onnu|oru)\b""").containsMatchIn(lower) -> 0
            Regex("""\b(2|second|rendavadhu|rendu|irandu)\b""").containsMatchIn(lower) -> 1
            Regex("""\b(3|third|moonavadhu|moonu)\b""").containsMatchIn(lower) -> 2
            else -> null
        }
        if (ordinal != null) return options.getOrNull(ordinal)
        // "Nagapattinam" / "Chennai Lokesh": the place or shop word only one of them has.
        options.filter { o ->
            hintLabels[o.id]?.split(Regex("""[^\p{L}\p{M}]+"""))?.any { w -> w.length >= 3 && !o.name.contains(w, ignoreCase = true) && lower.contains(w.lowercase(Locale.ROOT)) } == true
        }.singleOrNull()?.let { return it }
        // "Madurai" / "Madurai Lokesh" to "Lokesh-aa, Madurai Lokesh-aa?": the name word only one of them has.
        fun words(n: String) = n.lowercase(Locale.ROOT).split(Regex("""[^\p{L}\p{M}]+""")).filter { it.length >= 3 }.toSet()
        val shared = options.map { words(it.name) }.reduce { a, b -> a intersect b }
        val said = words(lower)
        options.filter { o -> words(o.name).any { it !in shared && it in said } }.singleOrNull()?.let { return it }
        options.filter { lower.contains(it.name.lowercase(Locale.ROOT)) }.distinctBy { it.name.lowercase(Locale.ROOT) }.singleOrNull()
            ?.let { named -> options.filter { it.name.equals(named.name, ignoreCase = true) }.singleOrNull() }?.let { return it }
        // By phone digits said.
        return null
    }

    private fun whichOne(name: String, matches: List<PartyFacts>, lang: KaiLang): String {
        val list = matches.take(3).mapIndexed { i, p ->
            "${i + 1}. ${hintLabels[p.id] ?: p.name}" + (if (p.side == Direction.PAYABLE) " (supplier)" else "") + " — ${KaiFormat.rupees(p.pending)}"
        }.joinToString("; ")
        return when (lang) {
            KaiLang.TAMIL -> "ஓனர், $name-னு ${matches.size} பேர் இருக்காங்க. யார் பத்தி கேக்குறீங்க? $list"
            KaiLang.TANGLISH -> "Owner, $name-nu ${if (matches.size == 2) "rendu" else matches.size.toString()} per irukkaanga. Yaar pathi kekkureenga? $list"
            KaiLang.ENGLISH -> "Owner, there are ${matches.size} named $name. Which one? $list"
        }
    }

    private fun notFound(name: String, lang: KaiLang) = when (lang) {
        KaiLang.TAMIL -> "ஓனர், $name-னு record எனக்கு கிடைக்கல."
        KaiLang.TANGLISH -> "Owner, $name-nu customer record enakku kidaikala."
        KaiLang.ENGLISH -> "Owner, I couldn't find a record for $name."
    }

    private fun whoDoYouMean(lang: KaiLang) = when (lang) {
        KaiLang.TAMIL -> "ஓனர், யார் பத்தி கேக்குறீங்க? பேர் சொல்லுங்க."
        KaiLang.TANGLISH -> "Owner, yaar pathi kekkureenga? Per sollunga."
        KaiLang.ENGLISH -> "Owner, who do you mean? Please tell me the name."
    }

    private fun unclear(lang: KaiLang) = when (lang) {
        KaiLang.TAMIL -> "ஓனர், கொஞ்சம் தெளிவா சொல்லுங்க."
        KaiLang.TANGLISH -> "Owner, konjam clear-ah sollunga."
        KaiLang.ENGLISH -> "Owner, could you say that a little more clearly?"
    }

    private fun missing(lang: KaiLang) = when (lang) {
        KaiLang.TAMIL -> "ஓனர், இந்த தகவல் எனக்கு record-ல இல்ல."
        KaiLang.TANGLISH -> "Owner, indha information enakku record-la illa."
        KaiLang.ENGLISH -> "Owner, I don't have this information in the records."
    }

    private fun noRecords(lang: KaiLang) = when (lang) {
        KaiLang.TAMIL -> "ஓனர், உங்க கணக்கை இப்போ பார்க்க முடியல. நெட் செக் பண்ணுங்க."
        KaiLang.TANGLISH -> "Owner, unga kanakku ippo paakka mudiyala. Net check pannunga."
        KaiLang.ENGLISH -> "Owner, I can't reach your records right now. Please check the internet."
    }

    /** One of several safe wordings — all carry the same facts. */
    private fun pick3(lang: KaiLang, ta: List<String>, tl: List<String>, en: List<String>): String =
        pick(when (lang) { KaiLang.TAMIL -> ta; KaiLang.TANGLISH -> tl; KaiLang.ENGLISH -> en })

    private fun pick(options: List<String>): String = options[random.nextInt(options.size)]

    private fun reply(intent: ChatIntent, mood: KaiMood, lang: KaiLang, text: String) = ChatReply(text, mood, intent)

    /** English only when the owner clearly writes English; otherwise Kai's usual Tanglish (or Tamil). */
    /** Set by Kai Chat for one turn when the owner spoke / wrote Tamil script (the words reach here in Tanglish). */
    var spokenLang: KaiLang? = null

    private fun chatLanguage(text: String): KaiLang {
        spokenLang?.let { return it }
        val detected = KaiLanguage.detect(text)
        if (detected != KaiLang.ENGLISH) return detected
        val words = text.lowercase(Locale.ROOT).split(Regex("[^a-z]+")).toSet()
        val english = setOf("what", "how", "who", "whom", "when", "which", "is", "are", "does", "do", "did", "the", "much", "owe", "owes",
            "my", "me", "i", "today", "tomorrow", "show", "tell", "total", "pay", "paid", "will", "has", "have", "from", "this", "next", "last")
        return if (words.any { it in english }) KaiLang.ENGLISH else KaiLang.TANGLISH
    }

    /** Start a fresh conversation (context cleared). */
    fun reset() {
        lastParty = null
        choices = emptyList()
        choiceQuery = null
        lastList = null
        listIsLatest = false
    }
}
