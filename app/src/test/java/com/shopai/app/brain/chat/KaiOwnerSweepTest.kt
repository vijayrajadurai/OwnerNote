package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ProductSalesFact
import com.shopai.app.brain.tools.ReminderSaved
import com.shopai.app.brain.tools.ScheduleResult
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One shop, the way an owner talks to Kai all day (owner's request, 9 Oct 2026: "stock, reminder, credit and debit entry and
 * questions about them — understand what the shop owner asks and answer correctly"). Every line is played on a fresh shop
 * and checked in the books, the inventory or the reminders — never on Kai's words alone. Nothing may be saved before Confirm.
 */
class KaiOwnerSweepTest {
    private val now = LocalDateTime.of(2026, 10, 8, 11, 0)
    private val today: LocalDate = now.toLocalDate()
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)
    private val zone = ZoneId.of("Asia/Kolkata")

    // ------------------------------------------------------------------ the shop

    private class Ledger {
        class Entry(val amount: BigDecimal, val created: LocalDate, val due: LocalDate?) {
            val payments = mutableListOf<Pair<BigDecimal, LocalDate>>()
            val paid: BigDecimal get() = payments.fold(BigDecimal.ZERO) { t, p -> t + p.first }
            val open: BigDecimal get() = amount - paid
        }
        class Party(val id: String, val name: String, val customer: Boolean) {
            val entries = mutableListOf<Entry>()
            val pending: BigDecimal get() = entries.fold(BigDecimal.ZERO) { t, e -> t + e.open }
            val nextDue: LocalDate? get() = entries.filter { it.open.signum() > 0 }.minOfOrNull { it.due ?: it.created }
        }
        val parties = mutableListOf<Party>()
        fun add(id: String, name: String, customer: Boolean, amount: String, created: LocalDate, due: LocalDate?): Entry {
            val p = parties.firstOrNull { it.id == id } ?: Party(id, name, customer).also { parties += it }
            return Entry(BigDecimal(amount), created, due).also { p.entries += it }
        }
        fun summary(p: Party) = PartySummary(p.id, p.name, null, p.pending.toDouble(), p.nextDue?.let { "${it}T00:00:00Z" })
        fun snapshot() = BusinessSnapshot(customers = parties.filter { it.customer }.map(::summary), suppliers = parties.filter { !it.customer }.map(::summary))
    }

    private class Books(val l: Ledger) : KaiBooks {
        override suspend fun snapshot() = l.snapshot()
        override suspend fun history(party: PartyFacts): PartyHistory? = l.parties.firstOrNull { it.id == party.id }?.let { p ->
            PartyHistory(party, p.entries.map { e ->
                PartyHistory.LedgerEntry(e.amount.toDouble(), e.paid.toDouble(), e.due, e.created, e.payments.map { PartyHistory.Payment(it.first.toDouble(), it.second) })
            })
        }
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    /** Stock from the inventory fixture; money from the ledger; reminders kept. Only Confirm writes. */
    private class Tools(val inv: Inventory, val l: Ledger, val today: LocalDate) : KaiTools by inv {
        val saved = mutableListOf<ActionPlan>()
        var prepared = 0
        val rems = mutableListOf<KaiReminder>()
        override suspend fun parties(name: String) = l.parties.filter { it.name.contains(name, true) }
            .map { PartyMatch(it.id, it.name, it.customer, null, it.pending) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String): ActionPlan =
            ActionPlan("p${prepared++}", kind, partyName, partyId, amount, mode, balanceBefore = l.parties.firstOrNull { it.id == partyId }?.pending, said = said)
        override suspend fun confirm(plan: ActionPlan): ActionOutcome {
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
        override fun createReminder(reminder: KaiReminder): ReminderSaved { rems += reminder; return ReminderSaved(reminder, false, ScheduleResult.EXACT) }
        override fun reminders() = rems.toList()
        override fun reminderStored(id: String) = rems.any { it.id == id }
        override fun zone() = "Asia/Kolkata"
        override suspend fun lowStock() = inv.items.filter { i -> i.stock.signum() <= 0 || i.minimum?.let { i.stock <= it } == true }
            .map { com.shopai.app.brain.tools.StockFact(it.name, it.stock, it.unit, it.minimum) }
        override suspend fun topProducts(from: LocalDate, to: LocalDate, limit: Int) = listOf(
            ProductSalesFact("Rice", BigDecimal("320"), BigDecimal("17600")), ProductSalesFact("Colgate", BigDecimal("90"), BigDecimal("4500")),
        ).take(limit)
    }

    private inner class Shop(more: Ledger.() -> Unit = {}) {
        val l = Ledger().apply {
            add("c1", "Kumar", true, "5000", d(9, 1), d(9, 15)).payments += BigDecimal("5000") to d(9, 25)
            add("c1", "Kumar", true, "3000", d(9, 20), d(9, 30)).payments += BigDecimal("3000") to d(10, 6)
            add("c1", "Kumar", true, "4000", d(10, 1), d(10, 8))
            add("c2", "Ramesh", true, "2000", d(8, 20), d(9, 1)).payments += BigDecimal("2000") to d(9, 1)
            add("c2", "Ramesh", true, "2500", d(9, 28), d(10, 8))
            add("c3", "Selvam", true, "3000", d(7, 20), d(8, 1)).payments += BigDecimal("3000") to d(8, 20)
            add("c3", "Selvam", true, "6000", d(9, 5), d(9, 20))
            add("c4", "Lakshmi", true, "1000", d(10, 2), d(10, 12))
            add("s1", "ABC Traders", false, "10000", d(9, 25), d(10, 8))
            add("s2", "Murugan Stores", false, "4000", d(10, 1), d(10, 15))
            more()
        }
        val inv = Inventory().apply {
            items += Inventory.Item("p1", "Colgate", "PCS", BigDecimal("240"), mapOf("BOX" to BigDecimal("48")), BigDecimal("28"), BigDecimal("35"), "FMCG", "200 g", BigDecimal("50"))
            items += Inventory.Item("p2", "Rice", "KG", BigDecimal("250"), mapOf("BAG" to BigDecimal("25")), BigDecimal("56"), BigDecimal("65"), "Grocery", null, BigDecimal("50"))
            items += Inventory.Item("p3", "Oil", "BOTTLE", BigDecimal("240"), mapOf("BOX" to BigDecimal("12")), BigDecimal("150"), BigDecimal("170"), "Grocery", "1 litre")
            items += Inventory.Item("p4", "Baniyan", "PCS", BigDecimal("50"), emptyMap(), BigDecimal("60"), BigDecimal("90"), "Garments & Textiles", null)
            items += Inventory.Item("p5", "Lux Soap", "PCS", BigDecimal("60"), emptyMap(), BigDecimal("30"), BigDecimal("38"), "FMCG", null)
            items += Inventory.Item("p6", "Maggi", "PCS", BigDecimal("0"), emptyMap(), BigDecimal("12"), BigDecimal("14"), "FMCG", null, BigDecimal("10"))
        }
        val books = Books(l)
        val tools = Tools(inv, l, today)
        val k = KaiAgent(KaiBusinessBrain(books, today = { today }, random = kotlin.random.Random(1)), books, tools, now = { now })
        val log = StringBuilder()
        fun say(t: String): KaiTurn = runBlocking { k.ask(t) }.also { r ->
            log.append("\n   » $t\n     ${r.reply.text.replace("\n", " / ")}")
            r.card?.let { c -> log.append("\n     [card] ${c.lines.joinToString(" | ")} {${c.buttons.joinToString { it.label }}}") }
        }
        fun act(a: KaiAction): KaiTurn? = runBlocking { k.act(a, KaiLang.TANGLISH) }.also { r -> log.append("\n   [tap] ${r?.reply?.text?.replace("\n", " / ")}") }
        fun nothingWritten() = tools.saved.isEmpty() && inv.moves.isEmpty() && tools.rems.isEmpty() && inv.created.isEmpty()
    }

    // ------------------------------------------------------------------ the checks

    private val failures = mutableListOf<String>()
    private var count = 0
    private fun fail(what: String, s: Shop, why: String) { failures += "[${if (mic) "mic: " else ""}$what] $why${s.log}" }

    /**
     * Every line is played twice: typed, then as Kai Chat's mic writes it ([KaiMicForm]: Tamil script). The text box and the
     * mic go to the same Kai and must do the same thing (owner's rule, 10 Oct 2026) — a fix that works only typed fails here.
     */
    private var mic = false
    /** The owner's line, typed or as the mic writes it (null: the mic would not hear it as typed — not replayed). */
    private fun heard(typed: String): String? = if (mic) KaiMicForm.of(typed) else typed
    /** A short answer to Kai's question, said the same way as the line. */
    private fun answer(typed: String): String = if (mic) KaiMicForm.of(typed) ?: typed else typed

    /** A money entry: drafted, confirmed (answering a due-date question with "due venam"), then exactly this in the books. */
    private fun entry(typed: String, kind: PlanKind, party: String, amount: String, due: LocalDate? = null, mode: PaymentMode? = null) {
        val said = heard(typed) ?: return
        count++
        val s = Shop()
        var t = s.say(said)
        repeat(4) {
            if (s.tools.saved.isNotEmpty()) return@repeat
            val confirm = t.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmPlan }
            if (confirm != null) {
                if (!s.nothingWritten()) return fail(said, s, "saved before Confirm")
                t = s.act(confirm.action) ?: t
                return@repeat
            }
            val txt = t.reply.text
            t = when {
                Regex("""(?i)due date eppa|eppo tharuvaanga|eppa tharuvaanga|eppo kudukkanum|eppa kudukkanum|due date""").containsMatchIn(txt) && txt.trim().endsWith("?") -> s.say(answer("due venam"))
                // "records-la already ₹4,000 irukku — adhey-aa, illa pudhu-aa?": this is a new entry.
                txt.contains("illa pudhu") || txt.contains("இல்ல புது") -> s.say(answer("pudhu"))
                // "'Lakshmi Akka' — Lakshmi dhaan-aa, illa Lakshmi-oda akka-aa?": the owner means the person in the books.
                (txt.contains("dhaan-aa") || txt.contains("தானா")) && t.card?.buttons?.isNotEmpty() == true -> s.act(t.card!!.buttons.first().action) ?: t
                else -> return fail(said, s, "no draft to confirm")
            }
        }
        val p = s.tools.saved.singleOrNull() ?: return fail(said, s, "saved ${s.tools.saved.size} entries")
        if (p.kind != kind) return fail(said, s, "kind ${p.kind} ≠ $kind")
        // Spoken, a new name stays in Tamil letters ("சுஜித்") — the same name.
        if (!p.partyName.equals(party, true) && !(mic && com.shopai.app.util.NameSound.same(p.partyName, party)))
            return fail(said, s, "party ${p.partyName} ≠ $party")
        if (p.amount.compareTo(BigDecimal(amount)) != 0) return fail(said, s, "amount ${p.amount} ≠ $amount")
        if (due != null && p.dueDate != due) return fail(said, s, "due ${p.dueDate} ≠ $due")
        if (mode != null && p.mode != mode) return fail(said, s, "mode ${p.mode} ≠ $mode")
    }

    /** A reminder: asked, confirmed, stored at that local time with that person / words. */
    private fun reminder(typed: String, at: LocalDateTime?, has: String? = null, repeat: Boolean = false) {
        val said = heard(typed) ?: return
        count++
        val s = Shop()
        val t = s.say(said)
        if (s.tools.rems.isNotEmpty()) return fail(said, s, "set before Confirm")
        val b = t.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmReminder } ?: return fail(said, s, "no reminder to confirm")
        s.act(b.action)
        val r = s.tools.rems.singleOrNull() ?: return fail(said, s, "stored ${s.tools.rems.size} reminders")
        val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(r.triggerAt), zone)
        if (at != null && time != at) return fail(said, s, "at $time ≠ $at")
        val words = r.title + " " + r.task + " " + (r.person ?: "")
        if (has != null && !words.contains(has, true) && !(mic && KaiMicForm.of(has)?.let { words.contains(it) } == true)) return fail(said, s, "'${r.title}' / '${r.task}' lacks '$has'")
        if (repeat && r.recurrence.repeat == com.shopai.app.brain.tools.Repeat.ONCE) return fail(said, s, "not repeating")
        if (s.tools.saved.isNotEmpty() || s.inv.moves.isNotEmpty()) return fail(said, s, "a reminder wrote money / stock")
    }

    /** Stock: drafted, confirmed (new products: answer what Kai asks), the product's stock after. */
    private fun stock(typed: String, product: String, after: String) {
        val said = heard(typed) ?: return
        count++
        val s = Shop()
        var t = s.say(said)
        repeat(8) {
            val b = t.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmStock || it.action is KaiAction.ConfirmPlan }
            if (b != null) {
                if (s.inv.moves.isNotEmpty()) return fail(said, s, "stock changed before Confirm")
                s.act(b.action)
                // Spoken, a new product keeps the name as said ("ஹார்லிக்ஸ்") — the same product.
                val i = s.inv.items.firstOrNull { it.name.equals(product, true) || mic && com.shopai.app.util.NameSound.same(it.name, product) } ?: return fail(said, s, "'$product' not in inventory: ${s.inv.items.map { it.name }}")
                if (i.stock.compareTo(BigDecimal(after)) != 0) fail(said, s, "$product stock ${i.stock.toPlainString()} ≠ $after")
                if (s.tools.saved.isNotEmpty() && s.inv.bills.isEmpty()) fail(said, s, "stock wrote a money entry")
                return
            }
            val txt = t.reply.text
            t = when {
                // "petti" is the owner's word for box; Kai asks once which it is.
                (txt.contains("pieces-aa") || txt.contains("pieces-ஆ")) && typed.contains("petti") -> s.say(answer("box"))
                txt.contains("pieces-aa") || txt.contains("pieces-ஆ") -> s.say(answer("pieces"))
                txt.contains("evlo pieces") || txt.contains("எத்தனை pieces") -> s.say("12")
                txt.contains("rate evlo") || txt.contains("rate எவ்வளவு") || txt.contains("'skip'") -> s.say(answer("skip"))
                txt.contains("Stock add pannattuma") -> s.say(answer("aama"))
                else -> return fail(said, s, "no stock draft")
            }
        }
        fail(said, s, "never reached Confirm")
    }

    /** A question: answered from the records — these words in the reply (or card), never those; nothing written. */
    private fun ask(typed: String, wanted: List<String>, not: List<String> = emptyList()) {
        val said = heard(typed) ?: return
        count++
        val s = Shop()
        val t = s.say(said)
        val all = t.reply.text + " " + t.card?.lines.orEmpty().joinToString(" ")
        // Spoken, Kai answers in Tamil: the amounts, numbers and names must still be there.
        val has = if (mic) wanted.filter(KaiMicForm::keepsInTamil).map(KaiMicForm::inTamil) else wanted
        has.firstOrNull { !all.contains(it, true) }?.let { return fail(said, s, "lacks '$it'") }
        not.firstOrNull { all.contains(it, true) }?.let { return fail(said, s, "has '$it'") }
        if (!s.nothingWritten()) fail(said, s, "a question wrote something")
    }

    // ------------------------------------------------------------------ the lines

    private fun moneyLines() {
        val IN = PlanKind.PAYMENT_IN; val OUT = PlanKind.PAYMENT_OUT; val CR = PlanKind.CREDIT_GIVEN; val DR = PlanKind.DEBIT_TAKEN
        entry("Kumar 2000 kuduthan", IN, "Kumar", "2000")
        entry("Kumar 2000 kuduthaan", IN, "Kumar", "2000")
        entry("kumar 2000 kuduthutan", IN, "Kumar", "2000")
        entry("Kumar 2,000 rs kuduthan", IN, "Kumar", "2000")
        entry("Kumar rendu aayiram kuduthan", IN, "Kumar", "2000")
        entry("Kumar kitta 2000 vaanginen", IN, "Kumar", "2000")
        entry("Kumar 2000 cash kuduthan", IN, "Kumar", "2000", mode = PaymentMode.CASH)
        entry("Kumar gpay la 1500 anuppinan", IN, "Kumar", "1500", mode = PaymentMode.UPI)
        entry("Kumar 1500 gpay pannitan", IN, "Kumar", "1500", mode = PaymentMode.UPI)
        entry("Ramesh 2500 full ah kuduthutan", IN, "Ramesh", "2500")
        entry("Lakshmi 500 kuduthanga", IN, "Lakshmi", "500")
        entry("Selvam 3000 vandhuchu", IN, "Selvam", "3000")
        entry("Selvam kitta irundhu 3000 vandhuchu", IN, "Selvam", "3000")
        entry("Received 2500 from Ramesh", IN, "Ramesh", "2500")
        entry("Ramesh paid 2500", IN, "Ramesh", "2500")
        entry("குமார் 2000 கொடுத்தான்", IN, "Kumar", "2000")
        entry("ABC Traders ku 5000 kuduthen", OUT, "ABC Traders", "5000")
        entry("ABC Traders-ku 5000 pay panniten", OUT, "ABC Traders", "5000")
        entry("Murugan Stores ku 4000 anuppinen", OUT, "Murugan Stores", "4000")
        entry("Paid 3000 to ABC Traders", OUT, "ABC Traders", "3000")
        entry("Kumar ku 3000 credit kuduthen", CR, "Kumar", "3000")
        entry("Kumar-ku 3000 kadan kuduthen", CR, "Kumar", "3000")
        entry("Muthu ku 1500 credit kuduthen", CR, "Muthu", "1500")
        entry("Kumar enakku 3000 tharanum", CR, "Kumar", "3000")
        entry("Muthu enaku 5000 tharanum", CR, "Muthu", "5000")
        entry("Ganesh 700 tharanum", CR, "Ganesh", "700")
        entry("Kumar 3000 baaki", CR, "Kumar", "3000")
        entry("Naan Murugan Stores ku 4000 kudukkanum", DR, "Murugan Stores", "4000")
        entry("ABC Traders ku 8000 kudukkanum", DR, "ABC Traders", "8000")
        entry("Kumar enakku 3000 tharanum, next month 5th", CR, "Kumar", "3000", due = d(11, 5))
        entry("Muthu 2000 tharanum 15th", CR, "Muthu", "2000", due = d(10, 15))
        // Round 2: other spellings and ways of saying it.
        entry("Kumar 500 kuduthaaru", IN, "Kumar", "500")
        entry("Kumar 500 thandhaan", IN, "Kumar", "500")
        entry("Kumar 500 thanthan", IN, "Kumar", "500")
        entry("Kumar kitta 500 vangunen", IN, "Kumar", "500")
        entry("Kumar 500 phonepe pannitan", IN, "Kumar", "500", mode = PaymentMode.UPI)
        entry("Kumar 500 upi la anuppitan", IN, "Kumar", "500", mode = PaymentMode.UPI)
        entry("Kumar 500 gpay", IN, "Kumar", "500", mode = PaymentMode.UPI)
        entry("Kumar paid 500 by gpay", IN, "Kumar", "500", mode = PaymentMode.UPI)
        entry("Got 500 from Kumar", IN, "Kumar", "500")
        entry("Kumar gave me 500", IN, "Kumar", "500")
        entry("Ramesh ippo 1000 kuduthan", IN, "Ramesh", "1000")
        entry("Innaiku Ramesh 1000 kuduthan", IN, "Ramesh", "1000")
        entry("Lakshmi akka 500 kuduthanga", IN, "Lakshmi", "500")
        entry("Selvam anna 2000 kuduthar", IN, "Selvam", "2000")
        entry("செல்வம் 3000 கொடுத்தார்", IN, "Selvam", "3000")
        entry("ABC Traders ku 2000 gpay pannen", OUT, "ABC Traders", "2000", mode = PaymentMode.UPI)
        entry("ABC Traders ku 2000 cash ah kuduthen", OUT, "ABC Traders", "2000", mode = PaymentMode.CASH)
        entry("Naan ABC Traders ku 2000 kuduthen", OUT, "ABC Traders", "2000")
        entry("I paid ABC Traders 2000", OUT, "ABC Traders", "2000")
        entry("Murugan Stores ku 1000 anupiten", OUT, "Murugan Stores", "1000")
        entry("Kumar ku 1000 udhaar kuduthen", CR, "Kumar", "1000")
        entry("Kumar 1000 ku saamaan credit la eduthutu ponaan", CR, "Kumar", "1000")
        entry("Kumar 1000 credit", CR, "Kumar", "1000")
        entry("Ravi ku 2500 kadan", CR, "Ravi", "2500")
        entry("ABC Traders kitta 8000 ku credit la maal vaanginen", DR, "ABC Traders", "8000")
        entry("Kumar enaku 1000 tharanum", CR, "Kumar", "1000")
        entry("Kumar ennaku 1000 kudukanum", CR, "Kumar", "1000")
        entry("ABC Traders ku naan 3000 tharanum", DR, "ABC Traders", "3000")
        entry("Ravi enakku 2000 tharanum naalaiku", CR, "Ravi", "2000", due = d(10, 9))
    }

    private fun reminderLines() {
        reminder("naalaiku kaalaila 10 manikku Kumar ku call panna remind pannu", LocalDateTime.of(2026, 10, 9, 10, 0), "Kumar")
        reminder("naalaiku 10 maniku Kumar-ku call panna remind pannu", LocalDateTime.of(2026, 10, 9, 10, 0), "Kumar")
        reminder("10 nimisham kalichu Selvam kitta panam vaanga remind pannu", now.plusMinutes(10), "Selvam")
        reminder("10 minutes la Ramesh ku call panna remind pannu", now.plusMinutes(10), "Ramesh")
        reminder("saayangalam 6 manikku kadai saavi eduka remind pannu", LocalDateTime.of(2026, 10, 8, 18, 0), "saavi")
        reminder("evening 6 pm saavi eduka nyabagam paduthu", LocalDateTime.of(2026, 10, 8, 18, 0), "saavi")
        reminder("Monday 9 am ABC Traders payment remind pannu", LocalDateTime.of(2026, 10, 12, 9, 0), "ABC Traders")
        reminder("remind me tomorrow at 5 pm to call Ramesh", LocalDateTime.of(2026, 10, 9, 17, 0), "Ramesh")
        reminder("2 manikku GST file panna remind pannu", LocalDateTime.of(2026, 10, 8, 14, 0), "GST")
        reminder("daily kaalaila 8 manikku kadai thirakka remind pannu", null, repeat = true)
        reminder("naalaiku Lakshmi kitta collection remind pannu 11 manikku", LocalDateTime.of(2026, 10, 9, 11, 0), "Lakshmi")
        reminder("நாளைக்கு காலை 10 மணிக்கு குமாருக்கு கால் பண்ண நினைவூட்டு", LocalDateTime.of(2026, 10, 9, 10, 0))
        // Round 2.
        reminder("naalaiku 10 am Kumar call", LocalDateTime.of(2026, 10, 9, 10, 0), "Kumar")
        reminder("1 hour kalichu Selvam ku phone pannanum nu remind pannu", now.plusHours(1), "Selvam")
        reminder("half an hour la remind me to call Kumar", now.plusMinutes(30), "Kumar")
        reminder("innaiku saayangalam 7 manikku bank poganum remind pannu", LocalDateTime.of(2026, 10, 8, 19, 0), "bank")
        reminder("naalai kaalai 9 manikku ABC Traders ku payment pannanum remind pannu", LocalDateTime.of(2026, 10, 9, 9, 0), "ABC Traders")
        reminder("15th 10 manikku Lakshmi kitta panam vaanganum remind pannu", LocalDateTime.of(2026, 10, 15, 10, 0), "Lakshmi")
        reminder("every monday 9 am stock check remind pannu", null, "stock", repeat = true)
        reminder("Friday 5 pm Murugan Stores payment nyabagam paduthu", LocalDateTime.of(2026, 10, 9, 17, 0), "Murugan")
        reminder("remind me at 6 pm to close the shop", LocalDateTime.of(2026, 10, 8, 18, 0), "close")
    }

    private fun stockLines() {
        stock("Colgate 2 box vandhuchu", "Colgate", "336")
        stock("Colgate 2 box vanthuchu", "Colgate", "336")
        stock("Colgate oru box add pannu", "Colgate", "288")
        stock("Colgate 20 pcs add pannu", "Colgate", "260")
        stock("Rice 3 moota vandhirukku", "Rice", "325")
        stock("Rice 3 bag vandhuchu", "Rice", "325")
        stock("Rice 50 kg add pannu", "Rice", "300")
        stock("Rice 2 bag pochu", "Rice", "200")
        stock("Oil 5 bottle damage", "Oil", "235")
        stock("Oil 1 box vandhuchu", "Oil", "252")
        stock("Baniyan 20 pcs add pannu", "Baniyan", "70")
        stock("bhaniyan 20 add pannu", "Baniyan", "70")
        stock("Lux soap 12 vandhuchu", "Lux Soap", "72")
        stock("Lux 12 vandhuchu", "Lux Soap", "72")
        stock("Maggi 30 add pannu", "Maggi", "30")
        stock("Colgate 10 vithuchu", "Colgate", "230")
        stock("Colgate 10 sale aachu", "Colgate", "230")
        stock("கோல்கேட் 2 பாக்ஸ் வந்தது", "Colgate", "336")
        stock("men shorts 60 add pannu", "Men Shorts", "60")
        stock("5inch tape 10 add pannu", "5 inch Tape", "10")
        stock("Horlicks 12 bottle vandhuchu", "Horlicks", "12")
        // Round 2.
        stock("Colgate 2 petti vandhuchu", "Colgate", "336")
        stock("Colgate rendu box vandhuchu", "Colgate", "336")
        stock("2 box Colgate vandhuchu", "Colgate", "336")
        stock("Colgate 1 box 10 pcs vandhuchu", "Colgate", "298")
        stock("Rice 25 kg moota 2 vandhuchu", "Rice", "300")
        stock("Rice 10 kg vithuchu", "Rice", "240")
        stock("Rice 10 kg sale", "Rice", "240")
        stock("Oil 2 bottle udanjiduchu", "Oil", "238")
        stock("Oil 2 bottle damage aagiduchu", "Oil", "238")
        stock("Oil 3 bottle return vandhuchu", "Oil", "243")
        stock("Lux soap 10 vithuten", "Lux Soap", "50")
        stock("Baniyan 5 pochu", "Baniyan", "45")
        stock("Received 2 boxes of Colgate", "Colgate", "336")
        stock("Sold 10 Colgate", "Colgate", "230")
        stock("கோல்கேட் 10 வித்துச்சு", "Colgate", "230")
        stock("அரிசி 2 மூட்டை வந்தது", "Rice", "300")
        stock("Maggi 2 box vandhuchu", "Maggi", "24")
    }

    private fun questionLines() {
        ask("Kumar evlo tharanum", listOf("₹4,000"))
        ask("Kumar balance enna", listOf("₹4,000"))
        ask("Kumar kitta evlo vaanganum", listOf("₹4,000"))
        ask("How much does Kumar owe?", listOf("₹4,000"))
        ask("குமார் எவ்வளவு தரணும்", listOf("₹4,000"))
        ask("Kumar due eppo", listOf("Kumar"), listOf("Selvam"))
        ask("Lakshmi eppo tharuvanga", listOf("October 12"))
        ask("Naan ABC Traders ku evlo kudukkanum", listOf("₹10,000"))
        ask("ABC Traders ku evlo kudukkanum", listOf("₹10,000"))
        ask("Innaiku yar yar tharanum", listOf("Kumar", "Ramesh"), listOf("Selvam ₹6,000 —"))
        ask("Innaiku yar payment tharanum", listOf("Kumar", "Ramesh"))
        ask("Innaiku yarukita payment vanganum", listOf("Kumar", "Ramesh"), listOf("remind"))
        ask("Innaiku yaruku payment tharanum", listOf("ABC Traders"), listOf("Murugan"))
        ask("Innaiku yaruku kudukkanum", listOf("ABC Traders"), listOf("Murugan"))
        ask("Overdue list sollu", listOf("Selvam"))
        ask("Yaar yaar late ah tharanga", listOf("Selvam", "Kumar"))
        ask("Mothama evlo varanum", listOf("₹13,500"))
        ask("Total evlo kudukkanum", listOf("₹14,000"))
        ask("Highest pending yaar kitta", listOf("Selvam"))
        ask("Who owes me the most?", listOf("Selvam"))
        ask("Selvam last payment eppo", listOf("August 20"))
        ask("Kumar correct date la tharuvana", listOf("late"))
        ask("Colgate stock evlo", listOf("240"))
        ask("Colgate evlo irukku", listOf("240"))
        ask("Rice stock evlo irukku", listOf("250"))
        ask("Today stock evlo iruku", listOf("Colgate", "Rice", "Oil"))
        ask("Low stock enna", listOf("Maggi"), listOf("Colgate —"))
        ask("Enna stock kammiya irukku", listOf("Maggi"))
        ask("Yentha stock fast move aguthu", listOf("Rice"))
        ask("Yentha stock move agala", listOf("Baniyan"), listOf("Rice —"))
        ask("Naalaiku yaruku payment pannanum", listOf("illa"))
        // Round 2.
        ask("Kumar pending evlo", listOf("₹4,000"))
        ask("Kumar baaki evlo", listOf("₹4,000"))
        ask("Kumar evlo baaki irukku", listOf("₹4,000"))
        ask("Kumar kitta evlo varanum", listOf("₹4,000"))
        ask("Kumar oda balance", listOf("₹4,000"))
        ask("Selvam evlo tharanum", listOf("₹6,000"))
        ask("Selvam due date eppo", listOf("September 20"))
        ask("ABC Traders balance evlo", listOf("₹10,000"))
        ask("Murugan Stores ku eppo kudukkanum", listOf("October 15"))
        ask("Yaar yaar ku naan kudukkanum", listOf("ABC Traders", "Murugan Stores"))
        ask("Supplier ku evlo kudukkanum", listOf("₹14,000"))
        ask("Customers evlo tharanum mothama", listOf("₹13,500"))
        ask("Pending list", listOf("Kumar", "Ramesh", "Selvam", "Lakshmi"))
        ask("Ellaa customers pending list pannu", listOf("Kumar", "Ramesh", "Selvam", "Lakshmi"))
        ask("Date thaandi yaar yaar tharanum", listOf("Selvam"))
        ask("Naalaiku yaar tharanum", listOf("illa"), listOf("Kumar ₹"))
        ask("Next week yaar tharanum", listOf("Lakshmi"))
        ask("Indha vaaram yaar yaar tharanum", listOf("Kumar", "Ramesh"))
        ask("Kumar history", listOf("Kumar"))
        ask("Kumar last ah eppo kuduthan", listOf("October 6"))
        ask("Ramesh time ku tharuvara", listOf("Ramesh"), listOf("late-aa dhaan"))
        ask("Selvam usually late ah", listOf("late"))
        ask("Rice evlo irukku", listOf("250"))
        ask("Arisi stock evlo", listOf("250"))
        ask("Oil stock", listOf("240"))
        ask("Maggi irukka", listOf("0"))
        ask("Endha product kammiya irukku", listOf("Maggi"))
        ask("Reorder panna vendiyadhu enna", listOf("Maggi"))
        ask("Indha maasam adhigama vithadhu enna", listOf("Rice"))
        ask("Endha item vikkala", listOf("Baniyan"))
        ask("Kumar phone number", listOf("Kumar"), listOf("₹4,000 tharanum"))
    }

    /** A short conversation: each line said in turn, then the books / stock / reminders checked. */
    private fun talk(what: String, lines: List<String>, more: Ledger.() -> Unit = {}, check: (Shop) -> String?) {
        count++
        val s = Shop(more)
        for (l in lines) {
            if (l == "TAP") {
                val b = lastTurn?.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmPlan || it.action is KaiAction.ConfirmStock || it.action is KaiAction.ConfirmReminder }
                    ?: return fail(what, s, "nothing to tap")
                s.act(b.action)
            } else lastTurn = s.say(l)
        }
        check(s)?.let { fail(what, s, it) }
    }
    private var lastTurn: KaiTurn? = null

    private fun conversationLines() {
        // The owner's conversation (10 Oct 2026): what was said before, push-back, and what Kai can't do — nothing invented,
        // nothing written by itself.
        fun said(vararg words: String): (Shop) -> String? = { _ -> lastTurn?.reply?.text.orEmpty().let { t -> if (words.all(t::contains)) null else "said: $t" } }
        fun nothingWritten(vararg words: String): (Shop) -> String? = { s -> said(*words)(s) ?: if (s.nothingWritten()) null else "wrote something" }
        fun button(kind: String): (Shop) -> String? = { _ -> if (lastTurn?.card?.buttons?.any { (it.action as? KaiAction.OpenRecord)?.kind == kind } == true) null else "no $kind button" }
        talk("both of them", listOf("Selvam evlo tharanum", "Ramesh?", "rendu perum total evlo"), check = said("Selvam ₹6,000", "Ramesh ₹2,500", "₹8,500"))
        talk("both of them, spoken", listOf("செல்வம் எவ்வளவு தரணும்", "ரமேஷ் எவ்வளவு தரணும்", "ரெண்டு பேரும் மொத்தம் எவ்வளவு"), check = said("₹8,500"))
        talk("the first person asked", listOf("Kumar evlo tharanum", "Colgate stock evlo", "Lakshmi due date eppo", "first ketta aal evlo tharanum nu sonna"),
            check = said("Kumar", "₹4,000"))
        talk("the same product again", listOf("Colgate stock evlo", "Lux soap evlo irukku", "ippo evlo irukku"), check = said("Lux Soap", "60"))
        talk("not Kumar — Ramesh", listOf("Kumar 2000 kuduthan", "illa thappu, Ramesh dhaan kuduthan", "TAP")) { s ->
            if (s.tools.saved.singleOrNull()?.let { it.partyName == "Ramesh" && it.amount.compareTo(BigDecimal("2000")) == 0 } == true) null
            else "saved ${s.tools.saved.map { "${it.partyName} ${it.amount}" }}" }
        talk("not now", listOf("Kumar 2000 kuduthan", "ippo venam aprom paakalam"), check = nothingWritten())
        talk("the owner disputes a balance", listOf("Kumar evlo tharanum", "illa avan 3000 dhaan tharanum"), check = { s ->
            nothingWritten("₹4,000", "₹3,000", "₹1,000")(s) ?: button("CUSTOMER")(s) })
        talk("take back a saved entry", listOf("Kumar 2000 kuduthan", "TAP", "thappa pottutta, adha cancel pannu"), check = { s ->
            said("TXN-1", "Cancel")(s) ?: button("CUSTOMER")(s) ?: if (s.tools.saved.size == 1) null else "saved ${s.tools.saved.size}" })
        talk("change a saved entry", listOf("Ramesh 1500 kuduthan", "TAP", "1500 illa 1000 dhaan, maathu"), check = { s ->
            said("TXN-1", "Ramesh 1000 kuduthan")(s) ?: if (s.tools.saved.size == 1) null else "saved ${s.tools.saved.size}" })
        talk("take back, spoken", listOf("குமார் 2000 குடுத்தான்", "TAP", "தப்பா போட்டுட்ட, கேன்சல் பண்ணு"), check = said("TXN-1", "Cancel"))
        talk("Kai got it wrong", listOf("Selvam evlo tharanum", "nee sonnadhu thappu"), check = nothingWritten("Mannichikonga"))
        talk("puriyala: again, in Tamil", listOf("Selvam evlo tharanum", "puriyala"), check = said("தமிழ்ல", "₹6,000"))
        talk("GST bill: the billing screen, not the scanner", listOf("GST bill podu Kumar ku 5000"), check = { s ->
            nothingWritten("Billing")(s) ?: button("SALE_BILL")(s) ?: if (lastTurn?.card?.buttons?.any { it.action == KaiAction.OpenScanner } == true) "opened the scanner" else null })
        talk("WhatsApp: can't send, can remind", listOf("Kumar ku whatsapp la payment reminder anuppu"), check = said("WhatsApp", "mudiyaadhu", "remind"))
        for ((ask, word) in listOf("naalaiku mazhai varuma" to "weather", "ipl score enna" to "cricket", "tamil nadu cm yaaru" to "news", "oru joke sollu" to "joke",
                "enakku loan venum" to "loan", "employee salary kanakku podu" to "salary", "amazon la 10 kg rice order pannu" to "Amazon", "நாளைக்கு மழை வருமா" to "வானிலை"))
            talk("not Kai's work: $ask", listOf(ask), check = nothingWritten(word))
        talk("a date", listOf("1 varusham kalichi enna date"), check = said("October 8, 2027"))
        // Paid more than owed (owner's conversation, 10 Oct 2026): the extra is said before Confirm and after, and
        // "ippa evlo tharanum" is about the person just talked about.
        val akash: Ledger.() -> Unit = { add("c8", "Akash", true, "5000", d(9, 25), d(10, 5)) }
        talk("paid more than owed", listOf("Akash evlo tharnum Kai", "Avan 6000 kuduthutan"), akash, said("₹5,000", "₹6,000", "₹1,000", "advance"))
        talk("paid more than owed, saved", listOf("Akash evlo tharnum Kai", "Avan 6000 kuduthutan", "TAP", "Ippa evlo tharanum"), akash) { s ->
            said("Akash", "₹1,000 advance")(s) ?: if (s.tools.saved.singleOrNull()?.amount?.compareTo(BigDecimal("6000")) == 0) null else "saved ${s.tools.saved.map { it.amount }}" }
        talk("paid part, then 'ippa evlo'", listOf("Akash evlo tharanum", "avan 2000 kuduthutan", "TAP", "ippa evlo tharanum"), akash, said("Akash", "₹3,000"))
        talk("paid more than owed, spoken", listOf("ஆகாஷ் எவ்வளவு தரணும்", "அவன் 6000 குடுத்துட்டான்", "TAP", "இப்ப எவ்வளவு தரணும்"), akash, said("Akash", "₹1,000 advance"))
        talk("a reminder at 5 pm is still a reminder", listOf("remind me tomorrow 5 pm to call Kumar", "TAP")) { s -> if (s.tools.rems.size == 1) null else "no reminder" }
        // From the owner's phone (9 Oct 2026): a bill with no due date was said "— innaikku" in the summary while its details said
        // "Due date illa", "andha 12 peroda details" showed the 2 due today instead of the 12 overdue, and "2 TV vaangi irukken"
        // wasn't understood. (An entry with no due date is still listed by its bill date — only never given that date.)
        val noDue: Ledger.() -> Unit = {
            add("c5", "Madhan", true, "2000", d(10, 8), null)
            add("c6", "Divya", true, "1500", d(9, 1), null)
            add("c7", "Anbu", true, "700", d(8, 1), d(9, 2)); add("c8", "Bala", true, "900", d(8, 1), d(9, 3)); add("c9", "Priya", true, "300", d(8, 1), d(9, 4))
        }
        for ((ask, more) in listOf("innaiku yaar enakku payment tharanum" to "andha 5 peroda details sollu",
                "இன்னைக்கு யார் எனக்கு பேமெண்ட் தரணும்" to "அந்த 5 பேரோட டீடைல் சொல்லு")) {
            talk("no due date is never said as today: $ask", listOf(ask), noDue) { lastTurn?.reply?.text.orEmpty().let { t ->
                if (Regex("""Madhan ₹2,000 — due date (illa|இல்ல)""").containsMatchIn(t) && Regex("""\b5\s*(per|பேர)""").containsMatchIn(t)) null else "said: $t" } }
            talk("the people the answer counted: $more", listOf(ask, more), noDue) { lastTurn?.reply?.text.orEmpty().let { t ->
                if (listOf("Selvam", "Divya", "Anbu", "Bala", "Priya").all(t::contains) && !t.contains("Kumar") && !t.contains("Madhan")) null else "said: $t" } }
        }
        talk("amount corrected before Confirm", listOf("Kumar 2000 kuduthan", "illa 2500", "aama")) { s ->
            val p = s.tools.saved.singleOrNull()
            when { p == null -> "saved ${s.tools.saved.size}"; p.amount.compareTo(BigDecimal("2500")) != 0 -> "saved ${p.amount}"; else -> null } }
        talk("cancelled draft saves nothing", listOf("Kumar 2000 kuduthan", "venam")) { s -> if (s.nothingWritten()) null else "saved after venam" }
        talk("cancel word", listOf("ABC Traders ku 5000 kuduthen", "cancel")) { s -> if (s.nothingWritten()) null else "saved after cancel" }
        talk("asked if saved before Confirm", listOf("Kumar 2000 kuduthan", "save pannitiya")) { s ->
            if (s.nothingWritten() && !s.log.contains("Save aagiduchu")) null else "claimed / saved without Confirm" }
        talk("balance read back after a payment", listOf("Kumar 2000 kuduthan", "aama", "Kumar evlo tharanum")) { s ->
            if (s.log.contains("₹2,000 tharanum")) null else "balance not read back as ₹2,000" }
        talk("follow-up 'avan' after a person", listOf("Kumar evlo tharanum", "avan eppo tharuvan")) { s ->
            if (s.log.lines().last { it.isNotBlank() }.contains("Kumar")) null else "follow-up lost Kumar" }
        talk("stock draft cancelled", listOf("Colgate 2 box vandhuchu", "venam")) { s -> if (s.inv.moves.isEmpty()) null else "stock changed after venam" }
        talk("stock quantity corrected", listOf("Rice 3 bag vandhuchu", "illa 4 bag dhaan", "TAP")) { s ->
            if (s.inv.item("Rice").stock.compareTo(BigDecimal("350")) == 0) null else "Rice ${s.inv.item("Rice").stock}" }
        talk("reminder cancelled before Confirm", listOf("naalaiku 10 manikku Kumar ku call panna remind pannu", "venam")) { s -> if (s.tools.rems.isEmpty()) null else "reminder set after venam" }
        talk("list then details", listOf("Innaiku yar tharanum", "details sollu")) { s ->
            if (s.log.contains("1. Kumar") && s.log.contains("2. Ramesh")) null else "no numbered list" }
        talk("list then total", listOf("Overdue list sollu", "avanga total evlo")) { s -> if (s.log.contains("₹6,000")) null else "no total" }
        talk("topic switch keeps the draft open", listOf("Kumar 2000 kuduthan", "Colgate stock evlo", "aama")) { s ->
            if (s.tools.saved.singleOrNull()?.partyName == "Kumar") null else "draft lost: ${s.tools.saved.map { it.partyName }}" }
    }

    /** KAI's own feed of owner lines (src/test/resources/kai/owner-lines.txt): each played like the lines above. */
    private fun feedLines() {
        val text = javaClass.getResourceAsStream("/kai/owner-lines.txt")?.bufferedReader()?.readText()
            ?: return run { failures += "[owner-lines.txt] not found on the test classpath" }
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim().takeIf { it.isNotEmpty() } ?: continue
            val (kind, said, want) = line.split('|').map { it.trim() }.takeIf { it.size == 3 } ?: run { failures += "[owner-lines.txt] bad line: $raw"; null } ?: continue
            when (kind.uppercase()) {
                "ENTRY" -> {
                    val w = want.split(Regex("""\s+"""))
                    val plan = when (w.first().uppercase()) { "IN" -> PlanKind.PAYMENT_IN; "OUT" -> PlanKind.PAYMENT_OUT; "CREDIT" -> PlanKind.CREDIT_GIVEN; else -> PlanKind.DEBIT_TAKEN }
                    entry(said, plan, w.drop(1).dropLast(1).joinToString(" "), w.last())
                }
                "STOCK" -> stock(said, want.substringBeforeLast(' ').trim(), want.substringAfterLast(' ').trim())
                "REMIND" -> {
                    val w = want.split(Regex("""\s+"""), limit = 3)
                    reminder(said, LocalDateTime.parse("${w[0]}T${w[1]}"), w.getOrNull(2))
                }
                "ASK" -> ask(said, want.split(';').map { it.trim() }.filter { it.isNotEmpty() })
                else -> failures += "[owner-lines.txt] unknown kind '$kind': $raw"
            }
        }
    }

    @Test fun ownersDay() {
        moneyLines(); reminderLines(); stockLines(); questionLines(); conversationLines(); feedLines()
        // The same lines said into the mic.
        mic = true
        moneyLines(); reminderLines(); stockLines(); questionLines(); feedLines()
        mic = false
        System.getProperty("sweep.out")?.let { java.io.File(it).writeText("$count lines, ${failures.size} failed\n\n" + failures.joinToString("\n\n")) }
        assertTrue("${failures.size} of $count owner lines failed:\n\n" + failures.joinToString("\n\n"), failures.isEmpty())
    }
}
