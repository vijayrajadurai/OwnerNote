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
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * The production test matrix: table-driven cases per category, each one a full conversation against an entry-level
 * fake ledger (entries + payments, balances aggregated from them), a reminder store that can be read back, an
 * inventory and the owner's private memory — the same KaiAgent the chat and the voice screen use.
 *
 * A JUnit method is one CATEGORY; every row in it is a separate case, run on a fresh shop, and every failing row is
 * reported (not just the first). The row counts per category are printed as "MATRIX <category> passed/total".
 * Nothing here is mocked inside Kai: only the books / tools / memory store at the edge are fakes, and they change
 * only on Confirm. Today is Thursday 8 October 2026, 11:00 (Asia/Kolkata).
 */
class KaiProductionMatrixTest {

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
        fun confirmReminder(): KaiTurn? = last?.let { t -> runBlocking { k.confirmIfAsked(t) } }
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

    // ------------------------------------------------------------------ the matrix runner

    private class Category(val name: String) {
        val failures = mutableListOf<String>()
        var total = 0
        fun case(id: String, block: () -> Unit) {
            total++
            try { block() } catch (e: Throwable) { failures += "[$name] $id → ${e.message ?: e}" }
        }
        fun done(expected: Int) {
            println("MATRIX $name ${total - failures.size}/$total")
            failures.forEach { println("MATRIX-FAIL $it") }
            assertEquals("$name case count", expected, total)
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        }
    }

    private fun check(ok: Boolean, what: () -> String) { if (!ok) throw AssertionError(what()) }
    private fun has(text: String?, vararg parts: String) = parts.forEach { p -> check(text?.contains(p) == true) { "'$p' not in: $text" } }
    private fun hasNot(text: String?, vararg parts: String) = parts.forEach { p -> check(text?.contains(p) != true) { "'$p' must not be in: $text" } }
    private val saveClaims = arrayOf("Save aagiduchu", "save pannitten", "add pannitten", "Saved, Owner", "சேமிச்சுட்டேன்")

    private fun plan(p: ActionPlan?, kind: PlanKind, id: String?, amount: String) {
        check(p != null) { "no draft" }
        check(p!!.kind == kind) { "kind ${p.kind} ≠ $kind" }
        check(p.partyId == id) { "party ${p.partyId} ≠ $id" }
        check(p.amount.compareTo(BigDecimal(amount)) == 0) { "amount ${p.amount} ≠ $amount" }
    }

    private val customers = listOf("Kumar" to "c1", "Selvi" to "c3", "Ravi" to "c4", "Priya" to "c5", "Mani" to "c6")
    private val suppliers = listOf("Ramesh" to "s1", "Basha" to "s2")

    // ================================================================== 1. payment language (100)

    @Test
    fun m01_paymentLanguage_customerPaidTheOwner() {
        val c = Category("payment-language")
        val phrasings = listOf(
            "{n} {a} kuduthutaan", "{n} {a} kuduthaan", "{n} {a} kuduthaanga", "{n} {a} kuduthutaru", "{n} {a} rupees kuduthaan",
            "{n} {a} anuppitaan", "{n} {a} GPay pannitaan", "{n} {a} UPI la anuppinaan", "{n} {a} cash kuduthaan", "{n} kitta {a} vaanginen",
            "{n} kitta irundhu {a} vandhuchu", "{n} {a} pay pannitaan", "{n} paid {a}", "{n} paid me {a}", "Received {a} from {n}",
            "Got {a} from {n}", "{n} {a} return pannitaan", "{n} kitta {a} collect pannitten", "{n} {a} kuduthuttan", "{n} {a} kuduthutaanga",
        )
        val amounts = listOf("500", "1000", "250", "300", "750")
        for (phrase in phrasings) for ((i, who) in customers.withIndex()) {
            val (n, id) = who
            val a = amounts[i]
            val text = phrase.replace("{n}", n).replace("{a}", a)
            c.case(text) {
                val s = Shop()
                val t = s.turn(text)
                check(s.draft() != null) { "no draft: ${t.reply.text}" }
                plan(s.draft(), PlanKind.PAYMENT_IN, id, a)
                check(s.tools.saved.isEmpty()) { "saved before Confirm" }
                hasNot(t.reply.text, *saveClaims)
            }
        }
        c.done(100)
    }

    // ================================================================== 2. payment direction (54)

    @Test
    fun m02_paymentDirection_receivablePayablePaidReceived() {
        val c = Category("payment-direction")
        // Customer owes the owner (receivable): a credit entry on that customer.
        val owes = listOf("{n} enaku {a} tharanum", "{n} enakku {a} kodukkanum", "{n} {a} baaki vechirukkaan", "{n} kitta {a} vaanganum")
        for (phrase in owes) for ((n, id) in customers) {
            val text = phrase.replace("{n}", n).replace("{a}", "600")
            c.case(text) { val s = Shop(); plan(s.drafted(text), PlanKind.CREDIT_GIVEN, id, "600"); check(s.tools.saved.isEmpty()) { "saved before Confirm" } }
        }
        // The owner owes a supplier (payable) / the owner paid a supplier.
        val payable = listOf("naan {n}-ku {a} kudukkanum", "{n}-ku {a} tharanum naan", "{n} kitta {a}-ku saamaan vaanginen")
        val paid = listOf("{n}-ku {a} kuduthen", "{n}-ku {a} pay pannitten", "{n}-ku {a} GPay pannen")
        for ((n, id) in suppliers) for (a in listOf("400", "900")) {
            for (phrase in payable) {
                val text = phrase.replace("{n}", n).replace("{a}", a)
                c.case(text) { val s = Shop(); plan(s.drafted(text), PlanKind.DEBIT_TAKEN, id, a) }
            }
            for (phrase in paid) {
                val text = phrase.replace("{n}", n).replace("{a}", a)
                c.case(text) { val s = Shop(); plan(s.drafted(text), PlanKind.PAYMENT_OUT, id, a) }
            }
        }
        // The same words, a customer and a supplier: "<name> {a} kuduthaan" is money IN from a customer only.
        for ((n, id) in suppliers) for (a in listOf("150", "350", "450", "550", "650")) {
            val text = "$n-ku $a kuduthen"
            c.case("supplier paid $text") { val s = Shop(); plan(s.drafted(text), PlanKind.PAYMENT_OUT, id, a) }
        }
        c.done(54)
    }

    // ================================================================== 3. entity (50)

    @Test
    fun m03_entity_sameNamesPlacesUnknownAndNearNames() {
        val c = Category("entity")
        val bare = listOf("Lokesh evlo tharanum?", "Lokesh balance evlo?", "Lokesh pending evlo?", "How much does Lokesh owe?", "Lokesh-oda balance sollu")
        for (q in bare) c.case("asks: $q") { has(Shop().say(q), "Chennai", "Nagapattinam") }
        val placed = listOf("Chennai Lokesh evlo tharanum?" to "₹2,000", "Lokesh Chennai balance evlo?" to "₹2,000", "Nagapattinam Lokesh evlo tharanum?" to "₹700",
            "Lokesh Nagapattinam pending evlo?" to "₹700", "Chennai Lokesh-oda balance sollu" to "₹2,000")
        for ((q, amount) in placed) c.case("place: $q") { has(Shop().say(q), amount) }
        for (q in bare) c.case("then pick: $q") { val s = Shop(); s.say(q); has(s.say("Nagapattinam"), "₹700") }
        for (q in bare) c.case("then pick Chennai: $q") { val s = Shop(); s.say(q); has(s.say("Chennai"), "₹2,000") }
        for (q in listOf("Kumaran evlo tharanum?", "Kumaran balance evlo?", "Kumaran pending evlo?", "How much does Kumaran owe?", "Kumaran-oda balance sollu"))
            c.case("near name: $q") { hasNot(Shop().say(q), "₹3,000") }
        for (q in listOf("Suresh evlo tharanum?", "Suresh balance evlo?", "Suresh pending evlo?", "How much does Suresh owe?", "Suresh-oda balance sollu"))
            c.case("unknown: $q") { val r = Shop().say(q); hasNot(r, "₹3,000", "₹4,200", "₹2,000") }
        for (a in listOf("100", "200", "300", "400", "500")) c.case("bare write asks: Lokesh $a") {
            val s = Shop(); s.say("Lokesh enaku $a tharanum"); s.say("seri"); check(s.tools.saved.isEmpty()) { "saved ${s.tools.saved}" }
        }
        for (a in listOf("100", "200", "300", "400", "500")) c.case("placed write: Chennai Lokesh $a") {
            val s = Shop(); plan(s.drafted("Chennai Lokesh enaku $a tharanum"), PlanKind.CREDIT_GIVEN, "c2", a)
        }
        for (a in listOf("110", "220", "330", "440", "550")) c.case("placed write: Nagapattinam Lokesh $a") {
            val s = Shop(); plan(s.drafted("Nagapattinam Lokesh enaku $a tharanum"), PlanKind.CREDIT_GIVEN, "c8", a)
        }
        for (q in listOf("kumar evlo tharanum?", "KUMAR evlo tharanum?", "kumar balance evlo", "Kumar  evlo  tharanum ?", "kumar pending evlo"))
            c.case("case/space: $q") { has(Shop().say(q), "₹3,000") }
        c.done(50)
    }

