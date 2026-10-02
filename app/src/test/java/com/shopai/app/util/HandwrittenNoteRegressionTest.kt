package com.shopai.app.util

import com.shopai.app.util.TxnDirection.CREDIT
import com.shopai.app.util.TxnDirection.DEBIT
import com.shopai.app.util.TxnDirection.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The "28/09/26 · Kumar 2000 Credit · Pamba 8000 Debit" note: a page date,
 * exactly two transactions, and nothing from the date or background marks.
 */
class HandwrittenNoteRegressionTest {
    private val today = LocalDate.of(2026, 9, 29)
    private val noteDate = LocalDate.of(2026, 9, 28)

    private fun parse(text: String) =
        HandwrittenTransactionParser.parse(listOf(text.trimIndent().lines().map { OcrLine(it, 80) }), today)

    private fun rupees(v: String) = BigDecimal(v).setScale(2)

    // TEST 1
    @Test
    fun dateAndTwoTransactionsGiveExactlyTwoRows() {
        val rows = parse(
            """
            28/09/26
            Kumar 2000 Credit
            Pamba 8000 Debit
            """,
        ).transactions
        assertEquals(2, rows.size)
        with(rows[0]) {
            assertEquals("Kumar", personName)
            assertEquals(rupees("2000"), amount)
            assertEquals(CREDIT, direction)
            assertEquals(noteDate, date)
        }
        with(rows[1]) {
            assertEquals("Pamba", personName)
            assertEquals(rupees("8000"), amount)
            assertEquals(DEBIT, direction)
            assertEquals(noteDate, date)
        }
        assertTrue(rows.none { it.needsReview })
    }

    // TEST 2
    @Test
    fun aDateAloneIsNotATransaction() {
        val result = parse("28/09/26")
        assertTrue(result.transactions.isEmpty())
        assertTrue(result.unparsedLines.isEmpty())
    }

    @Test
    fun everyWrittenDateFormatIsAHeaderNeverAnAmount() {
        for (date in listOf("28/09/26", "28/09/2026", "28-09-26", "28-09-2026", "28.09.26", "28.09.2026", "28 09 26")) {
            val result = parse("$date\nKumar 2000 Credit")
            assertEquals(date, 1, result.transactions.size)
            assertEquals(date, rupees("2000"), result.transactions.single().amount)
            assertEquals(date, noteDate, result.transactions.single().date)
            assertTrue(date, result.unparsedLines.isEmpty())
        }
    }

    // TEST 3
    @Test
    fun garbledDateDigitsAreNotATransactionOrAmount() {
        for (garbled in listOf("2373426", "280926", "28 0926")) {
            val result = parse(garbled)
            assertTrue(garbled, result.transactions.isEmpty())
            // Kept (listed, collapsed) but never pre-filled as an amount.
            val prefill = HandwrittenTransactionParser.prefill(OcrLine(garbled, 80), today)
            assertNull(garbled, prefill.amount)
        }
    }

    // TEST 4
    @Test
    fun kumarCredit() {
        with(parse("Kumar 2000 Credit").transactions.single()) {
            assertEquals("Kumar", personName)
            assertEquals(rupees("2000"), amount)
            assertEquals(CREDIT, direction)
        }
    }

    // TEST 5
    @Test
    fun pambaDebit() {
        with(parse("Pamba 8000 Debit").transactions.single()) {
            assertEquals("Pamba", personName)
            assertEquals(rupees("8000"), amount)
            assertEquals(DEBIT, direction)
        }
    }

    // TEST 6
    @Test
    fun nameAndAmountWithoutTypeIsAPartialTransaction() {
        with(parse("Kumar 2000").transactions.single()) {
            assertEquals("Kumar", personName)
            assertEquals(rupees("2000"), amount)
            assertEquals(UNKNOWN, direction)
            assertTrue(ReviewIssue.TYPE_UNKNOWN in issues)
            assertTrue(needsReview)
        }
    }

    // TEST 7
    @Test
    fun backgroundGarbageLinesAreIgnored() {
        val lines = listOf(
            OcrLine("28/09/26", 80),
            OcrLine("Kumar 2000 Credit", 80),
            OcrLine("Pamba 8000 Debit", 80),
            // Bleed-through / notebook marks as OCR reports them.
            OcrLine("", 0),
            OcrLine("~ - ..", 12),
            OcrLine("Il |", 20),
            OcrLine("o 0", 15),
            OcrLine("Esc", 40),
            OcrLine("ab 12", 5),
        )
        val result = HandwrittenTransactionParser.parse(listOf(lines), today)
        assertEquals(2, result.transactions.size)
        assertEquals(listOf("Kumar", "Pamba"), result.transactions.map { it.personName })
    }

    @Test
    fun commonHandwritingSlipsOfCreditAndDebit() {
        for (word in listOf("credit", "Credit", "creait", "credi", "credt")) {
            assertEquals(word, CREDIT, parse("Kumar 2000 $word").transactions.single().direction)
        }
        for (word in listOf("debit", "Debit", "debi", "deblt")) {
            assertEquals(word, DEBIT, parse("Pamba 8000 $word").transactions.single().direction)
            assertEquals(word, "Pamba", parse("Pamba 8000 $word").transactions.single().personName)
        }
        // "Debi" before the amount is still a name.
        assertEquals("Debi", parse("Debi 500 give").transactions.single().personName)
    }

    @Test
    fun ocrDigitSlipsInTheAmountAreRepairedButFlagged() {
        with(parse("Kumar 2OOO Credit").transactions.single()) {
            assertEquals(rupees("2000"), amount)
            assertEquals(CREDIT, direction)
            assertTrue(ReviewIssue.AMOUNT_UNCLEAR in issues)
        }
    }

    @Test
    fun theReadingWithTransactionStructureWinsOverMoreText() {
        val structured = listOf(OcrLine("Kumar 2000 Credit", 60))
        val noisy = listOf(OcrLine("Esc FnLock obit Lumo uma 2373426 keyboard", 90))
        assertTrue(
            HandwrittenTransactionParser.readingScore(structured, today) >
                HandwrittenTransactionParser.readingScore(noisy, today),
        )
        val partial = listOf(OcrLine("Kumar 2000", 60))
        assertTrue(
            HandwrittenTransactionParser.readingScore(structured, today) >
                HandwrittenTransactionParser.readingScore(partial, today),
        )
    }
}
