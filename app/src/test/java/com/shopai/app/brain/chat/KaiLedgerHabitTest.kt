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
 * The owner's own questions about who pays when — and how they pay against their due dates — answered from the
 * ledger's entries and payments (never guessed), plus the chat entries around them saving to the books.
 * "Yeppa", "Innaiku", "Friday", "Nan" are words, never people.
 */
class KaiLedgerHabitTest {
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
        override suspend fun products() = listOf(ProductRef("p1", "Colgate", "PCS", BigDecimal("20")))
        override suspend fun stock(product: String?) = listOf(StockFact("Colgate", BigDecimal("20"), "PCS", reorderAt = BigDecimal("25")))
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

    // ------------------------------------------------------------------ who pays, when

    @Test fun whoPaysTodayThenWhenThenTheirHabit() {
        val s = Shop()
        val today = s.say("Innaiku yar payment tharanum")
        assertTrue(today, today.contains("Kumar") && today.contains("Ramesh") && today.contains("₹6,500"))
        // "Yeppa tharanum" right after: the same people with their dates — "Yeppa" is not a person.
        val whenDue = s.say("Yeppa tharanum")
        assertFalse(whenDue, whenDue.contains("Yeppa"))
        assertTrue(whenDue, whenDue.contains("1. Kumar — ₹4,000 — due innaikku") && whenDue.contains("2. Ramesh — ₹2,500 — due innaikku"))
        val details = s.say("Avnanga details sollu")
        assertTrue(details, details.contains("Kumar") && details.contains("Ramesh"))
        // "avanga" = those two: Kumar paid late both times, Ramesh on time — not the overdue list (Selvam).
        val habit = s.say("Avanga correct date la payment pannuvanagala illa due date thandi late ah payment pannuvangala")
        assertTrue(habit, habit.contains("andha 2 per-oda history"))
        assertTrue(habit, habit.contains("1. Kumar — 2-la 2 thadava late (sarasari 8 naal)"))
        assertTrue(habit, habit.contains("Correct date-la tharuvaanga: Ramesh."))
        assertFalse(habit, habit.contains("Selvam"))
    }

    @Test fun whomTheOwnerPaysAndWhen() {
        val all = Shop().say("Nan yaruku payment tharanum yeppa tharanum")
        assertFalse(all, all.contains("Yeppa") || all.contains("Nan-nu") || all.contains("evlo kudukkanum?"))
        assertTrue(all, all.contains("ABC Traders ₹10,000 — innaikku") && all.contains("Murugan Stores ₹4,000 — October 15th"))
        val today = Shop().say("Innaiku yaruku payment tharanum")
        assertFalse(today, today.contains("Innai kitta") || today.contains("Murugan"))
        assertTrue(today, today.contains("1 per-ukku kudukkanum") && today.contains("ABC Traders ₹10,000"))
        val tomorrow = Shop().say("Naalaiku yaruku payment pannanum")
        assertTrue(tomorrow, tomorrow.contains("yaarukkum kudukka vendiya payment record illa"))
    }

    @Test fun whoUsuallyPaysLate() {
        val text = Shop().say("General ah yarlam late ah payment pannuvanga")
        assertTrue(text, text.contains("Late-aa tharuvaanga (2):"))
        assertTrue(text, text.contains("1. Selvam — 1-la 1 thadava late (19 naal); ippo 18 naal thaandi ₹6,000 pending"))
        assertTrue(text, text.contains("2. Kumar — 2-la 2 thadava late (sarasari 8 naal)"))
        assertTrue(text, text.contains("Correct date-la tharuvaanga: Ramesh, Lakshmi."))
        // Priya's bill has no due date: no habit claimed for her.
        assertTrue(text, text.contains("1 per-ukku innum history illa."))
        assertFalse(text, text.contains("Priya"))
        // Not the overdue list's wording.
        assertFalse(text, text.contains("follow up"))
        assertEquals(text, Shop().say("Yaarellam late ah tharuvanga"))
    }

