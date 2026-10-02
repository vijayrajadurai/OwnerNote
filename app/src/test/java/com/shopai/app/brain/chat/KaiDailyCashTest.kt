package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.data.model.DailyCashTotals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/** Kai reading the Daily Cash Note — read only, from the app's own figures. */
class KaiDailyCashTest {
    private val today = LocalDate.of(2026, 9, 30)

    // Opening ₹50,000; cash in ₹800; UPI out ₹8,007 → net −₹7,207; Kallapetti ₹50,800.
    private val totals = DailyCashTotals(
        totalIn = 800.0, totalOut = 8007.0, net = -7207.0,
        upiIn = 0.0, upiOut = 8007.0, upiNet = -8007.0,
        cashIn = 800.0, cashOut = 0.0, cashNet = 800.0,
    )

    private class Books(val note: CashNoteView?, val ledgerUp: Boolean = true) : KaiBooks {
        var cashNoteReads = 0
        override suspend fun snapshot(): BusinessSnapshot? = if (ledgerUp) BusinessSnapshot() else null
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate): CashBookTotals? = null
        override suspend fun cashNote(day: LocalDate): CashNoteView? { cashNoteReads++; return note }
    }

    private fun note(closed: Boolean = false, opening: Double? = 50_000.0, t: DailyCashTotals = totals, entries: Int = 2) =
        CashNoteView(today, entries, t, opening, opening?.let { it + t.cashNet }, closed)

    private fun ask(books: KaiBooks, text: String) = runBlocking { KaiBusinessBrain(books, { today }, Random(3)).ask(text) }

    @Test
    fun cashQuestionsInTanglishTamilEnglishAreDailyCash() {
        fun q(t: String) = KaiChatUnderstanding.understand(t, today, emptyList())
        val expect = mapOf(
            "inniku cash flow epdi irukku?" to CashAsk.FLOW,
            "today cash situation sollu" to CashAsk.FLOW,
            "inniku evlo cash vandhudhu?" to CashAsk.CASH_IN,
            "cash la evlo in?" to CashAsk.CASH_IN,
            "cash la evlo out?" to CashAsk.CASH_OUT,
            "UPI la enna nadandhudhu?" to CashAsk.UPI,
            "kalla petti la ippo evlo irukku?" to CashAsk.KALLAPETTI,
            "opening balance evlo?" to CashAsk.OPENING,
            "inniku total in evlo?" to CashAsk.TOTAL_IN,
            "total out evlo?" to CashAsk.TOTAL_OUT,
            "net amount evlo?" to CashAsk.NET,
            "inniku enna important?" to CashAsk.IMPORTANT,
            "day close epdi irukku?" to CashAsk.DAY_CLOSE,
            "கல்லா பெட்டியில இப்போ எவ்வளவு?" to CashAsk.KALLAPETTI,
        )
        for ((text, ask) in expect) {
            val query = q(text)
            assertEquals(text, ChatIntent.DAILY_CASH, query.intent)
            assertEquals(text, ask, query.cashAsk)
        }
        // "Who owes me cash" is still about customers, not the cash note.
        assertEquals(ChatIntent.UPCOMING_COLLECTIONS, q("Yaaru enakku cash tharanum?").intent)
    }

    @Test
    fun cashFlowIsExplainedNotJustRepeated() {
        val r = ask(Books(note()), "Kai, inniku cash flow epdi irukku?")
        assertTrue(r.text, r.text.contains("₹800 cash in") && r.text.contains("UPI-la ₹8,007 out"))
        assertTrue(r.text, r.text.contains("net ₹7,207 negative") && r.text.contains("in-a vida out adhigam"))
    }

    @Test
    fun eachFigureFromTheNote() {
        val b = Books(note())
        assertTrue(ask(b, "Inniku evlo cash vandhudhu?").text.contains("₹800"))
        assertTrue(ask(b, "UPI la enna nadandhudhu?").text.contains("₹8,007 out"))
        assertTrue(ask(b, "inniku total in evlo?").text.contains("₹800"))
        assertTrue(ask(b, "total out evlo?").text.contains("₹8,007"))
        assertTrue(ask(b, "net amount evlo?").text.contains("₹7,207 negative"))
        assertTrue(ask(b, "opening balance evlo?").text.contains("₹50,000"))
        assertTrue(ask(b, "cash la evlo out?").text.contains("cash out edhuvum illa"))
    }

    @Test
    fun kallapettiUsesTheExistingBoxFigure() {
        val r = ask(Books(note()), "Kalla petti la ippo evlo?")
        assertTrue(r.text, r.text.contains("opening ₹50,000") && r.text.contains("₹50,800") && r.text.contains("UPI"))
        // No opening set: never guessed.
        val none = ask(Books(note(opening = null)), "Kalla petti la ippo evlo?")
        assertTrue(none.text, none.text.contains("opening balance innum set pannala") && !none.text.contains("₹"))
    }

    @Test
    fun dayCloseStatus() {
        assertTrue(ask(Books(note(closed = false)), "day close epdi irukku?").text.contains("innum day close pannala"))
        assertTrue(ask(Books(note(closed = true)), "day close epdi irukku?").text.contains("day close aayiduchu"))
    }

    @Test
    fun noEntriesAndUnavailableData() {
        val empty = DailyCashTotals(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertTrue(ask(Books(note(t = empty, entries = 0)), "inniku cash flow epdi irukku?").text.contains("innum entry edhuvum illa"))
        assertEquals("Owner, indha information Daily Cash Note-la available illa.", ask(Books(null), "inniku cash flow epdi irukku?").text)
    }

    @Test
    fun theCashNoteAnswersEvenWhenTheLedgerIsOffline() {
        val r = ask(Books(note(), ledgerUp = false), "Inniku evlo cash vandhudhu?")
        assertTrue(r.text, r.text.contains("₹800"))
    }

    @Test
    fun englishAndTamilReplies() {
        val en = ask(Books(note()), "How is today's cash flow?")
        assertTrue(en.text, en.text.contains("more went out than came in"))
        val ta = ask(Books(note()), "இன்னைக்கு கல்லா பெட்டியில எவ்வளவு?")
        assertTrue(ta.text, ta.text.contains("₹50,800"))
    }
}
