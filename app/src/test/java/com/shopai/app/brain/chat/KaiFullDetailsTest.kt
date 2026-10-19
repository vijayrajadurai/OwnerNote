package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.memory.InMemoryKaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.memory.MemoryType
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.ContactMatch
import com.shopai.app.brain.tools.ContactSource
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PartyRole
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ProductRef
import com.shopai.app.brain.tools.ReminderSaved
import com.shopai.app.brain.tools.ScheduleResult
import com.shopai.app.brain.tools.StockFact
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Everything said at once (owner, 19 Oct 2026: "Sarath 5000 tharanum December 5 nu sonnalum kai due date yeppa nu varathu … thevayana
 * details kedacha kai draft katti confirm anathum task ah mudikanum"): a Credit / Debit with its due date in any form — "December
 * 5", "Dec 5th", "5/12/2026", "டிசம்பர் 5ஆம் தேதிக்குள்", "adutha maasam 5", "20th tharuvan", "due naalai" — goes straight to
 * the draft with that date and saves it on Confirm; an amount owed with a day is an entry, never a reminder asked its time; the
 * same for payments with their mode, reminders with their time, stock with its details, and a due date answered in Tamil.
 * Today is Thursday 8 Oct 2026; nothing is written before Confirm.
 */
class KaiFullDetailsTest {
    private val now = LocalDateTime.of(2026, 10, 8, 11, 0)
    private val today: LocalDate = now.toLocalDate()
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)
    private fun millis(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    // ------------------------------------------------------------------ the shop

    private class Ledger {
        class Entry(val amount: BigDecimal, val created: LocalDate, val due: LocalDate?) {
            val payments = mutableListOf<Pair<BigDecimal, LocalDate>>()
            val open: BigDecimal get() = amount - payments.fold(BigDecimal.ZERO) { t, p -> t + p.first }
        }
        class Party(val id: String, val name: String, val customer: Boolean, val city: String? = null) {
            val entries = mutableListOf<Entry>()
            val pending: BigDecimal get() = entries.fold(BigDecimal.ZERO) { t, e -> t + e.open }
            val nextDue: LocalDate? get() = entries.filter { it.open.signum() > 0 }.minOfOrNull { it.due ?: it.created }
        }
        val parties = mutableListOf<Party>()
        fun party(id: String) = parties.first { it.id == id }
        fun add(id: String, name: String, customer: Boolean, amount: String, created: LocalDate, due: LocalDate?, city: String? = null): Entry {
            val p = parties.firstOrNull { it.id == id } ?: Party(id, name, customer, city).also { parties += it }
            return Entry(BigDecimal(amount), created, due).also { p.entries += it }
        }
        fun summary(p: Party) = PartySummary(p.id, p.name, null, p.pending.toDouble(), p.nextDue?.let { "${it}T00:00:00Z" })
        fun snapshot() = BusinessSnapshot(customers = parties.filter { it.customer }.map(::summary), suppliers = parties.filter { !it.customer }.map(::summary))
    }

    private class Books(val l: Ledger, var frozen: Boolean = false) : KaiBooks {
        private var frozenCopy: BusinessSnapshot? = null
        override suspend fun snapshot(): BusinessSnapshot = if (frozen) frozenCopy ?: l.snapshot().also { frozenCopy = it } else l.snapshot()
        override suspend fun history(party: PartyFacts): PartyHistory? = l.parties.firstOrNull { it.id == party.id }?.let { p ->
            PartyHistory(party, p.entries.map { e ->
                PartyHistory.LedgerEntry(e.amount.toDouble(), (e.amount - e.open).toDouble(), e.due, e.created, e.payments.map { PartyHistory.Payment(it.first.toDouble(), it.second) })
            })
        }
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(val l: Ledger, val today: LocalDate) : KaiTools {
        val prepared = mutableListOf<ActionPlan>()
        val saved = mutableListOf<ActionPlan>()
        var fail: String? = null
        val stockChanges = mutableListOf<Triple<String, BigDecimal, Boolean>>()
        val reminders = mutableListOf<KaiReminder>()
        /** The reminder store loses what it was given (an engine that says "scheduled" but did not keep it). */
        var reminderLost = false
        val productList = mutableListOf(
            ProductRef("p1", "Colgate", "PCS", BigDecimal("20"), conversions = mapOf("BOX" to BigDecimal("12"), "CARTON" to BigDecimal("48"))),
            ProductRef("p2", "Rice", "KG", BigDecimal("45.5")),
            ProductRef("p3", "Sugar", "KG", BigDecimal("10")),
            ProductRef("p4", "Soap", "PCS", BigDecimal("12")),
        )
        override suspend fun parties(name: String) = l.parties.filter { it.name.contains(name, true) }
            .map { PartyMatch(it.id, it.name, it.customer, null, it.pending, city = it.city) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String): ActionPlan {
            val before = l.parties.firstOrNull { it.id == partyId }?.pending
            return ActionPlan("p${prepared.size}", kind, partyName, partyId, amount, mode, balanceBefore = before, said = said).also { prepared += it }
        }
        override suspend fun confirm(plan: ActionPlan): ActionOutcome {
            fail?.let { return ActionOutcome.Failed(it) }
            val customer = plan.kind == PlanKind.CREDIT_GIVEN || plan.kind == PlanKind.PAYMENT_IN
            val p = l.parties.firstOrNull { it.id == plan.partyId }
                ?: Ledger.Party("n${l.parties.size}", plan.partyName, customer).also { l.parties += it }
            when (plan.kind) {
                PlanKind.CREDIT_GIVEN, PlanKind.DEBIT_TAKEN -> p.entries += Ledger.Entry(plan.amount, today, plan.dueDate)
                else -> {
                    var left = plan.amount
                    for (e in p.entries.sortedBy { it.due ?: it.created }) {
                        if (left.signum() <= 0) break
                        val take = left.min(e.open)
                        if (take.signum() > 0) { e.payments += take to today; left -= take }
                    }
                }
            }
            saved += plan
            return ActionOutcome.Done("TXN-${saved.size}", p.pending)
        }
        override suspend fun products() = productList
        override suspend fun stock(product: String?) = productList.filter { product == null || it.name.equals(product, true) }
            .map { StockFact(it.name, it.stock, it.unit, reorderAt = BigDecimal("5")) }
        override suspend fun changeStock(product: ProductRef, qty: BigDecimal, incoming: Boolean, said: String): ActionOutcome {
            stockChanges += Triple(product.id, qty, incoming)
            return ActionOutcome.Done("ST-${stockChanges.size}", null)
        }
        override fun createReminder(reminder: KaiReminder): ReminderSaved {
            if (!reminderLost) reminders += reminder
            return ReminderSaved(reminder, false, ScheduleResult.EXACT)
        }
        override fun reminderStored(id: String): Boolean = reminders.any { it.id == id }
        override fun reminders() = reminders.filter { it.open }
        override fun zone() = "Asia/Kolkata"
        /** Like AppKaiTools: the books' people with that name (exact name first), then the phone's contacts. */
        override suspend fun contacts(name: String, role: PartyRole?): List<ContactMatch> {
            val fromBooks = l.parties.filter { it.name.contains(name, true) && (role == null || it.customer == (role == PartyRole.CUSTOMER)) }
                .map { ContactMatch(it.id, it.name, "+9190000" + it.id.filter(Char::isDigit).padStart(5, '0'), if (it.customer) ContactSource.CUSTOMER else ContactSource.SUPPLIER) }
            val exact = fromBooks.filter { it.name.equals(name, true) }
            // As AppKaiTools: "Lokesh" also brings "Madurai Lokesh" when a plain Lokesh exists.
            if (exact.isNotEmpty()) return exact + fromBooks.filter { c -> c !in exact && c.name.split(' ').any { it.equals(name, true) } }
            if (fromBooks.isNotEmpty()) return fromBooks
            return listOf(ContactMatch("phone:1", "Praba", "+919000000011", ContactSource.PHONE), ContactMatch("phone:2", "Ruthran", "+919000000012", ContactSource.PHONE))
                .filter { it.name.equals(name, true) }
        }
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?) = "K-1"
    }

    private class Access(val tools: Tools, store: InMemoryKaiMemoryStore, val business: String = "biz-A") : KaiMemoryAccess {
        val memory = KaiPrivateMemory(store, clock = { 1_000L })
        override suspend fun current(): KaiPrivateMemory = memory.also { it.open(business, "owner-A") }
        override suspend fun entities(): List<KnownEntity> =
            tools.productList.map { KnownEntity(it.id, it.name, MemoryType.PRODUCT_ALIAS) } +
                tools.l.parties.map { KnownEntity(it.id, it.name, if (it.customer) MemoryType.CUSTOMER_ALIAS else MemoryType.SUPPLIER_ALIAS) }
    }

    /**
     * Customers: Kumar ₹3,000 open (₹5,000 − ₹2,000) due today · Selvi ₹4,200 due today · Lokesh (Chennai) ₹2,000 due
     * 20 Oct · Lokesh (Nagapattinam) ₹700 due 15 Oct · Ravi ₹1,500 overdue (25 Sep) · Mani ₹4,773.10 overdue (1 Oct) ·
     * Priya ₹800 no due date · Arun settled.  Suppliers: Ramesh ₹2,500 due today · Basha ₹8,000 overdue (30 Sep).
     */
    private fun ledger() = Ledger().apply {
        add("c1", "Kumar", true, "5000", d(9, 1), d(10, 8)).payments += BigDecimal("2000") to d(10, 3)
        add("c2", "Lokesh", true, "2000", d(9, 10), d(10, 20), city = "Chennai")
        add("c3", "Selvi", true, "4200", d(9, 15), d(10, 8))
        add("c4", "Ravi", true, "1500", d(9, 1), d(9, 25))
        add("c5", "Priya", true, "800", d(9, 20), null)
        add("c6", "Mani", true, "4773.10", d(9, 5), d(10, 1))
        add("c7", "Arun", true, "1000", d(8, 1), d(8, 30)).payments += BigDecimal("1000") to d(10, 5)
        add("c8", "Lokesh", true, "700", d(9, 12), d(10, 15), city = "Nagapattinam")
        add("s1", "Ramesh", false, "2500", d(9, 20), d(10, 8))
        add("s2", "Basha", false, "9000", d(9, 1), d(9, 30)).payments += BigDecimal("1000") to d(10, 6)
    }

    private inner class Shop(val store: InMemoryKaiMemoryStore = InMemoryKaiMemoryStore(), val l: Ledger = ledger()) {
        val books = Books(l)
        val tools = Tools(l, today)
        private fun kai(business: String = "biz-A") =
            KaiAgent(KaiBusinessBrain(books, today = { today }, random = Random(1)), books, tools, now = { now }, memory = Access(tools, store, business))
        var k = kai()
        var last: KaiTurn? = null
        fun turn(text: String): KaiTurn = runBlocking { k.ask(text) }.also { last = it }
        fun say(text: String): String = turn(text).reply.text
        fun chat(vararg lines: String): String = lines.map { say(it) }.last()
        fun restart() { k = kai() }
        fun otherBusiness() { k = kai("biz-B") }
        fun tap(label: String): KaiTurn? = last?.card?.buttons?.firstOrNull { it.label == label }?.let { b -> runBlocking { k.act(b.action, com.shopai.app.brain.KaiLang.TANGLISH) } }?.also { last = it }
        fun balance(id: String): String = l.party(id).pending.stripTrailingZeros().toPlainString()
        /** The last draft Kai prepared (kind / party / amount / due), or null. */
        fun draft(): ActionPlan? = tools.prepared.lastOrNull()
        /** Say [text]; a receivable / payable statement asks the due date first — "save panniko" then makes the draft. */
        fun drafted(text: String): ActionPlan? {
            val before = tools.prepared.size
            say(text)
            if (tools.prepared.size == before && last?.card == null) say("save panniko")
            return tools.prepared.drop(before).lastOrNull()
        }
    }
    private fun d2(m: Int, day: Int) = LocalDate.of(2026, m, day)
    private data class C(val line: String, val kind: PlanKind, val who: String?, val amount: String, val due: LocalDate?, val mode: PaymentMode? = null, val known: Boolean = false)
    private val cases = listOf(
        C("Sarath 5000 December 5 tharanum", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5)),
        C("December 5 Sarath 5000 tharanum", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5)),
        C("Sarath 5000 rupees tharanum December 5", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5)),
        C("Sarath 5000 tharanum December 5 ku", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5)),
        C("Sarath 5000 tharanum December 5 kulla", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5)),
        C("Sarath 5000 tharanum December 5 tharuvan", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5)),
        C("Sarath 5000 tharanum December 5", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5), known = true),
        C("சரத் 5000 தரணும் டிசம்பர் 5", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5), known = true),
        C("சரத் 5000 ரூபாய் தரணும் டிசம்பர் ஐந்தாம் தேதி", PlanKind.CREDIT_GIVEN, "சரத்", "5000", d2(12, 5)),
        C("சரத் 5000 தரணும் டிசம்பர் 5ஆம் தேதிக்குள்", PlanKind.CREDIT_GIVEN, "சரத்", "5000", d2(12, 5)),
        C("சரத் ஐயாயிரம் தரணும் டிசம்பர் அஞ்சு", PlanKind.CREDIT_GIVEN, "சரத்", "5000", d2(12, 5)),
        C("சரத் எனக்கு 5000 டிசம்பர் 5 தரணும்", PlanKind.CREDIT_GIVEN, "சரத்", "5000", d2(12, 5)),
        C("Sarath kitta 5000 vaanganum December 5", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5)),
        C("Sarath 5000 tharanum 5.12.2026", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5)),
        C("Sarath 5000 tharanum 05-12-2026", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5)),
        C("Sarath 5000 tharanum December 5", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5)),
        C("சரத் 5000 தரணும் டிசம்பர் 5", PlanKind.CREDIT_GIVEN, "சரத்", "5000", d2(12, 5)),
        C("Sarath enakku 5000 December 5 kulla tharanum", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5)),
        C("Sarath 5000 tharanum Dec 5th", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5)),
        C("Sarath 5000 tharanum 5/12/2026", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5)),
        C("Sarath 5000 tharanum 5 December", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5)),
        C("Sarath owes me 5000 by December 5", PlanKind.CREDIT_GIVEN, "Sarath", "5000", d2(12, 5)),
        C("Kumar 2000 naalaiku tharanum", PlanKind.CREDIT_GIVEN, "Kumar", "2000", d2(10, 9)),
        C("Kumar 2500 tharanum next Monday", PlanKind.CREDIT_GIVEN, "Kumar", "2500", d2(10, 12)),
        C("குமார் 2500 அடுத்த திங்கக்கிழமை தரணும்", PlanKind.CREDIT_GIVEN, "Kumar", "2500", d2(10, 12)),
        C("Kumar ku 1500 kadan kuduthen 20th tharuvan", PlanKind.CREDIT_GIVEN, "Kumar", "1500", d2(10, 20)),
        C("Kumar 2000 credit due date 15 November", PlanKind.CREDIT_GIVEN, "Kumar", "2000", d2(11, 15)),
        C("Selvi 800 baaki 10 thethi kulla tharuvaanga", PlanKind.CREDIT_GIVEN, "Selvi", "800", d2(10, 10)),
        C("Senthil 5000 kadan due naalai", PlanKind.CREDIT_GIVEN, "Senthil", "5000", d2(10, 9)),
        C("Senthil 5000 kadan, adutha maasam 5 tharuvan", PlanKind.CREDIT_GIVEN, "Senthil", "5000", d2(11, 5)),
        C("Ramesh ku 4000 kudukkanum 25 October", PlanKind.DEBIT_TAKEN, "Ramesh", "4000", d2(10, 25)),
        C("naan Basha ku 6000 tharanum next week", PlanKind.DEBIT_TAKEN, "Basha", "6000", null),
        C("Vasanth Traders kitta 5000 ku credit la maal vaanginen, 30th kudukkanum", PlanKind.DEBIT_TAKEN, "Vasanth Traders", "5000", d2(10, 30)),
        C("நான் ரமேஷுக்கு 4000 அக்டோபர் 25 குடுக்கணும்", PlanKind.DEBIT_TAKEN, "Ramesh", "4000", d2(10, 25)),
        C("Kumar 2000 GPay la kuduthan", PlanKind.PAYMENT_IN, "Kumar", "2000", null, PaymentMode.UPI),
        C("Basha ku 3000 cash ah kuduthen", PlanKind.PAYMENT_OUT, "Basha", "3000", null, PaymentMode.CASH),
        C("Selvi 1000 PhonePe la anuppinaanga", PlanKind.PAYMENT_IN, "Selvi", "1000", null, PaymentMode.UPI),
        C("Senthil 5000 kadan no due", PlanKind.CREDIT_GIVEN, "Senthil", "5000", null),
        C("Senthil 5000 kadan due date illa", PlanKind.CREDIT_GIVEN, "Senthil", "5000", null),
    )

    @Test fun creditDebitAndPaymentsWithEveryDetail() {
        val fails = mutableListOf<String>()
        for (c in cases) {
            val s = Shop()
            if (c.known) s.l.add("c9", "Sarath", true, "1000", d2(9, 1), d2(9, 30))
            val t = s.turn(c.line)
            val confirm = t.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmPlan }
            var why = ""
            if (confirm == null) why = "no draft on the first reply"
            else {
                s.tap(confirm.label)
                val p = s.tools.saved.singleOrNull()
                why = when {
                    p == null -> "not saved"
                    p.kind != c.kind -> "kind ${p.kind}"
                    c.who != null && !p.partyName.contains(c.who, true) && !com.shopai.app.util.NameSound.same(p.partyName, c.who) -> "party ${p.partyName}"
                    p.amount.compareTo(BigDecimal(c.amount)) != 0 -> "amount ${p.amount}"
                    c.due != null && p.dueDate != c.due -> "due ${p.dueDate}"
                    c.mode != null && p.mode != c.mode -> "mode ${p.mode}"
                    else -> ""
                }
            }
            if (why.isNotEmpty()) fails += "«${c.line}» $why → ${(t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" | ")).take(300)}"
            if (why.isEmpty() && s.tools.saved.size != 1) fails += "«${c.line}» saved ${s.tools.saved.size}"
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun remindersStockAndDueAnswersWithEveryDetail() {
        val fails = mutableListOf<String>()
        val lines = listOf(
            listOf("naalaiku kaalai 10 manikku Kumar ku call pannanum remind pannu"),
            listOf("நாளைக்கு காலை 10 மணிக்கு குமாருக்கு கால் பண்ணணும் ரிமைண்ட் பண்ணு"),
            listOf("December 5 kaalai 9 manikku Sarath kitta 5000 vaanga remind pannu"),
            listOf("ovvoru Monday kaalai 9 manikku stock check panna remind pannu"),
            listOf("innaiku saayangaalam 6 manikku current bill katta remind pannu"),
            listOf("Colgate 2 box vandhuchu"),
            listOf("Rice 5 kg vithuchu"),
            listOf("Pepsodent 5 box vandhuchu, boxku 12 pieces, purchase 40, selling 50"),
            listOf("Hamam soap 10 pieces vandhuchu purchase 30 selling 38"),
            listOf("Sarath 5000 tharanum", "5/12/2026"),
            listOf("Sarath 5000 tharanum", "டிசம்பர் 5ஆம் தேதி"),
            listOf("சரத் 5000 தரணும்", "டிசம்பர் 5ஆம் தேதிக்குள்"),
            listOf("Sarath 5000 tharanum", "December 5"),
            listOf("Sarath 5000 tharanum", "december 5-aam thethi"),
            listOf("Sarath 5000 tharanum", "டிசம்பர் 5"),
            listOf("Sarath 5000 tharanum", "december 5 thethi"),
            listOf("Sarath 5000 tharanum", "10 thethi", "indha maasam"),
        )
        for (l in lines) {
            val s = Shop()
            var t = s.turn(l[0])
            for (x in l.drop(1)) t = s.turn(x)
            val ok = t.card?.buttons?.any { b -> b.action is KaiAction.ConfirmPlan || b.action is KaiAction.ConfirmReminder || b.action is KaiAction.ConfirmStock } == true
            if (!ok) fails += "«${l.joinToString(" → ")}» no draft → ${(t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" | ")).take(300)}"
            if (s.tools.saved.isNotEmpty() || s.tools.stockChanges.isNotEmpty() || s.tools.reminders.isNotEmpty()) fails += "«${l.joinToString(" → ")}» written before Confirm"
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }
}
