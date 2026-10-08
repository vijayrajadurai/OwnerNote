package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
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
import com.shopai.app.util.parseIsoToLocalDate
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
 * The real bug: "Mahesh enaku 2000 tharanum" … Kai said it was noted, but nothing reached the books.
 * Here the books are one in-memory store that both Kai's tools write (only on Confirm) and the Business
 * Brain / Customers / Collections read — so a save is real only if the next read sees it, and survives a new Kai.
 */
class KaiPaymentSavePersistenceTest {
    // Thursday 8 October 2026, 11 AM.
    private val now = LocalDateTime.of(2026, 10, 8, 11, 0)

    /** One row per Credit (customer owes the owner) / Debit (owner owes the supplier) entry, like the books' documents. */
    data class Entry(val partyId: String, val name: String, val amount: BigDecimal, val customer: Boolean, val dueDate: LocalDate?, val said: String)

    class Db {
        val entries = mutableListOf(Entry("c-kumar", "Kumar", BigDecimal("3000.00"), true, null, "opening"))
        var failConfirm: String? = null
        var booksDown = false
        var problems: List<String> = emptyList()
        fun customers() = entries.filter { it.customer }.groupBy { it.partyId }.map { (id, e) ->
            PartySummary(id, e.first().name, null, e.sumOf { it.amount.toDouble() }, e.mapNotNull { it.dueDate }.minOrNull()?.let { "${it}T00:00:00Z" }) }
        fun suppliers() = entries.filter { !it.customer }.groupBy { it.partyId }.map { (id, e) ->
            PartySummary(id, e.first().name, null, e.sumOf { it.amount.toDouble() }, e.mapNotNull { it.dueDate }.minOrNull()?.let { "${it}T00:00:00Z" }) }
    }