    @Test fun onePersonsHabitFromTheirEntries() {
        val kumar = Shop().say("Kumar yeppadi pannuvan or pannuvar correct date pannuvara illa late ah payment pannuvara")
        assertEquals("Owner, Kumar usually late-aa dhaan tharuvaanga: 2 thadava-um due date thaandi kuduthaanga " +
            "(10, 6 naal late; sarasari 8 naal). Ippo ₹4,000 — due innaikku.", kumar)
        // A Tanglish question is answered in Tanglish (it was English before, for its "pay").
        val ramesh = Shop().say("Ramesh yeppadi pay pannuvaar?")
        assertTrue(ramesh, ramesh.contains("Ramesh correct date-la dhaan tharuvaanga") && ramesh.contains("2 thadava"))
        val lakshmi = Shop().say("Lakshmi correct date la tharuvangala")
        assertTrue(lakshmi, lakshmi.contains("Lakshmi correct date-la dhaan tharuvaanga") && lakshmi.contains("October 12th"))
        val selvam = Shop().say("Selvam late ah tharuvana")
        assertTrue(selvam, selvam.contains("usually late-aa") && selvam.contains("19 naal late") && selvam.contains("18 naal aachu"))
        val english = Shop().say("Does Kumar pay on time?")
        assertTrue(english, english.contains("Kumar usually pays late") && english.contains("8 days on average"))
        val tamil = Shop().say("குமார் சரியான தேதியில தருவாரா?")
        assertTrue(tamil, tamil.contains("Kumar") && tamil.contains("late") && tamil.contains("சராசரி 8 நாள்"))
        // No settled entry with a due date: Kai says it can't tell — never a guess.
        val priya = Shop().say("Priya correct date la tharuvangala")
        assertTrue(priya, priya.contains("solla mudiyadhu"))
        assertFalse(priya, priya.contains("late-aa dhaan") || priya.contains("correct date-la dhaan"))
    }

    @Test fun aSingleAvarFollowsThePersonTalkedAbout() {
        val s = Shop()
        s.say("Kumar evlo tharanum?")
        val text = s.say("avar late ah tharuvara")
        assertTrue(text, text.startsWith("Owner, Kumar usually late-aa"))
        assertTrue(Shop().say("avar late ah tharuvara").contains("yaar pathi"))
    }

    @Test fun habitWordsNeverBecomePeopleOrDraftsAndAStatementStaysAStatement() {
        val s = Shop()
        val text = s.say("Kumar late ah 3000 tharuvaan")
        // An amount said: a payment being stated, not a habit question — and "Late" is not part of Kumar's name.
        assertFalse(text, text.contains("usually") || text.contains("Kumar Late"))
        assertTrue(s.tools.saved.isEmpty())
    }

    // ------------------------------------------------------------------ entries reach the books

    @Test fun aPayDateSaidToAnOpenCreditDraftIsItsDueDate() {
        val s = Shop()
        s.say("Ramesh-ku 3000 credit kuduthen")
        val draft = s.turn("next friday tharuvaan")
        assertFalse(draft.reply.text, draft.reply.text.contains("Friday kitta") || draft.reply.text.contains("evlo vaanganum"))
        assertTrue(draft.card!!.lines.joinToString(" | "), draft.card!!.lines.any { it == "Due date: naalaikku" })
        assertTrue("nothing saved before Confirm", s.tools.saved.isEmpty())
        val saved = s.say("aama")
        assertTrue(saved, saved.startsWith("Save aagiduchu") && saved.contains("Ippo balance ₹5,500"))
        assertEquals(PlanKind.CREDIT_GIVEN, s.tools.saved.single().kind)
        assertEquals(d(10, 9), s.tools.saved.single().dueDate)
        assertTrue(s.say("Ramesh evlo tharanum?").contains("₹5,500"))

        val t = Shop()
        t.say("Selvam-ku 2000 credit kuduthen")
        t.say("15th tharuvaan")
        t.say("aama")
        assertEquals(d(10, 15), t.tools.saved.single().dueDate)
    }

    @Test fun paymentsInAndOutSaveAndReadBack() {
        val s = Shop()
        s.say("Kumar 2000 kuduthan")
        assertTrue(s.say("aama").contains("Ippo balance ₹2,000"))
        assertEquals(PlanKind.PAYMENT_IN, s.tools.saved.single().kind)
        assertTrue(s.say("Kumar evlo tharanum?").contains("₹2,000"))

        val o = Shop()
        o.say("ABC Traders-ku 5000 kuduthen")
        assertTrue(o.say("aama").contains("Ippo balance ₹5,000"))
        assertEquals(PlanKind.PAYMENT_OUT, o.tools.saved.single().kind)
        assertTrue(o.say("ABC Traders-ku evlo tharanum?").contains("₹5,000 kudukkanum"))
    }

    @Test fun aCallReminderIsConfirmedAndNeverNamesAClockWord() {
        val s = Shop()
        val ask = s.say("naalaiku 10 maniku Kumar-ku call panna remind pannu")
        assertTrue(ask, ask.contains("Kumar") && ask.contains("10:00 AM"))
        assertTrue("not set before Confirm", s.tools.rems.isEmpty())
        val done = s.say("aama")
        assertTrue(done, done.startsWith("Done Owner"))
        assertEquals(1, s.tools.rems.size)

        val t = Shop()
        t.say("Kumar 1000 kuduthan")
        val noName = t.say("naalaiku 10 maniku call remind pannu")
        assertFalse(noName, noName.contains("Maniku"))
    }

}
