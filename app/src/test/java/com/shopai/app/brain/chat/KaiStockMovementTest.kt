package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.ContactMatch
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PartyRole
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ProductRef
import com.shopai.app.brain.tools.StockFact
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random


/**
 * "Today stock evlo iruku", "Yentha stock fast move aguthu", "Yentha stock move agala", "Innaiku yarukita payment vanganum":
 * the stock on hand and which products sell or sit, read from the inventory and the item-wise sales records — never guessed.
 */
class KaiStockMovementTest {
    private val now = LocalDateTime.of(2026, 10, 8, 11, 0)
    private val today: LocalDate = now.toLocalDate()
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

    // ------------------------------------------------------------------ the ledger

    private class Ledger {
        class Entry(val amount: BigDecimal, val created: LocalDate, val due: LocalDate?) {
            val payments = mutableListOf<Pair<BigDecimal, LocalDate>>()
            val paid: BigDecimal get() = payments.fold(BigDecimal.ZERO) { t, p -> t + p.first }
            val open: BigDecimal get() = amount - paid
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

    /**
     * The books as Kai reads them. [cached]: like the app's Business Memory, the snapshot is kept until [changed] —
     * a save that isn't followed by [changed] would leave the old balance. [frozen]: the books never show new saves.
     */
    private class Books(val l: Ledger, val cached: Boolean = false, var frozen: Boolean = false) : KaiBooks {
        private var copy: BusinessSnapshot? = null
        private var frozenCopy: BusinessSnapshot? = null
        var changes = 0
        override suspend fun snapshot(): BusinessSnapshot {
            if (frozen) return frozenCopy ?: l.snapshot().also { frozenCopy = it }
            if (!cached) return l.snapshot()
            return copy ?: l.snapshot().also { copy = it }
        }
        override fun changed() { changes++; copy = null }
        override suspend fun history(party: PartyFacts): PartyHistory? = l.parties.firstOrNull { it.id == party.id }?.let { p ->
            PartyHistory(party, p.entries.map { e ->
                PartyHistory.LedgerEntry(e.amount.toDouble(), e.paid.toDouble(), e.due, e.created, e.payments.map { PartyHistory.Payment(it.first.toDouble(), it.second) })
            })
        }
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    /** Drafts are recorded; only [confirm] writes, through to the ledger (an entry, or a payment against the oldest dues). */
    private class Tools(val l: Ledger, val today: LocalDate) : KaiTools {
        val prepared = mutableListOf<ActionPlan>()
        val saved = mutableListOf<ActionPlan>()
        var fail: String? = null
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
                ?: l.parties.firstOrNull { it.customer == customer && it.name.equals(plan.partyName, true) }
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
        override fun zone() = "Asia/Kolkata"
        val rems = mutableListOf<com.shopai.app.brain.tools.KaiReminder>()
        override fun createReminder(reminder: com.shopai.app.brain.tools.KaiReminder): com.shopai.app.brain.tools.ReminderSaved {
            rems += reminder; return com.shopai.app.brain.tools.ReminderSaved(reminder, false, com.shopai.app.brain.tools.ScheduleResult.EXACT)
        }
        override fun reminders() = rems.toList()
        override suspend fun contacts(name: String, role: PartyRole?) = emptyList<ContactMatch>()
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?) = "K-1"
        val items = listOf(StockFact("Colgate", BigDecimal("20"), "PCS", reorderAt = BigDecimal("25")), StockFact("Ponni Arisi", BigDecimal("150"), "KG", reorderAt = BigDecimal("50")),
            StockFact("Sunflower Oil", BigDecimal("12"), "LTR", reorderAt = BigDecimal("10")), StockFact("Lux Soap", BigDecimal("60"), "PCS", reorderAt = BigDecimal("20")),
            StockFact("Horlicks", BigDecimal("8"), "PCS", reorderAt = BigDecimal("5")), StockFact("Maggi", BigDecimal("0"), "PCS", reorderAt = BigDecimal("10")))
        override suspend fun products() = items.mapIndexed { i, f -> ProductRef("p$i", f.name, f.unit, f.qty) }
        override suspend fun stock(product: String?) = if (product == null) items else items.filter { it.name.contains(product, true) }
        override suspend fun lowStock() = items.filter { it.qty <= (it.reorderAt ?: BigDecimal.ZERO) }
        var noSales = false
        var asked: Pair<LocalDate, LocalDate>? = null
        override suspend fun topProducts(from: LocalDate, to: LocalDate, limit: Int) = (if (noSales) emptyList() else listOf(
            com.shopai.app.brain.tools.ProductSalesFact("Ponni Arisi", BigDecimal("320"), BigDecimal("17600")),
            com.shopai.app.brain.tools.ProductSalesFact("Colgate", BigDecimal("90"), BigDecimal("4500")),
            com.shopai.app.brain.tools.ProductSalesFact("Maggi", BigDecimal("60"), BigDecimal("840")),
            com.shopai.app.brain.tools.ProductSalesFact("Sunflower Oil", BigDecimal("5"), BigDecimal("900")))).take(limit).also { asked = from to to }
    }

