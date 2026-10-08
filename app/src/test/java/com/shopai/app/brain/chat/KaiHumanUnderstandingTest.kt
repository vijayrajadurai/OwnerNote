package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * Kai as a companion that keeps the thread of the conversation: what is pending, who "avan" is, which of
 * several same-named people is meant, which product "adhu" is, when a topic really changed and when the owner
 * came back to an old one — answered from the records, saved only on Confirm, never guessed.
 *
 * The first block mirrors the owner's own conversations; the "generalised" blocks use other people, products,
 * amounts and phrasings so the behaviour is not tied to the examples.
 */
class KaiHumanUnderstandingTest {
    // Thursday 8 October 2026, 11 AM.
    private val now = LocalDateTime.of(2026, 10, 8, 11, 0)

    class Db {
        data class Party(val match: PartyMatch, var pending: BigDecimal, var due: LocalDate?)
        private fun p(id: String, name: String, customer: Boolean, pending: String, due: LocalDate? = null, phone: String? = null, city: String? = null, details: String? = null) =
            Party(PartyMatch(id, name, customer, phone, BigDecimal(pending), city = city, details = details), BigDecimal(pending), due)
        val parties = mutableListOf(
            p("c1", "Kumar", true, "3000.00", LocalDate.of(2026, 10, 20), "+919000000001"),
            p("c2", "Ravi", true, "1500.00"),
            p("c3", "Selvi", true, "4200.00", LocalDate.of(2026, 10, 12), city = "Madurai"),
            p("c4", "Arun", true, "0.00"),
            p("c5", "Priya", true, "800.00", city = "Salem", phone = "+919444411111"),
            p("c6", "Priya", true, "650.00", city = "Erode", details = "Priya Textiles, Bazaar Street"),
            p("s1", "Ramesh", false, "2500.00"),
            p("s2", "Basha", false, "9000.00", LocalDate.of(2026, 10, 15), city = "Vellore"),
            p("l1", "Lokesh", false, "0.00", phone = "+919876543210", city = "Chennai"),
            p("l2", "Lokesh", false, "0.00", phone = "+919123456780", city = "Nagapattinam", details = "Sri Vinayaga Hardware, Main Road"),
            p("l3", "Lokesh", false, "0.00", city = "Trichy"),
        )
        val saved = mutableListOf<ActionPlan>()
        var failSave = false
        fun summary(x: Party) = PartySummary(x.match.id, x.match.name, x.match.phone, x.pending.toDouble(), x.due?.let { "${it}T00:00:00Z" })
    }

    private class Books(val db: Db) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = db.parties.filter { it.match.customer }.map(db::summary),
            suppliers = db.parties.filter { !it.match.customer }.map(db::summary),
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(val db: Db) : KaiTools {
        val prepared = mutableListOf<ActionPlan>()
        val discarded = mutableListOf<ActionPlan>()
        val stockChanges = mutableListOf<Triple<String, BigDecimal, Boolean>>()
        val reminders = mutableListOf<KaiReminder>()
        val productList = listOf(
            ProductRef("p1", "Colgate", "PCS", BigDecimal("20"), mapOf("BOX" to BigDecimal(12))),
            ProductRef("p2", "Rice", "KG", BigDecimal("50")),
            ProductRef("p3", "Sugar", "KG", BigDecimal("8")),
            ProductRef("p4", "Tea Powder", "PCS", BigDecimal("40"), mapOf("BOX" to BigDecimal(10))),
        )
        private val reorder = mapOf("Colgate" to "25", "Rice" to "10", "Sugar" to "10", "Tea Powder" to "15")
        override suspend fun parties(name: String) = db.parties.map { it.match.copy(balance = it.pending) }.filter { it.name.contains(name, true) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            ActionPlan("p${prepared.size}", kind, partyName, partyId, amount, mode, said = said).also { prepared += it }
        override suspend fun confirm(plan: ActionPlan): ActionOutcome {
            if (db.failSave) return ActionOutcome.Failed("disk full")
            val customer = plan.kind == PlanKind.CREDIT_GIVEN
            val party = db.parties.firstOrNull { it.match.id == plan.partyId }
                ?: Db.Party(PartyMatch("n${db.parties.size}", plan.partyName, customer, null, BigDecimal.ZERO), BigDecimal.ZERO, null).also { db.parties += it }
            party.pending = party.pending + plan.amount
            if (plan.dueDate != null) party.due = plan.dueDate
            db.saved += plan
            return ActionOutcome.Done("TXN-${db.saved.size}", party.pending)
        }
        override suspend fun discard(plan: ActionPlan) { discarded += plan }
        override suspend fun products() = productList
        override suspend fun stock(product: String?) = productList.filter { product == null || it.name.equals(product, true) }
            .map { StockFact(it.name, it.stock, it.unit, reorderAt = BigDecimal(reorder.getValue(it.name))) }
        override suspend fun changeStock(product: ProductRef, qty: BigDecimal, incoming: Boolean, said: String): ActionOutcome {
            stockChanges += Triple(product.id, qty, incoming); return ActionOutcome.Done("ST-1", null)
        }
        override fun createReminder(reminder: KaiReminder): ReminderSaved { reminders += reminder; return ReminderSaved(reminder, false, ScheduleResult.EXACT) }
        override fun reminders() = reminders.filter { it.open }
        override fun zone() = "Asia/Kolkata"
        override suspend fun contacts(name: String, role: PartyRole?) = emptyList<ContactMatch>()
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?) = "K-1"
    }

