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
 * "Kumar yeanuku 5000 pay pannitan / kuduthan / full settlement / full ah kuduthutan" (owner, 19 Oct 2026), typed and as the mic writes
 * it: money in from Kumar — the amount said, or the whole pending when it was paid in full ("full settlement", "ellam clear",
 * "kanakku mudichitaan", "முழுசா குடுத்துட்டான்"); "எனக்கு … குடுத்துட்டான்" is Kumar giving the owner, never a Credit. And a
 * second entry for the same person — Credit, Debit, a stated due, a payment, even the same amount the same day — is drafted and
 * saved on Confirm (the same payment already saved today is said on the draft, never refused). Nothing is written before Confirm.
 */
class KaiPaidAndSecondEntryTest {
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
            // (as the books engine: the same payment already saved today is said on the draft)
            val sameDay = (kind == PlanKind.PAYMENT_IN || kind == PlanKind.PAYMENT_OUT) && saved.any { it.kind == kind && it.partyId == partyId && it.amount.compareTo(amount) == 0 && it.mode == mode }
            return ActionPlan("p${prepared.size}", kind, partyName, partyId, amount, mode, balanceBefore = before, said = said, sameDayRepeat = sameDay).also { prepared += it }
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
    private fun conf(s: Shop): Boolean { val b = s.last?.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmPlan } ?: return false; s.tap(b.label); return true }

    @Test fun paidSaidEveryWay() {
        val fails = mutableListOf<String>()
        val lines = listOf("Kumar enakku 5000 pay pannitan" to "5000", "Kumar 5000 kuduthan" to "5000", "Kumar enakku 5000 kuduthan" to "5000", "Kumar 5000 pay pannitaan" to "5000",
            "Kumar full settlement pannitan" to "3000", "Kumar full ah kuduthutan" to "3000", "Kumar full settle pannitaan" to "3000", "Kumar motham kuduthutan" to "3000",
            "Kumar ellam clear pannitan" to "3000", "Kumar kanakku mudichitaan" to "3000", "Kumar full payment pannitan" to "3000", "Kumar settlement kuduthutan" to "3000",
            "Kumar full amount kuduthutan" to "3000", "Kumar baaki ellam kuduthutan" to "3000", "Kumar yeanuku 5000 pay pannitan" to "5000", "Kumar yeanuku 5000 kuduthutan" to "5000", "Kumar enakku 5000 kuduthutaan" to "5000", "Kumar muzhusa kuduthutan" to "3000",
            "குமார் எனக்கு 5000 பே பண்ணிட்டான்" to "5000", "குமார் 5000 குடுத்தான்" to "5000", "குமார் எனக்கு 5000 குடுத்துட்டான்" to "5000",
            "குமார் ஃபுல் செட்டில்மென்ட் பண்ணிட்டான்" to "3000", "குமார் ஃபுல்லா குடுத்துட்டான்" to "3000", "குமார் முழுசா குடுத்துட்டான்" to "3000",
            "குமார் ஃபுல் அமௌண்ட் குடுத்துட்டான்" to "3000", "குமார் மொத்தமா குடுத்துட்டான்" to "3000", "குமார் கணக்கு முடிச்சிட்டான்" to "3000", "குமார் செட்டில் பண்ணிட்டான்" to "3000")
        for ((l, a) in lines) {
            val s = Shop()
            val t = s.turn(l)
            val ok = conf(s) && s.tools.saved.singleOrNull()?.let { it.kind == PlanKind.PAYMENT_IN && it.partyId == "c1" && it.amount.compareTo(BigDecimal(a)) == 0 } == true
            if (!ok) fails += "«$l» → ${s.tools.saved.joinToString { "${it.kind} ${it.partyName} ${it.amount}" }} | ${t.reply.text.take(200)}"
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun aSecondEntryForTheSamePerson() {
        val fails = mutableListOf<String>()
        val flows = listOf(
            listOf("Kumar ku 2000 credit", "Kumar ku 1000 credit"),
            listOf("Kumar ku 2000 credit", "Kumar ku 2000 credit"),
            listOf("Kumar ku 2000 kadan kuduthen", "Kumar ku 500 kadan kuduthen"),
            listOf("Kumar enakku 2000 tharanum naalaiku", "Kumar enakku 1500 tharanum next week"),
            listOf("Kumar enakku 2000 tharanum naalaiku", "Kumar enakku 2000 tharanum next week"),
            listOf("Senthil 500 kadan", "Senthil 300 kadan"),
            listOf("Senthil 500 kadan", "Senthil 500 kadan"),
            listOf("Ramesh kitta 3000 credit la maal vaanginen", "Ramesh kitta 2000 credit la maal vaanginen"),
            listOf("Ramesh kitta 3000 credit la maal vaanginen", "Ramesh kitta 3000 credit la maal vaanginen"),
            listOf("naan Basha ku 4000 tharanum naalaiku", "naan Basha ku 1000 tharanum next week"),
            listOf("Kumar 1000 kuduthan", "Kumar 500 kuduthan"),
            listOf("Kumar 1000 kuduthan", "Kumar 1000 kuduthan"),
            listOf("குமாருக்கு 2000 கடன் குடுத்தேன்", "குமாருக்கு 1000 கடன் குடுத்தேன்"),
            listOf("குமாருக்கு 2000 கடன் குடுத்தேன்", "குமாருக்கு 2000 கடன் குடுத்தேன்"),
            listOf("Kumar yeanuku 1000 pay pannitan", "Kumar yeanuku 1000 pay pannitan"),
            listOf("Kumar 1000 kuduthan", "RESTART", "Kumar 1000 kuduthan"),
            listOf("குமார் 1000 குடுத்தான்", "RESTART", "குமார் 1000 குடுத்தான்"),
            listOf("Basha ku 2000 cash ah kuduthen", "RESTART", "Basha ku 2000 cash ah kuduthen"),
        )
        for (f in flows) {
            val s = Shop()
            val log = StringBuilder()
            for (line in f) {
                if (line == "RESTART") { s.restart(); continue }
                var t = s.turn(line)
                log.append(" » ").append(line).append(" ⟶ ").append(t.reply.text.replace("\n", " / ").take(200))
                var tries = 0
                while (t.card?.buttons?.none { it.action is KaiAction.ConfirmPlan } != false && tries < 3) {
                    val txt = t.reply.text
                    val ans = when {
                        Regex("(?i)due date|eppa|எப்போ|எந்த தேதி").containsMatchIn(txt) -> "due venam"
                        Regex("(?i)pudhu|புது|innoru|இன்னொரு|again|marubadi").containsMatchIn(txt) -> "pudhu"
                        else -> null
                    } ?: break
                    t = s.turn(ans); tries++
                    log.append(" » ").append(ans).append(" ⟶ ").append(t.reply.text.replace("\n", " / ").take(200))
                }
                log.append(" {").append(t.card?.warning ?: "").append("}")
                if (t.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmPlan }?.enabled == false) { log.append(" [CONFIRM DISABLED]"); break }
                if (!conf(s)) { log.append(" [NO CONFIRM]"); break }
                log.append(" ⟶ [tap] ").append(s.last?.reply?.text?.take(120))
            }
            val ok = s.tools.saved.size == 2
            if (!ok) fails += "«${f.joinToString(" → ")}» saved ${s.tools.saved.size}:$log"
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }
}