    /** What the Business Brain, Customers, Collections and Home read. */
    private class Books(val db: Db) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = db.customers(), suppliers = db.suppliers())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    /** Kai's tools over the same store: prepare writes nothing; confirm writes exactly one entry (like AppKaiTools → createCredit / createDebit). */
    private class Tools(val db: Db) : KaiTools {
        val prepared = mutableListOf<ActionPlan>()
        val confirms = mutableListOf<ActionPlan>()
        val discarded = mutableListOf<ActionPlan>()
        override suspend fun parties(name: String): List<PartyMatch>? = if (db.booksDown) null else
            (db.customers().map { it to true } + db.suppliers().map { it to false }).filter { it.first.name.contains(name, true) }
                .map { (p, c) -> PartyMatch(p.id, p.name, c, null, BigDecimal(p.pendingTotal).setScale(2)) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String): ActionPlan =
            ActionPlan("p${prepared.size}", kind, partyName, partyId, amount, mode, said = said, problems = db.problems).also { prepared += it }
        override suspend fun confirm(plan: ActionPlan): ActionOutcome {
            confirms += plan
            db.failConfirm?.let { return ActionOutcome.Failed(it) }
            val customer = plan.kind == PlanKind.CREDIT_GIVEN
            val id = plan.partyId ?: ((if (customer) "c-" else "s-") + plan.partyName.lowercase())
            db.entries += Entry(id, plan.partyName, plan.amount, customer, plan.dueDate, plan.said)
            return ActionOutcome.Done("TXN-${db.entries.size}", null)
        }
        override suspend fun discard(plan: ActionPlan) { discarded += plan }
        override fun zone() = "Asia/Kolkata"
        override suspend fun contacts(name: String, role: PartyRole?) = emptyList<ContactMatch>()
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?) = "K-1"
    }

    private val db = Db()
    private val tools = Tools(db)
    private fun kai(t: Tools = tools) = KaiAgent(KaiBusinessBrain(Books(t.db), today = { now.toLocalDate() }, random = Random(1)), Books(t.db), t, now = { now })
    private fun KaiAgent.say(text: String) = runBlocking { ask(text) }
    private fun KaiAgent.chat(vararg lines: String): KaiTurn = lines.map { say(it) }.last()
    private fun saved(name: String) = db.entries.filter { it.name == name }

    // 1
    @Test
    fun theReportedConversationNowReachesTheBooks() {
        val kai = kai()
        kai.chat("Mahesh enaku 2000 tharanum", "save panniko")
        assertTrue("nothing written before Confirm", saved("Mahesh").isEmpty())
        val done = kai.say("seri")
        assertEquals(1, saved("Mahesh").size)
        val e = saved("Mahesh").single()
        assertEquals(BigDecimal("2000.00"), e.amount)
        assertTrue("receivable = the customer owes the owner", e.customer)
        assertEquals(PlanKind.CREDIT_GIVEN, tools.confirms.single().kind)
        assertTrue(done.reply.text, done.reply.text.startsWith("Save aagiduchu Owner. Mahesh — ₹2,000 (TXN-2)."))
    }

    // 2
    @Test
    fun theBusinessBrainSeesTheSavedAmount() {
        val kai = kai()
        assertEquals("Owner, Mahesh-nu customer record enakku kidaikala.", kai.say("Mahesh enaku evlo tharanum?").reply.text)
        kai.chat("Mahesh enaku 2000 tharanum", "save panniko", "seri")
        val answer = kai.say("Mahesh enaku evlo tharanum?").reply.text
        assertTrue(answer, answer.contains("Mahesh") && answer.contains("₹2,000"))
    }

    // 3
    @Test
    fun customersCollectionsAndHomeReadTheSameEntry() = runBlocking {
        kai().chat("Mahesh enaku 2000 tharanum", "5", "next month", "save panniko", "ok")
        val snap = Books(db).snapshot()
        val mahesh = snap.customers.single { it.name == "Mahesh" }
        assertEquals(2000.0, mahesh.pendingTotal, 0.0)
        assertEquals(LocalDate.of(2026, 11, 5), parseIsoToLocalDate(mahesh.nextDueDate))
        // The total to collect (Home) includes it.
        assertEquals(5000.0, snap.customers.sumOf { it.pendingTotal }, 0.0)
    }

    // 4
    @Test
    fun itSurvivesAnAppRestart() {
        kai().chat("Mahesh enaku 2000 tharanum", "save panniko", "seri")
        val afterRestart = kai(Tools(db)) // a new Kai over the same books: nothing in memory
        val answer = afterRestart.say("Mahesh enaku evlo tharanum?").reply.text
        assertTrue(answer, answer.contains("₹2,000"))
    }

    // 5
    @Test
    fun savePannikoAgainAfterSavingAddsNothing() {
        val kai = kai()
        kai.chat("Mahesh enaku 2000 tharanum", "save panniko", "seri")
        val again = kai.say("save panniko")
        kai.say("save panniko")
        kai.say("add pannu")
        assertEquals(1, saved("Mahesh").size)
        assertTrue(again.reply.text, again.reply.text.startsWith("Owner, adhu already save aagiduchu"))
    }

    // 6
    @Test
    fun theConfirmButtonAfterAVoiceConfirmAddsNothing() = runBlocking {
        val kai = kai()
        val draft = kai.chat("Mahesh enaku 2000 tharanum", "save panniko")
        kai.say("ama")
        val key = (draft.card!!.buttons.first { it.action is KaiAction.ConfirmPlan }.action as KaiAction.ConfirmPlan).key
        assertNull(kai.act(KaiAction.ConfirmPlan(key), KaiLang.TANGLISH))
        assertEquals(1, saved("Mahesh").size)
    }

    // 7
    @Test
    fun saveSaidTwiceWhileTheDraftIsOpenIsOneEntry() {
        val kai = kai()
        kai.chat("Mahesh enaku 2000 tharanum", "save panniko", "save panniko", "save panniko")
        assertEquals(1, saved("Mahesh").size)
    }

    // 8
    @Test
    fun venamCancelsAndNothingIsWritten() {
        val kai = kai()
        val cancelled = kai.chat("Mahesh enaku 2000 tharanum", "save panniko", "venam")
        assertEquals("Seri Owner, cancel pannitten. Edhuvum save aagala.", cancelled.reply.text)
        assertTrue(saved("Mahesh").isEmpty())
        assertTrue(tools.confirms.isEmpty())
        assertEquals(1, tools.discarded.size)
        // The cancelled payment is gone: a later "save panniko" does not bring it back.
        kai.say("save panniko")
        assertTrue(saved("Mahesh").isEmpty())
        assertEquals("Owner, innum edhuvum save pannala.", kai.say("add pannitiya?").reply.text)
    }

    // 9
    @Test
    fun theCancelButtonWritesNothing() = runBlocking {
        val kai = kai()
        val draft = kai.chat("Mahesh enaku 2000 tharanum", "save panniko")
        kai.act(draft.card!!.buttons.first { it.action is KaiAction.CancelPlan }.action, KaiLang.TANGLISH)
        assertTrue(saved("Mahesh").isEmpty())
        assertNull(kai.conversationState.stated)
    }

    // 10
    @Test
    fun anEditedAmountSavesOnlyTheFinalAmount() {
        val kai = kai()
        val edited = kai.chat("Mahesh enaku 2000 tharanum", "save panniko", "3000")
        assertEquals(BigDecimal("3000.00"), edited.plan!!.amount)
        assertEquals(PlanKind.CREDIT_GIVEN, edited.plan.kind)
        kai.say("seri")
        assertEquals(listOf(BigDecimal("3000.00")), saved("Mahesh").map { it.amount })
    }

    // 11
    @Test
    fun twoEditsStillOneEntry() {
        val kai = kai()
        kai.chat("Mahesh enaku 2000 tharanum", "save panniko", "3000", "3500", "ok")
        assertEquals(listOf(BigDecimal("3500.00")), saved("Mahesh").map { it.amount })
    }

    // 12
    @Test
    fun theDueDateIsSavedOnTheEntry() {
        kai().chat("Mahesh enaku 5000 tharanum", "5", "ama next month", "note panniko", "ama")
        assertEquals(LocalDate.of(2026, 11, 5), saved("Mahesh").single().dueDate)
    }

    // 13
    @Test
    fun julySixIsSavedAsNextJulySix() {
        kai().chat("Mahesh enaku 5000 tharanum", "July 6", "kanakkula podu", "seri")
        assertEquals(LocalDate.of(2027, 7, 6), saved("Mahesh").single().dueDate)
    }

    // 14
    @Test
    fun aDateSaidWhileTheDraftIsOpenGoesOnTheEntry() {
        val kai = kai()
        val redrafted = kai.chat("Mahesh enaku 2000 tharanum", "save panniko", "next month 10")
        assertTrue(redrafted.reply.text, redrafted.reply.text.startsWith("Owner, naan purinjukittadhu idhu."))
        kai.say("confirm")
        assertEquals(LocalDate.of(2026, 11, 10), saved("Mahesh").single().dueDate)
    }

    // 15
    @Test
    fun theDateTheBooksGetParsesBackToTheSameDay() {
        // AppKaiTools sends plan.dueDate.toString() to createCredit / createDebit; the books read it with parseIsoToLocalDate.
        val kai = kai()
        val draft = kai.chat("Mahesh enaku 5000 tharanum", "July 6")
        assertEquals(LocalDate.of(2027, 7, 6), parseIsoToLocalDate(draft.plan!!.dueDate!!.toString()))
    }

    // 16
    @Test
    fun aPayableIsSavedAsTheSuppliersDebit() {
        kai().chat("Mahesh-ku 5000 kudukkanum", "July 6", "account la podu", "yes")
        val e = saved("Mahesh").single()
        assertFalse("payable = the owner owes them", e.customer)
        assertEquals(PlanKind.DEBIT_TAKEN, tools.confirms.single().kind)
        assertEquals(LocalDate.of(2027, 7, 6), e.dueDate)
    }

    // 17
    @Test
    fun anExistingCustomerGetsTheEntryNotANewOne() {
        kai().chat("Kumar enaku 500 tharanum", "save panniko", "seri")
        assertEquals(setOf("c-kumar"), db.entries.filter { it.name == "Kumar" }.map { it.partyId }.toSet())
        assertEquals(3500.0, db.customers().single { it.name == "Kumar" }.pendingTotal, 0.0)
    }

    // 18
    @Test
    fun aFailedSaveSaysSoAndNeverClaimsItWasAdded() {
        db.failConfirm = "books unavailable"
        val kai = kai()
        val failed = kai.chat("Mahesh enaku 2000 tharanum", "save panniko", "seri")
        assertTrue(failed.reply.text, failed.reply.text.startsWith("Owner, save aagala. Naan amount-a save pannala."))
        assertTrue(saved("Mahesh").isEmpty())
        assertNull(kai.conversationState.lastSaved)
        val asked = kai.say("add pannitiya?").reply.text
        assertFalse(asked, asked.contains("pannitten"))
    }

    // 19
    @Test
    fun booksUnreachableMeansNoDraftAndASaidFailure() {
        db.booksDown = true
        val t = kai().chat("Mahesh enaku 2000 tharanum", "save panniko")
        assertEquals("Owner, save aagala. Naan amount-a save pannala.", t.reply.text)
        assertTrue(tools.prepared.isEmpty())
        assertTrue(tools.confirms.isEmpty())
    }

    // 20
    @Test
    fun aBlockedDraftIsNotSavedByVoice() {
        db.problems = listOf("Party is inactive")
        val kai = kai()
        kai.chat("Mahesh enaku 2000 tharanum", "save panniko")
        val t = kai.say("ama")
        assertTrue(t.reply.text, t.reply.text.contains("save panna mudiyadhu"))
        assertTrue(tools.confirms.isEmpty())
    }

    // 21
    @Test
    fun noAmountMeansNoDraft() {
        val kai = kai()
        val t = kai.chat("Mahesh-ku cash kudukanum", "save panniko")
        assertTrue(t.reply.text, t.reply.text.contains("evlo kudukkanum"))
        assertTrue(tools.prepared.isEmpty())
        // The amount said next completes it; still only a draft until Confirm.
        kai.say("4000")
        assertTrue(saved("Mahesh").isEmpty())
    }

    // 22
    @Test
    fun theSavedReplyComesOnlyFromTheEnginesDone() {
        val kai = kai()
        for (line in listOf("Mahesh enaku 2000 tharanum", "save panniko", "add pannitiya?")) {
            val text = kai.say(line).reply.text
            assertFalse("$line → $text", text.contains("Save aagiduchu") || text.contains("add pannitten"))
        }
        assertTrue(kai.say("seri").reply.text.startsWith("Save aagiduchu"))
        assertTrue(kai.say("add pannitiya?").reply.text.startsWith("ஆம் Owner, add pannitten."))
        assertNotNull(kai.conversationState.lastSaved)
    }

    // 23
    @Test
    fun aNewCustomerIsShownAsNewBeforeConfirm() {
        val draft = kai().chat("Mahesh enaku 2000 tharanum", "save panniko")
        assertTrue(draft.card!!.lines.toString(), draft.card!!.lines.any { it == "Puthu customer-ah add aagum" })
        assertTrue(draft.card!!.lines.any { it == "Due date: illa" })
        assertNull(draft.plan!!.partyId)
    }

    // 25
    @Test
    fun venamBeforeAnyDraftDropsTheStatedPayment() {
        val kai = kai()
        val t = kai.chat("Mahesh enaku 2000 tharanum", "venam")
        assertEquals("Seri Owner, edhuvum save pannala.", t.reply.text)
        assertNull(kai.conversationState.stated)
        kai.say("save panniko")
        assertTrue(tools.prepared.isEmpty())
        assertTrue(saved("Mahesh").isEmpty())
    }

    // 26
    @Test
    fun aNewStatementReplacesTheOpenDraftNeverSavingTheOldOne() {
        val kai = kai()
        kai.chat("Mahesh enaku 2000 tharanum", "save panniko", "Ravi enaku 1000 tharanum")
        assertEquals(1, tools.discarded.size)
        kai.chat("save panniko", "seri")
        assertTrue(saved("Mahesh").isEmpty())
        assertEquals(listOf(BigDecimal("1000.00")), saved("Ravi").map { it.amount })
    }

    // 24
    @Test
    fun englishOwesMeSavesTheSameWay() {
        val kai = kai()
        val draft = kai.chat("Mahesh owes me 2000", "save it")
        assertEquals(PlanKind.CREDIT_GIVEN, draft.plan?.kind)
        kai.say("yes")
        assertEquals(BigDecimal("2000.00"), saved("Mahesh").single().amount)
    }
}