    private class Access(store: InMemoryKaiMemoryStore, var business: String, var owner: String, val tools: Tools) : KaiMemoryAccess {
        val memory = KaiPrivateMemory(store, clock = { 1_000L })
        override suspend fun current(): KaiPrivateMemory = memory.also { it.open(business, owner) }
        override suspend fun entities(): List<KnownEntity> = tools.productList.map { KnownEntity(it.id, it.name, MemoryType.PRODUCT_ALIAS) }
    }

    private var db = Db()
    private var tools = Tools(db)
    private fun kai(memory: KaiMemoryAccess? = null): KaiAgent {
        db = Db(); tools = Tools(db)
        return agentOver(db, tools, memory)
    }
    private fun agentOver(d: Db, t: Tools, memory: KaiMemoryAccess? = null) =
        KaiAgent(KaiBusinessBrain(Books(d), today = { now.toLocalDate() }, random = Random(1)), Books(d), t, now = { now }, memory = memory)
    private fun KaiAgent.say(text: String) = runBlocking { ask(text) }
    private fun KaiAgent.chat(vararg lines: String): KaiTurn = lines.map { say(it) }.last()
    private fun KaiAgent.stated() = conversationState.stated!!
    private val KaiTurn.t get() = reply.text
    private fun has(turn: KaiTurn, vararg parts: String) = parts.forEach { assertTrue("'$it' in: ${turn.t}", turn.t.contains(it)) }
    private fun hasNot(turn: KaiTurn, vararg parts: String) = parts.forEach { assertFalse("'$it' not in: ${turn.t}", turn.t.contains(it)) }
    private fun neverBlankOrClearAh(turn: KaiTurn) {
        assertTrue(turn.t.isNotBlank())
        hasNot(turn, "clear-ah sollunga", "meaning-la?")
    }
    private fun nothingWritten() {
        assertTrue("saved: ${db.saved}", db.saved.isEmpty())
        assertTrue("stock: ${tools.stockChanges}", tools.stockChanges.isEmpty())
    }
    private val IN = KaiConversationPaymentDirection.PAYMENT_IN
    private val OUT = KaiConversationPaymentDirection.PAYMENT_OUT

    // ======================================================================== REQUIRED CONVERSATIONS (1–11)

    @Test
    fun t01_paymentContextThroughToAvan() {
        val k = kai()
        has(k.say("Kumar enakku 3000 tharanum"), "Kumar", "₹3,000", "Due date")
        k.say("eppa?")
        has(k.say("10"), "indha maasam 10-aa", "adutha maasam 10-aa")
        k.say("next month")
        val t = k.say("avan eppa tharuvaan?")
        assertEquals("Owner, Kumar ₹3,000 adutha maasam 10-m thethi tharuvaar. Innum save pannala Owner.", t.t)
        assertEquals(IN, k.stated().direction)
        assertEquals(LocalDate.of(2026, 11, 10), k.stated().dueDate)
        nothingWritten()
    }

    @Test
    fun t02_casualInterruptionThenEppa() {
        val t = kai().chat("Kumar enakku 3000 tharanum", "saptiya?", "seri", "eppa?")
        has(t, "Kumar", "₹3,000")
        hasNot(t, "meaning")
    }

    @Test
    fun t03_calculatorInterruption() {
        val k = kai()
        k.say("Kumar enakku 3000 tharanum")
        assertEquals("1,625", k.say("1250 plus 375").t)
        k.say("seri")
        has(k.say("avan eppa tharuvaan?"), "Kumar", "₹3,000")
    }

    @Test
    fun t04_stockContext() {
        val k = kai()
        has(k.say("Colgate stock evlo?"), "Colgate", "20")
        has(k.say("adhu low-aa?"), "Colgate")
        has(k.say("athula 5 pochu"), "Colgate", "5 pieces stock-out")
        nothingWritten()
    }

    @Test
    fun t05_paymentPersistenceAndRestart() {
        val k = kai()
        k.chat("Mahesh enakku 2000 tharanum", "save panniko")
        nothingWritten()
        has(k.say("seri"), "Save aagiduchu")
        assertEquals(BigDecimal("2000.00"), db.saved.single().amount)
        has(k.say("Mahesh enakku evlo tharanum?"), "₹2,000")
        val restarted = agentOver(db, Tools(db))
        has(restarted.say("Mahesh enakku evlo tharanum?"), "₹2,000")
    }

    @Test
    fun t06_duplicateNameByLocation() {
        val k = kai()
        k.say("Lokesh Nagapattinam-ku 500 tharanum")
        assertEquals("l2", k.stated().partyId)
    }

    @Test
    fun t07_ambiguousNameThenTown() {
        val k = kai()
        has(k.say("Lokesh-ku 500 tharanum"), "Lokesh-nu moonu records irukku", "Chennai Lokesh", "Nagapattinam Lokesh", "Trichy Lokesh")
        assertNull(k.stated().partyId)
        has(k.say("Nagapattinam"), "Nagapattinam Lokesh-ku ₹500")
        assertEquals("l2", k.stated().partyId)
    }