    // ================================================================== 4. ledger aggregation (50)

    @Test
    fun m04_ledgerAggregation_balancesFromEveryEntryAndPayment() {
        val c = Category("ledger-aggregation")
        val customerQs = listOf("{n} evlo tharanum?", "{n} balance evlo?", "{n} pending evlo?", "How much does {n} owe?", "{n}-oda balance sollu", "{n} kitta evlo varanum?")
        val expected = mapOf("Kumar" to "₹3,000", "Selvi" to "₹4,200", "Ravi" to "₹1,500", "Priya" to "₹800", "Mani" to "₹4,773.10")
        for ((n, amount) in expected) for (q in customerQs) {
            val text = q.replace("{n}", n)
            c.case(text) { has(Shop().say(text), amount) }
        }
        val supplierQs = listOf("{n}-ku evlo kudukkanum?", "{n} balance evlo?", "naan {n}-ku evlo tharanum?", "How much do I owe {n}?", "{n}-oda balance sollu", "{n} pending evlo?")
        for ((n, amount) in mapOf("Ramesh" to "₹2,500", "Basha" to "₹8,000")) for (q in supplierQs) {
            val text = q.replace("{n}", n)
            c.case(text) { has(Shop().say(text), amount) }
        }
        for (q in listOf("Arun evlo tharanum?", "Arun balance evlo?", "Arun pending evlo?", "Arun-oda balance sollu", "How much does Arun owe?"))
            c.case("settled: $q") { hasNot(Shop().say(q), "₹1,000") }
        c.case("total receivable") { has(Shop().say("Mothama customers evlo tharanum?"), "₹16,973.10") }
        c.case("total payable") { has(Shop().say("Mothama suppliers-ku evlo kudukkanum?"), "₹10,500") }
        c.case("count to collect") { has(Shop().say("Evlo per tharanum?"), "7") }
        c.done(50)
    }

    // ================================================================== 5. persistence (50)

    @Test
    fun m05_persistence_saveIsReadBackFromTheBooks() {
        val c = Category("persistence")
        val start = mapOf("c1" to 3000.0, "c3" to 4200.0, "c4" to 1500.0, "c5" to 800.0, "c6" to 4773.10)
        for ((n, id) in customers) for (a in listOf(500, 1200, 75, 2000)) c.case("credit $n +$a") {
            val s = Shop()
            s.chat("$n enaku $a tharanum", "save panniko")
            val r = s.say("seri")
            has(r, "Save aagiduchu")
            val after = start.getValue(id) + a
            check(s.l.party(id).pending.toDouble() == after) { "books ${s.l.party(id).pending} ≠ $after" }
            has(s.say("$n evlo tharanum?"), KaiFormat.rupees(after))
        }
        for ((n, id) in customers) for (a in listOf(100, 300)) c.case("payment $n -$a") {
            val s = Shop()
            s.say("$n $a kuduthutaan")
            has(s.say("seri"), "Save aagiduchu")
            val after = start.getValue(id) - a
            check(s.l.party(id).pending.toDouble() == after) { "books ${s.l.party(id).pending} ≠ $after" }
        }
        for ((n, _) in customers) for (reason in listOf("database locked", "disk full")) c.case("engine fails $n: $reason") {
            val s = Shop()
            s.tools.fail = reason
            s.chat("$n enaku 500 tharanum", "save panniko")
            hasNot(s.say("seri"), *saveClaims)
            check(s.tools.saved.isEmpty()) { "saved" }
        }
        for ((n, id) in customers) for (a in listOf(250, 900)) c.case("restart keeps $n +$a") {
            val s = Shop()
            s.chat("$n enaku $a tharanum", "save panniko", "seri")
            s.restart()
            has(s.say("$n evlo tharanum?"), KaiFormat.rupees(start.getValue(id) + a))
        }
        c.done(50)
    }

    // ================================================================== 6. dates (40)

    @Test
    fun m06_dates_dueDatesResolveAgainstToday() {
        val c = Category("date")
        val answers = listOf(
            "naalaikku" to d(10, 9), "tomorrow" to d(10, 9), "nalaiku" to d(10, 9), "day after tomorrow" to d(10, 10),
            "next Monday" to d(10, 12), "15th" to d(10, 15), "October 20" to d(10, 20), "20 Oct" to d(10, 20),
        )
        for ((answer, date) in answers) for ((n, id) in customers) c.case("$n due $answer") {
            val s = Shop()
            s.say("$n enaku 500 tharanum")
            s.say(answer)
            // A bare day ("15th") is asked: this month or next? — the owner answers.
            if (s.last?.reply?.text?.contains("adutha maasam") == true) s.say("indha maasam")
            if (s.last?.card == null) s.say("save panniko")
            s.say("seri")
            // The due date the books were given on Confirm.
            val p = s.tools.saved.singleOrNull()
            plan(p, PlanKind.CREDIT_GIVEN, id, "500")
            check(p!!.dueDate == date) { "due ${p.dueDate} ≠ $date: ${s.last?.reply?.text}" }
        }
        c.done(40)
    }

    // ================================================================== 7. reminders (40)

    @Test
    fun m07_reminders_confirmedStoredAndReadBack() {
        val c = Category("reminder")
        val times = listOf("10 minutes la" to now.plusMinutes(10), "2 minutes la" to now.plusMinutes(2), "1 hour la" to now.plusHours(1),
            "30 minutes la" to now.plusMinutes(30), "5 nimishathula" to now.plusMinutes(5))
        val asks = listOf("Praba-ku {t} call panna remind pannu", "{t} Praba-ku call pannanum, nyabagam paduthu", "{t} gas booking remind pannu",
            "{t} bank-ku poganum remind pannu")
        for (ask in asks) for ((t, at) in times) {
            val text = ask.replace("{t}", t)
            c.case(text) {
                val s = Shop()
                s.turn(text)
                check(s.tools.reminders.isEmpty()) { "scheduled before Confirm" }
                s.confirmReminder()
                val r = s.tools.reminders.singleOrNull() ?: throw AssertionError("not stored: ${s.last?.reply?.text}")
                check(r.triggerAt == millis(at)) { "at ${r.triggerAt} ≠ ${millis(at)}" }
            }
        }
        for ((t, at) in listOf("naalaikku kaalaila 10 manikku" to d(10, 9).atTime(10, 0), "tomorrow 10 AM" to d(10, 9).atTime(10, 0),
            "saayangalam 6 manikku" to today.atTime(18, 0), "today 6 PM" to today.atTime(18, 0), "naalaikku 9 AM" to d(10, 9).atTime(9, 0)))
            for (ask in listOf("{t} Praba-ku call panna remind pannu", "Remind me to call Praba {t}")) {
                val text = ask.replace("{t}", t)
                c.case(text) {
                    val s = Shop()
                    s.turn(text)
                    s.confirmReminder()
                    val r = s.tools.reminders.singleOrNull() ?: throw AssertionError("not stored: ${s.last?.reply?.text}")
                    check(r.triggerAt == millis(at)) { "at ${java.time.Instant.ofEpochMilli(r.triggerAt)} ≠ $at" }
                }
            }
        for (text in listOf("10 minutes la Praba-ku call panna remind pannu", "naalaikku kaalaila 10 manikku gas booking remind pannu",
            "2 minutes la bank-ku poganum remind pannu", "Remind me to call Praba tomorrow 10 AM", "30 minutes la Ruthran-ku call panna remind pannu"))
            c.case("lost by the store: $text") {
                val s = Shop()
                s.tools.reminderLost = true
                s.turn(text)
                val done = s.confirmReminder()?.reply?.text
                hasNot(done, "remind pannuren", "I'll remind you", "நினைவூட்டுறேன்")
            }
        for (text in listOf("Remind pannu", "remind me", "Praba-ku call panna remind pannu", "Gas booking remind pannu", "remind pannunga"))
            c.case("no time asks: $text") { val s = Shop(); s.turn(text); check(s.tools.reminders.isEmpty()) { "stored without a time" } }
        c.done(40)
    }

    // ================================================================== 8. stock (40)

