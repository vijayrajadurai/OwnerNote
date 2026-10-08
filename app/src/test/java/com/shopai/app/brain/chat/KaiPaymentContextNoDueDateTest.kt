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
 * Two phone conversations:
 *  1. "Selvam enaku already 3000 tharanum ippa oru 2000 tharanum" — a NEW ₹2,000 entry on top of the ₹3,000 already
 *     in the books (never an edit, never a replacement); total ₹5,000 after Confirm.
 *  2. "selvam 5000 tharanum" … "Due date eppa?" … "due venam" — the same ₹5,000 draft with no due date; never
 *     "account clear", never cancelled, saved (still pending) only on Confirm.
 * The books keep one row per entry, so the existing entry can be checked as untouched.
 */
class KaiPaymentContextNoDueDateTest {
    // Thursday 8 October 2026, 11 AM.
    private val now = LocalDateTime.of(2026, 10, 8, 11, 0)

    /** One accounting entry: a Credit (customer owes the owner) or a Debit (owner owes the supplier). */
    data class Entry(val id: String, val partyId: String, val amount: BigDecimal, val dueDate: LocalDate?, val paid: Boolean = false)
    data class Party(val id: String, val name: String, val customer: Boolean)

    class Db(selvamOwes: String? = "3000.00") {
        val parties = mutableListOf(Party("c-selvam", "Selvam", true), Party("c-kumar", "Kumar", true), Party("s-ramesh", "Ramesh", false))
        val entries = mutableListOf(Entry("e-kumar", "c-kumar", BigDecimal("1000.00"), null), Entry("e-ramesh", "s-ramesh", BigDecimal("2500.00"), null))
            .apply { if (selvamOwes != null) add(Entry("e-selvam-old", "c-selvam", BigDecimal(selvamOwes), LocalDate.of(2026, 10, 1))) }
        fun pending(partyId: String) = entries.filter { it.partyId == partyId && !it.paid }.fold(BigDecimal.ZERO) { a, e -> a + e.amount }
        fun summary(p: Party) = PartySummary(p.id, p.name, null, pending(p.id).toDouble(),
            entries.filter { it.partyId == p.id && !it.paid }.mapNotNull { it.dueDate }.minOrNull()?.let { "${it}T00:00:00Z" })
    }

    private class Books(val db: Db) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = db.parties.filter { it.customer }.map(db::summary),
            suppliers = db.parties.filter { !it.customer }.map(db::summary),
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(val db: Db) : KaiTools {
        val prepared = mutableListOf<ActionPlan>()
        val discarded = mutableListOf<ActionPlan>()
        override suspend fun parties(name: String) = db.parties.filter { it.name.contains(name, true) }
            .map { PartyMatch(it.id, it.name, it.customer, null, db.pending(it.id)) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            ActionPlan("p${prepared.size}", kind, partyName, partyId, amount, mode, said = said).also { prepared += it }
        /** Like createCredit / createDebit: one new entry per Confirm; existing entries are never touched. */
        override suspend fun confirm(plan: ActionPlan): ActionOutcome {
            val party = db.parties.firstOrNull { it.id == plan.partyId }
                ?: Party("n-${plan.partyName.lowercase()}", plan.partyName, plan.kind == PlanKind.CREDIT_GIVEN).also { db.parties += it }
            db.entries += Entry("e${db.entries.size + 1}", party.id, plan.amount, plan.dueDate)
            return ActionOutcome.Done("TXN-${db.entries.size}", db.pending(party.id))
        }
        override suspend fun discard(plan: ActionPlan) { discarded += plan }
        override fun zone() = "Asia/Kolkata"
        override suspend fun contacts(name: String, role: PartyRole?) = emptyList<ContactMatch>()
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?) = "K-1"
    }