    @Test
    fun t08_businessName() {
        val k = kai()
        k.say("Lokesh Sri Vinayaga Hardware-ku 500 tharanum")
        assertEquals("l2", k.stated().partyId)
    }

    @Test
    fun t09_phone() {
        val k = kai()
        k.say("Lokesh 9876543210-ku 500 tharanum")
        assertEquals("l1", k.stated().partyId)
    }

    @Test
    fun t10_topicSwitchToStock() {
        val t = kai().chat("Kumar enakku 3000 tharanum", "Colgate stock evlo?", "adhu low-aa?")
        has(t, "Colgate")
        hasNot(t, "Kumar")
    }

    @Test
    fun t11_returnToTheOldTopic() {
        val k = kai()
        val t = k.chat("Kumar enakku 3000 tharanum", "Colgate stock evlo?", "adhu low-aa?", "Kumar eppa tharuvaan?")
        has(t, "neenga sonna Kumar ₹3,000", "due date innum sollala")
        // What the books already hold for Kumar is said apart from the new amount — never merged into it.
        has(t, "Records-la Kumar ₹3,000 October 20th due already irukku")
        assertEquals(KaiPendingQuestion.DUE_DATE, k.conversationState.pendingQuestion)
        assertEquals(LocalDate.of(2026, 11, 10), k.chat("next month 10").let { k.stated().dueDate })
    }

    // ======================================================================== A. context continuation

    @Test
    fun a01_thisMonthChoice() {
        val k = kai()
        k.chat("Kumar enakku 3000 tharanum", "10", "indha maasam")
        assertEquals(LocalDate.of(2026, 10, 10), k.stated().dueDate)
    }

    @Test
    fun a02_tomorrowAnswersTheDate() {
        val k = kai()
        has(k.chat("Kumar enakku 3000 tharanum", "tomorrow"), "naalaikku")
        assertEquals(LocalDate.of(2026, 10, 9), k.stated().dueDate)
    }

    @Test
    fun a03_explicitDateNeverGuessed() {
        val k = kai()
        k.chat("Kumar enakku 3000 tharanum", "July 6")
        assertEquals(LocalDate.of(2027, 7, 6), k.stated().dueDate)
    }

    @Test
    fun a04_noteThenAvanStillReceivable() {
        val t = kai().chat("Kumar enakku 3000 tharanum", "next month 10", "note panniko", "avan eppa tharuvaan?")
        has(t, "tharuvaar")
        hasNot(t, "kudukkanum")
    }

    // ======================================================================== B. short follow-ups

    @Test
    fun b01_bareDayAsksTheMonth() = has(kai().chat("Ravi enakku 700 tharanum", "15"), "indha maasam 15-aa", "adutha maasam 15-aa")

    @Test
    fun b02_amaAloneReasks() = has(kai().chat("Ravi enakku 700 tharanum", "15", "ama"), "indha maasam 15-aa")

    @Test
    fun b03_amaNextMonth() {
        val k = kai()
        k.chat("Ravi enakku 700 tharanum", "15", "ama next month")
        assertEquals(LocalDate.of(2026, 11, 15), k.stated().dueDate)
    }

    @Test
    fun b04_aLargerNumberCorrectsTheAmount() {
        val k = kai()
        has(k.chat("Ravi enakku 700 tharanum", "900"), "₹900")
        assertEquals(BigDecimal("900.00"), k.stated().amount)
    }

    @Test
    fun b05_sameWithATopicIsAskedAboutIt() =
        assertEquals("Owner, idhu Kumar ₹3,000 payment pathiyaa, illa vera vishayam pathiyaa?", kai().chat("Kumar enakku 3000 tharanum", "same").t)

    @Test
    fun b06_sameWithNothingOpen() = has(kai().say("same"), "edha pathi sollureenga")

    @Test
    fun b07_loneNumberWithNothingOpen() = assertEquals("Owner, 10 — amount-aa, date-aa? Yaar pathi-nu sollunga.", kai().say("10").t)

    @Test
    fun b08_adhuWithNothingOpen() = has(kai().say("adhu low-aa?"), "Product per sollunga")

    @Test
    fun b09_eppaWithNothingOpenAsksWho() = has(kai().say("eppa?"), "yaar pathi")

    @Test
    fun b10_pannitiyaBeforeSaving() = has(kai().chat("Ravi enakku 700 tharanum", "pannitiya?"), "Innum save pannala")

    @Test
    fun b11_noWithATopicIsAskedNotGuessed() {
        val k = kai()
        has(k.chat("Kumar enakku 3000 tharanum", "no"), "Kumar ₹3,000 payment pathiyaa")
        assertNotNull(k.conversationState.stated)
    }

    @Test
    fun b12_noShortFollowUpEverSaysClearAh() {
        for (c in listOf(listOf("same"), listOf("again"), listOf("10"), listOf("adhu low-aa?"), listOf("blah blah xyz"),
            listOf("Kumar enakku 3000 tharanum", "no"), listOf("Kumar enakku 3000 tharanum", "again"), listOf("Colgate stock evlo?", "same"))) {
            neverBlankOrClearAh(kai().chat(*c.toTypedArray()))
        }
    }