    @Test
    fun m08_stock_readInOutConfirmed() {
        val c = Category("stock")
        val qty = mapOf("Colgate" to "20", "Rice" to "45.5", "Sugar" to "10", "Soap" to "12")
        for ((p, q) in qty) for (ask in listOf("{p} stock evlo?", "{p} evlo irukku?", "{p} stock sollu", "How much {p} in stock?")) {
            val text = ask.replace("{p}", p)
            c.case(text) { has(Shop().say(text), q) }
        }
        val ids = mapOf("Colgate" to "p1", "Rice" to "p2", "Sugar" to "p3", "Soap" to "p4")
        for ((p, id) in ids) for (ask in listOf("{p} 6 vandhuchu", "{p} 6 stock in", "{p} 6 pudhu stock vandhiruku")) {
            val text = ask.replace("{p}", p)
            c.case(text) {
                val s = Shop()
                s.turn(text)
                check(s.tools.stockChanges.isEmpty()) { "stock changed before Confirm" }
                s.tap("Confirm")
                check(s.tools.stockChanges.singleOrNull() == Triple(id, BigDecimal("6"), true)) { "changes ${s.tools.stockChanges} / ${s.last?.reply?.text}" }
            }
        }
        for ((p, id) in ids) for (ask in listOf("{p} 3 pochu", "{p} 3 stock out", "{p} 3 vithuduchu")) {
            val text = ask.replace("{p}", p)
            c.case(text) {
                val s = Shop()
                s.turn(text)
                check(s.tools.stockChanges.isEmpty()) { "stock changed before Confirm" }
                s.tap("Confirm")
                check(s.tools.stockChanges.singleOrNull() == Triple(id, BigDecimal("3"), false)) { "changes ${s.tools.stockChanges} / ${s.last?.reply?.text}" }
            }
        }
        c.done(40)
    }

    // ================================================================== 9. memory (33)

    @Test
    fun m09_memory_ownerWordsAreConfirmedScopedAndUsed() {
        val c = Category("memory")
        for (word in listOf("potti", "dabba", "petti")) for (n in 1..5) c.case("$word ×$n") {
            val s = Shop()
            s.turn("'$word' na box")
            s.tap("Save")
            s.turn("Colgate $n $word vandhudhu")
            has(s.last?.reply?.text, "${n * 12} pieces")
            check(s.tools.stockChanges.isEmpty()) { "stock changed before Confirm" }
        }
        for (word in listOf("potti", "dabba", "petti")) for (n in 1..3) c.case("other business never knows $word") {
            val s = Shop()
            s.turn("'$word' na box"); s.tap("Save")
            s.otherBusiness()
            hasNot(s.say("Colgate $n $word vandhudhu"), "${n * 12} pieces")
        }
        for (word in listOf("potti", "dabba", "petti")) for (n in 1..2) c.case("restart keeps $word") {
            val s = Shop()
            s.turn("'$word' na box"); s.tap("Save")
            s.restart()
            has(s.say("Colgate $n $word vandhudhu"), "${n * 12} pieces")
        }
        for (word in listOf("potti", "dabba", "petti")) c.case("not saved without Save: $word") {
            val s = Shop()
            s.turn("'$word' na box"); s.tap("Not now")
            hasNot(s.say("Colgate 2 $word vandhudhu"), "24 pieces")
        }
        c.done(33)
    }

    // ================================================================== 10. calculator (30)

    @Test
    fun m10_calculator() {
        val c = Category("calculator")
        val sums = listOf("10*3" to "30", "1250 plus 375 evlo?" to "1,625", "500 + 300 evlo?" to "800", "1000 - 250 evlo?" to "750",
            "12 x 12 evlo?" to "144", "100 / 4 evlo?" to "25", "2500 + 2500" to "5,000", "75 * 4" to "300", "999 + 1" to "1,000", "4500 - 1500" to "3,000",
            "25000 la 18% GST evlo?" to "₹4,500", "1000 la 5% GST evlo?" to "₹50", "2000 la 12% GST evlo?" to "₹240", "10000 la 28% GST evlo?" to "₹2,800",
            "300 + 200 + 100 evlo?" to "600", "50 * 50" to "2,500", "1200 / 3" to "400", "7 * 8" to "56", "15000 + 5000 evlo?" to "20,000", "100 - 1" to "99",
            "2 kilo evlo gram?" to "2,000 g", "40 x 25 evlo" to "1,000", "500 plus 500" to "1,000", "800 minus 300 evlo" to "500", "60 * 60" to "3,600",
            "1,000 + 2,000 evlo?" to "3,000", "5000 la 18% GST evlo?" to "₹900", "250 * 4" to "1,000", "90 + 10" to "100", "3000 / 6" to "500")
        for ((q, a) in sums) c.case(q) {
            val s = Shop()
            has(s.say(q), a)
            check(s.tools.prepared.isEmpty()) { "a sum became a draft" }
        }
        c.done(30)
    }

    // ================================================================== 11. context (30)

    @Test
    fun m11_context_followUpsAreAboutWhoWasJustDiscussed() {
        val c = Category("context")
        val expected = mapOf("Kumar" to "₹3,000", "Selvi" to "₹4,200", "Ravi" to "₹1,500", "Priya" to "₹800", "Mani" to "₹4,773.10")
        for ((n, amount) in expected) {
            c.case("$n then evlo?") { val s = Shop(); s.say("$n pathi sollu"); has(s.say("evlo?"), amount) }
            c.case("$n then avan evlo tharanum?") { val s = Shop(); s.say("$n pathi sollu"); has(s.say("avan evlo tharanum?"), amount) }
            c.case("$n then due eppa?") { val s = Shop(); s.say("$n evlo tharanum?"); hasNot(s.say("due eppa?"), "yaar", "Yaar") }
        }
        for ((n, id) in customers) {
            c.case("$n then avan 200 kuduthaan") { val s = Shop(); s.say("$n evlo tharanum?"); plan(s.drafted("avan 200 kuduthaan"), PlanKind.PAYMENT_IN, id, "200") }
            c.case("$n then avanukku innum 300 tharanum") { val s = Shop(); s.say("$n evlo tharanum?"); plan(s.drafted("avan innum 300 tharanum"), PlanKind.CREDIT_GIVEN, id, "300") }
        }
        for ((n, _) in customers) c.case("two named then avan asks: $n") {
            val s = Shop(); s.say("$n and Ramesh pathi sollu"); val r = s.say("avan evlo tharanum?")
            has(r, "Ramesh")
        }
        c.done(30)
    }

    // ================================================================== 12. topic switch (30)

    @Test
    fun m12_topicSwitch_detoursDoNotLoseOrLeakContext() {
        val c = Category("topic-switch")
        val detours = listOf("Colgate stock evlo?", "10*3", "Vanakkam Kai", "Rice stock evlo?", "500 + 300 evlo?", "Thanks Kai")
        for ((n, id) in customers) for (detour in detours) c.case("$n draft → $detour") {
            val s = Shop()
            s.say("$n 400 kuduthutaan")
            s.say(detour)
            check(s.tools.saved.isEmpty()) { "detour '$detour' saved the draft" }
            // The draft is still the one Kai holds, or it was set aside — never another person's.
            check(s.tools.prepared.all { it.partyId == id }) { "draft moved: ${s.tools.prepared.map { it.partyId }}" }
        }
        c.done(30)
    }

    // ================================================================== 13. negative (30)

    @Test
    fun m13_negative_nothingInventedNothingWritten() {
        val c = Category("negative")
        val noWrite = listOf("Kumar", "500", "tharanum", "seri", "ok", "save panniko", "Confirm", "Kumar evlo?", "yes", "haan", "podu", "add pannu",
            "Kumar 500", "naalaikku", "Kumar paid ah?", "Kumar kuduthaana?", "evlo?", "Suresh 500 kuduthaan", "Kumaran 500 kuduthaan", "Lokesh 500 kuduthaan",
            "Kumar 0 kuduthaan", "Kumar -500 kuduthaan", "Kumar abc kuduthaan", "?", "...", "hello", "Kai", "illa", "venaam", "cancel")
        for (text in noWrite) c.case("no write: '$text'") {
            val s = Shop()
            val r = s.say(text)
            s.say("seri")
            check(s.tools.saved.isEmpty()) { "saved ${s.tools.saved.map { "${it.partyId}/${it.kind}/${it.amount}" }} after '$text' → $r" }
            hasNot(r, *saveClaims)
        }
        c.done(30)
    }

    // ================================================================== 14. Tamil (30)