    private var db = Db()
    private var tools = Tools(db)
    private fun kai(d: Db = Db()): KaiAgent {
        db = d; tools = Tools(d)
        return restart()
    }
    /** The app restarted: a new Kai over the same books. */
    private fun restart() = KaiAgent(KaiBusinessBrain(Books(db), today = { now.toLocalDate() }, random = Random(1)), Books(db), tools, now = { now })
    private fun KaiAgent.say(text: String) = runBlocking { ask(text) }
    private fun selvamEntries() = db.entries.filter { it.partyId == "c-selvam" }
    private fun has(t: KaiTurn, vararg parts: String) = parts.forEach { assertTrue("'$it' in: ${t.reply.text}", t.reply.text.contains(it)) }
    private fun neverClearedOrCancelled(t: KaiTurn) {
        for (w in listOf("clear", "pending illa", "Pending illa", "pending edhuvum illa", "completed", "cancel pannitten", "Edhuvum save aagala", "paid"))
            assertFalse("'$w' must not be in: ${t.reply.text}", t.reply.text.contains(w))
    }
    private val oldSelvam = Entry("e-selvam-old", "c-selvam", BigDecimal("3000.00"), LocalDate.of(2026, 10, 1))

    // ================================================================ A. an amount already owed + a NEW amount

    @Test
    fun a1_theNewAmountIsDraftedAndTheOldOneIsLeftAlone() {
        val k = kai()
        val t = k.say("Selvam enaku already 3000 tharanum ippa oru 2000 tharanum")
        val plan = t.plan!!
        assertEquals(BigDecimal("2000.00"), plan.amount)
        assertEquals(PlanKind.CREDIT_GIVEN, plan.kind)
        assertEquals("c-selvam", plan.partyId)
        assertNull("due date only if said", plan.dueDate)
        has(t, "already irukkura ₹3,000 apdiye irukkum", "pudhu ₹2,000 entry", "mothama ₹5,000")
        val lines = t.card!!.lines
        assertTrue(lines.toString(), lines.contains("Pudhu entry: ₹2,000 (pazhaya ₹3,000 maaraadhu)"))
        assertTrue(lines.toString(), lines.contains("Balance: ₹3,000 → ₹5,000"))
        assertEquals("not saved before Confirm", listOf(oldSelvam), selvamEntries())
    }

    @Test
    fun a2_confirmAddsOneSeparateEntry() {
        val k = kai()
        k.say("Selvam enaku already 3000 tharanum ippa oru 2000 tharanum")
        has(k.say("ama"), "Save aagiduchu Owner. Selvam — ₹2,000", "Ippo balance ₹5,000")
        val rows = selvamEntries()
        assertEquals(2, rows.size)
        assertEquals("the existing entry is untouched", oldSelvam, rows.first())
        assertEquals(BigDecimal("2000.00"), rows.last().amount)
        assertTrue(rows.first().id != rows.last().id)
        assertEquals(BigDecimal("5000.00"), db.pending("c-selvam"))
    }

    @Test
    fun a3_theBalanceQuestionReadsTheTotal() {
        val k = kai()
        k.say("Selvam enaku already 3000 tharanum ippa oru 2000 tharanum")
        k.say("ama")
        has(k.say("Selvam enaku evlo tharanum?"), "₹5,000")
    }

    @Test
    fun a4_survivesARestart() {
        val k = kai()
        k.say("Selvam enaku already 3000 tharanum ippa oru 2000 tharanum")
        k.say("ama")
        has(restart().say("Selvam enaku evlo tharanum?"), "₹5,000")
        assertEquals(2, selvamEntries().size)
    }

    @Test
    fun a5_everyWayOfSayingItIsANewTwoThousand() {
        for (s in listOf(
            "Selvam enaku already 3000 tharanum ippa oru 2000 tharanum",
            "Selvam already 3000 tharanum, ippo 2000 tharanum",
            "Selvam kitta 3000 pending irukku, innum 2000 tharanum",
            "Selvam enakku already 3000 pending, ippo 2000 kudukkanum",
            "Selvam 3000 tharanum, innoru 2000 tharanum",
        )) {
            val t = kai().say(s)
            assertEquals(s, BigDecimal("2000.00"), t.plan?.amount)
            assertEquals(s, PlanKind.CREDIT_GIVEN, t.plan?.kind)
            assertTrue(s, t.card!!.lines.contains("Balance: ₹3,000 → ₹5,000"))
            assertEquals(s, listOf(oldSelvam), selvamEntries())
        }
    }

