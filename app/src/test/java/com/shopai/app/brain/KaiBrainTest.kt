package com.shopai.app.brain

import com.shopai.app.data.model.CashFlowSummary
import com.shopai.app.data.model.CashFlowWindow
import com.shopai.app.data.model.ParsedTransaction
import com.shopai.app.data.model.PartySummary
import com.shopai.app.data.model.ReminderItem
import com.shopai.app.brain.Direction.PAYABLE
import com.shopai.app.brain.Direction.RECEIVABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class KaiBrainTest {
    private val today = LocalDate.of(2026, 9, 30)

    private fun record(text: String, known: List<String> = emptyList()) =
        KaiUnderstanding.understand(text, today, known) as KaiIntent.Record

    // ------------------------------------------------ one meaning, any language

    @Test
    fun manyWaysOfSayingTheSameReceivable() {
        for (text in listOf(
            "Kumar kitta 3000 vanganum",
            "Kumar owes me 3000",
            "Kumar 3000 credit",
            "Kumar kitta moonu ayiram pending",
            "Kumar kitta moonu ayiram vanganu",
        )) {
            val r = record(text)
            assertEquals(text, "Kumar", r.person)
            assertEquals(text, 3000.0, r.amount!!, 0.0)
            assertEquals(text, RECEIVABLE, r.direction)
        }
    }

    @Test
    fun spokenEntryWithWordAmountAndDueDate() {
        val r = record("Kumar kitta moonu ayiram vanganu, October 10th due.")
        assertEquals("Kumar", r.person)
        assertEquals(3000.0, r.amount!!, 0.0)
        assertEquals(RECEIVABLE, r.direction)
        assertEquals(LocalDate.of(2026, 10, 10), r.dueDate)
    }

    @Test
    fun typedEntryWithNumericDueDate() {
        val r = record("Kumar 3000 due 10.10.2026")
        assertEquals("Kumar", r.person)
        assertEquals(3000.0, r.amount!!, 0.0)
        assertEquals(RECEIVABLE, r.direction)
        assertEquals(LocalDate.of(2026, 10, 10), r.dueDate)
    }

    @Test
    fun payables() {
        for (text in listOf("Ravi-ku 8000 kudukkanum", "Ravi kitta 8000 kudukkanum", "I owe Ravi 8000", "Ravi 8000 debit")) {
            val r = record(text)
            assertEquals(text, "Ravi", r.person)
            assertEquals(text, 8000.0, r.amount!!, 0.0)
            assertEquals(text, PAYABLE, r.direction)
        }
    }

    @Test
    fun missingOrMixedFactsAreNeverGuessed() {
        with(record("Kumar kitta pending")) { assertNull(amount); assertEquals(listOf(KaiIntent.Field.AMOUNT), missing) }
        with(record("Kumar 3000")) { assertNull(direction); assertFalse(complete) }
        with(record("Kumar 3000 vanganum kudukkanum")) { assertNull(direction) }
        with(record("Kumar 3000 5000 vanganum")) { assertNull(amount); assertTrue(amountAmbiguous) }
        with(record("3000 vanganum")) { assertNull(person) }
        // No date said → no due date.
        assertNull(record("Kumar 3000 vanganum").dueDate)
    }

    @Test
    fun theServerCannotAddWhatTheOwnerDidNotSay() {
        val local = record("Kumar kitta pending")
        val server = ParsedTransaction("CREATE_CREDIT", "Suresh", 2000.0, "INR", "2026-10-01T00:00:00Z", null, 0.9, "")
        val r = KaiUnderstanding.reconcile(local, server, "Kumar kitta pending", today)
        assertEquals("Kumar", r.person)
        assertNull("2000 was never said", r.amount)
        assertNull("no due date was said", r.dueDate)
        // The owner's own amount wins over a different server amount.
        val own = KaiUnderstanding.reconcile(record("Kumar 3000 vanganum"), server.copy(amount = 2000.0), "Kumar 3000 vanganum", today)
        assertEquals(3000.0, own.amount!!, 0.0)
    }

    // ---------------------------------------------------------- questions

    private val known = listOf("Kumar", "Ravi", "Anand Traders")

    @Test
    fun businessQuestionsInTanglishTamilAndEnglish() {
        fun q(text: String) = KaiUnderstanding.understand(text, today, known)
        assertEquals(KaiIntent.PersonBalance("Kumar"), q("Kumar enna tharanum?"))
        assertEquals(KaiIntent.PersonHistory("Kumar", lastPaymentOnly = true), q("Kumar last payment eppo pannaan?"))
        assertEquals(KaiIntent.PersonHistory("Kumar", lastPaymentOnly = false), q("Kumar history sollu"))
        assertEquals(KaiIntent.WhoOwesMe, q("Yaar kitta money collect pannanum?"))
        assertEquals(KaiIntent.WhomDoIOwe, q("Yaarukku naan money kudukkanum?"))
        assertEquals(KaiIntent.DueToday, q("Innaikku enna payment due?"))
        assertEquals(KaiIntent.WeekCollection, q("Indha week collection evlo?"))
        assertEquals(KaiIntent.TotalPending, q("Enakku total pending evlo?"))
        assertEquals(KaiIntent.Briefing, q("Today enna important?"))
        assertTrue(q("Last month sales epdi?") is KaiIntent.OpenQuestion)
        assertTrue(q("Expenses adhigama irukka?") is KaiIntent.OpenQuestion)
        assertEquals(KaiIntent.WhoOwesMe, q("Who owes me money?"))
    }

    @Test
    fun languageFollowsTheOwner() {
        assertEquals(KaiLang.TANGLISH, KaiLanguage.detect("Kumar kitta 3000 vanganum"))
        assertEquals(KaiLang.ENGLISH, KaiLanguage.detect("Kumar owes me 3000"))
        assertEquals(KaiLang.TAMIL, KaiLanguage.detect("குமார் கிட்ட 3000 வாங்கணும்"))
    }

    // -------------------------------------------------------------- memory

    private val ledger = BusinessSnapshot(
        customers = listOf(
            PartySummary("c1", "Kumar", null, 3000.0, "2026-10-10T00:00:00Z"),
            PartySummary("c2", "Ravi", null, 5500.0, "2026-10-01T00:00:00Z"),
            PartySummary("c3", "Selvam", null, 0.0, null),
        ),
        suppliers = listOf(PartySummary("s1", "Anand Traders", null, 8000.0, "2026-09-30T00:00:00Z")),
        reminders = listOf(ReminderItem("r1", "COLLECTION", "Kumar", 3000.0, "2026-10-10T00:00:00Z", false)),
        cashFlow = CashFlowSummary(8500.0, 8000.0, 8500.0, 8000.0, 500.0, CashFlowWindow(7, 5500.0, 8000.0, 0.0), CashFlowWindow(30, 8500.0, 8000.0, 0.0), ""),
    )

    @Test
    fun answersComeFromTheLedger() {
        val kumar = KaiResponder.personBalance("Kumar", ledger.find("Kumar"), KaiLang.TANGLISH, today)
        assertEquals("Owner, Kumar kitta ₹3,000 pending irukku. October 10th due.", kumar.display)
        assertTrue(kumar.speech.contains("3000 ரூபாய்"))

        val english = KaiResponder.personBalance("Kumar", ledger.find("Kumar"), KaiLang.ENGLISH, today)
        assertEquals("Owner, Kumar owes you ₹3,000, due October 10th.", english.display)
        assertEquals("en-IN", english.speechLanguage)

        val stranger = KaiResponder.personBalance("Muthu", ledger.find("Muthu"), KaiLang.TANGLISH, today)
        assertTrue(stranger.display.contains("Muthu-nu yaarum"))

        val nothing = KaiResponder.personBalance("Selvam", ledger.find("Selvam"), KaiLang.TANGLISH, today)
        assertTrue(nothing.display.contains("pending edhuvum illa"))
    }

    @Test
    fun collectionsPaymentsAndTotals() {
        assertEquals(listOf("Ravi", "Kumar"), ledger.owesMe().map { it.name })
        assertEquals(listOf("Anand Traders"), ledger.iOwe().map { it.name })
        assertTrue(KaiResponder.whoOwesMe(ledger, KaiLang.TANGLISH, today).display.startsWith("Owner, mothama ₹8,500 collect pannanum. Ravi ₹5,500"))
        assertTrue(KaiResponder.dueToday(ledger, KaiLang.ENGLISH, today).display.contains("pay Anand Traders ₹8,000"))
        assertEquals("Owner, indha week ₹5,500 collection varanum.", KaiResponder.weekCollection(ledger, KaiLang.TANGLISH, today).display)
    }

    @Test
    fun dailyBriefUsesRealDataOnly() {
        val brief = KaiResponder.briefing(ledger, KaiLang.TANGLISH, today, LocalTime.of(9, 0)).display
        assertTrue(brief, brief.startsWith("Good morning Owner."))
        assertTrue(brief, brief.contains("Ravi ₹5,500 — naalaikku"))
        assertTrue(brief, brief.contains("Supplier payment ₹8,000 pending"))
        val empty = KaiResponder.briefing(BusinessSnapshot(), KaiLang.TANGLISH, today, LocalTime.of(9, 0)).display
        assertEquals("Good morning Owner. Innaikku avasaram edhuvum illa.", empty)
    }

    // --------------------------------------------------------- after saving

    @Test
    fun savedReplyUsesTheSavedAmountAndOnlyARealReminder() {
        val due = LocalDate.of(2026, 10, 10)
        val withReminder = KaiResponder.recorded("Kumar", 3000.0, RECEIVABLE, due, ledger.hasReminderFor(3000.0, due), KaiLang.TANGLISH, today)
        assertEquals("Owner, Kumar kitta ₹3,000 receive panna pending irukku. October 10th due. Save panniten. Naan reminder vachiruken.", withReminder.display)
        val noReminder = KaiResponder.recorded("Kumar", 3000.0, RECEIVABLE, due, ledger.hasReminderFor(2000.0, due), KaiLang.TANGLISH, today)
        assertFalse(noReminder.display.contains("reminder"))
        assertFalse(noReminder.display.contains("2,000"))
    }

    @Test
    fun clarificationAsksForTheMissingPiece() {
        assertEquals("Owner, Kumar-ku evlo amount?", KaiResponder.clarify(record("Kumar kitta pending"), KaiLang.TANGLISH).display)
        assertTrue(KaiResponder.clarify(record("Kumar 3000"), KaiLang.TANGLISH).display.contains("tharanuma, illa"))
    }
}