    // ======================================================================== C. pronouns

    @Test
    fun c01_avanAfterBalance() = has(kai().chat("Ravi balance evlo?", "avan kitta evlo pending?"), "Ravi", "₹1,500")

    @Test
    fun c02_avanukkuAfterSupplierQuestion() = has(kai().chat("Ramesh-ku evlo kudukkanum?", "avanukku eppa kudukkanum?"), "Ramesh")

    @Test
    fun c03_avangaKitta() = has(kai().chat("Selvi balance evlo?", "avanga kitta eppa vaanganum?"), "Selvi")

    @Test
    fun c04_andhaAal() = has(kai().chat("Kumar balance evlo?", "andha aal eppa tharuvaan?"), "Kumar")

    @Test
    fun c05_samePerson() = has(kai().chat("Basha-ku evlo kudukkanum?", "same person-ku eppa due?"), "Basha")

    @Test
    fun c06_twoPeopleAsk() = assertEquals("Ravi-aa Selvi-aa Owner?", kai().chat("Ravi and Selvi rendu perum pending", "avan eppa?").t)

    @Test
    fun c07_twoPeopleAnswered() = has(kai().chat("Ravi and Selvi rendu perum pending", "avan eppa?", "Selvi"), "Selvi")

    @Test
    fun c08_latestNamedWins() = has(kai().chat("Kumar enakku 3000 tharanum", "Ramesh-ku 500 tharanum", "avan eppa?"), "Ramesh-ku ₹500")

    @Test
    fun c09_avanukkuCall() {
        val t = kai().chat("Kumar enakku 3000 tharanum", "avanukku call pannu")
        has(t, "Kumar-ku call")
    }

    @Test
    fun c10_avanWithNobody() {
        val t = kai().say("avan eppa tharuvaan?")
        assertNull(t.plan)
        hasNot(t, "Kumar", "Ravi")
    }

    // ======================================================================== D/E/F/G/H. entities

    @Test
    fun e01_threeLokeshQuestionText() =
        assertEquals("Owner, Lokesh-nu moonu records irukku. Chennai Lokesh-aa, Nagapattinam Lokesh-aa, illa Trichy Lokesh-aa?", kai().say("Lokesh-ku 500 tharanum").t)

    @Test
    fun e02_pickByOrdinal() {
        val k = kai()
        k.chat("Lokesh-ku 500 tharanum", "rendavadhu")
        assertEquals("l2", k.stated().partyId)
    }

    @Test
    fun e03_pickByPhone() {
        val k = kai()
        k.chat("Lokesh-ku 500 tharanum", "9876543210")
        assertEquals("l1", k.stated().partyId)
    }

    @Test
    fun e04_draftUsesTheResolvedId() {
        val k = kai()
        val d = k.chat("Lokesh Trichy-ku 300 tharanum", "save panniko")
        assertEquals("l3", d.plan!!.partyId)
        assertEquals("Trichy Lokesh — ₹300", d.card!!.lines.first())
    }

    @Test
    fun e05_contextualEntity() {
        val k = kai()
        has(k.say("Nagapattinam Lokesh pathi pesuren."), "Nagapattinam Lokesh")
        k.say("avanukku 500 tharanum.")
        assertEquals("l2", k.stated().partyId)
    }

    @Test
    fun e06_ambiguousAboutThenPick() {
        val k = kai()
        has(k.say("Lokesh pathi pesuren"), "moonu records")
        has(k.say("Chennai"), "Chennai Lokesh")
        k.say("avanukku 250 kudukkanum")
        assertEquals("l1", k.stated().partyId)
    }

    @Test
    fun e07_kumaranIsNotKumar() {
        val k = kai()
        k.say("Kumaran enakku 3000 tharanum")
        assertNull(k.stated().partyId)
        assertEquals("Owner, Kumaran-nu separate customer-aa? Kumar-a?", k.say("save panniko").t)
    }

    @Test
    fun e08_fuzzyNeverResolves() {
        val k = kai()
        k.say("Lokeshwaran-ku 500 tharanum")
        assertNull(k.stated().partyId)
    }

    @Test
    fun e09_otherBusinessNeverSelected() {
        val other = Db().apply { parties.removeAll { it.match.name == "Lokesh" } }
        val k = agentOver(other, Tools(other))
        k.say("Lokesh Nagapattinam-ku 500 tharanum")
        assertNull(k.conversationState.stated!!.partyId)
    }

    @Test
    fun e10_currentNameOverridesStaleContext() {
        val k = kai()
        k.say("Kumar")
        k.say("Ramesh-ku 500 tharanum")
        assertEquals("Ramesh", k.stated().person)
        assertEquals(OUT, k.stated().direction)
    }

    // ======================================================================== I. payment direction

    @Test
    fun i01_receivableVariants() {
        for (s in listOf("Kumar enaku 3k tharanum", "Kumar enakku 3000 kudukanum", "Kumar kitta irundhu 3000 vanganu", "Kumar enakku 3000 tharan", "Kumar enakku moonu aayiram tharanum")) {
            val k = kai()
            k.say(s)
            assertEquals(s, IN, k.stated().direction)
            assertEquals(s, BigDecimal("3000.00"), k.stated().amount)
        }
    }

