package com.shopai.app.util

import com.shopai.app.util.ReviewIssue.AMOUNT_MISSING
import com.shopai.app.util.ReviewIssue.AMOUNT_UNCLEAR
import com.shopai.app.util.ReviewIssue.DATE_MISSING
import com.shopai.app.util.ReviewIssue.NAME_UNCLEAR
import com.shopai.app.util.TxnDirection.CREDIT
import com.shopai.app.util.TxnDirection.DEBIT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Handwritten-OCR fix. Fixture: the real note "28/09/26 · Kumar - 2000
 * Debit · Arun - 500 Credit · Praba - 2000 Debit". These values are test
 * data only — nothing is hard-coded in the parser.
 */
class HandwrittenOcrFixTest {
    private val today = LocalDate.of(2026, 9, 28)

    private fun parse(text: String) =
        HandwrittenTransactionParser.parse(listOf(text.trimIndent().lines().map { OcrLine(it) }), today)

    private fun rupees(v: String) = BigDecimal(v).setScale(2)

    // 1, 5, 12. Date + 3 entries, mixed debit/credit, multiple lines
    @Test
    fun realNoteFixtureAsWritten() {
        val rows = parse(
            """
            28/09/26
            Kumar - 2000 Debit
            Arun - 500 Credit
            Praba - 2000 Debit
            """,
        ).transactions
        assertEquals(listOf("Kumar", "Arun", "Praba"), rows.map { it.personName })
        assertEquals(listOf(rupees("2000"), rupees("500"), rupees("2000")), rows.map { it.amount })
        assertEquals(listOf(DEBIT, CREDIT, DEBIT), rows.map { it.direction })
        assertTrue(rows.all { it.date == LocalDate.of(2026, 9, 28) })
        assertTrue(rows.none { it.needsReview })
    }

    // 2, 9. No date: rows still produced, date left empty (not invented)
    @Test
    fun noDateStillGivesRows() {
        val rows = parse("Kumar - 2000 Debit\nArun - 500 Credit").transactions
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.date == null && DATE_MISSING in it.issues && !it.needsReview })
    }

    // 3, 4. Debit only / credit only
    @Test
    fun debitOnlyAndCreditOnly() {
        assertTrue(parse("Kumar 2000 Debit\nPraba 2000 Debit").transactions.all { it.direction == DEBIT })
        assertTrue(parse("Arun 500 Credit\nSelvi 300 credit").transactions.all { it.direction == CREDIT })
    }

    // 6, 7, 8. ₹ symbol, no symbol, different spacing / separators
    @Test
    fun formatsAndSpacing() {
        for (text in listOf("Kumar - 2000 Debit", "Kumar 2000 debit", "Kumar - ₹2000 debit", "Kumar-2000-Debit", "  Kumar     2000      DEBIT  ", "Kumar 2000 D", "Kumar 2000 Dr")) {
            with(parse(text).transactions.single()) {
                assertEquals(text, "Kumar", personName)
                assertEquals(text, rupees("2000"), amount)
                assertEquals(text, DEBIT, direction)
            }
        }
        with(parse("Arun 500 C").transactions.single()) { assertEquals(CREDIT, direction) }
    }

    // OCR slips in the type word
    @Test
    fun misreadDebitCreditWords() {
        assertEquals(DEBIT, parse("Kumar 2000 Debt").transactions.single().direction)
        assertEquals(DEBIT, parse("Kumar 2000 Dbit").transactions.single().direction)
        assertEquals(CREDIT, parse("Arun 500 Credt").transactions.single().direction)
        assertEquals(CREDIT, parse("Arun 500 Crdit").transactions.single().direction)
        // A short name like "Debi" is not taken as "Debit".
        assertEquals("Debi", parse("Debi 500 give").transactions.single().personName)
    }

    // 10. Unclear amount: "2OOO" becomes 2000 but is flagged, never silently trusted
    @Test
    fun ocrLetterInAmountIsRepairedButFlagged() {
        for (text in listOf("Kumar - 2OOO Debit", "Kumar 200O Debit", "Kumar 2o00 Debit", "Kumar l000 Debit")) {
            with(parse(text).transactions.single()) {
                assertEquals(text, "Kumar", personName)
                assertTrue(text, amount == rupees("2000") || amount == rupees("1000"))
                assertTrue(text, AMOUNT_UNCLEAR in issues)
                assertTrue(text, needsReview)
            }
        }
        // A clean amount is not flagged.
        assertFalse(AMOUNT_UNCLEAR in parse("Kumar 2000 Debit").transactions.single().issues)
    }

    @Test
    fun namesAreNeverTurnedIntoNumbers() {
        // "Oli", "Sil", "Bob" have no real digits, so they stay words.
        assertEquals("Oli", parse("Oli 300 give").transactions.single().personName)
        assertEquals("Bob", parse("Bob 300 give").transactions.single().personName)
    }

    // 11. Unclear person name (low OCR confidence) → flagged, value kept
    @Test
    fun unclearNameFromOcrConfidence() {
        val line = OcrLine("AYun - 5000 Credit", 40, words = listOf(OcrWord("AYun", 33), OcrWord("-", 90), OcrWord("5000", 85), OcrWord("Credit", 80)))
        with(HandwrittenTransactionParser.parse(listOf(listOf(line)), today).transactions.single()) {
            assertEquals("AYun", personName)
            assertEquals(rupees("5000"), amount)
            assertEquals(CREDIT, direction)
            assertTrue(NAME_UNCLEAR in issues)
            assertTrue(needsReview)
        }
    }

    // Real ML Kit output shape on the fixture photo: partial words, no row has
    // both a name and an amount. No transaction is invented; every line is kept.
    @Test
    fun partialOcrWithoutNameAndAmountMakesNoTransaction() {
        val result = parse(
            """
            23]09Jel
            200O Debit
            AYan SGos
            2o00
            """,
        )
        assertTrue(result.transactions.isEmpty())
        assertEquals(listOf("23]09Jel", "200O Debit", "AYan SGos", "2o00"), result.unparsedLines.map { it.second.text })
    }

    // 13. Empty page → nothing (the screen then offers Retake / Enter manually)
    @Test
    fun emptyPage() {
        val result = HandwrittenTransactionParser.parse(listOf(emptyList()), today)
        assertTrue(result.transactions.isEmpty())
        assertTrue(result.unparsedLines.isEmpty())
    }

    @Test
    fun missingAmountIsNotInvented() {
        // A name and a type but no amount: not a transaction, line kept.
        val result = parse("Kumar Debit")
        assertTrue(result.transactions.isEmpty())
        assertEquals(listOf("Kumar Debit"), result.unparsedLines.map { it.second.text })
    }
}