    @Test
    fun a6_noDuplicateOnASecondSave() {
        val k = kai()
        k.say("Selvam enaku already 3000 tharanum ippa oru 2000 tharanum")
        k.say("ama")
        k.say("save panniko")
        k.say("save pannu")
        assertEquals(2, selvamEntries().size)
    }

    @Test
    fun a7_whenTheBooksDisagreeKaiSaysSoAndOnlyAddsTheNewAmount() {
        val k = kai(Db(selvamOwes = null))
        val t = k.say("Selvam enaku already 3000 tharanum ippa oru 2000 tharanum")
        has(t, "Records-la Selvam pending ₹0 dhaan irukku", "neenga sonna ₹3,000 illa", "pudhu ₹2,000 entry")
        assertEquals(BigDecimal("2000.00"), t.plan!!.amount)
        k.say("ama")
        assertEquals(listOf(BigDecimal("2000.00")), selvamEntries().map { it.amount })
    }

    @Test
    fun a8_payableOnTopOfAPayable() {
        val k = kai()
        val t = k.say("naan Ramesh-ku already 2500 tharanum, ippo 1000 kudukkanum")
        assertEquals(PlanKind.DEBIT_TAKEN, t.plan!!.kind)
        assertEquals(BigDecimal("1000.00"), t.plan.amount)
        has(t, "mothama ₹3,500 kudukkanum")
    }

    @Test
    fun a9_aDateInTheSentenceGoesOnTheNewEntry() {
        val k = kai()
        val t = k.say("Selvam already 3000 tharanum, ippo 2000 tharanum next month 10-ku")
        assertEquals(BigDecimal("2000.00"), t.plan!!.amount)
        assertEquals(LocalDate.of(2026, 11, 10), t.plan.dueDate)
    }

    @Test
    fun a10_aQuestionWithTwoNumbersIsNotAnEntry() {
        val k = kai()
        val t = k.say("Selvam already 3000 tharanum, ippo 2000 tharanum-aa?")
        assertNull(t.plan)
        assertTrue(tools.prepared.isEmpty())
    }

    // ================================================================ B. "due venam" keeps the same payment

    @Test
    fun b1_dueVenamKeepsTheDraftWithoutADate() {
        val k = kai(Db(selvamOwes = "0.00"))
        has(k.say("selvam 5000 tharanum"), "Selvam", "₹5,000", "Due date eppa")
        val t = k.say("due venam")
        assertEquals("Seri Owner 👍 Due date illa. Selvam kitta ₹5,000 collect panna vendiyadhu. Save pannava?", t.reply.text)
        neverClearedOrCancelled(t)
        val plan = t.plan!!
        assertEquals(BigDecimal("5000.00"), plan.amount)
        assertEquals(PlanKind.CREDIT_GIVEN, plan.kind)
        assertNull(plan.dueDate)
        assertEquals(KaiConversationPaymentDirection.PAYMENT_IN, k.conversationState.stated!!.direction)
        assertNull(k.conversationState.stated!!.dueDate)
        assertTrue(t.card!!.lines.contains("Due date: illa"))
        assertTrue("nothing saved yet", selvamEntries().all { it.amount.signum() == 0 })
    }

    @Test
    fun b2_amaSavesItPendingWithNoDueDate() {
        val k = kai(Db(selvamOwes = null))
        k.say("selvam 5000 tharanum")
        k.say("due venam")
        has(k.say("ama"), "Save aagiduchu Owner. Selvam — ₹5,000")
        val row = selvamEntries().single()
        assertEquals(BigDecimal("5000.00"), row.amount)
        assertNull(row.dueDate)
        assertFalse("still outstanding", row.paid)
    }