    @Test
    fun i02_payableVariants() {
        for (s in listOf("naan Kumar-ku 3000 kudukanum", "Kumar-ku 3000 pay pannanum", "naan Kumar-ku 3000 tharanum", "Kumar-ku 3000 kudukkanum")) {
            val k = kai()
            k.say(s)
            assertEquals(s, OUT, k.stated().direction)
        }
    }

    // ======================================================================== L/M/N/O/P/Q. confirm, save, cancel, edit, persistence

    @Test
    fun l01_confirmWordSavesOnlyTheShownDraft() {
        val k = kai()
        k.chat("Ravi enakku 700 tharanum", "save panniko")
        nothingWritten()
        k.say("ok")
        assertEquals(1, db.saved.size)
    }

    @Test
    fun l02_staleAmaDoesNotSave() {
        val k = kai()
        k.chat("Ravi enakku 700 tharanum", "save panniko", "saptiya?", "ama")
        nothingWritten()
    }

    @Test
    fun m01_saveFailureIsSaid() {
        val k = kai()
        db.failSave = true
        has(k.chat("Ravi enakku 700 tharanum", "save panniko", "seri"), "save aagala. Naan amount-a save pannala")
        hasNot(k.say("add pannitiya?"), "pannitten")
    }

    @Test
    fun m02_savedReplyOnlyAfterDone() {
        val k = kai()
        hasNot(k.chat("Ravi enakku 700 tharanum", "save panniko"), "aagiduchu", "pannitten")
        has(k.say("seri"), "Save aagiduchu")
        has(k.say("add pannitiya?"), "add pannitten")
    }

    @Test
    fun n01_cancel() {
        val k = kai()
        k.chat("Ravi enakku 700 tharanum", "save panniko", "venam")
        nothingWritten()
        assertNull(k.conversationState.stated)
    }

    @Test
    fun o01_editThenSave() {
        val k = kai()
        k.chat("Kumar enakku 3000 tharanum", "next month 10", "save panniko", "3500", "seri")
        assertEquals(BigDecimal("3500.00"), db.saved.single().amount)
        assertEquals(LocalDate.of(2026, 11, 10), db.saved.single().dueDate)
    }

    @Test
    fun q01_businessBrainAfterSave() {
        val k = kai()
        k.chat("Kumar enakku 3000 tharanum", "save panniko", "seri")
        has(k.say("Kumar balance evlo?"), "₹6,000")
    }

    @Test
    fun q02_noDuplicateOnRepeat() {
        val k = kai()
        k.chat("Ravi enakku 700 tharanum", "save panniko", "seri", "save panniko", "save pannu")
        assertEquals(1, db.saved.size)
    }

    // ======================================================================== R/S. calculator, stock

    @Test
    fun r01_gstSumThenReturn() {
        val k = kai()
        k.say("Selvi enakku 1200 tharanum")
        k.say("25000 la 18% GST evlo?")
        has(k.say("avanga eppa tharuvaanga?"), "Selvi")
    }

    @Test
    fun s01_shelfCountMatches() = assertEquals("Seri Owner, records-la-um Rice 50 KG dhaan irukku.", kai().say("Rice 50 kg irukku").t)

    @Test
    fun s02_shelfCountDiffersNoWrite() {
        val k = kai()
        has(k.say("Sugar 12 kg irukku"), "records-la Sugar 8 KG irukku")
        nothingWritten()
    }

    @Test
    fun s03_lowCheckFollowsTheLastProduct() {
        val k = kai()
        k.say("Sugar stock evlo?")
        has(k.say("adhu low-aa?"), "Sugar", "keezha")
    }

    @Test
    fun s04_idhulaStockOut() {
        val k = kai()
        k.say("Rice stock evlo?")
        has(k.say("idhula 5 kg pochu"), "Rice", "stock-out")
        nothingWritten()
    }

    @Test
    fun s05_stockInThenAthula() {
        val k = kai()
        has(k.say("Tea Powder 2 box vandhudhu"), "Tea Powder")
        has(k.say("athula 3 pochu"), "Tea Powder")
    }

    // ======================================================================== T/U/V. customer, supplier, reminder

    @Test
    fun t01c_customerBalance() = has(kai().say("Selvi balance evlo?"), "₹4,200")

    @Test
    fun u01_supplierPayable() = has(kai().say("Basha-ku evlo kudukkanum?"), "₹9,000")

    @Test
    fun u02_supplierStatedPayable() {
        val k = kai()
        k.say("Basha Vellore-ku 1500 kudukkanum")
        assertEquals("s2", k.stated().partyId)
        assertEquals(OUT, k.stated().direction)
    }

    @Test
    fun v01_reminderIsAReminderNotAPayment() {
        val t = kai().say("Kumar-ku payment remind pannu")
        has(t, "remind")
        assertNull(t.plan)
    }

    @Test
    fun v02_paymentWhenIsALookup() = has(kai().say("Kumar-ku payment eppa?"), "October 20")

    // ======================================================================== W. owner memory

