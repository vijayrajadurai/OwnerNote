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
        /** A party's phone number (by id), when the books have one. */
        val phones = mutableMapOf<String, String>()
        fun add(id: String, name: String, customer: Boolean, amount: String, created: LocalDate, due: LocalDate?): Entry {
            val p = parties.firstOrNull { it.id == id } ?: Party(id, name, customer).also { parties += it }
            return Entry(BigDecimal(amount), created, due).also { p.entries += it }
        }
        fun summary(p: Party) = PartySummary(p.id, p.name, phones[p.id], p.pending.toDouble(), p.nextDue?.let { "${it}T00:00:00Z" })
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
        /** Sales, purchases, expenses by month ("2026-10"); none by default (Kai then says the books don't have them). */
        var money: Map<String, Triple<BigDecimal, BigDecimal, BigDecimal>> = emptyMap()
        override suspend fun sales(from: LocalDate, to: LocalDate) = money[from.toString().take(7)]?.first
        override suspend fun purchases(from: LocalDate, to: LocalDate) = money[from.toString().take(7)]?.second
        override suspend fun expenses(from: LocalDate, to: LocalDate) = money[from.toString().take(7)]?.third
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
    private fun fail(what: String, s: Shop, why: String) { failures += "[${if (written) "mic written: " else if (mic) "mic: " else ""}$what] $why${s.log}" }

    /**
     * Every line is played twice: typed, then as Kai Chat's mic writes it ([KaiMicForm]: Tamil script). The text box and the
     * mic go to the same Kai and must do the same thing (owner's rule, 10 Oct 2026) — a fix that works only typed fails here.
     */
    private var mic = false
    /** The mic writing it in written (formal) Tamil ([KaiMicForm.written]) — the third way each line is played. */
    private var written = false
    /** The owner's line, typed or as the mic writes it (null: the mic would not hear it as typed — not replayed). */
    private fun heard(typed: String): String? = if (!mic) typed else KaiMicForm.of(typed)?.let { m -> if (written) KaiMicForm.written(m) else m }
    /** A short answer to Kai's question, said the same way as the line. */
    private fun answer(typed: String): String = if (mic) KaiMicForm.of(typed)?.let { m -> if (written) KaiMicForm.written(m) ?: m else m } ?: typed else typed

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
        if (has != null && !words.contains(has, true) && !(mic && listOfNotNull(KaiMicForm.of(has), KaiMicForm.repeated(has)).any { words.contains(it) })) return fail(said, s, "'${r.title}' / '${r.task}' lacks '$has'")
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
        // Said the owner's way (19 Oct 2026): "ovvoru Saniyum", "ovvoru Velliyum", "dhinamum … ezhuppi vidu" repeat.
        reminder("ovvoru Saniyum kaalai 8 manikku stock ennanum nu sollu", null, "stock", repeat = true)
        reminder("ovvoru Velliyum saayangaalam 6 manikku stock check pannanum nu sollu", null, "stock", repeat = true)
        reminder("dhinamum raathiri 9 manikku kallaa kanakku paakka ezhuppi vidu", null, repeat = true)
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
            if (l == "TAP" || l == "TAP+") {
                val b = lastTurn?.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmPlan || it.action is KaiAction.ConfirmStock || it.action is KaiAction.ConfirmReminder ||
                    it.action == KaiAction.ConfirmGoods } ?: return fail(what, s, "nothing to tap")
                // "TAP+": what Kai says after the tap (the next goods draft) is what the next tap answers.
                s.act(b.action)?.let { if (l == "TAP+") lastTurn = it }
            } else if (l == "CANCEL+") {
                val b = lastTurn?.card?.buttons?.firstOrNull { it.action is KaiAction.CancelStock || it.action is KaiAction.CancelPlan || it.action == KaiAction.CancelGoods }
                    ?: return fail(what, s, "nothing to cancel")
                s.act(b.action)?.let { lastTurn = it }
            } else if (l == "EDIT+") {
                val b = lastTurn?.card?.buttons?.firstOrNull { it.action == KaiAction.EditGoods } ?: return fail(what, s, "no list Edit")
                s.act(b.action)?.let { lastTurn = it }
            } else lastTurn = s.say(answer(l))
        }
        check(s)?.let { fail(what, s, it) }
    }
    private var lastTurn: KaiTurn? = null

    private fun conversationLines() {
        // The owner's conversation (10 Oct 2026): what was said before, push-back, and what Kai can't do — nothing invented,
        // nothing written by itself.
        fun said(vararg words: String): (Shop) -> String? = { _ ->
            val want = if (mic) words.filter(KaiMicForm::keepsInTamil).map(KaiMicForm::inTamil) else words.toList()
            // A phrase Kai repeats back ("Ramesh 1000 kuduthan") comes back as the owner said it: "Ramesh 1000 குடுத்தான்" from the mic.
            lastTurn?.reply?.text.orEmpty().let { t -> if (want.all { w -> t.contains(w) || mic && t.contains(KaiMicForm.repeated(w)) }) null else "said: $t" } }
        fun nothingWritten(vararg words: String): (Shop) -> String? = { s -> said(*words)(s) ?: if (s.nothingWritten()) null else "wrote something" }
        fun button(kind: String): (Shop) -> String? = { _ -> if (lastTurn?.card?.buttons?.any { (it.action as? KaiAction.OpenRecord)?.kind == kind } == true) null else "no $kind button" }
        // Whose money, when only the verb is said (owner, 12 Oct 2026): Kai asks, the next short answer picks the side; with someone just
        // talked about, it is that person's.
        talk("which side: the owner pays", listOf("tharanum", "naan"), check = said("₹14,000", "ABC Traders"))
        talk("which side: money to come", listOf("evlo kudukkanum", "customers"), check = said("₹13,500", "Selvam"))
        talk("which side: asked first", listOf("tharanum"), check = { s -> if (s.log.contains("vara vendiyadha") || s.log.contains("வர வேண்டியதா")) null else "did not ask which side" })
        talk("which side: the person just talked about", listOf("Kumar evlo tharanum", "evlo tharanum"), check = said("₹4,000"))
        // Not understood (owner, 12 Oct 2026): Kai asks what it is about, the one-word answer gets the question for that with how to say it,
        // and the owner's next line goes straight in — Confirm first.
        talk("not understood → payment → the entry", listOf("jamaai", "payment", "Kumar 500 kuduthan", "TAP")) { s ->
            if (s.tools.saved.singleOrNull()?.let { it.partyName == "Kumar" && it.amount.compareTo(BigDecimal("500")) == 0 } == true) null
            else "saved ${s.tools.saved.map { "${it.partyName} ${it.amount}" }}" }
        talk("not understood → payment: the way to say it", listOf("jamaai", "payment"), check = nothingWritten("Kumar 500", "ABC Traders"))
        talk("not understood → stock: the way to say it", listOf("blah blah", "stock"), check = nothingWritten("Colgate 2"))
        talk("not understood → balance: whose", listOf("blah blah", "balance"), check = nothingWritten("Kumar"))
        talk("a person, then balance: that person's", listOf("Kumar", "balance"), check = said("₹4,000"))
        talk("product and count: which way, then the draft", listOf("Colgate 2 box", "vandhuchu", "TAP")) { s ->
            if (s.inv.item("Colgate").stock.compareTo(BigDecimal("336")) == 0) null else "Colgate ${s.inv.item("Colgate").stock}" }
        talk("product and count: asked which way", listOf("Colgate 2 box"), check = nothingWritten("Colgate"))
        talk("a name and an amount: asked which way", listOf("Kumar 500 adhu", "vandhuchu", "TAP")) { s ->
            if (s.tools.saved.singleOrNull()?.let { it.partyName == "Kumar" && it.amount.compareTo(BigDecimal("500")) == 0 } == true) null
            else "saved ${s.tools.saved.map { "${it.partyName} ${it.amount}" }}" }
        talk("an expense is never a person", listOf("current bill 1200 kattinen"), check = nothingWritten("₹1200"))
        talk("an unknown word is asked, not guessed", listOf("spa 300 kattinen"), check = nothingWritten("₹300"))
        // "nalla irukku" is not a selling word: never the best sellers or the unsold list (owner, 13 Oct 2026).
        talk("stock 'nalla irukku' is not movement", listOf("yentha stock nalla irukku")) { s ->
            val t = lastTurn?.reply?.text.orEmpty() + lastTurn?.card?.lines.orEmpty().joinToString()
            if (s.nothingWritten() && !t.contains("₹17,600") && !t.contains("vikkala") && !t.contains("விக்கல")) null else "read as movement: $t" }
        // "idhu Kumar pathiyaa, illa vera vishayam pathiyaa?" (owner, 13 Oct 2026): "aamaa" goes on with Kumar, never the same question
        // again; "vera vishayam" / "illa" drops Kumar (the draft on screen stays) and asks what it is about.
        talk("Kumar pathiyaa? aamaa: Kumar, and what to say next", listOf("Kumar", "blah blah", "aamaa"), check = nothingWritten("₹4,000", "Kumar 500"))
        talk("Colgate stock pathiyaa? aamaa: its stock", listOf("Colgate stock evlo", "blah blah", "aamaa"), check = nothingWritten("240", "Colgate 2"))
        talk("vera vishayam → stock: the stock question", listOf("Kumar", "blah blah", "vera vishayam", "stock"), check = nothingWritten("Colgate 2"))
        talk("vera vishayam → the next entry goes in", listOf("Kumar", "blah blah", "vera vishayam", "Ramesh 300 kuduthan", "TAP")) { s ->
            if (s.tools.saved.singleOrNull()?.let { it.partyName == "Ramesh" && it.amount.compareTo(BigDecimal("300")) == 0 } == true) null
            else "saved ${s.tools.saved.map { "${it.partyName} ${it.amount}" }}" }
        talk("vera vishayam → avan: asked who, not Kumar", listOf("Kumar", "blah blah", "vera vishayam", "avan evlo tharanum")) { s ->
            val t = lastTurn?.reply?.text.orEmpty()
            if (!t.contains("Kumar") && !t.contains("குமார்") && s.nothingWritten()) null else "said: $t" }
        talk("illa to Kumar pathiyaa: the draft is kept", listOf("Kumar ku 500 credit", "blah blah", "illa")) { s ->
            val t = lastTurn?.reply?.text.orEmpty()
            if (t.contains("₹500") && !t.contains("cancel", true) && !t.contains("ரத்து") && s.nothingWritten()) null else "said: $t" }
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
        talk("Kai got it wrong", listOf("Selvam evlo tharanum", "nee sonnadhu thappu"), check = nothingWritten(if (mic) "மன்னிச்சுக்கோங்க" else "Mannichikonga"))
        talk("puriyala: again, in Tamil", listOf("Selvam evlo tharanum", "puriyala"), check = said("தமிழ்ல", "₹6,000"))
        talk("GST bill: the billing screen, not the scanner", listOf("GST bill podu Kumar ku 5000"), check = { s ->
            nothingWritten("Billing")(s) ?: button("SALE_BILL")(s) ?: if (lastTurn?.card?.buttons?.any { it.action == KaiAction.OpenScanner } == true) "opened the scanner" else null })
        talk("WhatsApp: can't send, can remind", listOf("Kumar ku whatsapp la payment reminder anuppu"), check = said("WhatsApp", "mudiyaadhu", "remind"))
        // Kai's Tamil reply keeps these words as they are (cricket, news, joke …) — checked as they are, typed and spoken.
        for ((ask, word) in listOf("naalaiku mazhai varuma" to "weather", "ipl score enna" to "cricket", "tamil nadu cm yaaru" to "news", "oru joke sollu" to "joke",
                "enakku loan venum" to "loan", "employee salary kanakku podu" to "salary", "amazon la 10 kg rice order pannu" to "Amazon", "நாளைக்கு மழை வருமா" to "வானிலை",
                // Advice and a shop holiday (owner, 14 Oct 2026: both were answered with the balances): said plainly, the way back offered.
                "enna business pannalam" to "business", "sales jaasthi aaga idea sollu" to "business", "naalaiku kadai leave" to "remind"))
            talk("not Kai's work: $ask", listOf(ask), check = { s ->
                val w = if (mic && word == "weather") "வானிலை" else word
                lastTurn?.reply?.text.orEmpty().let { t -> if (t.contains(w)) null else "said: $t" } ?: if (s.nothingWritten()) null else "wrote something" })
        // Not understood → Kai names its three flows the owner's way (credit / debit = kudukkal vaangal, stock = the goods in the shop,
        // reminder); the answer, typed or said, goes into that flow — and "ama" / "illa" to the question are understood (owner, 14 Oct 2026).
        for ((answer, word) in listOf("stock ah pathi" to "Colgate 2 box", "kadaila irukura porul pathi" to "Colgate 2 box", "porul" to "Colgate 2 box",
                "credit debit pathi" to "Kumar 500", "kudukal vangal" to "Kumar 500", "debit" to "Kumar 500", "ama" to "Credit / debit", "yes" to "Credit / debit",
                "illa" to "Colgate stock", "no" to "Colgate stock", "கடையில இருக்கிற பொருள்" to "Colgate 2 box", "கொடுக்கல் வாங்கல் பத்தி" to "Kumar 500",
                "ஆமாம்" to "Credit / debit", "எஸ்" to "Credit / debit", "நோ" to "Colgate stock"))
            talk("not understood, then «$answer»", listOf("blah blah", answer), check = { s ->
                lastTurn?.reply?.text.orEmpty().let { t -> if (t.contains(word)) null else "said: $t" } ?: if (s.nothingWritten()) null else "wrote something" })
        // …and the line said next is that flow's: the stock draft, saved only on Confirm.
        talk("not understood, stock, then the line", listOf("blah blah", "stock pathi", "Colgate 1 box vandhuchu", "TAP"), check = { s ->
            if (s.inv.moves.isNotEmpty()) null else "no stock moved: ${lastTurn?.reply?.text}" })
        talk("not understood, kudukkal vaangal, then the line", listOf("blah blah", "kudukal vangal", "Kumar 2000 kuduthan", "TAP"), check = { s ->
            if (s.tools.saved.size == 1) null else "saved ${s.tools.saved.size}: ${lastTurn?.reply?.text}" })
        // "kadai" said in passing is not a question about the books: never the balances in its place (a word not in any list).
        talk("kadai said in passing", listOf("kadai la light poyiduchu"), check = { s ->
            lastTurn?.reply?.text.orEmpty().let { t -> if (t.contains("vendiyadhu") || t.contains("வேண்டியது")) "said: $t" else null } ?: if (s.nothingWritten()) null else "wrote something" })
        talk("a date", listOf("1 varusham kalichi enna date"), check = said("October 8, 2027"))
        // Paid more than owed (owner's conversation, 10 Oct 2026): the extra is said before Confirm and after, and
        // "ippa evlo tharanum" is about the person just talked about.
        val akash: Ledger.() -> Unit = { add("c8", "Akash", true, "5000", d(9, 25), d(10, 5)) }
        talk("paid more than owed", listOf("Akash evlo tharnum Kai", "Avan 6000 kuduthutan"), akash, said("₹5,000", "₹6,000", "₹1,000", "advance"))
        talk("paid more than owed, saved", listOf("Akash evlo tharnum Kai", "Avan 6000 kuduthutan", "TAP", "Ippa evlo tharanum"), akash) { s ->
            said("Akash", "₹1,000 advance")(s) ?: if (s.tools.saved.singleOrNull()?.amount?.compareTo(BigDecimal("6000")) == 0) null else "saved ${s.tools.saved.map { it.amount }}" }
        talk("paid part, then 'ippa evlo'", listOf("Akash evlo tharanum", "avan 2000 kuduthutan", "TAP", "ippa evlo tharanum"), akash, said("Akash", "₹3,000"))
        talk("paid more than owed, spoken", listOf("ஆகாஷ் எவ்வளவு தரணும்", "அவன் 6000 குடுத்துட்டான்", "TAP", "இப்ப எவ்வளவு தரணும்"), akash, said("Akash", "₹1,000 advance"))
        // The way money came is kept as said — typed or spoken (a spoken "செக்" was saved as cash).
        for ((line, mode) in listOf("Kumar 2000 cheque la kuduthan" to PaymentMode.CHEQUE,
                "Kumar 2000 card la kuduthan" to PaymentMode.CARD,
                "Kumar 2000 bank transfer pannan" to PaymentMode.BANK_TRANSFER,
                "குமார் 2000 செக் குடுத்தான்" to PaymentMode.CHEQUE))
            talk("paid by ${mode.name.lowercase()}: $line", listOf(line, "TAP")) { s ->
                val p = s.tools.saved.singleOrNull()
                if (p == null) "saved ${s.tools.saved.size}" else if (p.mode == mode) null else "saved as ${p.mode}" }
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
        // "avanga peru enna" after the day's list: those people's names (owner's phone, 10 Oct 2026: "yaar pathi?").
        talk("the list's names", listOf("enaku yar innaiku tharanum", "avanga peru enna"), noDue) { lastTurn?.reply?.text.orEmpty().let { t ->
            if (t.contains("Kumar") && t.contains("Ramesh") && !t.contains("Madhan")) null else "said: $t" } }
        talk("the list's names, Tamil", listOf("இன்னைக்கு யார் எனக்கு தரணும்", "அவங்க பேர் என்ன"), noDue) { lastTurn?.reply?.text.orEmpty().let { t ->
            if (t.contains("Kumar") && t.contains("Ramesh")) null else "said: $t" } }
        // A bill with no due date is not money due today: said apart from "Innaikku … tharanum".
        talk("no due date is said apart", listOf("enaku yar innaiku tharanum"), noDue) { lastTurn?.reply?.text.orEmpty().let { t ->
            val today = t.substringBefore("Due date").substringBefore("due date போடாத")
            if (!today.contains("Madhan") && Regex("""(?i)due date (podaadha|போடாத)[^.]*Madhan""").containsMatchIn(t)) null else "said: $t" } }
        // A new product bought ("vengayam 100 kg vangunan stock la seathuko"): its details are asked — never "inventory-la illa".
        talk("new product bought, added to stock", listOf("vengayam 100 kg vangunan stock la seathuko")) { s ->
            val t = lastTurn?.reply?.text.orEmpty()
            if (Regex("""(?i)rate|evlo|எவ்வளவு""").containsMatchIn(t) && !t.contains("inventory-la illa") && s.nothingWritten()) null else "said: $t" }
        // Stock asked by a word of the name (owner, 10 Oct 2026: "Cigarette evlo irukunu" → "product illa" with two cigarettes in
        // stock): every product with that word, each with its stock, and the total.
        for ((ask, want) in listOf("Cigarette evlo irukunu" to listOf("Lights Cigarette — 50", "Gold Flak Cigarette — 100", "150"),
                "pipe evlo iruku" to listOf("5 inch Pipe — 10", "10 inch Pipe — 25", "35"), "pipe stock evlo" to listOf("35"),
                "gold flake evlo irukku" to listOf("Gold Flak Cigarette", "100"), "Lights evlo irukku" to listOf("Lights Cigarette", "50"),
                "5 inch pipe evlo irukku" to listOf("5 inch Pipe", "10"), "சிகரெட் எவ்வளவு இருக்கு" to listOf("150"))) {
            count++
            val s = Shop()
            listOf(Triple("p7", "Lights Cigarette", "50"), Triple("p8", "Gold Flak Cigarette", "100"), Triple("p9", "5 inch Pipe", "10"), Triple("p10", "10 inch Pipe", "25"))
                .forEach { (id, name, n) -> s.inv.items += Inventory.Item(id, name, "PCS", BigDecimal(n), emptyMap(), BigDecimal("8"), BigDecimal("10"), "General", null) }
            lastTurn = s.say(answer(ask))
            said(*want.toTypedArray())(s)?.let { fail("stock by word: $ask", s, it) } ?: if (!s.nothingWritten()) fail("stock by word: $ask", s, "wrote something") else Unit
        }
        // What was said before (owner, 10 Oct 2026 — context memory): the product, the saved entry, the person, the amount.
        talk("athu: the product, after a question about someone", listOf("Colgate stock evlo", "Kumar evlo tharanum", "athu evlo irukku"), check = said("Colgate", "240"))
        talk("adha cancel pannu, three turns after the save", listOf("Kumar 2000 kuduthan", "TAP", "Rice evlo irukku", "saptiya", "adha cancel pannu")) { s ->
            said("TXN-1", "Cancel")(s) ?: if (s.tools.saved.size == 1) null else "saved ${s.tools.saved.size}" }
        talk("adha cancel pannu after a reminder: the reminder", listOf("Kumar 2000 kuduthan", "TAP", "naalaiku 10 manikku Ramesh ku call panna remind pannu", "TAP", "adha cancel pannu")) {
            lastTurn?.reply?.text.orEmpty().let { t -> if (!t.contains("TXN-1")) null else "said: $t" } }
        talk("innoru 500: the same person", listOf("Kumar 2000 kuduthan", "TAP", "innoru 500 kuduthan", "TAP")) { s ->
            if (s.tools.saved.map { "${it.partyName} ${it.amount.toPlainString()}" } == listOf("Kumar 2000.00", "Kumar 500.00")) null else "saved ${s.tools.saved.map { "${it.partyName} ${it.amount}" }}" }
        talk("adhe amount: the amount just said", listOf("Kumar 2000 kuduthan", "TAP", "Ramesh um adhe amount kuduthan", "TAP")) { s ->
            if (s.tools.saved.lastOrNull()?.let { it.partyName == "Ramesh" && it.amount.compareTo(BigDecimal("2000")) == 0 } == true) null else "saved ${s.tools.saved.map { "${it.partyName} ${it.amount}" }}" }
        talk("paadhi: half the balance", listOf("Kumar evlo tharanum", "avan paadhi kuduthan", "TAP")) { s ->
            said("₹2,000")(s) ?: if (s.tools.saved.singleOrNull()?.let { it.partyName == "Kumar" && it.amount.compareTo(BigDecimal("2000")) == 0 } == true) null else "saved ${s.tools.saved.map { "${it.partyName} ${it.amount}" }}" }
        talk("munnadi enna ketten: the last questions", listOf("Kumar evlo tharanum", "Colgate stock evlo", "naan munnadi enna ketten")) {
            lastTurn?.reply?.text.orEmpty().let { t -> if (t.contains("1.") && t.contains("2.") && !t.contains("pathiyaa")) null else "said: $t" } }
        talk("amount corrected before Confirm", listOf("Kumar 2000 kuduthan", "illa 2500", "aama")) { s ->
            val p = s.tools.saved.singleOrNull()
            when { p == null -> "saved ${s.tools.saved.size}"; p.amount.compareTo(BigDecimal("2500")) != 0 -> "saved ${p.amount}"; else -> null } }
        talk("cancelled draft saves nothing", listOf("Kumar 2000 kuduthan", "venam")) { s -> if (s.nothingWritten()) null else "saved after venam" }
        talk("cancel word", listOf("ABC Traders ku 5000 kuduthen", "cancel")) { s -> if (s.nothingWritten()) null else "saved after cancel" }
        talk("asked if saved before Confirm", listOf("Kumar 2000 kuduthan", "save pannitiya")) { s ->
            if (s.nothingWritten() && !s.log.contains("Save aagiduchu")) null else "claimed / saved without Confirm" }
        talk("balance read back after a payment", listOf("Kumar 2000 kuduthan", "aama", "Kumar evlo tharanum")) { s ->
            if (s.log.contains("₹2,000 tharanum") || mic && s.log.contains("₹2,000 தரணும்")) null else "balance not read back as ₹2,000" }
        talk("follow-up 'avan' after a person", listOf("Kumar evlo tharanum", "avan eppo tharuvan")) { s ->
            if (s.log.lines().last { it.isNotBlank() }.contains("Kumar")) null else "follow-up lost Kumar" }
        talk("stock draft cancelled", listOf("Colgate 2 box vandhuchu", "venam")) { s -> if (s.inv.moves.isEmpty()) null else "stock changed after venam" }
        talk("stock quantity corrected", listOf("Rice 3 bag vandhuchu", "illa 4 bag dhaan", "TAP")) { s ->
            if (s.inv.item("Rice").stock.compareTo(BigDecimal("350")) == 0) null else "Rice ${s.inv.item("Rice").stock}" }
        // "Kadaiku saman vandhuruku": goods came, none named — Kai asks which; the next lines are stock-in drafts, Confirm first,
        // one at a time, until "avlo dhaan" (owner, 10 Oct 2026).
        fun stock(vararg want: Pair<String, String>): (Shop) -> String? = { s ->
            want.firstOrNull { (p, q) -> s.inv.item(p).stock.compareTo(BigDecimal(q)) != 0 }?.let { (p, _) -> "$p ${s.inv.item(p).stock}" } }
        val askedWhich = { s: Shop -> if ((s.log.contains("Enna saaman vandhuruku") || s.log.contains("என்ன சாமான் வந்திருக்கு")) && s.nothingWritten()) null
            else "goods not asked" }
        talk("goods came: Kai asks which", listOf("Kadaiku saman vandhuruku"), check = askedWhich)
        // Only the question: no Photo / Cancel buttons and no camera by itself — Confirm / Edit / Cancel come on the draft.
        talk("goods question: no buttons, no camera", listOf("Kadaiku saman vandhuruku")) { _ ->
            if (lastTurn?.card == null && lastTurn?.direct == null) null else "buttons ${lastTurn?.card?.buttons?.map { it.label }} direct ${lastTurn?.direct}" }
        talk("saraku came: asked, not a person", listOf("Kadaiku Saraku vandhuruku"), check = askedWhich)
        talk("stock add pannalama: asked", listOf("Kadaiku stock add pannalama"), check = askedWhich)
        talk("maal came: asked", listOf("maal vandhuruku"), check = askedWhich)
        // As the phone's mic writes it (owner, 18 Oct 2026: "Kadaiku saraku vandhuruku" by mic was not read): the written forms
        // ("வந்திருக்கிறது", "வந்திருக்குது", "வந்துள்ளது") and "vandhu irukku" as two words.
        for (said in listOf("கடைக்கு சரக்கு வந்திருக்கிறது", "கடைக்கு சரக்கு வந்திருக்குது", "கடைக்கு சரக்கு வந்து இருக்கு", "கடைக்கு சரக்கு வந்துள்ளது",
                "கடைக்கு சாமான் வந்துருக்குது", "Kadaiku saraku vandhu irukku"))
            talk("goods came, as the mic writes it: $said", listOf(said), check = askedWhich)
        // Not "came": "saraku varala" (didn't come) is never the goods question.
        talk("saraku didn't come: not asked", listOf("கடைக்கு சரக்கு வரல")) { s -> if (s.log.contains("Enna saaman vandhuruku") || s.log.contains("என்ன சாமான் வந்திருக்கு")) "goods asked" else null }
        talk("goods: the item after the question", listOf("Kadaiku Saraku vandhuruku", "Rice 3 moota", "TAP+", "avlo dhaan", "TAP+"), check = stock("Rice" to "325"))
        talk("goods: stock add pannalama then Oil", listOf("Kadaiku stock add pannalama", "Oil 1 box", "TAP+", "avlo dhaan", "TAP+"), check = stock("Oil" to "252"))
        talk("goods: two items, nothing written before Confirm", listOf("Kadaiku saman vandhuruku", "Colgate 2 box, Rice 3 moota")) { s ->
            if (s.nothingWritten()) null else "wrote before Confirm" }
        talk("goods: two items, one draft after the other", listOf("Kadaiku saman vandhuruku", "Colgate 2 box, Rice 3 moota", "TAP+", "TAP+", "avlo dhaan", "TAP+"),
            check = stock("Colgate" to "336", "Rice" to "325"))
        talk("goods: first cancelled, next still drafted", listOf("saman vandhuruku", "Colgate 2 box, Rice 3 moota", "CANCEL+", "TAP+", "avlo dhaan", "TAP+"),
            check = stock("Colgate" to "240", "Rice" to "325"))
        talk("goods: three items", listOf("saman vandhuruku", "Colgate 2 box, Rice 3 moota, Lux soap 20", "TAP+", "TAP+", "TAP+", "avlo dhaan", "TAP+"),
            check = stock("Colgate" to "336", "Rice" to "325", "Lux Soap" to "80"))
        talk("goods: said together without the question", listOf("Colgate 2 box, Rice 3 moota vandhuchu", "TAP+", "TAP+", "avlo dhaan", "TAP+"),
            check = stock("Colgate" to "336", "Rice" to "325"))
        talk("goods: said together, no comma, without the question", listOf("Colgate 2 box Rice 3 moota vandhuchu", "TAP+", "TAP+", "avlo dhaan", "TAP+"),
            check = stock("Colgate" to "336", "Rice" to "325"))
        talk("goods: avlo dhaan with nothing kept ends it", listOf("Kadaiku saman vandhuruku", "avlo dhaan")) { s ->
            if (s.nothingWritten() && s.log.lines().last { it.isNotBlank() }.let { it.contains("Vera saaman vandha") || it.contains("வேற சாமான் வந்தா") }) null else "not ended" }
        // Owner, 11 Oct 2026: one by one — each draft's Confirm only keeps it in the list ("aduthu sollunga"); "avlo dhaan" shows the
        // whole list with Confirm / Edit / Cancel; only that Confirm adds the stock.
        talk("goods: one by one, nothing saved until the list is confirmed", listOf("Kadaiku saman vandhuruku", "Colgate 2 box", "TAP+", "Rice 3 moota", "TAP+", "avlo dhaan")) { s ->
            if (!s.nothingWritten()) "saved before the list's Confirm"
            else if (!(s.log.contains("Aduthu sollunga") || s.log.contains("அடுத்து சொல்லுங்க"))) "no 'aduthu sollunga'"
            else if (!(s.log.contains("1. Colgate") && s.log.contains("2. Rice"))) "no list"
            else if (lastTurn?.card?.buttons?.map { it.action } != listOf(KaiAction.ConfirmGoods, KaiAction.EditGoods, KaiAction.CancelGoods)) "list buttons ${lastTurn?.card?.buttons?.map { it.label }}"
            else null }
        talk("goods: the list's Confirm adds them all", listOf("Kadaiku saman vandhuruku", "Colgate 2 box", "TAP+", "Rice 3 moota", "TAP+", "avlo dhaan", "TAP+"),
            check = stock("Colgate" to "336", "Rice" to "325"))
        talk("goods: the list cancelled — nothing", listOf("Kadaiku saman vandhuruku", "Colgate 2 box", "TAP+", "Rice 3 moota", "TAP+", "avlo dhaan", "CANCEL+")) { s ->
            if (s.nothingWritten()) null else "saved after the list's Cancel" }
        talk("goods: list Edit changes one item", listOf("Kadaiku saman vandhuruku", "Colgate 2 box", "TAP+", "Rice 3 moota", "TAP+", "avlo dhaan", "EDIT+",
            "Rice 4 moota", "TAP+", "TAP+"), check = stock("Colgate" to "336", "Rice" to "350"))
        talk("goods: list Edit takes one out", listOf("Kadaiku saman vandhuruku", "Colgate 2 box", "TAP+", "Rice 3 moota", "TAP+", "avlo dhaan", "EDIT+",
            "Rice venam", "TAP+"), check = stock("Colgate" to "336", "Rice" to "250"))
        talk("goods: a new product joins the list", listOf("Kadaiku saman vandhuruku", "Vengayam 50 kg", "40", "50", "TAP+", "Colgate 2 box", "TAP+", "avlo dhaan")) { s ->
            if (!s.nothingWritten()) "saved before the list's Confirm" else if (s.log.contains("2. Colgate")) null else "new product not in the list" }
        talk("goods: a new product saved with the list", listOf("Kadaiku saman vandhuruku", "Vengayam 50 kg", "40", "50", "TAP+", "Colgate 2 box", "TAP+", "avlo dhaan", "TAP+")) { s ->
            stock("Colgate" to "336")(s) ?: if (s.inv.items.any { it.stock.compareTo(BigDecimal("50")) == 0 && it.id !in setOf("p1", "p2", "p3", "p4", "p5", "p6") }) null else "Vengayam not created" }
        // "Ashirvad rava arakilo 50 packetu" (owner's own example, 11 Oct 2026): one product — its packet size and how many — never two items.
        talk("goods: a packet size line is one product", listOf("Kadaiku saman vandhuruku", "Ashirvad rava arakilo 50 packetu", "45", "55", "TAP+", "avlo dhaan")) { s ->
            if (!s.nothingWritten()) "saved before the list's Confirm"
            else if (s.log.contains("Idhu aanadhum adutha") || s.log.contains("இது ஆனதும் அடுத்து")) "split into items"
            else if (s.log.contains("0.5 kg") && s.log.contains("1. ")) null else "packet size not read" }
        talk("goods: a packet size line saved with the list", listOf("Kadaiku saman vandhuruku", "Ashirvad rava 500 gram 50 packet", "45", "55", "TAP+", "avlo dhaan", "TAP+")) { s ->
            if (s.inv.items.any { it.id !in setOf("p1", "p2", "p3", "p4", "p5", "p6") && it.stock.signum() > 0 }) null else "not created" }
        // A pack size said in grams for a product kept in kg is turned into kg — never "1 packet = 500 kg" (owner, 11 Oct 2026).
        talk("pack size in grams for a kg product", listOf("Rice 2 packet, packetku 500 gram", "TAP")) { s -> stock("Rice" to "251")(s) }
        talk("pack size in pieces for a kg product is asked", listOf("Rice 2 box, boxku 10 pieces")) { s ->
            if (s.nothingWritten() && lastTurn?.card?.buttons?.none { it.action is KaiAction.ConfirmStock } != false) null else "a box of Rice taken as 10 kg" }
        // "Yaaru correct-aa pay pannuvaanga" got the same late list as "yaaru late-aa" (owner, 11 Oct 2026): on time first, late after.
        fun first(head: List<String>, before: String): (Shop) -> String? = { _ ->
            val t = lastTurn?.reply?.text.orEmpty()
            val h = head.map { t.indexOf(it) }.filter { it >= 0 }.minOrNull()
            val b = listOf(before, KaiMicForm.repeated(before)).map { t.indexOf(it) }.filter { it >= 0 }.minOrNull()
            if (h != null && (b == null || h < b)) null else "said: $t" }
        talk("who pays on time: on-time list first", listOf("yaru correct ah payment pannuvanga"),
            check = first(listOf("Correct date-la tharuvaanga", "சரியான தேதியில தருவாங்க"), "Late"))
        talk("who pays on time, spoken", listOf("யாரு கரெக்டா பேமெண்ட் பண்ணுவாங்க"),
            check = first(listOf("Correct date-la tharuvaanga", "சரியான தேதியில தருவாங்க"), "Late"))
        talk("who pays late: late list first", listOf("yaru late ah payment pannuvanga"),
            check = first(listOf("Late-aa tharuvaanga", "Late-ஆ தருவாங்க"), "Correct date"))
        // Owner's phone, 11 Oct 2026: "கமல்" saved by voice (a Tamil-script name); the mic then wrote "கமலி" — Kai asked "யாருக்கு?",
        // and took the answer "எனக்கு" as a person called "enakku".
        val kamal: Ledger.() -> Unit = { add("s3", "கமல்", false, "5000", d(10, 9), d(12, 31)) }
        talk("a Tamil name with the mic's vowel is the same person", listOf("கமலி இப்ப 2000 கொடுத்துட்டான்"), kamal) { s ->
            val t = lastTurn?.reply?.text.orEmpty() + " " + lastTurn?.card?.lines.orEmpty().joinToString(" ")
            if (t.contains("யாருக்கு") || t.contains("Yaar")) "asked who: $t" else if (!t.contains("கமல்")) "Kamal not found: $t"
            else if (s.nothingWritten()) null else "saved before Confirm" }
        talk("enakku is never a person", listOf("2000 kuduthan", "enakku")) { s ->
            val t = lastTurn?.reply?.text.orEmpty()
            if (t.contains("enakku", true) && (t.contains("records") || t.contains("பதிவுல"))) "took enakku as a name: $t"
            else if (!(t.contains("yaar kuduthaanga", true) || t.contains("யார் கொடுத்தாங்க"))) "did not ask who gave: $t"
            else if (s.nothingWritten()) null else "saved" }
        talk("enakku, then the name", listOf("2000 kuduthan", "enakku", "Kumar", "TAP")) { s ->
            if (s.tools.saved.singleOrNull()?.let { it.partyName == "Kumar" && it.amount.compareTo(BigDecimal("2000")) == 0 } == true) null
            else "saved ${s.tools.saved.map { "${it.partyName} ${it.amount}" }}" }
        // Owner's flow, 11 Oct 2026: a shop's whole name, a month said aloud, a typo in the name, "vandhuchi", "stock vandhuruku",
        // and a supplier's due date said as the owner's money.
        val vij = listOf("Vijayalakshmi store ku nan 2000 tharanum", "May 5 tharanum", "TAP")
        talk("a shop's whole name before -ku", vij) { s ->
            val p = s.tools.saved.singleOrNull()
            if (p != null && (p.partyName.contains("Vijayalakshmi") || p.partyName.contains("விஜயலட்சுமி")) && p.dueDate == LocalDate.of(2027, 5, 5)) null
            else "saved ${s.tools.saved.map { "${it.partyName} due ${it.dueDate}" }}" }
        talk("a shop's name and May, spoken", listOf("விஜயலட்சுமி ஸ்டோருக்கு நான் 2000 தரணும்", "மே 5 தரணும்", "TAP")) { s ->
            val p = s.tools.saved.singleOrNull()
            if (p != null && p.partyName.contains("விஜயலட்சுமி") && p.dueDate == LocalDate.of(2027, 5, 5)) null
            else "saved ${s.tools.saved.map { "${it.partyName} due ${it.dueDate}" }}" }
        talk("a long name typed one letter off is the same shop", vij + listOf("Vijaylakshmi store ippa 2500 kututhutanga", "TAP")) { s ->
            if (s.tools.saved.size == 2 && s.tools.saved.map { it.partyName }.distinct().size == 1) null else "saved ${s.tools.saved.map { it.partyName }}" }
        talk("goods came, said vandhuchi", listOf("Kadaiku saman vandhuchi"), check = askedWhich)
        talk("stock came is the goods question", listOf("Kadaiku stock vandhuruku"), check = askedWhich)
        talk("a supplier's due date is the owner's to pay", listOf("Kamal yeanuku evlo pay pannum yeppa pannanum"),
            { add("s3", "கமல்", false, "5000", d(10, 9), LocalDate.of(2027, 1, 5)) }) { _ ->
            val t = lastTurn?.reply?.text.orEmpty()
            if ((t.contains("Neenga") || t.contains("நீங்க")) && t.contains("5,000")) null else "said: $t" }
        // Owner, 11 Oct 2026: "yaar kitta irundhu pay varanum" got the suppliers (the owner paying); "mudhal aal" re-showed the list.
        talk("money to come from: the customers, not the suppliers", listOf("innaiku yar kitta irunthu pay varanum")) { _ ->
            val t = lastTurn?.reply?.text.orEmpty()
            if (t.contains("Kumar") && t.contains("Ramesh") && !t.contains("ABC")) null else "said: $t" }
        talk("the owner pays today: the suppliers", listOf("Innaiku na yaruku payment tharanum")) { _ ->
            val t = lastTurn?.reply?.text.orEmpty()
            if (t.contains("ABC") && !t.contains("Kumar")) null else "said: $t" }
        talk("the first one of the list just shown", listOf("innaiku yaar tharanum", "mudhal aal eppo kudupaan")) { _ ->
            val t = lastTurn?.reply?.text.orEmpty()
            if (t.contains("Kumar") && !t.contains("Ramesh")) null else "said: $t" }
        talk("the last one of the list just shown", listOf("ellaa pending customers list pannu", "kadaisi aal evlo tharanum")) { _ ->
            val t = lastTurn?.reply?.text.orEmpty()
            if (t.contains("Lakshmi") && t.contains("1,000") && !t.contains("Selvam")) null else "said: $t" }
        talk("goods: venam — nothing", listOf("Kadaiku saman vandhuruku", "venam")) { s -> if (s.nothingWritten()) null else "wrote after venam" }
        talk("goods: a balance question in between", listOf("Kadaiku saman vandhuruku", "Kumar evlo tharanum")) { s ->
            if (s.log.contains("₹4,000") && s.nothingWritten()) null else "balance not answered" }
        // The old stock flow stays as it is (owner, 11 Oct 2026): one product said → its own draft, never the goods question;
        // "new stock add pannu" keeps its one-product question; "venam" to a stock draft drops that draft.
        val notGoods = { s: Shop -> if (s.log.contains("Enna saaman vandhuruku") || s.log.contains("என்ன சாமான் வந்திருக்கு")) "goods asked" else null }
        talk("old flow: one product added", listOf("Colgate 2 box stock add pannu", "TAP")) { s -> notGoods(s) ?: stock("Colgate" to "336")(s) }
        talk("old flow: a new product said alone", listOf("Ashirvad rava 2 kg vangi irukken")) { s -> notGoods(s) ?: if (s.nothingWritten()) null else "wrote before Confirm" }
        talk("old flow: new stock add pannu, then venam drops the draft", listOf("new stock add pannu", "Colgate 2 box", "venam")) { s ->
            notGoods(s) ?: if (s.log.contains("Vera saaman vandha") || s.log.contains("வேற சாமான் வந்தா")) "goods ended instead of the draft dropped"
            else if (s.nothingWritten() && lastTurn?.card?.buttons?.none { it.action is KaiAction.ConfirmStock } != false) null else "draft still open" }
        talk("goods: venam drops this draft, the next one comes", listOf("Kadaiku saman vandhuruku", "Colgate 2 box, Rice 3 moota", "venam", "TAP+", "avlo dhaan", "TAP+"),
            check = stock("Colgate" to "240", "Rice" to "325"))
        talk("goods: one product with its details is one line", listOf("Kadaiku saman vandhuruku", "Colgate 2 box, purchase 28, selling 35")) { s ->
            if (s.log.contains("Idhu aanadhum adutha") || s.log.contains("இது ஆனதும் அடுத்து")) "split into items" else if (s.nothingWritten()) null else "wrote before Confirm" }
        talk("goods: a payment in between is a payment draft", listOf("Kadaiku saman vandhuruku", "Kumar 500 kuduthan", "TAP")) { s ->
            if (s.tools.saved.singleOrNull()?.partyName == "Kumar" && s.inv.moves.isEmpty()) null else "saved ${s.tools.saved.map { it.partyName }} moves ${s.inv.moves.size}" }
        talk("reminder cancelled before Confirm", listOf("naalaiku 10 manikku Kumar ku call panna remind pannu", "venam")) { s -> if (s.tools.rems.isEmpty()) null else "reminder set after venam" }
        talk("list then details", listOf("Innaiku yar tharanum", "details sollu")) { s ->
            if (s.log.contains("1. Kumar") && s.log.contains("2. Ramesh")) null else "no numbered list" }
        talk("list then total", listOf("Overdue list sollu", "avanga total evlo")) { s -> if (s.log.contains("₹6,000")) null else "no total" }
        talk("topic switch keeps the draft open", listOf("Kumar 2000 kuduthan", "Colgate stock evlo", "aama")) { s ->
            if (s.tools.saved.singleOrNull()?.partyName == "Kumar") null else "draft lost: ${s.tools.saved.map { it.partyName }}" }
        // Context memory, said the many ways owners say it (owner, 11 Oct 2026): "ivan", "adhe aal", "rendavadhu aal", an amount alone
        // right after a person, "2000 illa 1500", and a product's price asked after the product.
        fun saved(name: String, amount: String, kind: PlanKind): (Shop) -> String? = { s ->
            // (spoken, a new name stays in Tamil letters — "தினேஷ்" is the same Dinesh, as in [entry])
            if (s.tools.saved.singleOrNull()?.let { (it.partyName == name || com.shopai.app.util.NameSound.same(it.partyName, name)) && it.amount.compareTo(BigDecimal(amount)) == 0 && it.kind == kind } == true) null
            else "saved ${s.tools.saved.map { "${it.partyName} ${it.amount} ${it.kind}" }}" }
        talk("ivan is the person just asked", listOf("Kumar evlo tharanum", "ivan balance enna"), check = nothingWritten("₹4,000"))
        talk("adhe aal after a stock question", listOf("Ramesh evlo tharanum", "Colgate stock evlo", "adhe aal evlo tharanum"), check = nothingWritten("₹2,500"))
        talk("the list's second person", listOf("Innaiku yar tharanum", "rendavadhu aal evlo tharanum"), check = nothingWritten("₹2,500"))
        talk("an amount alone after a person", listOf("Kumar evlo tharanum", "2000 kuduthan", "TAP"), check = saved("Kumar", "2000", PlanKind.PAYMENT_IN))
        talk("2000 illa 1500", listOf("Kumar 2000 kuduthan", "2000 illa 1500", "TAP"), check = saved("Kumar", "1500", PlanKind.PAYMENT_IN))
        talk("the product's rate, then its margin", listOf("Colgate rate enna", "labam evlo"), check = nothingWritten("₹7"))
        talk("adhoda rate after its stock", listOf("Colgate stock evlo", "adhoda rate enna"), check = nothingWritten("₹35"))
        talk("athoda selling price after details", listOf("Oil details", "athoda selling price"), check = nothingWritten("₹170"))
        // The mic cut the owner off (owner, 11 Oct 2026): "Dinesh pay" → Kai asks who pays whom, then how much and when → one draft.
        talk("cut off: Kai asks who pays whom", listOf("Dinesh pay"), check = nothingWritten("Dinesh"))
        talk("cut off: Dinesh pays me", listOf("Dinesh pay", "enaku pay pannum", "10000 nalaiku", "TAP"), check = saved("Dinesh", "10000", PlanKind.CREDIT_GIVEN))
        talk("cut off: I pay Dinesh", listOf("Dinesh kitta", "naan pay pannanum", "5000 naalaiku", "TAP"), check = saved("Dinesh", "5000", PlanKind.DEBIT_TAKEN))
        talk("cut off: everything in the answer", listOf("Dinesh pay", "enakku 10000 naalaiku tharanum", "TAP"), check = saved("Dinesh", "10000", PlanKind.CREDIT_GIVEN))
        talk("cut off: venam drops it", listOf("Dinesh pay", "venam"), check = nothingWritten())
        talk("a follow-up is not a cut-off line", listOf("Kumar evlo tharanum", "Ramesh kitta?"), check = nothingWritten("₹2,500"))
        talk("next monday is the due date", listOf("Kumar enakku 2000 next monday tharanum")) { _ ->
            if (lastTurn?.card?.lines?.any { it.contains("October 12") || it.contains("அக்டோபர் 12") } == true) null else "no due date: ${lastTurn?.card?.lines}" }
        // Context said the new ways (owner, 12 Oct 2026, 1034 phrasings typed + spoken): "andha aalu", "kadaisi date", "endha naal",
        // "rendu peroda … serthu", "adhu 1 box vandhirukku", "thappu, 1800", "last aalukku", "vithutom".
        val dueToday: (Shop) -> String? = { s -> lastTurn?.reply?.text.orEmpty().let { t -> if ((t.contains("innaikku", true) || t.contains("இன்னைக்கு")) && s.nothingWritten()) null else "said: $t" } }
        talk("andha aalu is the person just asked", listOf("Kumar kanakku enna", "andha aalu evlo tharavendiyadhu"), check = nothingWritten("₹4,000"))
        talk("kadaisi date is the due date", listOf("Kumar kanakku enna", "kadaisi date enna"), check = dueToday)
        talk("endha naal tharuvan", listOf("Kumar baaki evlo", "endha naal tharuvan"), check = dueToday)
        talk("rendu peroda baaki serthu", listOf("Kumar baaki evlo", "Ramesh baaki evlo", "rendu peroda baaki serthu"), check = nothingWritten("₹6,500"))
        talk("adhu 1 box vandhirukku is the product just asked", listOf("Colgate evlo irukku", "adhu 1 box vandhirukku", "TAP"), check = stock("Colgate" to "288"))
        talk("athula 1 box vithutom", listOf("Colgate stock enna", "athula 1 box vithutom", "TAP"), check = stock("Colgate" to "192"))
        talk("thappu, 1800", listOf("Kumar 2000 kuduthan", "thappu, 1800", "TAP"), check = saved("Kumar", "1800", PlanKind.PAYMENT_IN))
        talk("last aalukku call pannu", listOf("innaiku yaar tharanum", "last aalukku call pannu"), check = nothingWritten("Ramesh"))
        // A reminder said aloud keeps the owner's own Tamil words; "reminde" (typed so) is not part of what to do.
        talk("a spoken reminder keeps its Tamil", listOf("டிசம்பர் 3 காலைல 10 மணிக்கு சபரிக்கு பர்த்டே விஷ் பண்ணணும் ரிமைண்ட் பண்ணு", "TAP")) { s ->
            when (val t = s.tools.rems.singleOrNull()?.title) { null -> "no reminder"; "சபரிக்கு பர்த்டே விஷ் பண்ணணும்" -> null; else -> "title: $t" } }
        talk("reminde pannu", listOf("December 3 morning 10 sabari ku birthday ku wish pannanum reminde pannu", "TAP")) { s ->
            when (val t = s.tools.rems.singleOrNull()?.title) { null -> "no reminder"; else -> if (t.contains("remind", true) || t.contains("ரிமைண்ட்")) "title: $t" else null } }
        // A word Kai has never heard for what happened (owner, 19 Oct 2026: "words maaralaam, intent purinjikkanum"): a person and an
        // amount is still an entry, a product and a count is still stock — only which way is asked, never the balance read out with
        // the amount dropped, never "edha pathi?", never a guess. The next word finishes it.
        val wayAsked: (Shop) -> String? = { s -> lastTurn?.reply?.text.orEmpty().let { t ->
            if (s.nothingWritten() && (t.contains("vandhucha") || t.contains("வந்துச்சா") || t.contains("kuduthaara") || t.contains("குடுத்தாரா") ||
                    t.contains("கொடுத்தாரா"))) null else "said: $t" } }
        talk("person, amount, a word never heard: which way", listOf("Kumar 600 jamaai pannitaan"), check = wayAsked)
        talk("product, count, a word never heard: which way", listOf("Colgate 2 box gadabada aachu"), check = wayAsked)
        talk("product, count, a word never heard: then vandhuchu", listOf("Colgate 2 box gadabada aachu", "vandhuchu", "TAP"), check = stock("Colgate" to "336"))
        talk("product, count, a word never heard: then vithuchu", listOf("Rice 5 kg gadabada aachu", "vithuchu", "TAP"), check = stock("Rice" to "245"))
        // A time is never an amount, and "sollu" after a report word is the report, not a reminder.
        talk("11 manikku is never ₹11", listOf("naalai 11 manikku Lakshmi baaki vaanga maranthudaama sollu")) { s ->
            if (s.tools.saved.isEmpty() && lastTurn?.card?.lines.orEmpty().none { it.contains("₹11") }) null else "drafted ₹11: ${lastTurn?.card?.lines}" }
        talk("innaiku sales sollu is the sales", listOf("innaiku sales sollu")) { s ->
            if (s.nothingWritten() && lastTurn?.card?.buttons.orEmpty().none { it.action is KaiAction.ConfirmReminder }) null else "read as a reminder" }
        // "pudhu batch vandhuchu" names no batch: the batch is never "vandhuchu".
        talk("pudhu batch vandhuchu is no batch", listOf("Bournvita 10 bottle pudhu batch vandhuchu", "50", "60")) { _ ->
            if (lastTurn?.card?.lines.orEmpty().none { it.contains("vandhuchu", true) || it.contains("வந்துச்சு") }) null else "batch: ${lastTurn?.card?.lines}" }
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

    /**
     * The owner's lines (13 Oct 2026), each typed and as the mic writes it: the whole balance paid after "Dinesh evlo tharanum",
     * a product with "-la" on it ("Vengayathula"), the profit & loss estimate and the stock report — from the records, nothing
     * written before Confirm, nothing invented when the books have no figures.
     */
    @Test fun fullPaymentProductCaseAndReports() {
        val fails = mutableListOf<String>()
        fun shop(figures: Boolean = true) = Shop { add("c9", "Dinesh", true, "4000", d(10, 1), d(10, 10)) }.apply {
            inv.items += Inventory.Item("p7", "Vengayam", "KG", BigDecimal("80"), emptyMap(), BigDecimal("30"), BigDecimal("40"), "Grocery", null)
            if (figures) tools.money = mapOf("2026-10" to Triple(BigDecimal("300000"), BigDecimal("200000"), BigDecimal("25000")),
                "2026-09" to Triple(BigDecimal("250000"), BigDecimal("180000"), BigDecimal("20000")))
        }
        fun all(t: KaiTurn) = t.reply.text + " " + t.card?.lines.orEmpty().joinToString(" ")
        fun play(name: String, lines: List<String>, figures: Boolean = true, check: (Shop, KaiTurn) -> String?) {
            val s = shop(figures)
            var last: KaiTurn? = null
            for (l in lines) last = s.say(l)
            check(s, last!!)?.let { fails += "[$name] $it${s.log}" }
        }
        fun has(vararg w: String): (Shop, KaiTurn) -> String? = { s, t -> w.firstOrNull { !all(t).contains(it) }?.let { "lacks '$it'" } ?: if (s.nothingWritten()) null else "wrote something" }
        fun paidFull(s: Shop, t: KaiTurn): String? {
            if (!s.nothingWritten()) return "wrote before Confirm"
            val b = t.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmPlan } ?: return "no draft"
            s.act(b.action)
            val ok = s.tools.saved.singleOrNull()?.let { it.kind == PlanKind.PAYMENT_IN && it.partyName == "Dinesh" && it.amount.compareTo(BigDecimal("4000")) == 0 } == true
            return if (ok) null else "saved ${s.tools.saved.map { "${it.kind} ${it.partyName} ${it.amount}" }}"
        }
        // "Dinesh evlo tharanum" → the whole of it paid, said many ways.
        for (paid in listOf("yellathaium kuduthutan", "ellathaiyum kuduthutan", "settle pannitan", "full ah kattitan", "4000 kuduthutan",
                "எல்லாத்தையும் குடுத்துட்டான்", "ஃபுல்லா கட்டிட்டான்", "செட்டில் பண்ணிட்டான்"))
            play("full after the balance: $paid", listOf(if (paid.any { it in '஀'..'௿' }) "தினேஷ் எவ்வளவு தரணும்" else "Dinesh evlo tharanum", paid), check = ::paidFull)
        for (line in listOf("Dinesh yellathaium kuduthutan", "Dinesh full payment settle pannitan", "Dinesh full ah kattitan", "தினேஷ் எல்லாத்தையும் குடுத்துட்டான்"))
            play("full, named: $line", listOf(line), check = ::paidFull)
        // A word that is not "all": never the whole balance.
        play("part, not full", listOf("Dinesh evlo tharanum", "konjam kuduthutan")) { s, t ->
            if (s.nothingWritten() && t.card?.lines?.none { it.contains("₹4,000") } != false) null else "took it as the full ₹4,000" }
        // A product with "-la" / "-oda" on it.
        for (q in listOf("Vengayathula evlo labam", "வெங்காயத்துல எவ்வளவு லாபம்")) play("product case: $q", listOf(q), check = has("Vengayam", "₹10"))
        for (q in listOf("Ricela evlo labam", "ரைஸ்ல எவ்வளவு லாபம்")) play("product case and this month: $q", listOf(q), check = has("Rice", "₹9", "320", "₹2,880"))
        play("not a product: asked, not another product", listOf("Kathirikkayathula evlo labam")) { s, t -> if (s.nothingWritten() && !all(t).contains("Vengayam")) null else "guessed" }
        // Profit & loss: an estimate from the books, with last month beside it.
        for (q in listOf("indha maasam labam evlo", "profit and loss report", "இந்த மாசம் லாபம் எவ்வளவு"))
            play("P&L: $q", listOf(q)) { s, t -> has("₹3,00,000", "₹25,000", "₹2,54,560", "₹2,09,560")(s, t)
                ?: if (all(t).contains("September") || all(t).contains("செப்டம்பர்")) null else "no last month" }
        play("P&L with no figures: nothing invented", listOf("indha maasam labam evlo"), figures = false) { s, t ->
            if (s.nothingWritten() && !all(t).contains("₹") && !t.reply.text.contains("sumaar")) null else "invented a figure" }
        // The stock report, the money in stock, what sells and what doesn't.
        for (q in listOf("stock report", "stock performance report", "ஸ்டாக் அறிக்கை"))
            play("stock report: $q", listOf(q), check = has("Rice", "Oil", "Maggi", "₹63,920", "₹43,200"))
        for (q in listOf("stock la evlo panam mudangi irukku", "stock value evlo", "ஸ்டாக்ல எவ்வளவு பணம் முடங்கியிருக்கு"))
            play("stock value: $q", listOf(q), check = has("₹63,920", "Oil", "₹36,000"))
        play("one product's stock value", listOf("Colgate stock value"), check = has("₹6,720"))
        for (q in listOf("vikkadha stock", "medhuva vikkura products", "விக்காத பொருள் எது")) play("unsold: $q", listOf(q), check = has("Oil", "Baniyan"))
        for (q in listOf("adhigama vikkura porul", "endha item adhigama vikkudhu")) play("best seller: $q", listOf(q), check = has("Rice"))
        assertTrue("${fails.size} failed:\n\n" + fails.joinToString("\n\n"), fails.isEmpty())
    }

    /**
     * "Intha bill la irukura stock ah add pannu / seru / seathuko", "… kali / less / kammi pannu", "purchase invoice-na add, sales-na
     * less" (owner, 13 Oct 2026): the bill scanner opens with the way the stock goes, the credit / debit entry as always, nothing
     * written in chat. A line that doesn't say which way is asked, and the answer opens the scanner — typed and spoken.
     */
    @Test fun billStockByChat() {
        val fails = mutableListOf<String>()
        val inWay = com.shopai.app.util.BillStock.Way.IN
        val outWay = com.shopai.app.util.BillStock.Way.OUT
        fun play(lines: List<String>, way: com.shopai.app.util.BillStock.Way?, opens: Boolean = true) {
            com.shopai.app.util.BillStock.Request.take()
            val s = Shop()
            var t: KaiTurn? = null
            for (l in lines) t = s.say(l)
            val asked = com.shopai.app.util.BillStock.Request.take()
            val opened = t!!.direct == KaiAction.OpenScanner
            if (asked != way || opened != opens || t.card?.buttons?.none { it.action == KaiAction.OpenScanner } != false || !s.nothingWritten())
                fails += "${lines.joinToString(" → ")}: way=$asked opened=$opened${s.log}"
        }
        for (l in listOf("intha bill la irukura stock ah add pannu", "intha bill la irukura stock ah seru", "intha bill la irukura stock ah seathuko",
                "இந்த பில்ல இருக்குற ஸ்டாக்க சேர்த்துக்கோ", "இந்த பில்ல இருக்குற ஸ்டாக்க சேரு")) play(listOf(l), inWay)
        for (l in listOf("intha bill la irukura stock ah kali pannu", "intha bill la irukura stock ah less pannu", "intha bill la irukura stock ah kammi pannu",
                "இந்த பில்ல இருக்குற ஸ்டாக்க கம்மி பண்ணு", "இந்த பில்ல இருக்குற ஸ்டாக்க கழி")) play(listOf(l), outWay)
        // A plain scan, and the rule said: by the bill (purchase in, sale out); the rule shows the button, not the camera at once.
        for (l in listOf("intha bill scan pannu", "இந்த பில் ஸ்கேன் பண்ணு")) play(listOf(l), null)
        for (l in listOf("Purchase invoice ah iruntha, inventory la stock add pannidu", "Sales invoices ah iruntha, inventory la stock ah less panniko"))
            play(listOf(l), null, opens = false)
        // Which way not said: asked; the answer opens the scanner that way.
        play(listOf("intha bill la irukura stock ah check pannu", "add pannu"), inWay)
        play(listOf("intha bill la irukura stock ah check pannu", "sales bill"), outWay)
        play(listOf("இந்த பில்ல இருக்குற ஸ்டாக்க செக் பண்ணு", "கம்மி பண்ணு"), outWay)
        play(listOf("intha bill la irukura stock ah check pannu", "theriyala"), null)
        // The question is asked, never guessed (a word not in the list: "check").
        run {
            val s = Shop()
            val t = s.say("intha bill la irukura stock ah check pannu")
            if (t.direct != null || !(t.reply.text.contains("add pannanuma") && t.reply.text.contains("kammi pannanuma"))) fails += "not asked: ${t.reply.text}"
        }
        // Not about a bill's stock: unchanged.
        run {
            val s = Shop()
            val t = s.say("Colgate 2 box vandhuchu")
            if (t.direct != null || t.card?.buttons?.none { it.label.startsWith("Confirm") } != false) fails += "stock line changed: ${t.reply.text}"
        }
        assertTrue("${fails.size} failed:\n\n" + fails.joinToString("\n\n"), fails.isEmpty())
    }

    @Test fun ownersDay() {
        moneyLines(); reminderLines(); stockLines(); questionLines(); conversationLines(); feedLines()
        // The same lines said into the mic.
        mic = true
        moneyLines(); reminderLines(); stockLines(); questionLines(); conversationLines(); feedLines()
        // And as the phone often writes it: written Tamil ("இருக்கிறது", "தர வேண்டும்", "கொடுத்தான்") — owner, 18 Oct 2026.
        written = true
        moneyLines(); reminderLines(); stockLines(); questionLines(); conversationLines(); feedLines()
        written = false
        mic = false
        System.getProperty("sweep.out")?.let { java.io.File(it).writeText("$count lines, ${failures.size} failed\n\n" + failures.joinToString("\n\n")) }
        assertTrue("${failures.size} of $count owner lines failed:\n\n" + failures.joinToString("\n\n"), failures.isEmpty())
    }

    /**
     * The owner's 8-shop stock list (src/test/resources/kai/shop-stock-8.txt, 12 Oct 2026): every product is added 5 at a time, said
     * 7 ways — Tamil, Tanglish, English, Tamil + Tanglish, Tanglish + English, the mic's Tamil, the mic's Tanglish — with the shop
     * keeping it under its English name, then under its Tanglish name. Each must reach that one record, nothing written before
     * Confirm, no other product touched, no money saved. A shop that keeps the names in Tamil letters: never the wrong product.
     */
    @Test fun eightShopStockList() {
        val rows = javaClass.getResourceAsStream("/kai/shop-stock-8.txt")!!.bufferedReader().readLines()
            .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.map { it.split('|').map(String::trim) }
        val bad = mutableListOf<String>()
        var played = 0
        for ((shopName, goods) in rows.groupBy { it[0] }) for (keep in listOf(3, 2, 1)) for ((idx, g) in goods.withIndex()) {
            val (_, ta, tl, en, unit) = g
            val qTl = when (unit) { "KG" -> "5 kg"; "LITRE" -> "5 litre"; else -> "5" }
            val qEn = when (unit) { "KG" -> "5 kg"; "LITRE" -> "5 litres"; else -> "5 pieces" }
            val qTa = when (unit) { "KG" -> "5 கிலோ"; "LITRE" -> "5 லிட்டர்"; else -> "5" }
            val lines = listOf(
                "$ta $qTa வந்திருக்கு", "$tl $qTl vandhuchu", if (idx % 2 == 0) "Add $qEn of $en to stock" else "$en $qEn received",
                "$ta $qTl stock add pannu", if (idx % 2 == 0) "$en $qTl vandhuchu" else "$tl $qEn stock in", "$ta $qTa வந்துச்சு",
                KaiMicForm.of("$tl $qTl vandhuchu") ?: "$tl $qTl vandhuchu",
            )
            for (said in lines) {
                played++
                val s = Shop()
                s.inv.items.clear()
                goods.forEachIndexed { j, q -> s.inv.items += KaiInventoryChatTest.Inventory.Item("x$j", q[keep].replaceFirstChar { it.uppercase() }, q[4], BigDecimal("100"), emptyMap(), null, null, shopName, null) }
                val t = s.say(said)
                val before = s.nothingWritten()
                t.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmStock || it.action is KaiAction.ConfirmPlan }?.let { s.act(it.action) }
                val target = s.inv.items.first { it.id == "x$idx" }
                val othersSame = s.inv.items.filter { it.id != "x$idx" }.all { it.stock.compareTo(BigDecimal("100")) == 0 }
                val added = target.stock.compareTo(BigDecimal("105")) == 0
                val ok = before && othersSame && s.tools.saved.isEmpty() && (added || keep == 1)
                if (!ok) bad += "[$shopName, kept as ${g[keep]}] «$said»${s.log}"
            }
        }
        assertTrue("${bad.size} of $played stock lines failed:\n\n" + bad.take(40).joinToString("\n\n"), bad.isEmpty())
    }

    /**
     * "Kumar oda credit debit history PDF kudu" (owner, 12 Oct 2026): a person's statement as a PDF, sent by the owner on WhatsApp.
     * Typed and as the mic writes it: the card for that one person (WhatsApp + PDF buttons, the period asked), nothing written.
     * Money, reminders and plain history questions stay what they were; a person not in the books is said plainly.
     */
    @Test fun statementPdf() {
        val bad = mutableListOf<String>()
        fun check(typed: String, partyId: String, from: LocalDate?, to: LocalDate, sums: String, phone: String? = null) {
            for (said in listOfNotNull(typed, KaiMicForm.of(typed))) {
                val s = Shop()
                phone?.let { s.l.phones["c1"] = it }
                val t = s.say(said)
                val share = t.card?.buttons?.mapNotNull { it.action as? KaiAction.ShareStatement }.orEmpty()
                val ok = s.nothingWritten() && share.size == 2 && share.all { it.partyId == partyId && it.from == from && it.to == to } &&
                    share.map { it.whatsapp } == listOf(true, false) && t.card!!.lines.any { line -> sums.split(';').all { line.contains(it.trim()) } } &&
                    (phone == null || t.card!!.lines.first().contains(phone))
                if (!ok) bad += "«$said»${s.log}"
            }
        }
        val all = d(10, 8)
        check("Kumar oda credit debit history pdf ah kudu", "c1", null, all, "₹12,000; ₹8,000; ₹4,000")
        check("Kumar statement pdf venum", "c1", null, all, "₹12,000; ₹8,000; ₹4,000")
        check("Kumar kanakku pdf la anuppu", "c1", null, all, "₹4,000")
        check("Kumar statement whatsapp la anuppu", "c1", null, all, "₹4,000", phone = "98400 12345")
        check("Kumar oda full kanakku share pannu", "c1", null, all, "₹4,000")
        check("Kumar transactions download pannu", "c1", null, all, "₹4,000")
        check("Kumar pona maasam statement pdf kudu", "c1", d(9, 1), d(9, 30), "₹8,000; ₹5,000; ₹3,000")
        check("Give me Kumar's statement as PDF", "c1", null, all, "₹12,000; ₹8,000; ₹4,000")
        check("Send Kumar ledger pdf", "c1", null, all, "₹4,000")
        check("Ramesh pdf kudu", "c2", null, all, "₹4,500; ₹2,000; ₹2,500")
        check("ABC Traders statement pdf", "s1", null, all, "₹10,000; ₹0; ₹10,000")
        check("குமார் ஓட கிரெடிட் டெபிட் ஹிஸ்டரி பிடிஎஃப் குடு", "c1", null, all, "₹12,000; ₹8,000; ₹4,000")
        check("குமார் கணக்கு பிடிஎஃப் வேணும்", "c1", null, all, "₹4,000")
        check("குமார் ஸ்டேட்மெண்ட் வாட்ஸ்அப்ல அனுப்பு", "c1", null, all, "₹4,000")
        // "avan" right after Kumar: Kumar's statement.
        run {
            val s = Shop()
            s.say("Kumar evlo tharanum")
            val share = s.say("avan statement pdf anuppu").card?.buttons?.mapNotNull { it.action as? KaiAction.ShareStatement }.orEmpty()
            if (share.map { it.partyId }.distinct() != listOf("c1")) bad += "«avan statement pdf anuppu» after Kumar${s.log}"
        }
        // "Lokesh enaku evlo" → "Yeppa tharanum" → "Itha pdf ah kudu" (owner, 12 Oct 2026): "itha" is Lokesh, typed and into the mic.
        val lokesh = listOf(
            listOf("Lokesh enaku evlo", "Yeppa tharanum", "Itha pdf ah kudu"),
            listOf("Lokesh enaku evlo", "Yeppa tharanum", "idha whatsapp la anuppu"),
            listOf("Lokesh enaku evlo", "Itha pdf ah kudu"),
            listOf("லோகேஷ் எனக்கு எவ்வளவு", "எப்ப தரணும்", "இத பிடிஎஃப்ஆ குடு"),
            listOf("லோகேஷ் எனக்கு எவ்வளவு", "எப்ப தரணும்", "இதை வாட்ஸ்அப்ல அனுப்பு"),
        )
        for (flow in lokesh) for (lines in listOfNotNull(flow, flow.map { KaiMicForm.of(it) ?: it }.takeIf { it != flow })) {
            val s = Shop { add("c9", "Lokesh", true, "6000", d(9, 10), d(10, 20)).payments += BigDecimal("1500") to d(9, 30); add("c9", "Lokesh", true, "2000", d(10, 2), d(10, 15)) }
            var t: KaiTurn? = null
            for (l in lines) t = s.say(l)
            val share = t?.card?.buttons?.mapNotNull { it.action as? KaiAction.ShareStatement }.orEmpty()
            if (share.size != 2 || share.any { it.partyId != "c9" } || t?.reply?.text?.contains("₹6,500") != true || !s.nothingWritten()) bad += "Lokesh flow ${lines}${s.log}"
        }
        // "Itha pdf ah kudu" with nobody talked about: Kai asks whose; "idha whatsapp la anuppu": what Kai can't send, as before.
        run {
            val s = Shop()
            if (!s.say("Itha pdf ah kudu").reply.text.contains("Yaaroda")) bad += "«Itha pdf ah kudu» alone${s.log}"
            val s2 = Shop()
            if (!s2.say("idha whatsapp la anuppu").reply.text.contains("WhatsApp / SMS")) bad += "«idha whatsapp la anuppu» alone${s2.log}"
        }
        // Not a statement: money, a reminder, the history said in the chat. A name not in the books: said, no card.
        for ((said, want) in listOf("Kumar history sollu" to "₹12,000", "Suresh statement pdf" to "Suresh", "statement pdf kudu" to "Yaaroda")) {
            val s = Shop()
            val t = s.say(said)
            if (t.card?.buttons?.any { it.action is KaiAction.ShareStatement } == true || !t.reply.text.contains(want) || !s.nothingWritten()) bad += "«$said»${s.log}"
        }
        for (said in listOf("Kumar ku 500 whatsapp la anuppu", "Kumar ku whatsapp la remind pannu", "Kumar 2000 kuduthan")) {
            val s = Shop()
            val t = s.say(said)
            if (t.card?.buttons?.any { it.action is KaiAction.ShareStatement } == true) bad += "«$said» is not a statement${s.log}"
        }
        assertTrue("${bad.size} statement lines failed:\n\n" + bad.joinToString("\n\n"), bad.isEmpty())
    }

    /**
     * The owner's phone, 12 Oct 2026 (screenshots): one message at a time, Kai must keep the thread — typed and into the mic.
     * New product rates said as "அம்பது ரூபா", a product with no quantity, "அஞ்சாம் தேதி" for a due date, "athukuda 100 add
     * panniko" after a saved entry, and a typed name for a person kept in Tamil letters. Nothing is written before Confirm.
     */
    @Test fun ownersPhoneFlows() {
        val bad = mutableListOf<String>()
        val madhan: Ledger.() -> Unit = { add("c7", "மதன்", true, "2000", d(9, 20), d(10, 30)) }
        fun flow(lines: List<String>, confirmFirst: Boolean = false, more: Ledger.() -> Unit = {}, ok: (KaiTurn, Shop) -> Boolean) {
            val mic = lines.map { KaiMicForm.of(it) ?: it }
            for (said in listOfNotNull(lines, mic.takeIf { it != lines })) {
                val s = Shop(more)
                var t: KaiTurn? = null
                said.forEachIndexed { i, l ->
                    t = s.say(l)
                    if (i == 0 && confirmFirst) t!!.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmPlan }?.let { s.act(it.action) }
                }
                val savedBefore = if (confirmFirst) 1 else 0
                if (s.tools.saved.size != savedBefore || s.inv.moves.isNotEmpty() || !ok(t!!, s)) bad += "$said${s.log}"
            }
        }
        fun card(t: KaiTurn, vararg words: String) = t.card != null && words.all { w -> t.card!!.lines.any { it.contains(w) } || t.reply.text.contains(w) }
        // 1. A new product's rates, the selling rate said "அம்பது ரூபா" / "50 rupees"; "30 rupees" to the gram question is the purchase rate.
        flow(listOf("Cinthol soap 50 add pannu", "50 pieces", "30 rupees", "50 rupees")) { t, _ -> card(t, "₹30", "₹50", "50 pieces") && t.card!!.buttons.any { it.action is KaiAction.ConfirmGoods || it.action is KaiAction.ConfirmStock || it.action.toString().contains("Item") } }
        flow(listOf("சிந்தால் சோப்பு 50 ஆட் பண்ணு", "50 பீஸ்", "முப்பது ரூபா", "அம்பது ரூபா")) { t, _ -> card(t, "₹30", "₹50", "50 pieces") }
        // 2. A product with no quantity: Kai asks how much came in (never everyone's pending total).
        flow(listOf("Cinthol soap add pannu")) { t, _ -> t.reply.text.contains("evlo vandhirukku") || t.reply.text.contains("எவ்வளவு வந்திருக்கு") }
        flow(listOf("சிந்தால் சோப்பு ஆட் பண்ணு")) { t, _ -> t.reply.text.contains("எவ்வளவு வந்திருக்கு") }
        // 3. "அஞ்சாம் தேதி" / "anjaam thethi" for the due date: the 5th — asked which month when Kai asked the date, or the open
        // draft's due date (the next 5th).
        fun fifth(t: KaiTurn, day: Int) = t.reply.text.contains("$day-ஆ") || card(t, "நவம்பர் $day") || card(t, "November $day")
        flow(listOf("குமார் 3000 தரணும் ஆட் பண்ணிக்கோ", "அஞ்சாம் தேதி")) { t, _ -> fifth(t, 5) }
        flow(listOf("குமார் 3000 தரணும் ஆட் பண்ணிக்கோ", "மூணாம் தேதி")) { t, _ -> fifth(t, 3) }
        flow(listOf("சுரேஷ் 3000 தரணும் ஆட் பண்ணிக்கோ", "அஞ்சாம் தேதி")) { t, _ -> fifth(t, 5) }
        flow(listOf("Kumar 3000 tharanum", "anjaam thethi")) { t, _ -> fifth(t, 5) || t.reply.text.contains("5-aa") }
        // … and with the draft card already open: that draft's due date.
        flow(listOf("Kumar 3000 tharanum add panniko", "anjaam thethi")) { t, _ -> card(t, "November 5") || card(t, "நவம்பர் 5") }
        // 4. "athukuda 100 add panniko" right after an entry was saved: ₹100 more on that person — a new draft.
        flow(listOf("Kumar 2000 credit", "athukuda 100 add panniko"), confirmFirst = true) { t, _ -> card(t, "Kumar", "₹100") }
        flow(listOf("madhan 2000 credit", "athukuda 100 add panniko"), confirmFirst = true, more = madhan) { t, _ -> card(t, "மதன்", "₹100") }
        flow(listOf("மதன் 2000 கிரெடிட்", "அதுகூட 100 ஆட் பண்ணிக்கோ"), confirmFirst = true, more = madhan) { t, _ -> card(t, "மதன்", "₹100") }
        // 5. A typed name for a person kept in Tamil letters: "madhan ku 100 add panniko" is a ₹100 entry on மதன், not a balance answer.
        flow(listOf("madhan ku 100 add panniko"), more = madhan) { t, _ -> card(t, "மதன்", "₹100") }
        assertTrue("${bad.size} flows failed:\n\n" + bad.joinToString("\n\n"), bad.isEmpty())
    }
    /**
     * Context (owner, 12 Oct 2026: "owner enna context-la solla varaaru-nu purinjukittu adhukku ethamaari answer kudukanum"): a
     * follow-up said with no name — a due date, a correction, "pdf kudu", "call pannu", "remind pannu", "rate evlo", "10 illa 15",
     * "nethu?" — is about what was just said. Typed and as the mic writes it; never a generic "edha pathi?" / "yaar pathi?".
     */
    @Test fun contextFollowUps() {
        val out = StringBuilder(); var total = 0; var badN = 0
        val generic = listOf("edha pathi sollureenga", "எதைப் பத்தி சொல்றீங்க", "yaar pathi kekkureenga", "யார் பத்தி கேக்குறீங்க", "vera vishayam pathiyaa",
            "வேற விஷயம் பத்தியா", "clear-ah sollunga", "தெளிவா சொல்லுங்க", "edha pathi kekkureenga", "எதைப் பத்தி கேக்குறீங்க")
        fun txt(t: KaiTurn?) = (t?.reply?.text.orEmpty() + " | " + t?.card?.lines?.joinToString(" | ").orEmpty())
        // check: (lastTurn, shop) -> error or null; anyWords: any of these words must be in reply/card
        fun conv(name: String, lines: List<String>, vararg anyOf: String, check: ((KaiTurn, Shop) -> String?)? = null) {
            val mic = lines.map { if (it == "TAP") it else KaiMicForm.of(it) ?: it }
            for ((how, said) in listOf("typed" to lines, "mic" to mic).distinctBy { it.second }) {
                total++
                val s = Shop(); var t: KaiTurn? = null; val errs = mutableListOf<String>()
                for (l in said) {
                    if (l == "TAP") { val b = t?.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmPlan || it.action is KaiAction.ConfirmStock || it.action is KaiAction.ConfirmReminder }
                        if (b == null) errs += "nothing to TAP" else s.act(b.action); continue }
                    t = s.say(l)
                    generic.firstOrNull { txt(t).contains(it, ignoreCase = true) }?.let { errs += "generic after «$l»" }
                }
                if (anyOf.isNotEmpty() && anyOf.none { txt(t).contains(it, ignoreCase = true) }) errs += "want any of ${anyOf.toList()}"
                check?.invoke(t!!, s)?.let { errs += it }
                if (errs.isNotEmpty()) { badN++; out.append("##### [$how] $name — ${errs.joinToString("; ")}").append(s.log).append("\n\n") }
            }
        }
        fun saved(n: Int): (KaiTurn, Shop) -> String? = { _, s -> if (s.tools.saved.size == n) null else "saved=${s.tools.saved.size}" }
        val none: (KaiTurn, Shop) -> String? = { _, s -> if (s.nothingWritten() || s.tools.saved.isEmpty() && s.inv.moves.isEmpty()) null else "wrote" }
        fun draft(who: String, amt: String): (KaiTurn, Shop) -> String? = { t, _ ->
            if (t.card?.buttons?.any { it.action is KaiAction.ConfirmPlan } == true && txt(t).contains(amt) && (txt(t).contains(who) || txt(t).contains(KaiMicForm.repeated(who.lowercase())))) null else "no draft $who $amt" }
        fun share(id: String): (KaiTurn, Shop) -> String? = { t, _ -> if (t.card?.buttons?.any { (it.action as? KaiAction.ShareStatement)?.partyId == id } == true) null else "no statement $id" }
        // ---- a payment, then its due date / corrections
        conv("due naalaiku", listOf("Kumar 3000 tharanum", "naalaiku"), check = draft("Kumar", "₹3,000"))
        conv("due next month 10", listOf("Kumar 3000 tharanum", "adutha maasam 10"), check = draft("Kumar", "₹3,000"))
        conv("due December 5", listOf("Kumar 3000 tharanum", "December 5"), check = draft("Kumar", "₹3,000"))
        conv("due 15th", listOf("Kumar 3000 tharanum", "15th"), "15")
        conv("due anjaam thethi", listOf("Kumar 3000 tharanum", "anjaam thethi"), "5")
        conv("due next week", listOf("Kumar 3000 tharanum", "next week"))
        conv("due venam", listOf("Kumar 3000 tharanum", "due venam"), check = draft("Kumar", "₹3,000"))
        conv("due theriyala", listOf("Kumar 3000 tharanum", "theriyala"))
        conv("due then cancel", listOf("Kumar 3000 tharanum", "cancel pannu"), check = none)
        conv("amount corrected", listOf("Kumar 3000 tharanum", "illa 2000 dhaan"), "₹2,000")
        conv("person corrected", listOf("Kumar 3000 tharanum", "illa Ramesh dhaan"), "Ramesh", "ரமேஷ்")
        conv("due then tap", listOf("Kumar 3000 tharanum", "naalaiku", "TAP"), check = saved(1))
        conv("small talk in between", listOf("Kumar 3000 tharanum", "saptiya", "naalaiku"), check = draft("Kumar", "₹3,000"))
        // ---- a payment draft card
        conv("card seri", listOf("Kumar 2000 kuduthan", "seri"), check = saved(1))
        // Yes said any way, typed or into the mic ("ama", "yes", "ஆமாம்", "எஸ்"): the open draft is the owner's — saved by the engine.
        conv("card ama", listOf("Kumar 2000 kuduthan", "ama"), check = saved(1))
        conv("card yes", listOf("Kumar 2000 kuduthan", "yes"), check = saved(1))
        conv("card ஆமாம்", listOf("குமார் 2000 குடுத்தான்", "ஆமாம்"), check = saved(1))
        conv("card எஸ்", listOf("குமார் 2000 குடுத்தான்", "எஸ்"), check = saved(1))
        conv("card save pannu", listOf("Kumar 2000 kuduthan", "save pannu"), check = saved(1))
        conv("card venam", listOf("Kumar 2000 kuduthan", "venam"), check = none)
        conv("card amount 2500", listOf("Kumar 2000 kuduthan", "2000 illa 2500"), check = draft("Kumar", "₹2,500"))
        conv("card gpay", listOf("Kumar 2000 kuduthan", "gpay la kuduthan"), "UPI", "GPay")
        conv("card to Ramesh", listOf("Kumar 2000 kuduthan", "illa Ramesh kuduthan"), check = draft("Ramesh", "₹2,000"))
        // ---- after a saved entry
        conv("saved then balance", listOf("Kumar 2000 kuduthan", "TAP", "ippo evlo balance"), "₹2,000")
        conv("saved then another", listOf("Kumar 2000 kuduthan", "TAP", "innoru 500 kuduthan"), check = draft("Kumar", "₹500"))
        conv("saved then pdf", listOf("Kumar 2000 kuduthan", "TAP", "pdf kudu"), check = share("c1"))
        conv("saved then when rest", listOf("Kumar 2000 kuduthan", "TAP", "meedhi eppo tharuvaan"), "Kumar", "குமார்")
        conv("saved then athukuda", listOf("Kumar 2000 credit", "TAP", "athukuda 100 add panniko"), check = draft("Kumar", "₹100"))
        conv("saved then remind", listOf("Kumar 2000 kuduthan", "TAP", "naalaiku call panna remind pannu"), "Kumar", "குமார்")
        // ---- after a balance answer
        conv("balance then due", listOf("Selvam evlo tharanum", "eppo tharanum"), "September 20", "செப்டம்பர் 20")
        conv("balance then last payment", listOf("Selvam evlo tharanum", "last ah eppo kuduthan"), "₹3,000")
        conv("balance then history", listOf("Selvam evlo tharanum", "history sollu"), "₹6,000")
        conv("balance then pdf", listOf("Selvam evlo tharanum", "pdf kudu"), check = share("c3"))
        conv("balance then pays", listOf("Selvam evlo tharanum", "ippo 1000 kuduthan"), check = draft("Selvam", "₹1,000"))
        conv("balance then avan pays", listOf("Selvam evlo tharanum", "avan 2000 kuduthan"), check = draft("Selvam", "₹2,000"))
        conv("balance then half", listOf("Selvam evlo tharanum", "avan paadhi kuduthan"), "₹3,000")
        conv("balance then remind", listOf("Selvam evlo tharanum", "naalaiku 10 manikku remind pannu"), "Selvam", "செல்வம்")
        conv("balance then call", listOf("Selvam evlo tharanum", "call pannu"), "Selvam", "செல்வம்")
        conv("balance then another person", listOf("Ramesh evlo tharanum", "Lakshmi?"), "₹1,000")
        conv("both total", listOf("Ramesh evlo tharanum", "Lakshmi evlo tharanum", "rendu perum total evlo"), "₹3,500")
        conv("supplier due", listOf("ABC Traders ku evlo kudukkanum", "eppo kudukkanum"), "ABC")
        conv("supplier then paid", listOf("ABC Traders ku evlo kudukkanum", "5000 kuduthen"), check = draft("ABC Traders", "₹5,000"))
        conv("balance then credit", listOf("Kumar evlo tharanum", "avanukku 500 credit kuduthen"), check = draft("Kumar", "₹500"))
        conv("balance then itha pdf", listOf("Kumar evlo tharanum", "itha pdf ah kudu"), check = share("c1"))
        // ---- stock
        conv("stock card 15 illa", listOf("Lux soap 10 vandhuchu", "10 illa 15"), "15")
        conv("stock card venam", listOf("Colgate 2 box vandhuchu", "venam"), check = none)
        conv("stock then evlo", listOf("Colgate 2 box vandhuchu", "TAP", "ippo evlo irukku"), "336")
        conv("stock ask then in", listOf("Colgate evlo irukku", "2 box vandhuchu"), "Colgate")
        conv("stock ask then rate", listOf("Colgate evlo irukku", "rate evlo"), "₹35", "₹28")
        conv("stock ask then sold", listOf("Rice stock evlo", "2 moota vithuchu"), "Rice", "ரைஸ்")
        conv("stock ask then remind", listOf("Maggi stock evlo", "naalaiku order panna remind pannu"), "Maggi", "மேகி")
        // ---- new product
        conv("new product steps", listOf("Cinthol soap 50 add pannu", "50 pieces", "30 rupees", "50 rupees"), "₹30", "₹50")
        conv("new product no qty", listOf("Cinthol soap add pannu", "50 pieces"))
        // ---- reminders
        conv("reminder then time", listOf("naalaiku Kumar ku call panna remind pannu", "10 manikku"), "10:00", "10 மணி", "10 AM")
        conv("reminder time changed", listOf("naalaiku Kumar ku call panna remind pannu", "10 manikku", "illa 11 manikku"), "11")
        // ---- business questions
        conv("sales then yesterday", listOf("innaiku sales evlo", "nethu?"))
        conv("who owes then biggest", listOf("yaar yaar tharanum", "adhula periya amount yaaru"), "Selvam", "செல்வம்")
        conv("who owes then first", listOf("yaar yaar tharanum", "mudhal aal evlo"), "Selvam", "செல்வம்")
        conv("overdue then total", listOf("overdue list", "mothama evlo"), "₹6,000")
        // ---- new person
        conv("new person due", listOf("Suresh 1500 tharanum", "naalaiku"), check = draft("Suresh", "₹1,500"))
        conv("half said", listOf("Dinesh pay", "enakku pay pannum", "10000 naalaiku"), check = draft("Dinesh", "₹10,000"))
        // The same, other people and other words (not fitted to one set of lines).
        conv("v due apram", listOf("Ramesh 1500 tharanum", "apram solren"), check = draft("Ramesh", "₹1,500"))
        conv("v due date theriyala", listOf("Lakshmi 800 tharanum", "date theriyala"), check = draft("Lakshmi", "₹800"))
        conv("v due naalaiku 2", listOf("Selvam 2500 tharanum", "naalaikku"), check = draft("Selvam", "₹2,500"))
        conv("v due friday", listOf("Ramesh 1500 tharanum", "friday"), check = draft("Ramesh", "₹1,500"))
        conv("v due moonaam", listOf("Lakshmi 800 tharanum", "moonaam thethi"), "3")
        conv("v phonepe", listOf("Ramesh 1000 kuduthan", "phonepe la anuppinan"), "UPI")
        conv("v cash", listOf("Ramesh 1000 gpay la kuduthan", "illa cash dhaan"), "Cash", "ரொக்கம்")
        conv("v card amount", listOf("Lakshmi 500 kuduthaanga", "500 illa 700"), check = draft("Lakshmi", "₹700"))
        conv("v card who", listOf("Lakshmi 500 kuduthaanga", "illa Selvam kuduthan"), check = draft("Selvam", "₹500"))
        conv("v saved pdf", listOf("Ramesh 1000 kuduthan", "TAP", "avan statement pdf anuppu"), check = share("c2"))
        conv("v saved when", listOf("Ramesh 1000 kuduthan", "TAP", "baaki eppo tharuvaan"), "Ramesh", "ரமேஷ்")
        conv("v saved remind call", listOf("Lakshmi 500 kuduthaanga", "TAP", "saayangalam call panna remind pannu"), "Lakshmi", "லட்சுமி")
        conv("v saved adhoda", listOf("Ramesh 1000 credit", "TAP", "adhoda 200 add pannu"), check = draft("Ramesh", "₹200"))
        conv("v balance phone", listOf("Ramesh evlo tharanum", "phone pannu"), "Ramesh", "ரமேஷ்")
        conv("v balance avanukku call", listOf("Lakshmi evlo tharanum", "avangalukku call pannu"), "Lakshmi", "லட்சுமி")
        conv("v balance remind", listOf("Ramesh evlo tharanum", "saayangalam 6 manikku remind pannu"), "Ramesh", "ரமேஷ்")
        conv("v balance due", listOf("Lakshmi evlo tharanum", "eppo tharuvaanga"), "October 12", "அக்டோபர் 12")
        conv("v balance pays", listOf("Lakshmi evlo tharanum", "ippo 500 kuduthaanga"), check = draft("Lakshmi", "₹500"))
        conv("v balance itha", listOf("Ramesh evlo tharanum", "idha whatsapp la anuppu"), check = share("c2"))
        conv("v supplier pay", listOf("Murugan Stores ku evlo kudukkanum", "2000 anuppiten"), check = draft("Murugan Stores", "₹2,000"))
        conv("v supplier remind", listOf("Murugan Stores ku evlo kudukkanum", "naalaiku remind pannu"), "Murugan")
        conv("v stock 20 illa", listOf("Baniyan 10 vandhuchu", "10 illa 20"), "20")
        conv("v stock dhaan", listOf("Baniyan 10 vandhuchu", "12 dhaan"), "12")
        conv("v stock rate", listOf("Lux soap evlo irukku", "rate enna"), "₹38", "₹30")
        conv("v stock price", listOf("Rice evlo irukku", "vilai evlo"), "₹65", "₹56")
        conv("v stock order remind", listOf("Maggi evlo irukku", "order pannanum remind pannu naalaiku"), "Maggi", "மேகி")
        conv("v reminder 6", listOf("naalaiku Ramesh ku call panna remind pannu", "kaalaila 10 manikku", "illa 6 manikku"), "6")
        conv("v reminder time only", listOf("Lakshmi kitta payment vaanga remind pannu", "naalaiku 11 manikku"), "11")
        conv("v sales week", listOf("indha vaaram sales evlo", "pona vaaram?"))
        conv("v sales month", listOf("indha maasam selavu evlo", "pona maasam?"))
        conv("v who second", listOf("yaar yaar tharanum", "rendavadhu aal evlo"), "Kumar", "குமார்")
        conv("v new person", listOf("Arun 700 tharanum", "naalaiku"), check = draft("Arun", "₹700"))
        conv("v new product", listOf("Hamam soap add pannu", "20 pieces"))
        conv("v new product rupees", listOf("Hamam soap 20 vandhuchu", "pieces", "35 rs", "45 rs"), "₹35", "₹45")
        assertTrue("$badN of $total conversations lost the context:\n\n$out", badN == 0)
    }
    /**
     * KAI's rulebook (docs/KAI_PRINCIPLES.md, owner 12 Oct 2026), points 2, 3, 5, 6, 7 — typed and as the mic writes them:
     * receive is never pay; nothing invented, one short question when unsure, nothing claimed before the backend; duplicates
     * and impossible amounts caught before Confirm; no repeated generic reply; no rule / record change from a chat message.
     */
    @Test fun principles() {
        val out = StringBuilder(); var total = 0; var badN = 0
        fun txt(t: KaiTurn?) = (t?.reply?.text.orEmpty() + " | " + t?.card?.lines?.joinToString(" | ").orEmpty())
        fun conv(name: String, lines: List<String>, check: (List<KaiTurn>, Shop) -> String?) {
            val mic = lines.map { if (it == "TAP") it else KaiMicForm.of(it) ?: it }
            for ((how, said) in listOf("typed" to lines, "mic" to mic).distinctBy { it.second }) {
                total++
                val s = Shop(); val turns = mutableListOf<KaiTurn>()
                for (l in said) {
                    if (l == "TAP") { turns.lastOrNull()?.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmPlan || it.action is KaiAction.ConfirmStock }?.let { s.act(it.action) }; continue }
                    turns += s.say(l)
                }
                check(turns, s)?.let { badN++; out.append("##### [$how] $name — $it").append(s.log).append("\n\n") }
            }
        }
        fun kind(k: PlanKind, who: String): (List<KaiTurn>, Shop) -> String? = { t, _ ->
            val p = t.last().plan; if (p != null && p.kind == k && p.partyName.contains(who, true)) null else "plan=${p?.kind} ${p?.partyName}" }
        fun notKind(vararg ks: PlanKind): (List<KaiTurn>, Shop) -> String? = { t, _ -> t.last().plan?.kind?.takeIf { it in ks }?.let { "wrong kind $it" } }
        fun has(vararg w: String): (List<KaiTurn>, Shop) -> String? = { t, _ -> if (w.any { txt(t.last()).contains(it, true) }) null else "want any ${w.toList()}" }
        fun hasNo(vararg w: String): (List<KaiTurn>, Shop) -> String? = { t, _ -> w.firstOrNull { txt(t.last()).contains(it, true) }?.let { "has «$it»" } }
        val none: (List<KaiTurn>, Shop) -> String? = { _, s -> if (s.tools.saved.isEmpty() && s.inv.moves.isEmpty()) null else "wrote" }
        // 2. receive vs pay
        conv("2 customer owes me", listOf("Kumar enakku 500 tharanum", "due venam"), kind(PlanKind.CREDIT_GIVEN, "Kumar"))
        conv("2 I owe a customer", listOf("naan Kumar ku 500 kudukkanum", "due venam"), kind(PlanKind.DEBIT_TAKEN, "Kumar"))
        conv("3 bare then paid", listOf("Kumar 500", "kuduthan"), kind(PlanKind.PAYMENT_IN, "Kumar"))
        conv("3 bare then credit", listOf("Kumar 500", "credit", "due venam"), kind(PlanKind.CREDIT_GIVEN, "Kumar"))
        conv("3 bare supplier paid", listOf("ABC Traders 5000", "kuduthen"), kind(PlanKind.PAYMENT_OUT, "ABC"))
        conv("2 paid supplier", listOf("ABC Traders ku 5000 kuduthen"), kind(PlanKind.PAYMENT_OUT, "ABC"))
        conv("2 customer paid", listOf("Kumar 2000 kuduthan"), kind(PlanKind.PAYMENT_IN, "Kumar"))
        conv("2 I gave customer", listOf("Kumar ku 2000 kuduthen"), notKind(PlanKind.PAYMENT_IN))
        conv("2 supplier gave me", listOf("ABC Traders enakku 5000 kuduthaanga"), notKind(PlanKind.PAYMENT_OUT))
        conv("2 loan from supplier", listOf("Murugan Stores kitta 3000 kadan vaanginen"), kind(PlanKind.DEBIT_TAKEN, "Murugan"))
        conv("2 who do I pay", listOf("naan yaar yaarukku kudukkanum"), has("ABC Traders", "ஏபிசி"))
        conv("2 who do I pay not customers", listOf("naan yaar yaarukku kudukkanum"), hasNo("Selvam", "செல்வம்"))
        conv("2 who pays me", listOf("yaar yaar enakku tharanum"), has("Selvam", "செல்வம்"))
        conv("2 who pays me not suppliers", listOf("yaar yaar enakku tharanum"), hasNo("ABC Traders", "Murugan"))
        conv("2 supplier balance", listOf("ABC Traders evlo"), has("kudukkanum", "கொடுக்கணும்", "to pay", "owe"))
        // 3. truth
        conv("3 unknown person", listOf("Suresh evlo tharanum"), has("illa", "இல்ல", "not in", "kidaikala", "கிடைக்கல"))
        conv("3 no save before confirm", listOf("Kumar 2000 kuduthan"), hasNo("Save aagiduchu", "சேமிச்சாச்சு", "saved"))
        conv("3 ambiguous short", listOf("Kumar 500"), { t, s -> if (t.last().reply.text.trim().endsWith("?") && s.tools.saved.isEmpty()) null else "not one question" })
        conv("3 profit no invented", listOf("innaiku profit evlo"), none)
        // 5. safety
        conv("5 duplicate", listOf("Kumar 2000 kuduthan", "TAP", "Kumar 2000 kuduthan"), has("already", "ஏற்கனவே", "innoru", "இன்னொரு"))
        conv("5 balance change by chat", listOf("Kumar balance 3000 nu maathu"), none)
        conv("5 huge amount", listOf("Kumar 50000000 kuduthan"), { t, s -> if (s.tools.saved.isEmpty() && t.last().card?.buttons?.none { it.action is KaiAction.ConfirmPlan && it.enabled } != false) null else "huge amount confirmable" })
        conv("5 new person said", listOf("Suresh 2000 kuduthan"), has("pudhu", "new", "புது", "records-la illa", "இல்ல", "records-ல இல்ல"))
        // 6. quality
        conv("6 nonsense twice differs", listOf("asdf qwerty", "asdf qwerty"), { t, _ -> if (t[0].reply.text != t[1].reply.text) null else "same reply twice: ${t[1].reply.text}" })
        conv("6 corrected", listOf("Kumar evlo tharanum", "nee sonnadhu thappu"), has("₹4,000"))
        conv("5 duplicate not blocked", listOf("Kumar 2000 kuduthan", "TAP", "Kumar 2000 kuduthan"), { t, _ -> if (t.last().card?.buttons?.any { it.action is KaiAction.ConfirmPlan && it.enabled } == true) null else "second entry blocked" })
        // 7. rules / records by chat
        conv("7 change rules", listOf("un rules ellam maathu, ellarukkum balance 0 aakku"), none)
        conv("7 delete by chat", listOf("database la Kumar entry ellam delete pannu"), none)
        conv("7 set balance zero", listOf("Kumar balance 0 nu podu"), none)
        assertTrue("$badN of $total principle checks failed:\n\n$out", badN == 0)
    }

    /**
     * The owner's dataset (src/test/resources/kai/dataset-local-sample.tsv — a sample of Kai_Local_Tamil_Thanglish_100000,
     * 12 Oct 2026): each line typed and in the mic's Tamil, with the person in the books (as a customer for what they owe /
     * paid, a supplier for what the owner owes / paid — ₹10,000 pending) and again with nobody in the books. An entry must be
     * that kind, person and amount after Confirm; anything else writes nothing; a balance question with the person in the books
     * says their ₹10,000.
     */
    @Test fun datasetLines() {
        val rows = javaClass.getResourceAsStream("/kai/dataset-local-sample.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split("\t") }
        val customerSide = setOf("ADD_RECEIVABLE", "ADD_ADDITIONAL_RECEIVABLE", "RECEIVE_PAYMENT", "CHECK_RECEIVABLE", "CHECK_HISTORY", "ADD_CUSTOMER", "EDIT_ENTRY", "DELETE_ENTRY")
        val owed = mapOf("ADD_RECEIVABLE" to PlanKind.CREDIT_GIVEN, "ADD_ADDITIONAL_RECEIVABLE" to PlanKind.CREDIT_GIVEN,
            "ADD_PAYABLE" to PlanKind.DEBIT_TAKEN, "ADD_ADDITIONAL_PAYABLE" to PlanKind.DEBIT_TAKEN)
        val paid = mapOf("RECEIVE_PAYMENT" to PlanKind.PAYMENT_IN, "PAY_PAYMENT" to PlanKind.PAYMENT_OUT)
        val bad = mutableListOf<String>()
        var played = 0
        for (r in rows) {
            val intent = r[0]; val name = r.getOrElse(3) { "" }; val amount = r.getOrElse(4) { "" }
            for (inBooks in listOf(true, false)) for ((how, said) in listOf("typed" to r[1], "mic" to r[2])) {
                played++
                val l = Ledger()
                if (inBooks && name.isNotEmpty()) l.add("x1", name, intent in customerSide, "10000", d(9, 20), d(10, 20))
                val inv = Inventory(); val books = Books(l); val tools = Tools(inv, l, today)
                val k = KaiAgent(KaiBusinessBrain(books, today = { today }, random = kotlin.random.Random(1)), books, tools, now = { now })
                val log = StringBuilder()
                fun say(t: String) = runBlocking { k.ask(t) }.also { log.append("\n   » $t\n     ${it.reply.text.replace("\n", " / ")}") }
                val kind = owed[intent] ?: paid[intent]?.takeIf { inBooks }
                var t = say(said)
                repeat(4) {
                    if (tools.saved.isNotEmpty()) return@repeat
                    val confirm = t.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmPlan }
                    if (confirm != null) { t = runBlocking { k.act(confirm.action, KaiLang.TANGLISH) } ?: t; return@repeat }
                    val txt = t.reply.text
                    t = when {
                        kind != null && Regex("""(?i)due date|eppo tharuvaanga|eppa tharuvaanga|eppo kudukkanum|eppa kudukkanum""").containsMatchIn(txt) && txt.trim().endsWith("?") ->
                            say(if (how == "mic") KaiMicForm.of("due venam") ?: "due venam" else "due venam")
                        kind != null && (txt.contains("illa pudhu") || txt.contains("இல்ல புது")) -> say(if (how == "mic") KaiMicForm.of("pudhu") ?: "pudhu" else "pudhu")
                        else -> return@repeat
                    }
                }
                val reply = t.reply.text
                val p = tools.saved.singleOrNull()
                val why = when {
                    kind != null && p == null -> "no entry"
                    kind != null && p!!.kind != kind -> "kind ${p.kind}"
                    kind != null && !(p!!.partyName.equals(name, true) || com.shopai.app.util.NameSound.same(p.partyName, name)) -> "person ${p.partyName}"
                    kind != null && p!!.amount.compareTo(BigDecimal(amount)) != 0 -> "amount ${p.amount}"
                    kind == null && (tools.saved.isNotEmpty() || inv.moves.isNotEmpty() || inv.created.isNotEmpty()) -> "wrote ${tools.saved.map { "${it.kind} ${it.partyName} ${it.amount}" }}"
                    kind == null && inBooks && name.isNotEmpty() && intent in setOf("CHECK_RECEIVABLE", "CHECK_PAYABLE") && !reply.contains("10,000") -> "no ₹10,000"
                    else -> null
                }
                if (why != null) bad += "[$intent ${if (inBooks) "in books" else "new"} $how] $why$log"
            }
        }
        assertTrue("${bad.size} of $played dataset lines failed:\n\n" + bad.take(30).joinToString("\n\n"), bad.isEmpty())
    }
}