    /**
     * Today is Thursday 8 Oct 2026. Customers: Kumar (paid 10 and 6 days late; ₹4,000 due today) · Ramesh (always on time;
     * ₹2,500 due today) · Selvam (paid 19 days late; ₹6,000 now 18 days overdue) · Lakshmi (on time; ₹1,000 due 12 Oct) ·
     * Priya (₹800 with no due date — no history to judge). Suppliers: ABC Traders ₹10,000 due today · Murugan Stores ₹4,000 due 15 Oct.
     */

    private fun ledger() = Ledger().apply {
        // Kumar: late twice (paid 10 and 6 days after due), ₹4,000 due today.
        add("c1", "Kumar", true, "5000", d(9, 1), d(9, 15)).payments += BigDecimal("5000") to d(9, 25)
        add("c1", "Kumar", true, "3000", d(9, 20), d(9, 30)).payments += BigDecimal("3000") to d(10, 6)
        add("c1", "Kumar", true, "4000", d(10, 1), d(10, 8))
        // Ramesh: always on time, ₹2,500 due today.
        add("c2", "Ramesh", true, "2000", d(8, 20), d(9, 1)).payments += BigDecimal("2000") to d(9, 1)
        add("c2", "Ramesh", true, "1500", d(9, 10), d(9, 20)).payments += BigDecimal("1500") to d(9, 18)
        add("c2", "Ramesh", true, "2500", d(9, 28), d(10, 8))
        // Selvam: 18 days overdue; paid late before.
        add("c3", "Selvam", true, "3000", d(7, 20), d(8, 1)).payments += BigDecimal("3000") to d(8, 20)
        add("c3", "Selvam", true, "6000", d(9, 5), d(9, 20))
        // Lakshmi: due 12 Oct, paid on time before.
        add("c4", "Lakshmi", true, "1200", d(8, 1), d(8, 10)).payments += BigDecimal("1200") to d(8, 10)
        add("c4", "Lakshmi", true, "1000", d(10, 2), d(10, 12))
        // Priya: a bill with no due date — nothing to say about on time / late.
        add("c5", "Priya", true, "800", d(9, 20), null)
        // Suppliers the owner pays.
        add("s1", "ABC Traders", false, "10000", d(9, 25), d(10, 8))
        add("s2", "Murugan Stores", false, "4000", d(10, 1), d(10, 15))
    }

    private inner class Shop(val l: Ledger = ledger(), cached: Boolean = false) {
        val books = Books(l, cached)
        val tools = Tools(l, today)
        fun kai() = KaiAgent(KaiBusinessBrain(books, today = { today }, random = Random(1)), books, tools, now = { now })
        var k = kai()
        fun turn(text: String): KaiTurn = runBlocking { k.ask(text) }
    }

    private fun Shop.say(text: String): String = turn(text).reply.text
    private fun lines(t: KaiTurn) = t.card?.lines.orEmpty()