    @Test
    fun m14_tamilScript() {
        val c = Category("tamil")
        val ta = mapOf("Kumar" to "குமார்", "Selvi" to "செல்வி", "Ravi" to "ரவி", "Priya" to "பிரியா", "Mani" to "மணி")
        val expected = mapOf("Kumar" to "3,000", "Selvi" to "4,200", "Ravi" to "1,500", "Priya" to "800", "Mani" to "4,773.10")
        for ((n, t) in ta) for (q in listOf("$t எவ்வளவு தரணும்?", "$t பாக்கி எவ்வளவு?", "$t balance எவ்வளவு?"))
            c.case(q) { has(Shop().say(q), expected.getValue(n)) }
        val ids = customers.toMap()
        for ((n, t) in ta) for (q in listOf("$t 500 குடுத்தான்", "$t 500 கொடுத்தாங்க", "$t கிட்ட 500 வாங்கினேன்"))
            c.case(q) { val s = Shop(); s.turn(q); plan(s.draft(), PlanKind.PAYMENT_IN, ids.getValue(n), "500") }
        c.done(30)
    }

    // ================================================================== 15. Tanglish (30)

    @Test
    fun m15_tanglish() {
        val c = Category("tanglish")
        val expected = mapOf("Kumar" to "₹3,000", "Selvi" to "₹4,200", "Ravi" to "₹1,500", "Priya" to "₹800", "Mani" to "₹4,773.10")
        for ((n, amount) in expected) for (q in listOf("$n evvalavu tharanum?", "$n ewlo baaki?", "$n kitta evlo varanum?"))
            c.case(q) { has(Shop().say(q), amount) }
        for ((n, id) in customers) for (q in listOf("$n 500 kuduthutaan da", "$n 500 rooba kuduthaan", "$n ivlo 500 kuduthaan"))
            c.case(q) { val s = Shop(); s.turn(q); plan(s.draft(), PlanKind.PAYMENT_IN, id, "500") }
        c.done(30)
    }

    // ================================================================== 16. mixed / speech-to-text (30)

    @Test
    fun m16_mixedAndSpeechToText() {
        val c = Category("mixed-stt")
        // Speech-to-text: lower case, number words, no punctuation, Tamil + English mixed.
        val words = mapOf("Kumar" to "c1", "Selvi" to "c3", "Ravi" to "c4", "Priya" to "c5", "Mani" to "c6")
        for ((n, id) in words) {
            c.case("$n ainnooru") { val s = Shop(); s.turn("${n.lowercase()} ainnooru kuduthaan"); plan(s.draft(), PlanKind.PAYMENT_IN, id, "500") }
            c.case("$n five hundred") { val s = Shop(); s.turn("${n.lowercase()} five hundred kuduthaan"); plan(s.draft(), PlanKind.PAYMENT_IN, id, "500") }
            c.case("$n 500 rs paid today") { val s = Shop(); s.turn("$n 500 rs paid today"); plan(s.draft(), PlanKind.PAYMENT_IN, id, "500") }
            c.case("$n ₹500 குடுத்தான்") { val s = Shop(); s.turn("$n ₹500 குடுத்தான்"); plan(s.draft(), PlanKind.PAYMENT_IN, id, "500") }
            c.case("$n balance how much") { has(Shop().say("$n balance how much"), KaiFormat.rupees(Shop().l.party(id).pending.toDouble())) }
            c.case("$n evlo pending english mix") { has(Shop().say("what is $n pending amount"), KaiFormat.rupees(Shop().l.party(id).pending.toDouble())) }
        }
        c.done(30)
    }

    // ================================================================== journeys 1–8 (end to end, JVM)
    // The Collect / Pay screens and the phone notification are Android UI: here the same ledger summaries those
    // screens read (the books' snapshot) and the stored reminder are checked; the screens themselves need a device.

    private fun onlyLokesh(amount: String? = null) = Ledger().apply {
        if (amount != null) add("c2", "Lokesh", true, amount, d(9, 10), null)
        else parties += Ledger.Party("c2", "Lokesh", true)
    }

    private fun collectRow(s: Shop, id: String): Double? = s.l.snapshot().customers.firstOrNull { it.id == id }?.pendingTotal

    @Test
    fun j1_receivableSavedReadAndKeptAfterRestart() {
        val s = Shop(l = onlyLokesh())
        s.chat("Lokesh enakku 2000 tharanum", "save panniko")
        has(s.say("seri"), "Save aagiduchu", "₹2,000")
        has(s.say("Lokesh evlo tharanum?"), "₹2,000")
        s.restart()
        has(s.say("Lokesh evlo tharanum?"), "₹2,000")
        assertEquals("2000", s.balance("c2"))
    }

    @Test
    fun j2_existingPlusNewEntryOnTheCollectSummaryAndAfterRestart() {
        val s = Shop(l = onlyLokesh("2000"))
        s.say("Lokesh-ku 500 add pannu")
        if (s.last?.card == null) s.say("save panniko")
        has(s.say("seri"), "Save aagiduchu", "₹2,500")
        assertEquals(2500.0, collectRow(s, "c2"))
        s.restart()
        has(s.say("Lokesh evlo tharanum?"), "₹2,500")
    }

    @Test
    fun j3_receivableWithDueDateThenReminderThenQuery() {
        val s = Shop(l = Ledger().apply { parties += Ledger.Party("c1", "Kumar", true) })
        s.say("Kumar enakku 5000 tharanum")
        s.say("naalaikku")
        if (s.last?.card == null) s.say("save panniko")
        has(s.say("seri"), "Save aagiduchu", "₹5,000")
        assertEquals(d(10, 9), s.tools.saved.single().dueDate)
        s.turn("naalaikku kaalaila 10 manikku Kumar-ku call panna remind pannu")
        s.confirmReminder()
        val r = s.tools.reminders.single()
        assertEquals(millis(d(10, 9).atTime(10, 0)), r.triggerAt)
        has(s.say("Kumar evlo tharanum?"), "₹5,000")
    }

    @Test
    fun j4_paymentReducesTheBalanceEverywhereAndAfterRestart() {
        val s = Shop(l = Ledger().apply { add("c1", "Kumar", true, "5000", d(9, 1), d(10, 8)) })
        s.say("Kumar 2000 kuduthutaan")
        has(s.say("seri"), "Save aagiduchu", "₹3,000")
        assertEquals(3000.0, collectRow(s, "c1"))
        has(s.say("Kumar evlo tharanum?"), "₹3,000")
        s.restart()
        has(s.say("Kumar evlo tharanum?"), "₹3,000")
    }

    @Test
    fun j5_todayListCountTotalDetailsTopicSwitchAndBack() {
        val s = Shop()
        val today = s.say("innaikku yaar payment tharanum?")
        has(today, "Kumar", "Selvi", "₹7,200")
        hasNot(today, "Ravi", "Priya")
        has(s.say("details sollu"), "Kumar", "₹3,000", "Selvi", "₹4,200")
        s.say("Colgate stock evlo?")
        has(s.say("details sollu"), "Kumar", "Selvi")
        check(s.tools.saved.isEmpty()) { "a question wrote" }
    }

    @Test
    fun j6_duplicateNameClarifiedByPlaceThenPaymentSavedAndRead() {
        val s = Shop()
        has(s.say("Lokesh 500 kuduthaan"), "Chennai", "Nagapattinam")
        check(s.tools.prepared.isEmpty()) { "drafted before the person was clear" }
        s.say("Chennai")
        if (s.last?.card == null) s.say("save panniko")
        has(s.say("seri"), "Save aagiduchu")
        assertEquals(listOf("c2"), s.tools.saved.map { it.partyId })
        assertEquals("1500", s.balance("c2"))
        assertEquals("700", s.balance("c8"))
        has(s.say("Chennai Lokesh evlo tharanum?"), "₹1,500")
    }

    @Test
    fun j7_ownerMemoryLearnConfirmUseCorrectUseCorrected() {
        val s = Shop()
        s.turn("'potti' na box")
        s.say("aama")
        has(s.say("Colgate 2 potti vandhudhu"), "24 pieces")
        s.tap("Cancel")
        has(s.say("potti meaning change pannu"), "new meaning")
        s.say("carton")
        s.say("aama")
        has(s.say("Colgate 2 potti vandhudhu"), "96 pieces")
        check(s.tools.stockChanges.isEmpty()) { "stock changed without Confirm" }
    }

    @Test
    fun j8_interruptionCasualQuestionCalculatorThenBackToThePaymentAndSave() {
        val s = Shop()
        s.say("Kumar 400 kuduthutaan")
        s.say("Vanakkam Kai, eppadi irukeenga?")
        has(s.say("500 + 300 evlo?"), "800")
        check(s.tools.saved.isEmpty()) { "saved during the detour" }
        // Back to the payment: Kai's last answer (800) asked nothing, so "seri" confirms the draft Kai still holds.
        has(s.say("seri"), "Save aagiduchu", "₹2,600")
        assertEquals(listOf("c1/PAYMENT_IN"), s.tools.saved.map { "${it.partyId}/${it.kind}" })
        assertEquals("2600", s.balance("c1"))
    }