    @Test
    fun b3_theBalanceAfterwardsIsFiveThousand() {
        val k = kai(Db(selvamOwes = null))
        k.say("selvam 5000 tharanum")
        k.say("due venam")
        k.say("ama")
        has(k.say("Selvam enaku evlo tharanum?"), "₹5,000")
        has(restart().say("Selvam enaku evlo tharanum?"), "₹5,000")
    }

    @Test
    fun b4_withAnOlderBalanceTheDraftSaysItIsNew() {
        val k = kai()
        k.say("selvam 5000 tharanum")
        val t = k.say("due venam")
        assertTrue(t.card!!.lines.contains("Balance: ₹3,000 → ₹8,000"))
        k.say("ama")
        assertEquals(2, selvamEntries().size)
        assertEquals(oldSelvam, selvamEntries().first())
    }

    // ================================================================ C. every "no due date" phrase

    @Test
    fun c1_allNoDueDatePhrases() {
        for (p in listOf("due venam", "due vendaam", "due date venam", "due date vendaam", "date venam", "date vendaam", "Due venam Owner", "no due date")) {
            val k = kai(Db(selvamOwes = null))
            k.say("selvam 5000 tharanum")
            val t = k.say(p)
            neverClearedOrCancelled(t)
            assertEquals(p, BigDecimal("5000.00"), t.plan?.amount)
            assertNull(p, t.plan?.dueDate)
            assertNotNull(p, k.conversationState.stated)
        }
    }

    @Test
    fun c2_semantics() {
        val s = KaiConversationSemantics
        for (p in listOf("due venam", "due vendaam", "due date venam", "due date vendaam", "date venam", "date vendaam", "due illa", "no due date", "due date thevai illa"))
            assertTrue(p, s.dropsDueDate(p))
        for (p in listOf("venam", "cancel", "due eppa?", "Selvam due evlo?", "due date 10")) assertFalse(p, s.dropsDueDate(p))
    }

    // ================================================================ D. never cleared, cancelled, paid, deleted, or ₹0

    @Test
    fun d1_dueVenamNeverTouchesTheBooks() {
        val k = kai()
        k.say("selvam 5000 tharanum")
        val t = k.say("due venam")
        neverClearedOrCancelled(t)
        assertTrue("no cancel / discard", tools.discarded.isEmpty())
        assertEquals("no entry changed or deleted", listOf(oldSelvam), selvamEntries())
        assertTrue("no ₹0 draft", tools.prepared.none { it.amount.signum() == 0 })
        assertEquals(BigDecimal("3000.00"), db.pending("c-selvam"))
    }

    @Test
    fun d2_dueVenamWithTheDraftAlreadyOpenDropsOnlyTheDate() {
        val k = kai(Db(selvamOwes = null))
        k.say("selvam 5000 tharanum")
        k.say("next month 10")
        assertEquals(LocalDate.of(2026, 11, 10), k.say("save panniko").plan!!.dueDate)
        val t = k.say("due venam")
        neverClearedOrCancelled(t)
        assertNull(t.plan!!.dueDate)
        assertEquals(BigDecimal("5000.00"), t.plan.amount)
        k.say("ama")
        assertNull(selvamEntries().single().dueDate)
    }

    @Test
    fun d3_aPlainVenamStillCancels() {
        val k = kai(Db(selvamOwes = null))
        k.say("selvam 5000 tharanum")
        k.say("save panniko")
        has(k.say("venam"), "cancel pannitten")
        assertTrue(selvamEntries().isEmpty())
    }

    @Test
    fun d4_theBalanceQuestionIsStillALookup() {
        val k = kai()
        val t = k.say("Selvam enaku evlo tharanum?")
        has(t, "₹3,000")
        assertNull(t.plan)
        assertNull(k.conversationState.stated)
    }

    @Test
    fun d5_noSaveClaimBeforeConfirm() {
        val k = kai(Db(selvamOwes = null))
        for (l in listOf("selvam 5000 tharanum", "due venam"))
            assertFalse(k.say(l).reply.text.let { it.contains("aagiduchu") || it.contains("pannitten") })
        assertTrue(selvamEntries().isEmpty())
    }
}
