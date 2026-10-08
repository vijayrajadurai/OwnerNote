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
 * Kai answers from the COMPLETE ledger: every entry and every payment, aggregated in plain code, read fresh after a
 * save, kept as a structured list for follow-ups ("details sollu", "avanga total evlo?"), never from the last chat line.
 *
 * The fake ledger here is entry-level, like the books: each party has entries (amount, bill date, due date) and payments
 * against them; a party's balance is the sum of what is still open, and its summary date is the earliest open due date
 * (or the bill date when an entry has none) — the same as the app's party summaries. Kai's tools write to it only on
 * Confirm. Today is Thursday 8 October 2026.
 */
class KaiCompleteLedgerQueryTest {

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
        override suspend fun contacts(name: String, role: PartyRole?) = emptyList<ContactMatch>()
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?) = "K-1"
        override suspend fun products() = listOf(ProductRef("p1", "Colgate", "PCS", BigDecimal("20")))
        override suspend fun stock(product: String?) = listOf(StockFact("Colgate", BigDecimal("20"), "PCS", reorderAt = BigDecimal("25")))
    }

    /**
     * Customers: Kumar ₹5,000 (paid ₹2,000) due today · Selvi ₹4,200 due today · Lokesh (Chennai) ₹2,000 due 20 Oct ·
     * Lokesh (Nagapattinam) ₹700 due 15 Oct · Ravi ₹1,500 due 25 Sep · Mani ₹4,773.10 due 1 Oct · Priya ₹800 with no due
     * date (billed 20 Sep) · Arun ₹1,000 fully paid on 5 Oct.
     * Suppliers: Ramesh ₹2,500 due today · Basha ₹9,000 (paid ₹1,000 on 6 Oct) due 30 Sep.
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

    private inner class Shop(val l: Ledger = ledger(), cached: Boolean = false) {
        val books = Books(l, cached)
        val tools = Tools(l, today)
        fun kai() = KaiAgent(KaiBusinessBrain(books, today = { today }, random = Random(1)), books, tools, now = { now })
        var k = kai()
        fun turn(text: String): KaiTurn = runBlocking { k.ask(text) }
        fun say(text: String): String = turn(text).reply.text
        fun chat(vararg lines: String): String = lines.map { say(it) }.last()
        fun restart() { k = kai() }
    }

    private fun has(text: String, vararg parts: String) = parts.forEach { assertTrue("'$it' in: $text", text.contains(it)) }
    private fun hasNot(text: String, vararg parts: String) = parts.forEach { assertFalse("'$it' must not be in: $text", text.contains(it)) }
    private val saveClaims = arrayOf("Save aagiduchu", "save pannitten", "add pannitten", "record pannitten", "Balance updated")

    // ========================================================== A. person balance from every entry and payment

    @Test
    fun a01_balanceIsEveryOpenEntryMinusPayments() {
        // Kumar: ₹5,000 billed, ₹2,000 paid.
        assertEquals("Kumar ungalukku ₹3,000 tharanum owner.", Shop().say("Kumar enakku evlo tharanum?"))
    }

    @Test
    fun a02_existingPlusNewEntryIsTheSumAfterConfirm() {
        val s = Shop()
        s.chat("Lokesh Chennai enakku 500 tharanum", "save panniko")
        has(s.say("seri"), "Save aagiduchu", "₹2,500")
        assertEquals(2, s.l.party("c2").entries.size)
        has(s.say("Chennai Lokesh enakku evlo tharanum?"), "₹2,500")
    }

    @Test
    fun a03_aPaymentReducesTheBalance_2000Plus500Minus1000Is1500() {
        val s = Shop()
        s.chat("Lokesh Chennai enakku 500 tharanum", "save panniko", "seri")
        val draft = s.turn("Chennai Lokesh 1000 kuduthutaan")
        has(draft.card!!.lines.joinToString(), "₹1,000")
        assertEquals(1, s.tools.saved.size)
        has(s.say("seri"), "Save aagiduchu", "₹1,500")
        has(s.say("Chennai Lokesh enakku evlo tharanum?"), "₹1,500")
    }

    @Test
    fun a04_multipleNewEntriesAllCount() {
        val s = Shop()
        s.chat("Selvi enakku 300 tharanum", "save panniko", "seri")
        s.chat("Selvi enakku 200 tharanum", "save panniko", "seri")
        has(s.say("Selvi enakku evlo tharanum?"), "₹4,700")
        assertEquals(3, s.l.party("c3").entries.size)
    }

    @Test
    fun a05_payableSideIsTheSuppliersBooks() {
        val s = Shop()
        has(s.say("naan Basha-ku evlo tharanum?"), "Basha", "₹8,000")
        has(s.say("Ramesh balance evlo?"), "Ramesh", "₹2,500")
    }

    @Test
    fun a06_receivableAskedOfASupplierOnlyIsNothingOnThatSide() {
        assertEquals("Owner, Basha ungalukku tharanum-nu pending amount illa.", Shop().say("Basha enakku evlo tharanum?"))
    }

    @Test
    fun a07_paidTotalAndOutstandingAreThreeDifferentFigures() {
        val s = Shop()
        has(s.say("Kumar evlo kuduthirukkaan?"), "₹2,000")
        has(s.say("Kumar total transaction evlo?"), "mothama ₹5,000", "₹2,000", "pending ₹3,000")
        has(s.say("Kumar enakku evlo tharanum?"), "₹3,000")
    }

    @Test
    fun a08_paymentHistoryListsTheRealEntriesAndPayments() {
        val t = Shop().say("Kumar payment history sollu")
        has(t, "1. September 1st — ₹5,000 (paid ₹2,000)", "Payments: October 3rd ₹2,000")
    }

    @Test
    fun a09_supplierPurchasesAndWhatTheOwnerPaid() {
        val s = Shop()
        has(s.say("Basha kitta naan evlo vanginen?"), "mothama ₹9,000 vaangirukkeenga", "kuduthadhu ₹1,000", "₹8,000 kudukkanum")
        assertEquals("Owner, neenga Basha-ku ₹1,000 kuduthirukkeenga. Innum ₹8,000 kudukkanum.", s.say("Basha-ku naan evlo kuduthen?"))
    }

    @Test
    fun a10_aFullyPaidCustomerIsClear() {
        has(Shop().say("Arun enakku evlo tharanum?"), "Arun", "pending")
        hasNot(Shop().say("Arun enakku evlo tharanum?"), "₹1,000 tharanum")
    }

    // ========================================================== the Lokesh ₹2,000 vs ₹2,500 root cause: a cached ledger

    @Test
    fun cache01_aSaveDropsTheCachedLedgerSoTheNextAnswerIsTheNewBalance() {
        val s = Shop(cached = true)
        // The question before the save fills the cache with ₹2,000.
        has(s.say("Chennai Lokesh enakku evlo tharanum?"), "₹2,000")
        s.chat("Lokesh Chennai enakku 500 tharanum", "save panniko")
        has(s.say("seri"), "Save aagiduchu")
        assertTrue("Kai told the books something changed", s.books.changes >= 1)
        has(s.say("lokesh chennai enakku evlo tharanum"), "₹2,500")
    }

    @Test
    fun cache02_aPaymentSavedThroughKaiIsReadFreshToo() {
        val s = Shop(cached = true)
        has(s.say("Kumar enakku evlo tharanum?"), "₹3,000")
        s.chat("Kumar 1000 kuduthutaan", "seri")
        has(s.say("Kumar enakku evlo tharanum?"), "₹2,000")
    }

    // ========================================================== B. a draft is never ledger truth

    @Test
    fun b01_beforeConfirmTheSavedBalanceAnswers_theDraftIsOnlyNoted() {
        val s = Shop()
        s.chat("Kumar enakku 500 tharanum", "save panniko")
        val t = s.say("Kumar enakku evlo tharanum?")
        has(t, "Kumar ungalukku ₹3,000 tharanum", "₹500 innum save aagala")
        assertTrue(s.tools.saved.isEmpty())
    }

    @Test
    fun b02_confirmCountsItOnce() {
        val s = Shop()
        s.chat("Kumar enakku 500 tharanum", "save panniko", "seri")
        s.say("seri")
        s.say("save panniko")
        assertEquals(1, s.tools.saved.size)
        has(s.say("Kumar enakku evlo tharanum?"), "₹3,500")
    }

    @Test
    fun b03_cancelLeavesTheLedgerAsItWas() {
        val s = Shop()
        s.chat("Kumar enakku 5000 tharanum", "save panniko")
        has(s.say("cancel"), "Edhuvum save aagala")
        assertEquals("Kumar ungalukku ₹3,000 tharanum owner.", s.say("Kumar enakku evlo tharanum?"))
        assertTrue(s.tools.saved.isEmpty())
    }

    @Test
    fun b04_anEditedDraftSavesOnlyTheFinalAmount() {
        val s = Shop()
        s.chat("Chennai Lokesh enakku 500 tharanum", "save panniko", "700")
        has(s.say("seri"), "Save aagiduchu", "₹700")
        assertEquals(listOf(BigDecimal("700.00")), s.tools.saved.map { it.amount })
        has(s.say("Chennai Lokesh enakku evlo tharanum?"), "₹2,700")
    }

    @Test
    fun b05_confirmSaidToAStatedPaymentShowsTheCardFirst() {
        val s = Shop()
        s.say("Chennai Lokesh-ku 500 collect pannu")
        val card = s.turn("confirm")
        has(card.card!!.lines.joinToString(), "₹500", "₹2,000")
        assertTrue(s.tools.saved.isEmpty())
        has(s.say("confirm"), "Save aagiduchu", "₹2,500")
    }

    // ========================================================== persistence: read back before "saved"

    @Test
    fun p01_theBooksDoNotShowTheEntry_noSaveClaim() {
        val s = Shop()
        s.books.frozen = true
        s.say("Kumar enakku 500 tharanum")
        s.say("save panniko")
        val t = s.say("seri")
        hasNot(t, *saveClaims)
        has(t, "check panna mudiyala", "thirumba save pannaadheenga")
        // Nothing claims it on "add pannitiya?" either.
        hasNot(s.say("add pannitiya?"), "add pannitten")
    }

    @Test
    fun p02_aFailedSaveIsSaidAsFailed() {
        val s = Shop()
        s.tools.fail = "disk full"
        s.chat("Kumar enakku 500 tharanum", "save panniko")
        val t = s.say("seri")
        has(t, "save aagala")
        hasNot(t, *saveClaims)
    }

    @Test
    fun p03_restartReadsTheSameLedger() {
        val s = Shop()
        s.chat("Kumar enakku 2000 tharanum", "save panniko", "seri")
        s.restart()
        assertEquals("Kumar ungalukku ₹5,000 tharanum owner.", s.say("Kumar enakku evlo tharanum?"))
    }

    @Test
    fun p04_theDueDateIsSavedAndAnsweredBack() {
        val s = Shop()
        s.chat("Priya enakku 500 tharanum next month 10-ku", "save panniko", "seri")
        assertEquals(LocalDate.of(2026, 11, 10), s.tools.saved.single().dueDate)
        s.restart()
        has(s.say("Priya payment history sollu"), "₹500")
        assertEquals(LocalDate.of(2026, 11, 10), s.l.party("c5").entries.last().due)
    }

    @Test
    fun p05_dueVenamSavesWithNoDueDate_neverClearsOrPays() {
        val s = Shop()
        s.chat("Selvi enakku 500 tharanum", "due venam")
        has(s.say("seri"), "Save aagiduchu")
        assertNull(s.tools.saved.single().dueDate)
        has(s.say("Selvi enakku evlo tharanum?"), "₹4,700")
    }

    @Test
    fun p06_aChatPayableEntryShowsInThePayableList() {
        val s = Shop()
        s.chat("naan Ramesh-ku 500 tharanum", "save panniko", "seri")
        has(s.say("ellaa payable list pannu"), "Ramesh — ₹3,000")
    }

    @Test
    fun p07_aNewPartySavedByChatIsInTheBooks() {
        val s = Shop()
        s.chat("Muthu enakku 1200 tharanum", "save panniko")
        has(s.say("seri"), "Save aagiduchu")
        has(s.say("Muthu enakku evlo tharanum?"), "₹1,200")
        has(s.say("ellaa pending customers list pannu"), "Muthu — ₹1,200")
    }

    // ========================================================== C. today

    @Test
    fun c01_todayCountAndTotalFromTheWholeLedger() {
        val t = Shop().say("Innaikku evlo per payment tharanum?")
        has(t, "Innaikku 2 per tharanum", "₹7,200", "Selvi ₹4,200", "Kumar ₹3,000")
    }

    @Test
    fun c02_todayDetailsAreEveryRecord() {
        val s = Shop()
        s.say("Innaikku evlo per payment tharanum?")
        assertEquals("Owner, Innaikku 2 per tharanum — mothama ₹7,200:\n1. Selvi — ₹4,200 — due innaikku\n2. Kumar — ₹3,000 — due innaikku", s.say("details sollu"))
    }

    @Test
    fun c03_nothingDueToday() {
        val l = Ledger().apply { add("c1", "Kumar", true, "1000", d(9, 1), d(10, 20)) }
        has(Shop(l).say("Innaikku evlo per payment tharanum?"), "yaarum tharavendiyadhu illa")
    }

    @Test
    fun c04_todaysPaymentsToSuppliers() {
        val s = Shop()
        has(s.say("innaikku yaarukku payment pannanum?"), "Innaikku 1 per-ukku kudukkanum", "₹2,500", "Ramesh")
        has(s.say("details sollu"), "1. Ramesh — ₹2,500 — due innaikku")
    }

    @Test
    fun c05_todaysCollectAndPayTotals() {
        val s = Shop()
        has(s.say("innaikku collect panna vendiya total evlo?"), "₹7,200")
        has(s.say("innaikku pay panna vendiya total evlo?"), "₹2,500")
    }

    // ========================================================== D. overdue

    @Test
    fun d01_overdueCountTotalAndDays() {
        val s = Shop()
        has(s.say("Yaar yaar payment due date thandi pochu?"), "3 payment date thaandiduchu", "Ravi ₹1,500", "Mani ₹4,773.10")
        val details = s.say("avanga details sollu")
        has(details, "3 per-oda due date thaandiduchu — mothama ₹7,073.10", "Ravi — ₹1,500 — due September 25th — 13 naal thaandiduchu",
            "Mani — ₹4,773.10 — due October 1st — 7 naal thaandiduchu")
    }

    @Test
    fun d02_anEntryWithNoDueDateIsShownSo_neverGivenADate() {
        val s = Shop()
        s.say("Yaar yaar payment due date thandi pochu?")
        has(s.say("avanga details sollu"), "Priya — ₹800 — Due date illa")
    }

    @Test
    fun d03_nothingOverdue() {
        val l = Ledger().apply { add("c1", "Kumar", true, "1000", d(9, 1), d(10, 20)) }
        val t = Shop(l).say("yaaroda due date poiduchu?")
        has(t, "edhuvum illa")
        hasNot(t, "₹", "Kumar")
    }

    @Test
    fun d04_overduePayables() {
        val t = Shop().say("naan kudukka vendiyadhu yaarukku due date thaandiduchu?")
        has(t, "Basha — ₹8,000 — due September 30th — 8 naal thaandiduchu")
        hasNot(t, "Ravi", "Mani")
    }

    @Test
    fun d05_todayAndOverdueAreTwoSeparateLists() {
        val t = Shop().say("today due + overdue rendu list-um sollu")
        val (todayPart, overduePart) = t.split("\n\n")
        has(todayPart, "Innaikku 2 per tharanum", "Selvi", "Kumar")
        hasNot(todayPart, "Ravi", "Mani")
        has(overduePart, "3 per-oda due date thaandiduchu", "Ravi", "Mani", "Priya")
        hasNot(overduePart, "Selvi")
    }

    // ========================================================== E. follow-ups on the same records

    @Test
    fun e01_tamilTwelvePeoplesDetailsMeansTheLastList() {
        val s = Shop()
        s.say("Innaikku evlo per payment tharanum?")
        val t = s.say("12 பேருடைய details சொல்லு")
        has(t, "இன்னைக்கு 2 பேர் தரணும்", "1. Selvi — ₹4,200", "2. Kumar — ₹3,000")
        hasNot(t, "யார் பத்தி", "yaar pathi")
    }

    @Test
    fun e02_avangaYaarAndTotal() {
        val s = Shop()
        s.say("Innaikku yaar payment tharanum?")
        has(s.say("avanga yaar yaar?"), "1. Selvi", "2. Kumar")
        assertEquals("Owner, andha 2 per mothama ₹7,200.", s.say("avanga total evlo?"))
    }

    @Test
    fun e03_dueDateTooMeansTheSameRecords() {
        val s = Shop()
        s.say("Yaar yaar payment due date thandi pochu?")
        val t = s.say("due date-um sollu")
        has(t, "Ravi — ₹1,500 — due September 25th", "Mani — ₹4,773.10 — due October 1st")
    }

    @Test
    fun e04_theListIsReReadSoAPaymentSinceShows() {
        val s = Shop()
        s.say("Innaikku evlo per payment tharanum?")
        s.chat("Kumar 1000 kuduthutaan", "seri")
        has(s.say("ellaaroda details sollu"), "Kumar — ₹2,000", "₹6,200")
    }

    @Test
    fun e05_aNewScopeIsAFreshQuestionNotTheOldList() {
        val s = Shop()
        s.say("Innaikku evlo per payment tharanum?")
        has(s.say("overdue customers details sollu"), "3 per-oda due date thaandiduchu")
        has(s.say("ellaa payable list pannu"), "Basha", "Ramesh")
    }

    @Test
    fun e06_detailsWithNoListAsksWhoAndInventsNothing() {
        val t = Shop().say("details sollu")
        hasNot(t, "₹")
    }

    // ========================================================== F. entity resolution

    @Test
    fun f01_twoLokeshesAreAskedWithTheirPlaces() {
        val t = Shop().say("Lokesh enakku evlo tharanum?")
        has(t, "rendu", "Chennai Lokesh — ₹2,000", "Nagapattinam Lokesh — ₹700")
    }

    @Test
    fun f02_thePlaceAnswersWhichOne() {
        val s = Shop()
        s.say("Lokesh enakku evlo tharanum?")
        assertEquals("Nagapattinam Lokesh ungalukku ₹700 tharanum owner.", s.say("Nagapattinam"))
    }

    @Test
    fun f03_anOrdinalAnswersWhichOne() {
        val s = Shop()
        s.say("Lokesh enakku evlo tharanum?")
        has(s.say("rendavadhu"), "₹700")
    }

    @Test
    fun f04_thePlaceInTheQuestionPicksExactly() {
        val s = Shop()
        has(s.say("Lokesh Nagapattinam enakku evlo tharanum?"), "Nagapattinam Lokesh", "₹700")
        has(s.say("Chennai Lokesh balance evlo?"), "Chennai Lokesh", "₹2,000")
    }

    @Test
    fun f05_theOneInTheConversationIsUsed_neverTheFirstMatch() {
        val s = Shop()
        s.say("Lokesh Nagapattinam enakku evlo tharanum?")
        has(s.say("Lokesh due eppa?"), "₹700")
        hasNot(s.say("Lokesh enakku evlo tharanum?"), "₹2,000")
    }

    // ========================================================== G. topic switches keep the list

    @Test
    fun g01_stockInBetween() {
        val s = Shop()
        s.say("Innaikku yaar payment tharanum?")
        has(s.say("Colgate stock evlo?"), "Colgate")
        has(s.say("avanga details sollu"), "1. Selvi — ₹4,200", "2. Kumar — ₹3,000")
    }

    @Test
    fun g02_calculatorInBetween() {
        val s = Shop()
        s.say("Innaikku yaar payment tharanum?")
        assertEquals("17", s.say("12 + 5 evlo?"))
        has(s.say("avanga details sollu"), "1. Selvi", "2. Kumar")
    }

    @Test
    fun g03_casualTalkInBetween() {
        val s = Shop()
        s.say("Yaar yaar payment due date thandi pochu?")
        s.say("saptiya?")
        has(s.say("details sollu"), "Ravi — ₹1,500", "Mani — ₹4,773.10")
    }

    @Test
    fun g04_aPersonQuestionInBetweenMakesBareDetailsAboutThatPerson_ellaaStillMeansTheList() {
        val s = Shop()
        s.say("Innaikku yaar payment tharanum?")
        s.say("Kumar enakku evlo tharanum?")
        has(s.say("details sollu"), "Kumar", "September 1st — ₹5,000")
        has(s.say("ellaaroda details sollu"), "1. Selvi", "2. Kumar")
    }

    // ========================================================== H–L. languages and STT variants

    @Test
    fun h01_tamilToday() {
        val s = Shop()
        has(s.say("இன்னைக்கு யார் யார் பணம் தரணும்?"), "2 பேர் தரணும்", "₹7,200")
        has(s.say("அவங்க விவரம் சொல்லு"), "1. Selvi — ₹4,200", "இன்னைக்கு due")
    }

    @Test
    fun i01_tanglishMixed() {
        has(Shop().say("innaikku evlo per payment tharanum"), "2 per tharanum", "₹7,200")
    }

    @Test
    fun j01_englishWhoPaysMeToday_andTheirDetails() {
        val s = Shop()
        has(s.say("Who has to pay me today?"), "2 to collect", "₹7,200")
        has(s.say("show their details"), "1. Selvi — ₹4,200 — due today")
    }

    @Test
    fun j02_englishOverdue() {
        has(Shop().say("Which payments are overdue?"), "3 collection(s) are overdue", "Ravi ₹1,500")
    }

    @Test
    fun k01_mixedScriptAndCase() {
        has(Shop().say("kumar balance evlo"), "₹3,000")
        has(Shop().say("selvi kita evlo pending"), "₹4,200")
    }

    @Test
    fun l01_sttTypos() {
        has(Shop().say("kumaar enaku evlo tharanum"), "₹3,000")
        has(Shop().say("Kumar enaku evlo tharnum?"), "₹3,000")
    }

    // ========================================================== M. the ledger query classes

    @Test
    fun m01_whoDoIPay() {
        has(Shop().say("yaarukku naan cash kudukkanum?"), "₹10,500", "Basha ₹8,000", "Ramesh ₹2,500")
    }

    @Test
    fun m02_totalsBothSides() {
        has(Shop().say("total collection evlo pending?"), "₹16,973.10", "₹10,500")
        has(Shop().say("total payable evlo?"), "₹10,500")
    }

    @Test
    fun m03_paymentsInTheLast7Days() {
        val t = Shop().say("last 7 days la yaar yaar payment pannanga?")
        has(t, "Kadandha 7 naal-la 2 per payment pannanga — mothama ₹3,000", "Arun — ₹1,000 — October 5th", "Kumar — ₹2,000 — October 3rd")
    }

    @Test
    fun m04_lastMonthCollectionsFromTheRecords() {
        has(Shop().say("last month collection evlo?"), "Pona maasam yaarum payment pannadhu record-la illa")
    }

    @Test
    fun m05_highestAndLowestPending() {
        assertEquals("Owner, adhigama pending Mani kitta — ₹4,773.10 — October 1st.", Shop().say("highest pending amount yaar kitta?"))
        // Two Lokeshes in the books: the line says which one.
        assertEquals("Owner, kammiya pending Nagapattinam Lokesh kitta — ₹700 — October 15th.", Shop().say("smallest pending amount yaar kitta?"))
    }

    @Test
    fun m06_everyPendingCustomer() {
        val t = Shop().say("ellaa pending customers list pannu")
        has(t, "7 per tharanum — mothama ₹16,973.10")
        for (n in listOf("Priya", "Ravi", "Mani", "Selvi", "Kumar", "Nagapattinam Lokesh — ₹700", "Chennai Lokesh — ₹2,000")) has(t, n)
        hasNot(t, "Arun", "Basha", "Ramesh")
    }

    @Test
    fun m07_everyPayable() {
        val t = Shop().say("ellaa payable list pannu")
        has(t, "neenga 2 per-ukku kudukkanum — mothama ₹10,500", "Basha — ₹8,000", "Ramesh — ₹2,500")
        hasNot(t, "Kumar")
    }

    @Test
    fun m08_pendingWithNoDueDate() {
        assertEquals("Owner, due date illaama 1 per tharanum — mothama ₹800:\n1. Priya — ₹800 — Due date illa",
            Shop().say("due date illama pending irukkuravanga yaar?"))
    }

    @Test
    fun m09_whoseDueDateIsToday() {
        has(Shop().say("yaroda due date innaikku?"), "Selvi", "Kumar", "₹7,200")
    }

    @Test
    fun m10_kumarsPayments() {
        has(Shop().say("Kumar enna payments pannirukkaan?"), "₹2,000", "October 3rd", "₹3,000")
    }

    // ========================================================== N. reads never write

    @Test
    fun n01_queriesNeverCreateADraftOrAnEntry() {
        val s = Shop()
        for (q in listOf("Kumar enakku evlo tharanum?", "naan Kumar-ku evlo tharanum?", "innaikku yaar payment tharanum?", "details sollu",
            "yaaroda due date poiduchu?", "avanga details sollu", "total collection evlo pending?", "ellaa payable list pannu",
            "Kumar payment history sollu", "last 7 days la yaar yaar payment pannanga?", "highest pending amount yaar kitta?",
            "Kumar evlo kuduthirukkaan?", "today due + overdue rendu list-um sollu")) {
            val t = s.turn(q)
            assertNull("$q drafted something", t.plan)
            hasNot(t.reply.text, *saveClaims)
        }
        assertTrue(s.tools.prepared.isEmpty())
        assertTrue(s.tools.saved.isEmpty())
    }

    @Test
    fun n02_aListAnswerIsNotAReminder() {
        val s = Shop()
        val t = s.turn("today due + overdue rendu list-um sollu")
        assertTrue(t.card == null || t.card!!.buttons.isEmpty())
        has(t.reply.text, "Innaikku 2 per tharanum")
    }

    @Test
    fun n03_totalsAreExactToThePaisa() {
        val l = Ledger().apply {
            add("c1", "Kumar", true, "0.10", d(9, 1), d(10, 8))
            add("c2", "Ravi", true, "0.20", d(9, 1), d(10, 8))
            add("c3", "Selvi", true, "1000.05", d(9, 1), d(10, 8))
        }
        has(Shop(l).say("Innaikku evlo per payment tharanum?"), "3 per", "₹1,000.35")
    }
}
