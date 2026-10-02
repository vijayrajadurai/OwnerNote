package com.shopai.app.util

import com.shopai.app.util.ReviewIssue.AMOUNT_AMBIGUOUS
import com.shopai.app.util.ReviewIssue.AMOUNT_MISSING
import com.shopai.app.util.ReviewIssue.AMOUNT_UNCLEAR
import com.shopai.app.util.ReviewIssue.DATE_MISSING
import com.shopai.app.util.ReviewIssue.NAME_UNCLEAR
import com.shopai.app.util.ReviewIssue.TYPE_UNKNOWN
import com.shopai.app.util.ReviewIssue.YEAR_ASSUMED
import com.shopai.app.util.TxnDirection.CREDIT
import com.shopai.app.util.TxnDirection.DEBIT
import com.shopai.app.util.TxnDirection.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class HandwrittenTransactionParserTest {
    private val today = LocalDate.of(2026, 9, 28)

    private fun parse(vararg pages: String) =
        HandwrittenTransactionParser.parse(pages.map { page -> page.trimIndent().lines().map { OcrLine(it) } }, today)

    private fun rupees(v: String) = BigDecimal(v).setScale(2)

    // 1. One person + one amount
    @Test
    fun onePersonOneAmount() {
        val rows = parse("Ravi - 500 - 10/10/2026 - give").transactions
        assertEquals(1, rows.size)
        with(rows.single()) {
            assertEquals("Ravi", personName)
            assertEquals(rupees("500"), amount)
            assertEquals(LocalDate.of(2026, 10, 10), date)
            assertEquals(DEBIT, direction)
            assertFalse(needsReview)
        }
    }

    // 2, 4, 5, 6, 7. Multiple people, credit + debit, different dates
    @Test
    fun specExampleGivesOneRowPerPerson() {
        val rows = parse(
            """
            Ravi - 500 - 10/10/2026 - give
            Kumar - 1200 - 12/10/2026 - receive
            Priya - 750 - 15/10/2026 - give
            Suresh - 2000 - 20/10/2026 - receive
            """,
        ).transactions
        assertEquals(listOf("Ravi", "Kumar", "Priya", "Suresh"), rows.map { it.personName })
        assertEquals(listOf("500.00", "1200.00", "750.00", "2000.00"), rows.map { it.amount!!.toPlainString() })
        assertEquals(listOf(DEBIT, CREDIT, DEBIT, CREDIT), rows.map { it.direction })
        assertEquals(listOf(10, 12, 15, 20), rows.map { it.date!!.dayOfMonth })
        assertTrue(rows.none { it.needsReview })
    }

    @Test
    fun fivePeopleWithYearlessDatesGiveFiveRows() {
        val rows = parse(
            """
            Ravi 500 10/10 give
            Kumar 1000 11/10 receive
            Priya 250 12/10 give
            Arun 3000 15/10 receive
            Bala 750 18/10 give
            """,
        ).transactions
        assertEquals(5, rows.size)
        // Year missing → this year, flagged (not blocking).
        assertTrue(rows.all { it.date!!.year == 2026 && YEAR_ASSUMED in it.issues })
    }

    // 3. Same person, multiple transactions → separate rows
    @Test
    fun samePersonTwiceStaysTwoRows() {
        val rows = parse("Ravi 500 10/10 give\nRavi 1000 20/10 give").transactions
        assertEquals(2, rows.size)
        assertEquals(listOf("500.00", "1000.00"), rows.map { it.amount!!.toPlainString() })
    }

    @Test
    fun twoPeopleOnOneLineAreSplit() {
        val rows = parse("Ravi 500 10/10 give Kumar 300 11/10 receive").transactions
        assertEquals(listOf("Ravi" to DEBIT, "Kumar" to CREDIT), rows.map { it.personName to it.direction })
    }

    // Credit / Debit meaning, English
    @Test
    fun englishDirectionFromMeaning() {
        assertEquals(DEBIT, parse("Give Kumar 1000").transactions.single().direction)
        assertEquals(DEBIT, parse("Pay Priya 750").transactions.single().direction)
        assertEquals(DEBIT, parse("Kumar - 1000 - payable").transactions.single().direction)
        assertEquals(CREDIT, parse("Receive from Kumar 1000").transactions.single().direction)
        assertEquals(CREDIT, parse("Priya owes me 750").transactions.single().direction)
        assertEquals(CREDIT, parse("Kumar - 1000 - receivable").transactions.single().direction)
        assertEquals(CREDIT, parse("Ravi will give 500").transactions.single().direction)
        assertEquals("Kumar", parse("Receive from Kumar 1000").transactions.single().personName)
        assertEquals("Priya", parse("Priya owes me 750").transactions.single().personName)
    }

    // 11, 13. Tamil / mixed: meaning depends on who gives to whom
    @Test
    fun tamilDirectionFromRelationship() {
        with(parse("Raviக்கு ₹500 கொடுக்கணும்").transactions.single()) {
            assertEquals(DEBIT, direction)
            assertEquals("Ravi", personName)
        }
        with(parse("Ravi ₹500 கொடுக்கணும்").transactions.single()) {
            assertEquals(CREDIT, direction)
            assertEquals("Ravi", personName)
        }
        assertEquals(DEBIT, parse("Kumarக்கு 1000 தர வேண்டும்").transactions.single().direction)
        with(parse("Kumar எனக்கு 1000 தர வேண்டும்").transactions.single()) {
            assertEquals(CREDIT, direction)
            assertEquals("Kumar", personName)
        }
        assertEquals(CREDIT, parse("முருகன் 500 வாங்கணும்").transactions.single().direction)
        assertEquals("முருகன்", parse("முருகன் 500 வாங்கணும்").transactions.single().personName)
    }

    @Test
    fun ambiguousOrSettledDirectionIsUnknown() {
        // No direction word at all.
        with(parse("Ravi 500 10/10").transactions.single()) {
            assertEquals(UNKNOWN, direction)
            assertTrue(TYPE_UNKNOWN in issues)
            assertTrue(needsReview)
        }
        // Both give and receive.
        assertEquals(UNKNOWN, parse("Ravi 500 give receive").transactions.single().direction)
        // Already paid / received: not an open amount either way.
        assertEquals(UNKNOWN, parse("Ravi 500 paid").transactions.single().direction)
        assertEquals(UNKNOWN, parse("Ravi 500 கொடுத்தேன்").transactions.single().direction)
    }

    // Headings apply to the lines below them
    @Test
    fun headingDateAndDirectionApplyToFollowingLines() {
        val rows = parse(
            """
            Date: 10/10/2026
            To give:
            Ravi 500
            Kumar 300
            To receive:
            Priya 200
            """,
        ).transactions
        assertEquals(listOf(DEBIT, DEBIT, CREDIT), rows.map { it.direction })
        assertTrue(rows.all { it.date == LocalDate.of(2026, 10, 10) })
    }

    @Test
    fun tamilHeadingGiveMeansIGive() {
        val rows = parse("கொடுக்க வேண்டியது:\nRavi 500\nவாங்க வேண்டியது:\nKumar 300").transactions
        assertEquals(listOf(DEBIT, CREDIT), rows.map { it.direction })
    }

    // 8. Missing date
    @Test
    fun missingDateIsNotSpecifiedButNotBlocking() {
        with(parse("Ravi 500 give").transactions.single()) {
            assertNull(date)
            assertTrue(DATE_MISSING in issues)
            assertFalse(needsReview)
        }
    }

    // 9, 18 (spec). Missing / unreadable amount is never invented
    @Test
    fun unreadableAmountIsLeftForReview() {
        // No amount could be read: no transaction is created, the line is kept.
        val result = parse("Ravi ??? 10/10")
        assertTrue(result.transactions.isEmpty())
        assertEquals(listOf("Ravi ??? 10/10"), result.unparsedLines.map { it.second.text })
    }

    @Test
    fun twoPlainNumbersAreAmbiguousNotGuessed() {
        with(parse("Ravi 500 1000 give").transactions.single()) {
            assertNull(amount)
            assertTrue(AMOUNT_AMBIGUOUS in issues)
        }
    }

    // 14. Currency formats
    @Test
    fun amountFormats() {
        fun amountOf(text: String) = parse(text).transactions.single().amount
        assertEquals(rupees("500"), amountOf("Ravi ₹500 give"))
        assertEquals(rupees("500"), amountOf("Ravi Rs.500 give"))
        assertEquals(rupees("500"), amountOf("Ravi Rs 500 give"))
        assertEquals(rupees("500"), amountOf("Ravi 500 ரூபாய் give"))
        assertEquals(rupees("1500"), amountOf("Ravi ₹1,500 give"))
        assertEquals(rupees("1500"), amountOf("Ravi 1.5K give"))
        assertEquals(rupees("2000"), amountOf("Ravi 2K give"))
        assertEquals(rupees("250.50"), amountOf("Ravi 250.50 give"))
        assertEquals(rupees("750"), amountOf("Ravi 750/- give"))
        // The original written form is kept.
        assertEquals("₹1,500", parse("Ravi ₹1,500 give").transactions.single().amountText)
    }

    @Test
    fun currencyMarkedAmountBeatsAPlainNumber() {
        assertEquals(rupees("500"), parse("Ravi room 12 ₹500 give").transactions.single().amount)
    }

    // Date formats
    @Test
    fun dateFormats() {
        fun dateOf(text: String) = parse(text).transactions.single().date
        assertEquals(LocalDate.of(2026, 10, 10), dateOf("Ravi 500 10-10-2026 give"))
        assertEquals(LocalDate.of(2026, 10, 10), dateOf("Ravi 500 10.10.2026 give"))
        assertEquals(LocalDate.of(2026, 10, 10), dateOf("Ravi 500 10 Oct give"))
        assertEquals(LocalDate.of(2026, 10, 10), dateOf("Ravi 500 Oct 10 give"))
        assertEquals(LocalDate.of(2026, 10, 10), dateOf("Ravi 500 10 அக்டோபர் give"))
        // The date's digits are not taken as the amount.
        assertEquals(rupees("500"), parse("Ravi 500 10/10/2026 give").transactions.single().amount)
    }

    // 15. Multiple pages
    @Test
    fun multiplePagesKeepEveryRow() {
        val result = parse("Ravi 500 give\nKumar 300 receive", "Priya 200 give")
        assertEquals(3, result.transactions.size)
        assertEquals(listOf(0, 0, 1), result.transactions.map { it.pageIndex })
    }

    // 16, 17. 10+ and 50+ rows: nothing merged or lost
    @Test
    fun fiftyFivePeopleGiveFiftyFiveRows() {
        val names = (1..55).map { "Person${'A' + (it % 26)}${'a' + (it / 26)}x" }
        val page = names.mapIndexed { i, n -> "$n ${100 + i} ${(i % 28) + 1}/10 ${if (i % 2 == 0) "give" else "receive"}" }
        val rows = HandwrittenTransactionParser.parse(listOf(page.map { OcrLine(it) }), today).transactions
        assertEquals(55, rows.size)
        assertEquals((0 until 55).map { BigDecimal(100 + it).setScale(2) }, rows.map { it.amount })
        assertEquals(names, rows.map { it.personName })
    }

    // 10, 23. Low OCR confidence → Needs Review, value kept, not dropped
    @Test
    fun lowConfidenceWordsAreFlagged() {
        val line = OcrLine(
            text = "Ravl 500 give",
            confidence = 80,
            words = listOf(OcrWord("Ravl", 41), OcrWord("500", 95), OcrWord("give", 90)),
        )
        with(HandwrittenTransactionParser.parse(listOf(listOf(line)), today).transactions.single()) {
            assertEquals("Ravl", personName)
            assertEquals(41, nameConfidence)
            assertEquals(95, amountConfidence)
            assertTrue(NAME_UNCLEAR in issues)
            assertFalse(AMOUNT_UNCLEAR in issues)
            assertTrue(needsReview)
        }
    }

    @Test
    fun unreadableLinesAreReturnedNotDropped() {
        val result = parse("Ravi 500 give\n@@ ## xx\nTotal 5000")
        assertEquals(1, result.transactions.size)
        assertEquals(listOf("@@ ## xx", "Total 5000"), result.unparsedLines.map { it.second.text })
    }

    @Test
    fun descriptionKeepsExtraWords() {
        with(parse("Ravi 500 give for rice").transactions.single()) {
            assertEquals("Ravi", personName)
            assertEquals("rice", description)
        }
    }

    @Test
    fun rupeeFormatting() {
        assertEquals("₹1,500.00", formatRupees(BigDecimal("1500")))
        assertEquals("₹1,24,000.00", formatRupees(BigDecimal("124000")))
    }
}
