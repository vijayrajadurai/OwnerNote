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
 * The owner's "Kai Chat — Complete Test Checklist", line by line, through the same Kai the chat screen (typed and
 * mic) talks to. Each section is one conversation; the books are an in-memory store that Kai's tools write only on
 * Confirm and the Business Brain reads (so "save" and "restart" are checked against what was actually stored).
 */
class KaiChatChecklistTest {
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
        override fun updateReminder(reminder: KaiReminder): ReminderSaved {
            reminders.removeAll { it.id == reminder.id }; reminders += reminder
            return ReminderSaved(reminder, false, ScheduleResult.EXACT)
        }
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
    private var access = Access(InMemoryKaiMemoryStore(), "biz-A", "owner-A", tools)
    private fun kai(): KaiAgent {
        db = Db(); tools = Tools(db); access = Access(InMemoryKaiMemoryStore(), "biz-A", "owner-A", tools)
        return restart()
    }
    /** The app restarted: a new Kai (nothing in memory) over the same books and owner memory. */
    private fun restart() = KaiAgent(KaiBusinessBrain(Books(db), today = { now.toLocalDate() }, random = Random(1)), Books(db), tools, now = { now }, memory = access)
    private fun KaiAgent.say(text: String) = runBlocking { ask(text) }
    private fun has(turn: KaiTurn, vararg parts: String) = parts.forEach { assertTrue("'$it' in: ${turn.reply.text}", turn.reply.text.contains(it)) }
    private fun hasNot(turn: KaiTurn, vararg parts: String) = parts.forEach { assertFalse("'$it' not in: ${turn.reply.text}", turn.reply.text.contains(it)) }
    private fun nothingWritten() {
        assertTrue("saved: ${db.saved}", db.saved.isEmpty())
        assertTrue("stock: ${tools.stockChanges}", tools.stockChanges.isEmpty())
    }

    @Test
    fun s01_casualAndIdentity() {
        val k = kai()
        val lines = listOf("saptiya?", "saptia?", "saptya Kai?", "saaptiya?", "un peru enna?", "un name enna?", "nee yaaru?", "epdi iruka Kai?",
            "dei Kai inniku romba tired ah iruka?", "super Kai", "thanks Kai", "hmm")
        for (l in lines) {
            val t = k.say(l)
            assertTrue(l, t.reply.text.isNotBlank())
            hasNot(t, "clear-ah", "meaning-la")
            assertNull(l, t.plan)
        }
        has(k.say("un peru enna?"), "Kai")
        nothingWritten()
    }

    @Test
    fun s02_paymentDirection() {
        fun dir(s: String) = kai().also { it.say(s) }.conversationState.stated!!
        assertEquals(KaiConversationPaymentDirection.PAYMENT_IN, dir("Kumaran enaku 3000 tharanum").direction)
        assertEquals(KaiConversationPaymentDirection.PAYMENT_OUT, dir("naan Kumaran-ku 3000 tharanum").direction)
        assertEquals(KaiConversationPaymentDirection.PAYMENT_IN, dir("Kumar enakku 3k kudukanum").direction)
        assertEquals(BigDecimal("3000.00"), dir("Kumar enakku 3k kudukanum").amount)
        assertEquals(KaiConversationPaymentDirection.PAYMENT_IN, dir("Kumar kitta irundhu 5000 vanganu").direction)
        assertEquals(KaiConversationPaymentDirection.PAYMENT_OUT, dir("Kumar-ku 2500 kudukkanum").direction)
        assertEquals("Kumaran", dir("Kumaran enaku 3000 tharanum").person)
    }

    @Test
    fun s03_contextAndDate() {
        val k = kai()
        k.say("Kumaran enaku 3000 tharanum")
        has(k.say("eppa?"), "Kumaran", "₹3,000")
        assertEquals("Owner, indha maasam 10-aa, illa adutha maasam 10-aa?", k.say("10").reply.text)
        has(k.say("next month"), "adutha maasam 10-m thethi")
        assertEquals("Owner, Kumaran ₹3,000 adutha maasam 10-m thethi tharuvaar. Innum save pannala Owner.", k.say("avan eppa tharuvaan?").reply.text)
    }

    @Test
    fun s04_dateThenAddPannitiya() {
        val k = kai()
        k.say("Mahesh enaku 2000 tharanum")
        has(k.say("July 6"), "July 6th 2027")
        val t = k.say("add pannitiya?")
        assertEquals("Innum save pannala Owner. Confirm pannunga, save pannidren.", t.reply.text)
        assertEquals(LocalDate.of(2027, 7, 6), t.plan!!.dueDate)
        nothingWritten()
    }