    @Test
    fun j8b_seriAnswersKaisLaterQuestionNotTheDraft() {
        val s = Shop()
        s.say("Kumar 400 kuduthutaan")
        // Kai's last reply asked something else ("which Lokesh?"): a bare "seri" now is not the payment's Confirm.
        has(s.say("Lokesh evlo tharanum?"), "Chennai", "Nagapattinam")
        s.say("seri")
        check(s.tools.saved.isEmpty()) { "seri to another question saved the payment" }
        // The draft is still open: "confirm" saves it.
        has(s.say("confirm"), "Save aagiduchu", "₹2,600")
        assertEquals(listOf("c1/PAYMENT_IN"), s.tools.saved.map { "${it.partyId}/${it.kind}" })
    }

    // ================================================================== 17. reported on the Pixel 8 (8 Oct 2026)

    private fun deviceLedger() = Ledger().apply {
        add("c1", "Kumar", true, "5000", d(9, 1), d(10, 8)).payments += BigDecimal("2000") to d(10, 3)
        add("c9", "Praba", true, "5000", d(10, 8), d(11, 10))
        add("c10", "Suresh", true, "8000", d(9, 1), d(10, 20))
        add("c11", "Lokesh", true, "2000", d(9, 10), d(10, 20))
        add("s1", "Ramesh", false, "2500", d(9, 20), d(10, 8))
    }

    @Test
    fun m17_deviceReports() {
        val c = Category("device-reports")
        c.case("payable: due date answer brings the draft at once") {
            val s = Shop(l = deviceLedger())
            has(s.say("naan Selvam ku 3000 tharanum"), "Due date eppa?")
            val t = s.turn("nalaiku")
            has(t.reply.text, "Selvam-ku ₹3,000 naalaikku kudukkanum. Add pannalama?")
            check(t.plan?.kind == PlanKind.DEBIT_TAKEN && t.plan.dueDate == d(10, 9)) { "plan ${t.plan}" }
            check(s.tools.saved.isEmpty()) { "saved before Confirm" }
            has(s.say("seri"), "Save aagiduchu", "₹3,000")
        }
        c.case("receivable: tomorrow brings the draft") {
            val s = Shop(l = deviceLedger())
            s.say("Kumar enaku 1000 tharanum")
            val t = s.turn("tomorrow")
            has(t.reply.text, "Add pannalama?")
            check(t.plan?.partyId == "c1" && t.plan.dueDate == d(10, 9)) { "plan ${t.plan}" }
        }
        for (spelling in listOf("tmrw", "tomorow", "tommorow", "nalaiki"))
            c.case("due date spelling $spelling") {
                val s = Shop(l = deviceLedger()); s.say("Kumar enaku 1000 tharanum")
                check(s.turn(spelling).plan?.dueDate == d(10, 9)) { "no draft for $spelling: ${s.last?.reply?.text}" }
            }
        for (text in listOf("Suresh gpay la 5000 pay pannan", "Suresh 5000 gpay pannan", "Suresh 5000 pay pannitaan", "Suresh 5000 UPI la transfer pannitaan"))
            c.case(text) {
                val s = Shop(l = deviceLedger())
                val t = s.turn(text)
                plan(s.draft(), PlanKind.PAYMENT_IN, "c10", "5000")
                has(t.reply.text, "Add pannalama?")
            }
        c.case("owner paid by GPay") { val s = Shop(l = deviceLedger()); s.turn("Ramesh-ku 400 GPay pannen"); plan(s.draft(), PlanKind.PAYMENT_OUT, "s1", "400") }
        c.case("Praba thambi is asked, then a new person") {
            val s = Shop(l = deviceLedger())
            has(s.say("Praba thambi enaku 6000 tharanum"), "Praba dhaan-aa", "Praba-oda thambi")
            check(s.tools.prepared.isEmpty()) { "drafted before asking" }
            s.say("vera aal")
            check(s.draft()?.partyId == null && s.draft()?.partyName == "Praba Thambi" && s.draft()?.kind == PlanKind.CREDIT_GIVEN) { "draft ${s.draft()}" }
        }
        c.case("Praba thambi is asked, then Praba himself") {
            val s = Shop(l = deviceLedger())
            s.say("Praba thambi enaku 6000 tharanum"); s.say("Praba dhaan")
            plan(s.draft(), PlanKind.CREDIT_GIVEN, "c9", "6000")
        }
        c.case("Praba thambi payment is asked too") {
            val s = Shop(l = deviceLedger())
            has(s.say("Praba thambi 500 kuduthaan"), "Praba dhaan-aa")
            s.say("Praba")
            plan(s.draft(), PlanKind.PAYMENT_IN, "c9", "500")
        }
        c.case("ten thousand is ₹10,000") {
            // One Lokesh in the books: "Chennai Lokesh" is asked (same or new — owner's rule), then ₹10,000 on the new one.
            val s = Shop(l = deviceLedger())
            has(s.say("Chennai Lokesh ten thousand tharanum"), "'Chennai Lokesh' — adhey Lokesh-aa, illa puthu customer-aa?")
            s.say("pudhu")
            plan(s.draft(), PlanKind.CREDIT_GIVEN, null, "10000")
            check(s.draft()?.partyName == "Chennai Lokesh") { "draft ${s.draft()}" }
        }
        c.case("new two-word name keeps both words") {
            val s = Shop(l = deviceLedger())
            has(s.say("Madurai Ravi enakku 10000 tharanum"), "Madurai Ravi kitta")
            s.say("naalaikku")
            check(s.draft()?.partyName == "Madurai Ravi" && s.draft()?.partyId == null) { "draft ${s.draft()}" }
        }
        c.case("unknown product and customer said plainly") { has(Shop(l = deviceLedger()).say("Dettol evlo irukku?"), "product-um illa, customer-um illa") }
        c.case("known product still answers stock") { has(Shop(l = deviceLedger()).say("Colgate evlo irukku?"), "20") }
        for (q in listOf("today yar payment tharanum", "today yaar payment tharanum?", "innaikku yaar payment tharanum?"))
            c.case(q) { val r = Shop(l = deviceLedger()).say(q); has(r, "Innaikku", "Kumar"); hasNot(r, "kitta evlo vaanganum") }
        c.case("add pannitiya? shows the draft again") {
            val s = Shop(l = deviceLedger())
            s.say("Kumar enaku 1000 tharanum"); s.say("naalaikku")
            val t = s.turn("add pannitiya?")
            has(t.reply.text, "Innum save pannala")
            check(t.card?.buttons?.any { it.label == "Confirm" } == true && t.plan != null) { "no card" }
        }
        c.done(22)
    }

