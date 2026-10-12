package com.shopai.app.util

import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.chat.KaiStatement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PartyStatementTest {
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

    // Kumar: ₹5,000 on 1 Sep (paid 25 Sep), ₹3,000 on 20 Sep (paid 6 Oct), ₹4,000 on 1 Oct due 8 Oct (not paid).
    private val kumar = listOf(
        StatementTxn(5000.0, 5000.0, null, d(9, 1), d(9, 15), listOf(StatementPayment(5000.0, d(9, 25), null))),
        StatementTxn(3000.0, 3000.0, "Rice 2 bags", d(9, 20), d(9, 30), listOf(StatementPayment(3000.0, d(10, 6), "GPay"))),
        StatementTxn(4000.0, 0.0, null, d(10, 1), d(10, 8), emptyList()),
    )

    @Test fun wholeHistoryRunsToThePendingBalance() {
        val st = partyStatement("Kumar", null, true, kumar, null, d(10, 12), d(10, 12))
        assertEquals(listOf(5000.0, 3000.0, 0.0, 4000.0, 0.0), st.rows.map { it.given })
        assertEquals(listOf(5000.0, 8000.0, 3000.0, 7000.0, 4000.0), st.rows.map { it.balance })
        assertEquals(12000.0, st.given, 0.001)
        assertEquals(8000.0, st.paid, 0.001)
        assertEquals(4000.0, st.closing, 0.001)
        assertEquals(0.0, st.opening, 0.001)
        // Due 8 Oct, made on 12 Oct: overdue.
        assertEquals(4000.0, st.overdue, 0.001)
        assertNull(st.nextDue)
        assertEquals("GPay", st.rows[4].label)
        assertEquals("Rice 2 bags", st.rows[1].label)
    }

    @Test fun aPeriodStartsFromTheBalanceBeforeIt() {
        // October only: ₹3,000 was pending at the end of September.
        val st = partyStatement("Kumar", null, true, kumar, d(10, 1), d(10, 31), d(10, 5))
        assertEquals(3000.0, st.opening, 0.001)
        assertEquals(listOf(d(10, 1), d(10, 6)), st.rows.map { it.date })
        assertEquals(4000.0, st.given, 0.001)
        assertEquals(3000.0, st.paid, 0.001)
        assertEquals(4000.0, st.closing, 0.001)
        assertEquals(0.0, st.overdue, 0.001)
        assertEquals(d(10, 8), st.nextDue)
        // September: what came after 30 Sep is not in it.
        val sep = partyStatement("Kumar", null, true, kumar, d(9, 1), d(9, 30), d(10, 12))
        assertEquals(3000.0, sep.closing, 0.001)
        assertEquals(3, sep.rows.size)
    }

    @Test fun whatsAppNumbers() {
        assertEquals("919840012345", whatsappNumber("98400 12345"))
        assertEquals("919840012345", whatsappNumber("09840012345"))
        assertEquals("919840012345", whatsappNumber("+91 98400-12345"))
        assertEquals("971501234567", whatsappNumber("+971 50 123 4567"))
        // Not a mobile number Kai can be sure of: the owner picks the contact in WhatsApp.
        assertNull(whatsappNumber("044 2345 6789"))
        assertNull(whatsappNumber("12345"))
        assertNull(whatsappNumber(null))
    }

    @Test fun theWhatsAppMessage() {
        val st = partyStatement("Kumar", "9840012345", true, kumar, null, d(10, 12), d(10, 12))
        val tl = KaiStatement.caption(st, "Anbu Stores", KaiLang.TANGLISH)
        assertTrue(tl, tl.contains("Kumar") && tl.contains("₹4,000") && tl.contains("Anbu Stores"))
        assertTrue(KaiStatement.caption(st, "", KaiLang.TAMIL).contains("பாக்கி: ₹4,000"))
        assertTrue(KaiStatement.caption(st, "", KaiLang.ENGLISH).contains("Balance due: ₹4,000"))
    }
}
