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
 * Phase 2 context: a short answer ("ama next month", "July 6", "seri", "save panniko", "add pannitiya?")
 * is read against what Kai is waiting for — the month of a date, the draft on screen, the stated payment —
 * and never re-asked, never "clear-ah sollunga", never saved without the owner's Confirm.
 */
class KaiContextFollowupTest {
    // Thursday 8 October 2026, 11 AM.
    private val now = LocalDateTime.of(2026, 10, 8, 11, 0)

    private class Books(val customers: List<PartySummary> = listOf(PartySummary("c1", "Kumar", "9000000001", 3000.0, "2026-10-20T00:00:00Z"))) : KaiBooks {
        /** The entries the engine confirmed: the books show them (Kai reads a save back before saying it is saved). */
        var confirmed: () -> List<ActionPlan> = { emptyList() }
        private fun withSaved(base: List<PartySummary>, kind: PlanKind): List<PartySummary> {
            val saved = confirmed().filter { it.kind == kind }
            val existing = base.map { p -> p.copy(pendingTotal = p.pendingTotal + saved.filter { it.partyId == p.id }.sumOf { it.amount.toDouble() }) }
            val added = saved.filter { s -> s.partyId == null || base.none { it.id == s.partyId } }.groupBy { it.partyName }
                .map { (name, plans) -> PartySummary("new-$name", name, null, plans.sumOf { it.amount.toDouble() }, null) }
            return existing + added
        }
        override suspend fun snapshot() = BusinessSnapshot(
            customers = withSaved(customers, PlanKind.CREDIT_GIVEN),
            suppliers = withSaved(listOf(PartySummary("s1", "Ramesh", null, 2500.0, null)), PlanKind.DEBIT_TAKEN),
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    /** Drafts are recorded; a save happens only through [confirm] (the books engine). */
    private class Tools(val people: List<PartyMatch> = listOf(
        PartyMatch("c1", "Kumar", customer = true, phone = "+919000000001", balance = BigDecimal("3000.00")),
        PartyMatch("s1", "Ramesh", customer = false, phone = null, balance = BigDecimal("2500.00")),
    )) : KaiTools {
        val prepared = mutableListOf<ActionPlan>()
        val confirmed = mutableListOf<ActionPlan>()
        override suspend fun parties(name: String) = people.filter { it.name.contains(name, true) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String): ActionPlan =
            ActionPlan("p${prepared.size}", kind, partyName, partyId, amount, mode, said = said).also { prepared += it }
        override suspend fun confirm(plan: ActionPlan): ActionOutcome { confirmed += plan; return ActionOutcome.Done("S-${confirmed.size}", null) }
        override fun zone() = "Asia/Kolkata"
        override suspend fun contacts(name: String, role: PartyRole?) = emptyList<ContactMatch>()
        override fun log(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String?) = "K"
    }

    private var tools = Tools()
    private fun kai(books: Books = Books(), t: Tools = tools) =
        KaiAgent(KaiBusinessBrain(books, today = { now.toLocalDate() }, random = Random(1)), books, t, now = { now }).also { tools = t; books.confirmed = { t.confirmed } }
    private fun KaiAgent.say(text: String) = runBlocking { ask(text) }
    private fun KaiAgent.chat(vararg lines: String): KaiTurn = lines.map { say(it) }.last()
    private fun noSaveClaim(text: String) {
        for (w in listOf("save pannitten", "add pannitten", "note pannitten", "record pannitten", "note pannikiren", "save aagiduchu"))
            assertFalse("'$w' without a save: $text", text.contains(w, ignoreCase = true))
    }
    private fun neverUnclear(text: String) = assertFalse("never 'clear-ah sollunga': $text", text.contains("clear-ah", ignoreCase = true))

    // ---------------------------------------------------------------- A. month clarification

    @Test
    fun amaNextMonthResolvesTheFifthOfNextMonthWithThePayment() {
        val kai = kai()
        assertEquals("Owner, indha maasam 5-aa, illa adutha maasam 5-aa?", kai.chat("Mahesh enaku 5000 tharanum", "5").reply.text)
        val t = kai.say("ama next month")
        assertEquals("Seri Owner, Mahesh kitta irundhu ₹5,000 adutha maasam 5-m thethi vaanganum. Add pannalama?", t.reply.text)
        assertEquals(LocalDate.of(2026, 11, 5), t.plan!!.dueDate)
        assertEquals(LocalDate.of(2026, 11, 5), kai.conversationState.lastDate)
        assertTrue(tools.confirmed.isEmpty())
    }

    @Test
    fun yesNextMonthAndPlainNextMonthResolveTheSameWay() {
        for (answer in listOf("yes next month", "next month", "aama next month", "seri next month", "adutha maasam", "Ama, next month.")) {
            val kai = kai()
            val t = kai.chat("Mahesh enaku 5000 tharanum", "5", answer)
            assertEquals(answer, LocalDate.of(2026, 11, 5), kai.conversationState.lastDate)
            assertTrue(answer, t.reply.text.contains("Mahesh") && t.reply.text.contains("₹5,000"))
            neverUnclear(t.reply.text)
        }
    }

    @Test
    fun indhaMonthAfterTheDayIsThisMonth() {
        val kai = kai()
        kai.chat("Mahesh enaku 5000 tharanum", "5", "indha month")
        assertEquals(LocalDate.of(2026, 10, 5), kai.conversationState.lastDate)
    }

    @Test
    fun amaAloneAsksTheTwoChoicesAgainNeverClearAh() {
        val kai = kai()
        val t = kai.chat("Mahesh enaku 5000 tharanum", "5", "ama")
        assertEquals("Owner, indha maasam 5-aa, adutha maasam 5-aa?", t.reply.text)
        assertEquals(LocalDate.of(2026, 11, 5), kai.chat("next month").let { kai.conversationState.lastDate })
    }

    @Test
    fun everyDayOnlyFormAsksTheMonth() {
        for (day in listOf("5", "5th", "5 தேதி", "5-ம் தேதி")) {
            val t = kai().chat("Mahesh enaku 5000 tharanum", day)
            assertTrue(day, t.reply.text.contains("5-aa") || t.reply.text.contains("5-ஆ"))
        }
    }

    @Test
    fun dayWithMonthResolvesAtOnce() {
        for ((said, date) in listOf(
            "indha month 5" to LocalDate.of(2026, 10, 5), "indha maasam 5" to LocalDate.of(2026, 10, 5), "this month 5" to LocalDate.of(2026, 10, 5),
            "next month 5" to LocalDate.of(2026, 11, 5), "adutha month 5" to LocalDate.of(2026, 11, 5), "adutha maasam 5" to LocalDate.of(2026, 11, 5),
        )) {
            val kai = kai()
            kai.chat("Mahesh enaku 5000 tharanum", said)
            assertEquals(said, date, kai.conversationState.lastDate)
        }
    }

    @Test
    fun monthAnswerSurvivesSmallTalkInBetween() {
        val kai = kai()
        kai.chat("Mahesh enaku 5000 tharanum", "5", "saptiya?")
        val t = kai.say("next month")
        assertEquals(LocalDate.of(2026, 11, 5), kai.conversationState.lastDate)
        assertTrue(t.reply.text.contains("Mahesh"))
    }

    // ---------------------------------------------------------------- B. explicit dates (KaiTime, never hardcoded)

    @Test
    fun julySixInEveryFormIsTheNextJulySix() {
        for (said in listOf("July 6", "july 6", "6 July", "July 6th", "next July 6")) {
            val kai = kai()
            val t = kai.chat("Mahesh enaku 5000 tharanum", said)
            assertEquals(said, LocalDate.of(2027, 7, 6), kai.conversationState.lastDate)
            assertTrue(said, t.reply.text.contains("Mahesh") && t.reply.text.contains("₹5,000") && t.reply.text.contains("2027"))
        }
    }

    @Test
    fun julySixAfterTheMonthQuestionStillKeepsThePayment() {
        val kai = kai()
        val t = kai.chat("Mahesh enaku 5000 tharanum", "5", "July 6")
        assertEquals(LocalDate.of(2027, 7, 6), kai.conversationState.lastDate)
        assertTrue(t.reply.text.contains("vaanganum"))
    }

    @Test
    fun payableKeepsItsDirectionThroughTheDate() {
        val kai = kai()
        val t = kai.chat("Mahesh-ku 5000 kudukkanum", "July 6")
        assertTrue(t.reply.text, t.reply.text.startsWith("Seri Owner, Mahesh-ku ₹5,000") && t.reply.text.contains("2027") && t.reply.text.endsWith("kudukkanum. Add pannalama?"))
        assertEquals(PlanKind.DEBIT_TAKEN, t.plan!!.kind)
        assertEquals("OUT", kai.conversationState.lastPaymentDirection)
    }

    @Test
    fun aDateInJulyFromAnotherDayIsStillTheNextOne() {
        val kai = KaiAgent(KaiBusinessBrain(Books(), today = { LocalDate.of(2026, 3, 1) }, random = Random(1)), Books(), tools, now = { LocalDateTime.of(2026, 3, 1, 10, 0) })
        kai.chat("Mahesh enaku 5000 tharanum", "July 6")
        assertEquals(LocalDate.of(2026, 7, 6), kai.conversationState.lastDate)
    }

    // ---------------------------------------------------------------- C. save words → the existing draft (never a blind save)

    @Test
    fun everySavePhraseMakesADraftAndNeverSaves() {
        for (phrase in listOf("note panniko", "note pannu", "save pannu", "save panniko", "add panniko", "add pannu", "record pannu", "record panniko",
            "kanakku la podu", "kanakkula podu", "account la podu", "account-la podu", "serthu vidu")) {
            val t = Tools()
            val kai = kai(t = t)
            val turn = kai.chat("Mahesh enaku 2000 tharanum", phrase)
            assertNotNull(phrase, turn.plan)
            assertEquals(phrase, PlanKind.CREDIT_GIVEN, turn.plan!!.kind)
            assertEquals(phrase, BigDecimal("2000.00"), turn.plan.amount)
            assertTrue(phrase, turn.card!!.buttons.any { it.action is KaiAction.ConfirmPlan })
            assertTrue("$phrase saved nothing", t.confirmed.isEmpty())
            noSaveClaim(turn.reply.text)
        }
    }

    @Test
    fun aStatementWithTheSaveWordsGoesStraightToTheDraft() {
        val turn = kai().say("Mahesh enaku 2000 tharanum, save panniko")
        assertEquals(PlanKind.CREDIT_GIVEN, turn.plan?.kind)
        assertTrue(tools.confirmed.isEmpty())
    }

    @Test
    fun aPayableSavesAsTheOwnersDebit() {
        val turn = kai().chat("Mahesh-ku 5000 kudukkanum", "note panniko")
        assertEquals(PlanKind.DEBIT_TAKEN, turn.plan?.kind)
        assertTrue(turn.card!!.lines.any { it.contains("Debit") })
    }

    // ---------------------------------------------------------------- D. confirmation words against the pending state

    @Test
    fun confirmWordsSaveTheDraftJustShown() {
        for (yes in listOf("ama", "aama", "yes", "seri", "correct", "ok", "okay", "seri add pannu", "save pannu", "add panniko", "note panniko")) {
            val t = Tools()
            val kai = kai(t = t)
            kai.chat("Mahesh enaku 2000 tharanum", "save panniko")
            val turn = kai.say(yes)
            assertEquals(yes, 1, t.confirmed.size)
            assertTrue(yes, turn.reply.text.startsWith("Save aagiduchu Owner. Mahesh — ₹2,000"))
        }
    }

    @Test
    fun aBareYesWithNoDraftSavesNothing() {
        val kai = kai()
        for (yes in listOf("ok", "seri", "ama")) kai.chat("Mahesh enaku 2000 tharanum", yes)
        assertTrue(tools.prepared.isEmpty())
        assertTrue(tools.confirmed.isEmpty())
    }

    @Test
    fun aBareYesLaterAnswersTheLatestQuestionNotAnOldDraft() {
        val kai = kai()
        kai.chat("Mahesh enaku 2000 tharanum", "save panniko", "saptiya?")
        kai.say("ama")
        assertTrue("an old draft is not confirmed by a later 'ama'", tools.confirmed.isEmpty())
        kai.say("save pannu")
        assertEquals(1, tools.confirmed.size)
    }

    @Test
    fun saveWordsWithANewAmountOrPersonAreNotAConfirmation() {
        val kai = kai()
        kai.chat("Mahesh enaku 2000 tharanum", "save panniko")
        kai.say("Kumar 500 add pannu")
        assertTrue(tools.confirmed.isEmpty())
    }

    @Test
    fun stockWordsAreNotTakenAsSavingThePayment() {
        val kai = kai()
        kai.chat("Mahesh enaku 2000 tharanum", "Colgate stock add pannu")
        assertTrue("no payment draft from a stock sentence: ${tools.prepared}", tools.prepared.none { it.partyName == "Mahesh" })
    }

    // ---------------------------------------------------------------- E. "add pannitiya?" — from what the books confirmed

    @Test
    fun addPannitiyaBeforeSavingSaysNotSavedAndShowsConfirm() {
        val kai = kai()
        val turn = kai.chat("Mahesh enaku 2000 tharanum", "add pannitiya?")
        assertEquals("Innum save pannala Owner. Confirm pannunga, save pannidren.", turn.reply.text)
        assertTrue(turn.card!!.buttons.any { it.action is KaiAction.ConfirmPlan })
        assertTrue(tools.confirmed.isEmpty())
    }

    @Test
    fun pannitiyaWithTheDraftOpenSaysNotSaved() {
        val kai = kai()
        val turn = kai.chat("Mahesh enaku 2000 tharanum", "save panniko", "pannitiya?")
        assertEquals("Innum save pannala Owner. Confirm pannunga, save pannidren.", turn.reply.text)
        assertTrue(tools.confirmed.isEmpty())
    }

    @Test
    fun addPannitiyaAfterTheBooksSavedItSaysAdded() {
        val kai = kai()
        val turn = kai.chat("Mahesh enaku 2000 tharanum", "save panniko", "seri", "add pannitiya?")
        assertTrue(turn.reply.text, turn.reply.text.startsWith("ஆம் Owner, add pannitten."))
    }

    @Test
    fun addPannitiyaWithNothingSaidSavesNothingAndSaysSo() {
        val turn = kai().say("add pannitiya?")
        assertEquals("Owner, innum edhuvum save pannala.", turn.reply.text)
        assertTrue(tools.prepared.isEmpty())
    }

    @Test
    fun aStatedPaymentNeverClaimsToBeSaved() {
        val kai = kai()
        for (line in listOf("Mahesh enaku 2000 tharanum", "5", "next month", "eppa?")) noSaveClaim(kai.say(line).reply.text)
        assertTrue(tools.confirmed.isEmpty())
    }

    // ---------------------------------------------------------------- F. business questions

    @Test
    fun howMuchDoesKumarOweIsAnsweredFromTheRecords() {
        for (q in listOf("Kumar enaku evlo tharanum?", "Kumar enakku evlo tharanum?", "Kumar enna amount tharanum?", "Kumar kitta irundhu evlo vanganu?",
            "Kumar enakku evlo kudukanum?", "Kumar balance evlo?", "Kumar oda balance evlo?", "Kumar enakku evlo pending?", "Kumar kitta evlo pending?")) {
            val t = kai().say(q)
            assertTrue("$q → ${t.reply.text}", t.reply.text.contains("₹3,000") && t.reply.text.contains("Kumar"))
            assertNull(q, t.plan)
        }
        assertTrue(tools.prepared.isEmpty())
    }

    @Test
    fun anUnknownPersonIsSaidToHaveNoRecord() {
        assertEquals("Owner, Mahesh-nu customer record enakku kidaikala.", kai().say("Mahesh enaku evlo tharanum?").reply.text)
    }

    // ---------------------------------------------------------------- G. names are never mapped silently

    @Test
    fun kumaranIsNeverSavedAsKumar() {
        val kai = kai()
        val turn = kai.chat("Kumaran enaku 3000 tharanum", "save panniko")
        assertEquals("Owner, Kumaran-nu separate customer-aa? Kumar-a?", turn.reply.text)
        assertTrue(tools.prepared.isEmpty())
        val labels = turn.card!!.buttons.map { it.label }
        assertTrue(labels.toString(), labels.first().startsWith("Kumaran") && labels.any { it.startsWith("Kumar ·") })
    }

    @Test
    fun theOwnerPicksANewKumaranOrTheExistingKumar() = runBlocking {
        val kai = kai()
        val ask = kai.chat("Kumaran enaku 3000 tharanum", "save panniko")
        val asNew = ask.card!!.buttons.first().action as KaiAction.ChoosePlan
        val draft = kai.act(asNew, com.shopai.app.brain.KaiLang.TANGLISH)!!
        assertEquals("Kumaran", draft.plan!!.partyName)
        assertNull(draft.plan.partyId)

        val kai2 = kai(t = Tools())
        val ask2 = kai2.chat("Kumaran enaku 3000 tharanum", "save panniko")
        val asKumar = ask2.card!!.buttons.first { it.label.startsWith("Kumar ·") }.action as KaiAction.ChoosePlan
        val draft2 = kai2.act(asKumar, com.shopai.app.brain.KaiLang.TANGLISH)!!
        assertEquals("c1", draft2.plan!!.partyId)
    }

    @Test
    fun sivakumarIsNotSiva() {
        val books = Books(listOf(PartySummary("c9", "Siva", null, 800.0, null)))
        val t = Tools(listOf(PartyMatch("c9", "Siva", customer = true, phone = null, balance = BigDecimal("800.00"))))
        val turn = kai(books, t).chat("Sivakumar enaku 1000 tharanum", "save panniko")
        assertEquals("Owner, Sivakumar-nu separate customer-aa? Siva-a?", turn.reply.text)
        assertTrue(t.prepared.isEmpty())
    }

    @Test
    fun anExactNameUsesThatCustomer() {
        val turn = kai().chat("Kumar enaku 500 tharanum", "save panniko")
        assertEquals("c1", turn.plan!!.partyId)
        assertFalse(turn.card!!.lines.any { it.contains("Puthu customer") })
    }

    // ---------------------------------------------------------------- H. context survives other topics

    @Test
    fun smallTalkThenEppaReturnsToThePayment() {
        val kai = kai()
        val t = kai.chat("Kumaran enaku 3000 tharanum", "saptiya?", "eppa?")
        assertTrue(t.reply.text, t.reply.text.contains("Kumaran") && t.reply.text.contains("₹3,000"))
    }

    @Test
    fun calculatorThenAvanEppaTharuvaanReturnsToThePayment() {
        val kai = kai()
        assertEquals("1,625", kai.chat("Kumaran enaku 3000 tharanum", "1250 plus 375").reply.text)
        val t = kai.say("avan eppa tharuvaan?")
        assertTrue(t.reply.text, t.reply.text.contains("Kumaran") && t.reply.text.contains("₹3,000"))
    }

    @Test
    fun theDueDateAnsweredBeforeSavingGoesOnTheDraft() {
        val kai = kai()
        // The date answer itself brings the draft (no separate "kanakkula podu" step).
        val turn = kai.chat("Mahesh enaku 5000 tharanum", "5", "ama next month")
        assertEquals(LocalDate.of(2026, 11, 5), turn.plan!!.dueDate)
        assertTrue(turn.card!!.lines.any { it.startsWith("Due date:") && it.contains("November") })
    }

    @Test
    fun noFollowUpInTheseConversationsSaysClearAhSollunga() {
        for (c in listOf(
            listOf("Mahesh enaku 5000 tharanum", "5", "ama next month"), listOf("Mahesh enaku 2000 tharanum", "save panniko"),
            listOf("Mahesh enaku 2000 tharanum", "note panniko"), listOf("Mahesh enaku 2000 tharanum", "add pannitiya?"),
            listOf("save panniko"), listOf("add pannitiya?"), listOf("Mahesh enaku 5000 tharanum", "July 6"),
        )) {
            val kai = kai(t = Tools())
            for (line in c) neverUnclear(kai.say(line).reply.text)
        }
    }

    @Test
    fun saveWithNothingStatedAsksWhoAndHowMuch() {
        val t = kai().say("save panniko")
        assertTrue(t.reply.text, t.reply.text.startsWith("Owner, save panna ippo edhuvum illa."))
        assertTrue(tools.prepared.isEmpty())
    }

    @Test
    fun semanticsTellASaveFromAQuestionAboutIt() {
        val s = KaiConversationSemantics
        assertTrue(s.savesDraft("save panniko") && s.savesDraft("Kanakkula podu Owner") && s.savesDraft("serthu vidu"))
        assertFalse(s.savesDraft("add pannitiya?"))
        assertTrue(s.asksIfSaved("add pannitiya?") && s.asksIfSaved("pannitiya") && s.asksIfSaved("save aagiducha?"))
        assertTrue(s.confirmsDraft("Ama.") && s.confirmsDraft("ok") && s.confirmsDraft("seri add pannu"))
        assertFalse(s.confirmsDraft("ama next month 5") || s.confirmsDraft("saptiya?"))
        assertTrue(s.onlySave("seri, save pannu") && s.onlySave("Mahesh-a save pannu", "Mahesh"))
        assertFalse(s.onlySave("Colgate stock add pannu"))
    }
}
