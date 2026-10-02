package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.Direction
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.data.model.PartySummary
import com.shopai.app.data.model.ReminderItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/** Kai Chat's own Business Brain — no AI service — against fixed records. */
class KaiBusinessBrainTest {
    private val today = LocalDate.of(2026, 9, 30)

    private class FakeBooks(
        val customers: List<PartySummary>,
        val suppliers: List<PartySummary> = emptyList(),
        val histories: Map<String, PartyHistory> = emptyMap(),
        val cash: CashBookTotals? = null,
        val reminders: List<ReminderItem> = emptyList(),
    ) : KaiBooks {
        var historyCalls = 0
        override suspend fun snapshot() = BusinessSnapshot(customers, suppliers, reminders)
        override suspend fun history(party: PartyFacts): PartyHistory? { historyCalls++; return histories[party.id] }
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = cash
    }

    private fun kumarHistory(): PartyHistory {
        val kumar = PartyFacts("c1", "Kumar", Direction.RECEIVABLE, 2000.0, LocalDate.of(2026, 10, 10))
        return PartyHistory(
            kumar,
            listOf(
                PartyHistory.LedgerEntry(
                    amount = 5000.0, paid = 3000.0, dueDate = LocalDate.of(2026, 10, 10), createdAt = LocalDate.of(2026, 9, 5),
                    payments = listOf(
                        PartyHistory.Payment(2000.0, LocalDate.of(2026, 9, 12)),
                        PartyHistory.Payment(1000.0, LocalDate.of(2026, 9, 25)),
                    ),
                ),
            ),
        )
    }

    private fun books() = FakeBooks(
        customers = listOf(
            PartySummary("c1", "Kumar", "9000000001", 2000.0, "2026-10-10T00:00:00Z"),
            PartySummary("c2", "Ravi", null, 5500.0, "2026-09-30T00:00:00Z"),
            PartySummary("c3", "Selvam", null, 0.0, null),
            PartySummary("c4", "Priya", null, 1200.0, null),
            PartySummary("c5", "Meena", null, 800.0, "2026-10-20T00:00:00Z"),
        ),
        suppliers = listOf(PartySummary("s1", "Anand Traders", null, 8000.0, "2026-10-02T00:00:00Z")),
        histories = mapOf("c1" to kumarHistory()),
        cash = CashBookTotals(totalIn = 12500.0, totalOut = 3200.0, entries = 6),
    )

    private fun brain(b: KaiBooks = books()) = KaiBusinessBrain(b, today = { today }, random = Random(7))
    private fun ask(brain: KaiBusinessBrain, text: String) = runBlocking { brain.ask(text) }

    // ---------------------------------------------------------- intents

    @Test
    fun tamilTanglishEnglishVariationsGiveTheSameIntent() {
        val people = listOf("Kumar", "Ravi")
        fun intent(t: String) = KaiChatUnderstanding.understand(t, today, people).intent
        for (t in listOf("Kumar evlo tharanum?", "Kumar evlo kudukkanum?", "Kumar kitta evlo varanum?", "Kumar balance enna?", "Kumar pending evlo?", "How much does Kumar owe?")) {
            assertEquals(t, ChatIntent.CUSTOMER_BALANCE, intent(t))
        }
        for (t in listOf("Kumar eppo tharanum?", "Kumar payment eppo?", "When will Kumar pay?")) assertEquals(t, ChatIntent.CUSTOMER_DUE_DATE, intent(t))
        assertEquals(ChatIntent.CUSTOMER_LAST_PAYMENT, intent("Kumar last payment eppo?"))
        assertEquals(ChatIntent.CUSTOMER_HISTORY, intent("Kumar history sollu"))
        for (t in listOf("Yaaru innaiku payment tharanum?", "Innaiku yaar payment tharanum?")) assertEquals(t, ChatIntent.TODAY_COLLECTIONS, intent(t))
        for (t in listOf("Yaaru enakku cash tharanum?", "Yaar kitta collection poganum?", "Next week yaar payment varanum?", "Next month yaaru payment tharanum?")) {
            assertEquals(t, ChatIntent.UPCOMING_COLLECTIONS, intent(t))
        }
        assertEquals(ChatIntent.TOTAL_RECEIVABLE, intent("Enakku total pending evlo?"))
        assertEquals(ChatIntent.TOTAL_PAYABLE, intent("Yaarukku naan kudukkanum?"))
        assertEquals(ChatIntent.EXPENSE_SUMMARY, intent("Indha maasam selavu evlo?"))
        assertEquals(ChatIntent.MONTHLY_SALES, intent("Indha month sales evlo?"))
        assertEquals(ChatIntent.REMINDER_QUERY, intent("Enna reminders irukku?"))
        assertEquals(ChatIntent.OVERDUE_COLLECTIONS, intent("Yaar payment overdue?"))
        assertEquals(ChatIntent.BUSINESS_SUMMARY, intent("Business eppadi pogudhu?"))
    }