    /**
     * One real shop day through chat, checked against the books after every save: payments in (cash / GPay), a credit
     * to one of two same-named customers, a payment from the other, a supplier purchase with a due date, payments out,
     * a credit with no due date, a brand-new customer, a NEW third "Murugan", then the owner's questions — and a restart.
     */
    @Test
    fun j9_realShopDayLedgerStaysExact() {
        val l = Ledger().apply {
            add("c1", "Kumar", true, "5000", d(9, 1), d(10, 8)).payments += BigDecimal("2000") to d(10, 3)
            add("c3", "Selvi", true, "4200", d(9, 15), d(10, 8))
            add("c20", "Murugan", true, "1000", d(9, 20), d(10, 20), city = "Chennai")
            add("c21", "Murugan", true, "2500", d(9, 22), d(10, 12), city = "Madurai")
            add("c5", "Priya", true, "800", d(9, 20), null)
            add("s1", "Ramesh", false, "2500", d(9, 20), d(10, 8))
            add("s2", "Basha", false, "9000", d(9, 1), d(9, 30)).payments += BigDecimal("1000") to d(10, 6)
        }
        val s = Shop(l = l)
        fun books() = s.l.parties.associate { p -> (p.city?.let { "$it " }.orEmpty() + p.name) to p.pending.stripTrailingZeros().toPlainString() }
        fun step(expected: Map<String, String>, vararg lines: String): String {
            val last = lines.map { s.say(it) }.last()
            val b = books()
            expected.forEach { (who, amount) -> assertEquals("$who after ${lines.toList()} → $last", amount, b[who]) }
            return last
        }

        has(step(mapOf("Selvi" to "2200"), "Selvi 2000 kuduthaanga", "seri"), "Save aagiduchu", "₹2,200")
        has(step(mapOf("Kumar" to "2500"), "Kumar gpay la 500 pay pannan", "seri"), "₹2,500")
        has(s.say("Murugan enakku 1500 tharanum"), "Chennai Murugan", "Madurai Murugan")
        has(step(mapOf("Madurai Murugan" to "4000", "Chennai Murugan" to "1000"), "Madurai", "naalaikku", "seri"), "Madurai Murugan — ₹1,500", "₹4,000")
        has(step(mapOf("Chennai Murugan" to "0", "Madurai Murugan" to "4000"), "Chennai Murugan 1000 kuduthaan", "seri"), "Chennai Murugan — ₹1,000")
        has(step(mapOf("Ramesh" to "14500"), "naan Ramesh-ku 12000 kudukkanum", "next week", "seri"), "₹14,500")
        has(step(mapOf("Ramesh" to "9500"), "Ramesh-ku 5000 GPay pannen", "seri"), "₹9,500")
        has(step(mapOf("Basha" to "5000"), "Basha-ku 3000 cash kuduthen", "seri"), "₹5,000")
        has(step(mapOf("Priya" to "1250"), "Priya enakku 450 tharanum", "due venam", "seri"), "₹1,250")
        has(step(mapOf("Ganesh" to "700"), "Ganesh enakku 700 tharanum", "naalaikku", "seri"), "₹700")
        has(s.say("Trichy Murugan enakku 600 tharanum"), "Illa puthu customer 'Trichy Murugan'-aa?")
        has(step(mapOf("Trichy Murugan" to "600", "Chennai Murugan" to "0", "Madurai Murugan" to "4000"), "pudhu", "naalaikku", "seri"), "Trichy Murugan — ₹600")
        assertEquals(10, s.tools.saved.size)

        // The owner's questions, answered from the books.
        has(s.say("Rendu Murugan-oda balance sollu"), "Chennai Murugan — ₹0", "Madurai Murugan — ₹4,000")
        val all = s.say("yaar yaar evlo tharanum?")
        has(all, "6 per", "₹11,250", "Priya — ₹1,250", "Kumar — ₹2,500", "Selvi — ₹2,200", "Madurai Murugan — ₹4,000", "Ganesh — ₹700", "Trichy Murugan — ₹600")
        has(s.say("naan yaarukku evlo kudukkanum?"), "₹14,500", "Basha ₹5,000", "Ramesh ₹9,500")
        has(s.say("innaikku yaar tharanum?"), "₹4,700", "Kumar ₹2,500", "Selvi ₹2,200")
        has(s.say("Mothama evlo varanum?"), "₹11,250", "₹14,500")
        assertEquals(10, s.tools.saved.size)
        s.restart()
        has(s.say("Madurai Murugan evlo tharanum?"), "₹4,000")
        has(s.say("Ramesh-ku evlo kudukkanum?"), "₹9,500")
    }

    // ================================================================== 18. Tamil / Tanglish / English combinations

    @Test
    fun m18_languageCombinations() {
        val c = Category("language-combinations")
        val custTa = listOf("குமார்" to "c1", "செல்வி" to "c3", "ரவி" to "c4", "மணி" to "c6")
        val supTa = listOf("ரமேஷ்" to "s1", "பாஷா" to "s2")
        val amounts = listOf("500", "1200", "350", "2000")
        // Tamil script: the owner gives credit / owes / receives / pays.
        for ((i, who) in custTa.withIndex()) {
            val (n, id) = who; val a = amounts[i]
            for (t in listOf("$n எனக்கு $a தரணும்", "$n எனக்கு $a ரூபாய் தரணும்", "$n கிட்ட $a வாங்கணும்"))
                c.case(t) { val s = Shop(); plan(s.drafted(t), PlanKind.CREDIT_GIVEN, id, a) }
            for (t in listOf("$n $a கொடுத்தான்", "$n $a குடுத்தாங்க", "$n $a அனுப்பினான்", "$n கிட்ட $a வாங்கினேன்"))
                c.case(t) { val s = Shop(); s.turn(t); plan(s.draft(), PlanKind.PAYMENT_IN, id, a) }
            c.case("$n எவ்வளவு தரணும்?") { has(Shop().say("$n எவ்வளவு தரணும்?"), KaiFormat.rupees(Shop().l.party(id).pending.toDouble()).removePrefix("₹")) }
        }
        for ((i, who) in supTa.withIndex()) {
            val (n, id) = who; val a = amounts[i]
            for (t in listOf("நான் $n-க்கு $a கொடுக்கணும்", "நான் ${n}க்கு $a தரணும்"))
                c.case(t) { val s = Shop(); plan(s.drafted(t), PlanKind.DEBIT_TAKEN, id, a) }
            for (t in listOf("$n-க்கு $a கொடுத்தேன்", "${n}க்கு $a அனுப்பினேன்"))
                c.case(t) { val s = Shop(); s.turn(t); plan(s.draft(), PlanKind.PAYMENT_OUT, id, a) }
        }
        // English.
        for ((n, id) in customers.take(4)) {
            c.case("$n owes me 500") { val s = Shop(); plan(s.drafted("$n owes me 500"), PlanKind.CREDIT_GIVEN, id, "500") }
            c.case("$n paid 300") { val s = Shop(); s.turn("$n paid 300"); plan(s.draft(), PlanKind.PAYMENT_IN, id, "300") }
            c.case("Received 250 from $n") { val s = Shop(); s.turn("Received 250 from $n"); plan(s.draft(), PlanKind.PAYMENT_IN, id, "250") }
            c.case("How much does $n owe?") { has(Shop().say("How much does $n owe?"), KaiFormat.rupees(Shop().l.party(id).pending.toDouble())) }
        }
        for ((n, id) in suppliers) {
            c.case("I have to pay $n 900") { val s = Shop(); plan(s.drafted("I have to pay $n 900"), PlanKind.DEBIT_TAKEN, id, "900") }
            c.case("I paid $n 400") { val s = Shop(); s.turn("I paid $n 400"); plan(s.draft(), PlanKind.PAYMENT_OUT, id, "400") }
            c.case("Paid 400 to $n") { val s = Shop(); s.turn("Paid 400 to $n"); plan(s.draft(), PlanKind.PAYMENT_OUT, id, "400") }
        }
        // Tamil due-date answers bring the draft with the right date.
        for ((ans, date) in listOf("நாளைக்கு" to d(10, 9), "நாளன்னைக்கு" to d(10, 10), "அடுத்த மாதம் 5" to d(11, 5), "நாளை" to d(10, 9)))
            c.case("due $ans") {
                val s = Shop(); s.say("குமார் எனக்கு 700 தரணும்")
                val t = s.turn(ans)
                check(t.plan?.dueDate == date && t.plan.partyId == "c1") { "plan ${t.plan} / ${t.reply.text}" }
            }
        // Questions across languages.
        for (q in listOf("இன்னைக்கு யார் தரணும்?", "innaikku yaar tharanum?", "Who has to pay me today?"))
            c.case(q) { has(Shop().say(q), "Kumar", "Selvi") }
        for (q in listOf("மொத்தம் எவ்வளவு வரணும்?", "Mothama evlo varanum?", "How much do I have to collect in total?"))
            c.case(q) { has(Shop().say(q), "16,973.10") }
        for (q in listOf("நான் யாருக்கு எவ்வளவு கொடுக்கணும்?", "naan yaarukku evlo kudukkanum?", "Whom do I have to pay?"))
            c.case(q) { has(Shop().say(q), "Ramesh", "Basha") }
        c.done(75)
    }

    // ================================================================== 19. random combinations — safety invariants