    @Test
    fun w01_taughtUnitUsed() {
        val k = kai(Access(InMemoryKaiMemoryStore(), "biz-A", "owner-A", Tools(Db())))
        k.say("potti na box")
        k.say("aama")
        has(k.say("Colgate 2 potti vandhudhu"), "2 boxes = 24 pieces")
    }

    @Test
    fun w02_memoryIsPerBusiness() = runBlocking {
        val store = InMemoryKaiMemoryStore()
        val a = Access(store, "biz-A", "owner-A", Tools(Db()))
        val k = kai(a)
        k.say("potti na box")
        k.say("aama")
        val b = Access(store, "biz-B", "owner-B", Tools(Db()))
        assertNull(b.current().find("potti"))
        assertNotNull(a.current().find("potti"))
    }

    @Test
    fun w03_contextClearsOnBusinessChange() {
        val access = Access(InMemoryKaiMemoryStore(), "biz-A", "owner-A", Tools(Db()))
        val k = kai(access)
        k.say("Kumar enakku 3000 tharanum")
        access.business = "biz-B"
        k.say("saptiya?")
        assertNull(k.conversationState.stated)
        assertNull(k.conversationState.lastPerson)
    }

    // ======================================================================== X/Y/Z/AA/AB. languages, STT

    @Test
    fun x01_tamilStatement() {
        val k = kai()
        has(k.say("குமார் எனக்கு 3000 தரணும்"), "₹3,000")
        assertEquals(IN, k.stated().direction)
    }

    @Test
    fun x02_tamilFollowUpStaysTamil() = has(kai().chat("குமார் எனக்கு 3000 தரணும்", "எப்போ?"), "தரணும்")

    @Test
    fun y01_tanglishPayable() {
        val k = kai()
        has(k.say("naan Selvi-ku 450 kudukkanum"), "Selvi-ku ₹450")
    }

    @Test
    fun z01_englishOwesMe() {
        val k = kai()
        k.say("Arun owes me 1200")
        assertEquals(IN, k.stated().direction)
        assertEquals("Arun", k.stated().person)
    }

    @Test
    fun aa01_mixed() {
        val k = kai()
        k.say("Ravi enakku 2500 rupees tharanum next week")
        assertEquals(BigDecimal("2500.00"), k.stated().amount)
    }

    @Test
    fun ab01_textAndVoiceSameState() {
        val typed = kai().also { it.say("Kumar enakku 3000 tharanum") }.stated()
        val spoken = kai().also { it.say("kumar enakku moonu aayiram tharanum") }.stated()
        assertEquals(typed.amount, spoken.amount)
        assertEquals(typed.direction, spoken.direction)
        assertEquals(typed.person.lowercase(), spoken.person.lowercase())
    }

    @Test
    fun ab02_clippedVerb() {
        val k = kai()
        k.say("Selvi kitta 900 vangan")
        assertEquals(IN, k.stated().direction)
    }

    // ======================================================================== AC. casual

    @Test
    fun ac01_casualAnswersAndKeepsTheThread() {
        val k = kai()
        k.say("Kumar enakku 3000 tharanum")
        for (c in listOf("saptiya?", "saptia?", "saptya?", "saaptiya?", "un peru enna?", "un name enna?", "nee yaaru?", "epdi iruka?", "tired ah iruka?", "super Kai", "thanks Kai", "hmm")) {
            val t = k.say(c)
            neverBlankOrClearAh(t)
            assertNull(c, t.plan)
        }
        has(k.say("eppa?"), "Kumar", "₹3,000")
    }

    // ======================================================================== AD/AE. topic switch, return

    @Test
    fun ad01_bareEppaAfterStockDoesNotGuess() = hasNot(kai().chat("Kumar enakku 3000 tharanum", "Colgate stock evlo?", "eppa?"), "Kumar ₹3,000")

    @Test
    fun ae01_returnWithPaymentWord() = has(kai().chat("Kumar enakku 3000 tharanum", "Colgate stock evlo?", "Kumar payment eppa?"), "neenga sonna Kumar ₹3,000")

    @Test
    fun ae02_returnAfterDateWasGiven() =
        has(kai().chat("Ravi enakku 700 tharanum", "next month 5", "Rice stock evlo?", "Ravi eppa tharuvaan?"), "neenga sonna Ravi ₹700 adutha maasam 5-m thethi tharuvaar", "Innum save pannala")

    // ======================================================================== AF/AG/AH. ambiguity, no hallucination, isolation

    @Test
    fun af01_unknownNeverBlank() = neverBlankOrClearAh(kai().say("blah blah xyz"))

    @Test
    fun ag01_noInventedSales() = hasNot(kai().say("innaikku sales evlo?"), "₹")

    @Test
    fun ag02_unknownPersonHasNoRecord() = assertEquals("Owner, Mahesh-nu customer record enakku kidaikala.", kai().say("Mahesh enakku evlo tharanum?").t)

    @Test
    fun ag03_noAmountNoDraft() {
        val k = kai()
        has(k.chat("Ravi-ku cash kudukanum", "save panniko"), "evlo kudukkanum")
        assertTrue(tools.prepared.isEmpty())
    }

    @Test
    fun ah01_otherBusinessKeepsNothing() {
        val access = Access(InMemoryKaiMemoryStore(), "biz-A", "owner-A", Tools(Db()))
        val k = kai(access)
        k.chat("Kumar balance evlo?")
        access.business = "biz-B"
        val t = k.say("avan eppa tharuvaan?")
        hasNot(t, "Kumar")
    }