    @Test
    fun entitiesAndDates() {
        val q = KaiChatUnderstanding.understand("Kumar 3000 eppo tharanum?", today, listOf("Kumar"))
        assertEquals(ChatIntent.CUSTOMER_DUE_DATE, q.intent)
        assertEquals(PersonRef.Named("Kumar"), q.person)
        assertEquals(3000.0, q.amount!!, 0.0)
        fun period(t: String) = KaiChatUnderstanding.understand(t, today, emptyList()).period
        assertEquals(today, period("innaikku yaar tharanum")!!.from)
        assertEquals(today.plusDays(1), period("naalaikku yaar tharanum")!!.from)
        assertEquals(LocalDate.of(2026, 10, 1), period("next month yaar tharanum")!!.from)
        assertEquals(LocalDate.of(2026, 10, 10), period("next month 10th yaar tharanum")!!.from)
        assertEquals(LocalDate.of(2026, 10, 10), period("10.10.2026 yaar tharanum")!!.from)
        assertEquals(LocalDate.of(2026, 10, 10), period("October 10th yaar tharanum")!!.from)
        // "10th" in the past this month → next month's 10th.
        assertEquals(LocalDate.of(2026, 10, 10), period("10th yaar tharanum")!!.from)
        assertEquals(LocalDate.of(2026, 10, 5), period("next week yaar tharanum")!!.from)
    }

    // --------------------------------------------------------- answers

    @Test
    fun balanceComesFromTheRecords() {
        val r = ask(brain(), "Kumar evlo tharanum?")
        assertEquals(ChatIntent.CUSTOMER_BALANCE, r.intent)
        assertTrue(r.text, r.text.contains("Kumar") && r.text.contains("₹2,000"))
        val other = ask(brain(), "Ravi kitta evlo varanum?")
        assertTrue(other.text, other.text.contains("Ravi") && other.text.contains("₹5,500"))
    }

    @Test
    fun conversationFollowsUpOnTheSamePerson() {
        val b = brain()
        ask(b, "Kumar evlo tharanum?")
        val due = ask(b, "Eppo?")
        assertEquals(ChatIntent.CUSTOMER_DUE_DATE, due.intent)
        assertTrue(due.text, due.text.contains("October 10th") && due.text.contains("₹2,000"))
        val paid = ask(b, "Avan already edhavadhu kuduthana?")
        assertEquals(ChatIntent.CUSTOMER_PAYMENTS, paid.intent)
        assertTrue(paid.text, paid.text.contains("₹3,000") && paid.text.contains("Kumar"))
    }

    @Test
    fun lastPaymentFromHistory() {
        val r = ask(brain(), "Kumar last payment eppo?")
        assertTrue(r.text, r.text.contains("₹1,000") && r.text.contains("September 25th"))
    }

    @Test
    fun todayAndUpcomingCollections() {
        val today = ask(brain(), "Yaaru innaiku payment tharanum?")
        assertTrue(today.text, today.text.contains("Ravi") && today.text.contains("₹5,500"))
        assertFalse(today.text.contains("Kumar"))
        val next = ask(brain(), "Next month yaaru payment tharanum?")
        assertTrue(next.text, next.text.contains("Kumar") && next.text.contains("Meena"))
        assertFalse(next.text.contains("Ravi"))
    }