    /**
     * 400 seeded random sentences (name × amount form × verb × filler, Tamil / Tanglish / English, typos and lower case).
     * Whatever Kai understands, these must always hold: no crash; nothing saved before Confirm; never "saved" in the
     * reply before Confirm; a draft only for the person named (or a new person); the draft's amount is the one said;
     * and after "seri" the books hold exactly the draft that was shown.
     */
    @Test
    fun m19_randomCombinationsKeepTheSafetyRules() {
        val c = Category("random-invariants")
        val rnd = Random(20261008)
        val names = listOf("Kumar" to "c1", "Selvi" to "c3", "Ravi" to "c4", "Priya" to "c5", "Mani" to "c6", "Ramesh" to "s1", "Basha" to "s2",
            "குமார்" to "c1", "செல்வி" to "c3", "kumar" to "c1", "selvi" to "c3", "Suresh" to null, "Lokesh" to null)
        val amounts = listOf("500" to "500", "₹750" to "750", "1,200" to "1200", "2k" to "2000", "300 rs" to "300", "ainooru" to "500", "five hundred" to "500")
        val verbs = listOf("kuduthaan", "kuduthutaan", "enakku {a} tharanum", "-ku {a} kuduthen", "-ku {a} kudukkanum", "GPay pannan", "paid", "{a} baaki",
            "கொடுத்தான்", "எனக்கு {a} தரணும்", "-க்கு {a} கொடுத்தேன்", "pay pannitaan", "anuppitaan", "kitta {a} vaanginen")
        val fillers = listOf("", "", "", "da ", "bro ", "inniku ", "ippo ", "owner ", "seri ")
        var drafted = 0
        var savedCount = 0
        repeat(400) { i ->
            val (n, id) = names[rnd.nextInt(names.size)]
            val (aText, aValue) = amounts[rnd.nextInt(amounts.size)]
            val v = verbs[rnd.nextInt(verbs.size)]
            val f = fillers[rnd.nextInt(fillers.size)]
            val text = when {
                v.startsWith("-") -> f + n + v.replace("{a}", aText)
                v.contains("{a}") -> "$f$n " + v.replace("{a}", aText)
                else -> "$f$n $aText $v"
            }
            c.case("#$i $text") {
                val s = Shop()
                val first = s.turn(text)
                check(s.tools.saved.isEmpty()) { "saved before Confirm: ${s.tools.saved}" }
                hasNot(first.reply.text, *saveClaims)
                // If Kai asked for a due date, answer it; then whatever draft exists is the one checked.
                if (first.reply.text.contains("Due date eppa", ignoreCase = true) || first.reply.text.contains("due date", ignoreCase = true)) s.turn("naalaikku")
                val draft = s.draft()
                if (draft != null) {
                    drafted++
                    check(draft.partyId == null || draft.partyId == id) { "draft for ${draft.partyId}, said $n ($id): ${s.last?.reply?.text}" }
                    check(draft.amount.compareTo(BigDecimal(aValue)) == 0) { "amount ${draft.amount} ≠ $aValue" }
                    check(s.tools.saved.isEmpty()) { "saved before Confirm" }
                    s.turn("seri")
                    s.tools.saved.singleOrNull()?.let { saved ->
                        savedCount++
                        check(saved.partyId == draft.partyId && saved.amount.compareTo(draft.amount) == 0 && saved.kind == draft.kind) { "saved $saved ≠ draft $draft" }
                    }
                }
            }
        }
        println("MATRIX random-invariants: $drafted drafts, $savedCount saved after Confirm, ${400 - drafted} asked / answered without a draft")
        c.done(400)
    }

    // ================================================================== 20. same name: "Madurai Lokesh" next to "Lokesh"
    // Owner's rule (9 Oct 2026): a new "Lokesh <area>" is asked — never merged into the Lokesh already in the books;
    // "Lokesh evlo tharanum?" with both asks which one and then gives that one's figures; reminders ask the same way.

    /** Lokesh ₹2,000 (no town) · Kumar ₹3,000 · supplier Ramesh ₹2,500 — and, when [both], Madurai Lokesh ₹500. */
    private fun sameNameLedger(both: Boolean = false) = Ledger().apply {
        add("c30", "Lokesh", true, "2000", d(9, 10), d(10, 20))
        add("c1", "Kumar", true, "3000", d(9, 1), d(10, 8))
        add("s1", "Ramesh", false, "2500", d(9, 20), d(10, 8))
        if (both) add("c31", "Madurai Lokesh", true, "500", d(10, 1), d(10, 25))
    }