    // ======================================================================== GENERALISED SCENARIOS (other people / products / phrasing)

    @Test
    fun g01_priyaTwoTownsAsked() =
        assertEquals("Owner, Priya-nu rendu records irukku. Salem Priya-aa illa Erode Priya-aa?", kai().say("Priya enakku 400 tharanum").t)

    @Test
    fun g02_priyaByTown() {
        val k = kai()
        k.say("Erode Priya enakku 400 tharanum")
        assertEquals("c6", k.stated().partyId)
        assertEquals(IN, k.stated().direction)
    }

    @Test
    fun g03_priyaByShop() {
        val k = kai()
        k.say("Priya Textiles enakku 400 tharanum")
        assertEquals("c6", k.stated().partyId)
    }

    @Test
    fun g04_priyaByPhone() {
        val k = kai()
        k.say("Priya 9444411111 enakku 400 tharanum")
        assertEquals("c5", k.stated().partyId)
    }

    @Test
    fun g05_priyaPickedThenSaved() {
        val k = kai()
        k.chat("Priya enakku 400 tharanum", "Salem", "save panniko", "seri")
        assertEquals("c5", db.saved.single().partyId)
        assertEquals(BigDecimal("1200.00"), db.parties.first { it.match.id == "c5" }.pending)
    }

    @Test
    fun g06_priyaContextThenAvalukku() {
        val k = kai()
        k.say("Erode Priya pathi pesuren")
        k.say("avalukku 200 kudukkanum")
        assertEquals("c6", k.stated().partyId)
        assertEquals(OUT, k.stated().direction)
    }

    @Test
    fun g07_selviFullThread() {
        val k = kai()
        k.say("Selvi enakku 1200 tharanum")
        k.say("eppa?")
        k.say("20")
        k.say("indha month")
        has(k.say("avanga eppa tharuvaanga?"), "Selvi", "₹1,200", "indha maasam 20-m thethi")
    }

    @Test
    fun g08_arunPayableThread() {
        val k = kai()
        k.say("naan Arun-ku 650 tharanum")
        k.say("next month 3")
        has(k.say("avanukku eppa kudukkanum?"), "Arun-ku ₹650", "adutha maasam 3-m thethi", "kudukkanum")
    }

    @Test
    fun g09_bashaSmallTalkThenEppa() = has(kai().chat("naan Basha-ku 1500 kudukkanum", "epdi iruka?", "eppa?"), "Basha-ku ₹1,500")

    @Test
    fun g10_raviCalculatorThenAvan() {
        val k = kai()
        k.say("Ravi enakku 2200 tharanum")
        k.say("450 times 3")
        has(k.say("avan eppa tharuvaan?"), "Ravi", "₹2,200")
    }

    @Test
    fun g11_sugarLow() = has(kai().chat("Sugar stock evlo?", "adhu low-aa?"), "Sugar", "keezha")

    @Test
    fun g12_riceNotLow() = has(kai().chat("Rice stock evlo?", "adhu low-aa?"), "Rice", "mela")

    @Test
    fun g13_switchFromSelviToSugar() {
        val t = kai().chat("Selvi enakku 1200 tharanum", "Sugar stock evlo?", "adhu low-aa?")
        has(t, "Sugar")
        hasNot(t, "Selvi")
    }

    @Test
    fun g14_returnToSelvi() = has(kai().chat("Selvi enakku 1200 tharanum", "Sugar stock evlo?", "Selvi eppa tharuvaanga?"), "neenga sonna Selvi ₹1,200")

    @Test
    fun g15_returnRecordsNoteForSelvi() =
        has(kai().chat("Selvi enakku 1200 tharanum", "Sugar stock evlo?", "Selvi eppa tharuvaanga?"), "Records-la Selvi ₹4,200 October 12th due already irukku")

    @Test
    fun g16_arunHasNoRecordsNote() =
        hasNot(kai().chat("Arun enakku 500 tharanum", "Rice stock evlo?", "Arun eppa tharuvaan?"), "Records-la")

    @Test
    fun g17_piecesKarthik() {
        val k = kai()
        k.say("Karthik")
        k.say("1800")
        has(k.say("naan kudukkanum"), "Karthik-ku ₹1,800")
        assertEquals(OUT, k.stated().direction)
    }

    @Test
    fun g18_piecesWithKnownCustomer() {
        val k = kai()
        k.say("Ravi")
        k.say("650")
        k.say("enakku tharanum")
        assertEquals("Ravi", k.stated().person)
        assertEquals(BigDecimal("650.00"), k.stated().amount)
    }

    @Test
    fun g19_twoSuppliersThenAvan() = assertEquals("Ramesh-aa Basha-aa Owner?", kai().chat("Ramesh and Basha rendu perum payment pending", "avanukku eppa?").t)

    @Test
    fun g20_twoSuppliersAnswered() = has(kai().chat("Ramesh and Basha rendu perum payment pending", "avanukku eppa?", "Basha"), "Basha")

    @Test
    fun g21_severalPeopleBalances() = has(kai().say("Selvi and Basha rendu perum balance evlo?"), "Selvi ₹4,200", "Basha-ku ₹9,000")

