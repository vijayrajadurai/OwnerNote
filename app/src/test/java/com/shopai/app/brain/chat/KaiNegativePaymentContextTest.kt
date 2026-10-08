package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.Direction
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
import com.shopai.app.brain.tools.KaiPaymentDirection
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.OwedDirection
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiLanguage
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * Negative / adversarial payment context: "avar yenakku evlo tharanum" (Kumar pays the owner) and
 * "na avarukku evlo tharanum" (the owner pays Kumar) are opposite questions and must never be answered
 * from each other's side. Queries never draft or save anything.
 *
 * Two kinds of books are used:
 *  - [Kumar.BOOKS]: the phone case — Kumar owes ₹2,000, due 30 Sep 2026 (already passed on 8 Oct 2026).
 *  - [Kumar.NONE] / [Kumar.ABSENT]: Kumar has nothing in the books (₹0 / no record), so a stated
 *    "Kumar enakku 2000 tharanum" is the only ₹2,000 in the conversation.
 */
class KaiNegativePaymentContextTest {

    private val now = LocalDateTime.of(2026, 10, 8, 11, 0)

    enum class Kumar { BOOKS, NONE, ABSENT }

    private class Db(kumar: Kumar) {
        data class Party(val match: PartyMatch, var pending: BigDecimal, var due: LocalDate?)
        private fun p(id: String, name: String, customer: Boolean, pending: String, due: LocalDate? = null) =
            Party(PartyMatch(id, name, customer, null, BigDecimal(pending)), BigDecimal(pending), due)
        val parties = mutableListOf(
            p("c2", "Ravi", true, "1500.00"),
            p("c3", "Selvi", true, "4200.00", LocalDate.of(2026, 10, 12)),
            p("c9", "Selvam", true, "5000.00"),
            p("s1", "Ramesh", false, "2500.00"),
        ).also {
            when (kumar) {
                Kumar.BOOKS -> it += p("c1", "Kumar", true, "2000.00", LocalDate.of(2026, 9, 30))
                Kumar.NONE -> it += p("c1", "Kumar", true, "0.00")
                Kumar.ABSENT -> Unit
            }
        }
        val saved = mutableListOf<ActionPlan>()
        /** What Kumar has paid so far (his ledger history; the rest is pending). */
        var kumarPaid = 0.0
        fun summary(x: Party) = PartySummary(x.match.id, x.match.name, x.match.phone, x.pending.toDouble(), x.due?.let { "${it}T00:00:00Z" })
    }