    @Test
    fun totalsFromTheRecords() {
        val r = ask(brain(), "Enakku total pending evlo?")
        // 2000 + 5500 + 1200 + 800 = 9500 receivable; 8000 payable.
        assertTrue(r.text, r.text.contains("₹9,500") && r.text.contains("₹8,000"))
        val pay = ask(brain(), "Yaarukku naan kudukkanum?")
        assertTrue(pay.text, pay.text.contains("Anand Traders") && pay.text.contains("₹8,000"))
    }

    @Test
    fun cashBookAnswers() {
        assertTrue(ask(brain(), "Indha maasam selavu evlo?").text.contains("₹3,200"))
        assertTrue(ask(brain(), "Indha month sales evlo?").text.contains("₹12,500"))
        val none = ask(brain(FakeBooks(customers = emptyList(), cash = null)), "Indha maasam selavu evlo?")
        assertTrue(none.text, none.text.contains("record-la illa"))
    }

    @Test
    fun supplierIsAnsweredAsAPayable() {
        val r = ask(brain(), "Anand Traders evlo?")
        assertEquals(ChatIntent.SUPPLIER_BALANCE, r.intent)
        assertTrue(r.text, r.text.contains("₹8,000") && !r.text.lowercase().contains("tharanum"))
    }

    // ------------------------------------------------------ edge cases

    @Test
    fun unknownCustomerIsNotAnsweredAboutSomeoneElse() {
        val r = ask(brain(), "Muthu evlo tharanum?")
        assertTrue(r.text, r.text.contains("Muthu-nu customer record enakku kidaikala"))
    }

    @Test
    fun twoCustomersWithTheSameNameAreNeverGuessed() {
        val b = brain(FakeBooks(customers = listOf(
            PartySummary("a", "Kumar", "9000000001", 2000.0, null),
            PartySummary("b", "Kumar", "9000000002", 4500.0, null),
        )))
        val ask1 = ask(b, "Kumar evlo tharanum?")
        assertTrue(ask1.text, ask1.text.contains("rendu per") && ask1.text.contains("Yaar pathi kekkureenga"))
        // The owner picks the second one.
        val chosen = ask(b, "rendavadhu")
        assertTrue(chosen.text, chosen.text.contains("₹4,500"))
    }

    @Test
    fun missingDueDateIsSaidPlainly() {
        val r = ask(brain(), "Priya eppo tharanum?")
        assertTrue(r.text, r.text.contains("due date record-la illa") && r.text.contains("₹1,200"))
    }

    @Test
    fun zeroBalance() {
        val r = ask(brain(), "Selvam evlo tharanum?")
        assertTrue(r.text, r.text.contains("Selvam") && (r.text.contains("pending edhuvum illa") || r.text.contains("Pending illa")))
    }

    @Test
    fun aDifferentAmountSaidByTheOwnerDoesNotChangeTheRecords() {
        val r = ask(brain(), "Kumar 3000 eppo tharanum?")
        assertTrue(r.text, r.text.contains("₹2,000"))
        assertTrue(r.text, r.text.contains("Record-la ₹2,000 dhaan irukku"))
        // A negative / invalid amount is ignored, never used.
        val neg = ask(brain(), "Kumar -500 evlo tharanum?")
        assertTrue(neg.text, neg.text.contains("₹2,000"))
    }

    @Test
    fun emptyAndUnclearInput() {
        assertEquals("Owner, konjam clear-ah sollunga.", ask(brain(), "   ").text)
        assertEquals("Owner, konjam clear-ah sollunga.", ask(brain(), "asdf qwer").text)
    }

    @Test
    fun followUpWithoutAnyoneAsksWho() {
        val r = ask(brain(), "Eppo?")
        assertTrue(r.text, r.text.contains("Yaar pathi kekkureenga") || r.text.contains("yaar pathi kekkureenga"))
    }

    @Test
    fun replyVariationKeepsTheSameFacts() {
        val texts = (1..12).map { seed ->
            runBlocking { KaiBusinessBrain(books(), { today }, Random(seed)).ask("Kumar evlo tharanum?").text }
        }.toSet()
        assertTrue("several wordings: $texts", texts.size >= 2)
        assertTrue(texts.all { it.contains("₹2,000") && it.contains("Kumar") })
    }
}