    @Test
    fun s05_saveWithoutConfirmIsNotSaved() {
        val k = kai()
        k.say("Mahesh enaku 2000 tharanum")
        assertNotNull(k.say("save panniko").plan)
        has(k.say("Mahesh enaku evlo tharanum?"), "kidaikala", "innum save aagala — Confirm pannunga")
        nothingWritten()
        has(restart().say("Mahesh enaku evlo tharanum?"), "kidaikala")
    }

    @Test
    fun s05b_saveConfirmedPersistsAcrossRestart() {
        val k = kai()
        k.say("Mahesh enaku 2000 tharanum")
        k.say("save panniko")
        has(k.say("seri"), "Save aagiduchu Owner. Mahesh — ₹2,000")
        assertEquals(BigDecimal("2000.00"), db.saved.single().amount)
        has(k.say("Mahesh enaku evlo tharanum?"), "₹2,000")
        has(restart().say("Mahesh enaku evlo tharanum?"), "₹2,000")
    }

    @Test
    fun s06_casualInterruption() {
        val k = kai()
        k.say("Kumar enaku 3000 tharanum")
        k.say("saptiya?")
        k.say("seri")
        has(k.say("eppa?"), "Kumar", "₹3,000")
    }

    @Test
    fun s07_calculatorInterruption() {
        val k = kai()
        k.say("Kumar enaku 3000 tharanum")
        assertEquals("1,625", k.say("1250 plus 375").reply.text)
        k.say("seri")
        has(k.say("avan eppa tharuvaan?"), "Kumar", "₹3,000")
    }

    @Test
    fun s08_stockProduct() {
        val k = kai()
        has(k.say("Colgate stock evlo?"), "Colgate stock 20 PCS")
        has(k.say("adhu low-aa?"), "Colgate", "keezha")
        val t = k.say("athula 5 pochu")
        has(t, "Colgate — 5 pieces stock-out")
        assertNotNull(t.card)
        nothingWritten()
    }

    @Test
    fun s09_topicSwitchAndReturn() {
        val k = kai()
        k.say("Kumar enaku 3000 tharanum")
        k.say("Colgate stock evlo?")
        val low = k.say("adhu low-aa?")
        has(low, "Colgate")
        hasNot(low, "Kumar")
        has(k.say("Kumar eppa tharuvaan?"), "neenga sonna Kumar ₹3,000", "Records-la Kumar ₹3,000 October 20th due already irukku")
    }

    @Test
    fun s10_duplicateName() {
        val k = kai()
        assertEquals("Owner, Lokesh-nu moonu records irukku. Chennai Lokesh-aa, Nagapattinam Lokesh-aa, illa Trichy Lokesh-aa?", k.say("Lokesh-ku 500 tharanum").reply.text)
        assertTrue(tools.prepared.isEmpty())
        has(k.say("Nagapattinam"), "Nagapattinam Lokesh-ku ₹500")
        assertEquals("l2", k.conversationState.stated!!.partyId)
        val again = kai()
        again.say("Lokesh Nagapattinam-ku 500 tharanum")
        assertEquals("l2", again.conversationState.stated!!.partyId)
    }

    @Test
    fun s11_businessName() {
        val k = kai()
        has(k.say("Lokesh Sri Vinayaga Hardware-ku 500 tharanum"), "Nagapattinam Lokesh-ku ₹500")
        assertEquals("l2", k.conversationState.stated!!.partyId)
    }

    @Test
    fun s12_businessPaymentQueries() {
        val k = kai()
        has(k.say("Innaikku yaar payment tharanum?"), "Innaikku yaarum")
        has(k.say("Innaikku yaarukku payment pannanum?"), "Innaikku yaarukkum")
        has(k.say("yar kitta collection irukku?"), "Selvi ₹4,200", "Kumar ₹3,000")
        has(k.say("yarukku cash kudukanum?"), "Basha ₹9,000", "Ramesh ₹2,500")
        has(k.say("Mahesh enaku evlo tharanum?"), "kidaikala")
        has(k.say("Kumar balance evlo?"), "₹3,000")
        nothingWritten()
    }

    @Test
    fun s13_reminder() {
        val k = kai()
        has(k.say("Kumar-ku 10 minutes kalichi call pannanum"), "Kumar", "call reminder")
        assertTrue("confirm first", tools.reminders.isEmpty())
        has(k.say("ama"), "Done Owner")
        assertEquals(1, tools.reminders.size)
        has(k.say("Time maathu"), "maathanum")
        has(k.say("5 mani"), "5:00 PM")
        assertEquals(1, tools.reminders.size)
    }