    @Test
    fun m20_sameNameNewPersonQueriesAndReminders() {
        val c = Category("same-name")
        val asked = "— adhey Lokesh-aa, illa puthu customer-aa?"

        // A. "<area> Lokesh" with one Lokesh in the books: asked, nothing drafted / saved (6 areas × 5 phrasings = 30)
        for (a in listOf("Madurai", "Trichy", "Salem", "Erode", "Coimbatore", "Velachery")) {
            val lower = a.lowercase()
            listOf(
                "$a Lokesh enakku 500 tharanum" to "'$a Lokesh' $asked",
                "Lokesh $a enakku 500 tharanum" to "'Lokesh $a' $asked",
                "$lower lokesh enakku 500 tharanum" to "'$a Lokesh' $asked",
                "$a Lokesh 500 kuduthaan" to "'$a Lokesh' $asked",
                "$a Lokesh owes me 500" to "'$a Lokesh' — the same Lokesh, or a new customer?",
            ).forEach { (said, expect) ->
                c.case("asks: $said") {
                    val s = Shop(l = sameNameLedger())
                    has(s.say(said), expect)
                    check(s.tools.prepared.isEmpty()) { "drafted before the owner answered: ${s.draft()}" }
                    check(s.balance("c30") == "2000") { "Lokesh changed: ${s.balance("c30")}" }
                    hasNot(s.last?.reply?.text, *saveClaims)
                }
            }
        }

        // B. The owner's answer: new person / the same Lokesh — typed, spoken or tapped; books right after Confirm (20)
        listOf("pudhu", "puthu customer", "new", "vera aal", "Madurai", "2", "tap:Madurai Lokesh — puthu customer").forEach { ans ->
            c.case("new person by '$ans'") {
                val s = Shop(l = sameNameLedger())
                s.say("Madurai Lokesh enakku 500 tharanum")
                if (ans.startsWith("tap:")) s.tap(ans.removePrefix("tap:")) else s.say(ans)
                plan(s.draft(), PlanKind.CREDIT_GIVEN, null, "500")
                check(s.draft()?.partyName == "Madurai Lokesh") { "draft ${s.draft()}" }
                check(s.l.parties.size == 3) { "saved before Confirm" }
                has(s.say("confirm"), "Save aagiduchu", "Madurai Lokesh")
                val fresh = s.l.parties.single { it.name == "Madurai Lokesh" }
                check(fresh.pending.compareTo(BigDecimal("500")) == 0 && s.balance("c30") == "2000") { "books: new ${fresh.pending}, Lokesh ${s.balance("c30")}" }
            }
        }
        listOf("adhey", "adhey Lokesh", "pazhaya Lokesh", "same", "1", "Lokesh dhaan", "tap:Lokesh · ₹2,000").forEach { ans ->
            c.case("same Lokesh by '$ans'") {
                val s = Shop(l = sameNameLedger())
                s.say("Madurai Lokesh enakku 500 tharanum")
                if (ans.startsWith("tap:")) s.tap(ans.removePrefix("tap:")) else s.say(ans)
                plan(s.draft(), PlanKind.CREDIT_GIVEN, "c30", "500")
                check(s.balance("c30") == "2000") { "saved before Confirm" }
                has(s.say("confirm"), "Save aagiduchu")
                check(s.balance("c30") == "2500" && s.l.parties.size == 3) { "books: Lokesh ${s.balance("c30")}, parties ${s.l.parties.map { it.name }}" }
            }
        }
        c.case("payment: adhey → Lokesh paid") {
            val s = Shop(l = sameNameLedger())
            has(s.say("Madurai Lokesh 500 kuduthaan"), asked)
            s.say("adhey")
            plan(s.draft(), PlanKind.PAYMENT_IN, "c30", "500")
            s.say("confirm")
            check(s.balance("c30") == "1500") { "Lokesh ${s.balance("c30")}" }
        }
        c.case("payment: pudhu → not Lokesh's record") {
            val s = Shop(l = sameNameLedger())
            s.say("Madurai Lokesh 500 kuduthaan")
            s.say("pudhu")
            check(s.draft()?.partyId == null && s.draft()?.partyName == "Madurai Lokesh") { "draft ${s.draft()}" }
            check(s.balance("c30") == "2000") { "Lokesh changed" }
        }
        c.case("supplier side asks 'puthu supplier'") {
            val s = Shop(l = sameNameLedger())
            has(s.say("Madurai Ramesh-ku naan 500 kudukkanum"), "'Madurai Ramesh' — adhey Ramesh-aa, illa puthu supplier-aa?")
            check(s.tools.prepared.isEmpty()) { "drafted" }
        }
        c.case("supplier: pudhu → new supplier") {
            val s = Shop(l = sameNameLedger())
            s.say("Madurai Ramesh-ku naan 500 kudukkanum")
            s.say("pudhu")
            plan(s.draft(), PlanKind.DEBIT_TAKEN, null, "500")
            check(s.draft()?.partyName == "Madurai Ramesh") { "draft ${s.draft()}" }
        }
        c.case("supplier: adhey → Ramesh") {
            val s = Shop(l = sameNameLedger())
            s.say("Madurai Ramesh-ku naan 500 kudukkanum")
            s.say("adhey")
            plan(s.draft(), PlanKind.DEBIT_TAKEN, "s1", "500")
        }
        c.case("a question instead of an answer saves nothing") {
            val s = Shop(l = sameNameLedger())
            s.say("Madurai Lokesh enakku 500 tharanum")
            has(s.say("Lokesh evlo tharanum?"), "₹2,000")
            check(s.tools.prepared.isEmpty() && s.balance("c30") == "2000") { "drafted / saved" }
        }

        // C. Only the name (with fillers, verbs, amounts, days): never asked — Lokesh's own record (20)
        listOf("Lokesh 500 kuduthaan", "lokesh 500 kuduthaan", "Lokesh inniku 500 kuduthaan", "Lokesh ippo 500 kuduthaan", "Today Lokesh 500 kuduthaan",
            "Lokesh gpay la 500 pay pannan", "Lokesh UPI la 500 anuppitaan", "Lokesh cash 500 kuduthaan", "Lokesh paid 500", "Lokesh gave 500",
            "Lokesh 500 return pannitaan", "Lokesh 500 kuduthutaru", "Lokesh five hundred kuduthaan", "Lokesh ainooru kuduthaan", "Lokesh 500 rooba kuduthaan",
            "Lokesh kitta irundhu 500 vandhuchu").forEach { said ->
            c.case("not asked: $said") {
                val s = Shop(l = sameNameLedger())
                hasNot(s.say(said), "puthu customer-aa?", "new customer?")
                plan(s.draft(), PlanKind.PAYMENT_IN, "c30", "500")
            }
        }
        listOf("Lokesh enakku 500 tharanum", "lokesh enakku 500 tharanum", "Lokesh ten thousand tharanum", "Lokesh kitta 500 vaanganum").forEach { said ->
            c.case("not asked: $said") {
                val s = Shop(l = sameNameLedger())
                val r = s.say(said)
                hasNot(r, "puthu customer-aa?")
                has(r, "Lokesh kitta irundhu", "Due date eppa?")
            }
        }

        // D. Both in the books: "Lokesh evlo?" asks which, then that one's figures (16)
        listOf("Lokesh evlo tharanum?", "lokesh evlo tharanum", "Lokesh balance evlo?", "Lokesh kitta evlo vaanganum?", "Lokesh pending evlo?",
            "Lokesh due eppa?", "How much does Lokesh owe?", "Lokesh evvalavu tharanum?").forEach { q ->
            c.case("which: $q") {
                val s = Shop(l = sameNameLedger(both = true))
                val r = s.say(q)
                check(r.contains("Lokesh-nu rendu per") || r.contains("there are 2 named Lokesh")) { "not asked which: $r" }
                has(r, "Madurai Lokesh")
            }
        }
        listOf("Madurai" to "₹500", "Madurai Lokesh" to "₹500", "2" to "₹500", "Lokesh" to "₹2,000", "1" to "₹2,000").forEach { (pick, amount) ->
            c.case("which → '$pick' → $amount") {
                val s = Shop(l = sameNameLedger(both = true))
                s.say("Lokesh evlo tharanum?")
                val r = s.say(pick)
                has(r, amount)
                hasNot(r, if (amount == "₹500") "₹2,000" else "₹500")
            }
        }
        c.case("which → 'rendu perum' → both") {
            val s = Shop(l = sameNameLedger(both = true))
            s.say("Lokesh evlo tharanum?")
            has(s.say("rendu perum"), "₹2,000", "₹500")
        }
        listOf("Madurai Lokesh evlo tharanum?", "madurai lokesh evlo tharanum").forEach { q ->
            c.case("full name answers directly: $q") {
                val s = Shop(l = sameNameLedger(both = true))
                val r = s.say(q)
                has(r, "₹500")
                hasNot(r, "₹2,000", "rendu per")
            }
        }

        // E. Both in the books: writes ask which and land on the picked record (10)
        c.case("payment asks which") {
            val s = Shop(l = sameNameLedger(both = true))
            has(s.say("Lokesh 300 kuduthaan"), "Endha Lokesh", "Madurai Lokesh")
            check(s.tools.prepared.isEmpty()) { "drafted without asking" }
        }
        listOf("Madurai" to "c31", "Madurai Lokesh" to "c31", "Lokesh" to "c30", "tap:Madurai Lokesh · Customer · ₹500" to "c31").forEach { (pick, id) ->
            c.case("payment pick '$pick'") {
                val s = Shop(l = sameNameLedger(both = true))
                s.say("Lokesh 300 kuduthaan")
                if (pick.startsWith("tap:")) s.tap(pick.removePrefix("tap:")) else s.say(pick)
                plan(s.draft(), PlanKind.PAYMENT_IN, id, "300")
                s.say("confirm")
                check(s.balance(id) == if (id == "c31") "200" else "1700") { "books ${s.balance(id)}" }
                check(s.balance(if (id == "c31") "c30" else "c31") == if (id == "c31") "2000" else "500") { "the other one changed" }
            }
        }
        c.case("full name pays directly") {
            val s = Shop(l = sameNameLedger(both = true))
            s.say("Madurai Lokesh 300 kuduthaan")
            plan(s.draft(), PlanKind.PAYMENT_IN, "c31", "300")
        }
        c.case("stated: Madurai → due → confirm → Madurai Lokesh") {
            val s = Shop(l = sameNameLedger(both = true))
            has(s.say("Lokesh enakku 100 tharanum"), "Lokesh-aa illa Madurai Lokesh-aa?")
            s.say("Madurai")
            s.say("naalaikku")
            plan(s.draft(), PlanKind.CREDIT_GIVEN, "c31", "100")
            has(s.say("confirm"), "Save aagiduchu")
            check(s.balance("c31") == "600" && s.balance("c30") == "2000") { "books c31 ${s.balance("c31")} c30 ${s.balance("c30")}" }
        }
        c.case("stated: Lokesh dhaan → due → confirm → Lokesh") {
            val s = Shop(l = sameNameLedger(both = true))
            s.say("Lokesh enakku 100 tharanum")
            s.say("Lokesh dhaan")
            s.say("naalaikku")
            has(s.say("confirm"), "Save aagiduchu")
            check(s.balance("c30") == "2100" && s.balance("c31") == "500") { "books c30 ${s.balance("c30")} c31 ${s.balance("c31")}" }
        }
        c.case("third Lokesh offered as new") {
            val s = Shop(l = sameNameLedger(both = true))
            has(s.say("Trichy Lokesh enakku 100 tharanum"), "Illa puthu customer 'Trichy Lokesh'-aa?")
            s.say("pudhu")
            s.say("naalaikku")
            check(s.draft()?.partyId == null && s.draft()?.partyName == "Trichy Lokesh") { "draft ${s.draft()}" }
        }

        // F. Reminders ask which Lokesh the same way (10)
        val reminder = "Lokesh-ku 10 minutes la call panna remind pannu"
        c.case("reminder asks which") {
            val s = Shop(l = sameNameLedger(both = true))
            has(s.say(reminder), "Endha Lokesh? Lokesh-aa, Madurai Lokesh-aa?")
            check(s.tools.reminders.isEmpty()) { "stored before Confirm" }
        }
        listOf("Madurai" to "Madurai Lokesh", "Madurai Lokesh" to "Madurai Lokesh", "2" to "Madurai Lokesh", "Lokesh" to "Lokesh", "1" to "Lokesh",
            "tap:Madurai Lokesh · +919000000031 · Customer" to "Madurai Lokesh").forEach { (pick, who) ->
            c.case("reminder pick '$pick'") {
                val s = Shop(l = sameNameLedger(both = true))
                s.say(reminder)
                val r = if (pick.startsWith("tap:")) s.tap(pick.removePrefix("tap:"))?.reply?.text else s.say(pick)
                has(r, "$who-ku 10 minutes-la call reminder set pannalama?")
                check(s.tools.reminders.isEmpty()) { "stored before Confirm" }
                has(s.say("confirm"), "remind pannuren")
                check(s.tools.reminders.single().person == who) { "stored for ${s.tools.reminders.map { it.person }}" }
            }
        }
        c.case("reminder by full name: not asked") {
            val s = Shop(l = sameNameLedger(both = true))
            has(s.say("Madurai Lokesh-ku 10 minutes la call panna remind pannu"), "Madurai Lokesh-ku 10 minutes-la call reminder set pannalama?")
        }
        c.case("reminder with one Lokesh: not asked") {
            val s = Shop(l = sameNameLedger())
            has(s.say(reminder), "Lokesh-ku 10 minutes-la call reminder set pannalama?")
        }
        c.case("reminder: 'confirm' is the reminder's, not an older payment's") {
            val s = Shop(l = sameNameLedger(both = true))
            s.say("Madurai Lokesh 100 kuduthaan"); s.say("confirm")
            s.say(reminder); s.say("Madurai")
            has(s.say("confirm"), "remind pannuren")
            check(s.tools.reminders.size == 1 && s.balance("c31") == "400") { "reminders ${s.tools.reminders.size}, c31 ${s.balance("c31")}" }
        }

        c.done(105)
    }
}