    private class Books(val db: Db) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = db.parties.filter { it.match.customer }.map(db::summary),
            suppliers = db.parties.filter { !it.match.customer }.map(db::summary),
        )
        override suspend fun history(party: PartyFacts): PartyHistory? {
            if (party.name != "Kumar") return null
            val paid = db.kumarPaid
            val payments = if (paid > 0) listOf(PartyHistory.Payment(paid, LocalDate.of(2026, 9, 20))) else emptyList()
            return PartyHistory(party, listOf(PartyHistory.LedgerEntry(party.pending + paid, paid, party.nextDue, LocalDate.of(2026, 9, 1), payments)))
        }
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(val db: Db) : KaiTools {
        val prepared = mutableListOf<ActionPlan>()
        val reminders = mutableListOf<KaiReminder>()
        val productList = listOf(ProductRef("p1", "Colgate", "PCS", BigDecimal("20"), mapOf("BOX" to BigDecimal(12))))
        override suspend fun parties(name: String) = db.parties.map { it.match.copy(balance = it.pending) }.filter { it.name.contains(name, true) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            ActionPlan("p${prepared.size}", kind, partyName, partyId, amount, mode, said = said).also { prepared += it }
        override suspend fun confirm(plan: ActionPlan): ActionOutcome {
            val customer = plan.kind == PlanKind.CREDIT_GIVEN
            val party = db.parties.firstOrNull { it.match.id == plan.partyId }
                ?: Db.Party(PartyMatch("n${db.parties.size}", plan.partyName, customer, null, BigDecimal.ZERO), BigDecimal.ZERO, null).also { db.parties += it }
            party.pending = party.pending + plan.amount
            if (plan.dueDate != null) party.due = plan.dueDate
            db.saved += plan
            return ActionOutcome.Done("TXN-${db.saved.size}", party.pending)
        }
        override suspend fun discard(plan: ActionPlan) = Unit
        override suspend fun products() = productList
        override suspend fun stock(product: String?) = productList.filter { product == null || it.name.equals(product, true) }
            .map { StockFact(it.name, it.stock, it.unit, reorderAt = BigDecimal("25")) }
        override suspend fun changeStock(product: ProductRef, qty: BigDecimal, incoming: Boolean, said: String) = ActionOutcome.Done("ST-1", null)
        override fun createReminder(reminder: KaiReminder): ReminderSaved { reminders += reminder; return ReminderSaved(reminder, false, ScheduleResult.EXACT) }
        override fun reminders() = reminders.filter { it.open }
        override fun updateReminder(reminder: KaiReminder): ReminderSaved { reminders.removeAll { it.id == reminder.id }; reminders += reminder; return ReminderSaved(reminder, false, ScheduleResult.EXACT) }
        override fun zone() = "Asia/Kolkata"
        override suspend fun contacts(name: String, role: PartyRole?) = emptyList<ContactMatch>()
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?) = "K-1"
    }

    private class Access(val tools: Tools) : KaiMemoryAccess {
        val memory = KaiPrivateMemory(InMemoryKaiMemoryStore(), clock = { 1_000L })
        override suspend fun current(): KaiPrivateMemory = memory.also { it.open("biz-A", "owner-A") }
        override suspend fun entities(): List<KnownEntity> = tools.productList.map { KnownEntity(it.id, it.name, MemoryType.PRODUCT_ALIAS) }
    }

    private inner class Shop(kumar: Kumar) {
        val db = Db(kumar)
        val tools = Tools(db)
        private val access = Access(tools)
        fun kai() = KaiAgent(KaiBusinessBrain(Books(db), today = { now.toLocalDate() }, random = Random(1)), Books(db), tools, now = { now }, memory = access)
        var k = kai()
        fun say(text: String): String = runBlocking { k.ask(text).reply.text }
        fun turn(text: String): KaiTurn = runBlocking { k.ask(text) }
        fun restart() { k = kai() }
    }

    private fun has(text: String, vararg parts: String) = parts.forEach { assertTrue("'$it' in: $text", text.contains(it)) }
    private fun hasNot(text: String, vararg parts: String) = parts.forEach { assertFalse("'$it' must not be in: $text", text.contains(it)) }
    private val noSaveClaim = arrayOf("save pannitten", "add pannitten", "note pannitten", "record pannitten", "Save aagiduchu")

    /** The receivable query: Kumar's ₹2,000, owner-facing, no due sentence unless asked. */
    private fun receivable(text: String, amount: String = "₹2,000") {
        has(text, "Kumar", "ungalukku", amount)
        hasNot(text, "kudukkanum", "September", "thaandi", "due")
    }

    /** The payable query when the owner owes Kumar nothing. */
    private fun nothingPayable(text: String) {
        assertEquals("Owner, Kumar-ku neenga kudukkanum-nu pending amount illa.", text)
    }

    // ------------------------------------------------------------ the phone case

    @Test
    fun realPhoneCase_avarYenakkuIsKumarPayingTheOwner() {
        val s = Shop(Kumar.BOOKS)
        s.say("Kumar balance evlo?")
        val t = s.say("avar yenakku evlo tharanum")
        assertEquals("Kumar ungalukku ₹2,000 tharanum owner.", t)
        receivable(t)
        assertTrue(s.db.saved.isEmpty())
    }

    @Test
    fun yenakkuSpellingsAreTheOwnerReceiving() {
        for (q in listOf("Kumar yenakku evlo tharanum", "Kumar yenaku evlo tharanum", "Kumar yennaku evlo tharanum", "Kumar enakku evlo tharanum")) {
            assertEquals(q, OwedDirection.RECEIVABLE, KaiPaymentDirection.of(q))
        }
        assertEquals(OwedDirection.PAYABLE, KaiPaymentDirection.of("na Kumar-ku evlo tharanum"))
        // No owner word: no side is assumed for a question ("Ramesh enna tharanum?" asks about whichever side Ramesh is on).
        assertNull(KaiPaymentDirection.explicitOf("Ramesh enna tharanum?"))
        assertEquals(OwedDirection.RECEIVABLE, KaiPaymentDirection.explicitOf("avar yenakku evlo tharanum"))
    }

    // ------------------------------------------------------------ 1–20

    @Test
    fun n01_payableQueryNeverReversesTheReceivable() {
        val s = Shop(Kumar.BOOKS)
        s.say("Kumar balance evlo?")
        val t = s.say("na avarukku evlo tharanum")
        nothingPayable(t)
        hasNot(t, "₹2,000")
    }

    @Test
    fun n02_statementThenBothSidesAreOpposite() {
        val s = Shop(Kumar.NONE)
        s.say("Kumar enakku 2000 tharanum")
        val rec = s.say("avar yenakku evlo tharanum")
        has(rec, "Kumar ungalukku ₹2,000 tharanum", "innum save aagala")
        hasNot(rec, "kudukkanum", *noSaveClaim)
        nothingPayable(s.say("na avarukku evlo tharanum"))
        assertTrue(s.db.saved.isEmpty())
    }

    @Test
    fun n03_casualInterruptionKeepsKumar() {
        val s = Shop(Kumar.NONE)
        s.say("Kumar enakku 2000 tharanum")
        s.say("saptiya?")
        s.say("seri")
        has(s.say("avar yenakku evlo tharanum?"), "Kumar ungalukku ₹2,000 tharanum")
        assertTrue(s.db.saved.isEmpty())
    }

    @Test
    fun n04_topicSwitchThenPronounIsKumarNotTheProduct() {
        val s = Shop(Kumar.NONE)
        s.say("Kumar enakku 2000 tharanum")
        has(s.say("Colgate stock evlo?"), "Colgate")
        val t = s.say("avar yenakku evlo tharanum?")
        has(t, "Kumar ungalukku ₹2,000 tharanum")
        hasNot(t, "Colgate", "PCS")
    }

    @Test
    fun n05_amountAndDueAreSeparateQuestions() {
        val s = Shop(Kumar.BOOKS)
        s.say("Kumar balance evlo?")
        receivable(s.say("avar yenakku evlo tharanum"))
        has(s.say("eppa due?"), "Kumar", "September 30")
        receivable(s.say("evlo?"))
    }

    @Test
    fun n06_aPassedDueDateIsNotPaid() {
        val s = Shop(Kumar.BOOKS)
        s.say("Kumar balance evlo?")
        val clear = s.say("Kumar clear ah?")
        has(clear, "₹2,000")
        hasNot(clear, "clear-ah irukku", "Pending illa")
        val due = s.say("Kumar due eppa?")
        has(due, "₹2,000", "September 30", "thaandiduchu")
        hasNot(due, "clear", "paid")
        assertTrue(s.db.saved.isEmpty())
    }

    @Test
    fun n07_freshPronounAsksWhoAndNeverPicksKumar() {
        val s = Shop(Kumar.BOOKS)
        val t = s.say("avar yenakku evlo tharanum")
        has(t, "yaar pathi")
        hasNot(t, "Kumar", "₹")
    }

    @Test
    fun n08_twoPeopleInTheConversationAreClarified() {
        val s = Shop(Kumar.BOOKS)
        s.say("Kumar and Selvam rendu perum pending")
        val t = s.say("avar yenakku evlo tharanum")
        assertEquals("Kumar-aa Selvam-aa Owner?", t)
    }

    @Test
    fun n09_aSimilarNameIsNotKumar() {
        val s = Shop(Kumar.BOOKS)
        s.say("Kumaran enakku 3000 tharanum")
        val t = s.say("avar evlo tharanum?")
        has(t, "Kumaran", "₹3,000", "innum save aagala")
        hasNot(t, "₹2,000", "Kumar ungalukku")
    }

    @Test
    fun n10_receivableAndPayableStatementsAreAnsweredPerSide() {
        val s = Shop(Kumar.NONE)
        has(s.say("Kumar enakku 3000 tharanum"), "collect")
        has(s.say("na Kumar-ku 3000 tharanum"), "pay")
        val rec = s.say("Kumar enakku evlo tharanum?")
        has(rec, "Kumar ungalukku ₹3,000 tharanum", "innum save aagala")
        hasNot(rec, "kudukkanum")
        val pay = s.say("na Kumar-ku evlo tharanum?")
        has(pay, "Kumar-ku ₹3,000 kudukkanum", "innum save aagala")
        hasNot(pay, "ungalukku")
        assertTrue(s.db.saved.isEmpty())
    }

    @Test
    fun n10b_withBooksTheReceivableIsTheBooksAndThePayableIsTheStatement() {
        val s = Shop(Kumar.BOOKS)
        s.say("Kumar enakku 3000 tharanum")
        s.say("na Kumar-ku 3000 tharanum")
        has(s.say("Kumar enakku evlo tharanum?"), "Kumar ungalukku ₹2,000 tharanum", "₹3,000 innum save aagala")
        // The receivable customer record (c1) is never read as the payable side.
        has(s.say("na Kumar-ku evlo tharanum?"), "Kumar-ku ₹3,000 kudukkanum-nu neenga sonneenga", "Records-la Kumar pending illa")
    }

    @Test
    fun n11_directionsOfFourStatements() {
        assertEquals(OwedDirection.RECEIVABLE, KaiPaymentDirection.of("Kumar enakku 3000 tharanum"))
        assertEquals(OwedDirection.PAYABLE, KaiPaymentDirection.of("Kumar-ku naan 3000 tharanum"))
        assertEquals(OwedDirection.RECEIVABLE, KaiPaymentDirection.of("avar enakku 3000 tharanum"))
        assertEquals(OwedDirection.PAYABLE, KaiPaymentDirection.of("naan avarukku 3000 tharanum"))
        val s = Shop(Kumar.BOOKS)
        s.say("Kumar balance evlo?")
        has(s.say("avar enakku 3000 tharanum"), "Kumar kitta irundhu ₹3,000 collect")
        val s2 = Shop(Kumar.BOOKS)
        s2.say("Kumar balance evlo?")
        has(s2.say("naan avarukku 3000 tharanum"), "Kumar-ku ₹3,000 pay")
    }

    @Test
    fun n12_queriesCreateNoDraftAndNoTransaction() {
        val s = Shop(Kumar.BOOKS)
        for (q in listOf("Kumar enakku evlo tharanum?", "na Kumar-ku evlo tharanum?", "avar yenakku evlo tharanum", "evlo?", "due eppa?", "September 30-ku evlo?")) {
            val t = s.turn(q)
            assertTrue("$q made a draft: ${t.card?.lines}", t.plan == null && t.card?.buttons.isNullOrEmpty())
            hasNot(t.reply.text, *noSaveClaim)
        }
        assertTrue(s.tools.prepared.isEmpty())
        assertTrue(s.db.saved.isEmpty())
    }

    @Test
    fun n13_dueVenamOnlyDropsTheDraftsDueDate() {
        val s = Shop(Kumar.NONE)
        s.say("Kumar enakku 2000 tharanum")
        // 30 Sep has just passed (today is 8 Oct): which year is asked, never assumed.
        has(s.say("September 30"), "September 30th 2026 already thaandiduchu", "2027")
        has(s.say("adutha varusham"), "₹2,000", "September 30th 2027")
        has(s.say("avar yenakku evlo tharanum?"), "Kumar ungalukku ₹2,000 tharanum")
        has(s.say("due eppa?"), "Kumar", "₹2,000", "September 30")
        val venam = s.turn("due venam")
        has(venam.reply.text, "Due date illa", "₹2,000")
        hasNot(venam.reply.text, "clear", "Pending illa")
        has(venam.card!!.lines.joinToString(), "Due date: illa")
        has(s.say("avar yenakku evlo tharanum?"), "Kumar ungalukku ₹2,000 tharanum", "Confirm pannunga")
        assertTrue(s.db.saved.isEmpty())
        // Saved only on Confirm, with no due date, still pending.
        has(s.say("save pannu"), "Save aagiduchu", "₹2,000")
        assertNull(s.db.saved.single().dueDate)
        s.restart()
        has(s.say("Kumar enakku evlo tharanum?"), "₹2,000")
    }

    @Test
    fun n14_avanAvarAfterAStatementIsKumar_freshIsAQuestion() {
        for (p in listOf("avan", "avar")) {
            val s = Shop(Kumar.NONE)
            s.say("Kumar enakku 3000 tharanum")
            has(s.say("$p evlo tharanum?"), "Kumar", "₹3,000")
        }
        val fresh = Shop(Kumar.BOOKS).say("avan evlo tharanum?")
        has(fresh, "yaar pathi")
        hasNot(fresh, "Kumar")
    }

    @Test
    fun n15_tamilScript() {
        val s = Shop(Kumar.NONE)
        s.say("குமார் எனக்கு 2000 தரணும்")
        has(s.say("அவர் எனக்கு எவ்வளவு தரணும்?"), "Kumar உங்களுக்கு ₹2,000 தரணும்")
        assertEquals("ஓனர், Kumar-க்கு நீங்க கொடுக்கணும்-னு பாக்கி எதுவும் இல்ல.", s.say("நான் அவருக்கு எவ்வளவு தரணும்?"))
        assertTrue(s.db.saved.isEmpty())
    }

    @Test
    fun n16_mixedTanglish() {
        val s = Shop(Kumar.NONE)
        has(s.say("kumar enaku 2k tharanum"), "₹2,000")
        has(s.say("avar enaku evlo tharanum?"), "Kumar ungalukku ₹2,000 tharanum")
        nothingPayable(s.say("na avarukku evlo tharanum?"))
    }

    @Test
    fun n17_aDateQuestionListsWhatFallsDueThatDay() {
        val s = Shop(Kumar.BOOKS)
        assertEquals("Owner, September 30th due: Kumar ₹2,000 tharanum.", s.say("September 30-ku evlo?"))
        val none = Shop(Kumar.NONE).say("September 30-ku evlo?")
        has(none, "yaarukkum due illa")
        hasNot(none, "₹")
    }

    @Test
    fun n18_anUnknownPersonIsNeverKumar() {
        val s = Shop(Kumar.BOOKS)
        s.say("Kumar balance evlo?")
        val t = s.say("Ravikumar enakku evlo tharanum?")
        has(t, "Ravikumar", "kidaikala")
        hasNot(t, "₹2,000", "Kumar ungalukku")
    }

    @Test
    fun n19_afterSaveBothSidesAreAnsweredFromTheBooks() {
        for (kumar in listOf(Kumar.NONE, Kumar.ABSENT)) {
            val s = Shop(kumar)
            s.say("Kumar enakku 2000 tharanum")
            s.say("save panniko")
            has(s.say("confirm"), "Save aagiduchu")
            assertEquals(1, s.db.saved.size)
            assertEquals("Kumar ungalukku ₹2,000 tharanum owner.", s.say("avar yenakku evlo tharanum?"))
            nothingPayable(s.say("na avarukku evlo tharanum?"))
            s.restart()
            assertEquals("Kumar ungalukku ₹2,000 tharanum owner.", s.say("Kumar enakku evlo tharanum?"))
            assertEquals(1, s.db.saved.size)
        }
    }

    @Test
    fun n20_stressSequence() {
        for (kumar in listOf(Kumar.NONE, Kumar.ABSENT)) {
            val s = Shop(kumar)
            s.say("Kumar enakku 2000 tharanum")
            has(s.say("eppa?"), "Kumar", "₹2,000")
            // Just passed: Kai asks 2026 or 2027; the owner moves on without answering.
            has(s.say("September 30"), "September 30th 2026 already thaandiduchu", "2027")
            has(s.say("avar yenakku evlo tharanum?"), "Kumar ungalukku ₹2,000 tharanum")
            s.say("saptiya?")
            s.say("seri")
            nothingPayable(s.say("na avarukku evlo tharanum?"))
            has(s.say("Colgate stock evlo?"), "Colgate")
            has(s.say("avar yenakku evlo tharanum?"), "Kumar ungalukku ₹2,000 tharanum")
            // No year was picked, so no due date was set — none is invented.
            val due = s.say("due eppa?")
            has(due, "Kumar", "₹2,000")
            hasNot(due, "2027", "2026")
            has(s.say("due venam"), "Due date illa", "₹2,000")
            val last = s.say("avar yenakku evlo tharanum?")
            has(last, "Kumar ungalukku ₹2,000 tharanum", "innum save aagala")
            hasNot(last, *noSaveClaim)
            assertTrue("$kumar: nothing saved", s.db.saved.isEmpty())
        }
    }

    @Test
    fun n20b_stressSequenceWithTheYearAnswered() {
        val s = Shop(Kumar.NONE)
        s.say("Kumar enakku 2000 tharanum")
        s.say("eppa?")
        s.say("September 30")
        has(s.say("2027"), "September 30th 2027")
        s.say("saptiya?")
        nothingPayable(s.say("na avarukku evlo tharanum?"))
        s.say("Colgate stock evlo?")
        has(s.say("due eppa?"), "Kumar", "₹2,000", "September 30th 2027")
        has(s.say("due venam"), "Due date illa", "₹2,000")
        has(s.say("avar yenakku evlo tharanum?"), "Kumar ungalukku ₹2,000 tharanum", "innum save aagala")
        assertTrue(s.db.saved.isEmpty())
    }

    // ------------------------------------------------------------ a due date that has just passed: which year?

    @Test
    fun y1_justPassedDayAsksTheYear_2026IsSavedAs2026() {
        val s = Shop(Kumar.NONE)
        s.say("Kumar enakku 2000 tharanum")
        val q = s.turn("September 30")
        assertEquals("Owner, September 30th 2026 already thaandiduchu. Andha date-aa (2026), illa adutha varusham September 30th 2027-aa?", q.reply.text)
        assertNull(q.plan)
        val draft = s.turn("2026")
        has(draft.reply.text, "₹2,000", "September 30th")
        assertEquals(LocalDate.of(2026, 9, 30), draft.plan!!.dueDate)
        assertTrue(s.db.saved.isEmpty())
        has(s.say("seri"), "Save aagiduchu")
        assertEquals(LocalDate.of(2026, 9, 30), s.db.saved.single().dueDate)
    }

    @Test
    fun y2_nextYearIsSavedAs2027() {
        val s = Shop(Kumar.NONE)
        s.say("Kumar enakku 2000 tharanum")
        s.say("September 30")
        has(s.say("adutha varusham"), "September 30th 2027")
        s.say("save pannu")
        s.say("seri")
        assertEquals(LocalDate.of(2027, 9, 30), s.db.saved.single().dueDate)
    }

    @Test
    fun y3_amaPicksNeitherYear_andAnExplicitYearIsNotAsked() {
        val s = Shop(Kumar.NONE)
        s.say("Kumar enakku 2000 tharanum")
        s.say("September 30")
        has(s.say("ama"), "2026", "2027")
        has(s.say("andha date dhaan"), "September 30th")
        val e = Shop(Kumar.NONE)
        e.say("Kumar enakku 2000 tharanum")
        val direct = e.say("September 30 2026")
        has(direct, "₹2,000", "September 30th")
        hasNot(direct, "thaandiduchu", "2027")
    }

    @Test
    fun y4_aDateInTheSentenceIsAskedToo_theAmountIsNotAYear() {
        has(Shop(Kumar.NONE).say("Kumar enakku 2000 tharanum September 30"), "September 30th 2026 already thaandiduchu")
        // ₹2,026 is the amount, not the year.
        has(Shop(Kumar.NONE).say("Kumar enakku 2026 tharanum September 30"), "September 30th 2026 already thaandiduchu")
        val s = Shop(Kumar.NONE)
        s.say("Kumar enakku 2000 tharanum September 30")
        has(s.say("2027"), "September 30th 2027")
    }

    @Test
    fun y5_aDayLongPastOrAheadIsNotAsked() {
        val s = Shop(Kumar.NONE)
        s.say("Kumar enakku 2000 tharanum")
        // 5 January 2026 is months back: "January 5" plainly means the coming one.
        val jan = s.say("January 5")
        hasNot(jan, "thaandiduchu")
        has(jan, "January 5th 2027")
        val d = Shop(Kumar.NONE)
        d.say("Kumar enakku 2000 tharanum")
        hasNot(d.say("December 25"), "thaandiduchu")
    }

    @Test
    fun y6_tamilScript() {
        val s = Shop(Kumar.NONE)
        s.say("குமார் எனக்கு 2000 தரணும்")
        has(s.say("செப்டம்பர் 30"), "ஏற்கனவே தாண்டிடுச்சு", "2026", "2027")
        has(s.say("2026"), "₹2,000")
    }

    // ------------------------------------------------------------ the same amount already in the books: same or new?

    @Test
    fun d1_sameAmountAsBooksAsksBeforeTheDraft_newGoesToTheDraft() {
        val s = Shop(Kumar.BOOKS)
        s.say("Kumar enakku 2000 tharanum")
        val q = s.turn("save pannu")
        assertEquals("Owner, records-la already Kumar ₹2,000 tharanum-nu irukku. Adhey ₹2,000-aa, illa pudhu ₹2,000-aa?", q.reply.text)
        assertNull(q.plan)
        assertTrue(s.tools.prepared.isEmpty())
        val d = s.turn("pudhusu")
        has(d.card!!.lines.joinToString(), "Pudhu entry: ₹2,000", "₹2,000 → ₹4,000")
        assertTrue(s.db.saved.isEmpty())
        has(s.say("confirm"), "Save aagiduchu", "₹4,000")
        assertEquals(1, s.db.saved.size)
    }

    @Test
    fun d2_sameAmountAddsNothing() {
        val s = Shop(Kumar.BOOKS)
        s.say("Kumar enakku 2000 tharanum")
        s.say("save pannu")
        assertEquals("Seri Owner, pudhusa edhuvum add pannala. Records-la Kumar ₹2,000 apdiye irukku.", s.say("adhey dhaan"))
        assertTrue(s.tools.prepared.isEmpty())
        assertTrue(s.db.saved.isEmpty())
        assertNull(s.k.conversationState.stated)
        assertEquals("Kumar ungalukku ₹2,000 tharanum owner.", s.say("Kumar enakku evlo tharanum?"))
    }

    @Test
    fun d3_amaIsNeitherSameNorNew() {
        val s = Shop(Kumar.BOOKS)
        s.say("Kumar enakku 2000 tharanum")
        s.say("save pannu")
        val again = s.turn("ama")
        assertEquals("Owner, adhey ₹2,000-aa, pudhu ₹2,000-aa?", again.reply.text)
        assertNull(again.plan)
        assertTrue(s.db.saved.isEmpty())
    }

    @Test
    fun d4_otherWaysToTheDraftAskToo_aDifferentAmountDoesNot() {
        val venam = Shop(Kumar.BOOKS)
        venam.say("Kumar enakku 2000 tharanum")
        has(venam.say("due venam"), "Adhey ₹2,000-aa, illa pudhu ₹2,000-aa?")
        has(Shop(Kumar.BOOKS).say("Kumar enakku 2000 tharanum save panniko"), "Adhey ₹2,000-aa, illa pudhu ₹2,000-aa?")
        val other = Shop(Kumar.BOOKS)
        other.say("Kumar enakku 3000 tharanum")
        val d = other.turn("save pannu")
        hasNot(d.reply.text, "Adhey")
        has(d.card!!.lines.joinToString(), "₹2,000 → ₹5,000")
    }

    @Test
    fun d5_saidAsNewIsNotAsked() {
        val t = Shop(Kumar.BOOKS).turn("Kumar enakku already 2000 tharanum ippa oru 2000 tharanum")
        has(t.reply.text, "pudhu ₹2,000 entry")
        has(t.card!!.lines.joinToString(), "₹2,000 → ₹4,000")
    }

    @Test
    fun d6_payableSideAsksInItsOwnWords() {
        val s = Shop(Kumar.BOOKS)
        s.say("naan Ramesh-ku 2500 tharanum")
        has(s.say("save pannu"), "records-la already Ramesh-ku ₹2,500 kudukkanum-nu irukku", "Adhey ₹2,500-aa")
        has(s.turn("pudhu").card!!.lines.joinToString(), "₹2,500")
    }

    // ------------------------------------------------------------ "Kumar paid ah?"

    @Test
    fun p1_paidAhIsTanglishAndAPassedDateIsNotPaid() {
        val s = Shop(Kumar.BOOKS)
        assertEquals("Illa owner, Kumar idhuvarai edhuvum kudukkala. Pending ₹2,000.", s.say("Kumar paid ah?"))
        has(s.say("Kumar clear-aa?"), "₹2,000")
        assertEquals(KaiLang.TANGLISH, KaiLanguage.detect("Kumar paid ah?"))
        assertEquals(KaiLang.TANGLISH, KaiLanguage.forChat("Kumar clear-aa?"))
        assertEquals(KaiLang.ENGLISH, KaiLanguage.forChat("Has Kumar paid?"))
        has(Shop(Kumar.BOOKS).say("Has Kumar paid?"), "hasn't paid anything", "₹2,000")
    }

    @Test
    fun p2_partPaidSaysWhatWasPaidAndWhatIsLeft() {
        val s = Shop(Kumar.BOOKS)
        s.db.kumarPaid = 500.0
        val t = s.say("Kumar paid ah?")
        has(t, "Kumar", "₹500", "₹2,000")
        hasNot(t, "clear", "Illa owner")
    }

    // ------------------------------------------------------------ the regressions found on the way

    @Test
    fun yenakkuIsNeverAPersonsName() {
        val s = Shop(Kumar.ABSENT)
        s.say("Kumar enakku 2000 tharanum")
        s.say("na avarukku evlo tharanum?")
        s.say("Colgate stock evlo?")
        val t = s.say("avar yenakku evlo tharanum?")
        hasNot(t, "Yenak")
        has(t, "Kumar")
        assertEquals("Kumar", s.k.conversationState.lastPerson)
    }

    @Test
    fun aSideQueryWithNothingOnThatSideStillKeepsTheRecordInFocus() {
        val s = Shop(Kumar.BOOKS)
        s.say("Kumar balance evlo?")
        nothingPayable(s.say("na avarukku evlo tharanum"))
        has(s.say("due eppa?"), "Kumar", "September 30")
    }

    @Test
    fun brainSideFilterAndUnmarkedQuestions() {
        val today = now.toLocalDate()
        assertEquals(Direction.PAYABLE, KaiChatUnderstanding.understand("na Kumar-ku evlo tharanum?", today, listOf("Kumar")).side)
        assertEquals(Direction.RECEIVABLE, KaiChatUnderstanding.understand("Kumar yenakku evlo tharanum?", today, listOf("Kumar")).side)
        assertNull(KaiChatUnderstanding.understand("Ramesh enna tharanum?", today, listOf("Ramesh")).side)
        // A supplier asked about with no side word is still answered from the supplier side.
        has(Shop(Kumar.BOOKS).say("Ramesh enna tharanum?"), "Ramesh", "₹2,500")
        // "entha date-la" is a due-date question.
        assertEquals(ChatIntent.CUSTOMER_DUE_DATE, KaiChatUnderstanding.understand("Kumar enaku entha date-la payment tharanum?", today, listOf("Kumar")).intent)
    }
}