    @Test
    fun s14_ownerMemory() {
        val k = kai()
        has(k.say("potti na box"), "potti", "box", "Save pannava")
        // Used before Save: Kai asks to save it first, then reads the sentence with it.
        has(k.say("Colgate 2 potti vandhudhu"), "innum save pannala", "Save pannava")
        val t = k.say("aama")
        has(t, "Inime `potti`", "2 boxes = 24 pieces")
        assertTrue("stock only after Confirm", tools.stockChanges.isEmpty())
    }

    @Test
    fun s15_memoryCorrection() {
        val k = kai()
        k.say("potti na box")
        has(k.say("illa, potti na packet"), "packet", "Save pannava")
        has(k.say("potti"), "packet", "innum save pannala")
    }

    @Test
    fun s15b_memoryCorrectionAfterSaving() {
        val k = kai()
        k.say("potti na box")
        k.say("aama")
        has(k.say("illa, potti na packet"), "packet")
        has(k.say("aama"), "Update panniten: `potti` = packet")
        has(k.say("potti"), "`potti`-na `packet`")
    }

    @Test
    fun s16_tamilScript() {
        val k = kai()
        has(k.say("குமார் எனக்கு 3000 தரணும்"), "₹3,000")
        assertEquals(KaiConversationPaymentDirection.PAYMENT_IN, k.conversationState.stated!!.direction)
        has(k.say("அவன் எப்போ தருவான்?"), "Kumar", "₹3,000")
        has(k.say("அடுத்த மாதம் 10"), "அடுத்த மாதம் 10-ம் தேதி")
        k.say("நான் ரமேஷுக்கு 500 தரணும்")
        assertEquals(KaiConversationPaymentDirection.PAYMENT_OUT, k.conversationState.stated!!.direction)
        assertEquals(BigDecimal("500.00"), k.conversationState.stated!!.amount)
    }

    @Test
    fun s17_mixedTanglish() {
        val k = kai()
        assertEquals("Seri Owner, Kumar kitta irundhu ₹3,000 adutha maasam 10-m thethi vaanganum. Owner, records-la already Kumar ₹3,000 tharanum-nu irukku. Adhey ₹3,000-aa, illa pudhu ₹3,000-aa?",
            k.say("Kumar enakku 3000 tharanum, next month 10-ku").reply.text)
        has(k.say("avanukku remind pannu"), "remind")
        // The books already have Kumar ₹3,000: Kai asks whether this is the same ₹3,000 or a new one.
        has(k.say("seri save panniko"), "Adhey ₹3,000-aa, illa pudhu ₹3,000-aa?")
        val d = k.say("pudhusu")
        assertEquals(BigDecimal("3000.00"), d.plan!!.amount)
        assertEquals(LocalDate.of(2026, 11, 10), d.plan.dueDate)
        nothingWritten()
    }

    @Test
    fun s18_ambiguousReference() {
        val k = kai()
        k.say("Kumar and Ramesh rendu perum payment pending")
        assertEquals("Kumar-aa Ramesh-aa Owner?", k.say("avan eppa tharuvaan?").reply.text)
    }

    @Test
    fun s19_unknownInput() {
        val k = kai()
        val t = k.say("asdf qwerty")
        assertTrue(t.reply.text.isNotBlank())
        hasNot(t, "clear-ah")
        assertNull(t.plan)
        assertNull(t.card)
        nothingWritten()
    }

    @Test
    fun s20_finalStress() {
        val k = kai()
        k.say("Kumar enaku 3000 tharanum")
        k.say("eppa?")
        k.say("10")
        k.say("next month")
        k.say("saptiya?")
        k.say("seri")
        assertEquals("1,625", k.say("1250 plus 375").reply.text)
        has(k.say("avan eppa tharuvaan?"), "Kumar ₹3,000 adutha maasam 10-m thethi")
        has(k.say("Colgate stock evlo?"), "Colgate")
        has(k.say("adhu low-aa?"), "Colgate")
        has(k.say("Lokesh-ku 500 tharanum"), "moonu records")
        has(k.say("Nagapattinam"), "Nagapattinam Lokesh")
        val draft = k.say("note panniko")
        assertEquals("l2", draft.plan!!.partyId)
        assertEquals("Nagapattinam Lokesh — ₹500", draft.card!!.lines.first())
        has(k.say("Mahesh enaku evlo tharanum?"), "kidaikala")
        nothingWritten()
    }
}
