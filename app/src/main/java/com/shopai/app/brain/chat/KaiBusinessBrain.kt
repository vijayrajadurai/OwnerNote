package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.Direction
import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiLanguage
import com.shopai.app.brain.KaiMood
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

    suspend fun ask(text: String): ChatReply {
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
        return when {
            query.intent == ChatIntent.UNKNOWN -> reply(query.intent, KaiMood.CLARIFY, lang, unclear(lang))
            isPersonIntent(query.intent) -> personAnswer(query, ledger, lang, day)
            else -> businessAnswer(query, ledger, lang, day)
        }
    }

    // ------------------------------------------------------------ people

    private fun isPersonIntent(i: ChatIntent) = i in setOf(
        ChatIntent.CUSTOMER_BALANCE, ChatIntent.CUSTOMER_DUE_DATE, ChatIntent.CUSTOMER_HISTORY,
        ChatIntent.CUSTOMER_LAST_PAYMENT, ChatIntent.CUSTOMER_PAYMENTS,
    )

    private suspend fun personAnswer(query: ChatQuery, ledger: BusinessSnapshot, lang: KaiLang, day: LocalDate): ChatReply {
        val party: PartyFacts = when (val ref = query.person) {
            is PersonRef.Named -> {
                val matches = ledger.find(ref.name).distinctBy { it.id }
                when {
                    matches.isEmpty() -> return reply(query.intent, KaiMood.CLARIFY, lang, notFound(ref.name, lang))
                    matches.size > 1 -> {
                        choices = matches
                        choiceQuery = query
                        return reply(query.intent, KaiMood.CLARIFY, lang, whichOne(ref.name, matches, lang))
                    }
                    else -> matches.single()
                }
            }
            // "avan" / a follow-up with no name: the person just talked about (refreshed from the ledger).
            PersonRef.Pronoun, PersonRef.None -> lastParty?.let { p -> ledger.parties.firstOrNull { it.id == p.id } ?: p }
                ?: return reply(query.intent, KaiMood.CLARIFY, lang, whoDoYouMean(lang))
        }
        lastParty = party
        return answerAbout(party, query, lang, day)
    }

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
            else -> reply(intent, KaiMood.CLARIFY, lang, unclear(lang))
        }
    }

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
        val due = p.nextDue?.let { dueText(it, lang, day) }
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
            base + (due?.let { " " + it.sentence } ?: "") + mismatch(said, p.pending, lang))
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
        val text = when (lang) {
            KaiLang.TAMIL -> "$n: ${h.entries.size} entry, மொத்தம் $total. வந்தது $paid, பாக்கி $pending." +
                (last?.let { " கடைசி பேமெண்ட் ${KaiFormat.rupees(it.amount)}, ${KaiFormat.date(it.date!!, lang, day)}." } ?: "")
            KaiLang.TANGLISH -> "$n: ${h.entries.size} entry, mothama $total. Vandhadhu $paid, pending $pending." +
                (last?.let { " Last payment ${KaiFormat.rupees(it.amount)}, ${KaiFormat.date(it.date!!, lang, day)}." } ?: "")
            KaiLang.ENGLISH -> "$n: ${h.entries.size} entries totalling $total. Paid $paid, pending $pending." +
                (last?.let { " Last payment ${KaiFormat.rupees(it.amount)} on ${KaiFormat.date(it.date!!, lang, day)}." } ?: "")
        }
        return reply(intent, KaiMood.EXPLAINING, lang, text)
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

    // ---------------------------------------------------------- business

    private suspend fun businessAnswer(q: ChatQuery, s: BusinessSnapshot, lang: KaiLang, day: LocalDate): ChatReply = when (q.intent) {
        ChatIntent.TODAY_COLLECTIONS -> collections(s.owesMe().filter { it.nextDue == day }, ChatIntent.TODAY_COLLECTIONS, lang, day, when (lang) {
            KaiLang.TAMIL -> "இன்னைக்கு"; KaiLang.TANGLISH -> "Innaikku"; KaiLang.ENGLISH -> "Today"
        }, overdue = s.overdue(day).filter { it.side == Direction.RECEIVABLE })
        ChatIntent.UPCOMING_COLLECTIONS -> {
            val period = q.period
            val list = s.owesMe().filter { p ->
                val due = p.nextDue
                if (period == null) true else due != null && due in period
            }
            val label = period?.let { periodLabel(it, lang, day) } ?: when (lang) {
                KaiLang.TAMIL -> "அடுத்து"; KaiLang.TANGLISH -> "Next"; KaiLang.ENGLISH -> "Next"
            }
            collections(list, ChatIntent.UPCOMING_COLLECTIONS, lang, day, label, overdue = emptyList())
        }
        ChatIntent.OVERDUE_COLLECTIONS -> {
            val list = s.overdue(day).filter { it.side == Direction.RECEIVABLE }
            if (list.isEmpty()) reply(q.intent, KaiMood.HAPPY, lang, pick3(lang,
                ta = listOf("தேதி தாண்டின வசூல் எதுவும் இல்ல ஓனர்."),
                tl = listOf("Date thaandina collection edhuvum illa owner.", "Owner, overdue edhuvum illa. Ellam on time."),
                en = listOf("No overdue collections, Owner."),
            )) else reply(q.intent, KaiMood.SERIOUS, lang, when (lang) {
                KaiLang.TAMIL -> "ஓனர், ${list.size} வசூல் தேதி தாண்டிடுச்சு: " + list.take(3).joinToString(", ") { "${it.name} ${KaiFormat.rupees(it.pending)}" } + ". Follow up பண்ணுங்க."
                KaiLang.TANGLISH -> "Owner, ${list.size} payment date thaandiduchu: " + list.take(3).joinToString(", ") { "${it.name} ${KaiFormat.rupees(it.pending)}" } + ". Konjam follow up pannunga."
                KaiLang.ENGLISH -> "Owner, ${list.size} collection(s) are overdue: " + list.take(3).joinToString(", ") { "${it.name} ${KaiFormat.rupees(it.pending)}" } + ". Please follow up."
            })
        }
        ChatIntent.TOTAL_RECEIVABLE -> {
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
            if (list.isEmpty()) reply(q.intent, KaiMood.NEUTRAL, lang, when (lang) {
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
            if (list.isEmpty()) reply(q.intent, KaiMood.HAPPY, lang, pick3(lang,
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
        val top = list.take(3).joinToString(", ") { "${it.name} ${KaiFormat.rupees(it.pending)}${dueSuffix(it, lang, day)}" }
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

    private data class Due(val sentence: String)

    private fun dueText(date: LocalDate, lang: KaiLang, day: LocalDate): Due = Due(
        when (lang) {
            KaiLang.TAMIL -> if (date.isBefore(day)) "${KaiFormat.date(date, lang, day)} due — தேதி தாண்டிடுச்சு." else "${KaiFormat.date(date, lang, day)} due."
            KaiLang.TANGLISH -> if (date.isBefore(day)) "${KaiFormat.date(date, lang, day)} due — date thaandiduchu." else "${KaiFormat.date(date, lang, day)} due."
            KaiLang.ENGLISH -> if (date.isBefore(day)) "Due ${KaiFormat.date(date, lang, day)} — overdue." else "Due ${KaiFormat.date(date, lang, day)}."
        },
    )

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
    }

    private fun monthOf(day: LocalDate) = day.withDayOfMonth(1).let { ChatPeriod(it, it.plusMonths(1).minusDays(1), ChatPeriod.Kind.THIS_MONTH) }

    /** The owner's answer to "which one?": a name, or "first"/"second"/"1"/"2". */
    private fun pick(text: String, options: List<PartyFacts>): PartyFacts? {
        val lower = text.lowercase(Locale.ROOT)
        val ordinal = when {
            Regex("""\b(1|first|mudhal|modhal|onnu|oru)\b""").containsMatchIn(lower) -> 0
            Regex("""\b(2|second|rendavadhu|rendu|irandu)\b""").containsMatchIn(lower) -> 1
            Regex("""\b(3|third|moonavadhu|moonu)\b""").containsMatchIn(lower) -> 2
            else -> null
        }
        if (ordinal != null) return options.getOrNull(ordinal)
        options.filter { lower.contains(it.name.lowercase(Locale.ROOT)) }.maxByOrNull { it.name.length }?.let { return it }
        // By phone digits said.
        return null
    }

    private fun whichOne(name: String, matches: List<PartyFacts>, lang: KaiLang): String {
        val list = matches.take(3).mapIndexed { i, p ->
            "${i + 1}. ${p.name}" + (if (p.side == Direction.PAYABLE) " (supplier)" else "") + " — ${KaiFormat.rupees(p.pending)}"
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
    private fun chatLanguage(text: String): KaiLang {
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
    }
}