    @Test fun whoToCollectFromTodayIsNotAReminder() {
        val s = Shop()
        val text = s.say("Innaiku yarukita payment vanganum")
        assertTrue(text, text.contains("Kumar ₹4,000") && text.contains("Ramesh ₹2,500"))
        assertFalse(text, text.contains("remind") || text.contains("Yarukita"))
        assertTrue(s.tools.rems.isEmpty())
    }

    @Test fun todaysStockIsEveryProductNotAProductCalledToday() {
        val t = Shop().turn("Today stock evlo iruku")
        assertFalse(t.reply.text, t.reply.text.contains("Today"))
        assertEquals(listOf("Colgate — 20 PCS", "Ponni Arisi — 150 KG", "Sunflower Oil — 12 LTR", "Lux Soap — 60 PCS", "Horlicks — 8 PCS", "Maggi — 0 PCS"), lines(t))
        assertEquals(lines(t), lines(Shop().turn("Stock evlo irukku")))
        // One product still answers for that product.
        assertEquals("Owner, Colgate stock 20 PCS irukku.", Shop().say("Colgate stock evlo iruku"))
    }

    @Test fun fastMovingIsTheLast30DaysOfItemSales() {
        val s = Shop()
        val t = s.turn("Yentha stock fast move aguthu")
        assertEquals("Owner, kadandha 30 naal-la fast-aa move aanadhu Ponni Arisi — 320 KG — ₹17,600.", t.reply.text)
        assertEquals("1. Ponni Arisi — 320 KG — ₹17,600", lines(t).first())
        assertEquals(d(9, 9) to d(10, 8), s.tools.asked)
        for (q in listOf("Endha product fast-a vikkudhu", "Fast moving items")) assertTrue(q, Shop().say(q).contains("Ponni Arisi"))
        // "Indha maasam adhigama vithuchu": this month's best seller — not the highest pending customer.
        val month = Shop()
        val best = month.say("Indha maasam enna adhigama vithuchu")
        assertEquals("Owner, adhigama vithadhu Ponni Arisi — ₹17,600.", best)
        assertEquals(d(10, 1), month.tools.asked!!.first)
    }

    @Test fun notMovingIsStockOnHandWithNoSale() {
        val t = Shop().turn("Yentha stock move agala")
        assertEquals("Owner, sales bill padi kadandha 30 naal-la 2 product onnu kooda vikkala (stock-la irukku):", t.reply.text)
        // Lux Soap and Horlicks have stock and no sale; Maggi sold (and has none left), so it is not "not moving".
        assertEquals(listOf("Lux Soap — 60 PCS", "Horlicks — 8 PCS"), lines(t))
        for (q in listOf("Endha item vikkala", "Slow moving stock enna", "Dead stock list")) assertEquals(q, lines(t), lines(Shop().turn(q)))
    }

    @Test fun withNoItemSalesKaiSaysItCannotTell() {
        val s = Shop()
        s.tools.noSales = true
        val text = s.say("Yentha stock move agala")
        assertTrue(text, text.contains("solla mudiyadhu"))
        assertTrue(lines(s.turn("Yentha stock move agala")).isEmpty())
    }

    @Test fun everydayWordsStayWhatTheyWere() {
        val now = LocalDateTime.of(2026, 10, 8, 11, 0)
        fun route(t: String) = com.shopai.app.brain.tools.KaiCommands.route(t, now, listOf("Kumar"))
        assertEquals(com.shopai.app.brain.tools.KaiCommand.SlowStock, route("Yentha stock move agala"))
        assertEquals(com.shopai.app.brain.tools.KaiCommand.TopProducts, route("Yentha stock fast move aguthu"))
        assertEquals(com.shopai.app.brain.tools.KaiCommand.Stock(null), route("Today stock evlo iruku"))
        // "pogala" / "fast-a" without a stock or item word are not about the shelf.
        assertFalse(route("Kumar payment innum pogala") is com.shopai.app.brain.tools.KaiCommand.SlowStock)
        assertFalse(route("fast-a kadaiku poganum") == com.shopai.app.brain.tools.KaiCommand.TopProducts)
    }
}