    @Test
    fun g22_saveSelviWithDate() {
        val k = kai()
        k.chat("Selvi enakku 1200 tharanum", "next month 20", "kanakkula podu", "correct")
        assertEquals(LocalDate.of(2026, 11, 20), db.saved.single().dueDate)
        assertEquals("c3", db.saved.single().partyId)
    }

    @Test
    fun g23_cancelBashaDraft() {
        val k = kai()
        k.chat("naan Basha-ku 1500 kudukkanum", "save pannu", "cancel")
        nothingWritten()
    }

    @Test
    fun g24_editArun() {
        val k = kai()
        k.chat("Arun enakku 500 tharanum", "save panniko", "650", "yes")
        assertEquals(BigDecimal("650.00"), db.saved.single().amount)
    }

    @Test
    fun g25_raviSavedThenBrain() {
        val k = kai()
        k.chat("Ravi enakku 700 tharanum", "save panniko", "seri")
        has(k.say("Ravi balance evlo?"), "₹2,200")
    }

    @Test
    fun g26_newCustomerSavedAndFoundAfterRestart() {
        val k = kai()
        k.chat("Dinesh enakku 300 tharanum", "save panniko", "seri")
        has(agentOver(db, Tools(db)).say("Dinesh enakku evlo tharanum?"), "₹300")
    }

    @Test
    fun g27_englishAmbiguity() = assertEquals("Owner, there are 2 records named Priya. Salem Priya or Erode Priya?",
        KaiEntityResolver.question("Priya", db.parties.filter { it.match.name == "Priya" }.map { it.match }, KaiLang.ENGLISH))

    @Test
    fun g28_tamilAmbiguity() = assertTrue(KaiEntityResolver.question("Priya", db.parties.filter { it.match.name == "Priya" }.map { it.match }, KaiLang.TAMIL).contains("records இருக்கு"))

    @Test
    fun g29_tamilScriptReceivableOtherName() {
        val k = kai()
        k.say("செல்வி எனக்கு 1200 தரணும்")
        assertEquals(IN, k.stated().direction)
        assertEquals(BigDecimal("1200.00"), k.stated().amount)
    }

    @Test
    fun g30_englishPayable() {
        val k = kai()
        k.say("I owe Ramesh 800")
        assertEquals(OUT, k.stated().direction)
    }

    @Test
    fun g31_stockStatementThenAdhuOtherProduct() {
        val k = kai()
        k.say("Tea Powder 40 pieces irukku")
        has(k.say("adhu low-aa?"), "Tea Powder")
    }

    @Test
    fun g32_samePhraseDifferentProduct() {
        val k = kai()
        k.say("Sugar stock evlo?")
        has(k.say("same"), "Sugar stock pathiyaa")
    }

    @Test
    fun g33_againWithPaymentTopic() = has(kai().chat("Ravi enakku 700 tharanum", "again"), "Ravi ₹700 payment pathiyaa")

    @Test
    fun g34_loneNumberWithPersonPieceIsAmount() = has(kai().chat("Gopal", "2400"), "Gopal ₹2,400")

    @Test
    fun g35_noReminderCreatedByAQuestion() {
        val k = kai()
        k.chat("Selvi enakku 1200 tharanum", "avanga eppa tharuvaanga?")
        assertTrue(tools.reminders.isEmpty())
    }

    @Test
    fun g36_noDraftFromABalanceQuestion() {
        val k = kai()
        k.say("Selvi enakku evlo tharanum?")
        assertTrue(tools.prepared.isEmpty())
        assertNull(k.conversationState.stated)
    }

    @Test
    fun g37_threeWayTopicChain() {
        val k = kai()
        k.say("Ravi enakku 700 tharanum")
        k.say("Sugar stock evlo?")
        k.say("Selvi balance evlo?")
        has(k.say("avanga eppa tharuvaanga?"), "Selvi")
    }

    @Test
    fun g38_resolvedLabelInDateAnswer() {
        val k = kai()
        k.say("Lokesh Trichy-ku 300 tharanum")
        has(k.say("next month 2"), "Trichy Lokesh-ku ₹300 adutha maasam 2-m thethi")
    }

    @Test
    fun g39_pendingPickSurvivesNothingElse() {
        val k = kai()
        k.say("Priya enakku 400 tharanum")
        k.say("epdi iruka?")
        assertNull(k.conversationState.entityChoice)
        assertNull(k.stated().partyId)
    }

    @Test
    fun g40_savedLabelledEntityKeepsItsId() {
        val k = kai()
        k.chat("Basha Vellore-ku 1500 kudukkanum", "save pannu", "seri")
        assertEquals("s2", db.saved.single().partyId)
        assertEquals(PlanKind.DEBIT_TAKEN, db.saved.single().kind)
    }

    @Test
    fun g41_lowercaseSttNameStatement() {
        val k = kai()
        k.say("selvi enakku 1200 tharanum")
        assertEquals("Selvi", k.stated().person)
        assertEquals("c3", k.stated().partyId)
    }

    @Test
    fun g42_kalpanaUnknownSaysNoRecord() = has(kai().say("Kalpana balance evlo?"), "kidaikala")
}
